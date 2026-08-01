// The options an editor is configured with, and whether setting one does it.
//
// Light Table configures editors through behaviors, so which options are on is
// a user's setting. The failure this guards against is the quiet one: an option
// recorded and never applied answers correctly from `getOption` and changes
// nothing on screen, which reads as "that setting is broken" long after the
// change that broke it.
//
// So each assertion is about the editor, not about the option map.

import { test, expect } from './fixtures';
import type { Page } from '@playwright/test';

/** Build an editor, set options on it, and read something back from the DOM. */
async function withEditor<T>(window: Page, body: string, doc = 'alpha\nbeta\ngamma\n'): Promise<T> {
    return await window.evaluate(([text, source]) => {
        const w = globalThis as any;
        const host = document.createElement('div');
        host.style.height = '200px';
        document.body.appendChild(host);
        const ed = w.ltCm6Editor.makeCm6Editor(host, { value: text });
        try {
            return new Function('ed', 'host', `return (${source});`)(ed, host);
        } finally {
            host.remove();
        }
    }, [doc, body] as [string, string]) as T;
}

test('an option that CodeMirror 6 can express changes the editor', async ({ window }) => {
    // Line numbers are a gutter, so turning them off removes one.
    expect(await withEditor(window, `(function () {
        const before = host.querySelectorAll('.cm-lineNumbers').length;
        ed.setOption('lineNumbers', false);
        const after = host.querySelectorAll('.cm-lineNumbers').length;
        ed.setOption('lineNumbers', true);
        return [before, after, host.querySelectorAll('.cm-lineNumbers').length];
    })()`)).toEqual([1, 0, 1]);

    // Wrapping is a style on the content, and it is off until asked for.
    expect(await withEditor(window, `(function () {
        const style = () => getComputedStyle(host.querySelector('.cm-content')).whiteSpace;
        const before = style();
        ed.setOption('lineWrapping', true);
        return [before, style()];
    })()`)).toEqual(['pre', 'break-spaces']);   // not pre-wrap: CodeMirror 6
    // wraps with break-spaces so a run of trailing spaces still breaks.

    // The active line is what themes colour as .CodeMirror-activeline-background.
    expect(await withEditor(window, `(function () {
        ed.setOption('styleActiveLine', true);
        ed.setCursor({line: 1, ch: 0});
        return host.querySelectorAll('.cm-activeLine').length > 0;
    })()`)).toBe(true);
});

test('and indentation is two options describing one thing', async ({ window }) => {
    // Setting either has to move both, or they disagree and the editor indents
    // with something nobody asked for.
    // With a language, because indentLine is *smart* indent — it computes what
    // the line should be — and a document with no language has no answer. That
    // divergence is named in cm6-editor.ts and this is the shape of it.
    expect(await withEditor(window, `(function () {
        ed.setOption('mode', 'javascript');
        ed.setOption('indentUnit', 4);
        ed.setOption('indentWithTabs', false);
        ed.setCursor({line: 1, ch: 0});
        ed.indentLine(1);
        const spaces = ed.getLine(1);
        ed.setOption('indentWithTabs', true);
        return [spaces, ed.getOption('indentUnit'), ed.getOption('indentWithTabs')];
    })()`, 'if (x) {\nbeta\n}\n')).toEqual(['    beta', 4, true]);
});

test('read-only refuses the keyboard, and only the keyboard', async ({ window }) => {
    // Which is what CodeMirror 5 means by it too: a read-only editor still
    // accepts replaceRange, because that is how Light Table itself writes into
    // one. Asserting on the document after a programmatic edit would have been
    // asserting the wrong thing, and would have passed for neither editor.
    await window.evaluate(() => {
        const w = globalThis as any;
        const host = document.createElement('div');
        host.id = 'ro-probe';
        document.body.appendChild(host);
        const ed = w.ltCm6Editor.makeCm6Editor(host, { value: 'locked\n' });
        ed.setOption('readOnly', true);
        ed.setCursor({ line: 0, ch: 0 });
        ed.focus();
        w.__roProbe = ed;
    });
    await window.keyboard.type('nope');
    expect(await window.evaluate(() => (globalThis as any).__roProbe.getValue())).toBe('locked\n');

    // And the program can still write, which is the half that matters to us.
    expect(await window.evaluate(() => {
        const ed = (globalThis as any).__roProbe;
        ed.replaceRange('x', { line: 0, ch: 0 });
        return ed.getValue();
    })).toBe('xlocked\n');

    await window.evaluate(() => {
        document.getElementById('ro-probe')?.remove();
        delete (globalThis as any).__roProbe;
    });
});

test('brackets match and close, which are two different extensions', async ({ window }) => {
    expect(await withEditor(window, `(function () {
        ed.setOption('matchBrackets', true);
        ed.setCursor({line: 0, ch: 1});
        return host.querySelectorAll('.cm-matchingBracket').length;
    })()`, '(alpha)\n')).toBeGreaterThan(0);

    // Auto-close is a keymap, so it is asserted through the key rather than by
    // calling the command — that is the path a person takes.
    await window.evaluate(() => {
        const w = globalThis as any;
        const host = document.createElement('div');
        host.id = 'close-probe';
        document.body.appendChild(host);
        const ed = w.ltCm6Editor.makeCm6Editor(host, { value: '' });
        ed.setOption('autoCloseBrackets', true);
        ed.focus();
        w.__closeProbe = ed;
    });
    await window.keyboard.type('(');
    expect(await window.evaluate(() => (globalThis as any).__closeProbe.getValue())).toBe('()');
    await window.evaluate(() => {
        document.getElementById('close-probe')?.remove();
        delete (globalThis as any).__closeProbe;
    });
});

test('an option with no equivalent is named rather than silently ignored', async ({ window }) => {
    // The point of the list. `rulers` is the one a person would miss, and this
    // is how they find out rather than by staring at a column that has no line
    // in it.
    const inert = await withEditor<string[]>(window, `(function () {
        ed.setOption('rulers', [{column: 80}]);
        ed.setOption('undoDepth', 10000);
        ed.setOption('lineNumbers', true);
        return ed.inertOptions();
    })()`);
    expect(inert).toEqual(['rulers', 'undoDepth']);

    // And it still answers what it was told, the way CodeMirror 5 does.
    expect(await withEditor(window, `(function () {
        ed.setOption('undoDepth', 42);
        return ed.getOption('undoDepth');
    })()`)).toBe(42);
});
