/* Copies the website (index.html and friends) into desktop/site/ so the
 * Windows app carries its own copy of the app. main.js serves this copy
 * when GitHub Pages can't be reached — no internet — so the app still opens
 * on the PC that runs Jellyfin. Runs automatically before `npm start` and
 * `npm run dist`, and in CI. */
'use strict';
const fs = require('fs');
const path = require('path');

const root = path.resolve(__dirname, '..', '..');
const out = path.resolve(__dirname, '..', 'site');

const files = [
  'index.html',
  'sw.js',
  'manifest.webmanifest',
  'icon-192.png',
  'icon-512.png',
  'icon-512-maskable.png',
];
const dirs = ['fonts'];

fs.rmSync(out, { recursive: true, force: true });
fs.mkdirSync(out, { recursive: true });

for (const f of files) {
  const src = path.join(root, f);
  if (!fs.existsSync(src)) throw new Error('missing ' + src);
  fs.copyFileSync(src, path.join(out, f));
}
for (const d of dirs) {
  fs.cpSync(path.join(root, d), path.join(out, d), { recursive: true });
}
console.log('site copied to', out);
