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
export async function launch(extraEnv: Record<string, string> = {}): Promise<ElectronApplication> {
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
        args: [CORE, '--no-sandbox'],
        env: {
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
async function teardown(app: ElectronApplication): Promise<void> {
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

    let previous = -1;
    for (let i = 0; i < 60; i++) {
        const now = await count();
        if (now === previous && now > 100) return;
        previous = now;
        await window.waitForTimeout(500);
    }
    throw new Error(`plugins never finished loading (${previous} behaviors)`);
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
    await window.evaluate(`(function () {
        var kw = function (n) { return cljs.core.keyword.call(null, n); };
        var connectors = cljs.core.get.call(null,
            cljs.core.deref(lt.objs.sidebar.clients.clients), kw('connectors'));
        var c = cljs.core.get.call(null, connectors, "Light Table UI");
        return cljs.core.get.call(null, c, kw('connect')).call(null);
    })()`);
    await window.waitForFunction("!!lt.objs.clients.by_name('LightTable-UI')", null, { timeout: 15_000 });
}

/** A directory of this test's own, removed when the test ends. */
export function scratchDir(name: string): string {
    return fs.mkdtempSync(path.join(os.tmpdir(), `lt-e2e-${name}-`));
}

interface Fixtures {
    app: ElectronApplication;
    window: Page;
    /** Errors Light Table reported into its own console during the test. */
    ltErrors: () => Promise<string[]>;
}

export const test = base.extend<Fixtures>({
    app: async ({ }, use) => {
        const app = await launch();
        await use(app);
        await teardown(app);
        cleanUpHomes();
    },

    window: async ({ app }, use) => {
        await use(await editorWindow(app));
    },

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
