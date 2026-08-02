// Everything the window runs: its TypeScript, the ClojureScript bundle, the
// worker, the analysis caches, and the plugins.
//
// This was a six-link `&&` chain in package.json, three of whose links were
// `npm run --silent something-else` — so reading it meant reading four scripts,
// and each nested `npm run` paid a process start to do it. A shell chain also
// says nothing about *why* the order is what it is, and the order is the only
// interesting thing about this: shadow-cljs consumes what tsc emits, the cache
// prune reads what shadow wrote, and the user plugin is a copy of a module the
// bundle produced.
//
// Steps run in order and stop at the first failure, which is what `&&` did.

import { spawn } from 'node:child_process';
import * as fs from 'node:fs';
import * as path from 'node:path';

const ROOT = path.join(import.meta.dirname, '..');

function run(command: string, args: string[]): Promise<void> {
    return new Promise((resolve, reject) => {
        const child = spawn(command, args, { cwd: ROOT, stdio: 'inherit' });
        child.on('close', (code) =>
            code === 0 ? resolve() : reject(new Error(`${command} ${args.join(' ')} exited ${code}`)));
        child.on('error', reject);
    });
}

const node = (script: string, ...args: string[]) =>
    run(process.execPath, [path.join(ROOT, 'script', script), ...args]);

const STEPS: Array<[string, () => Promise<void>]> = [
    // First, because shadow-cljs bundles src-window's output and the plugins'.
    ['TypeScript', () => node('build-ts.mts')],

    // The window bundle and the default user plugin as one module; the worker,
    // which runs under node rather than in the window; and the analysis caches
    // that let the editor compile ClojureScript inside itself.
    ['ClojureScript', () => run('npx', ['shadow-cljs', 'release', 'app', 'worker', 'bootstrap'])],

    // :bootstrap emits analysis for every namespace it walked, and the bundle
    // already contains most of them. Reads what the step above wrote.
    ['cljs-cache', () => node('prune-cljs-cache.mts')],

    // Each plugin's own npm dependencies, private and lockfile-pinned, then the
    // plugins themselves into deploy/plugins.
    ['plugin deps', () => node('install-plugin-deps.mts')],
    ['plugins', () => node('place-plugins.mts')],

    // The default user plugin is a module of the bundle above rather than a
    // build of its own, so shipping it is a copy. This was a `node -e` one
    // liner with the two paths quoted inside a JSON string inside a shell word.
    ['user plugin', async () => {
        fs.copyFileSync(path.join(ROOT, 'deploy/core/lighttable/user.js'),
                        path.join(ROOT, 'deploy/core/User/user_compiled.js'));
    }],

    // Last, so it describes a build that finished. `:version` reads it, which
    // is how a window answers "am I the code you just wrote".
    ['stamp', () => node('stamp-build.mts')]
];

for (const [name, step] of STEPS) {
    try {
        await step();
    } catch (e) {
        console.error(`\nbuild:cljs failed at "${name}": ${(e as Error).message}`);
        process.exit(1);
    }
}
