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
import { test, expect, evalClj, scratchDir } from './fixtures';

// `.wstree__tree` rather than `.wstree` throughout, and it is not cosmetic.
// The panel holds *either* the tree or the list of workspaces you can switch
// to — `lt.ui.view/workspace` picks one — and the recents list draws
// `row/list-row`, so `.wstree .row` matches rows belonging to a view that may
// not be the one on screen a moment later. Playwright resolves such a row,
// starts its actionability checks, and the view swaps underneath it:
//
//     waiting for locator('#side .wstree .row').filter({ hasText: '…' })
//     element was detached from the DOM, retrying
//
// which is what `a file is renamed in the row it is in` did on the first
// attempt of six consecutive Linux CI runs, passing on retry in two seconds
// every time. Scoping the locator to the tree makes the ambiguity impossible
// to hit.



test('the tree and the recents list are never both drawn', async ({ window }) => {
    // The invariant the locators above depend on, asserted rather than assumed.
    // `lt.ui.view/workspace` shows one or the other, and both draw `row`, so a
    // selector scoped only to `.wstree` matches rows from whichever view is not
    // on screen the moment it swaps.
    await evalClj(window, '(do (lt.objs.command/exec! :workspace.show :force) :shown)');
    await evalClj(window, `
        (do (object/raise lt.objs.workspace/current-ws :add.folder! "${scratchDir('ws-views')}") :added)`);
    await expect(window.locator('#side .wstree__tree')).toHaveCount(1);

    await evalClj(window, '(do (lt.actions/dispatch! [[:workspace/show-recents]]) :recents)');
    await expect(window.locator('#side .wstree__tree')).toHaveCount(0);
    // And the recents list draws rows of its own, which is the whole point: an
    // unscoped `.wstree .row` would match these.
    expect(await window.locator('#side .wstree .row').count()).toBeGreaterThan(0);

    await evalClj(window, '(do (lt.actions/dispatch! [[:workspace/show-tree]]) :tree)');
    await expect(window.locator('#side .wstree__tree')).toHaveCount(1);
});

test('a folder can be taken out of the workspace again', async ({ window, ltErrors }) => {
    const dir = scratchDir('ws');
    fs.writeFileSync(path.join(dir, 'a.txt'), 'a\n');

    // Counted against what is already open rather than from zero: the fixture
    // shares one boot, and other tests put folders in this workspace.
    const roots = () => evalClj(window, '(count (get-in @lt.state/app [:workspace :roots]))');
    const started = Number(await roots());

    await evalClj(window, `
        (do (object/raise lt.objs.workspace/current-ws :add.folder! "${dir}") :added)`);
    await expect.poll(async () => Number(await roots())).toBe(started + 1);

    const before = (await ltErrors()).length;

    await evalClj(window, `
        (do (object/raise lt.objs.workspace/current-ws :remove.folder! "${dir}") :removed)`);
    await expect.poll(async () => await evalClj(window,
        `(count (:folders @lt.objs.workspace/current-ws))`)).toBe(String(started));

    const after = await ltErrors();
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

    const names = () => window.locator('#side .wstree__tree .tree__name').allInnerTexts();
    await expect.poll(names).toContain(path.basename(dir));

    // Closed, so what is in it is not drawn — not hidden, not there. That is
    // the difference between a tree of data and a tree of nodes.
    expect(await names()).not.toContain('top.txt');

    await window.locator('#side .wstree__tree .row', { hasText: path.basename(dir) }).first().click();
    await expect.poll(names).toContain('top.txt');
    expect(await names()).toContain('inner');
    // Folders before files, each by name — decided once, where the directory is
    // read, rather than every time the tree is drawn.
    const drawn = await names();
    expect(drawn.indexOf('inner')).toBeLessThan(drawn.indexOf('top.txt'));

    // And one level only: `inner` is closed, so its contents were never read.
    expect(drawn).not.toContain('deep.txt');

    // Clicking a file opens it, which is the other half of what a tree is for.
    await window.locator('#side .wstree__tree .row', { hasText: 'top.txt' }).first().click();
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

test('and what a menu item does is the action it dispatches', async ({ window, ltErrors }) => {
    // The other half of the menu, and the half that was broken: every item
    // dispatches an action, and those actions were registered only as effects
    // — so each one reported `:error/unknown-action` and did nothing. The
    // items being right is not the same as clicking one working.
    const dir = scratchDir('ws-new');

    await evalClj(window, '(do (lt.objs.command/exec! :workspace.show :force) :shown)');
    await evalClj(window, `
        (do (object/raise lt.objs.workspace/current-ws :add.folder! "${dir}") :added)`);
    await window.locator('#side .wstree__tree .row', { hasText: path.basename(dir) }).first().click();

    const before = (await ltErrors()).length;
    await evalClj(window, `(do (lt.actions/dispatch! [[:tree/new-file "${dir}"]]) :new)`);

    // On disk, in the tree, open in the editor, and ready to be named — which
    // is four separate actions the one effect asks for in order.
    expect(fs.existsSync(path.join(dir, 'untitled.txt'))).toBe(true);
    await expect(window.locator('#side .wstree .tree__rename')).toBeFocused();
    expect(await evalClj(window, `
        (boolean (seq (lt.objs.editor.pool/by-path "${path.join(dir, 'untitled.txt')}")))`)).toBe('true');

    await evalClj(window, '(do (lt.objs.command/exec! :workspace.rename.cancel!) :cancelled)');
    await expect.poll(async () => await window.locator('#side .wstree__tree .tree__name').allInnerTexts())
        .toContain('untitled.txt');

    const after = await ltErrors();
    expect(after.length, after.slice(before).join('\n')).toBe(before);

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
    await window.locator('#side .wstree__tree .row', { hasText: path.basename(dir) }).first().click();

    const input = window.locator('#side .wstree .tree__rename');
    const names = () => window.locator('#side .wstree__tree .tree__name').allInnerTexts();
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

test('emptying the workspace is saved, and an empty new one is not written', async ({ window }) => {
    // Removing a folder appeared to do nothing, and the appearance was
    // accurate: the workspace was only written when it had something in it, so
    // taking the last root out left the old contents on disk and the folder
    // came back on the next start.
    const dir = scratchDir('ws-save');
    fs.writeFileSync(path.join(dir, 'a.txt'), 'a\n');

    const saved = () => evalClj(window, `
        (let [f (lt.objs.files/join lt.objs.workspace/workspace-cache-path
                                    (:file @lt.objs.workspace/current-ws))]
          (if (lt.objs.files/exists? f) (:content (lt.objs.files/open-sync f)) "no file"))`);

    // A workspace of exactly one folder, so removing it empties it.
    await evalClj(window, `
        (do (object/raise lt.objs.workspace/current-ws :clear!)
            (object/raise lt.objs.workspace/current-ws :add.folder! "${dir}") :ok)`);
    expect(await saved()).toContain(dir);

    await evalClj(window, `
        (do (object/raise lt.objs.workspace/current-ws :remove.folder! "${dir}") :ok)`);
    await expect.poll(saved).not.toContain(dir);
    expect(await saved()).toContain(':folders []');

    // The other half of the same guard: a workspace that has never been saved
    // is not written just for being empty, or every `New workspace` would leave
    // a blank entry in the recents list.
    const written = await evalClj(window, `
        (do (object/raise lt.objs.workspace/current-ws :new!)
            (let [f (lt.objs.files/join lt.objs.workspace/workspace-cache-path
                                        (:file @lt.objs.workspace/current-ws))]
              (lt.objs.files/exists? f)))`);
    expect(written).toBe('false');

    fs.rmSync(dir, { recursive: true, force: true });
});

test('clearing the workspace closes its editors, and removing one folder does not', async ({ window }) => {
    // The two gestures read differently. Taking one root out of a workspace is
    // a change to the workspace, and the file you are editing out of that
    // folder is very often why you did it. Clearing is an ending.
    const dir = scratchDir('ws-close');
    const file = path.join(dir, 'kept.txt');
    fs.writeFileSync(file, 'text\n');

    const open = () => evalClj(window, `(count (pool/by-path "${file}"))`);

    await evalClj(window, `
        (do (object/raise lt.objs.workspace/current-ws :clear!)
            (object/raise lt.objs.workspace/current-ws :add.folder! "${dir}")
            (cmd/exec! :open-path "${file}") :ok)`);
    await expect.poll(open).toBe('1');

    // Removing the folder leaves the editor alone.
    await evalClj(window, `
        (do (object/raise lt.objs.workspace/current-ws :remove.folder! "${dir}") :ok)`);
    expect(await open()).toBe('1');

    // Clearing closes it. Added back first, so there is a root to be under —
    // an editor on a file belonging to no project is nobody's to close.
    await evalClj(window, `
        (do (object/raise lt.objs.workspace/current-ws :add.folder! "${dir}") :ok)`);
    await evalClj(window, `(do (object/raise lt.objs.workspace/current-ws :clear!) :ok)`);
    await expect.poll(open).toBe('0');

    fs.rmSync(dir, { recursive: true, force: true });
});
