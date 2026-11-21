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
package io.github.keyfire.edtbridge.edt;

import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

/**
 * Platform texts EDT puts into a form preview (standard attribute titles, standard command captions,
 * the line number column) in the language of the configuration rather than of EDT itself.
 *
 * <p>EDT localizes these strings through its own message bundles, so a headless EDT started with
 * {@code -nl en_US} draws "Description" and "Save and close" in a Russian configuration. The same
 * bundles carry the other languages next to the English base ({@code messages_ru.properties}), so the
 * English text is looked up there and the translation of the same key is taken.
 */
final class EdtTexts {

    /** Bundles whose messages reach a form preview. */
    private static final String[] BUNDLES = {
        "com._1c.g5.v8.dt.form", "com._1c.g5.v8.dt.md", "com._1c.g5.v8.dt.form.layout",
    };

    private static final Map<String, EdtTexts> CACHE = new ConcurrentHashMap<>();

    /** English text to the keys carrying it and their translation: [key, translated]. */
    private final Map<String, List<String[]>> byEnglish = new HashMap<>();

    private EdtTexts(String language) {
        for (String id : BUNDLES) {
            Bundle bundle = Platform.getBundle(id);
            if (bundle == null) {
                continue;
            }
            Enumeration<URL> found = bundle.findEntries("/", "messages.properties", true);
            while (found != null && found.hasMoreElements()) {
                URL base = found.nextElement();
                String path = base.getPath();
                URL local = bundle.getEntry(path.substring(0, path.length() - "messages.properties".length())
                        + "messages_" + language + ".properties");
                if (local == null) {
                    continue;
                }
                Properties english = load(base);
                Properties translated = load(local);
                for (String key : english.stringPropertyNames()) {
                    String target = translated.getProperty(key);
                    if (target != null && !target.isBlank()) {
                        byEnglish.computeIfAbsent(english.getProperty(key), k -> new ArrayList<>())
                                .add(new String[] {key, target});
                    }
                }
            }
        }
    }

    /** The texts for a configuration language; null for English, which needs no translation. */
    static EdtTexts forLanguage(String language) {
        if (language == null || language.isBlank() || "en".equalsIgnoreCase(language)) {
            return null;
        }
        return CACHE.computeIfAbsent(language.toLowerCase(java.util.Locale.ROOT), EdtTexts::new);
    }

    /**
     * The translation of an English platform text, or null when EDT has none. With several keys
     * carrying the text, a key naming {@code hint} (the standard command or attribute name) wins,
     * then a key starting with one of the {@code prefixes}, then the first one.
     */
    String translate(String english, String hint, String... prefixes) {
        if (english == null) {
            return null;
        }
        List<String[]> candidates = byEnglish.get(english);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (hint != null && !hint.isBlank()) {
            for (String[] c : candidates) {
                if (c[0].contains("_" + hint + "_") || c[0].endsWith("_" + hint)) {
                    return c[1];
                }
            }
        }
        for (String prefix : prefixes) {
            for (String[] c : candidates) {
                if (c[0].startsWith(prefix)) {
                    return c[1];
                }
            }
        }
        return candidates.get(0)[1];
    }

    private static Properties load(URL url) {
        Properties p = new Properties();
        try (InputStream in = url.openStream()) {
            p.load(in);
        } catch (Exception ignored) {
            // an unreadable bundle file leaves its texts untranslated
        }
        return p;
    }
}
