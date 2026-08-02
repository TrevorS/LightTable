// Every unit test: ClojureScript under node, and the main process under node.
//
// Both at once. They share nothing — different runtimes, different sources,
// different output — so chaining them with `&&` only meant that a failing
// ClojureScript test hid whatever the main process had to say. Each block of
// output is printed whole and labelled, so running them together does not
// interleave into something unreadable.
//
// Not the e2e suite or the smoke test: those need a build and a display, and
// `make test-e2e` and `make smoke` are where they live.

import { spawn } from 'node:child_process';
import * as path from 'node:path';

const ROOT = path.join(import.meta.dirname, '..');

const SUITES = [
    { name: 'ClojureScript', command: 'npm', args: ['run', '--silent', 'test:cljs'] },
    { name: 'main process', command: 'npm', args: ['run', '--silent', 'test:electron'] }
];

function run({ name, command, args }: typeof SUITES[number]) {
    return new Promise<{ name: string; ok: boolean; out: string }>((resolve) => {
        const child = spawn(command, args,
                            { cwd: ROOT, env: { ...process.env, npm_config_loglevel: 'silent' } });
        let out = '';
        child.stdout.on('data', (d) => { out += d; });
        child.stderr.on('data', (d) => { out += d; });
        child.on('close', (code) => resolve({ name, ok: code === 0, out }));
        child.on('error', (e) => resolve({ name, ok: false, out: String(e) }));
    });
}

const results = await Promise.all(SUITES.map(run));

for (const { name, out } of results) {
    console.log(`\n── ${name} ${'─'.repeat(Math.max(0, 60 - name.length))}`);
    console.log(out.split('\n').filter((l) => !l.startsWith('npm notice')).join('\n').trim());
}

if (results.some((r) => !r.ok)) process.exit(1);
