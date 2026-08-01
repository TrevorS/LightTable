// The band field, which is the reason the port happened.
//
// One claim, checked rather than argued. A band used to be a line widget you
// added and had to remember to remove, which is why `lt.ui.bands` grew a table
// of what was drawn and an orphan sweep. The set of widgets is derived from
// state now — so the bookkeeping is not simpler, it is absent.
//
// These assert the field's own behaviour: what happens to a widget when the
// same key is declared with new content, when a line is past the end, when a
// key moves. That it is reached correctly from `lt.state` is `bands.spec.ts`.

import { test, expect } from './fixtures';
import type { Page } from '@playwright/test';

/**
 * Build an editor in a detached root and hand back a handle.
 *
 * The same factory the application uses, and not a second one written for the
 * test: a band field configured differently from the real editor's would let
 * this pass while the editor did something else.
 */
async function makeEditor(window: Page, doc: string): Promise<void> {
    await window.evaluate(([text]) => {
        const w = globalThis as any;
        const host = document.createElement('div');
        host.className = 'cm6-host';
        document.body.appendChild(host);
        w.__cm6host = host;
        w.__cm6 = w.ltCm6Editor.makeCm6Editor(host, { value: text });
    }, [doc]);
}

async function bands(window: Page, specs: { line: number, key: string, text: string }[]): Promise<void> {
    await window.evaluate(([given]) => {
        const w = globalThis as any;
        w.__cm6.setBands((given as any[]).map((b) => ({
            line: b.line,
            key: b.key,
            // What the band is showing. Equal content is a band that needs no
            // work at all — in the editor this is the hiccup, compared with `=`.
            content: b.text,
            // Whoever owns the node fills it. Here that is a string; in the
            // editor it would be replicant.dom/render, which is the point —
            // CodeMirror never looks inside.
            mount: (node: HTMLElement) => { node.textContent = b.text; }
        })));
    }, [specs] as any);
}

const drawn = (window: Page) =>
    window.evaluate(() => (globalThis as any).__cm6.bandKeys() as string[]);

test.afterEach(async ({ window }) => {
    await window.evaluate(() => {
        const w = globalThis as any;
        w.__cm6?.view.destroy();
        w.__cm6host?.remove();
    });
});

test('a band is a widget derived from state, not a widget you remember', async ({ window }) => {
    await makeEditor(window, 'zero\none\ntwo\nthree\nfour\n');
    await expect(window.locator('.cm6-host .cm-editor')).toHaveCount(1);

    await bands(window, [{ line: 1, key: 'a', text: 'result: 42' },
                         { line: 3, key: 'b', text: 'watch: 7' }]);
    await expect(window.locator('.cm6-host .lt-band')).toHaveCount(2);
    expect(await drawn(window)).toEqual(['a', 'b']);
    expect(await window.locator('.cm6-host .lt-band').first().textContent()).toBe('result: 42');

    // The whole set is replaced by declaring the new one. Nothing removes the
    // band that left — there is no table it was in.
    await bands(window, [{ line: 1, key: 'a', text: 'result: 42' }]);
    await expect(window.locator('.cm6-host .lt-band')).toHaveCount(1);
    expect(await drawn(window)).toEqual(['a']);

    await bands(window, []);
    await expect(window.locator('.cm6-host .lt-band')).toHaveCount(0);
});

test('a band keeps its DOM when it keeps its key', async ({ window }) => {
    // The reason a key is the address: the node someone else rendered into
    // survives the set being declared again. Without it every state change
    // would throw away whatever Replicant had patched.
    await makeEditor(window, 'a\nb\nc\n');
    await bands(window, [{ line: 0, key: 'keep', text: 'first' }]);
    await expect(window.locator('.cm6-host .lt-band')).toHaveCount(1);

    const touched = () => window.locator('.cm6-host .lt-band').getAttribute('data-touched');
    const text = () => window.locator('.cm6-host .lt-band').textContent();

    await window.evaluate(() =>
        document.querySelector('.cm6-host .lt-band')!.setAttribute('data-touched', 'yes'));

    // Same key, same content: nothing happens at all. The mark survives, and so
    // does the text — the widget was never even compared unequal.
    await bands(window, [{ line: 0, key: 'keep', text: 'first' }]);
    expect(await touched()).toBe('yes');
    expect(await text()).toBe('first');

    // Same key, new content: the *same node*, refilled. This is the case the
    // whole arrangement is for — a result whose value changed is not a new
    // band, and rebuilding its node would discard the DOM Replicant owns.
    await bands(window, [{ line: 0, key: 'keep', text: 'second' }]);
    expect(await touched()).toBe('yes');
    expect(await text()).toBe('second');

    // A different key is a different widget, and is rebuilt.
    await bands(window, [{ line: 0, key: 'other', text: 'third' }]);
    expect(await touched()).toBe(null);
    expect(await text()).toBe('third');
});

test('and it follows its line when the document changes under it', async ({ window }) => {
    // The part with no CodeMirror 5 equivalent. A band maps forward through
    // every edit on its own, so typing above a result does not leave the
    // result behind — and nothing in Light Table has to notice the edit.
    await makeEditor(window, 'zero\none\ntwo\n');
    await bands(window, [{ line: 2, key: 'r', text: 'on two' }]);
    await expect(window.locator('.cm6-host .lt-band')).toHaveCount(1);

    // Which line the band sits under, read off the document.
    const lineOf = () => window.evaluate(() => {
        const band = document.querySelector('.cm6-host .lt-band');
        const lines = Array.from(document.querySelectorAll('.cm6-host .cm-line'));
        let at = -1;
        lines.forEach((line, i) => {
            if (band!.compareDocumentPosition(line) & Node.DOCUMENT_POSITION_PRECEDING) at = i;
        });
        return at;
    });
    expect(await lineOf()).toBe(2);

    // Two lines inserted at the top. Nothing was told about the band.
    await window.evaluate(() => {
        (globalThis as any).__cm6.replaceRange('new\nlines\n', { line: 0, ch: 0 });
    });

    expect(await lineOf()).toBe(4);
    expect(await window.locator('.cm6-host .lt-band').textContent()).toBe('on two');
});
