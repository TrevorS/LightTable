// Tree-sitter powered syntax highlighting.
//
// The parse and the per-line span table it produces belong to no editor. The
// drawing does: `runsForLine` turns the table into styled runs, and
// cm6-treesitter.ts turns those into decorations. That separation is what let
// one engine be swapped for another underneath it without the parsing noticing.
//
// Why this exists rather than more CodeMirror modes: a mode is a per-line state
// machine that knows `foo` is an identifier and cannot know whether it is a
// parameter, a call, a type or a local. Measured on a realistic TypeScript
// file, CodeMirror's javascript mode emits seven token types. A tree-sitter
// highlight query over the same code produces a named capture per node —
// `function`, `variable.parameter`, `type`, `constructor`, `keyword.control` —
// which is both far more to colour and, more usefully, a *standard vocabulary*.
// Helix, Neovim and Zed themes are written against those same names, so a theme
// becomes a stylesheet rather than a port.
//
// The three things that made this look impractical, and what each turned out to
// be:
//
//   Loading WebAssembly with no Node and no fetch(file://). Both the runtime and
//   each grammar load from bytes — `Parser.init({instantiateWasm})` and
//   `Language.load(uint8array)` — and `lt.util.bridge.files.readFileBytesSync`
//   exists to supply them.
//
//   Cost. Measured: 4.8ms to parse a small file cold, 0.2ms to re-parse after a
//   one-character edit. That is a per-keystroke budget, which is what makes
//   this viable at all.
//
// Nothing here parses per line. A document is parsed once per change, into a
// table of spans; drawing a line is a lookup in it.

import type { Language, Parser as ParserType, Tree, QueryCapture } from 'web-tree-sitter';

/** One highlighted run within a line, in columns. */
export interface Span {
    from: number;
    to: number;
    /** Space-separated CodeMirror token classes, without the `cm-` prefix. */
    style: string;
}

/** One top-level form, in editor coordinates. */
export interface FormRange {
    startLine: number;
    startCh: number;
    endLine: number;
    endCh: number;
    /** The grammar's name for the node — `list_lit`, `comment`, and so on. */
    type: string;
}

/** Reads a file as bytes. Supplied by the caller so this module needs no bridge. */
export type ByteReader = (path: string) => Uint8Array;

export interface GrammarSpec {
    /** Capture-name vocabulary source, e.g. the contents of highlights.scm. */
    query: string;
    /** Path to the grammar's .wasm. */
    wasm: string;
}

/**
 * A capture name becomes every prefix of itself, so a theme can style broadly
 * or precisely and both work:
 *
 *     variable.parameter  ->  "ts-variable ts-variable-parameter"
 *
 * That cascade is the point. A theme that only knows `@variable` still colours
 * parameters; one that wants parameters dimmer says so, and wins by being more
 * specific. It is also why capture names are worth more than CodeMirror's flat
 * token list — they are a hierarchy other editors already agree on.
 */
export function captureClasses(name: string): string {
    const parts = name.split('.');
    const out: string[] = [];
    for (let i = 0; i < parts.length; i++) {
        out.push('ts-' + parts.slice(0, i + 1).join('-'));
    }
    return out.join(' ');
}

/**
 * How many distinct bracket depths get their own class before they repeat.
 *
 * Six, because the point is telling *this* bracket from the one outside it, and
 * a reader cannot hold more than a handful of colours apart anyway. Repeating
 * is what every rainbow-paren implementation does past its palette.
 */
export const BRACKET_DEPTHS = 6;

const OPENERS = '([{';
const CLOSERS = ')]}';

function isBracketCapture(name: string): boolean {
    return name === 'punctuation.bracket' || name.startsWith('punctuation.bracket.');
}

/**
 * Nesting depth per bracket, keyed by the bracket's byte offset.
 *
 * This is what the Rainbow plugin did by re-tokenizing the whole document
 * through `CodeMirror.overlayMode` — and it is one pass over the brackets the
 * highlight query already found, because the parse tree has the answer.
 *
 * Read from the bracket text rather than from the tree's shape. Depth by
 * counting ancestors sounds more principled and is not: grammars differ wildly
 * in how many wrapper nodes sit between a delimiter and the thing it delimits,
 * so the same code would come out at a different depth per language. What a
 * reader means by "one deeper" is one more unclosed bracket to their left, and
 * that is exactly what this counts.
 *
 * A closer is given the depth of the opener it matches, so a pair is one
 * colour. Unbalanced text clamps at zero rather than going negative — a bracket
 * with nothing open outside it is at depth 1 whatever came before.
 */
export function bracketDepths(captures: QueryCapture[]): Map<number, number> {
    const brackets: { at: number; text: string }[] = [];
    for (const capture of captures) {
        if (!isBracketCapture(capture.name)) continue;
        brackets.push({ at: capture.node.startIndex, text: capture.node.text });
    }
    // Captures arrive in query order, not document order, and depth is a
    // document-order question.
    brackets.sort((a, b) => a.at - b.at);

    const depths = new Map<number, number>();
    let level = 0;
    for (const bracket of brackets) {
        const first = bracket.text.charAt(0);
        if (OPENERS.includes(first)) {
            level++;
            depths.set(bracket.at, level);
        } else if (CLOSERS.includes(first)) {
            depths.set(bracket.at, level);
            if (level > 0) level--;
        }
        // Anything else a grammar calls a bracket — a `#{` reader macro's
        // brace is captured as one token in some queries — is left alone
        // rather than guessed at.
    }
    return depths;
}

/**
 * Turn query captures into per-line spans.
 *
 * Captures overlap by design: a query says "every identifier is a variable" and
 * then "an identifier in call position is a function". Narrower captures are
 * applied last so the more specific one wins, which is both what a reader
 * expects and what the query author intended by writing the specific rule.
 *
 * A capture spanning several lines — a block comment, a template literal — is
 * split at line boundaries, because the consumer is a line-based tokenizer.
 */
export function spansFromCaptures(captures: QueryCapture[], lineCount: number): Map<number, Span[]> {
    interface Raw { row: number; from: number; to: number; style: string; size: number; ord: number }
    const raw: Raw[] = [];
    const depths = bracketDepths(captures);

    let ord = 0;
    for (const capture of captures) {
        const node = capture.node;
        ord++;
        let style = captureClasses(capture.name);
        // A bracket carries its nesting depth as a further class, so a theme
        // can colour by depth and one that says nothing about depth still gets
        // the plain bracket colour from the class before it.
        const depth = depths.get(node.startIndex);
        if (depth !== undefined && isBracketCapture(capture.name)) {
            style += ' ts-punctuation-bracket-' + (((depth - 1) % BRACKET_DEPTHS) + 1);
        }
        const startRow = node.startPosition.row;
        const endRow = node.endPosition.row;
        // The whole capture's extent, used only for ordering: a capture over a
        // whole function body must not beat one over a single identifier.
        const size = node.endIndex - node.startIndex;

        for (let row = startRow; row <= endRow && row < lineCount; row++) {
            const from = row === startRow ? node.startPosition.column : 0;
            // Infinity rather than a line length we do not have: the tokenizer
            // clamps to the end of the line it is on.
            const to = row === endRow ? node.endPosition.column : Infinity;
            if (to > from) raw.push({ row, from, to, style, size, ord });
        }
    }

    // Widest first, so narrower captures overwrite them; and for captures of
    // equal width, the one that came later in the query wins.
    //
    // The ordinal tiebreak is not decoration. A highlight query opens with a
    // broad rule — `(identifier) @variable` — and narrows from there, so the
    // specific rule is written afterwards and is meant to win. Relying on sort
    // stability to get that would work today and break the first time anyone
    // touched this comparator.
    raw.sort((a, b) => (a.row - b.row) || (b.size - a.size) || (a.ord - b.ord));

    const byLine = new Map<number, Span[]>();
    for (const r of raw) {
        let line = byLine.get(r.row);
        if (!line) { line = []; byLine.set(r.row, line); }
        line.push({ from: r.from, to: r.to, style: r.style });
    }
    return byLine;
}

/**
 * The style covering `column` on a line, and where that run ends.
 *
 * Later spans win, which is the sort order above doing its work. Returns the
 * next boundary too, so the tokenizer can consume a whole run at once instead
 * of a character at a time.
 */
export function styleAt(spans: Span[] | undefined, column: number): { style: string | null; end: number } {
    if (!spans || spans.length === 0) return { style: null, end: Infinity };
    let style: string | null = null;
    let end = Infinity;
    for (const span of spans) {
        if (span.from <= column && column < span.to) {
            // Later wins: the spans arrive widest-first, so the last one
            // covering this column is the most specific capture for it.
            style = span.style;
            end = Math.min(end, span.to);
        } else if (span.from > column) {
            // The next span starts here, so an unstyled run ends there.
            end = Math.min(end, span.from);
        }
    }
    return { style, end };
}

/**
 * A token style as class names, the way CodeMirror 5 writes them.
 *
 * CodeMirror 5 prefixes every class a mode returns with `cm-`, so a mode saying
 * `ts-variable ts-variable-parameter` reaches the DOM as
 * `cm-ts-variable cm-ts-variable-parameter`. Every theme is written against
 * those names, css/treesitter.css included, so the other engine has to arrive
 * at the same string rather than at its own convention.
 */
export function tokenClasses(style: string): string {
    return 'cm-' + style.trim().replace(/\s+/g, ' cm-');
}

/**
 * One line's styled runs, in columns.
 *
 * The loop the CodeMirror 5 mode runs, with the stream taken out of it: ask
 * what covers this column and where that run ends, emit it, continue from
 * there. Written once because both engines need the same answer — one turns a
 * run into a token, the other into a decoration — and two implementations of
 * this would drift into two different sets of colours.
 */
export function runsForLine(spans: Span[] | undefined, length: number): Span[] {
    const out: Span[] = [];
    let column = 0;
    while (column < length) {
        const { style, end } = styleAt(spans, column);
        const stop = Math.min(end === Infinity ? length : end, length);
        // Always forward, even where a span says otherwise: a run that does not
        // advance is a loop that does not end.
        const next = Math.max(stop, column + 1);
        if (style) out.push({ from: column, to: next, style });
        column = next;
    }
    return out;
}

/** Everything needed to highlight one document. */
export class Highlighter {
    private parser: ParserType;
    private query: import('web-tree-sitter').Query;
    private tree: Tree | null = null;
    private spans: Map<number, Span[]> = new Map();
    /** Bumped on every reparse, so a mode can tell its cache is stale. */
    public generation = 0;

    constructor(parser: ParserType, query: import('web-tree-sitter').Query) {
        this.parser = parser;
        this.query = query;
    }

    /** Reparse `text` from scratch. */
    parse(text: string): void {
        this.tree = this.parser.parse(text, this.tree ?? undefined) ?? null;
        this.refresh(text);
    }

    /**
     * Tell the parser what changed before reparsing, which is what makes the
     * reparse incremental — 0.2ms rather than 4.8ms — and therefore what makes
     * this affordable on every keystroke.
     */
    edit(edit: import('web-tree-sitter').Edit, text: string): void {
        if (this.tree) this.tree.edit(edit);
        this.parse(text);
    }

    private refresh(text: string): void {
        if (!this.tree) return;
        // A newline count rather than a split: the text can be large and the
        // lines themselves are not wanted here.
        let lineCount = 1;
        for (let i = 0; i < text.length; i++) if (text.charCodeAt(i) === 10) lineCount++;
        this.spans = spansFromCaptures(this.query.captures(this.tree.rootNode), lineCount);
        this.generation++;
    }

    spansForLine(line: number): Span[] | undefined {
        return this.spans.get(line);
    }

    /**
     * The top-level forms of the document, in order, as editor positions.
     *
     * This is what makes a result appear beside each form rather than one
     * result for a whole file. Light Table used to ask its nREPL middleware
     * where the forms were, which meant only a language with a bespoke server
     * could have inline results and only when a REPL was running. The parse
     * tree is already here, already current on every keystroke, and knows.
     *
     * Named children only: a comment between two forms is a child of the root
     * but is not something to evaluate.
     */
    topLevelForms(): FormRange[] {
        if (!this.tree) return [];
        const out: FormRange[] = [];
        for (const node of this.tree.rootNode.namedChildren) {
            if (!node) continue;
            out.push({
                startLine: node.startPosition.row,
                startCh: node.startPosition.column,
                endLine: node.endPosition.row,
                endCh: node.endPosition.column,
                type: node.type
            });
        }
        return out;
    }

    dispose(): void {
        this.tree?.delete();
        this.tree = null;
        this.spans.clear();
    }
}

//*********************************************************
// Loading
//*********************************************************

let runtime: Promise<typeof import('web-tree-sitter')> | null = null;

/**
 * Initialise the tree-sitter runtime from bytes.
 *
 * `instantiateWasm` is emscripten's hook for supplying an already-fetched
 * module. Without it the runtime tries to fetch its .wasm by URL, which a
 * context-isolated window loading from file:// cannot do.
 */
export function initRuntime(readBytes: ByteReader, wasmPath: string): Promise<typeof import('web-tree-sitter')> {
    if (runtime) return runtime;
    runtime = (async () => {
        const ts = await import('web-tree-sitter');
        const bytes = readBytes(wasmPath);
        await ts.Parser.init({
            instantiateWasm(imports: WebAssembly.Imports,
                            success: (i: WebAssembly.Instance, m: WebAssembly.Module) => void) {
                // Cast because the two-argument overload TypeScript picks for a
                // Uint8Array is the compiled-Module one; these are bytes.
                (WebAssembly.instantiate(bytes as BufferSource, imports) as
                    Promise<WebAssembly.WebAssemblyInstantiatedSource>)
                    .then((out) => success(out.instance, out.module));
                return {};
            }
        } as object);
        return ts;
    })();
    return runtime;
}

const languages = new Map<string, Promise<Language>>();

/** Load a grammar's .wasm, once per path. */
export function loadLanguage(readBytes: ByteReader, wasmPath: string): Promise<Language> {
    let cached = languages.get(wasmPath);
    if (!cached) {
        cached = (async () => {
            const ts = await runtime!;
            return await ts.Language.load(readBytes(wasmPath));
        })();
        languages.set(wasmPath, cached);
    }
    return cached;
}

/** A parser and compiled query for one grammar, ready to highlight with. */
export async function highlighterFor(readBytes: ByteReader, spec: GrammarSpec): Promise<Highlighter> {
    const ts = await runtime!;
    const language = await loadLanguage(readBytes, spec.wasm);
    const parser = new ts.Parser();
    parser.setLanguage(language);
    return new Highlighter(parser, new ts.Query(language, spec.query));
}
