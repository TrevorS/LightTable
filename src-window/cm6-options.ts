// The options an editor is configured with, as CodeMirror 6 extensions.
//
// Light Table configures an editor by setting named options — `lineNumbers`,
// `lineWrapping`, `readOnly`, `matchBrackets` and a dozen more — and it does it
// through *behaviors*, so which ones are on is a user's setting rather than a
// constant. `deploy/settings/default/default.behaviors` is where wrapping and
// line numbers are turned on, and a person can turn them off.
//
// CodeMirror 5 took those as a bag of mutable properties. CodeMirror 6 has no
// such bag: what an editor does is its extension list, and changing part of it
// without rebuilding the state is what a `Compartment` is for. So each option
// gets a compartment and a function from its value to an extension, and setting
// one reconfigures exactly that compartment — the document, the history and the
// selection all survive, which is what `set-options` always implied.
//
// An option with no CodeMirror 6 equivalent is listed at the bottom rather than
// dropped silently, because "I turned that on and nothing happened" is the
// worst way to find out.

import { Compartment, EditorState } from '@codemirror/state';
import type { Extension } from '@codemirror/state';
import {
    EditorView, lineNumbers, highlightActiveLine, highlightActiveLineGutter,
    drawSelection, scrollPastEnd
} from '@codemirror/view';
import { bracketMatching, foldGutter, indentUnit } from '@codemirror/language';
import { closeBrackets } from '@codemirror/autocomplete';

/** A number, however the setting arrived — settings come back from JSON. */
const num = (value: unknown, fallback: number): number => {
    const n = Number(value);
    return Number.isFinite(n) ? n : fallback;
};

/** CodeMirror 5 treats a missing option as off, and so does this. */
const on = (value: unknown): boolean => Boolean(value);

type Build = (value: unknown, options: Record<string, unknown>) => Extension;

/**
 * Option name to what it means, for the ones CodeMirror 6 can express.
 *
 * Read from the option map rather than only from the new value where one option
 * depends on another — indentation is the case: whether a unit is tabs or
 * spaces is `indentWithTabs`, and setting either has to produce the same answer.
 */
export const OPTIONS: Record<string, Build> = {
    // Line numbers are a gutter, and their absence is the gutter's absence.
    lineNumbers: (v) => (on(v) ? lineNumbers() : []),

    lineWrapping: (v) => (on(v) ? EditorView.lineWrapping : []),

    readOnly: (v) => EditorState.readOnly.of(on(v)),

    // Two, because CodeMirror 5's one option lit the line and its number, and
    // the themes style `.CodeMirror-activeline-background` expecting both.
    styleActiveLine: (v) => (on(v) ? [highlightActiveLine(), highlightActiveLineGutter()] : []),

    matchBrackets: (v) => (on(v) ? bracketMatching() : []),

    autoCloseBrackets: (v) => (on(v) ? closeBrackets() : []),

    foldGutter: (v) => (on(v) ? foldGutter() : []),

    scrollPastEnd: (v) => (on(v) ? scrollPastEnd() : []),

    tabSize: (v) => EditorState.tabSize.of(num(v, 4)),

    indentUnit: (v, options) => indentUnit.of(
        on(options['indentWithTabs']) ? '\t' : ' '.repeat(num(v, 2))),

    indentWithTabs: (_v, options) => indentUnit.of(
        on(options['indentWithTabs']) ? '\t' : ' '.repeat(num(options['indentUnit'], 2))),

    // The cursor is drawn by `drawSelection`, so its blink rate is that
    // extension's configuration rather than a property of the editor.
    cursorBlinkRate: (v) => drawSelection({ cursorBlinkRate: num(v, 1200) }),

    // CodeMirror 6 draws its own selection either way; what CodeMirror 5's
    // option asked for is that it keep drawing one while unfocused.
    showCursorWhenSelecting: () => []
};

/**
 * Options CodeMirror 6 has nothing to say about, and why.
 *
 * `rulers` is the only one anybody would miss — a vertical line at column N —
 * and it wants a decoration field of its own rather than a shim. The rest were
 * CodeMirror 5 implementation details that CodeMirror 6 either does differently
 * or does not need: `dragDrop` is a DOM handler, `undoDepth` is history
 * configuration set once, `autoClearEmptyLines` and `singleCursorHeightPerLine`
 * are about a rendering model that no longer exists.
 */
export const UNSUPPORTED: Record<string, string> = {
    rulers: 'wants a decoration field; nothing is drawn',
    gutters: 'the gutter set is the extension list, not a value',
    dragDrop: 'CodeMirror 6 has no such option; drops are DOM events',
    undoDepth: 'history depth is configured once, at construction',
    autoClearEmptyLines: 'no equivalent; CodeMirror 6 does not do this',
    singleCursorHeightPerLine: 'no equivalent in CodeMirror 6 line rendering',
    keyMap: 'a keymap is an extension, not a named preset'
};

/**
 * One compartment per option, and the extension list they start as.
 *
 * Every compartment has to be present in the initial state — a compartment the
 * state has never seen cannot be reconfigured later — so they are all created
 * up front and most of them start empty.
 */
export class Options {
    private readonly compartments = new Map<string, Compartment>();
    readonly values: Record<string, unknown> = {};

    constructor() {
        for (const name of Object.keys(OPTIONS)) {
            this.compartments.set(name, new Compartment());
        }
    }

    /** The initial extensions: every compartment, holding what `defaults` says. */
    initial(defaults: Record<string, unknown>): Extension[] {
        Object.assign(this.values, defaults);
        return [...this.compartments.entries()].map(([name, compartment]) =>
            compartment.of(OPTIONS[name]!(this.values[name], this.values)));
    }

    /**
     * Set one option. True when it meant something here.
     *
     * False for a name in [[UNSUPPORTED]] and for anything unrecognised, which
     * the caller records either way — `getOption` answers what was set, the way
     * CodeMirror 5 does, whether or not it had an effect.
     */
    set(view: EditorView, name: string, value: unknown): boolean {
        this.values[name] = value;
        const compartment = this.compartments.get(name);
        if (!compartment) return false;
        view.dispatch({
            effects: compartment.reconfigure(OPTIONS[name]!(value, this.values))
        });
        // Indentation is two options describing one thing, so setting either
        // has to move the other's compartment as well or they disagree.
        for (const partner of name === 'indentUnit' ? ['indentWithTabs']
            : name === 'indentWithTabs' ? ['indentUnit'] : []) {
            const other = this.compartments.get(partner);
            if (other) {
                view.dispatch({
                    effects: other.reconfigure(OPTIONS[partner]!(this.values[partner], this.values))
                });
            }
        }
        return true;
    }

    /** Every option name that does something, for asking about. */
    static known(): string[] { return Object.keys(OPTIONS).sort(); }
}

declare global {
    interface Window { ltCm6Options?: unknown }
}

window.ltCm6Options = { OPTIONS, UNSUPPORTED, known: Options.known };
