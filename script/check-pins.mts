// The versions that are pinned in two places, and have to agree.
//
// Run by `npm run check`, because the failure it prevents is silent: the root
// `electron` devDependency exists only so `src-electron/*.ts` can be type-
// checked against Electron's API surface, and `deploy/electron` is the copy
// that actually gets downloaded and shipped. Bump one and not the other and
// the main process type-checks against an Electron it will never run on.

import * as fs from 'node:fs';
import * as path from 'node:path';

const ROOT = path.join(import.meta.dirname, '..');

function version(file: string, field: 'dependencies' | 'devDependencies', name: string): string {
    const json = JSON.parse(fs.readFileSync(path.join(ROOT, file), 'utf8'));
    const found = json[field]?.[name];
    if (!found) throw new Error(`${file} has no ${field}.${name}`);
    return String(found).replace(/^[\^~]/, '');
}

const shipped = version('deploy/electron/package.json', 'devDependencies', 'electron');
const checked = version('package.json', 'devDependencies', 'electron');

if (shipped !== checked) {
    console.error(
        `electron is pinned twice and the pins disagree:\n` +
        `  deploy/electron/package.json  ${shipped}   (downloaded, shipped)\n` +
        `  package.json                  ${checked}   (types for src-electron)\n` +
        `Set both to the same version.`);
    process.exit(1);
}

console.log(`electron ${shipped} — pinned the same in both places`);
