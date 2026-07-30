// Parses `.behaviors` files, on the worker thread.
//
// These are read at startup and again whenever one changes, and can be large
// enough that parsing them on the main thread is felt while typing. Pure string
// handling: no I/O, nothing from Node.
//
// What it produces is not a value — it is positions. Light Table highlights and
// edits behavior files in place, so every token carries where it started and
// ended in the source. That is why this exists rather than a reader.

/**
 * CodeMirror's StringStream, from `codemirror/addon/runmode/runmode.node.js`.
 *
 * Only the members this parser uses, and `start`/`pos` are read *and written*:
 * a mode drives a stream forward, and this drives it the same way.
 */
export interface StringStream {
    /** Where the current token began. Assigned to as tokens are marked out. */
    start: number;
    /** The read position. */
    pos: number;
    peek(): string | undefined;
    next(): string | undefined;
    eatSpace(): boolean;
    /** Consume characters while they match, and say whether any did. */
    eatWhile(match: RegExp | string | ((ch: string) => boolean)): boolean;
    skipTo(ch: string): boolean | undefined;
    /** The text between `start` and `pos`. */
    current(): string;
}

/** A token with a value: a keyword, a string, or an atom. */
export interface LeafToken {
    start: number;
    end: number;
    value: string;
    type: 'keyword' | 'string' | 'atom';
}

/** A bracketed group. `end` is absent until the closing delimiter is seen. */
export interface CollectionToken {
    start: number;
    end?: number;
    type: 'collection';
    tokens: Token[];
}

export type Token = LeafToken | CollectionToken;

/** One top-level entry: a `[:tag :behavior]` pair, or a group of them. */
export interface Entry {
    start: number;
    end?: number;
    tokens: Token[];
}

export interface ParseError {
    error: string;
    from: number;
    to: number;
}

export interface ParseResult {
    errors: ParseError[];
    entries: Entry[];
}

const opposites: Record<string, string | undefined> = {
    '(': ')',
    '[': ']',
    '{': '}',
    ')': '(',
    ']': '[',
    '}': '{'
};

/** Anything that can be part of a bare token: not space, not a closer. */
const chars = /[^\s)\]}]/;

/**
 * Parse `stream` as a flat behavior file.
 *
 * Flat means nesting is tracked by depth rather than by recursion, which is
 * what lets an unmatched delimiter be reported and skipped rather than
 * abandoning the file. Depth is what decides where a token belongs:
 *
 *   level 1   the outer vector
 *   level 2   an entry — `[:tag :behavior]`
 *   level 3   a token inside that entry
 *   level 4   a token inside a collection inside that entry
 *
 * Nothing deeper is recorded, which is a limit of the original and is kept:
 * behavior files do not nest further, and a parser that silently handled more
 * would be claiming a shape the rest of Light Table does not read.
 */
export function parseFlat(stream: StringStream): ParseResult {
    const stack: { type: string; pos: number }[] = [];
    let level = 0;
    const errors: ParseError[] = [];
    const entries: Entry[] = [];
    // Set the moment level reaches 2 and read only at level 2 or deeper, so it
    // cannot be missing where it is used — but say so rather than assert it.
    let curEntry: Entry | undefined;

    /** The token list the current depth appends to, or undefined. */
    function target(): Token[] | undefined {
        if (!curEntry) return undefined;
        if (level === 2) return curEntry.tokens;
        if (level === 3) {
            const last = curEntry.tokens[curEntry.tokens.length - 1];
            return last && last.type === 'collection' ? last.tokens : undefined;
        }
        return undefined;
    }

    stream.eatSpace();
    while (stream.peek()) {
        const ch = stream.next();
        if (ch === undefined) break;

        if (ch === '"') {
            stream.start = stream.pos - 1;
            let next: string | undefined;
            let escaped = false;
            while ((next = stream.next()) !== undefined) {
                if (next === '"' && !escaped) break;
                escaped = !escaped && next === '\\';
            }
            if (level === 2) {
                curEntry?.tokens.push({ start: stream.start, end: stream.pos,
                                        value: stream.current(), type: 'string' });
            }

        } else if (ch === ';') {
            stream.skipTo('\n');

        } else if (ch === '(' || ch === '[' || ch === '{') {
            stack.push({ type: ch, pos: stream.pos });
            level++;
            if (level === 2) {
                curEntry = { start: stream.pos - 1, tokens: [] };
                entries.push(curEntry);
            } else if (level === 3) {
                curEntry?.tokens.push({ start: stream.pos - 1, type: 'collection', tokens: [] });
            } else if (level === 4) {
                const last = curEntry?.tokens[curEntry.tokens.length - 1];
                if (last && last.type === 'collection') {
                    last.tokens.push({ start: stream.pos - 1, type: 'collection', tokens: [] });
                }
            }

        } else if (ch === ')' || ch === ']' || ch === '}') {
            const open = stack[stack.length - 1];
            if (open && open.type === opposites[ch]) {
                stack.pop();
                level--;

                if (level === 1 && curEntry) {
                    curEntry.end = stream.pos;
                } else if (level === 2 && curEntry) {
                    const last = curEntry.tokens[curEntry.tokens.length - 1];
                    if (last) last.end = stream.pos;
                    stream.start = stream.pos;
                } else if (level === 3 && curEntry) {
                    const last = curEntry.tokens[curEntry.tokens.length - 1];
                    if (last && last.type === 'collection') {
                        const inner = last.tokens[last.tokens.length - 1];
                        if (inner) inner.end = stream.pos;
                    }
                    stream.start = stream.pos;
                }

            } else {
                const expected = open ? opposites[open.type] : 'the end of the file';
                errors.push({ error: 'Unmatched delimiter ' + ch + ' expected to see ' + expected,
                              from: stream.start, to: stream.pos });
            }

        } else if (ch === ':') {
            stream.start = stream.pos - 1;
            stream.eatWhile(chars);
            target()?.push({ start: stream.start, end: stream.pos,
                             value: stream.current(), type: 'keyword' });
            stream.start = stream.pos;

        } else if (chars.test(ch)) {
            stream.start = stream.pos - 1;
            stream.eatWhile(chars);
            // An atom nested one deeper is recorded as a keyword. That is not a
            // typo here — it is what the original did, and behavior files are
            // read back by position and value rather than by this label, so
            // changing it would be a silent change to how they render.
            target()?.push({ start: stream.start, end: stream.pos, value: stream.current(),
                             type: level === 2 ? 'atom' : 'keyword' });
            stream.start = stream.pos;
        }
    }

    return { errors, entries };
}
