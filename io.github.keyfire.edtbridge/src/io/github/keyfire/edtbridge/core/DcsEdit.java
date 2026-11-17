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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Changes of the designer XML of a data composition schema, made as text edits: a field of a data
 * set added, retitled, formatted or removed, a schema parameter added, the query of a data set set,
 * an exact fragment replaced. The rest of the file keeps its bytes, so the change reads as the
 * changed lines only. The schema has to be in the designer layout - one tab per level, as EDT and the
 * designer write it - and every change is checked against what the schema already holds.
 */
public final class DcsEdit {
    /** The kinds of change, in the order the descriptor lists them. */
    public static final List<String> ACTIONS = List.of("addField", "addParameter", "setQuery", "replaceFragment",
            "setField", "removeField");

    /** One change. {@code action} is one of {@link #ACTIONS}; the other fields depend on it. */
    public record Operation(String action, String dataSet, String dataPath, String field, String name,
            String titleRu, String type, String query, String fragment, String replacement, String format) {
        public Operation(String action, String dataSet, String dataPath, String field, String name,
                String titleRu, String type) {
            this(action, dataSet, dataPath, field, name, titleRu, type, null, null, null, null);
        }
    }

    public record Result(String text, List<String> plan) { }

    private static final String ROOT = "DataCompositionSchema";
    private static final String SCHEMA_NS = "http://v8.1c.ru/8.1/data-composition-system/schema";
    /** Children of a data set field that follow its appearance, in the order the platform writes them. */
    private static final List<String> AFTER_APPEARANCE = List.of("inputParameters", "orderExpression");
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
                case "setQuery" -> current = setQuery(current, document, operation, nl, plan);
                case "replaceFragment" -> current = replaceFragment(current, operation, nl, plan);
                case "setField" -> current = setField(current, document, operation, nl, plan);
                case "removeField" -> current = removeField(current, document, operation, plan);
                default -> throw new IllegalArgumentException(AllowedValues.unknownValue("action", action, ACTIONS));
            }
            // Each change has to leave a schema the next one and the platform can read.
            parse(current);
        }
        return new Result(current, List.copyOf(plan));
    }

    /** The data set of this name among the top-level data sets, or a refusal that lists them. */
    private static Element dataSet(Document document, String name) {
        List<String> sets = new ArrayList<>();
        for (Element candidate : children(document.getDocumentElement(), "dataSet")) {
            String setName = childText(candidate, "name");
            sets.add(setName);
            if (name.equalsIgnoreCase(setName)) return candidate;
        }
        throw new IllegalArgumentException("data set not found: " + name + "; the schema has: "
                + (sets.isEmpty() ? "no data sets" : String.join(", ", sets)));
    }

    /** Where the text of a top-level data set lies: from its opening line to its closing tag. */
    private static int[] dataSetBlock(String text, Element set) {
        Matcher block = Pattern.compile("(?s)(?<=\\n)\\t<dataSet\\b[^>]*>\\r?\\n\\t\\t<name>"
                + Pattern.quote(escape(childText(set, "name"))) + "</name>.*?\\n\\t</dataSet>").matcher(text);
        if (!block.find()) throw layout();
        return new int[] {block.start(), block.end()};
    }

    /** The field of a data set with this data path, by its stored spelling, or a refusal that lists them. */
    private static Element setField(Element set, String dataPath) {
        List<String> paths = new ArrayList<>();
        for (Element existing : children(set, "field")) {
            String path = childText(existing, "dataPath");
            paths.add(path);
            if (dataPath.equalsIgnoreCase(path)) return existing;
        }
        throw new IllegalArgumentException("data set " + childText(set, "name") + " has no field " + dataPath
                + "; its fields: " + (paths.isEmpty() ? "none" : String.join(", ", paths)));
    }

    /** Where the text of one field of a data set lies, from the line break before it to its closing tag. */
    private static int[] fieldBlock(String text, int[] setBlock, String storedPath) {
        Matcher field = Pattern.compile("(?s)\\r?\\n\\t\\t<field xsi:type=\"[^\"]*\">\\r?\\n\\t\\t\\t<dataPath>"
                + Pattern.quote(escape(storedPath)) + "</dataPath>.*?\\n\\t\\t</field>").matcher(text);
        field.region(setBlock[0], setBlock[1]);
        if (!field.find()) throw layout();
        return new int[] {field.start(), field.end()};
    }

    private static String setQuery(String text, Document document, Operation op, String nl, List<String> plan) {
        Element set = dataSet(document, required(op.dataSet(), "dataSet"));
        String query = op.query();
        if (query == null || query.isBlank()) throw new IllegalArgumentException("query is required: the whole query text");
        if (children(set, "query").isEmpty()) {
            throw new IllegalArgumentException("data set " + childText(set, "name") + " has no query: only a query data "
                    + "set takes setQuery");
        }
        int[] block = dataSetBlock(text, set);
        Matcher element = Pattern.compile("(?s)\\n\\t\\t<query>.*?</query>|\\n\\t\\t<query/>").matcher(text);
        element.region(block[0], block[1]);
        if (!element.find()) throw layout();
        String lines = query.replace("\r\n", "\n").replace("\r", "\n");
        String stored = escape(lines).replace("\n", nl);
        plan.add("set the query of data set " + childText(set, "name") + " (" + lines.split("\n", -1).length + " lines)");
        return text.substring(0, element.start()) + "\n\t\t<query>" + stored + "</query>" + text.substring(element.end());
    }

    private static String replaceFragment(String text, Operation op, String nl, List<String> plan) {
        String fragment = op.fragment();
        if (fragment == null || fragment.isEmpty()) throw new IllegalArgumentException("fragment is required: exact text of the schema");
        if (op.replacement() == null) throw new IllegalArgumentException("replacement is required; an empty string removes the fragment");
        String wanted = lineEndings(fragment, nl);
        String replacement = lineEndings(op.replacement(), nl);
        List<Integer> found = occurrences(text, wanted);
        boolean escaped = false;
        if (found.isEmpty() && !escape(wanted).equals(wanted)) {
            // The file keeps & < > escaped; a fragment copied from a query reads them plain.
            List<Integer> asStored = occurrences(text, escape(wanted));
            if (!asStored.isEmpty()) {
                found = asStored;
                wanted = escape(wanted);
                replacement = escape(replacement);
                escaped = true;
            }
        }
        if (found.isEmpty()) {
            throw new IllegalArgumentException("fragment not found in the schema text; it must match the text "
                    + "edt_read_dcs returns, tabs and line breaks included");
        }
        if (found.size() > 1) {
            List<String> lines = new ArrayList<>();
            for (int index : found.subList(0, Math.min(found.size(), 10))) lines.add(String.valueOf(lineOf(text, index)));
            throw new IllegalArgumentException("fragment occurs " + found.size() + " times (lines " + String.join(", ", lines)
                    + "); extend it until it matches one place");
        }
        int at = found.get(0);
        plan.add("replace a fragment at line " + lineOf(text, at) + (escaped ? " (matched with & < > escaped as the "
                + "file stores them)" : ""));
        return text.substring(0, at) + replacement + text.substring(at + wanted.length());
    }

    private static String setField(String text, Document document, Operation op, String nl, List<String> plan) {
        Element set = dataSet(document, required(op.dataSet(), "dataSet"));
        Element field = setField(set, required(op.dataPath(), "dataPath"));
        boolean title = op.titleRu() != null && !op.titleRu().isBlank();
        boolean format = op.format() != null && !op.format().isBlank();
        if (!title && !format) throw new IllegalArgumentException("setField needs titleRu, format or both");
        if (format) {
            for (String prefix : List.of("dcscor", "dcsset")) {
                if (document.getDocumentElement().lookupNamespaceURI(prefix) == null) {
                    throw new IllegalArgumentException("the schema root does not declare the " + prefix
                            + " prefix that a field format needs; change it with replaceFragment");
                }
            }
        }
        String path = childText(field, "dataPath");
        String current = text;
        if (title) {
            int[] block = fieldBlock(current, dataSetBlock(current, set), path);
            current = fieldTitle(current, block, op.titleRu().trim(), nl);
        }
        if (format) {
            int[] block = fieldBlock(current, dataSetBlock(current, set), path);
            current = fieldFormat(current, block, op.format().trim(), formatParameter(document), nl);
        }
        List<String> parts = new ArrayList<>();
        if (title) parts.add("title \"" + op.titleRu().trim() + "\"");
        if (format) parts.add("format \"" + op.format().trim() + "\"");
        plan.add("set " + String.join(" and ", parts) + " of field " + path + " in data set " + childText(set, "name"));
        return current;
    }

    /** The Russian title of a field: its ru item changed, a ru item added to the title, or a title added. */
    private static String fieldTitle(String text, int[] block, String value, String nl) {
        String inside = text.substring(block[0], block[1]);
        Matcher titleElement = Pattern.compile("(?s)\\r?\\n\\t\\t\\t<title(?=[\\s/>])[^>]*?"
                + "(?:/>|>.*?\\r?\\n\\t\\t\\t</title>)").matcher(inside);
        if (titleElement.find()) {
            String titleText = titleElement.group();
            if (titleText.endsWith("/>")) {
                return splice(text, block[0] + titleElement.end() - 2, 2,
                        ">" + nl + item("\t\t\t\t", value, nl) + nl + "\t\t\t</title>");
            }
            Matcher ru = Pattern.compile("(?s)<v8:lang>\\s*ru\\s*</v8:lang>\\s*<v8:content>(.*?)</v8:content>")
                    .matcher(titleText);
            String changed = ru.find()
                    ? titleText.substring(0, ru.start(1)) + escape(value) + titleText.substring(ru.end(1))
                    : insertBeforeLine(titleText, "\t\t\t</title>", nl + item("\t\t\t\t", value, nl));
            return splice(text, block[0] + titleElement.start(), titleText.length(), changed);
        }
        // A new title goes after the field name, where the platform writes it.
        Matcher anchor = Pattern.compile("\\n\\t\\t\\t<field>[^<]*</field>").matcher(inside);
        if (!anchor.find()) {
            anchor = Pattern.compile("\\n\\t\\t\\t<dataPath>[^<]*</dataPath>").matcher(inside);
            if (!anchor.find()) throw layout();
        }
        return splice(text, block[0] + anchor.end(), 0, nl + title("\t\t\t", value, nl));
    }

    /** The format of a field: the value of its format parameter changed, or the parameter added to its appearance. */
    private static String fieldFormat(String text, int[] block, String value, String parameter, String nl) {
        String inside = text.substring(block[0], block[1]);
        Matcher appearance = Pattern.compile("(?s)\\r?\\n\\t\\t\\t<appearance>.*?\\r?\\n\\t\\t\\t</appearance>").matcher(inside);
        if (appearance.find()) {
            String appearanceText = appearance.group();
            Matcher item = Pattern.compile("(?s)\\r?\\n\\t\\t\\t\\t<dcscor:item\\b[^>]*>\\r?\\n\\t\\t\\t\\t\\t<dcscor:parameter>"
                    + "(Формат|Format)</dcscor:parameter>.*?\\r?\\n\\t\\t\\t\\t</dcscor:item>").matcher(appearanceText);
            String changed = item.find()
                    ? appearanceText.substring(0, item.start()) + nl + formatItem(item.group(1), value, nl)
                            + appearanceText.substring(item.end())
                    : insertBeforeLine(appearanceText, "\t\t\t</appearance>", nl + formatItem(parameter, value, nl));
            return splice(text, block[0] + appearance.start(), appearanceText.length(), changed);
        }
        // A new appearance goes before the children the platform writes after it, or last in the field.
        String added = nl + "\t\t\t<appearance>" + nl + formatItem(parameter, value, nl) + nl + "\t\t\t</appearance>";
        Matcher later = Pattern.compile("\\r?\\n\\t\\t\\t<(" + String.join("|", AFTER_APPEARANCE) + ")\\b").matcher(inside);
        if (later.find()) return splice(text, block[0] + later.start(), 0, added);
        return splice(text, block[0], inside.length(), insertBeforeLine(inside, "\t\t</field>", added));
    }

    /** Text with `added` put before the last line that is `line`, that line's break included. */
    private static String insertBeforeLine(String text, String line, String added) {
        int close = text.lastIndexOf("\n" + line);
        if (close < 0) throw layout();
        int lineStart = close > 0 && text.charAt(close - 1) == '\r' ? close - 1 : close;
        return text.substring(0, lineStart) + added + text.substring(lineStart);
    }

    private static String splice(String text, int start, int length, String replacement) {
        return text.substring(0, start) + replacement + text.substring(start + length);
    }

    /** The name the schema gives its format parameters: Format where all of them are English, else Формат. */
    private static String formatParameter(Document document) {
        var parameters = document.getElementsByTagNameNS("http://v8.1c.ru/8.1/data-composition-system/core", "parameter");
        boolean english = false;
        for (int index = 0; index < parameters.getLength(); index++) {
            String name = parameters.item(index).getTextContent().trim();
            if ("Формат".equals(name)) return "Формат";
            if ("Format".equals(name)) english = true;
        }
        return english ? "Format" : "Формат";
    }

    private static String formatItem(String parameter, String value, String nl) {
        return "\t\t\t\t<dcscor:item xsi:type=\"dcsset:SettingsParameterValue\">" + nl
                + "\t\t\t\t\t<dcscor:parameter>" + parameter + "</dcscor:parameter>" + nl
                + "\t\t\t\t\t<dcscor:value xsi:type=\"v8:LocalStringType\">" + nl
                + item("\t\t\t\t\t\t", value, nl) + nl
                + "\t\t\t\t\t</dcscor:value>" + nl
                + "\t\t\t\t</dcscor:item>";
    }

    private static String item(String indent, String value, String nl) {
        return indent + "<v8:item>" + nl
                + indent + "\t<v8:lang>ru</v8:lang>" + nl
                + indent + "\t<v8:content>" + escape(value) + "</v8:content>" + nl
                + indent + "</v8:item>";
    }

    private static String removeField(String text, Document document, Operation op, List<String> plan) {
        Element set = dataSet(document, required(op.dataSet(), "dataSet"));
        Element field = setField(set, required(op.dataPath(), "dataPath"));
        String path = childText(field, "dataPath");
        int[] block = fieldBlock(text, dataSetBlock(text, set), path);
        String result = text.substring(0, block[0]) + text.substring(block[1]);
        // A path the settings or an expression still name makes a variant the platform reports later.
        Matcher named = Pattern.compile(">" + Pattern.quote(escape(path)) + "<|>" + Pattern.quote(escape(path)) + "\\.")
                .matcher(result);
        int uses = 0;
        while (named.find()) uses++;
        plan.add("remove field " + path + " from data set " + childText(set, "name")
                + (uses == 0 ? "" : "; note: " + path + " is still named " + uses + " time(s) elsewhere in the schema, "
                        + "e.g. in the settings"));
        return result;
    }

    private static String lineEndings(String value, String nl) {
        String plain = value.replace("\r\n", "\n");
        return "\r\n".equals(nl) ? plain.replace("\n", "\r\n") : plain;
    }

    private static List<Integer> occurrences(String text, String fragment) {
        List<Integer> found = new ArrayList<>();
        for (int at = text.indexOf(fragment); at >= 0; at = text.indexOf(fragment, at + 1)) found.add(at);
        return found;
    }

    private static int lineOf(String text, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) if (text.charAt(i) == '\n') line++;
        return line;
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
            Document document = factory.newDocumentBuilder().parse(DcsCheck.xmlInput(text));
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
