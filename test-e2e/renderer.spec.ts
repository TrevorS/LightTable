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

import * as fs from 'node:fs';
import * as path from 'node:path';
import { test, expect, scratchDir } from './fixtures';
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

// ---------------------------------------------------------------------------
// The UI that has actually been moved over.
// ---------------------------------------------------------------------------

test('a Replicant-rendered statusbar item updates where it sits', async ({ window }) => {
    // The regression that the obvious implementation has. Rendering into a
    // detached holder and handing out its first child passes every test until
    // the node is moved somewhere — which the statusbar does immediately — and
    // then updates land in the holder and the item on screen never changes
    // again. It fails silently, so it needs asserting rather than watching.
    const message = () => window.textContent('#statusbar .log .message');

    await evalClj(window, '(do (lt.objs.notifos/set-msg! "first thing") :said)');
    await expect.poll(message).toBe('first thing');

    await evalClj(window, '(do (lt.objs.notifos/set-msg! "second thing") :said)');
    await expect.poll(message).toBe('second thing');

    // And the node is the one the object is holding, not a replacement — the
    // statusbar appended this and would not learn about a new one.
    expect(await evalClj(window, `
        (= (object/->content lt.objs.statusbar/statusbar-loader)
           (.closest (js/document.querySelector "#statusbar .log") "li"))`)).toBe('true');
});

test('and its class changes with the state it is rendered from', async ({ window }) => {
    const toggle = () => window.getAttribute('#statusbar .console-toggle', 'class');
    const count = () => window.textContent('#statusbar .console-toggle');

    await evalClj(window, '(do (lt.objs.statusbar/clean) :clean)');
    await expect.poll(count).toBe('0');
    expect(await toggle()).not.toContain('dirty');

    await evalClj(window, '(do (lt.objs.statusbar/dirty) (lt.objs.statusbar/dirty) :dirtied)');
    await expect.poll(count).toBe('2');
    expect(await toggle()).toContain('dirty');
});

test('the welcome screen renders and its buttons still do something', async ({ window }) => {
    // Not reached by the style snapshot: `::show-intro` stands down when the
    // window was given arguments, and every automated run gives it some.
    await evalClj(window, '(do (def intro (object/create :lt.objs.intro/intro)) (tabs/add! intro) (tabs/active! intro) :shown)');

    const root = window.locator('#intro');
    await expect(root).toHaveCount(1);
    expect(await root.locator('h1 img').getAttribute('src')).toBe('img/lighttabletextdark.png');
    await expect(root.locator('p')).toHaveCount(3);
    await expect(root.locator('button')).toHaveCount(3);

    // The buttons carry their handlers, which is the part a renderer swap can
    // quietly drop: the markup looks right and nothing responds.
    await root.getByText('changelog').click();
    await expect.poll(async () => await window.locator('#version-info').count()).toBe(1);

    await evalClj(window, '(do (object/destroy! intro) :gone)');
});

test('the component kit renders from the aliases the editor uses', async ({ window }) => {
    // The design document draws twenty-five components; this draws the same
    // twenty-five from lt.ui.row, lt.ui.chrome and lt.ui.band. If the two ever
    // disagree about one, the disagreement is visible rather than theoretical —
    // which is the only reason a catalogue is worth having.
    await evalClj(window, '(do (cmd/exec! :kit.catalogue) :opened)');

    const kit = window.locator('.kit');
    await expect(kit).toHaveCount(1);

    // Every alias is exercised, so a component that throws takes this down.
    await expect(kit.locator('.kit__card')).toHaveCount(23);
    await expect(kit.locator('.dot')).toHaveCount(15);
    await expect(kit.locator('.row')).toHaveCount(11);
    await expect(kit.locator('.band')).toHaveCount(7);

    // Roles resolve through the token sheet rather than a literal in the
    // markup: the class is what the component names, the colour is the CSS.
    const selected = kit.locator('.row--selected').first();
    expect(await selected.evaluate((n) => getComputedStyle(n).backgroundColor))
        .toBe('rgba(137, 220, 235, 0.15)');
    const agentDot = kit.locator('.dot--agent').first();
    expect(await agentDot.evaluate((n) => getComputedStyle(n).backgroundColor))
        .toBe('rgb(203, 166, 247)');

    // And a token can be moved without touching a component, which is the
    // whole argument for the sheet.
    await window.evaluate("document.documentElement.style.setProperty('--lt-agent', 'rgb(1, 2, 3)')");
    expect(await agentDot.evaluate((n) => getComputedStyle(n).backgroundColor)).toBe('rgb(1, 2, 3)');
    await window.evaluate("document.documentElement.style.removeProperty('--lt-agent')");
});

test('a handler in the kit is data, and dispatching it changes state', async ({ window }) => {
    // Section 05's claim, end to end in the assembled application: the vector
    // in the hiccup is what runs, through replicant's dispatch rather than a
    // closure the view captured.
    expect(await evalClj(window, `
        (do (reset! lt.state/app {:runs {"r" {:grants #{}}} :review {:at 0}})
            (lt.actions/dispatch! [[:review/goto 4] [:run/grant "r" :write/src-worker]])
            [(get-in @lt.state/app [:review :at])
             (vec (get-in @lt.state/app [:runs "r" :grants]))])`))
        .toBe('[4 [:write/src-worker]]');

    // An unknown action is reported rather than thrown, because a rebindable
    // table will be asked for actions that have gone away.
    expect(await evalClj(window, `
        (do (lt.actions/dispatch! [[:nope/at-all]]) :survived)`)).toBe(':survived');
});

test('the window renders from the state atom, and follows it', async ({ window }) => {
    // The design's one structural claim, in the assembled application: the
    // chrome is `(view/window @state/app)` and everything on it changed
    // because the state changed. There is no other way for it to change.
    await evalClj(window, '(do (cmd/exec! :ui.window) :opened)');
    await expect(window.locator('.window')).toHaveCount(1);

    // The projection is real: this editor is in the state because it is open,
    // not because a fixture put it there.
    const dir = scratchDir('window-view');
    const file = path.join(dir, 'projected.txt');
    fs.writeFileSync(file, 'one\ntwo\nthree\n');
    await evalClj(window, `(do (cmd/exec! :open-path "${file}") :opened)`);

    await expect.poll(async () => await evalClj(window,
        `(contains? (:editors @lt.state/app) "${file}")`)).toBe('true');
    await expect.poll(async () => await window.locator('.window .titlebar .tab').count())
        .toBeGreaterThan(0);

    // Change the state and the window follows, with nothing else touched.
    await evalClj(window, `
        (do (swap! lt.state/app assoc
                   :runs {"port-fuzzy" {:label "port fuzzy to ranges"
                                        :status :executing
                                        :edits [{:at ["a.ts" 14] :summary "readdir → fsp.readdir"
                                                 :applied? false
                                                 :evidence {:as-written "fs" :if-applied "fsp"}}]}}
                   :review {:run "port-fuzzy" :at 0})
            :set)`);

    await expect.poll(async () => await window.locator('.window .panel .row').count()).toBe(1);
    expect(await window.textContent('.window .panel .row')).toContain('readdir');
    // The run became a tab, because a run is a tab like any other.
    await expect.poll(async () =>
        await window.locator('.window .titlebar .tab .dot--agent').count()).toBe(1);
    // And the statusbar counted what is waiting on you.
    expect(await window.textContent('.window .statusbar')).toContain('waiting on you');

    // A click dispatches the vector the view put in the hiccup. The file
    // opened above took the active tab, and an inactive tab is hidden.
    await evalClj(window, `
        (do (doseq [w (object/by-tag :ui.window)] (tabs/active! w))
            (swap! lt.state/app assoc-in [:review :at] 99)
            :moved)`);
    await window.locator('.window .panel .row').first().click();
    expect(await evalClj(window, '(get-in @lt.state/app [:review :at])')).toBe('0');

    await evalClj(window, `
        (do (doseq [w (object/by-tag :ui.window)] (object/destroy! w))
            (swap! lt.state/app dissoc :runs :review)
            :closed)`);
    fs.rmSync(dir, { recursive: true, force: true });
});

test('a band is hiccup rendered into DOM the editor owns', async ({ window }) => {
    // The design's one genuinely hard problem. An inline result is not beside
    // the code, it is between two lines of it — so the band's parent is a node
    // CodeMirror created and reflows, and Replicant renders into it as an
    // ordinary root that happens to have a foreign parent.
    const dir = scratchDir('bands');
    const file = path.join(dir, 'banded.txt');
    fs.writeFileSync(dir && file, 'zero\none\ntwo\nthree\nfour\n');
    await evalClj(window, `(do (cmd/exec! :open-path "${file}") :opened)`);
    await expect.poll(async () => await evalClj(window,
        `(count (pool/by-path "${file}"))`)).toBe('1');

    // Put a result in the state. Nothing else is touched: no editor call, no
    // widget, no DOM.
    await evalClj(window, `
        (do (swap! lt.state/app assoc :results
                   {["${file}" 1] {:status :finished :value "({:count 2})" :mime "application/edn"}
                    ["${file}" 3] {:status :executing}})
            :put)`);

    // And it is on screen, inside the editor, under the line it belongs to.
    const bands = window.locator('.CodeMirror .band');
    await expect.poll(async () => await bands.count()).toBe(2);
    expect(await window.textContent('.CodeMirror .band--result .band__value'))
        .toContain('{:count 2}');
    // The gutter column is reserved so the band's content lands on the code.
    expect(await window.textContent('.CodeMirror .band .band__gutter')).toBe('1');

    // A watch on the same line is a second band, not the same band changing
    // shape — teal, because it re-reads itself.
    await evalClj(window, `
        (do (swap! lt.state/app assoc :watches
                   {["${file}" 1 [:count]] {:reads 8 :value 7 :expression "(count xs)"}})
            :watched)`);
    await expect.poll(async () => await window.locator('.CodeMirror .band--watch').count()).toBe(1);
    await expect.poll(async () => await bands.count()).toBe(3);

    // Updating a value patches the band in place rather than redrawing it: the
    // widget stays, which is what stops the buffer jumping while you read.
    const before = await evalClj(window, `(count (lt.ui.bands/drawn))`);
    await evalClj(window, `
        (do (swap! lt.state/app assoc-in [:results ["${file}" 1] :value] "({:count 9})") :changed)`);
    await expect.poll(async () => await window.textContent('.CodeMirror .band--result .band__value'))
        .toContain('{:count 9}');
    expect(await evalClj(window, `(count (lt.ui.bands/drawn))`)).toBe(before);

    // Taking it out of the state takes it off the screen. Nothing renders a
    // band away — it has to be retired, which is the other half of the
    // impurity this confines to one file.
    await evalClj(window, '(do (swap! lt.state/app assoc :results {} :watches {}) :cleared)');
    await expect.poll(async () => await bands.count()).toBe(0);
    expect(await evalClj(window, '(count (lt.ui.bands/drawn))')).toBe('0');

    // A line past the end of the buffer is a result computed against text we
    // have since changed, and is not drawn rather than throwing.
    await evalClj(window, `
        (do (swap! lt.state/app assoc :results {["${file}" 9000] {:status :finished :value "x"}})
            :past-the-end)`);
    await window.waitForTimeout(200);
    expect(await bands.count()).toBe(0);

    await evalClj(window, `
        (do (swap! lt.state/app assoc :results {} :watches {} :runs {})
            (doseq [ed (pool/by-path "${file}")] (object/raise ed :close))
            :closed)`);
    fs.rmSync(dir, { recursive: true, force: true });
});

test('a value that is not data is hosted rather than described', async ({ window }) => {
    // A table renders from EDN. A plot is a canvas and an HTML embed is a
    // sandboxed frame, so the band dispatches on :mime and hands off — there is
    // no describing a canvas as hiccup.
    const dir = scratchDir('mime');
    const file = path.join(dir, 'mimed.txt');
    fs.writeFileSync(file, 'a\nb\nc\n');
    await evalClj(window, `(do (cmd/exec! :open-path "${file}") :opened)`);
    await expect.poll(async () => await evalClj(window, `(count (pool/by-path "${file}"))`)).toBe('1');

    await evalClj(window, `
        (do (swap! lt.state/app assoc :results
                   {["${file}" 0] {:status :finished :mime "text/html" :value "<b>hi</b>"}
                    ["${file}" 1] {:status :finished :mime "text/plain" :value "just words"}
                    ["${file}" 2] {:status :finished :mime "video/mp4" :value "..."}})
            :put)`);

    // Sandboxed, because a value is not trusted markup — it came from whatever
    // was evaluated.
    const frame = window.locator('.CodeMirror .band__frame');
    await expect.poll(async () => await frame.count()).toBe(1);
    expect(await frame.getAttribute('sandbox')).toBe('');
    // Text stays text.
    expect(await window.textContent('.CodeMirror .band .band__value')).toBe('just words');
    // And a mime nothing can draw says so rather than rendering an object.
    expect(await window.textContent('.CodeMirror .band .band__note')).toContain('video/mp4');

    await evalClj(window, `
        (do (swap! lt.state/app assoc :results {})
            (doseq [ed (pool/by-path "${file}")] (object/raise ed :close))
            :closed)`);
    fs.rmSync(dir, { recursive: true, force: true });
});

test('a watch ticks on its own clock, not the window\'s', async ({ window }) => {
    // A streaming watch would re-render the whole window on every frame if its
    // readings went through the main atom. They go through their own, observed
    // by their own root — which is what makes the design's colour distinction
    // also a rendering boundary.
    const dir = scratchDir('watch-clock');
    const file = path.join(dir, 'ticking.txt');
    fs.writeFileSync(file, 'loop\nrecur\ndone\n');
    await evalClj(window, `(do (cmd/exec! :open-path "${file}") :opened)`);
    await expect.poll(async () => await evalClj(window, `(count (pool/by-path "${file}"))`)).toBe('1');

    await evalClj(window, `
        (do (swap! lt.state/app assoc :watches
                   {["${file}" 1 [:i]] {:expression "(recur (inc i))"}})
            :watching)`);
    await expect.poll(async () => await window.locator('.CodeMirror .band--watch').count()).toBe(1);

    // Sixty readings. The main atom is not touched by any of them.
    const before = await evalClj(window, '(hash @lt.state/app)');
    await evalClj(window, `
        (do (dotimes [i 60] (lt.state/observe! ["${file}" 1 [:i]] i)) :ticked)`);

    await expect.poll(async () => await window.textContent('.CodeMirror .band--watch'))
        .toContain('59');
    expect(await evalClj(window, '(hash @lt.state/app)')).toBe(before);
    expect(await evalClj(window, `(:reads (get @lt.state/watch-values ["${file}" 1 [:i]]))`)).toBe('60');

    await evalClj(window, `
        (do (swap! lt.state/app assoc :watches {})
            (reset! lt.state/watch-values {})
            (doseq [ed (pool/by-path "${file}")] (object/raise ed :close))
            :closed)`);
    fs.rmSync(dir, { recursive: true, force: true });
});
