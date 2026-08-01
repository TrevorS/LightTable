// CodeMirror 5's command names, mapped onto CodeMirror 6's commands.
//
// `lt.objs.editor.pool` registers 33 Light Table commands whose whole body is
// `js/CodeMirror.commands.<name>`. That was the largest single block of
// CodeMirror references in the codebase and it is the most mechanical: a name
// on the left, a function on the right.
//
// Two of them have no direct equivalent and are written out here rather than
// left missing, because "cursor to line start, but smart" is behaviour a person
// notices the absence of.

import type { EditorView } from '@codemirror/view';
import type { StateCommand } from '@codemirror/state';
import { EditorSelection } from '@codemirror/state';
import { EditorState } from '@codemirror/state';
import { selectNextOccurrence } from '@codemirror/search';
import {
    undoSelection, redoSelection, simplifySelection, selectParentSyntax,
    selectMatchingBracket, copyLineDown, moveLineUp, moveLineDown,
    cursorCharLeft, cursorCharRight, cursorLineUp, cursorLineDown,
    cursorLineStart, cursorLineEnd, cursorLineBoundaryBackward, cursorLineBoundaryForward,
    cursorDocStart, cursorDocEnd, cursorPageUp, cursorPageDown,
    cursorGroupLeft, cursorGroupRight,
    deleteCharBackward, deleteCharForward,
    deleteLine, deleteToLineStart, deleteToLineEnd,
    insertNewlineAndIndent, selectAll, cursorLineBoundaryLeft, cursorLineBoundaryRight
} from '@codemirror/commands';

type Command = (view: EditorView) => boolean;

/**
 * Move to the first non-whitespace character, or to the very start if already
 * there. CodeMirror 6 has `cursorLineStart` and no "smart" variant, and this is
 * the one people bind to Home.
 */
const goLineStartSmart: Command = (view) => {
    view.dispatch(view.state.update({
        selection: EditorSelection.create(view.state.selection.ranges.map((range) => {
            const line = view.state.doc.lineAt(range.head);
            const firstText = line.text.search(/\S/);
            const indented = firstText < 0 ? line.to : line.from + firstText;
            return EditorSelection.cursor(range.head === indented ? line.from : indented);
        }), view.state.selection.mainIndex),
        scrollIntoView: true
    }));
    return true;
};

/**
 * Delete the line and put the cursor at the start of the next one.
 *
 * CodeMirror 6's `deleteLine` keeps the column; CodeMirror 5 goes to column
 * zero. The document is identical either way — this is only about where you
 * are left standing, which is exactly the kind of difference a keybinding
 * makes you feel and no document comparison would catch.
 */
const deleteLineToStart: Command = (view) => {
    const done = deleteLine(view);
    view.dispatch({
        selection: EditorSelection.cursor(view.state.doc.lineAt(view.state.selection.main.head).from)
    });
    return done;
};

/**
 * Swap the characters around the cursor, and at the end of a line swap the two
 * before it.
 *
 * That last clause is the whole difference. CodeMirror 6 transposes across the
 * line boundary, so pressing this at the end of "last" moved the *t* onto the
 * next line; CodeMirror 5 turns it into "lats", which is what the command is
 * for. Found by running both from the end of a line rather than the middle.
 */
const transposeCharsLikeCm5: Command = (view) => {
    const state = view.state;
    const changes = [];
    for (const range of state.selection.ranges) {
        const line = state.doc.lineAt(range.head);
        const at = range.head === line.to && line.to > line.from + 1
            ? range.head - 1     // at the end: the two before
            : range.head;        // otherwise: around the cursor
        if (at <= line.from || at >= line.to) continue;
        changes.push({
            from: at - 1,
            to: at + 1,
            insert: state.sliceDoc(at, at + 1) + state.sliceDoc(at - 1, at)
        });
    }
    if (!changes.length) return false;
    // And the cursor moves past the pair, the way CodeMirror 5 leaves it — so
    // holding the key walks a character along the line instead of swapping the
    // same two back and forth.
    view.dispatch({
        changes,
        selection: EditorSelection.cursor(changes[0]!.to),
        scrollIntoView: true
    });
    return true;
};

/**
 * Group motion that steps over a line boundary the way CodeMirror 5 does.
 *
 * At the very start of a line CodeMirror 5 moves to the *end of the previous*
 * one; CodeMirror 6 skips a whole group and lands mid-word above. The
 * difference only shows at the boundary, which is why the test runs every
 * command from the edges as well as the middle.
 */
const classOf = (c: string): 'word' | 'space' | 'punct' =>
    /\w/.test(c) ? 'word' : /\s/.test(c) ? 'space' : 'punct';

/**
 * A group is a run of one character class, and whitespace is a class.
 *
 * That is CodeMirror 5's rule and not CodeMirror 6's: `cursorGroupForward`
 * skips the whitespace *and* the word after it, so from the start of an
 * indented line it lands mid-word rather than at the text. Written out because
 * both differences — this one and stepping over a line boundary — only show at
 * an edge, which is why the test runs every command from three of them.
 */
const groupMotion = (back: boolean): Command => (view) => {
    const doc = view.state.doc;
    const head = view.state.selection.main.head;
    const line = doc.lineAt(head);

    // At the boundary, step onto the neighbouring line and stop there.
    if (back ? head === line.from : head === line.to) {
        const beyond = back ? line.from - 1 : line.to + 1;
        if (beyond < 0 || beyond > doc.length) return false;
        view.dispatch({ selection: EditorSelection.cursor(beyond), scrollIntoView: true });
        return true;
    }

    const text = line.text;
    const at = (i: number): string => text[i] ?? '';
    let to = head - line.from;
    if (back) {
        const kind = classOf(at(to - 1));
        while (to > 0 && classOf(at(to - 1)) === kind) to -= 1;
    } else {
        const kind = classOf(at(to));
        while (to < text.length && classOf(at(to)) === kind) to += 1;
    }
    view.dispatch({ selection: EditorSelection.cursor(line.from + to), scrollIntoView: true });
    return true;
};

/**
 * Delete by the same group rule the motion uses.
 *
 * CodeMirror 5 defines these as "find where the group motion would land, and
 * delete to there" — so at the start of a line it takes the newline *and* the
 * word above, which `deleteGroupBackward` does not. Sharing the rule with the
 * motion is also what stops the two drifting apart later.
 */
const deleteByWord = (back: boolean): Command => (view) => {
    // A word is not a group. CodeMirror 5's del-word skips the whitespace and
    // then takes the word; del-group takes one run of one class. Mapping both
    // onto the same motion deleted the indent and left the word behind.
    const before = view.state.selection.main.head;
    const line = view.state.doc.lineAt(before);
    const at = before - line.from;
    const next = back ? line.text[at - 1] : line.text[at];
    const overSpace = next !== undefined && /\s/.test(next) && at !== (back ? 0 : line.text.length);
    // Crossing a line boundary is a step rather than a word, so that counts as
    // the whitespace half too — del-group stops at the newline, del-word does
    // not. That difference is the whole reason these are two functions.
    const atBoundary = back ? at === 0 : at === line.text.length;
    const done = deleteByGroup(back)(view);
    return (overSpace || atBoundary) ? (deleteByGroup(back)(view) || done) : done;
};

const deleteByGroup = (back: boolean): Command => (view) => {
    const from = view.state.selection.main.head;
    if (!groupMotion(back)(view)) return false;
    const to = view.state.selection.main.head;
    if (from === to) return false;
    view.dispatch({
        changes: { from: Math.min(from, to), to: Math.max(from, to) },
        selection: EditorSelection.cursor(Math.min(from, to)),
        scrollIntoView: true
    });
    return true;
};

/**
 * Overwrite mode. CodeMirror 6 does not have one, and rather than pretend, this
 * records the intent so a caller can see it was asked for — there is exactly
 * one binding to it and no code that reads the state.
 */
let overwriting = false;
const toggleOverwrite: Command = () => {
    overwriting = !overwriting;
    return true;
};

export const isOverwriting = (): boolean => overwriting;


// --- multiple selections ---------------------------------------------------
//
// CodeMirror 5 got these from the sublime keymap: an addon registering commands
// on the global, operating on a CodeMirror 5 editor. CodeMirror 6 has multiple
// selections in the state itself, so most of these are a few lines about
// `EditorSelection` rather than a feature to build — but they do have to be
// written, because nothing carries them over.

/** Every selection, sorted the way the document reads. */
const ranges = (view: EditorView) =>
    [...view.state.selection.ranges].sort((a, b) => a.from - b.from);

/**
 * Keep only the first selection, which is the top one in the document.
 *
 * Not CodeMirror 6's `simplifySelection`: that keeps the *main* range, and the
 * main range is the one added last. Pressing this after selecting four
 * occurrences downward would leave you at the bottom instead of back where you
 * started, which is the opposite of what the key is for.
 */
const singleSelectionTop: Command = (view) => {
    const first = ranges(view)[0];
    if (!first) return false;
    view.dispatch({ selection: EditorSelection.single(first.anchor, first.head), scrollIntoView: true });
    return true;
};

/** A cursor on the line above or below every existing one. */
const addCursorToLine = (down: boolean): Command => (view) => {
    const doc = view.state.doc;
    const added = [];
    for (const range of view.state.selection.ranges) {
        const line = doc.lineAt(range.head);
        const n = line.number + (down ? 1 : -1);
        if (n < 1 || n > doc.lines) continue;
        const target = doc.line(n);
        const column = range.head - line.from;
        added.push(EditorSelection.cursor(Math.min(target.from + column, target.to)));
    }
    if (!added.length) return false;
    view.dispatch({
        selection: EditorSelection.create([...view.state.selection.ranges, ...added],
                                          view.state.selection.mainIndex),
        scrollIntoView: true
    });
    return true;
};

/** One cursor per line of the selection, at the end of each. */
const splitSelectionByLine: Command = (view) => {
    const doc = view.state.doc;
    const out = [];
    for (const range of view.state.selection.ranges) {
        if (range.empty) { out.push(range); continue; }
        const first = doc.lineAt(range.from).number;
        const last = doc.lineAt(range.to).number;
        for (let n = first; n <= last; n++) {
            const line = doc.line(n);
            out.push(EditorSelection.cursor(Math.min(line.to, Math.max(line.from, range.to))));
        }
    }
    if (!out.length) return false;
    view.dispatch({ selection: EditorSelection.create(out), scrollIntoView: true });
    return true;
};

/** Leave this occurrence behind and take the next one instead. */
const skipAndSelectNextOccurrence: Command = (view) => {
    if (!selectNextOccurrence(view)) return false;
    const all = view.state.selection.ranges;
    if (all.length < 2) return true;
    // The one just added is the main range; drop the one before it.
    const main = view.state.selection.main;
    const kept = all.filter((r) => r !== all[view.state.selection.mainIndex - 1]);
    view.dispatch({
        selection: EditorSelection.create(kept.length ? kept : [main],
                                          Math.max(0, kept.indexOf(main))),
        scrollIntoView: true
    });
    return true;
};

/** Open a line before or after each selection, and put the cursor on it. */
const insertLine = (after: boolean): Command => (view) => {
    const doc = view.state.doc;
    const changes = [];
    for (const range of view.state.selection.ranges) {
        const line = doc.lineAt(range.head);
        changes.push(after
            ? { from: line.to, insert: '\n' }
            : { from: line.from, insert: '\n' });
    }
    if (!changes.length) return false;
    const tr = view.state.update({ changes, scrollIntoView: true });
    view.dispatch(tr);
    // After the insert, sit on the new line rather than where the text moved to.
    const at = view.state.selection.main.head;
    const line = view.state.doc.lineAt(at);
    view.dispatch({
        selection: EditorSelection.cursor(after ? line.to : Math.max(0, line.from - 1))
    });
    return true;
};

/** Sort the lines the selection covers, or the whole document when it is empty. */
const sortLines = (caseSensitive: boolean): Command => (view) => {
    const doc = view.state.doc;
    const range = view.state.selection.main;
    const first = doc.lineAt(range.empty ? 0 : range.from);
    const last = doc.lineAt(range.empty ? doc.length : range.to);
    if (first.number === last.number) return false;

    const lines = [];
    for (let n = first.number; n <= last.number; n++) lines.push(doc.line(n).text);
    const key = (x: string) => (caseSensitive ? x : x.toLowerCase());
    lines.sort((a, b) => (key(a) < key(b) ? -1 : key(a) > key(b) ? 1 : 0));

    // And the sorted region stays selected, with the head on the line *after*
    // it — which is where CodeMirror 5 leaves it, so sorting twice in a row
    // sorts the same lines rather than a region that crept by one each time.
    const end = Math.min(doc.length, last.to + 1);
    view.dispatch({
        changes: { from: first.from, to: last.to, insert: lines.join('\n') },
        selection: EditorSelection.range(first.from, end),
        scrollIntoView: true
    });
    return true;
};

/**
 * Join each selected line with the one after it.
 *
 * Written out because CodeMirror 6 has no such command, and the interesting
 * part is not the join: it is that the leading whitespace of the next line goes
 * with the newline. Joining an indented block otherwise leaves a gutter of
 * spaces in the middle of the line, which is not what anyone means by joining.
 */
const joinLines: Command = (view) => {
    const doc = view.state.doc;
    const changes = [];
    for (const range of view.state.selection.ranges) {
        const first = doc.lineAt(range.from).number;
        const last = Math.max(first, doc.lineAt(range.to).number - (range.empty ? 0 : 1));
        for (let n = first; n <= last; n++) {
            if (n >= doc.lines) continue;
            const line = doc.line(n);
            const next = doc.line(n + 1);
            const indent = next.text.length - next.text.trimStart().length;
            changes.push({ from: line.to, to: next.from + indent, insert: ' ' });
        }
    }
    if (!changes.length) return false;
    view.dispatch({ changes, scrollIntoView: true });
    return true;
};

/** The state extension multiple selections need to exist at all. */
export const multipleSelections = EditorState.allowMultipleSelections.of(true);

/** CodeMirror 5's name → the CodeMirror 6 command that does the same thing. */
export const commands: Record<string, Command | StateCommand> = {
    // Moving
    goCharLeft: cursorCharLeft,
    goCharRight: cursorCharRight,
    goColumnLeft: cursorLineBoundaryLeft,
    goColumnRight: cursorLineBoundaryRight,
    goLineUp: cursorLineUp,
    goLineDown: cursorLineDown,
    goLineStart: cursorLineStart,
    goLineEnd: cursorLineEnd,
    goLineLeft: cursorLineBoundaryBackward,
    goLineRight: cursorLineBoundaryForward,
    goLineStartSmart,
    goDocStart: cursorDocStart,
    goDocEnd: cursorDocEnd,
    goPageUp: cursorPageUp,
    goPageDown: cursorPageDown,
    goWordLeft: cursorGroupLeft,
    goWordRight: cursorGroupRight,
    goGroupLeft: groupMotion(true),
    goGroupRight: groupMotion(false),

    // Deleting
    delCharBefore: deleteCharBackward,
    delCharAfter: deleteCharForward,
    delWordBefore: deleteByWord(true),
    delWordAfter: deleteByWord(false),
    delGroupBefore: deleteByGroup(true),
    delGroupAfter: deleteByGroup(false),
    // At column zero there is nothing to the left, and CodeMirror 5 does
    // nothing. CodeMirror 6 deletes the newline and joins the lines, which is a
    // surprising thing for a key called "delete line left" to do.
    delLineLeft: (view: EditorView): boolean => {
        const head = view.state.selection.main.head;
        return head === view.state.doc.lineAt(head).from ? false : deleteToLineStart(view);
    },
    killLine: deleteToLineEnd,
    deleteLine: deleteLineToStart,

    // Everything else
    newlineAndIndent: insertNewlineAndIndent,
    selectAll,
    transposeChars: transposeCharsLikeCm5,
    toggleOverwrite,

    // The sublime keymap's, which is where Light Table's multiple cursors came
    // from. Three are missing and named at the bottom of this file.
    selectNextOccurrence,
    skipAndSelectNextOccurrence,
    singleSelectionTop,
    splitSelectionByLine,
    undoSelection,
    redoSelection,
    simplifySelection,
    addCursorToNextLine: addCursorToLine(true),
    addCursorToPrevLine: addCursorToLine(false),
    selectLinesDownward: addCursorToLine(true),
    selectLinesUpward: addCursorToLine(false),
    selectScope: selectParentSyntax,
    selectBetweenBrackets: selectMatchingBracket,
    duplicateLine: copyLineDown,
    swapLineUp: moveLineUp,
    swapLineDown: moveLineDown,
    joinLines,
    insertLineAfter: insertLine(true),
    insertLineBefore: insertLine(false),
    sortLines: sortLines(true),
    sortLinesInsensitive: sortLines(false)
};

/**
 * Sublime commands with no CodeMirror 6 equivalent here, and why.
 *
 * One left of the three. `selectScope` is `selectParentSyntax` — selecting the
 * enclosing syntax node is exactly what that is — and `selectBetweenBrackets`
 * is `selectMatchingBracket`. `goToBracket` only *moves* to the match, and
 * CodeMirror 6 has no command that does, so it wants writing against the
 * bracket-matching extension rather than mapping to a name.
 */
export const UNSUPPORTED_COMMANDS: Record<string, string> = {
    goToBracket: 'moves to the match rather than selecting to it; wants matchBrackets'
};

/** Run a CodeMirror 5 command by name. False when there is no such command. */
export function runCommand(name: string, view: EditorView): boolean {
    const command = commands[name];
    return command ? Boolean((command as Command)(view)) : false;
}

declare global {
    interface Window { ltCm6Commands?: unknown }
}

window.ltCm6Commands = { commands, runCommand, isOverwriting, UNSUPPORTED_COMMANDS };
