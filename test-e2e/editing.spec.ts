// Editing behaviour that has to be reachable, not merely present.
//
// Multiple cursors are the reason this file exists. The CodeMirror addon was
// bundled, the commands were defined, and the whole thing was unreachable for
// want of a keybinding — which is a failure no unit test can see, because
// every piece of it works.

import * as fs from 'node:fs';
import * as path from 'node:path';
import { test, expect, scratchDir } from './fixtures';
import type { Page } from '@playwright/test';

async function openFile(window: Page, file: string): Promise<void> {
    await window.evaluate(
        ([f]) => (globalThis as any).lt.objs.command.exec_BANG_(
            (globalThis as any).cljs.core.keyword.call(null, 'open-path'), f),
        [file]);
    await window.waitForFunction(
        ([f]) => {
            const lt = (globalThis as any).lt, cljs = (globalThis as any).cljs;
            return !!cljs.core.first.call(null, lt.objs.editor.pool.by_path(f));
        }, [file], { timeout: 30_000 });
}

for (const engine of [':cm5', ':cm6']) {
test(`multiple cursors select, edit and clear, on ${engine}`, async ({ window, ltErrors }) => {
    // Both engines, which was not true until the sublime commands were ported.
    // They were a CodeMirror 5 addon registering on the CodeMirror 5 global;
    // CodeMirror 6 keeps multiple selections in the state, so most of them turn
    // out to be a few lines about `EditorSelection` — but they had to be
    // written, because nothing carries them over.
    await window.evaluate(
        `lt.objs.editor.set_engine_BANG_(cljs.core.keyword.call(null, '${engine.slice(1)}'))`);

    const dir = scratchDir('cursors');
    const file = path.join(dir, 'probe.js');
    fs.writeFileSync(file, 'const total = 1;\nconst other = total + total;\n');

    await openFile(window, file);

    const result = await window.evaluate(([f]) => {
        const lt = (globalThis as any).lt, cljs = (globalThis as any).cljs;
        const kw = (n: string) => cljs.core.keyword.call(null, n);
        const run = (c: string) => lt.objs.command.exec_BANG_(kw(c));
        const ed = cljs.core.first.call(null, lt.objs.editor.pool.by_path(f));
        const cm = lt.objs.editor.__GT_cm_ed(ed);

        cm.setCursor({ line: 0, ch: 6 });
        run('editor.sublime.selectNextOccurrence');
        run('editor.sublime.selectNextOccurrence');
        run('editor.sublime.selectNextOccurrence');
        const selected = cm.listSelections().length;

        run('editor.sublime.undoSelection');
        const afterUndo = cm.listSelections().length;
        run('editor.sublime.redoSelection');

        cm.replaceSelections(cm.listSelections().map(() => 'sum'));
        const text = cm.getValue().split('\n')[1];

        run('editor.sublime.singleSelectionTop');
        const afterClear = cm.listSelections().length;
        return { selected, afterUndo, text, afterClear };
    }, [file]) as { selected: number; afterUndo: number; text: string; afterClear: number };

    // Three occurrences of `total`, all selected, all replaced at once.
    expect(result.selected).toBe(3);
    expect(result.afterUndo).toBe(2);
    expect(result.text).toBe('const other = sum + sum;');
    expect(result.afterClear).toBe(1);
    expect(await ltErrors()).toEqual([]);

    fs.rmSync(dir, { recursive: true, force: true });
});
}

test('and every one of them is reachable from a key', async ({ window }) => {
    // The gap this whole file is about: a command nothing can invoke is a
    // command that does not exist as far as anyone using the editor knows.
    const bound = await window.evaluate(`(function () {
        var kw = function (n) { return cljs.core.keyword.call(null, n); };
        var normal = cljs.core.get.call(null, cljs.core.deref(lt.objs.keyboard.keys),
                                        kw('editor.keys.normal'));
        var found = [];
        cljs.core.doall(cljs.core.map.call(null, function (kv) {
            var cmds = cljs.core.pr_str(cljs.core.second(kv));
            if (cmds.indexOf('sublime') !== -1) found.push(cmds);
            return null;
        }, normal));
        return found.join(' ');
    })()`) as string;

    for (const command of ['selectNextOccurrence', 'addCursorToNextLine', 'addCursorToPrevLine',
                           'splitSelectionByLine', 'undoSelection', 'singleSelectionTop']) {
        expect(bound).toContain(command);
    }
});

test('a platform-only binding does not leak onto other platforms', async ({ window }) => {
    // mac:pmeta-d and linux:ctrl-alt-d are the same command spelled for two
    // platforms; exactly one of them should have survived being read.
    const keys = await window.evaluate(`(function () {
        var kw = function (n) { return cljs.core.keyword.call(null, n); };
        var normal = cljs.core.get.call(null, cljs.core.deref(lt.objs.keyboard.keys),
                                        kw('editor.keys.normal'));
        var found = [];
        cljs.core.doall(cljs.core.map.call(null, function (kv) {
            if (cljs.core.pr_str(cljs.core.second(kv)).indexOf('selectNextOccurrence') !== -1) {
                found.push(cljs.core.first(kv));
            }
            return null;
        }, normal));
        return found;
    })()`) as string[];

    expect(keys.length).toBe(1);
    // And the prefix itself never reaches the key table.
    expect(keys[0]).not.toContain(':');
});
