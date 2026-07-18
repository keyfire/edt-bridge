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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;

/**
 * The platform Syntax Helper (1C:Enterprise API reference bundled with EDT). Self-contained: it reads
 * the HTML pages shipped inside EDT's {@code com._1c.g5.v8.dt.platform.doc_v8_*} bundles via OSGi and
 * needs nothing from the rest of the model gateway. Split out of the original model gateway to keep that
 * file focused; behaviour is unchanged.
 */
public final class DocsGateway {

    /** One documentation page: its title (Ru + En) and how to read it (bundle + entry path). */
    public static final class HelpEntry {
        public String title;
        public String bundle;
        public String path;
        public String version;   // platform version tag of the source bundle (v8_5_1, ...)
    }

    /** Result of {@link #platformHelp}. */
    public static final class HelpResult {
        public String mode;                    // "search" | "page"
        public List<HelpEntry> hits = new ArrayList<>();  // search mode
        public String title;                   // page mode
        public String text;                    // page mode: tag-stripped content
        public String bundle;
        public String path;
        public int indexed;                    // how many pages the index holds
        public String message;
    }

    // Lazy title index over the platform Syntax Helper bundles; built once, then reused.
    private volatile List<HelpEntry> helpIndex;

    /**
     * Search the platform Syntax Helper, or read one page. The docs are the HTML reference shipped
     * inside EDT's {@code com._1c.g5.v8.dt.platform.doc_v8_*} bundles (objects, methods, properties,
     * events – the real 1C:Enterprise API, in Russian + English). {@code path} non-empty → read that
     * page as text; otherwise search titles for {@code query} (all whitespace-separated terms must
     * appear, case-insensitive; matches Ru or En names).
     */
    public HelpResult platformHelp(String query, String path, String bundle, int limit) {
        HelpResult r = new HelpResult();
        List<HelpEntry> index = ensureHelpIndex();
        r.indexed = index.size();
        if (index.isEmpty()) {
            r.message = "no platform documentation bundles found "
                    + "(com._1c.g5.v8.dt.platform.doc_v8_* not installed in this EDT)";
            return r;
        }
        if (path != null && !path.isBlank()) {
            r.mode = "page";
            String sn = (bundle != null && !bundle.isBlank()) ? bundle
                    : index.stream().filter(e -> e.path.equals(path)).map(e -> e.bundle)
                            .findFirst().orElse(null);
            if (sn == null) {
                r.message = "page not in the index; pass the exact path from a search hit "
                        + "(and its bundle)";
                return r;
            }
            String html = readBundleEntry(sn, path);
            if (html == null) {
                r.message = "could not read the page: " + sn + "!" + path;
                return r;
            }
            r.bundle = sn;
            r.path = path;
            r.title = extractTitle(html);
            r.text = htmlToText(html);
            return r;
        }
        r.mode = "search";
        if (query == null || query.isBlank()) {
            r.message = "query is required for search";
            return r;
        }
        String[] terms = query.trim().toLowerCase().split("\\s+");
        int cap = (limit > 0) ? limit : 15;
        java.util.Set<String> seenTitles = new java.util.HashSet<>();
        for (HelpEntry e : index) {
            String hay = e.title.toLowerCase();
            boolean all = true;
            for (String t : terms) {
                if (!hay.contains(t)) {
                    all = false;
                    break;
                }
            }
            // The generic and version bundles repeat the same page; show each title once.
            if (all && seenTitles.add(e.title)) {
                r.hits.add(e);
                if (r.hits.size() >= cap) {
                    break;
                }
            }
        }
        if (r.hits.isEmpty()) {
            r.message = "nothing matched \"" + query + "\" among " + index.size() + " pages";
        }
        return r;
    }

    private List<HelpEntry> ensureHelpIndex() {
        List<HelpEntry> local = helpIndex;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (helpIndex != null) {
                return helpIndex;
            }
            List<HelpEntry> built = new ArrayList<>();
            BundleContext ctx = null;
            try {
                Bundle self = FrameworkUtil.getBundle(DocsGateway.class);
                ctx = (self == null) ? null : self.getBundleContext();
            } catch (RuntimeException ignored) {
                // no framework context – leave the index empty
            }
            if (ctx != null) {
                String base = "com._1c.g5.v8.dt.platform.doc";
                for (Bundle b : ctx.getBundles()) {
                    String sn = b.getSymbolicName();
                    // The generic bundle (base name) holds the full Syntax Helper; the version bundles
                    // (base_v8_5_1, base_v8_3_27, ...) add version-specific pages. Index them all.
                    if (sn == null || !(sn.equals(base) || sn.startsWith(base + "_v8_"))) {
                        continue;
                    }
                    String version = sn.equals(base) ? "generic" : sn.substring(base.length() + 1);
                    java.util.Enumeration<java.net.URL> entries =
                            b.findEntries("nl/ru/html", "*.html", true);
                    if (entries == null) {
                        continue;
                    }
                    while (entries.hasMoreElements()) {
                        java.net.URL url = entries.nextElement();
                        String p = url.getPath();
                        String title = readTitle(url);
                        if (title == null || title.isBlank()) {
                            continue;
                        }
                        HelpEntry e = new HelpEntry();
                        e.title = title;
                        e.bundle = sn;
                        e.path = p.startsWith("/") ? p.substring(1) : p;
                        e.version = version;
                        built.add(e);
                    }
                }
            }
            helpIndex = built;
            return built;
        }
    }

    /** Read only enough of an entry to extract its {@code <title>} (the index is title-only). */
    private static String readTitle(java.net.URL url) {
        try (java.io.InputStream in = url.openStream()) {
            byte[] buf = new byte[2048];
            int n = in.read(buf);
            if (n <= 0) {
                return null;
            }
            return extractTitle(new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private String readBundleEntry(String symbolicName, String path) {
        Bundle b = Platform.getBundle(symbolicName);
        if (b == null) {
            return null;
        }
        java.net.URL url = b.getEntry(path);
        if (url == null) {
            return null;
        }
        try (java.io.InputStream in = url.openStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private static final Pattern TITLE_RE =
            Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static String extractTitle(String html) {
        java.util.regex.Matcher m = TITLE_RE.matcher(html);
        return m.find() ? m.group(1).replaceAll("\\s+", " ").trim() : null;
    }

    /** HTML → readable text: drop script/style, tags to spaces, unescape the few common entities. */
    private static String htmlToText(String html) {
        String s = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        s = s.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)</p>", "\n");
        s = s.replaceAll("<[^>]+>", " ");
        s = s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
        s = s.replaceAll("[ \\t]+", " ").replaceAll("\\s*\\n\\s*", "\n").replaceAll("\\n{3,}", "\n\n");
        return s.trim();
    }
}
