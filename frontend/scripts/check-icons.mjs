// Fails when the app uses a Material Symbols name that is not in the self-hosted subset.
//
// The icon font is a subset (see scripts/subset-material-symbols.py): a name missing from it would
// render as its literal text ("inventory_2") instead of a glyph. This scan runs as part of
// `npm run lint`, so that mistake fails CI instead of shipping.
//
// It recognises the three ways the app names icons:
//   <mat-icon ...>name</mat-icon>                 literal
//   <mat-icon ...>{{ cond ? 'a' : 'b' }}</mat-icon>  quoted names inside an interpolation
//   icon: 'name'                                  icon fields in TypeScript metadata objects
import { readdir, readFile } from 'node:fs/promises';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
const manifestPath = path.join(root, 'src/assets/fonts/material-symbols.icons.txt');

const manifest = new Set(
  (await readFile(manifestPath, 'utf8'))
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l && !l.startsWith('#')),
);

async function* files(dir) {
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name !== 'generated') yield* files(full);
    } else if (/\.(ts|html)$/.test(entry.name) && !entry.name.endsWith('.spec.ts')) {
      yield full;
    }
  }
}

const used = new Map();
const note = (name, file) => used.set(name, [...(used.get(name) ?? []), path.relative(root, file)]);

for await (const file of files(path.join(root, 'src'))) {
  const text = await readFile(file, 'utf8');
  for (const [, body] of text.matchAll(/<mat-icon\b[^>]*>([\s\S]*?)<\/mat-icon>/g)) {
    const content = body.trim();
    if (content.includes('{{')) {
      for (const [, name] of content.matchAll(/'([a-z0-9_]+)'/g)) note(name, file);
    } else if (content) {
      note(content, file);
    }
  }
  for (const [, name] of text.matchAll(/\bicon:\s*'([a-z0-9_]+)'/g)) note(name, file);
}

const missing = [...used.keys()].filter((name) => !manifest.has(name)).sort();
const unused = [...manifest].filter((name) => !used.has(name)).sort();

if (unused.length) {
  console.warn(`Icons in the subset but not used (consider removing): ${unused.join(', ')}`);
}
if (missing.length) {
  console.error('Icons used but missing from the self-hosted Material Symbols subset:');
  for (const name of missing) console.error(`  ${name}  (${used.get(name).join(', ')})`);
  console.error('Add them to src/assets/fonts/material-symbols.icons.txt and run `npm run icons:subset`.');
  process.exit(1);
}
console.log(`Icons OK: ${used.size} used, all present in the ${manifest.size}-icon subset.`);
