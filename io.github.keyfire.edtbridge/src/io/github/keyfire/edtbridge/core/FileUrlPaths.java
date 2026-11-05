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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * The file system path behind the path part of a {@code file:} URL.
 *
 * <p>{@link URLDecoder} decodes form data, where {@code +} stands for a space. In a URL path it is
 * an ordinary character, and 1C:Installer puts one into every EDT component directory:
 * {@code 1c-edt-2026.1.2+2-x86_64} came out of a plain decode as {@code 1c-edt-2026.1.2 2-x86_64},
 * a directory that does not exist.
 */
public final class FileUrlPaths {

    private FileUrlPaths() {
    }

    public static String decode(String urlPath) {
        return URLDecoder.decode(urlPath.replace("+", "%2B"), StandardCharsets.UTF_8);
    }
}
