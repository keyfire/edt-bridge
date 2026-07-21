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

    // ── check documentation (why a validation problem fired) ────────────────────────────────────

    /** One EDT check and what its documentation says. */
    public static final class CheckEntry {
        public String id;                 // check id as the marker carries it, e.g. form-items-single-event-handler
        public String bundle;             // bundle holding the description
        public String title;
        public String matchedBy;          // "id" or "title" - how the query found it
        public String text;               // description as plain text (null when only listed)
        public final List<String> standards = new ArrayList<>();  // links to the development standards
    }

    /** Result of looking a check up. */
    public static final class CheckResult {
        public boolean ok;
        public String query;
        public String language;
        public int total;
        public boolean truncated;
        public final List<CheckEntry> checks = new ArrayList<>();
        public String message;
    }

    /** Where the check bundles keep their documentation, and how the localized copy is nested. */
    private static final String CHECK_DOCS = "check.descriptions";

    /**
     * The documentation EDT shows for a validation check: what it means, the non-compliant and
     * compliant examples, and the LINKS TO THE DEVELOPMENT STANDARDS behind it.
     *
     * <p>This is the other half of {@code edt_project_errors}. That tool reports which check fired and
     * carries its id; without the description the id is just a slug, and the reason the rule exists -
     * the standard it enforces - stays in the IDE where an agent cannot read it.
     *
     * <p>The checks ship their descriptions as HTML resources inside their own bundles
     * ({@code check.descriptions/<id>.html}, Russian under {@code check.descriptions/ru/}), so they are
     * read the same way as the platform Syntax Helper - through OSGi, no compile-time dependency on
     * the check framework.
     *
     * @param query    check id (with or without the {@code bundle:} prefix the markers use) or a
     *                 substring of the id; empty lists every check that carries documentation
     * @param language {@code ru} for the Russian text, anything else for English
     * @param limit    cap on the number of entries
     */
    public CheckResult checkInfo(String query, String language, int limit) {
        CheckResult r = new CheckResult();
        r.query = query;
        boolean russian = language != null && language.toLowerCase().startsWith("ru");
        r.language = russian ? "ru" : "en";
        int cap = limit > 0 ? limit : 50;

        // A marker's check id looks like "com.e1c.v8codestyle.bsl:module-unused-local-variable" -
        // the part before the colon names the bundle, so an id copied straight from a problem works.
        String needle = query == null ? "" : query.trim();
        String bundleHint = null;
        int colon = needle.indexOf(':');
        if (colon > 0) {
            bundleHint = needle.substring(0, colon).trim();
            needle = needle.substring(colon + 1).trim();
        }
        final String id = needle;
        final String lower = id.toLowerCase();

        BundleContext ctx = context();
        if (ctx == null) {
            r.message = "no OSGi context - the plugin is not running inside EDT";
            return r;
        }
        String folder = russian ? CHECK_DOCS + "/ru" : CHECK_DOCS;
        List<CheckEntry> found = new ArrayList<>();
        for (Bundle b : ctx.getBundles()) {
            if (bundleHint != null && !bundleHint.equalsIgnoreCase(b.getSymbolicName())) {
                continue;
            }
            java.util.Enumeration<java.net.URL> entries = b.findEntries(folder, "*.html", false);
            if (entries == null) {
                continue;
            }
            while (entries.hasMoreElements()) {
                java.net.URL url = entries.nextElement();
                String path = url.getPath();
                String name = path.substring(path.lastIndexOf('/') + 1);
                String checkId = name.endsWith(".html") ? name.substring(0, name.length() - 5) : name;
                String html = readUrl(url);
                String title = html == null ? null : extractTitle(html);
                boolean byId = lower.isEmpty() || checkId.toLowerCase().contains(lower);
                // A problem does not always name the check by its slug: some carry a short code
                // (SU200) whose mapping lives in the check engine, not in these resources. The
                // message, though, is the check's own title - so matching titles answers "why did
                // this fire" from the text of the problem itself.
                boolean byTitle = !lower.isEmpty() && title != null
                        && title.toLowerCase().contains(lower);
                if (!byId && !byTitle) {
                    continue;
                }
                CheckEntry entry = new CheckEntry();
                entry.id = checkId;
                entry.bundle = b.getSymbolicName();
                entry.matchedBy = byId ? "id" : "title";
                if (html != null) {
                    entry.title = title;
                    entry.text = withoutRepeatedTitle(htmlToText(html), title);
                    entry.standards.addAll(links(html));
                }
                found.add(entry);
            }
        }
        found.sort((a, c) -> a.id.compareToIgnoreCase(c.id));
        r.total = found.size();
        // An exact id match is what a caller coming from a problem wants - never bury it in a list.
        for (CheckEntry entry : found) {
            if (entry.id.equalsIgnoreCase(id)) {
                r.checks.add(entry);
                r.ok = true;
                r.total = 1;
                r.message = "check " + entry.id + " (" + entry.bundle + ")";
                return r;
            }
        }
        for (CheckEntry entry : found) {
            if (r.checks.size() >= cap) {
                r.truncated = true;
                break;
            }
            if (found.size() > 1) {
                entry.text = null;   // a list stays a list; ask by id for the full text
                entry.standards.clear();
            }
            r.checks.add(entry);
        }
        r.ok = true;
        r.message = found.isEmpty()
                ? "no check documentation matches \"" + (query == null ? "" : query) + "\""
                : r.checks.size() + " of " + r.total + " check(s)"
                  + (r.checks.size() == 1 ? "" : " - ask by exact id for the full description");
        return r;
    }

    /**
     * The generated descriptions open with the title three times over - the {@code <title>}, an
     * {@code <h1>} anchor and a restating paragraph. Keep one.
     */
    private static String withoutRepeatedTitle(String text, String title) {
        if (text == null || title == null || title.isBlank()) {
            return text;
        }
        String[] lines = text.split("\\R");
        StringBuilder out = new StringBuilder();
        boolean kept = false;
        for (String line : lines) {
            String plain = line.strip();
            boolean isTitle = plain.equalsIgnoreCase(title.strip())
                    || plain.equalsIgnoreCase(title.strip() + ".");
            if (isTitle) {
                if (kept) {
                    continue;
                }
                kept = true;
            }
            out.append(line).append('\n');
        }
        return out.toString().strip();
    }

    /** Links out of a description: these are the development-standard pages the check enforces. */
    private static List<String> links(String html) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = LINK_RE.matcher(html);
        while (m.find()) {
            String href = m.group(1);
            if (href.startsWith("http") && !out.contains(href)) {
                out.add(href);
            }
        }
        return out;
    }

    private static final Pattern LINK_RE =
            Pattern.compile("<a\\s+[^>]*href=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    private static String readUrl(java.net.URL url) {
        try (java.io.InputStream in = url.openStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException unreadable) {
            return null;
        }
    }

    /** The OSGi context of this plugin - the way to every other bundle's resources. */
    private static BundleContext context() {
        Bundle self = FrameworkUtil.getBundle(DocsGateway.class);
        return (self == null) ? null : self.getBundleContext();
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
