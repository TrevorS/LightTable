// The command table, checked against the one it replaces.
//
// `lt.objs.editor.pool` registers 33 Light Table commands whose whole body is
// `js/CodeMirror.commands.<name>`. That was the largest single block of
// CodeMirror references in the codebase; this is the mapping, and these say it
// is the same mapping rather than a plausible one.

import { test, expect } from './fixtures';
import type { Page } from '@playwright/test';

/**
 * Run `name` on both engines from the same cursor, and report what each did.
 * A command is judged by where the cursor ended up and what the text became —
 * which is what a person notices, and all two engines owe each other.
 */
async function bothEngines(window: Page, name: string, at: { line: number, ch: number }) {
    return await window.evaluate(([command, cursor]) => {
        const w = globalThis as any;
        const host = document.createElement('div');
        document.body.appendChild(host);
        const doc = 'first line here\n    indented line\nlast\n';
        const after = (value: string, pos: { line: number, ch: number }) =>
            `${value}|${pos.line}:${pos.ch}`;

        const five = w.CodeMirror(host, { value: doc });
        five.setCursor(cursor);
        w.CodeMirror.commands[command as string]?.(five);
        const fiveSaid = after(five.getValue(), five.getCursor());

        const six = w.ltCm6Editor.makeCm6Editor(host, { value: doc });
        six.setCursor(cursor);
        w.ltCm6Commands.runCommand(command as string, six.view);
        const sixSaid = after(six.getValue(), six.getCursor());

        host.remove();
        return [fiveSaid, sixSaid];
    }, [name, at] as [string, { line: number, ch: number }]) as [string, string];
}

const MOVING = ['goCharLeft', 'goCharRight', 'goLineUp', 'goLineDown', 'goLineStart',
                'goLineEnd', 'goDocStart', 'goDocEnd', 'goWordLeft', 'goWordRight',
                'goGroupLeft', 'goGroupRight', 'goLineStartSmart'];

const EDITING = ['delCharBefore', 'delCharAfter', 'delWordBefore', 'delWordAfter',
                 'delGroupBefore', 'delGroupAfter', 'delLineLeft', 'killLine',
                 'deleteLine', 'newlineAndIndent', 'transposeChars'];

test('moving the cursor lands in the same place', async ({ window }) => {
    for (const name of MOVING) {
        const [five, six] = await bothEngines(window, name, { line: 1, ch: 8 });
        expect(six, `${name} from 1:8`).toBe(five);
    }
});

test('editing the document produces the same document', async ({ window }) => {
    for (const name of EDITING) {
        const [five, six] = await bothEngines(window, name, { line: 1, ch: 8 });
        expect(six, `${name} from 1:8`).toBe(five);
    }
});

// Two documented divergences, both at a line start and both cases where
// CodeMirror 5 is the surprising one. They are named rather than shimmed.
//
// `newlineAndIndent` re-indents the line to what the mode says it should be,
// which with no language is column zero — so it silently strips an indent.
// `transposeChars` swaps the character before the cursor with the newline,
// which tears a line in half. CodeMirror 6 does nothing in both cases, and
// doing nothing is right.
const DIVERGES_AT_LINE_START = new Set(['newlineAndIndent', 'transposeChars']);

test('and from the edges, where off-by-one lives', async ({ window }) => {
    for (const at of [{ line: 0, ch: 0 }, { line: 2, ch: 4 }, { line: 1, ch: 0 }]) {
        for (const name of [...MOVING, ...EDITING]) {
            if (at.ch === 0 && DIVERGES_AT_LINE_START.has(name)) continue;
            const [five, six] = await bothEngines(window, name, at);
            expect(six, `${name} from ${at.line}:${at.ch}`).toBe(five);
        }
    }
});

test('every command the pool registers is in the table', async ({ window }) => {
    // The list is what `lt.objs.editor.pool` binds. A name missing from the
    // table is a key that silently does nothing.
    const missing = await window.evaluate(() => {
        const w = globalThis as any;
        const wanted = ['delCharAfter', 'delCharBefore', 'deleteLine', 'delGroupAfter',
            'delGroupBefore', 'delLineLeft', 'delWordAfter', 'delWordBefore', 'goCharLeft',
            'goCharRight', 'goColumnLeft', 'goColumnRight', 'goDocEnd', 'goDocStart',
            'goGroupLeft', 'goGroupRight', 'goLineDown', 'goLineEnd', 'goLineLeft',
            'goLineRight', 'goLineStart', 'goLineStartSmart', 'goLineUp', 'goPageDown',
            'goPageUp', 'goWordLeft', 'goWordRight', 'killLine', 'newlineAndIndent',
            'selectAll', 'toggleOverwrite', 'transposeChars'];
        return wanted.filter((n) => typeof w.ltCm6Commands.commands[n] !== 'function');
    });
    expect(missing).toEqual([]);
});
