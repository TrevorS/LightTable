// Tree-sitter highlighting on CodeMirror 6.
//
// The other engine takes this as a mode: a per-line tokenizer that CodeMirror 5
// pulls on, line by line, asking what colour the next few characters are.
// CodeMirror 6 has no such thing and does not want one — it draws from
// decorations, which is a set of ranges the view is handed rather than a
// question it asks.
//
// That difference is why this is a separate file rather than a branch inside
// the mode, and it is smaller than it sounds: the span table in treesitter.ts is
// already the answer, and `runsForLine` already turns it into runs. Both engines
// call that. This one wraps each run in a `Decoration.mark` with the same class
// name CodeMirror 5 would have put there, which is why css/treesitter.css did
// not have to change and neither did any of the capture vocabulary.
//
// Decorations are built for the visible lines only. That is not an optimisation
// borrowed from somewhere — it is how CodeMirror 6's own syntax highlighting
// works, and it is what keeps a 10,000-line file the same cost as a screenful.

import { StateEffect, StateField, RangeSetBuilder } from '@codemirror/state';
import type { Extension } from '@codemirror/state';
import { EditorView, Decoration, ViewPlugin } from '@codemirror/view';
import type { DecorationSet, ViewUpdate } from '@codemirror/view';
import { runsForLine, tokenClasses } from './treesitter.js';
import type { Span } from './treesitter.js';

/**
 * All this needs a highlighter to be.
 *
 * Structural rather than the `Highlighter` class, so nothing here depends on
 * how the parsing is done — a plugin that computes spans some other way can
 * hand one over and be drawn.
 */
export interface LineSpans {
    spansForLine(line: number): Span[] | undefined;
}

/** Install a highlighter, or `null` to stop highlighting from a tree. */
export const setTreeHighlighter = StateEffect.define<LineSpans | null>();

/**
 * Which highlighter this document is drawn from.
 *
 * State rather than a field on the view, because the decorations are derived
 * from it and everything derived from state in CodeMirror 6 has to be reachable
 * from state — including on the update where it changes.
 */
export const treeHighlighter = StateField.define<LineSpans | null>({
    create: () => null,
    update(value, tr) {
        for (const effect of tr.effects) {
            if (effect.is(setTreeHighlighter)) return effect.value;
        }
        return value;
    }
});

/**
 * One `Decoration.mark` per distinct class string.
 *
 * A document has a few dozen distinct token styles and tens of thousands of
 * runs. CodeMirror 6 compares decorations by identity when it reconciles, so
 * reusing them is worth more than the allocation it saves.
 */
const marks = new Map<string, Decoration>();

function markFor(style: string): Decoration {
    const className = tokenClasses(style);
    let mark = marks.get(className);
    if (!mark) {
        mark = Decoration.mark({ class: className });
        marks.set(className, mark);
    }
    return mark;
}

/** The decorations for what is on screen, from the span table. */
function build(view: EditorView, highlighter: LineSpans | null): DecorationSet {
    if (!highlighter) return Decoration.none;
    const builder = new RangeSetBuilder<Decoration>();
    const doc = view.state.doc;
    for (const { from, to } of view.visibleRanges) {
        const last = doc.lineAt(to).number;
        for (let n = doc.lineAt(from).number; n <= last; n++) {
            const line = doc.line(n);
            // Lines are one-based here and zero-based in the span table, which
            // is CodeMirror 5's numbering and therefore Light Table's.
            const spans = highlighter.spansForLine(n - 1);
            if (!spans) continue;
            for (const run of runsForLine(spans, line.length)) {
                builder.add(line.from + run.from, line.from + run.to, markFor(run.style));
            }
        }
    }
    return builder.finish();
}

const drawTreeHighlight = ViewPlugin.fromClass(class {
    decorations: DecorationSet;

    constructor(view: EditorView) {
        this.decorations = build(view, view.state.field(treeHighlighter));
    }

    update(update: ViewUpdate): void {
        // The effect, and not only a changed field value: the reparse hands
        // back the same highlighter object every time — it is the same
        // document, incrementally reparsed — so the field is equal to itself
        // and only the effect says the spans underneath it are new.
        const reparsed = update.transactions.some(
            (tr) => tr.effects.some((e) => e.is(setTreeHighlighter)));
        if (reparsed || update.docChanged || update.viewportChanged) {
            this.decorations = build(update.view, update.state.field(treeHighlighter));
        }
    }
}, { decorations: (plugin) => plugin.decorations });

/** Everything an editor needs to be highlighted from a parse tree. */
export function treeHighlighting(): Extension {
    return [treeHighlighter, drawTreeHighlight];
}

declare global {
    interface Window { ltCm6Treesitter?: unknown }
}

window.ltCm6Treesitter = { setTreeHighlighter, treeHighlighter, treeHighlighting };
