// CodeMirror 5, as much of it as Light Table's own additions use.
//
// CodeMirror 5 ships no type definitions, so this describes the surface these
// files actually touch rather than the library. That is deliberate: a partial
// description that is true is worth more than a complete one that is guessed,
// and it grows as more of Light Table moves into TypeScript.
//
// Anything absent here is not "untyped" — it is unused. If you reach for
// something new, describe it here rather than casting past it.

/** A place in a document. */
interface CMPosition {
    line: number;
    ch: number;
}

/** Screen coordinates of a position, as `cursorCoords` returns them. */
interface CMCoords {
    left: number;
    right: number;
    top: number;
    bottom: number;
}

/** A match, walked forwards or backwards. Created by `getSearchCursor`. */
interface CMSearchCursor {
    /** Advance to the next match; `reverse` searches backwards. */
    find(reverse?: boolean): boolean;
    /** As `find`, but always from the current position. */
    findNext(reverse?: boolean): boolean;
    from(): CMPosition;
    to(): CMPosition;
    replace(text: string): void;
}

/** A range highlight, as `markText` returns. */
interface CMTextMarker {
    clear(): void;
}

interface CMStream {
    /** Consume `pattern` if it comes next, and say whether it did. */
    match(pattern: string | RegExp, consume?: boolean, caseFold?: boolean): boolean | RegExpMatchArray | null;
    next(): string | undefined;
    /** True at the end of the line. */
    eol(): boolean;
    skipTo(ch: string): boolean | undefined;
    skipToEnd(): void;
    /** The whole line being tokenized. */
    readonly string: string;
    /** How far into it the tokenizer has got. Writable: a mode consumes by moving it. */
    pos: number;
    /**
     * CodeMirror's own Context for this line, carrying the line number.
     *
     * Undocumented but stable, and the only way a mode can learn where it is —
     * a counter kept in mode state does not survive CodeMirror restarting the
     * mode from a cached checkpoint. src-window/treesitter.ts depends on it.
     */
    readonly lineOracle?: { line: number };
}

/** A mode: something that tokenizes a line at a time. */
interface CMMode<S> {
    startState?(): S;
    copyState?(state: S): S;
    token(stream: CMStream, state: S): string | null | undefined;
    blankLine?(state: S): void;
}

/**
 * A mode painted on top of the document's own — used here to highlight every
 * match while a search is running.
 */
interface CMOverlay {
    /** A style name, or nothing when the token has no style. */
    token(stream: CMStream): string | null | undefined;
}

interface CMEditor {
    /** Batch changes so the editor redraws once. */
    operation<T>(fn: () => T): T;
    getCursor(which?: 'from' | 'to' | 'head' | 'anchor'): CMPosition;
    setCursor(pos: CMPosition): void;
    setSelection(anchor: CMPosition, head?: CMPosition): void;
    getRange(from: CMPosition, to: CMPosition): string;
    lineCount(): number;
    cursorCoords(pos?: CMPosition | boolean, mode?: string): CMCoords;
    markText(from: CMPosition, to: CMPosition, options?: { className?: string }): CMTextMarker;
    addOverlay(overlay: CMOverlay): void;
    /** Removing one that was never added is a no-op, so null is allowed. */
    removeOverlay(overlay: CMOverlay | null): void;
    /** From the searchcursor addon, which Light Table requires separately. */
    getSearchCursor(query: string | RegExp, pos?: CMPosition | null, caseFold?: boolean): CMSearchCursor;
    /**
     * Where cm-search.ts keeps its per-editor state. Light Table's own
     * property, not CodeMirror's, and opaque outside that file — the class
     * describing it lives inside cm-search.ts's own scope.
     */
    _searchState?: unknown;
}

/** Commands are reachable by name, which is how Light Table invokes them. */
interface CMCommands {
    [name: string]: (cm: CMEditor, ...args: any[]) => unknown;
}

interface CMStatic {
    Pos(line: number, ch?: number): CMPosition;
    commands: CMCommands;
    /** Set by cm-hint.ts: places the completion list near the cursor. */
    positionHint?(cm: CMEditor, hints: HTMLElement, from: CMPosition): void;
    /** Set by cm-hint.ts: scrolls the selected completion into view. */
    ensureHintVisible?(cm: CMEditor, hints: HTMLElement, node: HTMLElement): void;
}

/**
 * Imported rather than taken from the global. Light Table publishes
 * `window.CodeMirror` from lt.objs.editor, but these files may load first —
 * and did, which is how this was found. Importing gets the same bundled
 * instance regardless of order.
 */
declare module 'codemirror' {
    const CodeMirror: CMStatic;
    export = CodeMirror;
}

/**
 * web-tree-sitter's type definitions reference `EmscriptenModule`, which comes
 * from `@types/emscripten`. Nothing here uses the type — it appears once, in a
 * field this code never touches — so it is declared rather than depended on.
 */
declare type EmscriptenModule = Record<string, unknown>;
