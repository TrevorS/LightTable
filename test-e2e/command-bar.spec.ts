// The command bar, which is mostly other objects' DOM.
//
// Two of them: the fuzzy selector, made once and kept, and the options slot —
// whichever input the active command brought with it, which changes as you pick
// different commands. That is the shape doc/rendering.md called the one that
// cannot be swapped a component at a time, and `lt.ui.host` is what let it move
// without the things it composes having to move first.
//
// So this is also the only real exercise of that alias: placing a node,
// swapping it for another, and putting it back.

import { test, expect, evalClj, evalData } from './fixtures';

const show = async (window: import('@playwright/test').Page) => {
    await evalClj(window, `
        (do (object/raise lt.objs.sidebar/rightbar :toggle lt.objs.sidebar.command/sidebar-command
                          {:force? true})
            (object/merge! lt.objs.sidebar.command/sidebar-command {:active nil})
            :shown)`);
    await expect(window.locator('.command')).toHaveCount(1);
};

test('the selector is hosted, and it is the object that owns it', async ({ window }) => {
    await show(window);

    // The node in the slot is the selector object's own `->content` — not a
    // copy of it, and not something the view built.
    expect(await evalData(window, `
        (identical? (.-firstChild ^js (lt.util.dom/$ ".command .selector"))
                    (object/->content (:selector @lt.objs.sidebar.command/sidebar-command)))`))
        .toBe(true);

    // And it is a working filter list rather than an empty box.
    await expect(window.locator('.command .selector .filter-list input.search')).toHaveCount(1);
});

test('the options slot takes the active command\'s own input, and gives it back', async ({ window }) => {
    await show(window);

    // Nothing active: the slot is empty and the header says nothing.
    const slot = window.locator('.command .options > div');
    await expect.poll(async () => await evalData(window,
        '(.-childElementCount ^js (lt.util.dom/$ ".command .options > div"))')).toBe(0);
    expect(await window.textContent('.command .options h2')).toBe('');

    // A command with options, activated the way `::select-command` does. Two of
    // them, so the slot has to swap one node for another rather than only fill.
    await evalClj(window, `
        (do (def probe-a (lt.objs.sidebar.command/options-input {:placeholder "first"}))
            (def probe-b (lt.objs.sidebar.command/options-input {:placeholder "second"}))
            :made)`);

    for (const [which, placeholder] of [['probe-a', 'first'], ['probe-b', 'second']] as const) {
        await evalClj(window, `
            (do (object/merge! lt.objs.sidebar.command/sidebar-command
                               {:active {:desc "a probe command" :options ${which}}})
                :active)`);

        await expect.poll(async () => await slot.locator('input.option').getAttribute('placeholder'))
            .toBe(placeholder);
        // The node itself, again — hosting places what the object owns.
        expect(await evalData(window, `
            (identical? (.-firstChild ^js (lt.util.dom/$ ".command .options > div"))
                        (object/->content ${which}))`), which).toBe(true);
        // Exactly one: a slot that appended without removing would stack them.
        expect(await evalData(window,
            '(.-childElementCount ^js (lt.util.dom/$ ".command .options > div"))')).toBe(1);
    }

    // The header names the active command and cancels it, which empties the
    // slot again without destroying what was in it.
    expect(await window.textContent('.command .options h2')).toBe('a probe command');
    await window.locator('.command .options h2').click();
    await expect.poll(async () => await evalData(window,
        '(.-childElementCount ^js (lt.util.dom/$ ".command .options > div"))')).toBe(0);
    expect(await evalData(window, '(some? (object/->content probe-b))'),
           'the hosted object should survive being unhosted').toBe(true);
});

test('and the options input draws its placeholder and value from the object', async ({ window }) => {
    // This object's content *is* one element, so there is nothing to render
    // inside it: the placeholder and value that used to be `bound` are the
    // root's own attributes, through `lt.ui/node`'s `attrs`.
    await evalClj(window, `
        (do (def attr-probe (lt.objs.sidebar.command/options-input {:placeholder "line number"}))
            (js/document.body.appendChild (object/->content attr-probe))
            :mounted)`);

    const input = window.locator('body > input.option');
    await expect(input).toHaveCount(1);
    expect(await input.getAttribute('placeholder')).toBe('line number');
    expect(await input.inputValue()).toBe('');

    await evalClj(window, '(do (object/merge! attr-probe {:placeholder "changed" :value "42"}) :set)');
    await expect.poll(async () => await input.getAttribute('placeholder')).toBe('changed');
    expect(await input.inputValue()).toBe('42');

    // Typing then re-rendering with the same value must not rewrite the field:
    // setting `.value` moves the caret to the end, so a view that wrote it
    // every time would make the box unusable to type in anywhere but the end.
    await input.fill('4200');
    await evalClj(window, '(do (object/merge! attr-probe {:placeholder "again"}) :redrawn)');
    await expect.poll(async () => await input.getAttribute('placeholder')).toBe('again');
    expect(await input.inputValue(), 'a redraw should not have reset what was typed').toBe('4200');

    await evalClj(window, '(do (.remove (object/->content attr-probe)) (object/destroy! attr-probe) :gone)');
});
