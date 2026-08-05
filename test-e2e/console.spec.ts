// The console, now that it is a view of a value.
//
// It was the last surface still building its own nodes, and it had the best
// reason: it is genuinely append-only. `write` appended an `<li>` and dropped the
// first child past the limit, and `try-update` appended a text node into a `<pre>`
// already on screen so that a process talking in chunks accumulated in one row.
//
// doc/hygiene.md said making it a view means holding the last fifty lines as a
// value and that the streaming append is what would have to change. It did, and
// this is what says the behaviour did not: every assertion below is about what the
// console *shows*, so it would have passed against the imperative version too.
// That is the point of it — a conversion's test should not be able to tell which
// implementation it is running.

import { test, expect, evalClj, evalData } from './fixtures';

/** The console's rendered rows, as text.
 *
 * Through `object/->content` rather than a document query or a Playwright
 * locator, and that is not incidental: the console starts hidden, so its `<ul>`
 * is a real element that is **not attached**. The bottombar splices it in when
 * it is shown. `ltErrors` in the fixtures reads it the same way for the same
 * reason, and the smoke test says so about the searcher.
 *
 * Which also makes these assertions about what the console drew rather than
 * about whether the bar happens to be open. */
async function rows(window: import('@playwright/test').Page): Promise<string[]> {
    return await evalData<string[]>(window, `
        (let [^js el (lt.object/->content lt.objs.console/console)]
          (mapv (fn [^js li] (.-innerText li))
                (array-seq (.querySelectorAll el ":scope > li"))))`)
        .then((xs) => xs.map((x) => String(x).replace(/\s+/g, ' ').trim()));
}

/** How many nodes inside the console match `selector`. */
async function count(window: import('@playwright/test').Page,
                     selector: string): Promise<number> {
    return Number(await evalClj(window, `
        (let [^js el (lt.object/->content lt.objs.console/console)]
          (.-length (.querySelectorAll el "${selector}")))`));
}

test.describe('the console', () => {
    test.beforeEach(async ({ window }) => {
        await evalClj(window, '(do (lt.objs.console/clear) :cleared)');
    });

    test('draws a line for each thing logged', async ({ window }) => {
        await evalClj(window, '(do (lt.objs.console/log "first") (lt.objs.console/log "second") :logged)');
        expect(await rows(window)).toEqual(['first', 'second']);
    });

    test('and holds the lines as a value, which is the claim', async ({ window }) => {
        await evalClj(window, '(do (lt.objs.console/log "held") :logged)');
        // The DOM is derived from this rather than being the record of it.
        expect(await evalData(window, `
            (mapv :content (:lines @lt.objs.console/console))`)).toEqual(['held']);
    });

    test('drops the oldest past the buffer size', async ({ window }) => {
        // The limit is a user behavior, so it is set rather than assumed.
        await evalClj(window, '(do (set! lt.objs.console/console-limit 3) :set)');
        try {
            await evalClj(window, `
                (do (doseq [i (range 5)] (lt.objs.console/log (str "line" i))) :logged)`);
            expect(await rows(window)).toEqual(['line2', 'line3', 'line4']);
        } finally {
            await evalClj(window, '(do (set! lt.objs.console/console-limit 50) :restored)');
        }
    });

    test('an error is marked as one, which is what every other spec reads', async ({ window }) => {
        await evalClj(window, '(do (lt.objs.console/error "went wrong") :logged)');
        expect(await count(window, 'li.error')).toBe(1);
    });

    test('clearing empties it', async ({ window }) => {
        await evalClj(window, '(do (lt.objs.console/log "gone") :logged)');
        expect(await rows(window)).toHaveLength(1);
        await evalClj(window, '(do (lt.objs.console/clear) :cleared)');
        expect(await rows(window)).toEqual([]);
    });

    test.describe('streaming', () => {
        test('chunks under one id accumulate in one row', async ({ window }) => {
            // The whole reason `try-update` exists: nREPL stdout arrives in
            // pieces, and a row per piece is unreadable.
            await evalClj(window, `
                (do (lt.objs.console/loc-log {:file "proc" :line 1 :id 7 :content "one "})
                    (lt.objs.console/loc-log {:file "proc" :line 1 :id 7 :content "two "})
                    (lt.objs.console/loc-log {:file "proc" :line 1 :id 7 :content "three"})
                    :streamed)`);
            const drawn = await rows(window);
            expect(drawn).toHaveLength(1);
            expect(drawn[0]).toContain('one two three');
        });

        test('and two streams at once each keep their own row', async ({ window }) => {
            // Interleaved, which is what `querySelector('#console<id>')` handled
            // by matching an id anywhere in the list. Preserving that is why
            // `try-update` searches the whole vector rather than only the last
            // line — a chunk from the older stream belongs in the row it started,
            // not in a new one at the bottom.
            await evalClj(window, `
                (do (lt.objs.console/loc-log {:file "a" :line 1 :id 1 :content "a1 "})
                    (lt.objs.console/loc-log {:file "b" :line 1 :id 2 :content "b1 "})
                    (lt.objs.console/loc-log {:file "a" :line 1 :id 1 :content "a2"})
                    (lt.objs.console/loc-log {:file "b" :line 1 :id 2 :content "b2"})
                    :streamed)`);
            const drawn = await rows(window);
            expect(drawn).toHaveLength(2);
            expect(drawn[0]).toContain('a1 a2');
            expect(drawn[1]).toContain('b1 b2');
        });

        test('and a message with no id is always its own row', async ({ window }) => {
            await evalClj(window, `
                (do (lt.objs.console/loc-log {:file "x" :line 1 :content "one"})
                    (lt.objs.console/loc-log {:file "x" :line 1 :content "two"})
                    :logged)`);
            expect(await rows(window)).toHaveLength(2);
        });
    });

    test('verbatim owns its whole line, where log is wrapped', async ({ window }) => {
        // The distinction the old code made by what its callers wrapped things in
        // rather than by saying so: `log` puts its content in a `<pre>` and
        // `verbatim` does not, because a caller of `verbatim` is handing over the
        // line. The Javascript plugin's stdout rows depend on it.
        await evalClj(window, `
            (do (lt.objs.console/log "logged")
                (lt.objs.console/verbatim [:em.file "node"] nil)
                :logged)`);
        expect(await count(window, 'li > pre')).toBe(1);
        expect(await count(window, 'li > em.file')).toBe(1);
    });

    test('and none of it raises', async ({ window, ltErrors }) => {
        await evalClj(window, '(do (lt.objs.console/log "quiet") :logged)');
        expect((await ltErrors()).join('\n')).toBe('');
    });
});
