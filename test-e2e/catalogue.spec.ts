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

    test('a card told from the story registry says what the registry says', async ({ window }) => {
        // The unification. `chrome/status-dot` used to be written twice: a card
        // here with a sentence, a prop table and eight states, and — once
        // Storybook existed — a second list of states over there. Two answers
        // to "what states does this have?" is one too many, so the card is now
        // built from `lt.ui.story/registry` and the Storybook build reads the
        // same one.
        //
        // Asserted against the registry rather than against a list typed here,
        // because a list typed here would be the third answer.
        // Twice, because `evalClj` gives back the printed form of the value and
        // the value here is itself a JSON string.
        const wanted = JSON.parse(JSON.parse(await evalClj(window, `
            (let [spec (get @lt.ui.story/registry :lt.ui.chrome/status-dot)]
              (js/JSON.stringify
                (clj->js {:desc (:doc spec)
                          :props (mapv first (:props spec))
                          :states (mapv first (lt.ui.story/states :lt.ui.chrome/status-dot))
                          :usage (:usage spec)})))`))) as
            { desc: string; props: string[]; states: string[]; usage: string };

        // Nothing below can pass on an empty registry, which is the way a test
        // that compares two things read from the same place fails to mean
        // anything.
        expect(wanted.states.length).toBe(10);
        expect(wanted.props.length).toBe(3);

        const card = window.locator('.kit__card', { hasText: 'status-dot' }).first();
        expect(await card.locator('.kit__prop-name').allInnerTexts()).toEqual(wanted.props);
        expect(await card.locator('.kit__demo-row .status').allInnerTexts())
            .toEqual(wanted.states);
        expect((await card.locator('.kit__desc').innerText()).replace(/\s+/g, ' ').trim())
            .toBe(wanted.desc.replace(/\s+/g, ' ').trim());
        expect(await card.locator('.kit__usage').innerText()).toContain(wanted.usage);
    });

    test('draws a cell for every alias the registry holds', async ({ window }) => {
        // Not a number written here: the page asks lt.ui.kit/aliases for the
        // list and marks anything it did not draw, so this reads the marks
        // rather than counting cards and hoping.
        const undrawn = await window.locator('.kit__table-row--undrawn').allInnerTexts();
        expect(undrawn, 'aliases in the registry with no cell, or cells with no alias').toEqual([]);

        const registered = Number(await evalClj(window, '(count (lt.ui.kit/aliases))'));
        // Thirty-one of the kit — twenty-five from the document plus the six
        // `lt.ui.field` controls the settings screen added — plus the two that
        // host foreign DOM rather than describing it: the editor pane and
        // `lt.ui.host/host`. The eleven views are functions rather than aliases
        // and are listed separately.
        expect(registered).toBe(33);
        expect(await window.locator('.kit__table-row').count()).toBe(registered + 11);
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
        // Seven of the eleven are drawn by calling them, which is the same thing
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
