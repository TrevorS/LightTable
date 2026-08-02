// Reopening the editor should reopen your work.
//
// The workspace already survived a restart and every tab did not, so you were
// returned to a project with nothing open in it. This is the layer that can
// see that at all: it needs two runs of the real application sharing one home
// directory, which is the opposite of what every other spec here wants.

import * as fs from 'node:fs';
import * as path from 'node:path';
import { test, expect, launch, editorWindow, scratchDir, openFile } from './fixtures';
import type { ElectronApplication, Page } from '@playwright/test';

/** Open a file and wait for its editor. */

/** The paths currently open, in tab order. */
async function openPaths(window: Page): Promise<string[]> {
    return await window.evaluate(`(function () {
        var kw = function (n) { return cljs.core.keyword.call(null, n); };
        return cljs.core.clj__GT_js(cljs.core.mapv.call(null, lt.objs.tabs.__GT_path,
            cljs.core.vec(cljs.core.mapcat.call(null,
                function (ts) { return cljs.core.get.call(null, cljs.core.deref(ts), kw('objs')); },
                cljs.core.get.call(null, cljs.core.deref(lt.objs.tabs.multi), kw('tabsets'))))));
    })()`) as string[];
}

async function shutDown(app: ElectronApplication): Promise<void> {
    // Through :closed, so the session is written the way a real quit writes it
    // rather than by calling store! directly.
    const window = await app.firstWindow();
    await window.evaluate(`lt.object.raise.call(null, lt.objs.app.app,
                                                cljs.core.keyword.call(null,'closed'))`);
    await window.waitForTimeout(1000);
    await app.evaluate(({ BrowserWindow }) => {
        for (const w of BrowserWindow.getAllWindows()) w.destroy();
    }).catch(() => { /* already gone */ });
    await app.close().catch(() => { /* already gone */ });
}

test('the files that were open come back, with the cursor where it was', async () => {
    const home = scratchDir('session-home');
    const work = scratchDir('session-work');
    for (const [name, body] of [['a.txt', 'one\n'], ['b.txt', 'two\nline2\nline3\n']]) {
        fs.writeFileSync(path.join(work, name!), body!);
    }

    // First run: open two files, put the cursor somewhere findable in the
    // second, and shut down.
    const first = await launch({ LT_USER_DIR: home });
    const firstWindow = await editorWindow(first);
    await openFile(firstWindow, path.join(work, 'a.txt'));
    await openFile(firstWindow, path.join(work, 'b.txt'));
    await firstWindow.evaluate(([f]) => {
        const lt = (globalThis as any).lt, cljs = (globalThis as any).cljs;
        const ed = cljs.core.first.call(null, lt.objs.editor.pool.by_path(f));
        lt.objs.editor.move_cursor(ed, cljs.core.PersistentArrayMap.createAsIfByAssoc(
            [cljs.core.keyword.call(null, 'line'), 2, cljs.core.keyword.call(null, 'ch'), 3]));
    }, [path.join(work, 'b.txt')]);
    expect(await openPaths(firstWindow)).toEqual([path.join(work, 'a.txt'), path.join(work, 'b.txt')]);
    await shutDown(first);

    // The session is on disk and names both files. `ltcache` is what
    // lt.objs.cache calls the directory under LT_USER_DIR.
    const sessionFile = path.join(home, 'ltcache', 'session.clj');
    expect(fs.existsSync(sessionFile)).toBe(true);
    const written = fs.readFileSync(sessionFile, 'utf8');
    expect(written).toContain('a.txt');
    expect(written).toContain('b.txt');

    // Second run, same home directory: both come back, in order.
    const second = await launch({ LT_USER_DIR: home });
    const secondWindow = await editorWindow(second);
    await secondWindow.waitForFunction(
        ([f]) => {
            const lt = (globalThis as any).lt, cljs = (globalThis as any).cljs;
            return !!cljs.core.first.call(null, lt.objs.editor.pool.by_path(f));
        }, [path.join(work, 'b.txt')], { timeout: 60_000 });

    expect(await openPaths(secondWindow)).toEqual([path.join(work, 'a.txt'), path.join(work, 'b.txt')]);

    const cursor = await secondWindow.evaluate(([f]) => {
        const lt = (globalThis as any).lt, cljs = (globalThis as any).cljs;
        const ed = cljs.core.first.call(null, lt.objs.editor.pool.by_path(f));
        return cljs.core.clj__GT_js(lt.objs.editor.__GT_cursor(ed));
    }, [path.join(work, 'b.txt')]) as { line: number; ch: number };
    expect(cursor.line).toBe(2);
    expect(cursor.ch).toBe(3);

    await shutDown(second);
    fs.rmSync(home, { recursive: true, force: true });
    fs.rmSync(work, { recursive: true, force: true });
});

test('a file deleted since last time is skipped rather than fatal', async () => {
    const home = scratchDir('session-home-gone');
    const work = scratchDir('session-work-gone');
    fs.writeFileSync(path.join(work, 'stays.txt'), 'here\n');
    fs.writeFileSync(path.join(work, 'goes.txt'), 'not for long\n');

    const first = await launch({ LT_USER_DIR: home });
    const firstWindow = await editorWindow(first);
    await openFile(firstWindow, path.join(work, 'stays.txt'));
    await openFile(firstWindow, path.join(work, 'goes.txt'));
    await shutDown(first);

    fs.rmSync(path.join(work, 'goes.txt'));

    const second = await launch({ LT_USER_DIR: home });
    const secondWindow = await editorWindow(second);
    await secondWindow.waitForFunction(
        ([f]) => {
            const lt = (globalThis as any).lt, cljs = (globalThis as any).cljs;
            return !!cljs.core.first.call(null, lt.objs.editor.pool.by_path(f));
        }, [path.join(work, 'stays.txt')], { timeout: 60_000 });

    const paths = await openPaths(secondWindow);
    expect(paths).toContain(path.join(work, 'stays.txt'));
    expect(paths).not.toContain(path.join(work, 'goes.txt'));

    await shutDown(second);
    fs.rmSync(home, { recursive: true, force: true });
    fs.rmSync(work, { recursive: true, force: true });
});

test('and the folders that were expanded are expanded again', async () => {
    const home = scratchDir('session-home-tree');
    const work = scratchDir('session-work-tree');
    fs.mkdirSync(path.join(work, 'nested'), { recursive: true });
    fs.writeFileSync(path.join(work, 'nested', 'deep.txt'), 'deep\n');

    const expandedIn = async (window: Page) => await window.evaluate(`(function () {
        return cljs.core.clj__GT_js(cljs.core.vec(
            lt.objs.sidebar.workspace.open_dirs.call(null)));
    })()`) as string[];

    const first = await launch({ LT_USER_DIR: home });
    const firstWindow = await editorWindow(first);
    // A folder in the workspace, expanded, and a file open so the session is
    // written at all.
    await firstWindow.evaluate(([d]) => {
        const lt = (globalThis as any).lt, cljs = (globalThis as any).cljs;
        lt.object.raise.call(null, lt.objs.workspace.current_ws,
                             cljs.core.keyword.call(null, 'add.folder!'), d);
    }, [work]);
    await firstWindow.waitForTimeout(1500);
    await firstWindow.evaluate(([d]) => {
        (globalThis as any).lt.objs.sidebar.workspace.expand_BANG_.call(null, d);
    }, [work]);
    await firstWindow.waitForTimeout(500);
    await openFile(firstWindow, path.join(work, 'nested', 'deep.txt'));
    expect(await expandedIn(firstWindow)).toContain(work);
    await shutDown(first);

    const second = await launch({ LT_USER_DIR: home });
    const secondWindow = await editorWindow(second);
    await expect.poll(async () => await expandedIn(secondWindow)).toContain(work);

    await shutDown(second);
    fs.rmSync(home, { recursive: true, force: true });
    fs.rmSync(work, { recursive: true, force: true });
});
