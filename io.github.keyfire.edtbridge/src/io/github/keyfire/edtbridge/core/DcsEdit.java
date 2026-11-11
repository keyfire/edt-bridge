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
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/**
 * Additions to the designer XML of a data composition schema, made as text insertions: a field of a
 * data set and a schema parameter. The rest of the file keeps its bytes, so the change reads as the
 * added lines only. The schema has to be in the designer layout - one tab per level, as EDT and the
 * designer write it - and every addition is checked against what the schema already holds.
 */
public final class DcsEdit {
    /** One addition. {@code action} is addField or addParameter; the other fields depend on it. */
    public record Operation(String action, String dataSet, String dataPath, String field, String name,
            String titleRu, String type) { }

    public record Result(String text, List<String> plan) { }

    private static final String ROOT = "DataCompositionSchema";
    private static final String SCHEMA_NS = "http://v8.1c.ru/8.1/data-composition-system/schema";
    /** Root children that follow parameter in the platform's schema of the format. */
    private static final List<String> AFTER_PARAMETER = List.of("template", "groupTemplate", "settingsVariant");
    private static final Pattern IDENT = Pattern.compile("[\\p{L}_][\\p{L}\\p{Nd}_]*");
    private static final Pattern DATA_PATH = Pattern.compile("[\\p{L}_][\\p{L}\\p{Nd}_]*(\\.[\\p{L}_][\\p{L}\\p{Nd}_]*)*");

    private DcsEdit() { }

    public static Result apply(String text, List<Operation> operations) {
        if (text == null) throw new IllegalArgumentException("the schema text is missing");
        if (operations == null || operations.isEmpty()) throw new IllegalArgumentException("edits must name at least one addition");
        String nl = text.contains("\r\n") ? "\r\n" : "\n";
        List<String> plan = new ArrayList<>();
        String current = text;
        for (Operation operation : operations) {
            Document document = parse(current);
            String action = operation.action() == null ? "" : operation.action().trim();
            switch (action) {
                case "addField" -> current = addField(current, document, operation, nl, plan);
                case "addParameter" -> current = addParameter(current, document, operation, nl, plan);
                default -> throw new IllegalArgumentException(AllowedValues.unknownValue("action", action,
                        List.of("addField", "addParameter")));
            }
        }
        parse(current);
        return new Result(current, List.copyOf(plan));
    }

    private static String addField(String text, Document document, Operation op, String nl, List<String> plan) {
        String dataSet = required(op.dataSet(), "dataSet");
        String dataPath = required(op.dataPath(), "dataPath");
        if (!DATA_PATH.matcher(dataPath).matches()) throw new IllegalArgumentException("dataPath is not a valid path: " + dataPath);
        String field = op.field() == null || op.field().isBlank() ? dataPath : op.field().trim();
        Element set = null;
        List<String> sets = new ArrayList<>();
        for (Element candidate : children(document.getDocumentElement(), "dataSet")) {
            String name = childText(candidate, "name");
            sets.add(name);
            if (dataSet.equalsIgnoreCase(name)) set = candidate;
        }
        if (set == null) throw new IllegalArgumentException("data set not found: " + dataSet + "; the schema has: "
                + (sets.isEmpty() ? "no data sets" : String.join(", ", sets)));
        for (Element existing : children(set, "field")) {
            if (dataPath.equalsIgnoreCase(childText(existing, "dataPath"))) {
                throw new IllegalArgumentException("data set " + dataSet + " already has a field with dataPath " + dataPath);
            }
        }
        Matcher block = Pattern.compile("(?s)(?<=\\n)\\t<dataSet\\b[^>]*>\\r?\\n\\t\\t<name>"
                + Pattern.quote(escape(childText(set, "name"))) + "</name>.*?\\n\\t</dataSet>").matcher(text);
        if (!block.find()) throw layout();
        String inside = text.substring(block.start(), block.end());
        int anchor = inside.lastIndexOf("\n\t\t</field>");
        int insertAt = anchor >= 0 ? block.start() + anchor + "\n\t\t</field>".length()
                : block.start() + inside.indexOf("</name>") + "</name>".length();
        StringBuilder add = new StringBuilder();
        add.append(nl).append("\t\t<field xsi:type=\"DataSetFieldField\">");
        add.append(nl).append("\t\t\t<dataPath>").append(escape(dataPath)).append("</dataPath>");
        add.append(nl).append("\t\t\t<field>").append(escape(field)).append("</field>");
        if (op.titleRu() != null && !op.titleRu().isBlank()) add.append(nl).append(title("\t\t\t", op.titleRu(), nl));
        add.append(nl).append("\t\t</field>");
        plan.add("add field " + dataPath + " to data set " + childText(set, "name"));
        return text.substring(0, insertAt) + add + text.substring(insertAt);
    }

    private static String addParameter(String text, Document document, Operation op, String nl, List<String> plan) {
        String name = required(op.name(), "name");
        if (!IDENT.matcher(name).matches()) throw new IllegalArgumentException("parameter name is not an identifier: " + name);
        for (Element existing : children(document.getDocumentElement(), "parameter")) {
            if (name.equalsIgnoreCase(childText(existing, "name"))) {
                throw new IllegalArgumentException("the schema already has a parameter " + name);
            }
        }
        ValueType type = ValueType.parse(op.type());
        int insertAt = -1;
        Matcher last = Pattern.compile("\\n\\t</parameter>").matcher(text);
        while (last.find()) insertAt = last.end();
        if (insertAt < 0) {
            Matcher next = Pattern.compile("\\r?\\n\\t<(" + String.join("|", AFTER_PARAMETER) + ")\\b").matcher(text);
            Matcher end = Pattern.compile("\\r?\\n</" + ROOT + ">").matcher(text);
            if (next.find()) insertAt = next.start();
            else if (end.find()) insertAt = end.start();
        }
        if (insertAt < 0) throw layout();
        StringBuilder add = new StringBuilder();
        add.append(nl).append("\t<parameter>");
        add.append(nl).append("\t\t<name>").append(escape(name)).append("</name>");
        if (op.titleRu() != null && !op.titleRu().isBlank()) add.append(nl).append(title("\t\t", op.titleRu(), nl));
        for (String line : type.valueType()) add.append(nl).append("\t\t").append(line);
        for (String line : type.value()) add.append(nl).append("\t\t").append(line);
        add.append(nl).append("\t\t<useRestriction>false</useRestriction>");
        add.append(nl).append("\t</parameter>");
        plan.add("add parameter " + name + " of type " + type.display());
        return text.substring(0, insertAt) + add + text.substring(insertAt);
    }

    /** A parameter type in the grammar the bridge uses elsewhere, as the schema writes it. */
    record ValueType(String display, List<String> valueType, List<String> value) {
        private static final Pattern STRING = Pattern.compile("(?iu)(string|строка)(\\((\\d+)\\))?");
        private static final Pattern NUMBER = Pattern.compile("(?iu)(number|число)(\\((\\d+)\\s*(,\\s*(\\d+))?\\))?");

        static ValueType parse(String raw) {
            String type = raw == null || raw.isBlank() ? "String" : raw.trim();
            Matcher string = STRING.matcher(type);
            if (string.matches()) {
                String length = string.group(3) == null ? "0" : string.group(3);
                return new ValueType("String(" + length + ")", List.of("<valueType>", "\t<v8:Type>xs:string</v8:Type>",
                        "\t<v8:StringQualifiers>", "\t\t<v8:Length>" + length + "</v8:Length>",
                        "\t\t<v8:AllowedLength>Variable</v8:AllowedLength>", "\t</v8:StringQualifiers>", "</valueType>"),
                        List.of("<value xsi:type=\"xs:string\"/>"));
            }
            Matcher number = NUMBER.matcher(type);
            if (number.matches()) {
                String digits = number.group(3) == null ? "10" : number.group(3);
                String fraction = number.group(5) == null ? "0" : number.group(5);
                if (Integer.parseInt(fraction) > Integer.parseInt(digits)) {
                    throw new IllegalArgumentException("fraction digits exceed digits: " + type);
                }
                return new ValueType("Number(" + digits + "," + fraction + ")", List.of("<valueType>",
                        "\t<v8:Type>xs:decimal</v8:Type>", "\t<v8:NumberQualifiers>", "\t\t<v8:Digits>" + digits + "</v8:Digits>",
                        "\t\t<v8:FractionDigits>" + fraction + "</v8:FractionDigits>", "\t\t<v8:AllowedSign>Any</v8:AllowedSign>",
                        "\t</v8:NumberQualifiers>", "</valueType>"), List.of("<value xsi:type=\"xs:decimal\">0</value>"));
            }
            switch (type.toLowerCase(Locale.ROOT)) {
                case "date", "дата", "datetime", "датавремя" -> {
                    boolean time = type.toLowerCase(Locale.ROOT).endsWith("time") || type.toLowerCase(Locale.ROOT).endsWith("время");
                    String fractions = time ? "DateTime" : "Date";
                    return new ValueType(fractions, List.of("<valueType>", "\t<v8:Type>xs:dateTime</v8:Type>",
                            "\t<v8:DateQualifiers>", "\t\t<v8:DateFractions>" + fractions + "</v8:DateFractions>",
                            "\t</v8:DateQualifiers>", "</valueType>"),
                            List.of("<value xsi:type=\"xs:dateTime\">0001-01-01T00:00:00</value>"));
                }
                case "boolean", "булево" -> {
                    return new ValueType("Boolean", List.of("<valueType>", "\t<v8:Type>xs:boolean</v8:Type>", "</valueType>"),
                            List.of("<value xsi:type=\"xs:boolean\">false</value>"));
                }
                case "standardperiod", "стандартныйпериод" -> {
                    return new ValueType("StandardPeriod", List.of("<valueType>", "\t<v8:Type>v8:StandardPeriod</v8:Type>",
                            "</valueType>"), List.of("<value xsi:type=\"v8:StandardPeriod\">",
                            "\t<v8:variant xsi:type=\"v8:StandardPeriodVariant\">Custom</v8:variant>",
                            "\t<v8:startDate>0001-01-01T00:00:00</v8:startDate>",
                            "\t<v8:endDate>0001-01-01T00:00:00</v8:endDate>", "</value>"));
                }
                default -> throw new IllegalArgumentException(AllowedValues.unknownValue("type", type,
                        List.of("String(N)", "Number(D,F)", "Date", "DateTime", "Boolean", "StandardPeriod")));
            }
        }
    }

    private static String title(String indent, String text, String nl) {
        return indent + "<title xsi:type=\"v8:LocalStringType\">" + nl
                + indent + "\t<v8:item>" + nl
                + indent + "\t\t<v8:lang>ru</v8:lang>" + nl
                + indent + "\t\t<v8:content>" + escape(text) + "</v8:content>" + nl
                + indent + "\t</v8:item>" + nl
                + indent + "</title>";
    }

    private static Document parse(String text) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(text)));
            Element root = document.getDocumentElement();
            if (!ROOT.equals(root.getLocalName()) || !SCHEMA_NS.equals(root.getNamespaceURI())) {
                throw new IllegalArgumentException("the template is not a data composition schema: root " + root.getNodeName());
            }
            for (String prefix : List.of("v8", "xs", "xsi")) {
                if (root.lookupNamespaceURI(prefix) == null) {
                    throw new IllegalArgumentException("the schema root does not declare the " + prefix + " prefix");
                }
            }
            return document;
        } catch (IllegalArgumentException refusal) {
            throw refusal;
        } catch (Exception malformed) {
            throw new IllegalArgumentException("the schema is not well-formed XML: " + malformed.getMessage());
        }
    }

    private static List<Element> children(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && localName.equals(element.getLocalName())) result.add(element);
        }
        return result;
    }

    private static String childText(Element parent, String localName) {
        List<Element> found = children(parent, localName);
        return found.isEmpty() ? "" : found.get(0).getTextContent().trim();
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }

    private static IllegalArgumentException layout() {
        return new IllegalArgumentException("the schema is not in the designer layout (one tab per level), "
                + "so the addition has no place to go; change it with edt_replace_dcs");
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
