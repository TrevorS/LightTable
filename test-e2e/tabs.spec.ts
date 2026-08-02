// The tab strip, which is `lt.ui.view/titlebar`.
//
// It was a `<ul>` of `::tab-label` objects — one object with one node per tab
// per tabset — rebuilt from nothing whenever anything about the tabset changed,
// with a sortable library reattached to the fresh node every time. The strip is
// drawn from `[:tabsets]` in the state now, and reordering a tab is a change to
// the tab order rather than a library moving list items.
//
// `test/lt/ui/view_test.cljs` asserts the view from a map, which is most of
// what matters and cannot see whether the strip in this window is that
// function. This says what the tabs are and reads the screen.

import { test, expect, evalClj } from './fixtures';
import type { Page } from '@playwright/test';


/** The labels in the first tabset's strip, in order.
 *
 * `.tab__label` rather than the tab, because a tab also contains a close
 * button and `innerText` picks it up whenever it is visible. */
const labels = (window: Page) =>
    window.locator('#multi .tabset .titlebar .tab .tab__label').allInnerTexts();

/** The id of the first tabset, which every action the strip emits carries. */
const firstTabset = (window: Page) =>
    evalClj(window, '(:id (first (:tabsets @lt.state/app)))');

test('a tab is drawn for every open tab, named by what is in it', async ({ window }) => {
    await evalClj(window, '(do (cmd/exec! :new-file) (cmd/exec! :new-file) :two)');
    await expect.poll(() => labels(window)).toHaveLength(2);

    // Not the leaf of a path: most tabs are not files. The component kit has a
    // name and no path at all, and a strip that took the leaf would draw the
    // synthetic `obj-42` the projection keys it by.
    await evalClj(window, '(do (cmd/exec! :kit.catalogue) :opened)');
    await expect.poll(() => labels(window)).toContain('Component kit');
});

test('and the active one is the tab you are in', async ({ window }) => {
    await evalClj(window, '(do (cmd/exec! :new-file) (cmd/exec! :new-file) :two)');
    const tabs = window.locator('#multi .tabset .titlebar .tab');
    await expect.poll(() => labels(window)).toHaveLength(2);
    await expect(tabs.nth(1)).toHaveClass(/tab--active/);

    // Clicking dispatches `[:tab/activate <tabset> <index>]`, and the tabset is
    // in it because a window can be split and the strip you clicked is not
    // always the first.
    await tabs.nth(0).click();
    await expect(tabs.nth(0)).toHaveClass(/tab--active/);
    await expect(tabs.nth(1)).not.toHaveClass(/tab--active/);
    expect(await evalClj(window, '(:active (first (:tabsets @lt.state/app)))')).toBe('0');
});

test('a modified buffer says so with a dot, not an asterisk in its name', async ({ window }) => {
    // The old strip put a `*` after the file name with a `::after` rule. The
    // kit's answer is a dot: the label is the file's name and a name does not
    // change when you type.
    await evalClj(window, '(do (cmd/exec! :new-file) :opened)');
    const tab = window.locator('#multi .tabset .titlebar .tab').last();
    await expect(tab.locator('.dot--result')).toHaveCount(0);

    await evalClj(window, `
        (do (object/merge! (lt.objs.editor.pool/last-active) {:dirty true}) :dirtied)`);
    await expect(tab.locator('.dot--result')).toHaveCount(1);
    expect(await tab.innerText()).not.toContain('*');

    await evalClj(window, `
        (do (object/merge! (lt.objs.editor.pool/last-active) {:dirty false}) :clean)`);
    await expect(tab.locator('.dot--result')).toHaveCount(0);
});

test('reordering a tab is a change to the tab order', async ({ window }) => {
    // What `dragdrop.ts` did by moving list items and reading their positions
    // back out of the document afterwards. Picking a tab up is one state
    // change and dropping it is another, so this can be asserted without
    // simulating a drag — which is the point of handlers being data.
    await evalClj(window, '(do (cmd/exec! :new-file) (cmd/exec! :new-file) (cmd/exec! :new-file) :three)');
    await expect.poll(() => labels(window)).toHaveLength(3);
    const [first, second, third] = await labels(window);
    const ts = await firstTabset(window);

    await evalClj(window, `(do (lt.actions/dispatch! [[:tab/drag-start ${ts} 0]]) :picked-up)`);
    expect(await evalClj(window, '(:dragging @lt.state/app)')).toContain(':index 0');

    await evalClj(window, `(do (lt.actions/dispatch! [[:tab/drop ${ts} 2]]) :dropped)`);
    await expect.poll(() => labels(window)).toEqual([second, third, first]);
    expect(await evalClj(window, '(:dragging @lt.state/app)')).toBe('nil');

    // And the objects agree, because the objects are what moved — the strip is
    // drawn from them rather than the other way round.
    expect(await evalClj(window, `
        (mapv lt.objs.tabs/->name (:objs @(first (:tabsets @lt.objs.tabs/multi))))`))
        .toBe(`["${second}" "${third}" "${first}"]`);

    // A drop with nothing picked up is not a reorder. The strip's own node
    // dispatches this when you let go over the empty end of it.
    await evalClj(window, `(do (lt.actions/dispatch! [[:tab/drop ${ts} 0]]) :nothing)`);
    await expect.poll(() => labels(window)).toEqual([second, third, first]);
});

test('the close button is a fact about the tab, and closes it', async ({ window }) => {
    // `:close-button+` is a raise-reduce answered by `show-close-button`, a
    // user behavior — so whether a tab can be closed is a fact about that tab
    // and the view is handed it rather than deciding. It is on by default here
    // and hidden until you are on the tab, which is CSS: a row of crosses is a
    // row of mis-clicks.
    await evalClj(window, '(do (cmd/exec! :new-file) :opened)');
    const tab = window.locator('#multi .tabset .titlebar .tab').last();
    await expect(tab.locator('.tab__close')).toHaveCount(1);
    expect(await evalClj(window, `
        (:closable? (last (:tabs (first (:tabsets @lt.state/app)))))`)).toBe('true');

    const before = (await labels(window)).length;
    // Hovered first, and that is the assertion as much as the setup: the
    // button is `visibility: hidden` until you are on the tab, so Playwright
    // refusing to click an invisible element is the rule being enforced.
    await tab.hover();
    await tab.locator('.tab__close').click();
    await expect.poll(() => labels(window)).toHaveLength(before - 1);

    // A tab that says it cannot be closed is drawn without one — asserted from
    // a map in test/lt/ui/view_test.cljs, where turning the behavior off is
    // one key rather than a race with the editor that is being opened.
});
