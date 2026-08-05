// What the filter list costs per keystroke, measured in the running editor.
//
// doc/hygiene.md listed this as *the hinter's rendering cost is unmeasured*, and
// the concern was specific: `lt.ui.filter` replaced a fixed pool of `<li>` nodes
// repainted in place with ordinary diffing, which is right for the command bar
// and the navigator — a few hundred rows that change when you type. The
// auto-complete hinter is the surface that was actually being optimised for. Its
// candidate list is unbounded and it refreshes on every character.
//
// So this measures the real pipeline in the real window at four sizes. What it
// *asserts* is a property rather than a duration — `lt.objs.control/screen`
// exists because a raw span count said nothing, and a wall-clock threshold in CI
// is the same mistake: it fails on a loaded machine and passes on a fast one
// whatever the code does.
//
// The property that matters: **rendering is capped and scoring is not.**
// `indexed-results` slices to 50 before the expensive scoring pass, so the number
// of rows built does not grow with the candidate list however long it gets. If
// that ever stops being true, the hinter starts building thousands of rows on
// every keystroke and this is what says so.

import { test, expect, evalData } from './fixtures';

interface Cost {
    candidates: number;
    scoreMs: number;
    rowMs: number;
    results: number;
    rows: number;
}

/** Score and build rows for `n` synthetic candidates, timed. */
async function cost(window: import('@playwright/test').Page, n: number): Promise<Cost> {
    return await evalData<Cost>(window, `
        (let [;; Names that all match the query, so nothing is filtered out early
              ;; and the scoring pass does its full work. A query that matched
              ;; nothing would measure the cheap path.
              items (vec (for [i (range ${n})] (str "completionCandidate" i)))
              state {:search "cc" :size 100 :items items :key identity}
              t0 (js/performance.now)
              results (lt.objs.sidebar.command/indexed-results state)
              t1 (js/performance.now)
              rows (doall (lt.ui.filter/rows (assoc state :results results)))
              t2 (js/performance.now)]
          {:candidates ${n}
           :scoreMs (- t1 t0)
           :rowMs (- t2 t1)
           :results (count results)
           :rows (count rows)})`);
}

test.describe('the filter list per keystroke', () => {
    test('builds a bounded number of rows however many candidates there are',
         async ({ window }) => {
        const sizes = [100, 1_000, 10_000, 50_000];
        const measured: Cost[] = [];
        for (const n of sizes) measured.push(await cost(window, n));

        // For the record, since the point of this file is that there was no
        // number. Printed rather than asserted — see the header.
        console.log('\n  candidates   score    rows   results  rows built');
        for (const m of measured) {
            console.log('  ' + String(m.candidates).padStart(10) +
                        String(m.scoreMs.toFixed(1) + 'ms').padStart(8) +
                        String(m.rowMs.toFixed(1) + 'ms').padStart(8) +
                        String(m.results).padStart(10) +
                        String(m.rows).padStart(12));
        }

        // `indexed-results` slices to 50 before the expensive pass, so this is
        // the cap — and it is the reason the diffing renderer is fine here.
        for (const m of measured) {
            expect(m.results, `results at ${m.candidates} candidates`)
                .toBeLessThanOrEqual(50);
            expect(m.rows, `rows built at ${m.candidates} candidates`)
                .toBeLessThanOrEqual(50);
        }

        // And it really is a cap rather than an accident of the fixture: the
        // largest run has to have hit it, or the assertion above is vacuous.
        expect(measured.at(-1)!.results).toBe(50);
    });

    test('and the work it does grows with the candidates rather than squaring',
         async ({ window }) => {
        // The failure this guards is a scoring pass that becomes quadratic —
        // which is reachable, because `indexed-results` maps, filters and sorts
        // the full list before it slices. A 100x increase in candidates that
        // costs more than 100x is the signal; the allowance is deliberately
        // generous because the small run is dominated by fixed costs and a tight
        // ratio here would be a flaky test rather than a strict one.
        const small = await cost(window, 500);
        const large = await cost(window, 50_000);

        const ratio = large.scoreMs / Math.max(small.scoreMs, 0.05);
        console.log(`\n  500 -> 50,000 candidates is 100x the work, ` +
                    `and cost ${ratio.toFixed(1)}x the time`);
        expect(ratio).toBeLessThan(400);
    });

    test('and rendering does not grow with the candidate list at all',
         async ({ window }) => {
        // The claim in hygiene, stated as a comparison: the row-building step is
        // downstream of the slice, so its input is 50 items whether there were a
        // hundred candidates or fifty thousand.
        const small = await cost(window, 500);
        const large = await cost(window, 50_000);
        expect(large.rows).toBe(small.rows);
    });
});
