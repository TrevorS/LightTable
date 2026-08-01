// Bands, declared from state, on whichever engine the editor was built with.
//
// `lt.ui.bands` answers one question — given a state and a path, which bands
// exist — and hands the answer over whole. Nothing in it removes a band. That
// is the claim, and it has to hold on CodeMirror 5 too, because the CodeMirror
// 5 half of the bookkeeping did not disappear: it moved into
// `lt.objs.editor.bands`, where the engine that needs it is.
//
// So every test here runs twice, against a real editor object of each kind,
// through the control surface — the ClojureScript a person would type.

import { test, expect } from './fixtures';
import type { Page } from '@playwright/test';

async function evalClj(window: Page, source: string): Promise<any> {
    let job = await window.evaluate(
        ([s]) => (globalThis as any).lt.objs.control.request('eval', { source: s }), [source]);
    for (let i = 0; i < 100 && job.status === 'working'; i++) {
        await window.waitForTimeout(50);
        job = await window.evaluate(
            ([id]) => (globalThis as any).lt.objs.control.request('job', { job: id }), [job.id]);
    }
    if (job.status !== 'completed') throw new Error(`${job.status}: ${job.error}\n${source}`);
    return job.result;
}

const ENGINES = [':cm5', ':cm6'] as const;

/**
 * Open an editor on `engine` at a path of its own.
 *
 * A path per engine because `lt.ui.bands` finds editors by the file they show,
 * and the two runs must not find each other's.
 */
function open(engine: string): string {
    return `
        (do (lt.objs.editor/set-engine! ${engine})
            (def probe (object/create :lt.objs.editor/editor
                                      {:content "zero\\none\\ntwo\\nthree\\n"
                                       :path "/probe${engine.slice(1)}.txt"
                                       :mime "text/plain"}))
            (js/document.body.appendChild (object/->content probe))
            (lt.objs.editor/refresh probe)
            [(name (lt.objs.editor/engine)) (lt.objs.editor/cm6? probe)])`;
}

/**
 * Declare a state and read back what is on screen, in one evaluation.
 *
 * One evaluation on purpose: `lt.state.objects/sync!` runs from editor
 * behaviors and would overwrite `:results` between a write and a later read,
 * which is a race that reads as a missing band.
 */
function declare(engine: string, results: string): string {
    const path = `/probe${engine.slice(1)}.txt`;
    return `
        (do (swap! lt.state/app assoc :results ${results})
            (lt.ui.bands/sync-bands! @lt.state/app "${path}")
            (let [el (object/->content probe)]
              [(vec (sort (lt.ui.bands/drawn)))
               (mapv #(.-textContent %) (array-seq (.querySelectorAll el ".band__value")))]))`;
}

for (const engine of ENGINES) {
    test(`a band is what the state asks for, on ${engine}`, async ({ window }) => {
        expect(await evalClj(window, open(engine)))
            .toBe(engine === ':cm6' ? '["cm6" true]' : '["cm5" false]');

        // Two results, two bands. Nothing added them: they exist because the
        // state has them and `sync-bands!` was asked what exists.
        expect(await evalClj(window, declare(engine, `
            {["/probe${engine.slice(1)}.txt" 1] {:status :ok :value "42"}
             ["/probe${engine.slice(1)}.txt" 3] {:status :ok :value "7"}}`)))
            .toBe(`[["[\\"/probe${engine.slice(1)}.txt\\" 1 :result]" `
                + `"[\\"/probe${engine.slice(1)}.txt\\" 3 :result]"] ["42" "7"]]`);

        // One result. The other band is gone because it is absent from the
        // answer, and this is the whole point: no call removed it.
        expect(await evalClj(window, declare(engine, `
            {["/probe${engine.slice(1)}.txt" 1] {:status :ok :value "42"}}`)))
            .toBe(`[["[\\"/probe${engine.slice(1)}.txt\\" 1 :result]"] ["42"]]`);

        // None.
        expect(await evalClj(window, declare(engine, '{}'))).toBe('[[] []]');

        await evalClj(window, '(do (object/destroy! probe) :gone)');
    });

    test(`and a value that changed keeps the node it was rendered into, on ${engine}`,
        async ({ window }) => {
        // The band is the same band — same file, same line, same kind — so the
        // DOM Replicant owns has to survive the value changing. Rebuilding the
        // node instead would work and would throw away every render before it.
        const path = `/probe${engine.slice(1)}.txt`;
        await evalClj(window, open(engine));
        await evalClj(window, declare(engine, `{["${path}" 1] {:status :ok :value "42"}}`));

        await evalClj(window, `
            (do (.setAttribute (.querySelector (object/->content probe) ".lt-band")
                               "data-touched" "yes")
                :marked)`);

        expect(await evalClj(window, declare(engine, `{["${path}" 1] {:status :ok :value "43"}}`)))
            .toBe(`[["[\\"${path}\\" 1 :result]"] ["43"]]`);

        expect(await evalClj(window, `
            (.getAttribute (.querySelector (object/->content probe) ".lt-band") "data-touched")`))
            .toBe('"yes"');

        await evalClj(window, '(do (object/destroy! probe) :gone)');
    });

    test(`and an editor that closed takes its bands with it, on ${engine}`, async ({ window }) => {
        // There used to be a `::retire-bands` behavior for this, because the
        // table remembering the widgets outlived the editor holding them. The
        // table is now on the editor itself on CodeMirror 5 and is the editor's
        // own state on CodeMirror 6, so there is nothing left to tell.
        const path = `/probe${engine.slice(1)}.txt`;
        await evalClj(window, open(engine));
        await evalClj(window, declare(engine, `{["${path}" 1] {:status :ok :value "42"}}`));

        expect(await evalClj(window, `
            (do (object/destroy! probe)
                (swap! lt.state/app assoc :results {})
                [(count (lt.ui.bands/drawn))
                 (count (js/document.querySelectorAll ".lt-band"))])`))
            .toBe('[0 0]');
    });
}
