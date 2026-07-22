// Configuration of the documentation site (Blume, an Astro + Vite engine). Published to
// GitHub Pages from .github/workflows/docs.yml. The content lives in docs/ as Name.md +
// Name.ru.md pairs (the suffix i18n mode, parser: "dot"). Check a build locally with:
// npx blume build.
import { defineConfig } from "blume";

export default defineConfig({
  title: "EDT-Bridge",
  description:
    "A 1C:EDT plugin that exposes EDT's live semantic model to AI agents over MCP: " +
    "the environment's own diagnostics, real metadata and types, query validation " +
    "against the project, plus write tools that go through EDT's own engine.",

  // All site content is in docs/. The changelog*.md pages mirror the CHANGELOG at the
  // repository root and are assembled by scripts/sync-docs.mjs (npm run sync:docs).
  // The content is shared with the repository: the pages sit in ../docs next to the
  // sources, so a doc edit and a code edit live in one place.
  content: {
    root: "../docs",
    // DESIGN and BACKLOG are working notes for whoever edits the bridge itself: the
    // first covers the internals, the second is not even in the repository (a local file).
    // Neither belongs on the documentation site.
    exclude: ["**/_*", "**/.*", "DESIGN*.md", "BACKLOG*.md", "ru/**"],
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

  // The "last modified" date comes from the git history (CI needs fetch-depth: 0).
  lastModified: true,

  // Bilingual: English by default (Name.md), Russian by the .ru suffix (Name.ru.md).
  // The Russian UI pack ships with Blume - only the content is ours to translate.
  i18n: {
    defaultLocale: "en",
    locales: [
      { code: "en", label: "English" },
      { code: "ru", label: "Русский" },
    ],
    parser: "dot",
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
  },
});
