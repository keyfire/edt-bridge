// Mirrors root documents (CHANGELOG, ONBOARDING, SECURITY) into site pages: the single source
// of truth is the file at the repository root, and the mirrored pages under docs/ are assembled
// from it before the site build (npm run sync:docs, called from prebuild). Never edit the
// mirrored pages by hand.
//
// The table of tool groups travels the other way. The catalogue lives on the pages of
// docs/tools/, and the README's "Tools" section carries one section of the overview page
// between the marker comments: the table that names each group and links its page. It is
// injected and not kept by hand because a hand-kept copy of the catalogue had drifted from the
// page: `edt_designer_agent` grew a `sweep` action there while the README still listed three.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const enNote = (from) => `Assembled from ${from} by scripts/sync-docs.mjs. Do not edit by hand.`;
const ruNote = (from) => `Собрано из ${from} скриптом scripts/sync-docs.mjs. Не редактировать вручную.`;

const PAGES = [
  {
    from: 'ONBOARDING.md',
    to: 'docs/onboarding.md',
    note: enNote,
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
    to: 'docs/ru/onboarding.md',
    note: ruNote,
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
    from: 'SECURITY.md',
    to: 'docs/(project)/security.md',
    note: enNote,
    front: {
      title: 'Security',
      description:
        'How writes are gated, what the token protects, and how to report a security problem privately.',
      label: 'Security',
      order: 1,
    },
  },
  {
    from: 'docs/ru/SECURITY.ru.md',
    to: 'docs/ru/(project)/security.md',
    note: ruNote,
    front: {
      title: 'Безопасность',
      description:
        'Чем закрыта запись, что защищает токен и как сообщить о проблеме безопасности приватно.',
      label: 'Безопасность',
      order: 1,
    },
  },
  {
    from: 'CHANGELOG.md',
    to: 'docs/(project)/changelog.md',
    note: enNote,
    front: {
      title: 'Changelog',
      description: 'What changed in EDT-Bridge from release to release, grouped by day.',
      label: 'Changelog',
      order: 2,
    },
  },
  {
    from: 'docs/ru/CHANGELOG.ru.md',
    to: 'docs/ru/(project)/changelog.md',
    note: ruNote,
    front: {
      title: 'История изменений',
      description: 'Что менялось в EDT-Bridge от версии к версии, с разбивкой по дням.',
      label: 'История изменений',
      order: 2,
    },
  },
];

// Sections injected from a site page into a repository document, the other direction. The
// marker comments stay in the target file, so the surrounding text is edited by hand as usual.
// `section` names the `## ` heading whose body is injected; without it the whole page goes.
const INJECTIONS = [
  { from: 'docs/tools/index.md', section: 'Tool groups', into: 'README.md', marker: 'tools' },
  {
    from: 'docs/ru/tools/index.md',
    section: 'Группы инструментов',
    into: 'docs/ru/README.ru.md',
    marker: 'tools',
  },
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
    .replace(/^---\r?\n[\s\S]*?\r?\n---\r?\n/, '')
    .replace(/<!--[\s\S]*?-->\n?/g, '')
    .trim();

// The body of one `## Section` of a page, up to the next heading of that level. The guard in
// scripts/check_docs.py reads the section the same way, so the two have to agree on where a
// section ends.
export const sectionBody = (text, section) => {
  const lines = text.split('\n');
  const start = lines.indexOf(`## ${section}`);
  if (start === -1) throw new Error(`no section "## ${section}"`);
  const rest = lines.slice(start + 1);
  const end = rest.findIndex((l) => l.startsWith('## '));
  return (end === -1 ? rest : rest.slice(0, end)).join('\n').trim();
};

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
  fs.mkdirSync(path.dirname(path.join(root, p.to)), { recursive: true });
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
  const source = fs.readFileSync(path.join(root, inj.from), 'utf8');
  const body = readmeImages(inj.section ? sectionBody(source, inj.section) : pageBody(source));
  const next = `${text.slice(0, from + open.length)}\n\n${body}\n\n${text.slice(to)}`;
  fs.writeFileSync(target, next);
  console.log(`${inj.from}${inj.section ? ` / ${inj.section}` : ''} -> ${inj.into} (${inj.marker})`);
}
