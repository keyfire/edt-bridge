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

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import org.xml.sax.InputSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Bounded examples of lexical deletion risks; these are not resolved semantic references. */
public final class DeleteReferenceCandidates {
    private static final int CONTROL_CAP = 10_000;
    public record Candidate(String kind, String owner, int line, String text) {}
    public record Control(String name, String type, String dataPath) {}
    public static final class Scan {
        public final Map<String, Integer> counts = new LinkedHashMap<>();
        public final List<Candidate> examples = new ArrayList<>();
        public final List<Control> controls = new ArrayList<>();
        public boolean controlsTruncated;
        public int candidateCount() { return counts.values().stream().mapToInt(Integer::intValue).sum(); }
    }

    private DeleteReferenceCandidates() {}

    public static Scan bsl(String text, String name, int sampleCap) {
        Scan result = new Scan();
        identifiers(result, text, name, null, true, sampleCap);
        return result;
    }

    public static Scan xml(byte[] bytes, String name, boolean form, int sampleCap) throws Exception {
        return xml(new InputSource(new ByteArrayInputStream(bytes)), QueryTextFields.extract(bytes), name, form, sampleCap);
    }

    public static Scan xml(String text, String name, boolean form, int sampleCap) throws Exception {
        return xml(new InputSource(new StringReader(text)), QueryTextFields.extract(text), name, form, sampleCap);
    }

    private static Scan xml(InputSource source, List<QueryTextFields.Field> fields,
            String name, boolean form, int sampleCap) throws Exception {
        Scan result = new Scan();
        for (QueryTextFields.Field field : fields) {
            identifiers(result, field.text(), name, field.ownerName(), false, sampleCap);
        }
        if (!form) return result;
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var document = factory.newDocumentBuilder().parse(source);
        var elements = document.getElementsByTagName("*");
        for (int index = 0; index < elements.getLength(); index++) {
            Element element = (Element) elements.item(index);
            Element named = child(element, "name");
            String controlName = element.hasAttribute("name") ? element.getAttribute("name")
                    : named == null ? "" : named.getTextContent().strip();
            String type = element.getAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "type");
            Element path = child(element, "dataPath");
            String dataPath = element.hasAttribute("dataPath") ? element.getAttribute("dataPath")
                    : path == null ? "" : bindingText(path);
            if (controlName.isBlank() || (type.isBlank() && path == null && !element.hasAttribute("dataPath"))) continue;
            if (result.controls.size() < CONTROL_CAP) {
                result.controls.add(new Control(controlName, type, dataPath));
            } else {
                result.controlsTruncated = true;
            }
            if (containsIdentifier(dataPath, name)) {
                add(result, new Candidate("formBinding", controlName, 0, dataPath), sampleCap);
            }
        }
        return result;
    }

    /** Match controls by unique name/type before and after; do not infer removed controls were retained. */
    public static List<Control> retainedWithoutDataPath(List<Control> before, List<Control> after, String name) {
        Map<String, List<Control>> old = group(before);
        Map<String, List<Control>> now = group(after);
        List<Control> result = new ArrayList<>();
        for (var entry : old.entrySet()) {
            List<Control> current = now.get(entry.getKey());
            if (entry.getValue().size() != 1 || current == null || current.size() != 1) continue;
            Control previous = entry.getValue().get(0);
            if (containsIdentifier(previous.dataPath(), name) && current.get(0).dataPath().isBlank()) result.add(previous);
        }
        return result;
    }

    private static Map<String, List<Control>> group(List<Control> controls) {
        Map<String, List<Control>> result = new LinkedHashMap<>();
        for (Control control : controls) result.computeIfAbsent(
                control.name().toLowerCase(Locale.ROOT) + "\n" + control.type(),
                ignored -> new ArrayList<>()).add(control);
        return result;
    }

    private static void identifiers(Scan result, String text, String name, String owner,
            boolean bsl, int sampleCap) {
        boolean quoted = false;
        String[] lines = text.split("\\R", -1);
        for (int line = 0; line < lines.length; line++) {
            String source = lines[line];
            for (int offset = 0; offset < source.length();) {
                char value = source.charAt(offset);
                if (value == '"') {
                    if (quoted && offset + 1 < source.length() && source.charAt(offset + 1) == '"') offset += 2;
                    else { quoted = !quoted; offset++; }
                } else if (!quoted && value == '/' && offset + 1 < source.length() && source.charAt(offset + 1) == '/') {
                    break;
                } else if (identifier(value)) {
                    int end = offset + 1;
                    while (end < source.length() && identifier(source.charAt(end))) end++;
                    if (source.substring(offset, end).equalsIgnoreCase(name) && (bsl || !quoted)) {
                        String kind = bsl ? quoted ? "bslStringOrQuery" : "bslCode" : "queryField";
                        add(result, new Candidate(kind, owner, line + 1, excerpt(source)), sampleCap);
                    }
                    offset = end;
                } else {
                    offset++;
                }
            }
        }
    }

    private static void add(Scan result, Candidate candidate, int sampleCap) {
        result.counts.merge(candidate.kind(), 1, Integer::sum);
        if (result.examples.size() < sampleCap) result.examples.add(candidate);
    }

    private static boolean containsIdentifier(String text, String name) {
        for (int offset = 0; offset < text.length();) {
            if (!identifier(text.charAt(offset))) { offset++; continue; }
            int end = offset + 1;
            while (end < text.length() && identifier(text.charAt(end))) end++;
            if (text.substring(offset, end).equalsIgnoreCase(name)) return true;
            offset = end;
        }
        return false;
    }

    private static boolean identifier(char value) {
        return value == '_' || Character.isLetterOrDigit(value) || Character.getType(value) == Character.NON_SPACING_MARK;
    }

    private static String excerpt(String text) {
        String value = text.strip();
        return value.length() <= 800 ? value : value.substring(0, 800) + " ...";
    }

    private static Element child(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getLocalName())) return element;
        }
        return null;
    }

    private static String bindingText(Element path) {
        List<String> segments = new ArrayList<>();
        for (Node node = path.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) segments.add(element.getTextContent().strip());
        }
        return segments.isEmpty() ? path.getTextContent().strip() : String.join(".", segments);
    }
}
