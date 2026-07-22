// Mirrors the CHANGELOG into a site page: the single source of truth is the file at the
// repository root, and docs/changelog*.md is assembled from it before the site build
// (npm run sync:docs, called from prebuild). Never edit docs/changelog*.md by hand.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const PAGES = [
  {
    from: 'CHANGELOG.md',
    to: 'docs/changelog.md',
    note: (from) => `Assembled from ${from} by scripts/sync-docs.mjs. Do not edit by hand.`,
    front: {
      title: 'Changelog',
      description: 'What changed in EDT-Bridge from release to release, grouped by day.',
      label: 'Changelog',
      order: 6,
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
      order: 6,
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

for (const p of PAGES) {
  const src = fs.readFileSync(path.join(root, p.from), 'utf8');
  const head =
    `---\ntitle: "${p.front.title}"\ndescription: "${p.front.description}"\n` +
    `sidebar:\n  label: ${p.front.label}\n  order: ${p.front.order}\n---\n\n` +
    `<!-- ${p.note(p.from)} -->\n\n`;
  fs.writeFileSync(path.join(root, p.to), head + strip(src) + '\n');
  console.log(`${p.from} -> ${p.to}`);
}
