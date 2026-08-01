// The CodeMirror 6 spike: what a port would actually buy.
//
// One claim, checked rather than argued. On CodeMirror 5 a band is a line
// widget you add and must remember to remove, which is why `lt.ui.bands` grew
// a table of what is drawn and an orphan sweep. On CodeMirror 6 the set of
// widgets is derived from state — so the bookkeeping is not simpler, it is
// absent.
//
// Nothing in the editor uses this yet. It exists so the decision rests on
// something observed.

import { test, expect } from './fixtures';
import type { Page } from '@playwright/test';

/** Build a CM6 editor in a detached root and hand back a handle. */
async function makeEditor(window: Page, doc: string): Promise<void> {
    await window.evaluate(([text]) => {
        const w = globalThis as any;
        const host = document.createElement('div');
        host.className = 'cm6-host';
        document.body.appendChild(host);
        w.__cm6host = host;
        w.__cm6 = w.ltCm6.makeEditor({ doc: text, parent: host });
    }, [doc]);
}

async function bands(window: Page, specs: { line: number, key: string, text: string }[]): Promise<void> {
    await window.evaluate(([given]) => {
        const w = globalThis as any;
        w.ltCm6.showBands(w.__cm6, (given as any[]).map((b) => ({
            line: b.line,
            key: b.key,
            // Whoever owns the node fills it. Here that is a string; in the
            // editor it would be replicant.dom/render, which is the point —
            // CodeMirror never looks inside.
            mount: (node: HTMLElement) => { node.textContent = b.text; }
        })));
    }, [specs] as any);
}

const drawn = (window: Page) =>
    window.evaluate(() => (globalThis as any).ltCm6.drawnBands((globalThis as any).__cm6) as string[]);

test.afterEach(async ({ window }) => {
    await window.evaluate(() => {
        const w = globalThis as any;
        w.__cm6?.destroy();
        w.__cm6host?.remove();
    });
});

test('a band is a widget derived from state, not a widget you remember', async ({ window }) => {
    await makeEditor(window, 'zero\none\ntwo\nthree\nfour\n');
    await expect(window.locator('.cm6-host .cm-editor')).toHaveCount(1);

    await bands(window, [{ line: 1, key: 'a', text: 'result: 42' },
                         { line: 3, key: 'b', text: 'watch: 7' }]);
    await expect(window.locator('.cm6-host .cm6-band')).toHaveCount(2);
    expect(await drawn(window)).toEqual(['a', 'b']);
    expect(await window.locator('.cm6-host .cm6-band').first().textContent()).toBe('result: 42');

    // The whole set is replaced by declaring the new one. Nothing removes the
    // band that left — there is no table it was in.
    await bands(window, [{ line: 1, key: 'a', text: 'result: 42' }]);
    await expect(window.locator('.cm6-host .cm6-band')).toHaveCount(1);
    expect(await drawn(window)).toEqual(['a']);

    await bands(window, []);
    await expect(window.locator('.cm6-host .cm6-band')).toHaveCount(0);
});

test('a band keeps its DOM when it keeps its key', async ({ window }) => {
    // What `eq` buys, and the reason a key is the address: the node someone
    // else rendered into survives the set being declared again. Without it
    // every state change would throw away whatever Replicant had patched.
    await makeEditor(window, 'a\nb\nc\n');
    await bands(window, [{ line: 0, key: 'keep', text: 'first' }]);
    await expect(window.locator('.cm6-host .cm6-band')).toHaveCount(1);

    await window.evaluate(() =>
        document.querySelector('.cm6-host .cm6-band')!.setAttribute('data-touched', 'yes'));

    // Declared again, with the same key and a different mount.
    await bands(window, [{ line: 0, key: 'keep', text: 'second' }]);
    expect(await window.locator('.cm6-host .cm6-band').getAttribute('data-touched')).toBe('yes');
    expect(await window.locator('.cm6-host .cm6-band').textContent()).toBe('first');

    // A different key is a different widget, and is rebuilt.
    await bands(window, [{ line: 0, key: 'other', text: 'third' }]);
    expect(await window.locator('.cm6-host .cm6-band').getAttribute('data-touched')).toBe(null);
    expect(await window.locator('.cm6-host .cm6-band').textContent()).toBe('third');
});

test('and it follows its line when the document changes under it', async ({ window }) => {
    // The part with no CodeMirror 5 equivalent. A band maps forward through
    // every edit on its own, so typing above a result does not leave the
    // result behind — and nothing in Light Table has to notice the edit.
    await makeEditor(window, 'zero\none\ntwo\n');
    await bands(window, [{ line: 2, key: 'r', text: 'on two' }]);
    await expect(window.locator('.cm6-host .cm6-band')).toHaveCount(1);

    // Which line the band sits under, read off the document.
    const lineOf = () => window.evaluate(() => {
        const band = document.querySelector('.cm6-host .cm6-band');
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
        (globalThis as any).__cm6.dispatch({ changes: { from: 0, insert: 'new\nlines\n' } });
    });

    expect(await lineOf()).toBe(4);
    expect(await window.locator('.cm6-host .cm6-band').textContent()).toBe('on two');
});
