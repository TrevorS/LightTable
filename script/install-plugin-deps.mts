#!/usr/bin/env node

// Installs the third-party npm packages a plugin in this repository depends on.
//
// The Javascript plugin needs acorn to find the boundaries of the expression
// under the cursor, and harbor to pick a free port for the node debugger.
// Upstream shipped both by committing node_modules; here they are ordinary
// dependencies of an ordinary package.json, installed at build time. That is
// not the "code fetched at build time" the repository rules out — a plugin's
// own source lives here, and a third-party package is a dependency like any
// other.
//
// `npm ci` is the install, so the lockfile decides. It is skipped when the
// tree already matches the lockfile it was installed from, because `npm ci`
// deletes node_modules before every run and this sits in the inner build loop.

import * as fs from 'node:fs';
import * as path from 'node:path';
import * as crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { ROOT } from './lib/paths.mts';

const PLUGINS = path.join(ROOT, 'plugins');

// Written into node_modules so it disappears with the tree it describes.
const STAMP = '.lt-lockfile-sha256';

function main(): void {
    if (!fs.existsSync(PLUGINS)) return;

    for (const name of fs.readdirSync(PLUGINS).sort()) {
        const dir = path.join(PLUGINS, name);
        const lock = path.join(dir, 'package-lock.json');
        if (!fs.existsSync(path.join(dir, 'package.json'))) continue;
        if (!fs.existsSync(lock)) {
            console.error(`${name}: package.json without package-lock.json. ` +
                          `Run \`npm install\` in ${dir} and commit the lockfile.`);
            process.exit(1);
        }

        const want = crypto.createHash('sha256').update(fs.readFileSync(lock)).digest('hex');
        const stamp = path.join(dir, 'node_modules', STAMP);
        if (fs.existsSync(stamp) && fs.readFileSync(stamp, 'utf8') === want) {
            console.log(`${name}: dependencies up to date`);
            continue;
        }

        console.log(`${name}: installing dependencies`);
        execFileSync('npm', ['ci', '--omit=dev', '--no-audit', '--no-fund'],
                     { cwd: dir, stdio: 'inherit' });
        fs.writeFileSync(stamp, want);
    }
}

main();
