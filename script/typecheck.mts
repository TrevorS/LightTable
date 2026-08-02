// Type-check every project, at the same time.
//
// Seven `tsc --noEmit` runs over seven disjoint file sets, chained with `&&`,
// each paying process start and a full lib parse before it began. They share no
// state and cannot fail each other, so the only thing the chaining bought was
// stopping at the first failure — and stopping early is worth less than seeing
// every project's errors in one run.
//
// Not project references: none of these trees imports another, so there is no
// dependency graph for `composite` to skip work over. What was actually slow
// was doing them one after another.

import { spawn } from 'node:child_process';
import * as path from 'node:path';

const ROOT = path.join(import.meta.dirname, '..');

const PROJECTS = [
    'tsconfig.json',                   // the main process
    'tsconfig.window.json',            // src-window, consumed by shadow-cljs
    'tsconfig.worker.json',            // src-worker, node without a DOM
    'tsconfig.browser.json',           // the client served to a connected page
    'plugins/TypeScript/tsconfig.json',
    'tsconfig.test.json',              // check-only: node strips the types
    'tsconfig.scripts.json'            // check-only
];

/** `tsc -p project --noEmit`, resolving to its output rather than printing it. */
function check(project: string): Promise<{ project: string; ok: boolean; out: string }> {
    return new Promise((resolve) => {
        const tsc = spawn('npx', ['tsc', '-p', project, '--noEmit'],
                          { cwd: ROOT, env: { ...process.env, npm_config_loglevel: 'silent' } });
        let out = '';
        tsc.stdout.on('data', (d) => { out += d; });
        tsc.stderr.on('data', (d) => { out += d; });
        tsc.on('close', (code) => resolve({ project, ok: code === 0, out }));
    });
}

const results = await Promise.all(PROJECTS.map(check));
const failed = results.filter((r) => !r.ok);

for (const { project, out } of failed) {
    console.error(`\n${project}`);
    console.error(out.split('\n').filter((l) => !l.startsWith('npm notice')).join('\n').trim());
}

if (failed.length) process.exit(1);
console.log(`${results.length} projects type-check`);
