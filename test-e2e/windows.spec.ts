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
import { test, expect, ready, launch, editorWindow, scratchDir } from './fixtures';

test('the editor builds itself in the first window', async ({ window }) => {
    expect(await window.evaluate("typeof lt.objs.app")).toBe('object');
    const behaviors = await window.evaluate(
        "cljs.core.count(cljs.core.deref(lt.object.behaviors))") as number;
    expect(behaviors).toBeGreaterThan(500);
});

test('a second window builds the editor too', async ({ app, window }) => {
    // Through the command, so this is the path a person takes.
    await window.evaluate("lt.objs.command.exec_BANG_(cljs.core.keyword.call(null,'window.new'))");

    const second = await app.waitForEvent('window');
    await ready(second);

    expect(await second.evaluate("typeof lt.objs.app")).toBe('object');
    expect(app.windows().length).toBe(2);
});

test('and its preload ran, which is what the blank one was missing', async ({ app, window }) => {
    await window.evaluate("lt.objs.command.exec_BANG_(cljs.core.keyword.call(null,'window.new'))");
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
    const info = await window.evaluate(`(function () {
        var kw = function (n) { return cljs.core.keyword.call(null, n); };
        var i = lt.util.bridge.app_info;
        return {
            appPath: String(cljs.core.get.call(null, i, kw('appPath'))),
            userDataPath: String(cljs.core.get.call(null, i, kw('userDataPath'))),
            userDir: String(lt.objs.files.lt_user_dir.cljs$core$IFn$_invoke$arity$1('')),
            home: String(lt.objs.files.lt_home.cljs$core$IFn$_invoke$arity$1(''))
        };
    })()`) as { appPath: string; userDataPath: string; userDir: string; home: string };

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

    const errors = await window.evaluate(`(function () {
        var el = lt.object.__GT_content(lt.objs.console.console);
        return Array.from(el.querySelectorAll('li.error')).map(function (n) {
            return (n.innerText || '').slice(0, 200); });
    })()`) as string[];
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
