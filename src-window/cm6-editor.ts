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
import type { Extension, Range, Text } from '@codemirror/state';
import { EditorView, keymap, Decoration, WidgetType } from '@codemirror/view';
import type { DecorationSet } from '@codemirror/view';
import {
    defaultKeymap, history, historyKeymap, historyField, undo, redo,
    lineComment, lineUncomment, blockComment, blockUncomment, indentSelection
} from '@codemirror/commands';
import { codeFolding, foldCode, unfoldCode, syntaxTree } from '@codemirror/language';
import { themeExtensions } from './cm6-theme.js';
import { Options, UNSUPPORTED } from './cm6-options.js';
import {
    search, SearchQuery, setSearchQuery, findNext, findPrevious,
    replaceNext, replaceAll, highlightSelectionMatches
} from '@codemirror/search';
import { modeExtension } from './cm6-modes.js';
import { runCommand, multipleSelections } from './cm6-commands.js';
import { bandField, setBands } from './cm6.js';
import type { Band } from './cm6.js';

/** A text mark or a line class, as CodeMirror 5 hands them out. */
interface Marker {
    id: number;
    from: number;
    to: number;
    /**
     * A mark decorates a range, a line class decorates the line it starts on,
     * and a point sits between two characters without covering either.
     *
     * A point is what CodeMirror 5 calls a bookmark, and it has to be its own
     * kind here rather than a mark of zero width: CodeMirror 6 rejects an empty
     * mark decoration outright, which is the difference between a bookmark that
     * does nothing and an editor that throws while drawing.
     */
    kind: 'mark' | 'line' | 'point';
    className: string;
    clear: () => void;
    find: () => { from: Pos, to: Pos } | null;
}

const addMarker = StateEffect.define<MarkerSpec>();
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
interface MarkerSpec {
    id: number;
    from: number;
    to: number;
    kind: 'mark' | 'line' | 'point';
    className: string;
}

interface MarkerState { specs: MarkerSpec[], decorations: DecorationSet }

/** The DOM for a bookmark: an empty span the caller can style or fill. */
class PointWidget extends WidgetType {
    constructor(readonly className: string) { super(); }
    override eq(other: PointWidget): boolean { return other.className === this.className; }
    override toDOM(): HTMLElement {
        const node = document.createElement('span');
        if (this.className) node.className = this.className;
        return node;
    }
}

function markerDecorations(specs: MarkerSpec[]): DecorationSet {
    const ranges: Range<Decoration>[] = [];
    for (const m of [...specs].sort((a, b) => a.from - b.from || a.to - b.to)) {
        if (m.kind === 'line') {
            ranges.push(Decoration.line({ class: m.className }).range(m.from));
        } else if (m.kind === 'point' || m.from === m.to) {
            // A mark that has become empty — its text was deleted — is a point
            // now. Drawing it as a mark would throw, and dropping it would take
            // the marker away from a caller still holding it.
            ranges.push(Decoration.widget({ widget: new PointWidget(m.className), side: 1 }).range(m.from));
        } else {
            ranges.push(Decoration.mark({ class: m.className }).range(m.from, m.to));
        }
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

/**
 * A field of a CodeMirror 5 position, as a number.
 *
 * CodeMirror 5 fills a missing field in as zero, and callers rely on it:
 * `{line: n}` with no `ch` is an ordinary way to name the start of a line, and
 * `lt.objs.eval` writes them. Left alone it arrives here as `undefined`,
 * arithmetic turns it into NaN, and NaN passes every range check — the failure
 * surfaces as a null dereference inside CodeMirror's range set, nowhere near
 * the caller that made it. Strings are coerced for the same reason: a line
 * number that came back from JSON is a string, and `'1' + 1` is `'11'`.
 */
const coord = (value: unknown): number => {
    const n = Math.trunc(Number(value));
    return Number.isFinite(n) ? n : 0;
};

/** CodeMirror 5's position: a zero-based line and a character within it. */
export interface Pos { line: number; ch: number }

/** Where an offset is, in a document that may not be the current one. */
const posIn = (doc: Text, offset: number): Pos => {
    const line = doc.lineAt(offset);
    return { line: line.number - 1, ch: offset - line.from };
};

interface LineHandle { line: number }

type Listener = (...args: unknown[]) => void;

export class Cm6Editor {
    readonly view: EditorView;
    private readonly listeners = new Map<string, Listener[]>();
    private readonly language = new Compartment();
    private readonly settings = new Options();
    private declared: Band[] = [];
    private widgets: Band[] = [];
    private widgetId = 0;
    private cleanAt = 0;
    private generation = 0;
    private markerId = 0;
    private extending = false;
    private query = '';

    constructor(parent: HTMLElement, doc: string, extensions: Extension[] = [],
                defaults: Record<string, unknown> = {}) {
        this.view = new EditorView({
            state: EditorState.create({
                doc,
                extensions: [
                    // Everything a *setting* controls, each in a compartment of
                    // its own so changing one keeps the document. The defaults
                    // are CodeMirror 5's, because a behavior that has not run
                    // yet should leave the editor where it was.
                    ...this.settings.initial({
                        lineNumbers: true,
                        cursorBlinkRate: 530,
                        tabSize: 4,
                        indentUnit: 2,
                        ...defaults
                    }),
                    history(),
                    // The query state and the match highlighting, without
                    // CodeMirror 6's own search panel: Light Table has a find
                    // bar of its own and two would be one too many.
                    search(),
                    highlightSelectionMatches(),
                    multipleSelections,
                    keymap.of([...defaultKeymap, ...historyKeymap]),
                    bandField,
                    markerField,
                    codeFolding(),
                    // Without this a language parses and nothing is coloured:
                    // CodeMirror 6 separates having a tree from drawing one,
                    // and the tree alone is invisible. What this supplies is
                    // CodeMirror 5's class names — `cm-keyword` and its
                    // twenty-odd neighbours — so the colours come from Light
                    // Table's themes, unedited. See cm6-theme.ts.
                    themeExtensions(),
                    this.language.of([]),
                    EditorView.updateListener.of((update) => {
                        if (update.docChanged) {
                            this.generation += 1;
                            // One event per change, carrying CodeMirror 5's
                            // shape. An empty object was not enough: the LSP
                            // document sync reads `change.from.line` to build
                            // an incremental `didChange`, and with nothing
                            // there it threw — the version still incremented,
                            // so the server stayed confident about a file it
                            // had the wrong text for.
                            //
                            // Positions are in the document as it was, which is
                            // what CodeMirror 5 reports. For a transaction
                            // carrying several changes the later ones are
                            // measured against that same starting document
                            // rather than against each other, so a multi-cursor
                            // edit is approximate here; every edit Light Table
                            // makes today is one change.
                            const before = update.startState.doc;
                            update.changes.iterChanges((fromA, toA, _fromB, _toB, inserted) => {
                                this.emit('change', this, {
                                    from: posIn(before, fromA),
                                    to: posIn(before, toA),
                                    text: inserted.toJSON(),
                                    removed: before.slice(fromA, toA).toJSON(),
                                    origin: '+input'
                                });
                            });
                        }
                        if (update.selectionSet) this.emit('cursorActivity', this);
                        // Focus is not a nicety. `lt.ui.window/::sync-from-objects`
                        // runs on it, so without it the state atom never learns
                        // that a file is open — the window renders from a
                        // projection that nothing refreshed, and every symptom
                        // is somewhere other than here.
                        if (update.focusChanged) {
                            this.emit(this.view.hasFocus ? 'focus' : 'blur', this);
                        }
                    }),
                    EditorView.domEventHandlers({
                        scroll: () => { this.emit('scroll', this); }
                    }),
                    ...extensions
                ]
            }),
            parent
        });
        this.setOption('theme', 'default');
    }

    // --- positions ---------------------------------------------------------

    /** CodeMirror 5 talks in {line, ch}; CodeMirror 6 talks in offsets. */
    private offset(pos: Pos): number {
        const lines = this.view.state.doc.lines;
        const n = Math.min(Math.max(coord(pos?.line) + 1, 1), lines);
        const line = this.view.state.doc.line(n);
        return Math.min(line.from + Math.max(coord(pos?.ch), 0), line.to);
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

    /** Every selection, as CodeMirror 5's `{anchor, head}` pairs. */
    listSelections(): { anchor: Pos, head: Pos }[] {
        return this.view.state.selection.ranges.map((r) => ({
            anchor: this.position(r.anchor),
            head: this.position(r.head)
        }));
    }

    setSelections(selections: { anchor: Pos, head?: Pos }[], primary?: number): void {
        if (!selections.length) return;
        this.view.dispatch({
            selection: EditorSelection.create(
                selections.map((s) => EditorSelection.range(
                    this.offset(s.anchor), this.offset(s.head ?? s.anchor))),
                primary ?? selections.length - 1),
            scrollIntoView: true
        });
    }

    /**
     * Replace each selection with the matching entry of `texts`.
     *
     * One string per selection, in the order `listSelections` gave them — which
     * is what makes multiple cursors worth having rather than a way to select
     * several things and then edit one.
     */
    replaceSelections(texts: string[]): void {
        const ranges = this.view.state.selection.ranges;
        this.view.dispatch(this.view.state.changeByRange((range) => {
            const at = ranges.indexOf(range);
            const insert = texts[at] ?? texts[texts.length - 1] ?? '';
            return {
                changes: { from: range.from, to: range.to, insert },
                range: EditorSelection.cursor(range.from + insert.length)
            };
        }));
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

    /** Where the editor is scrolled to, and how much there is. */
    getScrollInfo(): { left: number, top: number, width: number, height: number,
                       clientWidth: number, clientHeight: number } {
        const el = this.view.scrollDOM;
        return {
            left: el.scrollLeft, top: el.scrollTop,
            width: el.scrollWidth, height: el.scrollHeight,
            clientWidth: el.clientWidth, clientHeight: el.clientHeight
        };
    }

    /**
     * CodeMirror 5 batched DOM work inside `operation`. CodeMirror 6 batches by
     * transaction and there is nothing to open or close, so this is the
     * function called — which is the correct translation rather than a stub.
     */
    operation<T>(f: () => T): T { return f(); }

    getOption(name: string): unknown { return this.settings.values[name]; }

    /** Options that were set but do nothing here. For asking, and for a test. */
    inertOptions(): string[] {
        return Object.keys(this.settings.values)
            .filter((k) => k in UNSUPPORTED)
            .sort();
    }

    /**
     * `mode` and `mime` reconfigure the language; everything else is recorded.
     *
     * A compartment is CodeMirror 6's way of swapping one part of a
     * configuration without rebuilding the state — so changing the mode keeps
     * the document, the history and the selection, which is what
     * `lt.objs.editor/set-mode` has always implied and CodeMirror 5 did by
     * mutating in place.
     */
    setOption(name: string, value: unknown): void {
        this.settings.set(this.view, name, value);
        if (name === 'theme') {
            // CodeMirror 5 scopes a theme by putting `cm-s-<name>` on the
            // wrapper, and every theme file is written `.cm-s-monokai .cm-keyword`.
            // Same element, same class, so the same rules apply.
            for (const c of Array.from(this.view.dom.classList)) {
                if (c.startsWith('cm-s-')) this.view.dom.classList.remove(c);
            }
            for (const part of String(value ?? 'default').split(' ')) {
                if (part) this.view.dom.classList.add('cm-s-' + part);
            }
        }
        if (name === 'mode' || name === 'mime') {
            this.view.dispatch({
                effects: this.language.reconfigure(modeExtension(String(value ?? '')))
            });
        }
    }

    // --- bands -------------------------------------------------------------

    /**
     * Declare the whole set of bands. The declarative path, and the reason for
     * the port.
     *
     * `lt.ui.bands` computes what should be under which line from `lt.state` and
     * hands the answer over; what is on screen and no longer wanted goes away
     * because it is absent from the new set, not because anyone removed it.
     * That is a table of drawn widgets, an orphan sweep and a
     * close-the-editor cleanup that stop existing — see `lt.objs.editor.bands`,
     * where the CodeMirror 5 half still has all three.
     */
    setBands(bands: Band[]): void {
        this.declared = bands;
        this.pushBands();
    }

    /**
     * A line widget, the CodeMirror 5 way.
     *
     * This is not a leftover. `line-widget` is published plugin API — the
     * Clojure plugin's collapsible exception uses it, as do `lt.objs.eval` and
     * the LSP diagnostics — so an editor that could not do this would break
     * plugins that are not ours to rewrite. It keeps its own list, and the two
     * are merged rather than one overwriting the other.
     */
    addLineWidget(line: number, node: HTMLElement): Band {
        const band: Band = {
            line,
            key: `widget-${++this.widgetId}`,
            // The node is the identity: handed the same one twice, nothing
            // happens, which is what an imperative widget nobody redeclared
            // should cost.
            content: node,
            mount: (n) => n.appendChild(node)
        };
        this.widgets = [...this.widgets, band];
        this.pushBands();
        return band;
    }

    removeLineWidget(widget: Band): void {
        this.widgets = this.widgets.filter((w) => w !== widget);
        this.pushBands();
    }

    /** Both sets, as the one thing the field holds. */
    private pushBands(): void {
        this.view.dispatch({ effects: setBands.of([...this.declared, ...this.widgets]) });
    }

    // --- search -------------------------------------------------------------

    /**
     * Set the query and move to the first match, which is what CodeMirror 5's
     * `find` command does in one call.
     *
     * The case rule is CodeMirror 5's and worth keeping: a query typed in lower
     * case is case-insensitive, and typing a capital makes it matter. It is the
     * behaviour people expect without being told, which is why nobody notices
     * it until it is gone.
     */
    search(query: string, reverse = false): boolean {
        this.query = query;
        this.setQuery();
        return query ? this.findNext(reverse) : false;
    }

    /** The query as CodeMirror 6 wants it, with an optional replacement. */
    private setQuery(replacement = ''): void {
        this.view.dispatch({
            effects: setSearchQuery.of(new SearchQuery({
                search: this.query,
                caseSensitive: this.query !== this.query.toLowerCase(),
                replace: replacement
            }))
        });
    }

    findNext(reverse = false): boolean {
        return reverse ? findPrevious(this.view) : findNext(this.view);
    }

    findPrev(): boolean { return this.findNext(true); }

    /** Forget the query, which takes the highlighting with it. */
    clearSearch(): void {
        this.query = '';
        this.setQuery();
    }

    replace(text: string, _reverse = false, all = false): boolean {
        this.setQuery(text);
        return all ? replaceAll(this.view) : replaceNext(this.view);
    }

    /** What is being searched for, or the empty string. */
    searchQuery(): string { return this.query; }

    /**
     * Run a CodeMirror 5 command by name, if there is one that means the same.
     *
     * False when there is not — which the caller turns into a passthrough, the
     * same answer CodeMirror 5 gives with `CodeMirror.Pass`. The table is
     * `cm6-commands.ts`; what is *not* in it is every command the CodeMirror 5
     * addons registered, the sublime keymap most of all.
     */
    execCommand(name: string): boolean {
        return runCommand(name, this.view);
    }

    /**
     * The declared bands that are actually drawn, as keys.
     *
     * Declared and drawn are not the same set — a band whose line is past the
     * end of the document is dropped — and this reports the second, because
     * that is the question anyone asking has.
     */
    bandKeys(): string[] {
        const declared = new Set(this.declared.map((b) => b.key));
        const out: string[] = [];
        const doc = this.view.state.doc;
        this.view.state.field(bandField).decorations.between(0, doc.length, (_from, _to, deco) => {
            const key = (deco.spec.widget as { key?: string }).key;
            if (key !== undefined && declared.has(key)) out.push(key);
        });
        return out;
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

    private marker(from: number, to: number, kind: 'mark' | 'line' | 'point', className: string): Marker {
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
        return this.marker(at, at, 'point', options.className ?? '');
    }

    findMarksAt(pos: Pos): Marker[] {
        const at = this.offset(pos);
        return this.view.state.field(markerField).specs
            .filter((m) => m.kind !== 'line' && m.from <= at && at <= m.to)
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
    getMode(): { name: string } { return { name: String(this.getOption('mode') ?? 'null') }; }
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
