// Bands, declared from state.
//
// `lt.ui.bands` answers one question — given a state and a path, which bands
// exist — and hands the answer over whole. Nothing in it removes a band; a band
// that left is gone because it is absent from the answer. That is the claim,
// and it is the reason for the port.
//
// Driven through the control surface: the ClojureScript a person would type,
// against a real editor object.

import { test, expect, evalClj } from './fixtures';


const PROBE_PATH = '/probe.txt';

/** Open an editor at a path of its own. */
function open(): string {
    return `
        (do (def probe (object/create :lt.objs.editor/editor
                                      {:content "zero\\none\\ntwo\\nthree\\n"
                                       :path "${PROBE_PATH}"
                                       :mime "text/plain"}))
            (js/document.body.appendChild (object/->content probe))
            (lt.objs.editor/refresh probe)
            :opened)`;
}

/**
 * Declare a state and read back what is on screen, in one evaluation.
 *
 * One evaluation on purpose: `lt.state.objects/sync!` runs from editor
 * behaviors and would overwrite `:results` between a write and a later read,
 * which is a race that reads as a missing band.
 */
function declare(results: string): string {
    const path = PROBE_PATH;
    return `
        (do (swap! lt.state/app assoc :results ${results})
            (lt.ui.bands/sync-bands! @lt.state/app "${path}")
            (let [el (object/->content probe)]
              [(vec (sort (lt.ui.bands/drawn)))
               (mapv #(.-textContent %) (array-seq (.querySelectorAll el ".band__value")))]))`;
}

// The probe editor is appended to `document.body` rather than opened in a
// tabset — that is the point of it, since a band's parent is a node the editor
// owns and this is the smallest way to have one. It also means the `reset`
// between tests cannot clean it up: `reset` closes tabs, and this was never a
// tab. So it is destroyed here, unconditionally, rather than on the last line
// of each test where a failing assertion above would skip it and leave a live
// editor for every file scheduled after this one in the same worker.
test.afterEach(async ({ window }) => {
    // By path rather than by the `probe` var, which only exists once a test has
    // opened one — and finding it by what it is means this also catches a
    // second probe a future test forgets about.
    await evalClj(window, `
        (do (doseq [ed (lt.objs.editor.pool/by-path "${PROBE_PATH}")]
              (object/destroy! ed))
            (swap! lt.state/app assoc :results {} :watches {})
            [(count (lt.objs.editor.pool/by-path "${PROBE_PATH}"))
             (count (js/document.querySelectorAll ".lt-band"))])`);
});

test('a band is what the state asks for', async ({ window }) => {
    expect(await evalClj(window, open())).toBe(':opened');

    // Two results, two bands. Nothing added them: they exist because the
    // state has them and `sync-bands!` was asked what exists.
    expect(await evalClj(window, declare(`
    {["${PROBE_PATH}" 1] {:status :ok :value "42"}
     ["${PROBE_PATH}" 3] {:status :ok :value "7"}}`)))
    .toBe(`[["[\\"${PROBE_PATH}\\" 1 :result]" `
        + `"[\\"${PROBE_PATH}\\" 3 :result]"] ["42" "7"]]`);

    // One result. The other band is gone because it is absent from the
    // answer, and this is the whole point: no call removed it.
    expect(await evalClj(window, declare(`
    {["${PROBE_PATH}" 1] {:status :ok :value "42"}}`)))
    .toBe(`[["[\\"${PROBE_PATH}\\" 1 :result]"] ["42"]]`);

    // None.
    expect(await evalClj(window, declare('{}'))).toBe('[[] []]');
});

test('and a value that changed keeps the node it was rendered into', async ({ window }) => {
    // The band is the same band — same file, same line, same kind — so the
    // DOM Replicant owns has to survive the value changing. Rebuilding the
    // node instead would work and would throw away every render before it.
    const path = PROBE_PATH;
    await evalClj(window, open());
    await evalClj(window, declare(`{["${path}" 1] {:status :ok :value "42"}}`));

    await evalClj(window, `
        (do (.setAttribute (.querySelector (object/->content probe) ".lt-band")
                           "data-touched" "yes")
            :marked)`);

    expect(await evalClj(window, declare(`{["${path}" 1] {:status :ok :value "43"}}`)))
        .toBe(`[["[\\"${path}\\" 1 :result]"] ["43"]]`);

    expect(await evalClj(window, `
        (.getAttribute (.querySelector (object/->content probe) ".lt-band") "data-touched")`))
        .toBe('"yes"');
});

test('and an editor that closed takes its bands with it', async ({ window }) => {
    // There used to be a `::retire-bands` behavior for this, because the
    // table remembering the widgets outlived the editor holding them. The
    // set is the editor's own state now, so there is nothing left to tell.
    const path = PROBE_PATH;
    await evalClj(window, open());
    await evalClj(window, declare(`{["${path}" 1] {:status :ok :value "42"}}`));

    expect(await evalClj(window, `
        (do (object/destroy! probe)
            (swap! lt.state/app assoc :results {})
            [(count (lt.ui.bands/drawn))
             (count (js/document.querySelectorAll ".lt-band"))])`))
        .toBe('[0 0]');
});
