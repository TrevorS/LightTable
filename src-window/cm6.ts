// CodeMirror 6, and the one thing it changes about this editor.
//
// Light Table's product is what sits *between* two lines of code: a result, a
// watch, a proposed edit, a diagnostic. On CodeMirror 5 that is a line widget —
// something you imperatively add and must remember to remove, which is why
// `lt.ui.bands` had to grow `ensure-widget!`, a table of what is drawn, and an
// orphan sweep to take away what the state no longer asks for.
//
// CodeMirror 6 derives decorations from state. You declare "given this, these
// widgets exist" and the view reconciles. That is the same shape as everything
// else built here — hiccup from state, bands from state — and it means the
// bookkeeping stops being code.
//
// This is the field that does it, and `lt.ui.bands` is now written against it:
// that namespace answers "which bands exist" and hands the answer over whole.
// The CodeMirror 5 half of the bookkeeping still exists, but it lives in
// `lt.objs.editor.bands` where the engine that needs it is, and it goes when
// that engine does.

import { EditorState, StateField, StateEffect, RangeSet } from '@codemirror/state';
import type { Extension } from '@codemirror/state';
import { EditorView, Decoration, WidgetType, lineNumbers } from '@codemirror/view';
import type { DecorationSet } from '@codemirror/view';

/** One band: a DOM node to show under a line. */
export interface Band {
    /** Zero-based, the way every address in `lt.state` is. */
    line: number;
    /** Identity, so a band that stays is not rebuilt. Usually `[path line kind]`. */
    key: string;
    /**
     * What the band is showing, if the caller can say.
     *
     * Two bands with the same key and equal content are the same widget and no
     * work happens. Same key, different content is the same *node* showing
     * something new — see `updateDOM`. Leave it undefined and every declaration
     * refills the node, which is correct and merely less lazy.
     */
    content?: unknown;
    /** How to compare content. Defaults to `Object.is`; ClojureScript passes `=`. */
    equals?: (a: unknown, b: unknown) => boolean;
    /** Who fills the node. Called to create it, and again when content changes. */
    mount: (node: HTMLElement) => void;
}

/**
 * A block widget whose content someone else owns.
 *
 * Two methods carry the whole arrangement. `eq` says when two declarations mean
 * the same widget, so an unchanged band is not touched at all. `updateDOM` says
 * what happens when they differ but share a key: the *existing node* is refilled
 * rather than replaced, so Replicant patches into the DOM it already rendered
 * and CodeMirror never looks inside.
 *
 * Without `updateDOM` the choice would be between a key that includes the
 * content — every change rebuilding the node and discarding Replicant's work —
 * and a key that does not, which would never show a new value at all.
 */
class BandWidget extends WidgetType {
    constructor(
        readonly key: string,
        readonly mount: (node: HTMLElement) => void,
        readonly content?: unknown,
        readonly equals?: (a: unknown, b: unknown) => boolean
    ) {
        super();
    }

    override eq(other: BandWidget): boolean {
        if (other.key !== this.key) return false;
        if (this.content === undefined && other.content === undefined) return false;
        return (this.equals ?? Object.is)(other.content, this.content);
    }

    override toDOM(): HTMLElement {
        const node = document.createElement('div');
        node.className = 'lt-band';
        // Which band this node is, so `updateDOM` can tell "the same band with a
        // new value" from "a different band at the same place" — CodeMirror
        // hands over the old node and not the old widget, so the key has to be
        // on the node to be asked about. Useful to a person reading the DOM too.
        node.dataset['band'] = this.key;
        this.mount(node);
        return node;
    }

    /**
     * Same band, new content: keep the node and refill it.
     *
     * A *different* band declines, and CodeMirror builds a fresh node — because
     * a proposed edit replaced by a conflict on the same line is not the edit
     * with a new value, and the node it was rendered into belongs to the band
     * that left.
     */
    override updateDOM(node: HTMLElement): boolean {
        if (node.dataset['band'] !== this.key) return false;
        this.mount(node);
        return true;
    }

    // The band is not editable text and the cursor should not enter it.
    override ignoreEvent(): boolean {
        return false;
    }
}

/** Replace the whole set of bands. The only way to change them. */
export const setBands = StateEffect.define<Band[]>();

function decorationsFor(state: EditorState, bands: Band[]): DecorationSet {
    const lines = state.doc.lines;
    return RangeSet.of(
        bands
            // Coerced before it is compared, because the comparisons below lie
            // about the values that are not numbers: `null >= 0` is true, and
            // a line that arrived as the string "1" would index line "11".
            .map((b) => ({ ...b, line: Math.trunc(Number(b.line)) }))
            // A band past the end is one computed against text since changed.
            // Dropped rather than thrown, the same rule the CodeMirror 5 path
            // applies in `lt.objs.editor.bands`.
            .filter((b) => Number.isFinite(b.line) && b.line >= 0 && b.line < lines)
            .sort((a, b) => a.line - b.line)
            .map((b) => Decoration.widget({
                widget: new BandWidget(b.key, b.mount, b.content, b.equals),
                block: true,
                side: 1
            }).range(state.doc.line(b.line + 1).to)),
        true);
}

/**
 * The field that holds them.
 *
 * This is the difference from CodeMirror 5, in eight lines. The set of widgets
 * is a function of state: it maps forward through every document change on its
 * own, so a band follows the line it belongs to when you type above it, and
 * nothing has to notice. There is no table of what is drawn and no orphan
 * sweep, because there is nothing to orphan.
 */
export const bandField = StateField.define<{ bands: Band[], decorations: DecorationSet }>({
    create() {
        return { bands: [], decorations: Decoration.none };
    },
    update(value, tr) {
        for (const effect of tr.effects) {
            if (effect.is(setBands)) {
                return { bands: effect.value, decorations: decorationsFor(tr.state, effect.value) };
            }
        }
        if (tr.docChanged) {
            return { bands: value.bands, decorations: value.decorations.map(tr.changes) };
        }
        return value;
    },
    provide: (f) => EditorView.decorations.from(f, (v) => v.decorations)
});

export interface Cm6Options {
    doc?: string;
    parent: HTMLElement;
    extensions?: Extension[];
}

/** A CodeMirror 6 editor with the band field installed. */
export function makeEditor(options: Cm6Options): EditorView {
    return new EditorView({
        state: EditorState.create({
            doc: options.doc ?? '',
            extensions: [lineNumbers(), bandField, ...(options.extensions ?? [])]
        }),
        parent: options.parent
    });
}

/** Ask the view to show exactly `bands`, and nothing else. */
export function showBands(view: EditorView, bands: Band[]): void {
    view.dispatch({ effects: setBands.of(bands) });
}

/** What is currently drawn, as keys. For asserting about. */
export function drawnBands(view: EditorView): string[] {
    const out: string[] = [];
    view.state.field(bandField).decorations.between(0, view.state.doc.length, (_from, _to, deco) => {
        const widget = deco.spec.widget;
        if (widget instanceof BandWidget) out.push(widget.key);
    });
    return out;
}

declare global {
    interface Window { ltCm6?: unknown }
}

// Reachable from the window, so a test and a REPL can both use it. The rest of
// the editor does not know this exists yet, which is the point of a spike.
window.ltCm6 = { makeEditor, showBands, drawnBands, EditorView, EditorState };
