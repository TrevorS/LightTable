// The settings screen, in the real application.
//
// `test/lt/ui/view_test.cljs` asks the two views what they draw for a map, which
// is most of what matters and cannot see the thing this file is for: whether the
// **projection** is right. Every assertion below rests on data nobody wrote for a
// test — the real behavior registry, the real keymap, the real provenance — so
// this is the half that fails if `lt.state.objects` stops agreeing with the
// object world.
//
// doc/hygiene.md ranked "the settings and keymap UI — nothing, at any layer" as
// the worst of the coverage gaps. The view tests closed most of it; this closes
// the part about the editor rather than about the functions.

import { test, expect, evalClj, evalData } from './fixtures';

test.describe('the settings screen', () => {
    test.beforeEach(async ({ window }) => {
        await evalClj(window, '(do (lt.objs.command/exec! :settings.screen) :opened)');
        await window.locator('.settings-screen').first().waitFor();
    });

    test('is a tab, and opening it twice does not make a second one', async ({ window }) => {
        // Unlike a file there is no second one to look at, and two would be two
        // views of one projection.
        await evalClj(window, '(do (lt.objs.command/exec! :settings.screen) :again)');
        await expect(window.locator('.settings-screen')).toHaveCount(1);
        expect(Number(await evalClj(window,
            '(count (lt.object/by-tag :settings.screen))'))).toBe(1);
    });

    test('every setting it draws is a behavior that says it is yours', async ({ window }) => {
        // The projection, checked against the registry it came from rather than
        // against a number typed here. `:type :user` is the marker, and if the
        // projection ever drifts from it this is what says so.
        const expected = Number(await evalClj(window, `
            (count (for [[_ beh] @lt.object/behaviors
                         :when (= :user (:type beh))
                         tag (keys @lt.object/tags)
                         :when (some (fn [e] (= (if (coll? e) (first e) e)
                                                (:name beh)))
                                     (get @lt.object/tags tag))]
                     1))`));
        const projected = Number(await evalClj(window,
            '(count (:entries (:settings @lt.state/app)))'));

        // Neither can be zero, or the comparison means nothing.
        expect(projected).toBeGreaterThan(0);
        expect(projected).toBe(expected);
        await expect(window.locator('.settings-screen .setting')).toHaveCount(projected);
    });

    test('a control per parameter, typed by what the behavior declared', async ({ window }) => {
        // Not a count: which *kinds* of control exist is the claim, and it has to
        // come from the real declarations. Every behavior with a :boolean
        // parameter should have produced a checkbox, and so on.
        const declared = await evalData<{ booleans: number; numbers: number; lists: number }>(
            window, `
            (let [entries (:entries (:settings @lt.state/app))
                  params (mapcat :params entries)
                  n (fn [t] (count (filter #(= t (:type %)) params)))]
              {:booleans (n :boolean) :numbers (n :number) :lists (n :list)})`);

        expect(declared.booleans + declared.numbers + declared.lists).toBeGreaterThan(0);
        await expect(window.locator('.settings-screen .field__toggle[type=checkbox]'))
            .toHaveCount(declared.booleans + await parameterlessRows(window));
        await expect(window.locator('.settings-screen .field__input--number'))
            .toHaveCount(declared.numbers);
        await expect(window.locator('.settings-screen .field__select'))
            .toHaveCount(declared.lists);
    });

    // A behavior with no parameters is a switch, so its row leads with a toggle
    // and contributes a checkbox that is not a parameter's.
    async function parameterlessRows(window: import('@playwright/test').Page): Promise<number> {
        return Number(await evalClj(window, `
            (count (filter (comp empty? :params)
                           (:entries (:settings @lt.state/app))))`));
    }

    test('the filter narrows what is drawn', async ({ window }) => {
        const all = await window.locator('.settings-screen .setting').count();
        await window.locator('.settings-screen__filter').fill('zzzznothing');
        await expect(window.locator('.settings-screen .setting')).toHaveCount(0);
        await expect(window.locator('.settings-screen .empty')).toHaveCount(1);

        await window.locator('.settings-screen__filter').fill('');
        await expect(window.locator('.settings-screen .setting')).toHaveCount(all);
    });

    test('the keys half draws the keymap that would actually fire', async ({ window }) => {
        await window.locator('.settings-screen__bar .action', { hasText: 'keys' }).click();

        // `lt.objs.keyboard/key-map` is the merge of the contexts you are in,
        // which is what a keystroke will really do — not every context's binding.
        const live = Number(await evalClj(window, '(count @lt.objs.keyboard/key-map)'));
        expect(live).toBeGreaterThan(0);
        expect(Number(await evalClj(window, '(count (:keymap @lt.state/app))'))).toBe(live);
        await expect(window.locator('.settings-screen .keys .row')).toHaveCount(live);
    });

    test('a binding reads as its command rather than as an action vector', async ({ window }) => {
        await window.locator('.settings-screen__bar .action', { hasText: 'keys' }).click();
        const first = window.locator('.settings-screen .keys .row').first();
        // The old eight-line view printed `[[:cmd/exec :save]]`, which is what it
        // is and not what anyone is looking for.
        await expect(first.locator('.keys__actions')).not.toContainText('cmd/exec');
        expect((await first.locator('.keys__actions').innerText()).trim()).not.toBe('');
    });

    test('clicking a binding takes the keyboard, and escaping gives it back', async ({ window }) => {
        await window.locator('.settings-screen__bar .action', { hasText: 'keys' }).click();
        await window.locator('.settings-screen .keys .keys__kbd').first().click();

        await expect(window.locator('.settings-screen .keys__capture')).toHaveCount(1);
        // Without this, pressing the shortcut you want to assign runs whatever it
        // is bound to — so the capture is interrupted by the thing being rebound.
        expect(await evalClj(window, 'lt.objs.keyboard/capturing?')).toBe('false');

        // Blur is the way out, and it is what the input's own handler does.
        await window.locator('.settings-screen__filter').click();
        await expect(window.locator('.settings-screen .keys__capture')).toHaveCount(0);
        expect(await evalClj(window, 'lt.objs.keyboard/capturing?')).toBe('true');
    });

    test('and drawing all of it raises nothing', async ({ window, ltErrors }) => {
        await window.locator('.settings-screen__bar .action', { hasText: 'keys' }).click();
        await window.locator('.settings-screen__bar .action', { hasText: 'settings' }).click();
        expect((await ltErrors()).join('\n')).toBe('');
    });

    test('a projection sync does not erase what you typed', async ({ window }) => {
        // The filter and which half you are looking at are the state's own, not
        // the projection's, and `sync!` splices `:settings` rather than merging
        // over it for exactly this reason — every tab that opens runs this.
        //
        // Calling `sync!` directly rather than doing something that triggers it,
        // because it *is* the operation under test. This is the bug the command
        // bar had before the same distinction was made for it.
        await window.locator('.settings-screen__bar .action', { hasText: 'keys' }).click();
        await window.locator('.settings-screen__filter').fill('sav');

        await evalClj(window, '(do (lt.state.objects/sync!) :synced)');

        await expect(window.locator('.settings-screen .keys')).toHaveCount(1);
        expect(await evalClj(window, '(:query (:settings @lt.state/app))')).toBe('"sav"');
        // And the entries really were refreshed, so the splice is keeping the
        // screen's state rather than skipping the sync.
        expect(Number(await evalClj(window,
            '(count (:entries (:settings @lt.state/app)))'))).toBeGreaterThan(0);
    });
});
