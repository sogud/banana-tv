import { build } from 'esbuild';
import { createHash } from 'node:crypto';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.dirname(fileURLToPath(import.meta.url));
const destination = process.argv[2] || path.join(root, '../build/generated/sourceScriptAssets');
await mkdir(destination, { recursive: true });
const result = await build({
  absWorkingDir: root,
  entryPoints: ['libraries.js'],
  bundle: true,
  platform: 'browser',
  // Bundle the browser source so transitive versions and their license notices stay explicit.
  alias: { 'jsonpath-plus': path.join(root, 'node_modules/jsonpath-plus/src/jsonpath-browser.js') },
  format: 'esm',
  target: 'es2020',
  minify: true,
  legalComments: 'eof',
  metafile: true,
  write: false,
});

// Only packages actually present in the bundle enter the shipped notice file.
const packageDirectories = new Set();
for (const input of Object.keys(result.metafile.inputs)) {
  const marker = input.lastIndexOf('node_modules/');
  if (marker < 0) continue;
  const parts = input.slice(marker + 'node_modules/'.length).split('/');
  const packageName = parts[0].startsWith('@') ? `${parts[0]}/${parts[1]}` : parts[0];
  packageDirectories.add(input.slice(0, marker) + 'node_modules/' + packageName);
}
const allowed = new Set(['MIT', 'BSD-2-Clause', 'BSD-3-Clause', 'ISC']);
const dependencies = [];
const notices = [];
for (const directory of [...packageDirectories].sort()) {
  const packageRoot = path.join(root, directory);
  const info = JSON.parse(await readFile(path.join(packageRoot, 'package.json'), 'utf8'));
  if (!allowed.has(info.license)) throw new Error(`Unreviewed license: ${info.name}`);
  let license;
  for (const filename of ['LICENSE', 'LICENSE.md', 'LICENSE.txt', 'LICENSE-MIT.txt', 'LICENSE-MIT']) {
    try { license = await readFile(path.join(packageRoot, filename), 'utf8'); break; }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
  if (!license && info.name === 'boolbase' && info.version === '1.0.0') {
    license = await readFile(path.join(root, 'licenses/boolbase-1.0.0.LICENSE'), 'utf8');
  }
  if (!license) throw new Error(`Missing license text: ${info.name}`);
  dependencies.push({ name: info.name, version: info.version, license: info.license,
    noticeSha256: createHash('sha256').update(license).digest('hex') });
  notices.push(`${info.name} ${info.version} (${info.license})\n\n${license.trim()}\n`);
}
const bundle = result.outputFiles[0].contents;
await writeFile(path.join(destination, 'banana-libraries.js'), bundle);
await writeFile(path.join(destination, 'banana-library-notices.txt'), notices.join('\n-----\n\n'));
await writeFile(path.join(destination, 'banana-library-manifest.json'), JSON.stringify({
  sha256: createHash('sha256').update(bundle).digest('hex'),
  bytes: bundle.length,
  dependencies,
}, null, 2) + '\n');
process.stdout.write(`Bundled ${dependencies.length} packages, ${bundle.length} bytes.\n`);
