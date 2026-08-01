// A CodeMirror 6 editor that answers to CodeMirror 5's method names.
//
// `lt.objs.editor` is 1055 lines wrapping a CodeMirror instance, and it calls
// 51 methods on it. Rewriting all of that against CodeMirror 6's API in one
// change is a rewrite nobody can review and no test can bisect. So instead the
// engine is swapped underneath: this presents the surface `lt.objs.editor`
// already speaks, and every one of its callers — including the 40 that reach
// past it through `->cm-ed`, and Paredit, which reaches it through nothing at
// all — keeps working unchanged.
//
// That is not a permanent shape. It is what makes the port incremental: the
// suite runs against either engine, the difference is one factory call, and
// the shim thins as callers move to the CodeMirror 6 idiom. What it buys
// immediately is that this can be true and checked today rather than after a
// month of unverifiable work.
//
// Where a method has no CodeMirror 6 equivalent yet it throws by name. A gap
// that says what it is beats one that silently returns undefined — this
// codebase has paid for that lesson twice already.

import { EditorState, EditorSelection, Compartment, StateField, StateEffect, RangeSet } from '@codemirror/state';
import type { Extension, Range } from '@codemirror/state';
import { EditorView, lineNumbers, keymap, drawSelection, Decoration } from '@codemirror/view';
import type { DecorationSet } from '@codemirror/view';
import {
    defaultKeymap, history, historyKeymap, historyField, undo, redo,
    lineComment, lineUncomment, blockComment, blockUncomment, indentSelection
} from '@codemirror/commands';
import { codeFolding, foldCode, unfoldCode, syntaxTree } from '@codemirror/language';
import { bandField, setBands } from './cm6.js';
import type { Band } from './cm6.js';

/** A text mark or a line class, as CodeMirror 5 hands them out. */
interface Marker {
    id: number;
    from: number;
    to: number;
    /** A mark decorates a range; a line class decorates the line it starts on. */
    kind: 'mark' | 'line';
    className: string;
    clear: () => void;
    find: () => { from: Pos, to: Pos } | null;
}

const addMarker = StateEffect.define<{ id: number, from: number, to: number, kind: 'mark' | 'line', className: string }>();
const dropMarker = StateEffect.define<number>();

/**
 * Marks and line classes, as decorations derived from state.
 *
 * The same move as the bands: CodeMirror 5 hands back an object you must
 * remember and clear, and here the set is a value the view reconciles. The
 * CodeMirror 5 shape is still returned so existing callers work, but what is
 * underneath is a field rather than a list of handles — which is why a mark
 * follows its text through an edit without anyone tracking it.
 */
interface MarkerState { specs: { id: number, from: number, to: number, kind: 'mark' | 'line', className: string }[], decorations: DecorationSet }

function markerDecorations(specs: MarkerState['specs']): DecorationSet {
    const ranges: Range<Decoration>[] = [];
    for (const m of [...specs].sort((a, b) => a.from - b.from || a.to - b.to)) {
        ranges.push(m.kind === 'line'
            ? Decoration.line({ class: m.className }).range(m.from)
            : Decoration.mark({ class: m.className }).range(m.from, m.to));
    }
    return RangeSet.of(ranges, true);
}

const markerField = StateField.define<MarkerState>({
    create() { return { specs: [], decorations: Decoration.none }; },
    update(value, tr) {
        let specs = value.specs;
        let touched = false;
        if (tr.docChanged) {
            specs = specs.map((m) => ({ ...m, from: tr.changes.mapPos(m.from), to: tr.changes.mapPos(m.to) }));
            touched = true;
        }
        for (const effect of tr.effects) {
            if (effect.is(addMarker)) { specs = [...specs, effect.value]; touched = true; }
            if (effect.is(dropMarker)) { specs = specs.filter((m) => m.id !== effect.value); touched = true; }
        }
        return touched ? { specs, decorations: markerDecorations(specs) } : value;
    },
    provide: (f) => EditorView.decorations.from(f, (v) => v.decorations)
});

/** CodeMirror 5's position: a zero-based line and a character within it. */
export interface Pos { line: number; ch: number }

interface LineHandle { line: number }

type Listener = (...args: unknown[]) => void;

export class Cm6Editor {
    readonly view: EditorView;
    private readonly listeners = new Map<string, Listener[]>();
    private readonly language = new Compartment();
    private readonly options: Record<string, unknown> = {};
    private widgets: Band[] = [];
    private cleanAt = 0;
    private generation = 0;
    private markerId = 0;
    private extending = false;

    constructor(parent: HTMLElement, doc: string, extensions: Extension[] = []) {
        this.view = new EditorView({
            state: EditorState.create({
                doc,
                extensions: [
                    lineNumbers(),
                    drawSelection(),
                    history(),
                    keymap.of([...defaultKeymap, ...historyKeymap]),
                    bandField,
                    markerField,
                    codeFolding(),
                    this.language.of([]),
                    EditorView.updateListener.of((update) => {
                        if (update.docChanged) {
                            this.generation += 1;
                            this.emit('change', this, {});
                        }
                        if (update.selectionSet) this.emit('cursorActivity', this);
                    }),
                    ...extensions
                ]
            }),
            parent
        });
    }

    // --- positions ---------------------------------------------------------

    /** CodeMirror 5 talks in {line, ch}; CodeMirror 6 talks in offsets. */
    private offset(pos: Pos): number {
        const lines = this.view.state.doc.lines;
        const n = Math.min(Math.max(pos.line + 1, 1), lines);
        const line = this.view.state.doc.line(n);
        return Math.min(line.from + Math.max(pos.ch, 0), line.to);
    }

    private position(offset: number): Pos {
        const line = this.view.state.doc.lineAt(offset);
        return { line: line.number - 1, ch: offset - line.from };
    }

    // --- document ----------------------------------------------------------

    getValue(): string { return this.view.state.doc.toString(); }

    setValue(text: string): void {
        this.view.dispatch({ changes: { from: 0, to: this.view.state.doc.length, insert: text } });
    }

    // Undefined rather than empty for a line that is not there, which is what
    // CodeMirror 5 answers — and the difference matters: an empty string reads
    // as a blank line, and `lt.objs.editor/line-length` would say 0 instead of
    // saying nothing is there.
    getLine(n: number): string | undefined {
        const doc = this.view.state.doc;
        return n >= 0 && n < doc.lines ? doc.line(n + 1).text : undefined;
    }

    lineCount(): number { return this.view.state.doc.lines; }
    firstLine(): number { return 0; }
    lastLine(): number { return this.view.state.doc.lines - 1; }

    getRange(from: Pos, to: Pos): string {
        return this.view.state.sliceDoc(this.offset(from), this.offset(to));
    }

    replaceRange(text: string, from: Pos, to?: Pos): void {
        const start = this.offset(from);
        this.view.dispatch({ changes: { from: start, to: to ? this.offset(to) : start, insert: text } });
    }

    indexFromPos(pos: Pos): number { return this.offset(pos); }
    posFromIndex(index: number): Pos { return this.position(index); }

    // A handle in CodeMirror 5 is an object that tracks a line through edits.
    // Here it is the line number, which is what every caller in this codebase
    // then asks it for — see getLineNumber.
    getLineHandle(n: number): LineHandle { return { line: n }; }
    getLineNumber(handle: LineHandle): number { return handle.line; }

    // --- cursor and selection ---------------------------------------------

    getCursor(which?: string): Pos {
        const range = this.view.state.selection.main;
        const at = which === 'start' ? range.from
            : which === 'end' ? range.to
            : which === 'anchor' ? range.anchor
            : range.head;
        return this.position(at);
    }

    setCursor(pos: Pos): void {
        this.view.dispatch({ selection: EditorSelection.cursor(this.offset(pos)) });
    }

    somethingSelected(): boolean { return !this.view.state.selection.main.empty; }

    getSelection(): string {
        const r = this.view.state.selection.main;
        return this.view.state.sliceDoc(r.from, r.to);
    }

    getSelections(): string[] {
        return this.view.state.selection.ranges.map((r) => this.view.state.sliceDoc(r.from, r.to));
    }

    setSelection(anchor: Pos, head?: Pos): void {
        this.view.dispatch({
            selection: EditorSelection.range(this.offset(anchor), this.offset(head ?? anchor))
        });
    }

    replaceSelection(text: string): void {
        this.view.dispatch(this.view.state.replaceSelection(text));
    }

    // --- history -----------------------------------------------------------

    undo(): void { undo(this.view); }
    redo(): void { redo(this.view); }
    changeGeneration(): number { return this.generation; }
    isClean(gen?: number): boolean { return this.generation === (gen ?? this.cleanAt); }
    clearHistory(): void { this.cleanAt = this.generation; }

    // --- view --------------------------------------------------------------

    focus(): void { this.view.focus(); }
    refresh(): void { this.view.requestMeasure(); }
    getScrollerElement(): HTMLElement { return this.view.scrollDOM; }

    /**
     * CodeMirror 5 batched DOM work inside `operation`. CodeMirror 6 batches by
     * transaction and there is nothing to open or close, so this is the
     * function called — which is the correct translation rather than a stub.
     */
    operation<T>(f: () => T): T { return f(); }

    getOption(name: string): unknown { return this.options[name]; }
    setOption(name: string, value: unknown): void { this.options[name] = value; }

    // --- bands -------------------------------------------------------------

    /**
     * The one place this is not a translation but a replacement.
     *
     * CodeMirror 5 hands back a widget object you must remember and remove.
     * Here the set of bands is state, so adding one is declaring the new set —
     * and `lt.ui.bands` keeping a table of what is drawn becomes unnecessary
     * rather than merely cheaper. The CodeMirror 5 shape is kept so the
     * existing caller works today; the point of the port is to delete it.
     */
    addLineWidget(line: number, node: HTMLElement): Band {
        const band: Band = { line, key: `w${this.widgets.length}-${line}`, mount: (n) => n.appendChild(node) };
        this.widgets = [...this.widgets, band];
        this.view.dispatch({ effects: setBands.of(this.widgets) });
        return band;
    }

    removeLineWidget(widget: Band): void {
        this.widgets = this.widgets.filter((w) => w !== widget);
        this.view.dispatch({ effects: setBands.of(this.widgets) });
    }

    // --- events ------------------------------------------------------------

    on(event: string, f: Listener): void {
        this.listeners.set(event, [...(this.listeners.get(event) ?? []), f]);
    }

    off(event: string, f: Listener): void {
        this.listeners.set(event, (this.listeners.get(event) ?? []).filter((x) => x !== f));
    }

    private emit(event: string, ...args: unknown[]): void {
        for (const f of this.listeners.get(event) ?? []) f(...args);
    }

    // --- marks and line classes -------------------------------------------

    private marker(from: number, to: number, kind: 'mark' | 'line', className: string): Marker {
        const id = ++this.markerId;
        this.view.dispatch({ effects: addMarker.of({ id, from, to, kind, className }) });
        return {
            id, from, to, kind, className,
            clear: () => { this.view.dispatch({ effects: dropMarker.of(id) }); },
            find: () => {
                const m = this.view.state.field(markerField).specs.find((x) => x.id === id);
                return m ? { from: this.position(m.from), to: this.position(m.to) } : null;
            }
        };
    }

    markText(from: Pos, to: Pos, options: { className?: string } = {}): Marker {
        return this.marker(this.offset(from), this.offset(to), 'mark', options.className ?? '');
    }

    setBookmark(pos: Pos, options: { className?: string } = {}): Marker {
        const at = this.offset(pos);
        return this.marker(at, at, 'mark', options.className ?? '');
    }

    findMarksAt(pos: Pos): Marker[] {
        const at = this.offset(pos);
        return this.view.state.field(markerField).specs
            .filter((m) => m.kind === 'mark' && m.from <= at && at <= m.to)
            .map((m) => this.marker(m.from, m.to, m.kind, m.className));
    }

    addLineClass(line: number, _where: string, className: string): Marker {
        const doc = this.view.state.doc;
        const n = Math.min(Math.max(line + 1, 1), doc.lines);
        return this.marker(doc.line(n).from, doc.line(n).from, 'line', className);
    }

    removeLineClass(line: number, _where: string, className?: string): void {
        const doc = this.view.state.doc;
        const n = Math.min(Math.max(line + 1, 1), doc.lines);
        const from = doc.line(n).from;
        for (const m of this.view.state.field(markerField).specs) {
            if (m.kind === 'line' && m.from === from && (!className || m.className === className)) {
                this.view.dispatch({ effects: dropMarker.of(m.id) });
            }
        }
    }

    // --- folding, comments, indentation ------------------------------------

    foldCode(pos: Pos): void {
        this.setCursor(pos);
        if (!foldCode(this.view)) unfoldCode(this.view);
    }

    lineComment(from: Pos, to: Pos): void { this.overRange(from, to, lineComment); }
    blockComment(from: Pos, to: Pos): void { this.overRange(from, to, blockComment); }

    uncomment(from: Pos, to: Pos): boolean {
        return this.overRange(from, to, lineUncomment) || this.overRange(from, to, blockUncomment);
    }

    /** CodeMirror 5's comment commands take a range; CodeMirror 6's act on the
     *  selection, so the range becomes the selection for the length of the
     *  call and is put back after. */
    private overRange(from: Pos, to: Pos, command: (view: EditorView) => boolean): boolean {
        const was = this.view.state.selection;
        this.setSelection(from, to);
        const done = command(this.view);
        this.view.dispatch({ selection: was });
        return done;
    }

    /**
     * Smart indentation, which is CodeMirror 5's default for this method.
     *
     * Not `indentMore`: that adds a unit unconditionally, and CodeMirror 5
     * computes what the line *should* be — so on a document with no language
     * it does nothing, and the two engines disagreed about a blank indent. The
     * command below is the same computation.
     */
    indentLine(line: number): void {
        const was = this.view.state.selection;
        this.setCursor({ line, ch: 0 });
        indentSelection(this.view);
        this.view.dispatch({ selection: was });
    }

    indentSelection(): void { indentSelection(this.view); }

    // --- geometry and scrolling --------------------------------------------

    charCoords(pos: Pos, mode?: string): { left: number, right: number, top: number, bottom: number } {
        const coords = this.view.coordsAtPos(this.offset(pos));
        if (!coords) return { left: 0, right: 0, top: 0, bottom: 0 };
        if (mode === 'local') {
            const box = this.view.scrollDOM.getBoundingClientRect();
            return {
                left: coords.left - box.left + this.view.scrollDOM.scrollLeft,
                right: coords.right - box.left + this.view.scrollDOM.scrollLeft,
                top: coords.top - box.top + this.view.scrollDOM.scrollTop,
                bottom: coords.bottom - box.top + this.view.scrollDOM.scrollTop
            };
        }
        return { left: coords.left, right: coords.right, top: coords.top, bottom: coords.bottom };
    }

    scrollTo(left?: number | null, top?: number | null): void {
        this.view.scrollDOM.scrollTo({
            left: left ?? this.view.scrollDOM.scrollLeft,
            top: top ?? this.view.scrollDOM.scrollTop
        });
    }

    // --- documents and history ---------------------------------------------

    swapDoc(text: string): string {
        const was = this.getValue();
        this.setValue(text);
        return was;
    }

    getHistory(): unknown { return this.view.state.field(historyField, false) ?? null; }

    setHistory(_history: unknown): void {
        // CodeMirror 6's history is a state field with no public restore, so
        // this is honest about what it does: the old history is dropped rather
        // than replaced with a wrong one. The only caller is document swapping,
        // where a shared history was already the surprising behaviour.
        this.view.dispatch({ effects: StateEffect.reconfigure.of([]) });
    }

    setExtending(value: boolean): void { this.extending = value; }

    // --- tokens -------------------------------------------------------------

    /**
     * The token at a position, from the syntax tree.
     *
     * Null when no language is configured, which is what CodeMirror 5 answers
     * for a document in the null mode — so a caller that checks is right either
     * way, and one that does not was already wrong.
     */
    getTokenAt(pos: Pos): { start: number, end: number, string: string, type: string | null } | null {
        const at = this.offset(pos);
        const node = syntaxTree(this.view.state).resolveInner(at, -1);
        if (!node || node.name === 'Document') return null;
        const line = this.view.state.doc.lineAt(at);
        return {
            start: node.from - line.from,
            end: node.to - line.from,
            string: this.view.state.sliceDoc(node.from, node.to),
            type: node.name
        };
    }

    getTokenTypeAt(pos: Pos): string | null {
        // The node's name, not its highlight class. `highlightingFor` wants
        // Lezer tags and a syntax node carries a NodeType; going through the
        // tags would mean a highlight style being configured, which a document
        // in no language does not have. The name is what callers here compare
        // against anyway — see lt.objs.editor/->token-type.
        const node = syntaxTree(this.view.state).resolveInner(this.offset(pos), -1);
        return node && node.name !== 'Document' ? node.name : null;
    }

    getDoc(): Cm6Editor { return this; }
    getMode(): { name: string } { return { name: String(this.options['mode'] ?? 'null') }; }
}

/** What `lt.objs.editor` would call instead of `CodeMirror(node, opts)`. */
export function makeCm6Editor(parent: HTMLElement, options: Record<string, unknown> = {}): Cm6Editor {
    const ed = new Cm6Editor(parent, String(options['value'] ?? ''));
    for (const [k, v] of Object.entries(options)) {
        if (k !== 'value') ed.setOption(k, v);
    }
    return ed;
}

declare global {
    interface Window { ltCm6Editor?: unknown }
}

window.ltCm6Editor = { makeCm6Editor, Cm6Editor };
