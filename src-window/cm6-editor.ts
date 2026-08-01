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

import { EditorState, EditorSelection, Compartment } from '@codemirror/state';
import type { Extension } from '@codemirror/state';
import { EditorView, lineNumbers, keymap, drawSelection } from '@codemirror/view';
import { defaultKeymap, history, historyKeymap, undo, redo } from '@codemirror/commands';
import { bandField, setBands } from './cm6.js';
import type { Band } from './cm6.js';

/** CodeMirror 5's position: a zero-based line and a character within it. */
export interface Pos { line: number; ch: number }

interface LineHandle { line: number }

type Listener = (...args: unknown[]) => void;

const NOT_YET = (name: string): never => {
    throw new Error(
        `CodeMirror 6: ${name} is not implemented yet. ` +
        'See src-window/cm6-editor.ts — the gap is deliberate and named.');
};

export class Cm6Editor {
    readonly view: EditorView;
    private readonly listeners = new Map<string, Listener[]>();
    private readonly language = new Compartment();
    private readonly options: Record<string, unknown> = {};
    private widgets: Band[] = [];
    private cleanAt = 0;
    private generation = 0;

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

    // --- not yet -----------------------------------------------------------

    getDoc(): Cm6Editor { return this; }
    getMode(): { name: string } { return { name: String(this.options['mode'] ?? 'null') }; }

    getTokenAt(): never { return NOT_YET('getTokenAt'); }
    getTokenTypeAt(): never { return NOT_YET('getTokenTypeAt'); }
    markText(): never { return NOT_YET('markText'); }
    setBookmark(): never { return NOT_YET('setBookmark'); }
    findMarksAt(): never { return NOT_YET('findMarksAt'); }
    foldCode(): never { return NOT_YET('foldCode'); }
    lineComment(): never { return NOT_YET('lineComment'); }
    blockComment(): never { return NOT_YET('blockComment'); }
    uncomment(): never { return NOT_YET('uncomment'); }
    indentLine(): never { return NOT_YET('indentLine'); }
    indentSelection(): never { return NOT_YET('indentSelection'); }
    addLineClass(): never { return NOT_YET('addLineClass'); }
    removeLineClass(): never { return NOT_YET('removeLineClass'); }
    charCoords(): never { return NOT_YET('charCoords'); }
    scrollTo(): never { return NOT_YET('scrollTo'); }
    swapDoc(): never { return NOT_YET('swapDoc'); }
    getHistory(): never { return NOT_YET('getHistory'); }
    setHistory(): never { return NOT_YET('setHistory'); }
    setExtending(): never { return NOT_YET('setExtending'); }
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
