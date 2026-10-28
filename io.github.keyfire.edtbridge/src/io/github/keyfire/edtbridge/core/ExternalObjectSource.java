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

import java.io.IOException;
import java.nio.file.Path;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/** The kind and name from the platform's external-object XML, with automatic BOM decoding. */
public record ExternalObjectSource(String kind, String name) {
    public static ExternalObjectSource read(Path file) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override public void fatalError(org.xml.sax.SAXParseException ex) throws SAXException { throw ex; }
                @Override public void error(org.xml.sax.SAXParseException ex) throws SAXException { throw ex; }
            });
            Element root = builder.parse(file.toFile()).getDocumentElement();
            if (!"MetaDataObject".equals(root.getLocalName())) throw new IOException("not platform metadata XML");
            for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
                if (!(node instanceof Element object)) continue;
                String kind = object.getLocalName();
                if (!"ExternalDataProcessor".equals(kind) && !"ExternalReport".equals(kind)) continue;
                Element properties = child(object, "Properties");
                Element nameNode = properties == null ? null : child(properties, "Name");
                if (nameNode == null) throw new IOException("external object has no name");
                String name = nameNode.getTextContent().trim();
                if (name.isEmpty()) throw new IOException("external object has an empty name");
                return new ExternalObjectSource(kind, name);
            }
            throw new IOException("XML contains neither ExternalDataProcessor nor ExternalReport");
        } catch (javax.xml.parsers.ParserConfigurationException | SAXException | IllegalArgumentException ex) {
            throw new IOException("could not read external-object XML: " + ex.getMessage(), ex);
        }
    }

    private static Element child(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getLocalName())) return element;
        }
        return null;
    }
}
