// Fuzzy matching for the command bar and the file navigator.
//
// Ported from deploy/core/lighttable/util/fuzzy.js, which was evaluated into
// global scope by `lt.util.load/js`. A module that declares its exports can be
// required instead, which is one fewer `eval` and a prerequisite for ever
// adopting a content security policy.
//
// The first function was `String.prototype.score`, a minified copy of
// string_score. Extending String.prototype from a plugin-hosting editor is a
// bad trade at the best of times; here it was also invisible — the call site
// read `(.score scorer search)` on an ordinary string, with nothing to say
// where that method came from.
//
// Compiled to deploy/core/window/fuzzy.js by `npm run build:window`.

/** Which character positions of a candidate a query matched, and how well. */
export interface Match {
    score: number;
    matched: Record<number, boolean>;
}

/**
 * How well `query` matches `candidate`, from 0 to 1.
 *
 * De-minified from string_score (Joshaven Potter, MIT), which is what the
 * `String.prototype.score` one-liner was. Behaviour is unchanged; the names
 * are new, because the original had none.
 *
 * @param fuzziness 0-1. When set, a character that does not appear at all
 *   costs a proportion of the score rather than failing the match outright.
 */
export function stringScore(candidate: string, query: string, fuzziness?: number): number {
    if (candidate === query) return 1;
    if (query === "") return 0;

    let runningScore = 0;
    let remaining = candidate;
    const candidateLength = candidate.length;
    let startOfStringBonus = false;
    // Every character missed under a fuzziness setting divides the final score
    // a little further.
    let fuzzies = 1;

    for (let i = 0; i < query.length; i++) {
        const char = query.charAt(i);
        const lower = remaining.indexOf(char.toLowerCase());
        const upper = remaining.indexOf(char.toUpperCase());
        const nearest = Math.min(lower, upper);
        const index = nearest > -1 ? nearest : Math.max(lower, upper);

        if (index === -1) {
            if (fuzziness) {
                fuzzies += 1 - fuzziness;
                continue;
            }
            return 0;
        }

        let charScore = 0.1;
        // Same case as the query asked for.
        if (remaining[index] === char) charScore += 0.1;

        if (index === 0) {
            // Start of what is left to search.
            charScore += 0.6;
            if (i === 0) startOfStringBonus = true;
        } else if (remaining.charAt(index - 1) === " ") {
            // Start of a word.
            charScore += 0.8;
        }

        remaining = remaining.substring(index + 1, candidateLength);
        runningScore += charScore;
    }

    const perCharacter = runningScore / query.length;
    // Weighted by how much of the candidate the query covers, so a short query
    // does not score a long candidate as highly as a short one.
    let score = (perCharacter * (query.length / candidateLength) + perCharacter) / 2;
    score = score / fuzzies;
    if (startOfStringBonus && score + 0.15 < 1) score += 0.15;
    return score;
}

/** Wraps the matched runs of `str` in `<em>`, for display. */
export function wrapMatch(str: string, info: Match): string {
    const matched = info.matched;
    let run = "";
    let final = "";
    for (let i = 0; i < str.length; i++) {
        while (matched[i]) {
            run += str[i];
            i++;
        }
        if (run) {
            final += "<em>" + run + "</em>";
            run = "";
        }
        if (i < str.length) {
            final += str[i];
        }
    }
    return final;
}

function clone(match: Match): Match {
    return { score: match.score, matched: Object.assign({}, match.matched) };
}

const SEPARATORS = /[\/\\\.\n\r\|\:\;\,\ ]/;

/**
 * Every way `query` can be spread across `str`, best first.
 *
 * Bounded by `limit` per character rather than exhaustively, because the
 * number of placements grows with the product of each character's occurrences.
 */
function findAll(str: string, query: string, from: number, current: Match,
                 limit: number, original?: string): Match[] {
    const orig = original ?? str;
    const first = query[0]!;
    const rest = query.substring(1);

    let index = str.indexOf(first, from);
    if (index < 0) return [{ score: 0, matched: {} }];

    const all: Match[] = [];
    while (index >= 0 && limit > 0) {
        const next = clone(current);
        next.matched[index] = true;

        if (next.matched[index - 1]) {
            // Adjacent to the previous match, which is worth most.
            next.score += 3;
            if (orig[index - 1]!.match(SEPARATORS)) next.score += 2;
        } else if (index === 0) {
            next.score += 3;
        } else if (orig[index - 1]!.match(SEPARATORS)) {
            // Start of a path segment or a word.
            next.score += 2;
        } else {
            next.score += 1;
        }

        if (rest) {
            all.push(...findAll(str, rest, index + 1, next, limit, orig));
        } else {
            all.push(next);
        }
        index = str.indexOf(first, index + 1);
        limit--;
    }
    return all;
}

/** The best way `query` matches `str`, with the positions it matched at. */
export function score(str: string, query: string): Match {
    const found = str.indexOf(query);
    if (found >= 0) {
        // A literal substring beats any spread-out match.
        let matchScore = query.length * 3;
        if (found === 0 || str[found - 1]!.match(SEPARATORS)) matchScore += 2;
        const matched: Record<number, boolean> = {};
        for (let i = 0; i < query.length; i++) matched[i + found] = true;
        return { score: matchScore, matched };
    }
    return findAll(str.toLowerCase(), query.toLowerCase(), 0,
                   { score: 0, matched: {} }, 10)
        .sort((x, y) => y.score - x.score)[0]!;
}

/**
 * Whether `query`'s characters appear in `str` in order. Cheap, and used to
 * discard most candidates before anything scores them properly.
 */
export function fastScore(str: string, query: string): boolean {
    const haystack = str.toLowerCase();
    const needle = query.toLowerCase();
    let index = -1;
    for (let i = 0; i < needle.length; i++) {
        index = haystack.indexOf(needle[i]!, index + 1);
        if (index < 0) return false;
    }
    return true;
}
