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

import java.util.Locale;

/**
 * A picture named the way {@code Form.form} stores it: {@code StdPicture.Post} is a standard
 * picture of the platform, {@code CommonPicture.Logo} a common picture of the configuration.
 * {@code БиблиотекаКартинок.Name}, the BSL spelling, may mean either and is looked up among the
 * standard pictures first, as the platform does. An empty name clears the picture.
 */
public final class PictureNames {
    private PictureNames() { }

    /** Where to look a picture up. */
    public enum Kind { STANDARD, COMMON, EITHER }

    /** A parsed name; {@code null} from {@link #parse} means "no picture". */
    public record Name(Kind kind, String name) {
        /** The name as Form.form writes it, for a standard or a common picture. */
        public String stored() {
            return (kind == Kind.COMMON ? "CommonPicture." : "StdPicture.") + name;
        }
    }

    public static Name parse(String raw) {
        String text = raw == null ? "" : raw.strip();
        if (text.isEmpty()) return null;
        int dot = text.indexOf('.');
        String prefix = dot < 0 ? "" : text.substring(0, dot).toLowerCase(Locale.ROOT);
        String name = dot < 0 ? "" : text.substring(dot + 1).strip();
        Kind kind = switch (prefix) {
            case "stdpicture" -> Kind.STANDARD;
            case "commonpicture", "общаякартинка" -> Kind.COMMON;
            case "библиотекакартинок" -> Kind.EITHER;
            default -> null;
        };
        if (kind == null || name.isEmpty() || name.contains(".") || !name.matches("[\\p{L}_][\\p{L}\\p{N}_]*")) {
            throw new IllegalArgumentException("picture must be StdPicture.<Name> (a standard picture), "
                    + "CommonPicture.<Name> (a common picture of the configuration) or БиблиотекаКартинок.<Name>; "
                    + "an empty string clears it; got: " + raw);
        }
        return new Name(kind, name);
    }
}
