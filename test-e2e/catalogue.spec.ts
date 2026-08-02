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

import { test, expect, evalClj } from './fixtures';



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
        // Twenty-five of the kit, plus the two that host foreign DOM rather
        // than describing it — the editor pane and `lt.ui.host/host`. The nine
        // views are functions rather than aliases and are listed separately.
        expect(registered).toBe(27);
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
        // Five of the nine are drawn by calling them, which is the same thing
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
        // And the connect panel showing the clients rather than the kinds.
        // Scoped to `.panel`, which is the view — section 02 draws three more
        // `connection-row`s as the alias on its own.
        await expect(window.locator('.kit .panel .connection')).toHaveCount(2);
        await expect(window.locator('.kit .connectors')).toHaveCount(0);
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

    test('and drawing all of it raises nothing', async ({ window, ltErrors }) => {
        const errors = await ltErrors();
        expect(errors.join('\n')).toBe('');
    });
});
