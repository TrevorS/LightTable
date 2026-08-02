// Compile every TypeScript tree, at the same time.
//
// The sibling of `script/typecheck.mts`, and the same argument: five `tsc`
// runs over five disjoint file sets, chained with `&&`, each paying process
// start and a full lib parse before it began. None of the five imports
// another — they are siblings, not a stack — so the chaining bought only
// stopping at the first failure, and seeing every tree's errors in one run is
// worth more.
//
// These emit, where typecheck.mts does not, which is the reason for two files
// rather than a flag: the emitting set is five of the seven. `tsconfig.test`
// and `tsconfig.scripts` are check-only, because node strips those types
// itself.
//
// Order matters exactly once, and not here: shadow-cljs consumes
// `src-window/`'s output, so this whole step runs before it.

import { spawn } from 'node:child_process';
import * as path from 'node:path';

const ROOT = path.join(import.meta.dirname, '..');

const PROJECTS = [
    'tsconfig.json',                   // the main process and the preload
    'tsconfig.window.json',            // src-window, consumed by shadow-cljs
    'tsconfig.worker.json',            // src-worker, node without a DOM
    'tsconfig.browser.json',           // the client served to a connected page
    'plugins/TypeScript/tsconfig.json'
];

/** `tsc -p project`, resolving to its output rather than printing it. */
function build(project: string): Promise<{ project: string; ok: boolean; out: string }> {
    return new Promise((resolve) => {
        const tsc = spawn('npx', ['tsc', '-p', project],
                          { cwd: ROOT, env: { ...process.env, npm_config_loglevel: 'silent' } });
        let out = '';
        tsc.stdout.on('data', (d) => { out += d; });
        tsc.stderr.on('data', (d) => { out += d; });
        tsc.on('close', (code) => resolve({ project, ok: code === 0, out }));
    });
}

const results = await Promise.all(PROJECTS.map(build));
const failed = results.filter((r) => !r.ok);

for (const { project, out } of failed) {
    console.error(`\n${project}`);
    console.error(out.split('\n').filter((l) => !l.startsWith('npm notice')).join('\n').trim());
}

if (failed.length) process.exit(1);
console.log(`${results.length} TypeScript projects built`);
