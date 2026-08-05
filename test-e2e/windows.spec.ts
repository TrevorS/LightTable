// Windows, and what every one of them is owed.
//
// This file exists because of a bug that shipped: the second window a user
// opened was blank, and every check in script/smoke-test.mts passed throughout,
// because they all ran in a first window and the harness built its window
// options itself instead of calling the code that ships.
//
// The defect — a shared options object mutated per window — is pinned exactly
// by test-electron/config.test.ts, in milliseconds and with no Electron. What
// belongs here is the consequence, which no unit test can see: that a second
// window is a working editor.

import * as fs from 'node:fs';
import * as path from 'node:path';
import { test, expect, evalClj, evalData, ready, launch, teardown, editorWindow, scratchDir } from './fixtures';

test('the editor builds itself in the first window', async ({ window }) => {
    expect(await window.evaluate("typeof lt.objs.app")).toBe('object');
    expect(await evalData<number>(window, '(count @object/behaviors)')).toBeGreaterThan(500);
});

test('a second window builds the editor too', async ({ app, window }) => {
    // Through the command, so this is the path a person takes.
    await evalClj(window, '(do (cmd/exec! :window.new) :opening)');

    const second = await app.waitForEvent('window');
    await ready(second);

    expect(await second.evaluate("typeof lt.objs.app")).toBe('object');
    expect(app.windows().length).toBe(2);
});

test('and its preload ran, which is what the blank one was missing', async ({ app, window }) => {
    await evalClj(window, '(do (cmd/exec! :window.new) :opening)');
    const second = await app.waitForEvent('window');
    await ready(second);

    // No preload meant no `lightTable`, and the bundle threw on the first
    // thing that reached the bridge — `lightTable is not defined`, into a
    // window that stayed white. Both windows get the same bridge or neither
    // is trustworthy.
    for (const w of [window, second]) {
        expect(await w.evaluate("typeof window.lightTable")).toBe('object');
        // host is where appInfo lives, and appInfo is the first thing the
        // bundle asks for — so this is the call that threw in the blank one.
        expect(await w.evaluate("typeof window.lightTable.host.appInfo")).toBe('function');
        expect(await w.evaluate("typeof window.lightTable.host.appInfo().appPath")).toBe('string');
    }

    // The same bridge, not merely a bridge each: a second window built from
    // different options is a difference that shows up later and elsewhere.
    const [first, other] = await Promise.all([window, second].map((w) =>
        w.evaluate("Object.keys(window.lightTable).sort().join(',')")));
    expect(other).toBe(first);
});

test('a window reports no errors while starting', async ({ ltErrors }) => {
    expect(await ltErrors()).toEqual([]);
});

test('user data is written outside the application, not inside it', async ({ app, window }) => {
    // Light Table used appPath for both what it reads and what it writes. In a
    // packaged build appPath is Contents/Resources/app, so the first run wrote
    // User/, logs/ and ltcache/ into the .app — `codesign --verify` then said
    // "a sealed resource is missing or invalid", and an application installed
    // where the user cannot write could not start at all.
    const info = await evalData<{ appPath: string; userDataPath: string; userDir: string; home: string }>(
        window, `
        {:appPath (str (:appPath lt.util.bridge/app-info))
         :userDataPath (str (:userDataPath lt.util.bridge/app-info))
         :userDir (str (files/lt-user-dir ""))
         :home (str (files/lt-home ""))}`);

    expect(info.userDataPath.length).toBeGreaterThan(0);
    // userData is somewhere of the user's, not somewhere of the application's.
    expect(info.userDataPath.startsWith(info.appPath)).toBe(false);
    // The fixture sets LT_USER_DIR, which wins over both — that is the escape
    // hatch every test here relies on. What matters is that what gets written
    // is never inside the application.
    expect(info.userDir.startsWith(info.appPath)).toBe(false);
    // Reading is a different directory, and still the application's.
    expect(info.home.length).toBeGreaterThan(0);
});

test('the User plugin is refreshed from the build, not left stale', async () => {
    // user_compiled.js references the bundle's hoisted constants, and those
    // are numbered per compilation — so a copy made by one build throws
    // `cljs$cst$110$tags is not defined` when a later build loads it. It was
    // copied once and never refreshed, which was invisible only because the
    // user directory used to *be* the application directory.
    const home = scratchDir('user-plugin');
    const copied = path.join(home, 'User', 'user_compiled.js');

    const first = await launch({ LT_USER_DIR: home });
    await editorWindow(first);
    await expect.poll(() => fs.existsSync(copied)).toBe(true);
    // Something a previous build could have left.
    fs.writeFileSync(copied, 'cljs$cst$1$stale;\n');
    await first.close().catch(() => { /* already gone */ });

    const second = await launch({ LT_USER_DIR: home });
    const window = await editorWindow(second);
    expect(fs.readFileSync(copied, 'utf8')).not.toContain('cljs$cst$1$stale');

    const errors = await evalData<string[]>(window, `
        (->> (.querySelectorAll ^js (object/->content lt.objs.console/console) "li.error")
             array-seq
             (mapv #(subs (or (.-innerText ^js %) "") 0 (min 200 (count (or (.-innerText ^js %) ""))))))`);
    expect(errors.join(' ')).not.toContain('user_compiled.js');

    await second.close().catch(() => { /* already gone */ });
    fs.rmSync(home, { recursive: true, force: true });
});

test('a plugin built against another release is refused by name', async () => {
    // The case the User plugin fix does not reach: a plugin a person installed
    // before upgrading. Its compiled module names constants this build does not
    // have, and evaluating it throws `cljs$cst$…$… is not defined` — an error
    // about a variable, for a problem about a version.
    const home = scratchDir('stale-plugin');
    const plugin = path.join(home, 'plugins', 'Stale');
    fs.mkdirSync(plugin, { recursive: true });
    fs.writeFileSync(path.join(plugin, 'plugin.edn'),
        '{:name "Stale" :version "0.0.1" :author "a test" :desc "Built against another release."\n' +
        ' :behaviors "stale.behaviors" :capabilities #{}}\n');
    fs.writeFileSync(path.join(plugin, 'stale.behaviors'),
        '{:+ {:app [(:lt.objs.plugins/load-js "stale_compiled.js" true)]}}\n');
    // Index 110 is a real constant in this build, under another name. Comparing
    // indices would have let this through; comparing names is what catches it.
    fs.writeFileSync(path.join(plugin, 'stale_compiled.js'),
        'lt.plugins.stale = {go: function () { return cljs$cst$110$tags; }};\n');

    const app = await launch({ LT_USER_DIR: home });
    const window = await editorWindow(app);

    // Asked of the error ring rather than of the console, because that is what
    // something driving the editor can ask — and a plugin that did not load is
    // the first thing it would want to know.
    const errors = await window.evaluate(
        "lt.objs.control.request('errors', {})") as { errors: { message: string }[] };
    const said = errors.errors.map((e) => e.message).join(' ');
    expect(said).toContain('stale_compiled.js');
    expect(said).toContain('different build');
    expect(said).toContain('cljs$cst$110$tags');
    // The editor is still an editor: one refused plugin is not a white screen.
    expect(await window.evaluate("typeof lt.objs.app")).toBe('object');

    await app.close().catch(() => { /* already gone */ });
    fs.rmSync(home, { recursive: true, force: true });
});

test('a window whose renderer dies comes back, instead of sitting there blank', async () => {
    // The white screen, as an event rather than a description. A renderer that
    // goes away used to leave the window exactly where it was — right size, title
    // bar, nothing in it — with nothing logged and nothing said, which is
    // indistinguishable from a hang. `test-electron/window-recovery.test.ts` pins
    // the decision and that the listeners are attached; only this can say that a
    // real dead renderer becomes a working editor again.
    //
    // Its own application, and no `window` fixture: crashing a renderer leaves
    // Playwright's `Page` unusable, and the shared per-test cleanup resets the
    // editor *through* that page, so borrowing the fixture window fails in
    // teardown with `Target crashed` after the test itself has passed.
    const app = await launch();
    try {
        await ready(await editorWindow(app));

        // Through Electron's own API for it, from the main process, so this takes
        // the same event path as a genuine crash.
        await app.evaluate(({ BrowserWindow }) => {
            const [first] = BrowserWindow.getAllWindows();
            first!.webContents.forcefullyCrashRenderer();
        });

        // Asserted from the main process rather than through Playwright, which
        // marks a crashed `Page` unusable for good — the window reloads into the
        // same BrowserWindow, so there is never a new page to attach to and the
        // old handle stays crashed for ever. `executeJavaScript` runs in whatever
        // renderer is there *now*, which is exactly the thing under test.
        const revived = await app.evaluate(async ({ BrowserWindow }) => {
            const [first] = BrowserWindow.getAllWindows();
            const wc = first!.webContents;

            const deadline = Date.now() + 90_000;
            const settle = () => new Promise((r) => setTimeout(r, 250));
            // A live renderer that has finished loading and built the editor. All
              // three, because a fresh but empty renderer would satisfy the first two.
            for (;;) {
                if (Date.now() > deadline) return { ok: false, why: 'never came back' };
                if (!wc.isCrashed() && !wc.isLoading()) {
                    try {
                        const kind = await wc.executeJavaScript("typeof lt.objs.app");
                        if (kind === 'object') {
                            const count = await wc.executeJavaScript(
                                "cljs.core.count(cljs.core.deref(lt.object.behaviors))");
                            const children = await wc.executeJavaScript("document.body.children.length");
                            return { ok: true, count, children, crashed: wc.isCrashed() };
                        }
                    } catch {
                        // Still starting: the bundle has not defined `lt` yet.
                    }
                }
                await settle();
            }
        });

        expect(revived).toMatchObject({ ok: true, crashed: false });
        // An editor, not merely a document that loaded.
        expect((revived as { count: number }).count).toBeGreaterThan(500);
        expect((revived as { children: number }).children).toBeGreaterThan(0);
    } finally {
        await teardown(app);
    }
});
