// The component kit, opened.
//
// `test/lt/ui/kit_test.cljs` holds the registry to the document without a DOM,
// which is most of what matters and cannot see whether the page draws. This
// opens it in the real application and asks what only the rendered page can
// answer: that every alias the *running* registry holds has a cell here, that
// the views draw when called with a map, and that drawing all of it raises
// nothing.
//
// The running registry is the point of the first. Under node only the three
// kit namespaces are loaded; a real window also has `:lt.ui.pane/pane`, and an
// alias the catalogue does not account for is drawn marked. A marked row is a
// failure here rather than a note somebody reads later — which is how this
// found the pane in the first place.

import { test, expect } from './fixtures';
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

test.describe('the component kit', () => {
    test.beforeEach(async ({ window }) => {
        await evalClj(window, '(do (lt.objs.command/exec! :kit.catalogue) :opened)');
        await window.locator('.kit').first().waitFor();
    });

    test('draws a cell for every alias the registry holds', async ({ window }) => {
        // Not a number written here: the page asks lt.ui.kit/aliases for the
        // list and marks anything it did not draw, so this reads the marks
        // rather than counting cards and hoping.
        const undrawn = await window.locator('.kit__table-row--undrawn').allInnerTexts();
        expect(undrawn, 'aliases in the registry with no cell, or cells with no alias').toEqual([]);

        const registered = Number(await evalClj(window, '(count (lt.ui.kit/aliases))'));
        // Twenty-five of the kit, the hosted pane, and the nine views.
        expect(registered).toBe(26);
        expect(await window.locator('.kit__table-row').count()).toBe(registered + 9);
    });

    test('and every cell is the component, not a picture of it', async ({ window }) => {
        // A card draws by expanding the alias, so the states the document names
        // are in the DOM under the classes kit.css resolves. Spot-checking the
        // ones that carry a rule: the row tints whole, the dot is the status
        // vocabulary, the gutter marker replaces the number.
        await expect(window.locator('.kit .row--warning')).toHaveCount(1);
        await expect(window.locator('.kit .row--error')).toHaveCount(1);
        await expect(window.locator('.kit .dot--executing').first()).toBeVisible();
        await expect(window.locator('.kit .band--stale')).toHaveCount(2);
        // One, and it is in the gutter card rather than in a band: a diagnostic
        // here is drawn beside the code, so no band swaps its line number for a
        // dot. See lt.objs.editor.lsp.
        await expect(window.locator('.kit .band__gutter--marked')).toHaveCount(1);
        await expect(window.locator('.kit .band .band__gutter--marked')).toHaveCount(0);

        // A row tints entirely or not at all — the rule the kit enforces, read
        // off the rendered element rather than off the stylesheet.
        const border = await window.locator('.kit .row--error').first().evaluate(
            (el) => getComputedStyle(el).borderLeftWidth);
        expect(border).toBe('0px');
    });

    test('the views draw themselves from a map, in the page', async ({ window }) => {
        // Four of the nine are drawn by calling them, which is the same thing
        // the test file does — so a view that throws on a shape takes this down
        // rather than waiting to be noticed in a window.
        // Three from two tabs: a run that is not in the tab list is still a tab,
        // which is the rule the view exists to make true on screen.
        await expect(window.locator('.kit .titlebar .tab')).toHaveCount(3);
        // Two: quiet with something working, and one that failed. The bar has
        // states now that the bar at the bottom of this window is in.
        await expect(window.locator('.kit .statusbar')).toHaveCount(2);
        await expect(window.locator('.kit .statusbar__console .pill--error')).toHaveCount(1);
        await expect(window.locator('.kit .commandbar')).toHaveCount(1);
        // The tree, drawn from a map: two roots, one folder open with two
        // children in it, and the shut folder's contents nowhere.
        await expect(window.locator('.kit .wstree .row')).toHaveCount(4);
    });

    test('props and usage turn off for a clean visual pass', async ({ window }) => {
        const props = window.locator('.kit .kit__props').first();
        await expect(props).toBeVisible();

        await window.locator('.kit__toggles .action', { hasText: 'Props' }).click();
        await expect(props).toBeHidden();

        await window.locator('.kit__toggles .action', { hasText: 'Usage' }).click();
        await expect(window.locator('.kit .kit__usage').first()).toBeHidden();

        // And back, because a toggle that only goes one way is a switch nobody
        // trusts.
        await window.locator('.kit__toggles .action', { hasText: 'Props' }).click();
        await expect(props).toBeVisible();
    });

    test('and drawing all of it raises nothing', async ({ window }) => {
        const errors = await consoleErrors(window);
        expect(errors.join('\n')).toBe('');
    });
});
