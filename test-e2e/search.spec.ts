// Workspace search and replace.
//
// The interesting case is the one that was broken: replacing across a project
// while one of the files is open in a tab. The worker cannot see tabs, so it
// used to writeFileSync over them and say nothing, and the buffer and the disk
// disagreed until something else saved over one of them.

import * as fs from 'node:fs';
import * as path from 'node:path';
import { test, expect, scratchDir } from './fixtures';
import type { Page } from '@playwright/test';

async function open(window: Page, file: string): Promise<void> {
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

/**
 * Fill the searcher's own inputs and run it.
 *
 * Through the inputs rather than by merging state onto the object, because
 * both ::search! and ::replace! read ->search-info off the DOM — so anything
 * set on the object is ignored, which is a good way to write a test that
 * passes while testing nothing.
 */
async function runSearcher(window: Page, dir: string, search: string,
                           replace: string, trigger: 'search!' | 'replace!') {
    await window.evaluate(([d, s, r, t]) => {
        const lt = (globalThis as any).lt, cljs = (globalThis as any).cljs;
        const kw = (n: string) => cljs.core.keyword.call(null, n);
        const searcher = lt.objs.search.searcher;
        const content = lt.object.__GT_content(searcher);
        (content.querySelector('.search') as HTMLInputElement).value = s!;
        (content.querySelector('.replace') as HTMLInputElement).value = r!;
        (content.querySelector('.loc') as HTMLInputElement).value = d!;
        lt.object.raise.call(null, searcher, kw(t!));
    }, [dir, search, replace, trigger]);
}

const replaceAll = (window: Page, dir: string, search: string, replace: string) =>
    runSearcher(window, dir, search, replace, 'replace!');

test('a replace reaches the tab as well as the disk', async ({ window, ltErrors }) => {
    const dir = scratchDir('replace');
    const openFile = path.join(dir, 'open.txt');
    const closedFile = path.join(dir, 'closed.txt');
    fs.writeFileSync(openFile, 'NEEDLE here\nand nothing else\n');
    fs.writeFileSync(closedFile, 'NEEDLE there\n');

    await open(window, openFile);
    await replaceAll(window, dir, 'NEEDLE', 'THREAD');

    // The closed file is rewritten on disk...
    await expect.poll(() => fs.readFileSync(closedFile, 'utf8')).toBe('THREAD there\n');

    // ...and the open one is rewritten in the tab, not only underneath it.
    const buffer = await window.evaluate(([f]) => {
        const lt = (globalThis as any).lt, cljs = (globalThis as any).cljs;
        const ed = cljs.core.first.call(null, lt.objs.editor.pool.by_path(f));
        return lt.objs.editor.__GT_val(ed);
    }, [openFile]) as string;
    expect(buffer).toBe('THREAD here\nand nothing else\n');

    // Which is the whole point: what the tab shows is what is on disk.
    expect(fs.readFileSync(openFile, 'utf8')).toBe(buffer);
    expect(await ltErrors()).toEqual([]);

    fs.rmSync(dir, { recursive: true, force: true });
});

test('and the whole replace is one undo', async ({ window }) => {
    const dir = scratchDir('replace-undo');
    const a = path.join(dir, 'a.txt');
    const b = path.join(dir, 'b.txt');
    fs.writeFileSync(a, 'NEEDLE one\n');
    fs.writeFileSync(b, 'NEEDLE two\n');

    await open(window, a);
    await replaceAll(window, dir, 'NEEDLE', 'THREAD');
    await expect.poll(() => fs.readFileSync(b, 'utf8')).toBe('THREAD two\n');

    // Across files, which is the reason lt.objs.workspace-edit keeps its own
    // stack: CodeMirror's history is per document, and b.txt was never open.
    await window.evaluate("lt.objs.workspace_edit.undo_BANG_()");

    await expect.poll(() => fs.readFileSync(a, 'utf8')).toBe('NEEDLE one\n');
    expect(fs.readFileSync(b, 'utf8')).toBe('NEEDLE two\n');

    fs.rmSync(dir, { recursive: true, force: true });
});

test('node_modules is not searched', async ({ window }) => {
    const dir = scratchDir('ignore');
    fs.mkdirSync(path.join(dir, 'node_modules', 'left-pad'), { recursive: true });
    fs.writeFileSync(path.join(dir, 'src.txt'), 'NEEDLE in source\n');
    fs.writeFileSync(path.join(dir, 'node_modules', 'left-pad', 'index.js'),
                     'NEEDLE in a dependency\n');

    await runSearcher(window, dir, 'NEEDLE', '', 'search!');

    // One result, and it is not the dependency. On this repository the
    // difference is 10,507 files walked against 1,296 worth reading.
    await expect.poll(async () => await window.evaluate(
        "cljs.core.get.call(null, cljs.core.deref(lt.objs.search.searcher), " +
        "cljs.core.keyword.call(null,'result-count'))")).toBe(1);

    const files = await window.evaluate(`(function () {
        var kw = function (n) { return cljs.core.keyword.call(null, n); };
        var rs = cljs.core.get.call(null, cljs.core.deref(lt.objs.search.searcher), kw('results'));
        return Array.prototype.map.call(rs, function (r) { return r.file; });
    })()`) as string[];
    expect(files.join(' ')).not.toContain('node_modules');

    fs.rmSync(dir, { recursive: true, force: true });
});
