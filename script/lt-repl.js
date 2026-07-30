#!/usr/bin/env node
/*jshint esversion: 8 */
"use strict";

// A REPL against a running Light Table.
//
// Every question about the assembled application used to cost a throwaway
// Electron harness: fifteen seconds of boot, a bespoke probe script, and one
// answer. This boots the real application once and keeps it, so asking is a
// second and a one-liner.
//
//   script/lt-repl.sh start                  boot it, wait until it answers
//   script/lt-repl.sh eval '1 + 1'           evaluate in the window
//   script/lt-repl.sh eval -f probe.js       evaluate a file
//   script/lt-repl.sh cljs 'files.cwd'       evaluate against lt.objs, munged
//   script/lt-repl.sh boot-log [secs]        boot fresh, print what it logged
//   script/lt-repl.sh stop
//
// It attaches over the Chrome DevTools Protocol, which main.js already opens a
// port for. Nothing is injected into the app, so what answers is exactly what
// ships — the same reason script/smoke-test.js reuses the real main.js.
//
// Expressions are evaluated with a timeout, so a call that hangs reports as a
// hang instead of taking the session with it. That is not hypothetical: it is
// what this was written to diagnose.

const path = require('path');
const { spawn } = require('child_process');
const fs = require('fs');
const os = require('os');

const ROOT = path.join(__dirname, '..');
const CORE = path.join(ROOT, 'deploy', 'core');
const ELECTRON = path.join(ROOT, 'deploy', 'electron', 'node_modules', 'electron', 'dist', 'electron');
// main.js appends --remote-debugging-port=8315 itself.
const PORT = 8315;
const STATE = path.join(os.tmpdir(), 'lt-repl.json');
const EVAL_TIMEOUT_MS = 15000;

function fail(msg) { console.error(msg); process.exit(1); }

async function targets() {
    const res = await fetch(`http://127.0.0.1:${PORT}/json`);
    return await res.json();
}

/** The window, once it exists. Rejects rather than hanging if it never does. */
async function window_(timeoutMs) {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
        try {
            const page = (await targets()).find((t) =>
                t.type === 'page' && String(t.url).includes('LightTable.html'));
            if (page) return page;
        } catch (e) { /* not listening yet */ }
        if (Date.now() > deadline) throw new Error('Light Table never opened a window');
        await new Promise((r) => setTimeout(r, 250));
    }
}

/**
 * Evaluate `expression` in the window and return its value.
 *
 * Promises are awaited, so an async capability can be probed directly.
 */
async function evaluate(expression) {
    const page = await window_(2000);
    const ws = new WebSocket(page.webSocketDebuggerUrl);
    const send = (id, method, params) => ws.send(JSON.stringify({ id, method, params }));

    return await new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
            ws.close();
            reject(new Error(`timed out after ${EVAL_TIMEOUT_MS}ms — the expression did not return`));
        }, EVAL_TIMEOUT_MS);

        ws.addEventListener('error', (e) => { clearTimeout(timer); reject(new Error('devtools socket error')); });
        ws.addEventListener('open', () => {
            send(1, 'Runtime.evaluate', {
                expression,
                returnByValue: true,
                // replMode is deliberately off: it changes how the result is
                // handled and stops awaitPromise taking effect, so an async
                // probe comes back as a serialized Promise — `{}` — rather
                // than its value.
                awaitPromise: true
            });
        });
        ws.addEventListener('message', (ev) => {
            const msg = JSON.parse(ev.data);
            if (msg.id !== 1) return;
            clearTimeout(timer);
            ws.close();
            if (msg.error) return reject(new Error(msg.error.message));
            const r = msg.result;
            if (r.exceptionDetails) {
                const ex = r.exceptionDetails.exception;
                return reject(new Error((ex && (ex.description || ex.value)) || r.exceptionDetails.text));
            }
            resolve(r.result.value === undefined ? r.result.description : r.result.value);
        });
    });
}

/**
 * ClojureScript names, munged. `files/cwd` and `objs.files.cwd` both reach
 * lt.objs.files.cwd, and `-` becomes `_` — which is most of what made hand
 * written probes tedious and wrong.
 */
function cljs(expression) {
    // Only outside string literals: a path like '/home/user/x' is full of
    // slashes that look exactly like namespace separators, and rewriting them
    // produces an expression that runs and quietly answers about nothing.
    return outsideStrings(expression, (chunk) =>
        chunk.replace(/\b([a-z][\w.-]*)\/([\w?!*<>=+-]+)/g, (_, ns, name) => {
        const full = ns.startsWith('lt.') ? ns
                   : ns.startsWith('objs.') || ns.startsWith('util.') ? `lt.${ns}`
                   : `lt.objs.${ns}`;
        return `${full}.${munge(name)}`;
    }));
}

/** Applies `f` to the parts of `src` that are not inside a string literal. */
function outsideStrings(src, f) {
    let out = '', i = 0;
    while (i < src.length) {
        const next = src.slice(i).search(/['"`]/);
        if (next === -1) { out += f(src.slice(i)); break; }
        out += f(src.slice(i, i + next));
        const quote = src[i + next];
        let j = i + next + 1;
        while (j < src.length && !(src[j] === quote && src[j - 1] !== '\\')) j++;
        out += src.slice(i + next, j + 1);
        i = j + 1;
    }
    return out;
}

function munge(name) {
    return name.replace(/-/g, '_').replace(/\?$/, '_QMARK_').replace(/!$/, '_BANG_')
               .replace(/\*$/, '_STAR_').replace(/^\*/, '_STAR_');
}

/**
 * Boot a fresh instance and print everything the renderer logs on the way up.
 *
 * A bundle that throws while loading leaves a half-built object graph and no
 * way to ask it what happened — the console it would have logged to is one of
 * the things that never got built. This attaches before the page settles and
 * prints what the renderer actually said.
 */
async function bootLog(seconds) {
    stop();
    await new Promise((r) => setTimeout(r, 500));
    const child = spawnApp();
    const page = await window_(60000);
    const ws = new WebSocket(page.webSocketDebuggerUrl);
    const lines = [];
    let id = 10;

    await new Promise((resolve) => {
        ws.addEventListener('open', () => {
            ws.send(JSON.stringify({ id: id++, method: 'Runtime.enable' }));
            ws.send(JSON.stringify({ id: id++, method: 'Log.enable' }));
            resolve();
        });
        ws.addEventListener('error', resolve);
    });

    ws.addEventListener('message', (ev) => {
        const msg = JSON.parse(ev.data);
        if (msg.method === 'Runtime.consoleAPICalled') {
            const text = (msg.params.args || [])
                .map((a) => a.value !== undefined ? a.value : (a.description || a.type)).join(' ');
            lines.push(`[${msg.params.type}] ${text}`);
        } else if (msg.method === 'Log.entryAdded') {
            lines.push(`[${msg.params.entry.level}] ${msg.params.entry.text}`);
        } else if (msg.method === 'Runtime.exceptionThrown') {
            const d = msg.params.exceptionDetails;
            const ex = d.exception || {};
            lines.push(`[uncaught] ${ex.description || d.text}`);
        }
    });

    await new Promise((r) => setTimeout(r, seconds * 1000));
    ws.close();
    console.log(lines.length ? lines.join('\n') : '(the renderer logged nothing)');
    if (child) { try { process.kill(-child.pid, 'SIGTERM'); } catch (e) {} }
    fs.rmSync(STATE, { force: true });
}

/** Starts the application detached, and records its pid for stop(). */
function spawnApp() {
    const useXvfb = !process.env.DISPLAY;
    const cmd = useXvfb ? 'xvfb-run' : ELECTRON;
    const args = useXvfb ? ['-a', '--server-args=-screen 0 1280x820x24', ELECTRON, CORE, '--no-sandbox']
                         : [CORE, '--no-sandbox'];
    const child = spawn(cmd, args, { detached: true, stdio: 'ignore' });
    child.unref();
    fs.writeFileSync(STATE, JSON.stringify({ pid: child.pid }));
    return child;
}

async function start() {
    if (!fs.existsSync(ELECTRON)) fail('Electron is missing. Run script/build.sh first.');
    if (!fs.existsSync(path.join(CORE, 'lighttable', 'bootstrap.js'))) fail('No bundle. Run npm run build:cljs.');

    try { await window_(0); console.log('already running'); return; } catch (e) { /* not up */ }

    spawnApp();
    await window_(60000);
    // The window answers before Light Table has finished starting; wait for the
    // object graph rather than for the page.
    for (let i = 0; i < 60; i++) {
        try {
            if (await evaluate("typeof lt !== 'undefined' && typeof lt.objs.app === 'object'")) break;
        } catch (e) { /* still loading */ }
        await new Promise((r) => setTimeout(r, 500));
    }
    console.log('ready');
}

function stop() {
    if (!fs.existsSync(STATE)) { console.log('not running'); return; }
    const { pid } = JSON.parse(fs.readFileSync(STATE, 'utf8'));
    // xvfb-run leaves a process group; kill the group so nothing is orphaned.
    try { process.kill(-pid, 'SIGTERM'); } catch (e) { try { process.kill(pid, 'SIGTERM'); } catch (e2) {} }
    fs.rmSync(STATE, { force: true });
    console.log('stopped');
}

async function main() {
    const [command, ...rest] = process.argv.slice(2);
    if (command === 'start') return await start();
    if (command === 'stop') return stop();
    if (command === 'boot-log') return await bootLog(Number(rest[0]) || 20);

    if (command !== 'eval' && command !== 'cljs') {
        fail('usage: script/lt-repl.sh start | eval <js> | eval -f <file> | cljs <expr>\n' +
             '                          | boot-log [seconds] | stop');
    }
    let expression = rest[0] === '-f' ? fs.readFileSync(rest[1], 'utf8') : rest.join(' ');
    if (command === 'cljs') expression = cljs(expression);

    try {
        const value = await evaluate(expression);
        console.log(typeof value === 'string' ? value : JSON.stringify(value, null, 1));
    } catch (e) {
        fail(String(e.message).split('\n').slice(0, 12).join('\n'));
    }
}

main();
