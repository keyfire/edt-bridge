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
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/** Extract only serialized dynamic-list and DCS dataset query fields, preserving their owners. */
public final class QueryTextFields {
    private QueryTextFields() { }
    public record Field(String kind, String ownerName, String text, int xmlLine) { }
    private static final class Frame {
        final String local;
        final String type;
        final StringBuilder text = new StringBuilder();
        final int line;
        String name;
        Frame(String local, String type, String name, int line) {
            this.local = local; this.type = type; this.name = name; this.line = line;
        }
    }
    private record Pending(String kind, Frame owner, String text, int line) { }

    public static List<Field> extract(String xml) throws IOException {
        String text = xml.startsWith("\uFEFF") ? xml.substring(1) : xml;
        return extract(new InputSource(new StringReader(text)));
    }
    public static List<Field> extract(byte[] xml) throws IOException {
        return extract(new InputSource(new ByteArrayInputStream(xml)));
    }
    private static List<Field> extract(InputSource source) throws IOException {
        List<Frame> stack = new ArrayList<>();
        List<Pending> pending = new ArrayList<>();
        try {
            SAXParserFactory factory = SAXParserFactory.newDefaultInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            var parser = factory.newSAXParser();
            var reader = parser.getXMLReader();
            reader.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            reader.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            DefaultHandler handler = new DefaultHandler() {
                private Locator locator;
                @Override public void setDocumentLocator(Locator value) { locator = value; }
                @Override public void startElement(String uri, String local, String qName, Attributes attributes) {
                    String type = attributes.getValue(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "type");
                    if (type == null) type = local;
                    type = type.substring(type.lastIndexOf(':') + 1);
                    stack.add(new Frame(local, type, attributes.getValue("name"), locator.getLineNumber()));
                }
                @Override public void characters(char[] text, int start, int length) {
                    if (!stack.isEmpty()) stack.get(stack.size() - 1).text.append(text, start, length);
                }
                @Override public void endElement(String uri, String local, String qName) {
                    Frame field = stack.remove(stack.size() - 1);
                    if (local.equals("name") && !stack.isEmpty()) {
                        stack.get(stack.size() - 1).name = field.text.toString().trim();
                    }
                    if (local.equals("queryText") && !field.text.toString().isBlank()) {
                        Frame info = nearest(stack, "DynamicListExtInfo");
                        Frame attribute = nearestAttribute(stack);
                        if (info != null && attribute != null && stack.get(stack.size() - 1) == info) pending.add(new Pending("dynamicList", attribute, field.text.toString(), field.line));
                    } else if (local.equals("query") && !field.text.toString().isBlank()) {
                        Frame dataSet = nearest(stack, "DataSetQuery", "DataCompositionSchemaDataSetQuery");
                        if (dataSet != null && stack.get(stack.size() - 1) == dataSet) pending.add(new Pending("dcs", dataSet, field.text.toString(), field.line));
                    }
                }
                @Override public void error(org.xml.sax.SAXParseException ex) throws SAXException { throw ex; }
                @Override public void fatalError(org.xml.sax.SAXParseException ex) throws SAXException { throw ex; }
            };
            reader.setContentHandler(handler);
            reader.setErrorHandler(handler);
            reader.parse(source);
            List<Field> out = new ArrayList<>();
            for (Pending item : pending) {
                if (item.owner().name == null || item.owner().name.isBlank()) throw new IOException("query field has no named attribute or dataset");
                out.add(new Field(item.kind(), item.owner().name, item.text(), item.line()));
            }
            return out;
        } catch (javax.xml.parsers.ParserConfigurationException | SAXException ex) {
            throw new IOException("could not parse query XML: " + ex.getMessage(), ex);
        }
    }
    private static Frame nearest(List<Frame> stack, String... types) {
        for (int index = stack.size() - 1; index >= 0; index--) {
            Frame frame = stack.get(index);
            for (String type : types) if (type.equals(frame.type)) return frame;
        }
        return null;
    }
    private static Frame nearestAttribute(List<Frame> stack) {
        for (int index = stack.size() - 1; index >= 0; index--) {
            Frame frame = stack.get(index);
            if (frame.local.equals("attributes") || frame.local.equals("attribute")) return frame;
        }
        return null;
    }
}
