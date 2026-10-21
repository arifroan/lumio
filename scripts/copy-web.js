const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const output = path.join(root, 'www');
const files = ['index.html', 'styles.css', 'app.js', 'manifest.webmanifest', 'icon.svg', 'sw.js'];

fs.rmSync(output, { recursive: true, force: true });
fs.mkdirSync(output, { recursive: true });
for (const file of files) {
  const source = path.join(root, file);
  if (!fs.existsSync(source)) {
    throw new Error('Required web asset is missing: ' + file);
  }
  fs.copyFileSync(source, path.join(output, file));
}
console.log('Lumio web assets copied to www/.');
