// The sublime keymap's commands, which is where Light Table's multiple cursors
// and line editing came from.
//
// Twenty-one commands, registered by a CodeMirror 5 addon onto the CodeMirror 5
// global. Nothing carries them to CodeMirror 6, so each one is either a name
// CodeMirror 6 already has, a few lines about `EditorSelection`, or a gap that
// says so.
//
// Compared against CodeMirror 5 where the answer should be identical, because
// "it does something" is not the claim — the claim is that the key you have
// pressed for ten years still does what it did.

import { test, expect } from './fixtures';
import type { Page } from '@playwright/test';

/** Run `command` on both engines from the same document, and return both. */
async function bothEngines(window: Page, doc: string, command: string,
                           setup = ''): Promise<[unknown, unknown]> {
    return await window.evaluate(([text, name, prepare]) => {
        const w = globalThis as any;
        const host = document.createElement('div');
        document.body.appendChild(host);

        const five = w.CodeMirror(host, { value: text });
        const six = w.ltCm6Editor.makeCm6Editor(host, { value: text });
        const prep = new Function('ed', prepare as string);

        const run = (ed: any, go: () => void) => {
            prep(ed);
            go();
            return {
                value: ed.getValue(),
                cursors: ed.listSelections().map(
                    (s: any) => `${s.head.line}:${s.head.ch}`).sort()
            };
        };

        try {
            return [
                run(five, () => w.CodeMirror.commands[name as string](five)),
                run(six, () => six.execCommand(name as string))
            ];
        } finally {
            host.remove();
        }
    }, [doc, command, setup] as [string, string, string]) as [unknown, unknown];
}

const DOC = 'gamma\nalpha\nbeta\n';
const AT_TOP = 'ed.setCursor({line: 0, ch: 0});';

test('a line command does the same thing on both engines', async ({ window }) => {
    for (const [command, setup] of [
        ['duplicateLine', AT_TOP],
        ['swapLineDown', AT_TOP],
        ['swapLineUp', 'ed.setCursor({line: 2, ch: 0});'],
        ['sortLines', 'ed.setSelection({line: 0, ch: 0}, {line: 2, ch: 4});'],
        ['sortLinesInsensitive', 'ed.setSelection({line: 0, ch: 0}, {line: 2, ch: 4});']
    ] as [string, string][]) {
        const [five, six] = await bothEngines(window, DOC, command, setup);
        expect(six, `${command} should match CodeMirror 5`).toEqual(five);
    }
});

test('and a selection command puts the cursors in the same places', async ({ window }) => {
    for (const [command, setup] of [
        ['selectNextOccurrence', 'ed.setSelection({line: 1, ch: 0}, {line: 1, ch: 5});'],
        ['addCursorToNextLine', AT_TOP],
        ['addCursorToPrevLine', 'ed.setCursor({line: 2, ch: 0});'],
        ['splitSelectionByLine', 'ed.setSelection({line: 0, ch: 0}, {line: 2, ch: 4});']
    ] as [string, string][]) {
        const [five, six] = await bothEngines(window, DOC, command, setup);
        expect(six, `${command} should match CodeMirror 5`).toEqual(five);
    }
});

test('singleSelectionTop keeps the first cursor, not the last one', async ({ window }) => {
    // The one that is not CodeMirror 6's `simplifySelection`, which keeps the
    // *main* range — and the main range is the one added last. Selecting four
    // occurrences downward and pressing this would leave you at the bottom
    // instead of back where you started, which is the opposite of the point.
    const line = await window.evaluate(() => {
        const w = globalThis as any;
        const host = document.createElement('div');
        document.body.appendChild(host);
        const ed = w.ltCm6Editor.makeCm6Editor(host, { value: 'a\nb\nc\nd\n' });
        ed.setCursor({ line: 0, ch: 0 });
        ed.execCommand('addCursorToNextLine');
        ed.execCommand('addCursorToNextLine');
        ed.execCommand('singleSelectionTop');
        const out = [ed.listSelections().length, ed.getCursor().line];
        host.remove();
        return out;
    });
    expect(line).toEqual([1, 0]);
});

test('a command with no CodeMirror 6 answer is named rather than silently absent',
    async ({ window }) => {
    const gaps = await window.evaluate(
        () => Object.keys((globalThis as any).ltCm6Commands.UNSUPPORTED_COMMANDS));
    // One left of the twenty-one, and it is the one that only *moves* to a
    // bracket rather than selecting to it — CodeMirror 6 has no such command.
    expect(gaps).toEqual(['goToBracket']);

    // And every other sublime command Light Table registers is answered.
    const missing = await window.evaluate(() => {
        const w = globalThis as any;
        const table = w.ltCm6Commands.commands;
        const gapNames = Object.keys(w.ltCm6Commands.UNSUPPORTED_COMMANDS);
        return ['addCursorToNextLine', 'addCursorToPrevLine', 'duplicateLine', 'goToBracket',
            'insertLineAfter', 'insertLineBefore', 'joinLines', 'redoSelection',
            'selectBetweenBrackets', 'selectLinesDownward', 'selectLinesUpward',
            'selectNextOccurrence', 'selectScope', 'singleSelectionTop',
            'skipAndSelectNextOccurrence', 'sortLines', 'sortLinesInsensitive',
            'splitSelectionByLine', 'swapLineDown', 'swapLineUp', 'undoSelection']
            .filter((name) => typeof table[name] !== 'function' && !gapNames.includes(name));
    });
    expect(missing).toEqual([]);
});
