/*
 * edt-bridge - a 1C:EDT bridge that exposes the live EDT model over MCP.
 * Copyright 2026 edt-bridge contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.keyfire.edtbridge.core;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;

/**
 * What a data composition schema text gets wrong that EDT's validation does not report and the
 * platform only shows when the report is built or formed: text that is not a schema at all, a data
 * set with no data source, a data source nobody declared, a settings variant with no structure.
 * Each of these used to cost a build and a run of the form, a minute or two, before it was seen.
 *
 * <p>An error is something the platform cannot use: the text is not a well-formed schema, or a data
 * set has no source to run on. A warning is a schema the platform reads that still does not do what
 * it looks like: a variant without structure outputs the parameters and nothing else, unless code
 * builds the structure when the report runs.
 */
public final class DcsCheck {
    public static final String SCHEMA_NS = "http://v8.1c.ru/8.1/data-composition-system/schema";
    private static final String SETTINGS_NS = "http://v8.1c.ru/8.1/data-composition-system/settings";
    private static final String XSI_NS = "http://www.w3.org/2001/XMLSchema-instance";

    /** One finding: error or warning, what is wrong, and where when the text says (line and column from 1, else 0). */
    public record Problem(String severity, String message, int line, int column) {
        public boolean error() { return "error".equals(severity); }
    }

    /** The findings and whether the text is well-formed XML with the schema root. */
    public record Inspection(List<Problem> problems, boolean schemaXml) { }

    private DcsCheck() { }

    /** The findings about a schema text, errors first in the order they occur; empty for a schema with none. */
    public static List<Problem> check(String text) {
        return inspect(text).problems();
    }

    public static Inspection inspect(String text) {
        List<Problem> problems = new ArrayList<>();
        if (text == null || text.isBlank()) {
            problems.add(new Problem("error", "the schema text is empty", 0, 0));
            return new Inspection(problems, false);
        }
        Document document;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(null);
            document = builder.parse(xmlInput(text));
        } catch (SAXParseException malformed) {
            problems.add(new Problem("error", "not well-formed XML: " + malformed.getMessage(),
                    Math.max(0, malformed.getLineNumber()), Math.max(0, malformed.getColumnNumber())));
            return new Inspection(problems, false);
        } catch (Exception unreadable) {
            problems.add(new Problem("error", "the text cannot be read as XML: " + unreadable.getMessage(), 0, 0));
            return new Inspection(problems, false);
        }
        Element root = document.getDocumentElement();
        if (!"DataCompositionSchema".equals(root.getLocalName()) || !SCHEMA_NS.equals(root.getNamespaceURI())) {
            problems.add(new Problem("error", "the root is " + root.getNodeName() + " in "
                    + root.getNamespaceURI() + ", not DataCompositionSchema in " + SCHEMA_NS, 2, 1));
            return new Inspection(problems, false);
        }
        Set<String> sources = new LinkedHashSet<>();
        for (Element source : children(root, SCHEMA_NS, "dataSource")) {
            String name = childText(source, "name");
            if (!name.isEmpty()) sources.add(name);
        }
        List<Problem> warnings = new ArrayList<>();
        for (Element set : children(root, SCHEMA_NS, "dataSet")) {
            checkDataSet(text, set, sources, problems);
        }
        for (Element variant : children(root, SCHEMA_NS, "settingsVariant")) {
            checkVariant(text, variant, warnings);
        }
        problems.addAll(warnings);
        return new Inspection(problems, true);
    }

    static InputSource xmlInput(String text) {
        // The character reader expects the XML after the BOM. The text used for the edit and hash keeps it.
        return new InputSource(new StringReader(text.startsWith("\uFEFF") ? text.substring(1) : text));
    }

    /** A query or object data set, and the sets inside a union, each need a declared data source. */
    private static void checkDataSet(String text, Element set, Set<String> sources, List<Problem> problems) {
        String type = type(set);
        String name = childText(set, "name");
        if (type.endsWith("DataSetUnion")) {
            for (Element item : children(set, SCHEMA_NS, "item")) checkDataSet(text, item, sources, problems);
            return;
        }
        if (!type.endsWith("DataSetQuery") && !type.endsWith("DataSetObject")) return;
        int line = lineOf(text, "<name>" + DcsEdit.escape(name) + "</name>");
        String source = childText(set, "dataSource");
        if (source.isEmpty()) {
            problems.add(new Problem("error", "data set " + name + " has no dataSource: forming the report stops "
                    + "with \"the data source of the data set is not set\""
                    + (sources.isEmpty() ? "; the schema declares no data source either"
                            : "; the schema declares " + String.join(", ", sources)), line, 0));
        } else if (!sources.contains(source)) {
            problems.add(new Problem("error", "data set " + name + " names data source " + source
                    + ", which the schema does not declare; it declares: "
                    + (sources.isEmpty() ? "none" : String.join(", ", sources)), line, 0));
        }
    }

    /** A settings variant whose settings have no structure item outputs the parameters and nothing else. */
    private static void checkVariant(String text, Element variant, List<Problem> warnings) {
        Element settings = first(variant, SETTINGS_NS, "settings");
        String name = first(variant, SETTINGS_NS, "name") == null ? "" : first(variant, SETTINGS_NS, "name").getTextContent().trim();
        boolean structure = false;
        if (settings != null) {
            for (Element item : children(settings, SETTINGS_NS, "item")) {
                if (type(item).contains("StructureItem")) {
                    structure = true;
                    break;
                }
            }
        }
        if (!structure) {
            warnings.add(new Problem("warning", "settings variant " + (name.isEmpty() ? "(unnamed)" : name)
                    + " has no structure item (a group, a table or a chart): the report outputs only the "
                    + "parameters, unless code builds the structure when it runs; a detail group is "
                    + "<dcsset:item xsi:type=\"dcsset:StructureItemGroup\"> with an auto order and selection",
                    lineOf(text, "<dcsset:name>" + DcsEdit.escape(name) + "</dcsset:name>"), 0));
        }
    }

    private static String type(Element element) {
        String type = element.getAttributeNS(XSI_NS, "type");
        return type == null ? "" : type.trim();
    }

    private static List<Element> children(Element parent, String namespace, String localName) {
        List<Element> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && localName.equals(element.getLocalName())
                    && namespace.equals(element.getNamespaceURI())) {
                result.add(element);
            }
        }
        return result;
    }

    private static Element first(Element parent, String namespace, String localName) {
        List<Element> found = children(parent, namespace, localName);
        return found.isEmpty() ? null : found.get(0);
    }

    private static String childText(Element parent, String localName) {
        Element found = first(parent, SCHEMA_NS, localName);
        return found == null ? "" : found.getTextContent().trim();
    }

    /** The whole number that follows a label in a message, such as "Line number = 14"; 0 when there is none. */
    public static int numberAfter(String message, String label) {
        if (message == null) return 0;
        int at = message.indexOf(label);
        if (at < 0) return 0;
        int start = at + label.length(), end = start;
        while (end < message.length() && Character.isDigit(message.charAt(end))) end++;
        if (end == start || end - start > 9) return 0;
        return Integer.parseInt(message.substring(start, end));
    }

    /** The line of the first occurrence of a piece of text, from 1; 0 when it is not there. */
    private static int lineOf(String text, String piece) {
        int at = text.indexOf(piece);
        if (at < 0) return 0;
        int line = 1;
        for (int i = 0; i < at; i++) if (text.charAt(i) == '\n') line++;
        return line;
    }
}
