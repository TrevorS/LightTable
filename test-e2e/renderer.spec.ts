// An object may be rendered by something other than singultus.
//
// `lt.object/->dom` passes hiccup to singultus and leaves anything else alone,
// which is the whole of what a different renderer needs: an `:init` returning
// a DOM node works, in the create path and in the redefinition one. That is
// two branches of an `if` in a namespace nobody edits often, so without this
// it is an accident rather than a contract, and the next tidy-up removes it.
//
// Written through the control surface, so the test is the ClojureScript a
// person would type rather than hand-munged names.

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

test('an object can be rendered by something that is not singultus', async ({ window }) => {
    // No hiccup anywhere: the node is built the way any other renderer would
    // hand one over, and `->content` has to be that node itself.
    expect(await evalClj(window, `
        (do (object/object* ::by-hand
                            :tags #{:renderer-probe}
                            :init (fn [_]
                                    (let [n (js/document.createElement "section")]
                                      (set! (.-className n) "made-by-hand")
                                      (set! (.-textContent n) "first")
                                      n)))
            (def probe (object/create ::by-hand))
            [(.-tagName (object/->content probe))
             (.-className (object/->content probe))
             (.-textContent (object/->content probe))])`))
        .toBe('["SECTION" "made-by-hand" "first"]');

    // Hiccup still goes through singultus, which is the other branch and the
    // one every panel in the editor is still using.
    expect(await evalClj(window, `
        (let [o (object/create (object/object* ::from-hiccup :init (fn [_] [:aside.from-hiccup "x"])))]
          [(.-tagName (object/->content o)) (.-className (object/->content o))])`))
        .toBe('["ASIDE" "from-hiccup"]');
});

test('and redefining it replaces the node that is on screen', async ({ window }) => {
    // The path live self-modification takes: redefining a type rebuilds every
    // live instance and swaps the old node for the new one. A renderer that
    // only worked on the way in would look fine until the first redefinition.
    await evalClj(window, `
        (do (object/object* ::redefinable
                            :init (fn [_]
                                    (let [n (js/document.createElement "section")]
                                      (set! (.-className n) "before")
                                      n)))
            (def live (object/create ::redefinable))
            (js/document.body.appendChild (object/->content live))
            :on-screen)`);

    expect(await window.locator('body > section.before').count()).toBe(1);

    await evalClj(window, `
        (do (object/object* ::redefinable
                            :init (fn [_]
                                    (let [n (js/document.createElement "article")]
                                      (set! (.-className n) "after")
                                      n)))
            :redefined)`);

    expect(await window.locator('body > section.before').count()).toBe(0);
    expect(await window.locator('body > article.after').count()).toBe(1);
    // And the object is holding the new node, not the detached old one.
    expect(await evalClj(window, '(.-className (object/->content live))')).toBe('"after"');

    await evalClj(window, '(do (object/destroy! live) :gone)');
    expect(await window.locator('body > article.after').count()).toBe(0);
});

test('an object that is destroyed lets go of what it was watching', async ({ window }) => {
    // Checked because a component renderer needs somewhere to unsubscribe, and
    // the answer turns out to be that the object lifecycle already is that
    // place: `bound` adds a watch, `object/destroy!` is what ends it. Opening
    // and closing tabs returns every count to where it started, which is what
    // makes the object model a foundation to render onto rather than one to
    // replace at the same time.
    const counts = async () => await evalClj(window, `
        [(count @object/instances)
         (count (object/by-tag :editor))
         (count (object/by-tag :tab-label))]`);

    const before = await counts();
    for (let i = 0; i < 3; i++) {
        await evalClj(window, `(do (cmd/exec! :new-file) :opened)`);
    }
    expect(await counts()).not.toBe(before);

    await evalClj(window, `
        (do (doseq [ed (object/by-tag :editor)] (object/raise ed :close))
            :closed)`);
    await expect.poll(counts).toBe(before);
});
