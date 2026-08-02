// A running Light Table, and the few things every test needs to ask it.
//
// The fixture launches the application the way a person does — the real
// deploy/core, the real main.js — and hands back both halves: `app` for the
// main process and `window` for the renderer. A test that wants a clean
// workspace asks for one; the rest share the boot.

import { test as base, _electron as electron, expect } from '@playwright/test';
import type { ElectronApplication, Page } from '@playwright/test';
import * as path from 'node:path';
import * as fs from 'node:fs';
import * as os from 'node:os';

const ROOT = path.join(__dirname, '..');
export const CORE = path.join(ROOT, 'deploy', 'core');

// The electron package reports where its own binary is, which differs by
// platform: dist/electron on Linux, dist/Electron.app/… on a Mac.
const ELECTRON: string = require(path.join(ROOT, 'deploy', 'electron', 'node_modules', 'electron'));

/**
 * Launch the application.
 *
 * `LT_REMOTE_DEBUGGING_PORT=off` is the one thing here that is not simply
 * "run it as it ships", and it has to be: main.js otherwise appends a fixed
 * --remote-debugging-port, Playwright appends its own, and Chromium then
 * honours one while the launcher waits for the other. What that looks like is
 * a launch that hangs for three minutes and a timeout that names nothing.
 * The browser tab is the only feature that reads the port, so a test needing
 * it should launch its own instance with a port set.
 */
export async function launch(extraEnv: Record<string, string> = {},
                             core: string = CORE): Promise<ElectronApplication> {
    // A home directory of its own. Settings, the workspace, logs and caches all
    // live under LT_USER_DIR, so without this a test inherits whatever the last
    // one — or the developer — left behind: an open tab pointing at a file that
    // has since been deleted, a workspace of scratch directories, a plugin
    // someone was working on. That is not a hypothetical. It is why a run that
    // took twelve seconds took fifteen minutes the next time.
    const home = fs.mkdtempSync(path.join(os.tmpdir(), 'lt-e2e-home-'));
    homes.push(home);

    return await electron.launch({
        executablePath: ELECTRON,
        args: [core, '--no-sandbox'],
        env: {
            // Headless unless something says otherwise — script/e2e.sh sets
            // it, and LT_HEADED turns a debugging run visible again.
            LT_HEADLESS: '1',
            ...process.env,
            LT_USER_DIR: home,
            LT_REMOTE_DEBUGGING_PORT: 'off',
            ...extraEnv
        } as Record<string, string>
    });
}

/**
 * Shut the application down without asking it anything.
 *
 * `close()` alone is not enough. Light Table intercepts a window close and, if
 * a buffer is dirty, draws "You will lose changes" and waits — so any test
 * that typed into an editor would hang the whole run on a modal that nothing
 * is going to click. `destroy()` skips the handler, which is what teardown
 * wants: the test is over, and its scratch directory is about to be deleted.
 *
 * Everything here is best-effort. A test that failed by crashing the window
 * should report that failure, not a teardown error on top of it.
 */
export async function teardown(app: ElectronApplication): Promise<void> {
    try {
        await app.evaluate(({ BrowserWindow }) => {
            for (const w of BrowserWindow.getAllWindows()) w.destroy();
        });
    } catch { /* already gone */ }
    try {
        await app.close();
    } catch { /* already gone */ }
}

/** Home directories to remove when the run ends. */
const homes: string[] = [];

export function cleanUpHomes(): void {
    for (const home of homes.splice(0)) {
        fs.rmSync(home, { recursive: true, force: true });
    }
}

/** The window, once Light Table has finished building itself in it. */
export async function editorWindow(app: ElectronApplication): Promise<Page> {
    const window = await app.firstWindow();
    await ready(window);
    return window;
}

/**
 * Wait for the object graph rather than for the page, and for the plugins
 * rather than for the object graph.
 *
 * The window answers long before Light Table has started: `did-finish-load`
 * fires when the bundle has been fetched, not when it has run. Every probe
 * that raced this failed in a way that read as a missing feature.
 *
 * Plugins register their behaviors as they load, so "the editor exists" and
 * "the editor is finished" are seconds apart and nothing announces the second.
 * Waiting for the count to stop moving is what a person does when they look at
 * the window and see it settle — cruder than an event, and it does not depend
 * on one existing.
 */
export async function ready(window: Page): Promise<void> {
    await window.waitForFunction(
        "typeof lt !== 'undefined' && lt.objs && typeof lt.objs.app === 'object'",
        null, { timeout: 90_000 });

    const count = () => window.evaluate(
        "cljs.core.count(cljs.core.deref(lt.object.behaviors))") as Promise<number>;

    // Sampled every 100ms and settled after three readings the same, rather
    // than every 500ms and settled after two. Same 500ms of quiet before the
    // suite believes a window is up — but reached in five cheap reads instead
    // of one long sleep, and the reads cost nothing. At one launch per test
    // the difference was most of a second every time, which is most of the
    // wall clock of a run that is otherwise waiting for Electron.
    let previous = -1;
    let same = 0;
    for (let i = 0; i < 300; i++) {
        const now = await count();
        same = now === previous ? same + 1 : 0;
        if (same >= 3 && now > 100) return;
        previous = now;
        await window.waitForTimeout(100);
    }
    throw new Error(`plugins never finished loading (${previous} behaviors)`);
}

/** Call the control surface the way `script/lt-repl.sh` and MCP do. */
export async function control<T = any>(window: Page, op: string, arg: unknown = {}): Promise<T> {
    return await window.evaluate(
        ([o, a]) => (globalThis as any).lt.objs.control.request(o, a),
        [op, arg] as [string, unknown]) as T;
}

interface EvalOptions {
    /** How many times to ask whether the job is done. Default 100. */
    tries?: number;
    /** How long to wait between asking, in ms. Default 50. */
    every?: number;
    /** Return the job instead of its result, and do not throw on failure. */
    raw?: boolean;
}

/**
 * Evaluate ClojureScript in the window, through the control surface.
 *
 * Real source rather than munged names: `(pool/by-path "x")` reads as what a
 * person would type, where `lt.objs.editor.pool.by_path` is a name a reviewer
 * has to demangle. It also fails legibly — the job carries the ClojureScript
 * exception, where `window.evaluate` on a throwing form gives a generic JS
 * error with the interesting part missing.
 *
 * It costs a round trip per call, so a test asserting on the DOM should still
 * use a locator, and the two specs that only exercise a JavaScript adapter are
 * right not to use this at all.
 *
 * There were eleven copies of this, differing only in how long they were
 * willing to wait — hence `tries`. `raw` is for `control.spec.ts`, which exists
 * to assert that a failure is a status rather than a silence.
 */
export async function evalClj(window: Page, source: string,
                              { tries = 100, every = 50, raw = false }: EvalOptions = {}): Promise<any> {
    let job = await control(window, 'eval', { source });
    for (let i = 0; i < tries && job.status === 'working'; i++) {
        await window.waitForTimeout(every);
        job = await control(window, 'job', { job: job.id });
    }
    if (raw) return job;
    if (job.status !== 'completed') throw new Error(`${job.status}: ${job.error}\n${source}`);
    return job.result;
}

/**
 * Open `path` and wait until there is an editor for it.
 *
 * Six specs had a near-copy of this under three different names. Waiting for
 * the editor rather than for the command is the whole of it: `:open-path`
 * returns long before a file is read, parsed and in the pool, and every probe
 * that raced it failed in a way that read as a missing feature.
 */
export async function openFile(window: Page, path: string): Promise<void> {
    await evalClj(window, `(do (lt.objs.command/exec! :open-path "${path}") :opening)`);
    await window.waitForFunction(
        ([p]) => !!(globalThis as any).cljs.core.first.call(
            null, (globalThis as any).lt.objs.editor.pool.by_path(p)),
        [path], { timeout: 30_000 });
}

/** Evaluate a ClojureScript expression the way script/lt-repl.sh `cljs` does. */
export async function cljs<T = unknown>(window: Page, expression: string): Promise<T> {
    return await window.evaluate(expression) as T;
}

/** `pr-str` of a ClojureScript value, which is the readable form for an assertion. */
export async function prStr(window: Page, expression: string): Promise<string> {
    return await window.evaluate(`cljs.core.pr_str(${expression})`) as string;
}

/**
 * Connect the "Light Table UI" client, the way the Connect bar does.
 *
 * ClojureScript does not need this — lt.plugins.clojure/connect-cljs picks the
 * window for Light Table's own namespaces — but JavaScript and CSS have no
 * namespace to route on, and auto-connecting on any `.js` file would hijack
 * every node project. So it is a thing you choose, and a test chooses it too.
 */
export async function connectLocalClient(window: Page): Promise<void> {
    // Through the action the panel emits, rather than reaching into it: the
    // connect panel is a view now, and the kinds of connection are a private
    // table of closures behind `connect!` — a closure is not data and does not
    // belong in the state.
    await window.evaluate(
        `lt.objs.sidebar.clients.connect_BANG_.call(null, "Light Table UI")`);
    await window.waitForFunction("!!lt.objs.clients.by_name('LightTable-UI')", null, { timeout: 15_000 });
}

/** A directory of this test's own, removed when the test ends. */
export function scratchDir(name: string): string {
    return fs.mkdtempSync(path.join(os.tmpdir(), `lt-e2e-${name}-`));
}

interface WorkerFixtures {
    app: ElectronApplication;
    window: Page;
}

interface Fixtures {
    /** Errors Light Table reported into its own console during the test. */
    ltErrors: () => Promise<string[]>;
    /** Automatic: puts the editor back between tests. See `reset`. */
    cleanEditor: undefined;
}

/**
 * Put the editor back to roughly how it boots, between tests.
 *
 * The price of one application per worker rather than one per test. Booting
 * Light Table is a second and a half and there are a hundred tests, so the
 * launch was the suite — and on macOS every one of them registered an
 * application, which is a Dock that grows and shrinks a hundred times whatever
 * `show: false` says about the window.
 *
 * So: tabs closed, workspace emptied, console cleared. Not a fresh process, and
 * a test that needs one still calls `launch()` — `session.spec.ts` does,
 * because what it is testing is what a second boot remembers.
 *
 * `:dirty false` before closing, because Light Table asks before losing
 * changes and nothing here is going to answer it.
 */
export async function reset(app: ElectronApplication, window: Page): Promise<void> {
    await window.evaluate(`(function () {
        try {
            return lt.objs.control.request('eval', { source: \`
                (do (doseq [ed (lt.object/by-tag :editor)]
                      (lt.object/merge! ed {:dirty false}))
                    (doseq [ts (lt.object/by-tag :tabset)
                            o (vec (:objs @ts))]
                      (lt.object/raise o :close))
                    (doseq [p (lt.object/by-tag :popup)]
                      (lt.object/raise p :close!))
                    (lt.object/raise lt.objs.workspace/current-ws :clear!)
                    (lt.objs.console/clear)
                    (lt.object/clear-errors!)
                    :reset)\` });
        } catch (e) { return 'reset failed: ' + e.message; }
    })()`);

    // Every window but the first. `windows.spec.ts` opens second windows on
    // purpose and does not close them, which was fine when each test had its
    // own application and is a leak now that a worker shares one: the extras
    // would stay alive for every file scheduled after it.
    //
    // `destroy` rather than `close`, for the reason `teardown` gives — a close
    // is a question the editor asks about unsaved changes, and nothing here is
    // going to answer it.
    try {
        await app.evaluate(({ BrowserWindow }) => {
            // The lowest id is the first window — Electron hands them out in
            // order — which is the one the `window` fixture is holding. Not
            // `getAllWindows()[0]`: that array is in no promised order, and
            // destroying the wrong one closes the page every later test uses.
            const all = BrowserWindow.getAllWindows();
            const first = Math.min(...all.map((w) => w.id));
            for (const w of all) if (w.id !== first) w.destroy();
        });
    } catch { /* the application is going away anyway */ }
}

export const test = base.extend<Fixtures, WorkerFixtures>({
    // One per worker, not one per test. Tests inside a file run in order
    // (`fullyParallel: false`), so they share this the way a person shares one
    // editor — and `reset` below is what stops the last test's tabs being the
    // next one's starting position.
    app: [async ({ }, use) => {
        const app = await launch();
        await use(app);
        await teardown(app);
        cleanUpHomes();
    }, { scope: 'worker' }],

    window: [async ({ app }, use) => {
        await use(await editorWindow(app));
    }, { scope: 'worker' }],

    // Automatic, and after the test rather than before: a failure leaves the
    // window as it was for the trace, and the next test still starts clean.
    cleanEditor: [async ({ app, window }, use) => {
        await use(undefined);
        await reset(app, window);
    }, { auto: true }],

    // lt.object catches exceptions thrown inside behavior reactions and
    // reports them, so a behavior that throws looks exactly like one that
    // decided not to act. This is how a test tells the difference.
    ltErrors: async ({ window }, use) => {
        await use(async function() {
            return await window.evaluate(`(function () {
                try {
                    var el = lt.object.__GT_content(lt.objs.console.console);
                    return Array.from(el.querySelectorAll('li.error'))
                                .map(function (n) { return (n.innerText || '').slice(0, 300); });
                } catch (e) { return ['could not read the console: ' + e.message]; }
            })()`) as string[];
        });
    }
});

export { expect };
