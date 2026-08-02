// Workspace search and replace.
//
// The interesting case is the one that was broken: replacing across a project
// while one of the files is open in a tab. The worker cannot see tabs, so it
// used to writeFileSync over them and say nothing, and the buffer and the disk
// disagreed until something else saved over one of them.

import * as fs from 'node:fs';
import * as path from 'node:path';
import { test, expect, evalClj, evalData, scratchDir, openFile } from './fixtures';
import type { Page } from '@playwright/test';


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
    await evalClj(window, `
        (let [content (object/->content lt.objs.search/searcher)
              put! (fn [sel v] (set! (.-value ^js (lt.util.dom/$ sel content)) v))]
          (put! :.search "${search}")
          (put! :.replace "${replace}")
          (put! :.loc "${dir}")
          (object/raise lt.objs.search/searcher :${trigger})
          :ran)`);
}

const replaceAll = (window: Page, dir: string, search: string, replace: string) =>
    runSearcher(window, dir, search, replace, 'replace!');

test('a replace reaches the tab as well as the disk', async ({ window, ltErrors }) => {
    const dir = scratchDir('replace');
    const shown = path.join(dir, 'open.txt');
    const closedFile = path.join(dir, 'closed.txt');
    fs.writeFileSync(shown, 'NEEDLE here\nand nothing else\n');
    fs.writeFileSync(closedFile, 'NEEDLE there\n');

    await openFile(window, shown);
    await replaceAll(window, dir, 'NEEDLE', 'THREAD');

    // The closed file is rewritten on disk...
    await expect.poll(() => fs.readFileSync(closedFile, 'utf8')).toBe('THREAD there\n');

    // ...and the open one is rewritten in the tab, not only underneath it.
    const buffer = await evalData<string>(window,
        `(editor/->val (first (pool/by-path "${shown}")))`);
    expect(buffer).toBe('THREAD here\nand nothing else\n');

    // Which is the whole point: what the tab shows is what is on disk.
    expect(fs.readFileSync(shown, 'utf8')).toBe(buffer);
    expect(await ltErrors()).toEqual([]);

    fs.rmSync(dir, { recursive: true, force: true });
});

test('and the whole replace is one undo', async ({ window }) => {
    const dir = scratchDir('replace-undo');
    const a = path.join(dir, 'a.txt');
    const b = path.join(dir, 'b.txt');
    fs.writeFileSync(a, 'NEEDLE one\n');
    fs.writeFileSync(b, 'NEEDLE two\n');

    await openFile(window, a);
    await replaceAll(window, dir, 'NEEDLE', 'THREAD');
    await expect.poll(() => fs.readFileSync(b, 'utf8')).toBe('THREAD two\n');

    // Across files, which is the reason lt.objs.workspace-edit keeps its own
    // stack: CodeMirror's history is per document, and b.txt was never open.
    await evalClj(window, '(do (lt.objs.workspace-edit/undo!) :undone)');

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
    await expect.poll(async () => await evalData(window,
        '(:result-count @lt.objs.search/searcher)')).toBe(1);

    // `:results` is a JavaScript array of plain objects from the worker, so the
    // field is read with `.-file` rather than as a keyword.
    const files = await evalData<string[]>(window,
        '(mapv #(.-file ^js %) (array-seq (:results @lt.objs.search/searcher)))');
    expect(files.join(' ')).not.toContain('node_modules');

    fs.rmSync(dir, { recursive: true, force: true });
});

test('the results are drawn, and clearing them takes them off screen', async ({ window }) => {
    // The panel is a view of the object now, and nothing asserted that it draws
    // at all: these tests read `:result-count` and `:results`, which are the
    // worker's answer rather than what is on screen. A conversion that rendered
    // nothing would have passed every one of them.
    const dir = scratchDir('drawn');
    fs.mkdirSync(path.join(dir, 'sub'), { recursive: true });
    fs.writeFileSync(path.join(dir, 'one.txt'), 'NEEDLE here\nplain\nNEEDLE twice\n');
    fs.writeFileSync(path.join(dir, 'sub', 'two.txt'), 'and NEEDLE there\n');

    await evalClj(window, '(do (cmd/exec! :searcher.show) :shown)');
    await runSearcher(window, dir, 'NEEDLE', '', 'search!');

    // Two files, three matches, and the line numbers the worker reported.
    const files = window.locator('.search-results .res > li');
    await expect.poll(async () => await files.count()).toBe(2);
    await expect.poll(async () => await window.locator('.search-results .res .entry').count()).toBe(3);
    expect(await window.locator('.search-results .res .file').allInnerTexts())
        .toEqual(expect.arrayContaining(['one.txt', 'two.txt']));
    expect((await window.locator('.search-results .res .entry').first().innerText()))
        .toContain('NEEDLE here');

    // The count line is part of the same view.
    expect(await window.textContent('.search-results .searcher p')).toContain('3 results');

    // And clearing is the array being replaced rather than the `ul` being
    // emptied, which is what the conversion changed.
    await evalClj(window, '(do (object/raise lt.objs.search/searcher :clear!) :cleared)');
    await expect.poll(async () => await files.count()).toBe(0);

    fs.rmSync(dir, { recursive: true, force: true });
});

test('and a click on a result opens that file at that line', async ({ window }) => {
    // The one thing you can do to a result. It was a `defui` handler and is a
    // handler in the hiccup now; a click that stopped working would look
    // exactly like a search that found the wrong thing.
    const dir = scratchDir('clicked');
    const file = path.join(dir, 'target.txt');
    fs.writeFileSync(file, 'zero\none\nNEEDLE on line three\nthree\n');

    await evalClj(window, '(do (cmd/exec! :searcher.show) :shown)');
    await runSearcher(window, dir, 'NEEDLE', '', 'search!');
    await expect.poll(async () => await window.locator('.search-results .res .entry').count()).toBe(1);

    await window.locator('.search-results .res .entry').first().click();

    await expect.poll(async () => await evalData(window,
        `(count (pool/by-path "${file}"))`)).toBe(1);
    await expect.poll(async () => await evalData(window,
        `(:line (editor/->cursor (first (pool/by-path "${file}"))))`)).toBe(2);

    await evalClj(window, `
        (do (doseq [ed (pool/by-path "${file}")] (object/raise ed :close)) :closed)`);
    fs.rmSync(dir, { recursive: true, force: true });
});
