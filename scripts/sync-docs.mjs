// Mirrors root documents (CHANGELOG, ONBOARDING) into site pages: the single source of
// truth is the file at the repository root, and the mirrored docs/*.md pages are assembled
// from it before the site build (npm run sync:docs, called from prebuild). Never edit the
// mirrored pages by hand.
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

for (const p of PAGES) {
  const src = fs.readFileSync(path.join(root, p.from), 'utf8');
  const head =
    `---\ntitle: "${p.front.title}"\ndescription: "${p.front.description}"\n` +
    `sidebar:\n  label: ${p.front.label}\n  order: ${p.front.order}\n---\n\n` +
    `<!-- ${p.note(p.from)} -->\n\n`;
  fs.writeFileSync(path.join(root, p.to), head + rewriteLinks(strip(src), p.links) + '\n');
  console.log(`${p.from} -> ${p.to}`);
}
