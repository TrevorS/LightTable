// The adapter: a CodeMirror 6 editor answering to CodeMirror 5's method names.
//
// `lt.objs.editor` calls 51 methods on its editor. Rewriting all of it against
// CodeMirror 6's API in one change is a rewrite nobody can review. Swapping the
// engine underneath is a change that can be checked a method at a time, which
// is what this does.
//
// So these assert the *contract*, not the implementation: given the same calls
// `lt.objs.editor` already makes, does the answer match what CodeMirror 5 says?

import { test, expect } from './fixtures';
import type { Page } from '@playwright/test';

/** Build one of each and run `body` against both, comparing the answers. */
async function bothEngines(window: Page, doc: string, body: string): Promise<[unknown, unknown]> {
    return await window.evaluate(([text, source]) => {
        const w = globalThis as any;
        const host = document.createElement('div');
        document.body.appendChild(host);

        const five = w.CodeMirror(host, { value: text });
        const six = w.ltCm6Editor.makeCm6Editor(host, { value: text });
        const run = new Function('ed', `return (${source});`);

        try {
            return [run(five), run(six)];
        } finally {
            host.remove();
        }
    }, [doc, body] as [string, string]) as [unknown, unknown];
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
        const [five, six] = await bothEngines(window, DOC, call);
        expect(six, `${call} — CodeMirror 5 said ${JSON.stringify(five)}`).toEqual(five);
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
        const [five, six] = await bothEngines(window, DOC, call);
        expect(six, `${call} — CodeMirror 5 said ${JSON.stringify(five)}`).toEqual(five);
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
        const [five, six] = await bothEngines(window, DOC, call);
        expect(six, `${call} — CodeMirror 5 said ${JSON.stringify(five)}`).toEqual(five);
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
        const [five, six] = await bothEngines(window, DOC, call);
        expect(six, `${call} — CodeMirror 5 said ${JSON.stringify(five)}`).toEqual(five);
    }
});

test('a method with no CodeMirror 6 answer yet says so by name', async ({ window }) => {
    // A gap that names itself beats one that returns undefined. This codebase
    // has paid for that lesson twice.
    const said = await window.evaluate(() => {
        const w = globalThis as any;
        const host = document.createElement('div');
        document.body.appendChild(host);
        const ed = w.ltCm6Editor.makeCm6Editor(host, { value: 'x\n' });
        try {
            ed.markText();
            return 'no error';
        } catch (e) {
            return String((e as Error).message);
        } finally {
            host.remove();
        }
    });
    expect(said).toContain('markText');
    expect(said).toContain('not implemented yet');
});
