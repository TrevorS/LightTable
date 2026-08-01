// The adapter: a CodeMirror 6 editor answering to CodeMirror 5's method names.
//
// `lt.objs.editor` calls 55 methods on its editor. Rewriting all of it against
// CodeMirror 6's API in one change is a rewrite nobody can review. Swapping the
// engine underneath is a change that can be checked a method at a time, which
// is what this does.
//
// So these assert the *contract*, not the implementation: given the same calls
// `lt.objs.editor` already makes, is the answer the one CodeMirror 5 gave? It
// used to be asked by building one of each and comparing. There is only one
// now, so the answers are the ones that were captured from the other before it
// went — see cm5-answers.ts.

import { test, expect } from './fixtures';
import { cm5 } from './cm5-answers';
import type { Page } from '@playwright/test';

/** Run `body` against an editor holding `doc`. */
async function onCm6(window: Page, doc: string, body: string): Promise<unknown> {
    return await window.evaluate(([text, source]) => {
        const w = globalThis as any;
        const host = document.createElement('div');
        document.body.appendChild(host);
        const ed = w.ltCm6Editor.makeCm6Editor(host, { value: text });
        try {
            return new Function('ed', `return (${source});`)(ed);
        } finally {
            host.remove();
        }
    }, [doc, body] as [string, string]);
}

/** What CodeMirror 6 answers, against what CodeMirror 5 did. */
async function check(window: Page, doc: string, call: string): Promise<void> {
    const was = cm5('editor::' + call);
    expect(await onCm6(window, doc, call),
           `${call} — CodeMirror 5 said ${JSON.stringify(was)}`).toEqual(was);
}

const DOC = 'zero line\none line\ntwo line\nthree\n';

// Positions are compared as line and ch. CodeMirror 5 also puts a `sticky`
// field on them — which side of a bidi boundary the cursor leans — and nothing
// in this repository reads it, checked rather than assumed.
test('the document surface answers the same as CodeMirror 5', async ({ window }) => {
    for (const call of [
        'ed.getValue()',
        'ed.getLine(1)',
        'ed.lineCount()',
        'ed.firstLine()',
        'ed.lastLine()',
        'ed.getRange({line:0,ch:5}, {line:1,ch:3})',
        'ed.indexFromPos({line:2,ch:4})',
        '(function(p){return p.line+":"+p.ch;})(ed.posFromIndex(14))',
        'ed.getLineNumber(ed.getLineHandle(2))'
    ]) {
        await check(window, DOC, call);
    }
});

test('and so do the cursor and the selection', async ({ window }) => {
    for (const call of [
        '(ed.setCursor({line:2,ch:3}), (function(p){return p.line+":"+p.ch;})(ed.getCursor()))',
        '(ed.setCursor({line:1,ch:0}), ed.somethingSelected())',
        '(ed.setSelection({line:0,ch:0},{line:1,ch:3}), ed.getSelection())',
        '(ed.setSelection({line:0,ch:0},{line:1,ch:3}), ed.somethingSelected())',
        '(ed.setSelection({line:0,ch:0},{line:0,ch:4}), (function(p){return p.line+":"+p.ch;})(ed.getCursor("start")))',
        '(ed.setSelection({line:0,ch:0},{line:0,ch:4}), (function(p){return p.line+":"+p.ch;})(ed.getCursor("end")))',
        '(ed.setSelection({line:0,ch:0},{line:0,ch:4}), ed.replaceSelection("ZERO"), ed.getLine(0))'
    ]) {
        await check(window, DOC, call);
    }
});

test('and editing the document', async ({ window }) => {
    for (const call of [
        '(ed.replaceRange("X", {line:0,ch:0}), ed.getLine(0))',
        '(ed.replaceRange("", {line:0,ch:0}, {line:0,ch:5}), ed.getLine(0))',
        '(ed.replaceRange("new\\n", {line:1,ch:0}), ed.lineCount())',
        '(ed.setValue("only\\n"), ed.getValue())',
        '(ed.replaceRange("Q", {line:0,ch:0}), ed.undo(), ed.getLine(0))',
        '(ed.replaceRange("Q", {line:0,ch:0}), ed.undo(), ed.redo(), ed.getLine(0))',
        'ed.operation(function () { return 42; })'
    ]) {
        await check(window, DOC, call);
    }
});

test('a position past the end of a line is clamped, not thrown', async ({ window }) => {
    // Light Table asks for positions computed against text that has since
    // changed — `lt.ui.bands` does it every render. CodeMirror 5 clamps, so
    // this has to as well or a stale address becomes a crash.
    for (const call of [
        '(function(p){return p.line+":"+p.ch;})(ed.getCursor())',
        '(ed.setCursor({line:99,ch:99}), (function(p){return p.line+":"+p.ch;})(ed.getCursor()))',
        'ed.getLine(99)',
        'ed.getRange({line:0,ch:0},{line:99,ch:99})'
    ]) {
        await check(window, DOC, call);
    }
});

test('marks and line classes follow their text through an edit', async ({ window }) => {
    // The same move as the bands, and the reason to make it: CodeMirror 5 hands
    // back a handle you must remember and clear. Here the set is state the view
    // reconciles, so a mark maps forward through every change on its own.
    for (const call of [
        '(ed.markText({line:0,ch:0},{line:0,ch:4},{className:"m"}), ed.findMarksAt({line:0,ch:2}).length)',
        '(ed.markText({line:0,ch:0},{line:0,ch:4},{className:"m"}), ed.findMarksAt({line:2,ch:0}).length)',
        '(function(){var m=ed.markText({line:0,ch:0},{line:0,ch:4},{className:"m"}); m.clear(); return ed.findMarksAt({line:0,ch:2}).length;})()',
        '(function(){var m=ed.markText({line:1,ch:0},{line:1,ch:3},{className:"m"}); ed.replaceRange("two\\nlines\\n",{line:0,ch:0}); var f=m.find(); return f.from.line+":"+f.from.ch;})()'
    ]) {
        await check(window, DOC, call);
    }

    // And a line class reaches the DOM, which is what the caller wanted.
    const classed = await window.evaluate(() => {
        const w = globalThis as any;
        const host = document.createElement('div');
        document.body.appendChild(host);
        const ed = w.ltCm6Editor.makeCm6Editor(host, { value: 'a\nb\nc\n' });
        ed.addLineClass(1, 'wrap', 'lit-up');
        const found = host.querySelectorAll('.lit-up').length;
        ed.removeLineClass(1, 'wrap', 'lit-up');
        const after = host.querySelectorAll('.lit-up').length;
        host.remove();
        return [found, after];
    });
    expect(classed).toEqual([1, 0]);
});

test('the rest of the surface answers rather than throwing', async ({ window }) => {
    // Nineteen methods used to throw by name. These are the ones whose answer
    // can be compared directly; the others are checked above or are about
    // geometry, which two engines were never obliged to agree about to the
    // pixel.
    for (const call of [
        '(ed.indentLine(1), ed.getLine(1).length > "one line".length)',
        '(ed.setSelection({line:0,ch:0},{line:1,ch:0}), ed.indentSelection(), ed.getValue().length > 30)',
        'typeof ed.charCoords({line:0,ch:0}).left',
        '(ed.scrollTo(0, 0), ed.getValue().length)'
    ]) {
        await check(window, DOC, call);
    }

    // `getTokenAt` in a document with no language is where they part, and it
    // used to be listed above as though they agreed. CodeMirror 5's null mode
    // tokenizes: it hands back the whole line as one token with a null type.
    // CodeMirror 6 has no tokenizer to ask, only an empty syntax tree, and
    // taking that at face value produced a token of the empty string at a
    // negative offset — which read as "the thing under your cursor is called
    // nothing", and stopped Toggle documentation from drawing anything at all.
    // Null is the honest answer, and the callers that check were already right.
    expect(cm5('editor::ed.getTokenAt({line:0,ch:1}) === null')).toBe(false);
    expect(await onCm6(window, DOC, 'ed.getTokenAt({line:0,ch:1}) === null')).toBe(true);
});

test('every method lt.objs.editor calls exists on the adapter', async ({ window }) => {
    // Every method any ClojureScript in this repository or in deploy/plugins
    // calls on the editor, extracted rather than remembered, plus the one
    // cm-hint.ts calls. A method that is missing is a TypeError in whichever
    // feature happens to reach it first, which is the worst way to find out.
    //
    // Three left with CodeMirror 5: `getDoc` and `swapDoc`, which were about a
    // shared buffer object that no longer exists, and `setExtending`, which set
    // a mode the new engine does not have and had been recording the flag and
    // doing nothing with it.
    const missing = await window.evaluate(() => {
        const w = globalThis as any;
        const host = document.createElement('div');
        document.body.appendChild(host);
        const ed = w.ltCm6Editor.makeCm6Editor(host, { value: 'x\n' });
        const wanted = ['addLineClass', 'addLineWidget', 'blockComment', 'changeGeneration',
            'charCoords', 'clearHistory', 'findMarksAt', 'firstLine', 'focus', 'foldCode',
            'getCursor', 'getHistory', 'getLine', 'getLineHandle', 'getLineNumber',
            'getMode', 'getOption', 'getRange', 'getScrollerElement', 'getSelection',
            'getTokenAt', 'getTokenTypeAt', 'indentLine', 'indentSelection', 'indexFromPos',
            'isClean', 'lastLine', 'lineComment', 'lineCount', 'markText', 'off', 'on',
            'operation', 'redo', 'refresh', 'removeLineClass', 'removeLineWidget',
            'replaceRange', 'replaceSelection', 'scrollTo', 'setBookmark', 'setCursor',
            'setHistory', 'setOption', 'setSelection', 'somethingSelected',
            'uncomment', 'undo',
            // Found by flipping the default and watching what threw.
            'getScrollInfo', 'getValue', 'setValue',
            // Called by cm-hint.ts rather than by any ClojureScript: the hint
            // list positions itself against the cursor.
            'cursorCoords'];
        const gaps = wanted.filter((m) => typeof ed[m] !== 'function');
        host.remove();
        return gaps;
    });
    expect(missing).toEqual([]);
});

