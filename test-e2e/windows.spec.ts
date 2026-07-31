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

import { test, expect, ready } from './fixtures';

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
