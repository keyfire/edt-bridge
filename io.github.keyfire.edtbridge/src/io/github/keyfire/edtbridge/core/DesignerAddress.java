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

/**
 * How the 1C designer addresses an infobase on its command line.
 *
 * <p>Pure string work, kept out of the gateway so it can be tested without EDT: the platform accepts
 * the infobase glued to its switch ({@code /FD:\Bases\demo}, {@code /Ssrv\base}) and a space would
 * turn it into a separate argument the designer ignores. Getting this wrong is quiet - the designer
 * starts and simply does not find a base.
 */
public final class DesignerAddress {

    private DesignerAddress() {
    }

    /** A file infobase: the directory holding {@code 1Cv8.1CD}. */
    public static String file(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        return "/F" + path.trim();
    }

    /**
     * A server infobase: the 1C cluster host and the infobase name in that cluster, separated by a
     * backslash. The host may carry a port ({@code srv:1741}), which is passed through untouched.
     */
    public static String server(String host, String reference) {
        if (host == null || host.isBlank() || reference == null || reference.isBlank()) {
            return null;
        }
        return "/S" + host.trim() + "\\" + reference.trim();
    }

    /**
     * Address whichever kind of infobase EDT registered.
     *
     * @param kind      {@code file} or {@code server}; anything else has no designer address
     * @param filePath  file infobase directory
     * @param host      cluster host of a server infobase
     * @param reference infobase name in that cluster
     * @return the command-line argument, or null when this kind cannot be addressed
     */
    public static String of(String kind, String filePath, String host, String reference) {
        if ("file".equals(kind)) {
            return file(filePath);
        }
        if ("server".equals(kind)) {
            return server(host, reference);
        }
        return null;
    }

    /**
     * An address the caller wrote out instead of naming a registered infobase: a designer switch as is
     * ({@code /Ssrv\base}, {@code /FD:\Bases\demo}), a bare {@code host\base} pair, or a path to a
     * file infobase directory. Null when the text is none of those, and the caller should look the name
     * up in EDT instead.
     *
     * <p>This exists because EDT does not know every infobase: a base that no project is bound to is
     * simply absent from its list, and refusing to work with it would be an artificial limit - the
     * designer itself needs nothing but the address.
     */
    public static String explicit(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.startsWith("/S") || value.startsWith("/F")) {
            return value;
        }
        // A path first: "D:\Bases\demo" also holds a backslash, and reading it as host\base would
        // produce a server address for a directory - the order of these two checks is the whole trick.
        if (value.length() > 2 && value.charAt(1) == ':') {
            return file(value);
        }
        if (value.startsWith("/")) {
            return file(value);
        }
        int slash = value.indexOf('\\');
        if (slash > 0 && slash < value.length() - 1 && !value.contains("/")) {
            return "/S" + value;
        }
        return null;
    }
}
