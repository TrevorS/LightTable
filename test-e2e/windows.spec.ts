// Windows, and what every one of them is owed.
//
// This file exists because of a bug that shipped: the second window a user
// opened was blank, and every check in script/smoke-test.js passed throughout,
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
