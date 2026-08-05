#!/usr/bin/env node

// A REPL against a running Light Table.
//
// Every question about the assembled application used to cost a throwaway
// Electron harness: fifteen seconds of boot, a bespoke probe script, and one
// answer. This boots the real application once and keeps it, so asking is a
// second and a one-liner.
//
//   script/lt-repl.sh start                  boot it, wait until it answers
//   script/lt-repl.sh start --release        boot the packaged app in builds/
//   script/lt-repl.sh eval '1 + 1'           evaluate in the window
//   script/lt-repl.sh eval -f probe.js       evaluate a file
//   script/lt-repl.sh eval -t 60000 '...'    give it longer than the default 15s
//   script/lt-repl.sh cljs 'files.cwd'       evaluate against lt.objs, munged
//   script/lt-repl.sh shot out.png [secs]    capture the window as it is now
//   script/lt-repl.sh boot-log [secs]        boot fresh, print what it logged
//   script/lt-repl.sh trace on            record every raise and behavior
//   script/lt-repl.sh trace show :editor.doc   what one trigger actually did
//   script/lt-repl.sh screen              modals, tabs, statusbar, focus
//   script/lt-repl.sh drift               projection vs the objects
//   script/lt-repl.sh stop
//
// Every evaluation gets `LT`, a few helpers that exist because doing these by
// hand goes wrong in ways that look like application bugs. See PRELUDE.
//
// It attaches over the Chrome DevTools Protocol, which main.js already opens a
// port for. Nothing is injected into the app, so what answers is exactly what
// ships — the same reason script/smoke-test.js reuses the real main.js.
//
// Expressions are evaluated with a timeout, so a call that hangs reports as a
// hang instead of taking the session with it. That is not hypothetical: it is
// what this was written to diagnose.

import * as path from 'node:path';
import { spawn, execFileSync } from 'node:child_process';
import * as fs from 'node:fs';
import * as os from 'node:os';
import { CORE, ROOT, electronBinary, packagedApp } from './lib/paths.mts';

const ELECTRON = await electronBinary();
// main.js appends --remote-debugging-port=8315 itself.
/** One entry from Chromium's /json listing. */
interface CdpTarget {
    type: string;
    url: string;
    webSocketDebuggerUrl: string;
}

/**
 * The DevTools port to attach to, which must be the one the app opened —
 * `LT_REMOTE_DEBUGGING_PORT`, exactly as `config.ts` reads it.
 *
 * This was the constant 8315 while the application had already been made
 * configurable, and the two disagreeing is worse than it sounds. The port is
 * *fixed*, so a second editor cannot have it: whichever one started first keeps
 * it, and every later instance is unreachable. Nothing says so. A probe against
 * a freshly launched build is then answered by the editor that has been open
 * since this morning, and it answers perfectly — the wrong build's PATH,
 * console and behaviors, reported as the new one's.
 *
 * That is not hypothetical. It is how a fix that worked was measured as broken,
 * and then looked for in the wrong place. So: set this to reach a second
 * instance, and set it on the instance too.
 */
const PORT = Number(process.env['LT_REMOTE_DEBUGGING_PORT']) || 8315;
const STATE = path.join(os.tmpdir(), 'lt-repl.json');
const EVAL_TIMEOUT_MS = 15000;

function fail(msg: string): never { console.error(msg); process.exit(1); }

/**
 * Helpers defined in the window before every evaluation, idempotently.
 *
 * Each one is here because the obvious way to do it is wrong in a way that
 * reads as a bug in Light Table rather than in the probe:
 *
 * - `LT.wrap` exists because a ClojureScript function of more than one arity
 *   compiles to a dispatcher with the real bodies hanging off it as
 *   properties, and internal call sites go straight to those. Replacing the
 *   function with a plain wrapper therefore breaks every caller —
 *   `cljs$core$IFn$_invoke$arity$variadic is not a function` — and the damage
 *   outlives the probe. This copies the arity properties across and can be
 *   undone.
 * - `LT.sleep` and `LT.until` exist because the interesting states are
 *   asynchronous, and a bare `setTimeout` chain is where probes lose their
 *   return value.
 * - `LT.errors` exists because lt.object catches exceptions thrown inside
 *   behavior reactions and reports them. A behavior that throws therefore
 *   looks exactly like one that decided not to act, which has cost this
 *   project more than one afternoon.
 */
const PRELUDE = `
if (typeof window.LT === 'undefined') {
  window.LT = {
    _undo: [],
    sleep: function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); },
    until: function (test, ms, step) {
      var deadline = Date.now() + (ms || 10000);
      var tick = function () {
        if (test()) return Promise.resolve(true);
        if (Date.now() > deadline) return Promise.resolve(false);
        return LT.sleep(step || 250).then(tick);
      };
      return tick();
    },
    wrap: function (obj, name, make) {
      var original = obj[name];
      var replacement = make(original);
      for (var k in original) { replacement[k] = original[k]; }
      obj[name] = replacement;
      LT._undo.push(function () { obj[name] = original; });
      return original;
    },
    unwrap: function () { LT._undo.splice(0).reverse().forEach(function (f) { f(); }); },
    errors: [],
    watchErrors: function () {
      if (LT._watching) { return LT.errors; }
      LT._watching = true;
      LT.wrap(lt.object, 'safe_report_error', function (original) {
        return function (e) {
          LT.errors.push(String((e && e.stack) || e).slice(0, 1500));
          return original(e);
        };
      });
      return LT.errors;
    },
    editor: function (path) { return cljs.core.first.call(null, lt.objs.editor.pool.by_path(path)); },
    kw: function (name) { return cljs.core.keyword.call(null, name); },
    get: function (obj, name) {
      return cljs.core.get.call(null, cljs.core.deref(obj), LT.kw(name));
    },
    open: function (path) {
      return lt.objs.command.exec_BANG_(LT.kw('open-path'), path);
    },
    tabsets: function () { return cljs.core.vec(lt.object.by_tag(LT.kw('tabset'))); },
    // Opening a file puts it in the active tabset, and opening one that is
    // already open focuses it where it already is — so arranging a split
    // means moving the tab, not opening it twice.
    openIn: function (path, index) {
      var ts = cljs.core.nth.call(null, LT.tabsets(), index);
      LT.open(path);
      return LT.sleep(1200).then(function () {
        var ed = LT.editor(path);
        if (ed && ts) { lt.objs.tabs.move_tab_to_tabset(ed, ts); }
        return ed;
      });
    }
  };
}
`;

async function targets(): Promise<CdpTarget[]> {
    const res = await fetch(`http://127.0.0.1:${PORT}/json`);
    return await res.json();
}

/** The window, once it exists. Rejects rather than hanging if it never does. */
async function window_(timeoutMs: number): Promise<CdpTarget> {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
        try {
            const page = (await targets()).find((t) =>
                t.type === 'page' && String(t.url).includes('LightTable.html'));
            if (page) return page;
        } catch { /* not listening yet */ }
        if (Date.now() > deadline) throw new Error('Light Table never opened a window');
        await new Promise((r) => setTimeout(r, 250));
    }
}

/**
 * Evaluate `expression` in the window and return its value.
 *
 * Promises are awaited, so an async capability can be probed directly.
 */
async function evaluate(expression: string, timeoutMs?: number): Promise<unknown> {
    const limit = timeoutMs || EVAL_TIMEOUT_MS;
    const page = await window_(2000);
    const ws = new WebSocket(page.webSocketDebuggerUrl);
    const send = (id: number, method: string, params: unknown) =>
        ws.send(JSON.stringify({ id, method, params }));

    return await new Promise((resolve, reject) => {
        const timer = setTimeout(() => {
            ws.close();
            reject(new Error(`timed out after ${limit}ms — the expression did not return` +
                             (timeoutMs ? '' : ' (raise it with -t)')));
        }, limit);

        ws.addEventListener('error', () => { clearTimeout(timer); reject(new Error('devtools socket error')); });
        ws.addEventListener('open', () => {
            send(1, 'Runtime.evaluate', {
                // The prelude is a closed block statement, so the completion
                // value of the program is still whatever the expression after
                // it evaluates to.
                expression: PRELUDE + '\n' + expression,
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
function cljs(expression: string): string {
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
function outsideStrings(src: string, f: (chunk: string) => string): string {
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

function munge(name: string): string {
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
async function bootLog(seconds: number): Promise<void> {
    stop();
    await new Promise((r) => setTimeout(r, 500));
    const child = spawnApp();
    const page = await window_(60000);
    const ws = new WebSocket(page.webSocketDebuggerUrl);
    const lines: string[] = [];
    let id = 10;

    await new Promise<void>((resolve) => {
        ws.addEventListener('open', () => {
            ws.send(JSON.stringify({ id: id++, method: 'Runtime.enable' }));
            ws.send(JSON.stringify({ id: id++, method: 'Log.enable' }));
            resolve();
        });
        ws.addEventListener('error', () => resolve());
    });

    ws.addEventListener('message', (ev) => {
        const msg = JSON.parse(ev.data);
        if (msg.method === 'Runtime.consoleAPICalled') {
            const text = (msg.params.args || [])
                .map((a: { value?: unknown; description?: string; type?: string }) =>
                    a.value !== undefined ? a.value : (a.description || a.type)).join(' ');
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
    if (child.pid) { try { process.kill(-child.pid, 'SIGTERM'); } catch { /* already gone */ } }
    fs.rmSync(STATE, { force: true });
}

/**
 * What to boot: the tree, or the packaged application in `builds/`.
 *
 * `--release` exists because "does it work in the real thing" was a question
 * nothing here could answer. The tree and the package differ in ways that have
 * mattered — the package has its own Electron, its own `node_modules`, its own
 * copy of every plugin, and a resources layout `deploy/core` does not have —
 * so a bug that only appears in one of them is the kind nobody can reproduce.
 *
 * Both run against your real user directory, because `spawn` inherits the
 * environment and `LT_USER_DIR` is unset: your settings, your workspace, your
 * plugins, your connections. That is the difference from `test-e2e`, which
 * gives every run a scratch home on purpose — and it is why a failure you can
 * see and this could not is worth checking here first.
 */
function target(release: boolean): { binary: string; args: string[]; what: string } {
    if (!release) return { binary: ELECTRON, args: [CORE, '--no-sandbox'], what: 'deploy/core' };
    const app = packagedApp();
    if (!app) fail('No packaged build in builds/. Run `make build` first.');
    return { binary: app.binary, args: [...app.args, '--no-sandbox'],
             what: path.relative(ROOT, app.binary) };
}

/** Starts the application detached, and records its pid for stop(). */
function spawnApp(release = false): ReturnType<typeof spawn> {
    const { binary, args, what } = target(release);
    // macOS has no DISPLAY and does not want one: Electron talks to the window
    // server directly, so asking for xvfb there fails on a machine that works
    // perfectly. script/smoke-test.sh already draws this line; this did not,
    // and `lt-repl.sh start` on a Mac spawned xvfb-run and reported ENOENT.
    const useXvfb = !process.env.DISPLAY && process.platform !== 'darwin';
    const cmd = useXvfb ? 'xvfb-run' : binary;
    const spawnArgs = useXvfb
        ? ['-a', '--server-args=-screen 0 1280x820x24', binary, ...args]
        : args;
    console.error(`booting ${what}`);
    const child = spawn(cmd, spawnArgs, { detached: true, stdio: 'ignore' });
    child.unref();
    fs.writeFileSync(STATE, JSON.stringify({ pid: child.pid, what }));
    return child;
}

async function start(release = false): Promise<void> {
    if (!release) {
        if (!fs.existsSync(ELECTRON)) fail('Electron is missing. Run script/build.sh first.');
        if (!fs.existsSync(path.join(CORE, 'lighttable', 'bootstrap.js')))
            fail('No bundle. Run npm run build:cljs.');
    }

    try { await window_(0); console.log('already running'); return; } catch { /* not up */ }

    spawnApp(release);
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

/** Every pid whose command line contains `needle`, newest first. */
function pidsMatching(needle: string): number[] {
    try {
        return execFileSync('ps', ['-Ao', 'pid=,command='], { encoding: 'utf8' })
            .split('\n')
            .filter((l) => l.includes(needle))
            .map((l) => Number(l.trim().split(/\s+/)[0]))
            .filter((n) => Number.isFinite(n) && n !== process.pid)
            .reverse();
    } catch {
        return [];
    }
}

function alive(pid: number): boolean {
    try { process.kill(pid, 0); return true; } catch { return false; }
}

/**
 * Stop the application, and say so only when it is true.
 *
 * This printed `stopped` unconditionally. It signalled the recorded pid, caught
 * both failures, deleted the state file and claimed success — so the one thing it
 * could not do was tell you it had not worked. A packaged app boot left a running
 * editor behind and reported that it had stopped it, which is how it was found.
 *
 * The reason the signal missed is worth keeping: `spawn(…, {detached: true})`
 * records the pid it launched, and the packaged app **re-execs** — the recorded
 * process is gone by the time anyone asks, so both `kill(-pid)` and `kill(pid)`
 * throw ESRCH and the app is untouched. So the pid is a hint rather than the
 * answer, and what is actually running has to be looked up.
 *
 * SIGTERM first, then SIGKILL for anything that ignores it, and the binary path
 * is the needle — `builds/…/LightTable.app` or `deploy/core`, never a bare
 * "Electron", because most desktop applications are one and killing them is not
 * this script's business.
 */
function stop(): void {
    if (!fs.existsSync(STATE)) { console.log('not running'); return; }
    const { pid, what } = JSON.parse(fs.readFileSync(STATE, 'utf8'));
    fs.rmSync(STATE, { force: true });

    // xvfb-run leaves a process group; kill the group so nothing is orphaned.
    try { process.kill(-pid, 'SIGTERM'); } catch {
        try { process.kill(pid, 'SIGTERM'); } catch { /* already gone — see the docstring */ }
    }

    // And whatever is actually running, found by what it was launched from.
    // What it was launched from, absolute. `what` is already the relative form of
    // exactly that — `deploy/core`, or the packaged binary's path — so nothing has
    // to be recomputed and the two cannot disagree.
    //
    // Trimmed to the `.app` for a packaged run, because the helper processes name
    // the bundle but not the `MacOS/Electron` inside it, and a needle that missed
    // them would report success while a GPU process was still up.
    const launched = path.join(ROOT, what);
    const needle = launched.includes('.app/')
        ? launched.slice(0, launched.indexOf('.app/') + 4)
        : launched;
    for (const signal of ['SIGTERM', 'SIGKILL'] as const) {
        const pids = pidsMatching(needle);
        if (!pids.length) break;
        for (const p of pids) {
            try { process.kill(p, signal); } catch { /* raced with its own exit */ }
        }
        // Long enough for a window to close and its helpers to follow.
        const until = Date.now() + 3000;
        while (Date.now() < until && pidsMatching(needle).some(alive)) { /* spin */ }
    }

    const left = pidsMatching(needle);
    if (left.length) {
        console.error(`still running: ${left.join(', ')} — ${needle}`);
        process.exit(1);
    }
    console.log('stopped');
}

/**
 * A PNG of the window as it stands, without booting anything.
 *
 * script/screenshot.js boots its own instance per run, which is right for a
 * fixed set of files and wrong for a window you have spent several evaluations
 * arranging. This captures whatever is on screen now.
 */
async function shot(out: string, settleSeconds: number): Promise<void> {
    if (!out) fail('usage: script/lt-repl.sh shot <out.png> [settle-seconds]');
    const page = await window_(2000);
    if (settleSeconds) await new Promise((r) => setTimeout(r, settleSeconds * 1000));
    const ws = new WebSocket(page.webSocketDebuggerUrl);
    const data = await new Promise((resolve, reject) => {
        const timer = setTimeout(() => { ws.close(); reject(new Error('capture timed out')); }, 30000);
        ws.addEventListener('error', () => { clearTimeout(timer); reject(new Error('devtools socket error')); });
        ws.addEventListener('open', () => ws.send(JSON.stringify(
            { id: 1, method: 'Page.captureScreenshot', params: { format: 'png' } })));
        ws.addEventListener('message', (ev) => {
            const msg = JSON.parse(ev.data);
            if (msg.id !== 1) return;
            clearTimeout(timer);
            ws.close();
            if (msg.error) return reject(new Error(msg.error.message));
            resolve(msg.result.data);
        });
    });
    fs.mkdirSync(path.dirname(path.resolve(out)), { recursive: true });
    fs.writeFileSync(out, Buffer.from(String(data), 'base64'));
    console.log(out + '  ' + fs.statSync(out).size + ' bytes');
}

/**
 * Call the control surface and print what it said.
 *
 * `poll` is for the operations that return a job: MCP's Tasks shape, where a
 * slow call hands back a handle and the caller polls until it is terminal.
 * Doing that here means `clj` reads like a REPL rather than like a protocol.
 */
async function control(op: string, arg: unknown, poll = false): Promise<void> {
    const call = (o: string, a: unknown) =>
        evaluate(`JSON.stringify(lt.objs.control.request(${JSON.stringify(o)}, ${JSON.stringify(a)}))`);

    let out = JSON.parse(String(await call(op, arg)));
    if (poll && out.id) {
        const deadline = Date.now() + 120000;
        while (out.status === 'working' && Date.now() < deadline) {
            await new Promise((r) => setTimeout(r, 100));
            out = JSON.parse(String(await call('job', { job: out.id })));
        }
    }
    // A value on its own reads better than a job wrapped around it.
    if (out.status === 'completed' && out.result !== undefined) console.log(out.result);
    else if (out.status === 'failed') fail(String(out.error));
    else console.log(JSON.stringify(out, null, 1));
}

async function main(): Promise<void> {
    const argv = process.argv.slice(2);
    // `--release` anywhere, because it belongs to the session rather than to
    // the subcommand, and `start --release` and `--release start` should not
    // be different things to remember.
    const release = argv.includes('--release');
    const [command, ...rest] = argv.filter((a) => a !== '--release');
    if (command === 'start') return await start(release);
    if (command === 'stop') return stop();
    if (command === 'boot-log') return await bootLog(Number(rest[0]) || 20);
    if (command === 'shot') return await shot(rest[0], Number(rest[1]) || 0);

    // The control surface — see src/lt/objs/control.cljs. Data in, data out,
    // and the same operations an MCP wrapper would expose.
    if (command === 'clj') return await control('eval', { source: rest.join(' ') }, true);
    if (command === 'state') return await control('snapshot', {});
    if (command === 'errors') return await control('errors', {});
    if (command === 'prompts') return await control('prompts', {});
    if (command === 'answer') return await control('answer', { prompt: Number(rest[0]), choice: rest[1] ?? null });
    if (command === 'job') return await control('job', { job: rest[0] });
    if (command === 'open') return await control('open', { path: path.resolve(rest[0] || '') }, true);

    // What fired, when a chain went quiet. See src/lt/objs/trace.cljs — the
    // whole point is that "nothing listens for this" and "something listened
    // and declined" look identical from outside, and this tells them apart.
    if (command === 'trace') {
        const verb = rest[0] ?? 'show';
        if (verb === 'on') return await control('eval', { source: '(do (lt.objs.trace/on!) :tracing)' }, true);
        if (verb === 'off') return await control('eval', { source: '(do (lt.objs.trace/off!) :stopped)' }, true);
        const trigger = rest[1] ? ` ${rest[1]}` : ' nil';
        const n = Number(rest[2]) || 200;
        return await control('eval',
            { source: `(lt.objs.trace/report ${n}${trigger})` }, true);
    }

    // What the window is showing, as a sentence rather than a picture. `shot`
    // gives a PNG; this gives the thing a PNG is usually read for — and it
    // exists because an hour went into querying the DOM for the widget that
    // was expected while a modal sat on top of it, which one screenshot
    // answered instantly.
    if (command === 'screen') return await control('screen', {});

    // Whether the state atom still agrees with the objects it is projected
    // from. `lt.state.objects/snapshot` is a pure function of the object
    // world, so this is checkable at any instant — and a statusbar stuck on
    // "connecting" while the server was ready for eight seconds is exactly
    // what it catches.
    if (command === 'drift') return await control('drift', {});

    if (command !== 'eval' && command !== 'cljs') {
        fail('usage: script/lt-repl.sh start | stop\n' +
             '  clj <expr>            evaluate ClojureScript, get a value back\n' +
             '  state | errors | prompts | job <id>\n' +
             '  answer <prompt-id> [choice]\n' +
             '  open <file>\n' +
             '  eval [-t ms] <js> | eval -f <file> | cljs <expr>\n' +
             '  trace on | trace off | trace show [trigger] [n]\n' +
             '  screen                what the window is showing right now\n' +
             '  drift                 where the state atom disagrees with the objects\n' +
             '  shot <out.png> [settle-seconds] | boot-log [seconds]\n' +
             '  --release             boot the packaged app in builds/ rather than the tree');
    }
    let args = rest;
    let timeout = 0;
    if (args[0] === '-t' || args[0] === '--timeout') {
        timeout = Number(args[1]);
        if (!timeout) fail('-t wants a number of milliseconds');
        args = args.slice(2);
    }
    let expression = args[0] === '-f' ? fs.readFileSync(args[1], 'utf8') : args.join(' ');
    if (command === 'cljs') expression = cljs(expression);

    try {
        const value = await evaluate(expression, timeout);
        console.log(typeof value === 'string' ? value : JSON.stringify(value, null, 1));
    } catch (e) {
        fail(String((e as Error).message).split('\n').slice(0, 12).join('\n'));
    }
}

main();
