// Everything that can fail without a build: both linters, every type-check,
// the generated API docs, and the two places Electron is pinned.
//
// This was five `npm run --silent x && …` links in package.json. Two things
// were wrong with that. Each nested `npm run` paid a process start to reach a
// one-line script, and `&&` stops at the first failure — so a type error hid
// a stale doc/api, and fixing one showed you the next. These are independent
// checks over disjoint inputs; there is no reason to learn about them one at a
// time.
//
// So they run at once and every failure is reported. `npm run check` is what
// CI runs first, before anything is compiled, which is the whole idea: a lint
// error should not cost a ClojureScript build to discover.

import { spawn } from 'node:child_process';
import * as path from 'node:path';

const ROOT = path.join(import.meta.dirname, '..');

interface Check { name: string; command: string; args: string[] }

const node = (script: string, ...args: string[]) =>
    ({ command: process.execPath, args: [path.join(ROOT, 'script', script), ...args] });

const CHECKS: Check[] = [
    { name: 'clj-kondo', ...node('lint-cljs.mts') },
    {
        name: 'eslint',
        command: 'npx',
        args: ['eslint', 'script', 'src-electron', 'src-window', 'src-worker',
               'src-browser', 'test-electron', 'test-e2e']
    },
    { name: 'typecheck', ...node('typecheck.mts') },
    // doc/api is committed, so nothing else would notice it going stale.
    { name: 'doc/api', ...node('gen-api-docs.mts', '--check') },
    { name: 'electron pins', ...node('check-pins.mts') }
];

function run({ name, command, args }: Check): Promise<{ name: string; ok: boolean; out: string }> {
    return new Promise((resolve) => {
        const child = spawn(command, args,
                            { cwd: ROOT, env: { ...process.env, npm_config_loglevel: 'silent' } });
        let out = '';
        child.stdout.on('data', (d) => { out += d; });
        child.stderr.on('data', (d) => { out += d; });
        child.on('close', (code) => resolve({ name, ok: code === 0, out }));
        child.on('error', (e) => resolve({ name, ok: false, out: String(e) }));
    });
}

const results = await Promise.all(CHECKS.map(run));

for (const { name, ok, out } of results) {
    const text = out.split('\n').filter((l) => !l.startsWith('npm notice')).join('\n').trim();
    if (ok) {
        console.log(`  ok   ${name}${text ? ` — ${text.split('\n').pop()}` : ''}`);
    } else {
        console.error(`  FAIL ${name}`);
        if (text) console.error(text.split('\n').map((l) => `       ${l}`).join('\n'));
    }
}

if (results.some((r) => !r.ok)) process.exit(1);
