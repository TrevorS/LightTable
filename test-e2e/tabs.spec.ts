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

import * as fs from 'node:fs';
import * as path from 'node:path';
import { test, expect, evalClj, evalData, openFile, scratchDir } from './fixtures';
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

test('a tab keeps its own DOM, and the tabset only shows one at a time', async ({ window }) => {
    // The last thing to move, and the most load-bearing: a tabset composes
    // every tab's `->content` — an editor, the plugin manager, a browser —
    // which is the shape doc/rendering.md called the one that cannot be swapped
    // a component at a time. Every tab is drawn and all but the active one are
    // hidden, which is how a tab keeps its scroll position and its editor's
    // state while another is in front.
    const dir = scratchDir('hosted-tabs');
    const files = ['first.txt', 'second.txt'].map((n) => path.join(dir, n));
    files.forEach((f, i) => fs.writeFileSync(f, `file ${i}\n`.repeat(3)));
    for (const f of files) await openFile(window, f);

    const slots = window.locator('#multi .tabset .items > .content');
    await expect.poll(async () => await slots.count()).toBeGreaterThanOrEqual(2);

    // Exactly one visible, and it is the active object's.
    expect(await evalData(window, `
        (let [ts (:lt.objs.tabs/tabset @(first (pool/by-path "${files[1]}")))
              slots (array-seq (.querySelectorAll ^js (object/->content ts) ".items > .content"))]
          {:visible (count (filter #(= "visible" (.-style.visibility ^js %)) slots))
           :active-is-visible
           (= "visible" (some (fn [^js s]
                                (when (identical? (.-firstChild s)
                                                  (object/->content (:active-obj @ts)))
                                  (.-style.visibility s)))
                              slots))})`))
        .toEqual({ visible: 1, 'active-is-visible': true });

    // The node in each slot is the tab object's own, placed rather than copied,
    // and it is a direct child because `#multi .content > *` is a child rule.
    expect(await evalData(window, `
        (let [ed (first (pool/by-path "${files[0]}"))
              ts (:lt.objs.tabs/tabset @ed)]
          (boolean (some (fn [^js s] (identical? (.-firstChild s) (object/->content ed)))
                         (array-seq (.querySelectorAll ^js (object/->content ts)
                                                       ".items > .content")))))`))
        .toBe(true);

    // Switching tabs moves the visibility rather than rebuilding anything: the
    // editor you left is the same element when you come back to it.
    await evalClj(window, `
        (do (set! (.-ltTabProbe ^js (object/->content (first (pool/by-path "${files[0]}")))) "kept")
            (tabs/active! (first (pool/by-path "${files[0]}")))
            :switched)`);
    await expect.poll(async () => await evalData(window, `
        (.-ltTabProbe ^js (object/->content (first (pool/by-path "${files[0]}"))))`)).toBe('kept');
    expect(await evalData(window, `
        (let [ts (:lt.objs.tabs/tabset @(first (pool/by-path "${files[0]}")))]
          (count (filter #(= "visible" (.-style.visibility ^js %))
                         (array-seq (.querySelectorAll ^js (object/->content ts)
                                                       ".items > .content")))))`))
        .toBe(1);

    // The strip and the grip are still where the layout expects them: the strip
    // is a render root of its own, made once and hosted, and the grip is drawn
    // by the view as a direct child.
    // One each per tabset rather than one overall: an earlier test in this file
    // splits the window, and a tabset without its own strip is the failure.
    //
    // `> .list > .titlebar` rather than a descendant: the strip's own root is a
    // `.titlebar` and `lt.ui.view/titlebar` returns another inside it, which is
    // a pre-existing nesting and not what this is asking about.
    const tabsets = await window.locator('#multi .tabset').count();
    await expect(window.locator('#multi .tabset > .list > .titlebar')).toHaveCount(tabsets);
    await expect(window.locator('#multi .tabset > .vertical-grip')).toHaveCount(tabsets);

    for (const f of files) {
        await evalClj(window, `(do (doseq [ed (pool/by-path "${f}")] (object/raise ed :close)) :closed)`);
    }
    fs.rmSync(dir, { recursive: true, force: true });
});
