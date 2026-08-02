// Adding a folder to the workspace, and taking it back out.
//
// Removing one used to throw: `The "path" argument must be of type string.
// Received null`, from Node, in a stack whose only Light Table frame was
// `object/destroy!`. A tree item was an object rendered from its own atom, and
// `destroy!` resets that atom to nil, so every view still watching it
// recomputed against nothing and the one that reads the folder's path handed
// nil to Node.
//
// There is no object per row any more — the tree is `[:workspace :nodes]` in
// the state and `lt.ui.view/workspace` draws it — so that failure has no way to
// happen. What is worth asserting survives the rewrite unchanged: the tree
// follows the workspace both ways, and does it without saying anything to the
// console.

import * as fs from 'node:fs';
import * as path from 'node:path';
import { test, expect, scratchDir } from './fixtures';
import type { Page } from '@playwright/test';

async function evalClj(window: Page, source: string): Promise<any> {
    let job = await window.evaluate(
        ([s]) => (globalThis as any).lt.objs.control.request('eval', { source: s }), [source]);
    for (let i = 0; i < 100 && job.status === 'working'; i++) {
        await window.waitForTimeout(50);
        job = await window.evaluate(
            ([id]) => (globalThis as any).lt.objs.control.request('job', { job: id }), [job.id]);
    }
    if (job.status !== 'completed') throw new Error(`${job.status}: ${job.error}\n${source}`);
    return job.result;
}

/** Whatever the editor's own console has been told about. */
const consoleErrors = (window: Page) => window.evaluate(() => {
    const w = globalThis as any;
    const el = w.lt.object.__GT_content(w.lt.objs.console.console) as HTMLElement;
    return Array.from(el.querySelectorAll('li.error')).map(
        (n) => ((n as HTMLElement).innerText || '').slice(0, 300));
});

test('a folder can be taken out of the workspace again', async ({ window }) => {
    const dir = scratchDir('ws');
    fs.writeFileSync(path.join(dir, 'a.txt'), 'a\n');

    // Counted against what is already open rather than from zero: the fixture
    // shares one boot, and other tests put folders in this workspace.
    const roots = () => evalClj(window, '(count (get-in @lt.state/app [:workspace :roots]))');
    const started = Number(await roots());

    await evalClj(window, `
        (do (object/raise lt.objs.workspace/current-ws :add.folder! "${dir}") :added)`);
    await expect.poll(async () => Number(await roots())).toBe(started + 1);

    const before = (await consoleErrors(window)).length;

    await evalClj(window, `
        (do (object/raise lt.objs.workspace/current-ws :remove.folder! "${dir}") :removed)`);
    await expect.poll(async () => await evalClj(window,
        `(count (:folders @lt.objs.workspace/current-ws))`)).toBe(String(started));

    const after = await consoleErrors(window);
    expect(after.length, after.slice(before).join('\n')).toBe(before);
    expect(after.join(' ')).not.toContain('must be of type string');

    // And the sidebar agrees, rather than keeping a row for a folder that is no
    // longer in the workspace.
    expect(Number(await roots())).toBe(started);

    fs.rmSync(dir, { recursive: true, force: true });
});

test('the tree draws the folder, and opening it reads the folder', async ({ window }) => {
    const dir = scratchDir('ws-tree');
    fs.mkdirSync(path.join(dir, 'inner'), { recursive: true });
    fs.writeFileSync(path.join(dir, 'inner', 'deep.txt'), 'deep\n');
    fs.writeFileSync(path.join(dir, 'top.txt'), 'top\n');

    await evalClj(window, '(do (lt.objs.command/exec! :workspace.show :force) :shown)');
    await evalClj(window, `
        (do (object/raise lt.objs.workspace/current-ws :add.folder! "${dir}") :added)`);

    const names = () => window.locator('#side .wstree .tree__name').allInnerTexts();
    await expect.poll(names).toContain(path.basename(dir));

    // Closed, so what is in it is not drawn — not hidden, not there. That is
    // the difference between a tree of data and a tree of nodes.
    expect(await names()).not.toContain('top.txt');

    await window.locator('#side .wstree .row', { hasText: path.basename(dir) }).first().click();
    await expect.poll(names).toContain('top.txt');
    expect(await names()).toContain('inner');
    // Folders before files, each by name — decided once, where the directory is
    // read, rather than every time the tree is drawn.
    const drawn = await names();
    expect(drawn.indexOf('inner')).toBeLessThan(drawn.indexOf('top.txt'));

    // And one level only: `inner` is closed, so its contents were never read.
    expect(drawn).not.toContain('deep.txt');

    // Clicking a file opens it, which is the other half of what a tree is for.
    await window.locator('#side .wstree .row', { hasText: 'top.txt' }).first().click();
    await expect.poll(async () => await evalClj(window,
        `(boolean (seq (lt.objs.editor.pool/by-path "${path.join(dir, 'top.txt')}")))`)).toBe('true');

    await evalClj(window, `
        (do (object/raise lt.objs.workspace/current-ws :remove.folder! "${dir}") :removed)`);
    fs.rmSync(dir, { recursive: true, force: true });
});

test('the right-click menu is built from the path, not from an object', async ({ window }) => {
    // What replaced four `menu-items` behaviors hung off four object types. It
    // is still `raise-reduce`, so a plugin can still add an item — the thing an
    // item is about is a path now, which is what one wanted from an object
    // anyway. The popup itself is Electron's and cannot be read from here, so
    // this asks for the items the effect would show.
    const dir = scratchDir('ws-menu');
    fs.writeFileSync(path.join(dir, 'a.txt'), 'a\n');

    await evalClj(window, `
        (do (object/raise lt.objs.workspace/current-ws :add.folder! "${dir}") :added)`);

    const labels = (p: string) => evalClj(window, `
        (pr-str (mapv :label (object/raise-reduce lt.objs.sidebar.workspace/sidebar-workspace
                                                  :tree-menu-items [] "${p}")))`);

    const onFile = await labels(path.join(dir, 'a.txt'));
    expect(onFile).toContain('Rename');
    expect(onFile).toContain('Duplicate');
    expect(onFile).not.toContain('New folder');

    const onRoot = await labels(dir);
    expect(onRoot).toContain('New file');
    expect(onRoot).toContain('Refresh folder');
    expect(onRoot).not.toContain('Duplicate');
    // Only a root offers this, because only a root is in the workspace — for
    // anything below it, leaving the workspace is not a thing it can do.
    expect(onRoot).toContain('Remove from workspace');
    expect(onFile).not.toContain('Remove from workspace');

    await evalClj(window, `
        (do (object/raise lt.objs.workspace/current-ws :remove.folder! "${dir}") :removed)`);
    fs.rmSync(dir, { recursive: true, force: true });
});

test('a file is renamed in the row it is in', async ({ window }) => {
    // The one thing in the tree that types. It is worth an end-to-end test
    // because three separate mechanisms meet in it: the row becomes an input
    // that focuses itself on mount, `esc` and `enter` are the keymap's in the
    // `:tree.rename` context, and what you typed reaches the action through a
    // `:event/value` placeholder — a handler is a value and cannot read an
    // event, so the argument is named and filled in at dispatch.
    const dir = scratchDir('ws-rename');
    fs.writeFileSync(path.join(dir, 'before.txt'), 'x\n');

    await evalClj(window, '(do (lt.objs.command/exec! :workspace.show :force) :shown)');
    await evalClj(window, `
        (do (object/raise lt.objs.workspace/current-ws :add.folder! "${dir}") :added)`);
    await window.locator('#side .wstree .row', { hasText: path.basename(dir) }).first().click();

    const input = window.locator('#side .wstree .tree__rename');
    const names = () => window.locator('#side .wstree .tree__name').allInnerTexts();
    await expect.poll(names).toContain('before.txt');

    await evalClj(window, `
        (do (lt.actions/dispatch! [[:tree/rename-start "${path.join(dir, 'before.txt')}"]]) :renaming)`);
    await expect(input).toBeFocused();
    // Selected up to the extension, because that is not the part you are
    // changing — so typing replaces the name and keeps the `.txt`.
    expect(await input.evaluate((el: HTMLInputElement) => [el.selectionStart, el.selectionEnd]))
        .toEqual([0, 'before'.length]);

    await input.fill('after.txt');
    await input.blur();

    await expect.poll(names).toContain('after.txt');
    expect(fs.existsSync(path.join(dir, 'after.txt'))).toBe(true);
    expect(fs.existsSync(path.join(dir, 'before.txt'))).toBe(false);

    // And escaping leaves the name alone, which is the other order the guard in
    // `:tree/rename-submit` exists for: cancelling removes the input, removing
    // the input blurs it, and blur is what submits.
    await evalClj(window, `
        (do (lt.actions/dispatch! [[:tree/rename-start "${path.join(dir, 'after.txt')}"]]) :renaming)`);
    await input.fill('never.txt');
    await evalClj(window, '(do (lt.objs.command/exec! :workspace.rename.cancel!) :cancelled)');
    await expect(input).toHaveCount(0);
    expect(fs.existsSync(path.join(dir, 'never.txt'))).toBe(false);
    expect(fs.existsSync(path.join(dir, 'after.txt'))).toBe(true);

    await evalClj(window, `
        (do (object/raise lt.objs.workspace/current-ws :remove.folder! "${dir}") :removed)`);
    fs.rmSync(dir, { recursive: true, force: true });
});
