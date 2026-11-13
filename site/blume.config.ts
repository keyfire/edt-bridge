// Configuration of the documentation site (Blume, an Astro + Vite engine). Published to
// GitHub Pages from .github/workflows/docs.yml. The English pages live in docs/, the Russian
// ones in docs/ru/ under the same paths (the directory i18n mode, Blume's default). Check a
// build locally with: npx blume build.
import { defineConfig } from "blume";

export default defineConfig({
  title: "EDT-Bridge",
  description:
    "A 1C:EDT plugin that exposes EDT's live semantic model to AI agents over MCP: " +
    "the environment's own diagnostics, real metadata and types, query validation " +
    "against the project, plus write tools, infobases and a debugger that go through " +
    "EDT's own engine.",

  // All site content is in docs/. The changelog, onboarding and security pages mirror the
  // documents at the repository root and are assembled by scripts/sync-docs.mjs
  // (npm run sync:docs). The content is shared with the repository: the pages sit in ../docs
  // next to the sources, so a doc edit and a code edit live in one place.
  //
  // A folder is a sidebar group. A folder in parentheses - (setup), (project) - groups its
  // pages without adding a URL segment, so /install and /security keep their addresses; a
  // plain folder - tools, native-forms - adds one. Each folder has a meta.ts with the group's
  // title and order, and the Russian tree has its own, which is why the Russian pages are in
  // docs/ru/: with the filename-suffix mode a meta.ts applies to every language, and the
  // Russian sidebar would carry English group titles.
  content: {
    root: "../docs",
    // DESIGN and BACKLOG are working notes for whoever edits the bridge itself: the
    // first covers the internals, the second is not even in the repository (a local file).
    // Neither belongs on the documentation site. docs/ru/ also holds the Russian editions of
    // the root documents (README.ru.md, CHANGELOG.ru.md and the others); they end in .ru.md
    // and are left out by that suffix.
    exclude: ["**/_*", "**/.*", "DESIGN*.md", "BACKLOG*.md", "ru/*.ru.md"],
  },

  // The site is served from the /edt-bridge/ subpath of the shared documentation domain:
  // `base` moves the whole site there and rewrites internal links and assets; `site` is the
  // origin for sitemap/canonical/OG. The domain is held by the keyfire.github.io repository.
  deployment: {
    base: "/edt-bridge",
    site: "https://docs.keyfire.ru",
  },

  // The repository: an "Edit on GitHub" link under every page and a repo icon in the header.
  github: {
    owner: "keyfire",
    repo: "edt-bridge",
  },

  // Search: FlexSearch rather than the default Orama. Both are keyless and share the same
  // static /blume-search.json index, but Orama tokenizes with its English splitter
  // (/[^A-Za-zàèéìòóù0-9_'-]+/), which treats every Cyrillic letter as a separator: the whole
  // Russian half of the site collapses to zero tokens, so every Russian query answered
  // "nothing found". Orama's tokenizer is fixed inside Blume and takes no language from this
  // config, whereas FlexSearch's default encoder is Unicode-aware and indexes Cyrillic as
  // words. Its "forward" tokenization also prefix-matches, which stands in for the stemming
  // an inflected language would otherwise need.
  search: {
    provider: "flexsearch",
  },

  // The "last modified" date comes from the git history (CI needs fetch-depth: 0).
  lastModified: true,

  // Yandex.Metrika, counter 110620738 - one counter for the whole docs.keyfire.ru domain, so
  // the same block stands in the xbsl and elemctl configs. Blume emits analytics in production
  // builds only (blume build, never blume dev), and it renders <script> tags: the <noscript>
  // pixel of the standard snippet is left out, as it would only count visitors with JavaScript
  // off, whom the tag cannot count either.
  analytics: {
    scripts: [
      {
        content: `(function(m,e,t,r,i,k,a){m[i]=m[i]||function(){(m[i].a=m[i].a||[]).push(arguments)};
m[i].l=1*new Date();
for (var j = 0; j < document.scripts.length; j++) { if (document.scripts[j].src === r) { return; } }
k=e.createElement(t),a=e.getElementsByTagName(t)[0],k.async=1,k.src=r,a.parentNode.insertBefore(k,a)})
(window, document, "script", "https://mc.yandex.ru/metrika/tag.js", "ym");
ym(110620738, "init", { clickmap: true, trackLinks: true, accurateTrackBounce: true });`,
      },
    ],
  },

  // Bilingual: English at the content root (docs/Name.md), Russian in the locale folder
  // (docs/ru/Name.md). The Russian UI pack ships with Blume - only the content is ours to
  // translate.
  i18n: {
    defaultLocale: "en",
    locales: [
      { code: "en", label: "English" },
      { code: "ru", label: "Русский" },
    ],
  },

  // The contributor guides live on GitHub rather than as site pages, so they are pinned
  // as links above the sidebar.
  navigation: {
    featured: [
    // The neighbouring tools: reachable from every page, not just the front one. They
    // point at the Russian versions - the receiving site carries a language switcher.
      {
        label: "XBSL",
        href: "https://docs.keyfire.ru/xbsl/ru/",
        icon: "spell-check",
      },
      {
        label: "Elemctl",
        href: "https://docs.keyfire.ru/elemctl/ru/",
        icon: "upload-cloud",
      },
      {
        label: "Contributing",
        href: "https://github.com/keyfire/edt-bridge/blob/main/CONTRIBUTING.md",
        icon: "git-pull-request",
      },
    ],
  },

  // An orange accent - it matches the diagrams in the documentation.
  theme: {
    accent: "orange",
    // Code is set in Geist Mono: at the small size of the table chips its Cyrillic reads
    // more evenly than the default IBM Plex Mono. Astro downloads its Latin subset only -
    // the Cyrillic one comes from theme.css, which names the family variable of every
    // role of this block, so a font changed here has to be renamed there too.
    fonts: { mono: "geist-mono" },
  },
});
