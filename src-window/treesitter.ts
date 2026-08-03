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
//
// A document is also not always one language. The `<script>` in an HTML file is
// JavaScript, a Rust macro body is Rust, a tagged template literal is whatever
// its tag says — and the grammars ship queries saying so, in a vocabulary
// shared with Helix and Neovim. See `injectionRegions` below: the injected
// parse reads the same text through `includedRanges`, so its captures come back
// in the host document's coordinates and merge into the same span table.

import type { Language, Parser as ParserType, Tree, Node, Range, Query,
              QueryCapture } from 'web-tree-sitter';

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
    /**
     * Source of the grammar's injections.scm, when it ships one. Absent means
     * this language never contains another, which is true of most of them.
     */
    injections?: string | null;
}

/**
 * A tree-sitter language name — what an injection query calls the thing inside
 * — to a grammar we can load, or null for one we do not bundle.
 *
 * Supplied by the caller because *which* languages exist is Light Table's
 * registry, not this module's: a plugin bringing its own grammar has to be able
 * to appear inside an HTML file like any other.
 */
export type LanguageResolver = (name: string) => GrammarSpec | null;

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
    let previous = -1;
    for (const bracket of brackets) {
        // The same bracket seen twice. An injection can cover text its host
        // already highlighted — Rust injects Rust into a macro's token tree —
        // so one `(` arrives from two grammars, and counting it twice would
        // leave every colour after it one deeper than it should be.
        if (bracket.at === previous) continue;
        previous = bracket.at;
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
 * there. Written apart from the drawing because the drawing changed once
 * already and this did not: what a span table means is not the renderer's
 * business.
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

//*********************************************************
// Indentation
//*********************************************************

/**
 * How many indent steps the line at `row` sits at, read from the tree's shape.
 *
 * Not from a query. Nine grammars in ten ship no `indents.scm` — of the ones
 * bundled here exactly one does — so a query-driven indenter would be an
 * indenter for Zig. What every grammar does have is the tree, and the tree
 * already says everything indentation is about: what encloses this line, and
 * where each of those things began.
 *
 * The rule is one line long. **Indentation is the number of distinct lines on
 * which something still open here was opened.** Not the number of enclosing
 * nodes — that is the trap, and it is the same one `bracketDepths` avoids:
 * grammars disagree wildly about how many wrapper nodes sit between a
 * construct and its body, so counting ancestors indents the same code
 * differently per language. A `function_declaration` and the `statement_block`
 * inside it both begin on the line with the `{`; a reader sees one opening
 * there and so does this.
 *
 * The one exception is the line that *closes* something. A `}` or an `end`
 * belongs to the level outside the block it finishes, not inside it, so an
 * enclosing node whose closing token is the first thing on this line does not
 * count. That is one condition rather than a table of per-language delimiters,
 * because "the anonymous token that ends my parent" is what all of them are.
 *
 * Returns null when there is no tree to read, which is a real answer: the
 * caller should leave the line alone rather than move it to column zero.
 */
export function indentLevel(root: Node, row: number, firstNonWs: number): number | null {
    const node = root.descendantForPosition({ row, column: firstNonWs });
    if (!node) return null;

    const opened = new Set<number>();
    for (let a: Node | null = node; a; a = a.parent) {
        // Strictly before, and still open: a node that began on this line has
        // not indented it, and one that ended above it is not enclosing it.
        if (a.startPosition.row < row && a.endPosition.row >= row) {
            opened.add(a.startPosition.row);
        }
    }
    let level = opened.size;

    if (node.startPosition.row === row
        && node.startPosition.column === firstNonWs
        && !node.isNamed
        && node.parent
        && node.parent.endPosition.row === row
        && node.parent.startPosition.row < row) {
        level -= 1;
    }
    return Math.max(0, level);
}

//*********************************************************
// Injections
//*********************************************************

/**
 * How deep a language inside a language inside a language goes.
 *
 * Three, which is CSS inside HTML inside a JavaScript template literal — the
 * deepest arrangement anyone writes on purpose. The cap is there because a
 * grammar that injects itself (Rust's macros do) would otherwise recurse until
 * the ranges stopped shrinking, and "stopped shrinking" is not something a
 * query can promise.
 */
export const MAX_INJECTION_DEPTH = 3;

/** A stretch of the host document to be parsed as some other language. */
export interface Region {
    /** What the query called it: `javascript`, `css`, the text of a heredoc tag. */
    language: string;
    /** Where it is, in the *host document's* coordinates. */
    ranges: Range[];
}

function rangeOf(node: Node): Range {
    return {
        startIndex: node.startIndex,
        endIndex: node.endIndex,
        startPosition: node.startPosition,
        endPosition: node.endPosition
    };
}

/**
 * The ranges one `@injection.content` capture contributes.
 *
 * Without `injection.include-children`, the node's children are *holes*: what
 * gets parsed as the other language is the text between them. That is what
 * makes `` html`<p>${name}</p>` `` work — the `${name}` is JavaScript and must
 * not be handed to the HTML parser, and it is a child of the template string.
 * With the flag, the node is taken whole.
 */
export function contentRanges(node: Node, includeChildren: boolean): Range[] {
    if (includeChildren || node.childCount === 0) return [rangeOf(node)];
    const out: Range[] = [];
    let index = node.startIndex;
    let point = node.startPosition;
    for (const child of node.children) {
        if (!child) continue;
        if (child.startIndex > index) {
            out.push({ startIndex: index, startPosition: point,
                       endIndex: child.startIndex, endPosition: child.startPosition });
        }
        index = child.endIndex;
        point = child.endPosition;
    }
    if (node.endIndex > index) {
        out.push({ startIndex: index, startPosition: point,
                   endIndex: node.endIndex, endPosition: node.endPosition });
    }
    return out;
}

/**
 * Every other-language region an injection query finds in a tree.
 *
 * The vocabulary is tree-sitter's, shared with Helix and Neovim, which is why
 * the queries can be the grammars' own files rather than ours: `@injection.content`
 * is the text, and the language is either `(#set! injection.language "css")` or
 * whatever an `@injection.language` capture spells — a heredoc tag, the
 * identifier tagging a template literal.
 *
 * `injection.combined` means every match of that pattern is one document: a
 * template literal split across several `${}` holes is one CSS stylesheet, not
 * one per fragment. Without it each match parses alone, which is what keeps two
 * unrelated `<script>` blocks from being read as one program.
 */
export function injectionRegions(tree: Tree, query: Query): Region[] {
    const out: Region[] = [];
    const combined = new Map<string, Region>();

    for (const match of query.matches(tree.rootNode)) {
        const properties = match.setProperties ?? {};
        let language = properties['injection.language'] ?? null;
        const includeChildren = 'injection.include-children' in properties;
        const ranges: Range[] = [];

        for (const capture of match.captures) {
            if (capture.name === 'injection.language') language = capture.node.text;
            else if (capture.name === 'injection.content') {
                for (const range of contentRanges(capture.node, includeChildren)) {
                    if (range.endIndex > range.startIndex) ranges.push(range);
                }
            }
        }
        if (!language || ranges.length === 0) continue;
        language = language.toLowerCase();

        if ('injection.combined' in properties) {
            // Per pattern as well as per language, so two different rules that
            // happen to inject the same language stay separate documents.
            const key = match.patternIndex + ' ' + language;
            let region = combined.get(key);
            if (!region) {
                region = { language, ranges: [] };
                combined.set(key, region);
                out.push(region);
            }
            for (const range of ranges) region.ranges.push(range);
        } else {
            out.push({ language, ranges });
        }
    }

    // `includedRanges` has to arrive ordered and disjoint or the parse throws,
    // and neither is something a query guarantees: matches come in pattern
    // order, and a combined region gathers ranges from all over the document.
    for (const region of out) {
        region.ranges.sort((a, b) => a.startIndex - b.startIndex);
        let end = -1;
        region.ranges = region.ranges.filter((range) => {
            if (range.startIndex < end) return false;
            end = range.endIndex;
            return true;
        });
    }
    return out.filter((region) => region.ranges.length > 0);
}

/** A grammar loaded to be parsed inside another one. */
export interface InjectedLanguage {
    parser: ParserType;
    query: Query;
    /** Its own injection query, so a language inside a language nests. */
    injections: Query | null;
}

/**
 * The languages available to appear inside another, loaded on first sighting.
 *
 * Lazy for the same reason the top-level grammars are: a `.wasm` is ~400KB and
 * opening an HTML file with no `<style>` in it should not pay for CSS. The
 * consequence is that the first parse of a file cannot highlight what has not
 * arrived yet, which is what `onLoad` is for — the editor reparses and
 * redraws when it does, and the only thing anyone sees is the injected block
 * gaining colour a moment late.
 *
 * A name that resolves to nothing — `regex`, `jsdoc`, a template-literal tag
 * that was never a language — is remembered as nothing, so a document full of
 * them costs one lookup each rather than one per keystroke.
 */
export class Injections {
    private readBytes: ByteReader;
    private resolve: LanguageResolver;
    private known = new Map<string, InjectedLanguage | null>();
    private loading = new Set<string>();
    private listeners = new Set<() => void>();

    constructor(readBytes: ByteReader, resolve: LanguageResolver) {
        this.readBytes = readBytes;
        this.resolve = resolve;
    }

    /** Called when a language arrives that was not there before. */
    onLoad(listener: () => void): () => void {
        this.listeners.add(listener);
        return () => { this.listeners.delete(listener); };
    }

    /**
     * The language called `name` if it is ready, and null if it is not — either
     * because we do not bundle it or because it is still loading. Asking is what
     * starts the load.
     */
    get(name: string): InjectedLanguage | null {
        const known = this.known.get(name);
        if (known !== undefined) return known;
        if (!this.loading.has(name)) {
            this.loading.add(name);
            void this.load(name);
        }
        return null;
    }

    private async load(name: string): Promise<void> {
        let language: InjectedLanguage | null = null;
        try {
            const spec = this.resolve(name);
            if (spec) {
                const ts = await runtime!;
                const loaded = await loadLanguage(this.readBytes, spec.wasm);
                const parser = new ts.Parser();
                parser.setLanguage(loaded);
                language = {
                    parser,
                    query: new ts.Query(loaded, spec.query),
                    injections: spec.injections ? new ts.Query(loaded, spec.injections) : null
                };
            }
        } catch {
            // A grammar that will not load costs its own language and nothing
            // else: the host document is already highlighted.
            language = null;
        }
        this.known.set(name, language);
        this.loading.delete(name);
        // Only an arrival worth redrawing for. Learning that `jsdoc` is not
        // something we have changes nothing on screen.
        if (language) for (const listener of this.listeners) listener();
    }
}

/** What a highlighter needs to find other languages inside its own. */
export interface InjectionSupport {
    registry: Injections;
    /** The host grammar's injection query. */
    query: Query;
}

/** Everything needed to highlight one document. */
export class Highlighter {
    private parser: ParserType;
    private query: Query;
    private tree: Tree | null = null;
    private spans: Map<number, Span[]> = new Map();
    private injections: InjectionSupport | null;
    /**
     * Held rather than deleted, because the captures handed to
     * `spansFromCaptures` are nodes *of* these trees and reading a node of a
     * deleted tree is a crash rather than a wrong answer. They live until the
     * next refresh has replaced the spans that point into them.
     */
    private injected: Tree[] = [];
    /** The text of the current parse, so an arriving grammar can be applied to it. */
    private text: string | null = null;
    private unsubscribe: (() => void) | null = null;
    /** Bumped on every reparse, so a mode can tell its cache is stale. */
    public generation = 0;
    /**
     * Called when the spans changed without anyone asking — which happens once
     * per injected language, when it finishes loading. The editor reinstalls,
     * because on both engines that is how "what you are drawing from is new" is
     * said.
     */
    public onUpdate: (() => void) | null = null;
    /**
     * The other languages actually drawn inside this document, as the queries
     * name them. For reporting: "is the CSS in this file being highlighted as
     * CSS" is otherwise only answerable by looking at it.
     */
    public injectedLanguages: string[] = [];

    constructor(parser: ParserType, query: Query, injections?: InjectionSupport | null) {
        this.parser = parser;
        this.query = query;
        this.injections = injections ?? null;
        if (this.injections) {
            this.unsubscribe = this.injections.registry.onLoad(() => {
                if (this.text === null) return;
                this.refresh(this.text);
                this.onUpdate?.();
            });
        }
    }

    /** Reparse `text` from scratch. */
    parse(text: string): void {
        this.tree = this.parser.parse(text, this.tree ?? undefined) ?? null;
        this.text = text;
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

        // Nothing points into last refresh's injected trees once the spans
        // below replace the ones that did.
        const stale = this.injected;
        this.injected = [];

        let captures = this.query.captures(this.tree.rootNode);
        this.injectedLanguages = [];
        if (this.injections) {
            // Appended, so injected captures carry the later ordinals and win
            // the ties: an injected `@keyword` and the host's `@string` over the
            // same characters are the same width, and the inner language is the
            // one that actually knows what those characters are.
            captures = captures.concat(
                this.collectInjected(text, this.tree, this.injections.query, 0));
        }
        this.spans = spansFromCaptures(captures, lineCount);
        for (const tree of stale) tree.delete();
        this.generation++;
    }

    /**
     * Captures from every language injected into `tree`, in host coordinates.
     *
     * `includedRanges` is what makes the coordinates free: the injected parser
     * is handed the *whole* document text and told which parts of it to read,
     * so its nodes come back with positions in the host's numbering and nothing
     * has to be offset. Parsing an extracted substring would work too, and then
     * every row and column would need adjusting by hand.
     *
     * A fresh parse each time rather than an incremental one. The incremental
     * path needs the previous tree for the same region, and a region moves,
     * splits and vanishes as the host document is edited; injected blocks are
     * small, and this is measured in tenths of a millisecond.
     */
    private collectInjected(text: string, tree: Tree, query: Query, depth: number): QueryCapture[] {
        const out: QueryCapture[] = [];
        if (depth >= MAX_INJECTION_DEPTH || !this.injections) return out;

        for (const region of injectionRegions(tree, query)) {
            const language = this.injections.registry.get(region.language);
            if (!language) continue;
            let sub: Tree | null = null;
            try {
                sub = language.parser.parse(text, null, { includedRanges: region.ranges });
            } catch {
                sub = null;
            }
            if (!sub) continue;
            this.injected.push(sub);
            if (!this.injectedLanguages.includes(region.language)) {
                this.injectedLanguages.push(region.language);
            }
            for (const capture of language.query.captures(sub.rootNode)) out.push(capture);
            if (language.injections) {
                for (const capture of this.collectInjected(text, sub, language.injections, depth + 1)) {
                    out.push(capture);
                }
            }
        }
        return out;
    }

    spansForLine(line: number): Span[] | undefined {
        return this.spans.get(line);
    }

    /**
     * Indent steps for a line, or null when there is no tree yet.
     *
     * See [[indentLevel]]. The host tree rather than an injected one: what a
     * line is nested inside is a question about the document, and the host
     * grammar is the one that spans all of it.
     */
    indentLevel(row: number, firstNonWs: number): number | null {
        if (!this.tree) return null;
        return indentLevel(this.tree.rootNode, row, firstNonWs);
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
        this.unsubscribe?.();
        this.unsubscribe = null;
        this.onUpdate = null;
        this.tree?.delete();
        this.tree = null;
        for (const tree of this.injected) tree.delete();
        this.injected = [];
        this.text = null;
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

let injectionRegistry: Injections | null = null;

/**
 * The window's one registry of injectable languages.
 *
 * Shared, because a grammar loaded for the CSS inside one HTML file is the same
 * grammar the next one needs, and because the underlying `.wasm` cache is
 * shared already. The first resolver wins; they all read the same registry, so
 * a plugin adding a grammar is picked up by a resolver that has already been
 * handed over — but a language looked up *before* that plugin loaded is
 * remembered as absent, which is the one case that wants a reopened file.
 */
function registryFor(readBytes: ByteReader, resolve: LanguageResolver): Injections {
    if (!injectionRegistry) injectionRegistry = new Injections(readBytes, resolve);
    return injectionRegistry;
}

/** A parser and compiled query for one grammar, ready to highlight with. */
export async function highlighterFor(readBytes: ByteReader, spec: GrammarSpec,
                                     resolve?: LanguageResolver | null): Promise<Highlighter> {
    const ts = await runtime!;
    const language = await loadLanguage(readBytes, spec.wasm);
    const parser = new ts.Parser();
    parser.setLanguage(language);
    const injections = spec.injections && resolve
        ? { registry: registryFor(readBytes, resolve),
            query: new ts.Query(language, spec.injections) }
        : null;
    return new Highlighter(parser, new ts.Query(language, spec.query), injections);
}
