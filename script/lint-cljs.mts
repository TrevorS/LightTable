// clj-kondo over every tree that holds ClojureScript.
//
// `plugins` rather than the six `plugins/<Name>/src` paths this named by hand:
// that list was exactly right and would have been silently wrong the first
// time a plugin added ClojureScript, which is the failure mode of every
// hand-maintained list of things a gate looks at. clj-kondo walks the
// directory and finds the same nine files.
//
// `--fail-level error` because warnings are already zero and stay that way;
// a warning that appears is a thing to fix, not a thing to fail the build,
// and clj-kondo's exit code says which happened.

import { spawn } from 'node:child_process';
import * as path from 'node:path';

const ROOT = path.join(import.meta.dirname, '..');

const PATHS = [
    'src',
    'test',
    'deploy/core/User/src',   // the default user plugin, compiled with the editor
    'plugins'
];

function run(command: string, args: string[]): Promise<number> {
    return new Promise((resolve, reject) => {
        const child = spawn(command, args, { cwd: ROOT, stdio: 'inherit' });
        child.on('close', (code) => resolve(code ?? 1));
        child.on('error', reject);
    });
}

// Pinned and checksummed rather than installed from npm; see the script.
const fetched = await run(process.execPath, [path.join(ROOT, 'script', 'fetch-clj-kondo.mts')]);
if (fetched !== 0) process.exit(fetched);

process.exit(await run(path.join(ROOT, '.tools', 'clj-kondo'),
                       ['--fail-level', 'error', '--lint', ...PATHS]));
