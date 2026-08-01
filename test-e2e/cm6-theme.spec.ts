// Thirty themes, none of them edited, applied to a CodeMirror 6 editor.
//
// Two claims. The colours come from a `HighlightStyle` that emits CodeMirror
// 5's class names, so every theme's token rules apply untouched. The chrome
// comes from mirroring each rule that names a CodeMirror 5 element onto the
// CodeMirror 6 one — and *that* is the part worth testing hardest, because a
// selector rewrite that goes wrong produces a selector matching nothing, which
// looks exactly like a theme that was always missing a rule.

import { test, expect } from './fixtures';
import type { Page } from '@playwright/test';

/**
 * Load a theme the way choosing one does, and wait for its rules to be there.
 *
 * `inject-theme` appends a `<link>`; the rules are not readable — and so not
 * mirrorable — until it has loaded, which is the race the bridge's own
 * MutationObserver exists to handle. Waiting for a rule proves both halves.
 */
async function useTheme(window: Page, theme: string): Promise<void> {
    await window.evaluate(([name]) =>
        (globalThis as any).lt.objs.style.inject_theme(name), [theme]);
    await window.waitForFunction(([name]) => {
        for (const sheet of Array.from(document.styleSheets)) {
            try {
                for (const rule of Array.from(sheet.cssRules)) {
                    if (rule instanceof CSSStyleRule
                        && rule.selectorText.includes(`cm-s-${name}`)
                        && rule.selectorText.includes('cm-gutters')) return true;
                }
            } catch { /* not ours to read */ }
        }
        return false;
    }, [theme], { timeout: 15_000 });
}

const mirror = (window: Page, selector: string) =>
    window.evaluate(([s]) => (globalThis as any).ltCm6Theme.mirrorSelector(s), [selector]);

test('a CodeMirror 5 selector is rewritten to the element CodeMirror 6 draws', async ({ window }) => {
    for (const [from, to] of [
        ['.CodeMirror', '.cm-editor'],
        ['.CodeMirror-cursor', '.cm-cursor'],
        ['.CodeMirror-selected', '.cm-selectionBackground'],
        ['.CodeMirror-gutters', '.cm-gutters'],
        ['.CodeMirror-linenumber', '.cm-lineNumbers .cm-gutterElement'],
        ['.CodeMirror-activeline-background', '.cm-activeLine'],
        ['.CodeMirror-matchingbracket', '.cm-matchingBracket'],
        // The scoped form every theme file actually uses.
        ['.cm-s-monokai .CodeMirror-cursor', '.cm-s-monokai .cm-cursor'],
        ['.cm-s-night div.CodeMirror-selected', '.cm-s-night div.cm-selectionBackground']
    ]) {
        expect(await mirror(window, from!)).toBe(to);
    }
});

test('and the longest name wins, which is the whole reason for the order', async ({ window }) => {
    // `.CodeMirror` is a prefix of every other name in the table. Rewriting it
    // first would turn `.CodeMirror-cursor` into `.cm-editor-cursor` — a
    // selector that matches nothing, in a rule that still looks right.
    expect(await mirror(window, '.CodeMirror-cursor')).not.toContain('cm-editor');
    expect(await mirror(window, '.CodeMirror-gutters')).not.toContain('cm-editor');

    // A name the table does not know is left alone rather than half-rewritten.
    // This one stays unknown on purpose: the autocomplete list is Light Table's
    // own element and carries this class on either engine, so a theme's rules
    // already reach it and a twin would match nothing.
    expect(await mirror(window, '.CodeMirror-hints')).toBe(null);

    // And a name that used to be in that position is not any more: search is
    // ported, so a theme's highlight reaches the matches CodeMirror 6 draws.
    expect(await mirror(window, '.CodeMirror-searching')).toBe('.cm-searchMatch');

    // And a selector with nothing to say about CodeMirror is not a twin at all.
    expect(await mirror(window, '.sidebar .row')).toBe(null);
});

test('a theme colours a CodeMirror 6 editor without being edited', async ({ window }) => {
    // End to end: pick a theme, open an editor on it, and read the colour off
    // a keyword. Nothing in deploy/core/css/themes knows CodeMirror 6 exists.
    await useTheme(window, 'monokai');

    const colour = await window.evaluate(() => {
        const w = globalThis as any;
        const host = document.createElement('div');
        document.body.appendChild(host);
        const ed = w.ltCm6Editor.makeCm6Editor(host, { value: 'const answer = 42;\n' });
        ed.setOption('mode', 'javascript');
        ed.setOption('theme', 'monokai');

        const keyword = host.querySelector('.cm-keyword');
        const scoped = host.querySelector('.cm-s-monokai');
        const out = {
            hasKeyword: !!keyword,
            scoped: !!scoped,
            // The wrapper carries CodeMirror 5's class too, which is what the
            // theme's `.CodeMirror { background: … }` rule needs.
            legacy: !!host.querySelector('.CodeMirror'),
            colour: keyword ? getComputedStyle(keyword).color : ''
        };
        host.remove();
        return out;
    });

    expect(colour.hasKeyword).toBe(true);
    expect(colour.scoped).toBe(true);
    expect(colour.legacy).toBe(true);
    // Monokai's keyword is #f92672. Asserted as a colour rather than merely
    // "something applied", because a class that exists and inherits the default
    // is the failure this is here to catch.
    expect(colour.colour).toBe('rgb(249, 38, 114)');
});

test('and the mirrored rules reach the elements CodeMirror 6 actually draws', async ({ window }) => {
    // The gutter is the one to check: it is drawn by CodeMirror 6 under its own
    // name, and every theme styles it under CodeMirror 5's.
    await useTheme(window, 'monokai');

    const out = await window.evaluate(() => {
        const w = globalThis as any;
        const host = document.createElement('div');
        document.body.appendChild(host);
        const ed = w.ltCm6Editor.makeCm6Editor(host, { value: 'a\nb\nc\n' });
        ed.setOption('theme', 'monokai');
        const gutters = host.querySelector('.cm-gutters') as HTMLElement | null;
        const result = {
            gutters: !!gutters,
            // Mirrored from `.cm-s-monokai .CodeMirror-gutters`.
            background: gutters ? getComputedStyle(gutters).backgroundColor : ''
        };
        host.remove();
        return result;
    });

    expect(out.gutters).toBe(true);
    expect(out.background).toBe('rgb(39, 40, 34)');
});
