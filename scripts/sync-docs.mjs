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
    front: {
      title: 'Changelog',
      description: 'Every notable change to EDT-Bridge, newest first.',
      label: 'Changelog',
      order: 6,
    },
  },
  {
    from: 'docs/ru/CHANGELOG.ru.md',
    to: 'docs/changelog.ru.md',
    front: {
      title: 'История изменений',
      description: 'Заметные изменения EDT-Bridge, свежие сверху.',
      label: 'История изменений',
      order: 6,
    },
  },
];

// The leading heading and the language-switcher line are dropped: the site sets the
// heading from the frontmatter and switches the language with its own button.
const strip = (text) =>
  text
    .split('\n')
    .filter((l, i) => !(i === 0 && l.startsWith('# ')))
    .filter((l) => !(l.startsWith('**English**') || l.startsWith('**Английская') || l.startsWith('[English]')))
    .join('\n')
    .trim();

for (const p of PAGES) {
  const src = fs.readFileSync(path.join(root, p.from), 'utf8');
  const head =
    `---\ntitle: "${p.front.title}"\ndescription: "${p.front.description}"\n` +
    `sidebar:\n  label: ${p.front.label}\n  order: ${p.front.order}\n---\n\n` +
    `<!-- Собрано из ${p.from} скриптом scripts/sync-docs.mjs. Не редактировать вручную. -->\n\n`;
  fs.writeFileSync(path.join(root, p.to), head + strip(src) + '\n');
  console.log(`${p.from} -> ${p.to}`);
}
