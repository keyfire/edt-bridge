// Mirrors root documents (CHANGELOG, ONBOARDING) into site pages: the single source of
// truth is the file at the repository root, and the mirrored docs/*.md pages are assembled
// from it before the site build (npm run sync:docs, called from prebuild). Never edit the
// mirrored pages by hand.
//
// The tool catalogue travels the other way. It is one long table that used to be kept by hand
// in both the README and the site page - and it drifted: `edt_designer_agent` grew a `sweep`
// action on the page while the README still listed three. So docs/tools*.md is the source
// now, and the README's "Tools" section is filled from it between the marker comments.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const PAGES = [
  {
    from: 'ONBOARDING.md',
    to: 'docs/onboarding.md',
    note: (from) => `Assembled from ${from} by scripts/sync-docs.mjs. Do not edit by hand.`,
    front: {
      title: 'Quick start',
      description:
        'From zero to a working bridge: install via pipx, connect to Claude Code, verify, update.',
      label: 'Quick start',
      order: 2,
    },
    // Relative links that leave the docs/ content root would break on the site;
    // they are rewritten to absolute GitHub URLs.
    links: {
      'README.md': 'https://github.com/keyfire/edt-bridge/blob/main/README.md',
    },
  },
  {
    from: 'docs/ru/ONBOARDING.ru.md',
    to: 'docs/onboarding.ru.md',
    note: (from) => `Собрано из ${from} скриптом scripts/sync-docs.mjs. Не редактировать вручную.`,
    front: {
      title: 'Быстрый старт',
      description:
        'От нуля до работающего моста: установка через pipx, подключение к Claude Code, проверка, обновление.',
      label: 'Быстрый старт',
      order: 2,
    },
    links: {
      'README.ru.md': 'https://github.com/keyfire/edt-bridge/blob/main/docs/ru/README.ru.md',
    },
  },
  {
    from: 'CHANGELOG.md',
    to: 'docs/changelog.md',
    note: (from) => `Assembled from ${from} by scripts/sync-docs.mjs. Do not edit by hand.`,
    front: {
      title: 'Changelog',
      description: 'What changed in EDT-Bridge from release to release, grouped by day.',
      label: 'Changelog',
      order: 7,
    },
  },
  {
    from: 'docs/ru/CHANGELOG.ru.md',
    to: 'docs/changelog.ru.md',
    note: (from) => `Собрано из ${from} скриптом scripts/sync-docs.mjs. Не редактировать вручную.`,
    front: {
      title: 'История изменений',
      description: 'Что менялось в EDT-Bridge от версии к версии, с разбивкой по дням.',
      label: 'История изменений',
      order: 7,
    },
  },
];

// Sections injected from a site page into a repository document, the other direction. The
// marker comments stay in the target file, so the surrounding text is edited by hand as usual.
const INJECTIONS = [
  { from: 'docs/tools.md', into: 'README.md', marker: 'tools' },
  { from: 'docs/tools.ru.md', into: 'docs/ru/README.ru.md', marker: 'tools' },
];

// The leading heading and the language-switcher line are dropped: the site sets the
// heading from the frontmatter and switches the language with its own button.
const isSwitcherLine = (l) =>
  l.startsWith('**English**') || l.startsWith('**Английская') || l.startsWith('[English]');

const strip = (text) => {
  const lines = text.split('\n').filter((l) => !isSwitcherLine(l));
  // The heading is dropped wherever it stands among the leading lines, not only on line 0:
  // in the Russian changelog the switcher comes first, so an index check left the H1 in
  // place and the page showed its title twice.
  const first = lines.findIndex((l) => l.trim() !== '');
  if (first !== -1 && lines[first].startsWith('# ')) lines.splice(first, 1);
  return lines.join('\n').trim();
};

const rewriteLinks = (text, links = {}) =>
  Object.entries(links).reduce((t, [from, to]) => t.split(`](${from})`).join(`](${to})`), text);

// A site page without its frontmatter and without the generator notes: what a repository
// document embeds.
const pageBody = (text) =>
  text
    .replace(/^---\n[\s\S]*?\n---\n/, '')
    .replace(/<!--[\s\S]*?-->\n?/g, '')
    .trim();

// The two surfaces need different files for the same diagram. A page shows the SVG, which
// carries both palettes and follows the reader's theme; a README on GitHub follows no theme
// and needs the PNG with a palette baked in. So the injected copy swaps the extension - the
// page kept a PNG for a while for exactly this reason, and showed a dark picture to a reader
// in a light theme.
export const readmeImages = (text) => text.replace(/(docs\/[\w.-]+)\.svg\)/g, '$1.png)');

for (const p of PAGES) {
  const src = fs.readFileSync(path.join(root, p.from), 'utf8');
  const head =
    `---\ntitle: "${p.front.title}"\ndescription: "${p.front.description}"\n` +
    `sidebar:\n  label: ${p.front.label}\n  order: ${p.front.order}\n---\n\n` +
    `<!-- ${p.note(p.from)} -->\n\n`;
  fs.writeFileSync(path.join(root, p.to), head + rewriteLinks(strip(src), p.links) + '\n');
  console.log(`${p.from} -> ${p.to}`);
}

for (const inj of INJECTIONS) {
  const target = path.join(root, inj.into);
  const text = fs.readFileSync(target, 'utf8');
  const open = `<!-- ${inj.marker}:start -->`;
  const close = `<!-- ${inj.marker}:end -->`;
  const from = text.indexOf(open);
  const to = text.indexOf(close);
  if (from === -1 || to === -1) {
    throw new Error(`${inj.into}: markers ${open} ... ${close} not found`);
  }
  const body = readmeImages(pageBody(fs.readFileSync(path.join(root, inj.from), 'utf8')));
  const next = `${text.slice(0, from + open.length)}\n\n${body}\n\n${text.slice(to)}`;
  fs.writeFileSync(target, next);
  console.log(`${inj.from} -> ${inj.into} (${inj.marker})`);
}
