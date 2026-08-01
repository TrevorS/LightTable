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
// This is the spike that says so with a running editor rather than an argument.
// It does not replace `lt.objs.editor`; it proves the mechanism that a port
// would be built on, so the decision rests on something observed.

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
    /** Who fills the node. Called once per distinct key. */
    mount: (node: HTMLElement) => void;
}

/**
 * A block widget whose content someone else owns.
 *
 * `eq` is what makes this worth doing: two widgets with the same key are the
 * same widget, so CodeMirror keeps the existing DOM and whatever was rendered
 * into it. Replicant patches inside; CodeMirror never looks.
 */
class BandWidget extends WidgetType {
    constructor(readonly key: string, readonly mount: (node: HTMLElement) => void) {
        super();
    }

    override eq(other: BandWidget): boolean {
        return other.key === this.key;
    }

    override toDOM(): HTMLElement {
        const node = document.createElement('div');
        node.className = 'cm6-band';
        this.mount(node);
        return node;
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
            // A band past the end is one computed against text since changed.
            // Dropped rather than thrown, the same rule the CodeMirror 5 path
            // applies in `ensure-widget!`.
            .filter((b) => b.line >= 0 && b.line < lines)
            .sort((a, b) => a.line - b.line)
            .map((b) => Decoration.widget({
                widget: new BandWidget(b.key, b.mount),
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
