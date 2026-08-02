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

/**
 * The editor's own element, on whichever engine built it.
 *
 * CodeMirror 5 calls it `.CodeMirror` and CodeMirror 6 calls it `.cm-editor`.
 * These tests are about what Light Table renders *inside* an editor, so they
 * should not care which — but the stylesheets do, and that is a real gap rather
 * than a selector: `deploy/core/css/themes` is written against the CodeMirror 5
 * names.
 */
const EDITOR = ':is(.CodeMirror, .cm-editor)';

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
    // `:tab-label` was the third count here — one object with one node per
    // tab per tabset, destroyed and recreated wholesale on every change. The
    // strip is a view now and there are none, so what is counted instead is
    // what the strip draws: a tab in the document per open tab.
    const counts = async () => await evalClj(window, `
        [(count @object/instances)
         (count (object/by-tag :editor))
         (count (js/document.querySelectorAll "#multi .titlebar .tab"))]`);

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

test('the statusbar is a view of the state, in the window you use', async ({ window }) => {
    // The first chrome that moved. `lt.ui.view/statusbar` is asserted from a
    // map in test/lt/ui/view_test.cljs, which is most of what matters and
    // cannot see whether the bar in this window is that function — so this
    // says what the state is and reads the screen.
    const message = () => window.textContent('#statusbar .statusbar__message');

    await evalClj(window, '(do (lt.objs.notifos/set-msg! "first thing") :said)');
    await expect.poll(message).toBe('first thing');

    // The regression that the obvious implementation has. Rendering into a
    // detached holder and handing out its first child passes every test until
    // the node is moved somewhere — which the statusbar container does
    // immediately — and then updates land in the holder and nothing on screen
    // changes again. It fails silently, so it needs asserting rather than
    // watching.
    await evalClj(window, '(do (lt.objs.notifos/set-msg! "second thing") :said)');
    await expect.poll(message).toBe('second thing');

    // And the node is the one the object is holding, not a replacement — the
    // container appended this and would not learn about a new one.
    expect(await evalClj(window, `
        (= (object/->content lt.objs.statusbar/statusbar)
           (js/document.querySelector "#statusbar"))`)).toBe('true');
});

test('and what it shows is state, so nothing reaches into it', async ({ window }) => {
    // Every one of these was an object/merge! into a statusbar item and is now
    // an action over the state atom. The bar draws from that atom, so a count
    // that is not there is a fact that is not true rather than a node hidden
    // in CSS — which is what the old console toggle was.
    const pill = window.locator('#statusbar .statusbar__console .pill');

    await evalClj(window, '(do (lt.objs.statusbar/clean) :clean)');
    await expect(pill).toHaveCount(0);

    await evalClj(window, '(do (lt.objs.statusbar/dirty) (lt.objs.statusbar/dirty) :dirtied)');
    await expect(pill).toHaveText('2');
    expect(await pill.getAttribute('class')).not.toContain('pill--error');

    // An error changes the colour of the count and nothing else: it is still
    // the same number of things you have not read.
    await evalClj(window, '(do (lt.objs.statusbar/console-class "error") :tinted)');
    await expect(pill).toHaveClass(/pill--error/);
    await expect(pill).toHaveText('2');

    // Clicking it is the one thing in the bar you can do, and the handler is a
    // vector — so this is also the only place that proves `actions/install!`
    // ran in a real window. Opening the console is what clears the count.
    await pill.click();
    await expect(pill).toHaveCount(0);
    expect(await evalClj(window, '(:console @lt.state/app)')).toBe('{:unread 0, :tone nil}');
    expect(await evalClj(window,
        '(lt.objs.bottombar/active? lt.objs.console/console)')).toBe('true');
});

test('and the working indicator counts rather than flips', async ({ window }) => {
    // Two overlapping tasks and one finishing must not turn it off. The old
    // loader had this right and it was the only thing in the bar that did;
    // it is a property of the action now, so it is also asserted without a
    // window in test/lt/actions_test.cljs.
    const dot = window.locator('#statusbar .dot--executing');

    await evalClj(window, '(do (lt.objs.statusbar/loader-set) :reset)');
    await expect(dot).toHaveCount(0);

    await evalClj(window, '(do (lt.objs.notifos/working) (lt.objs.notifos/working) :two)');
    await expect(dot).toHaveCount(1);

    await evalClj(window, '(do (lt.objs.notifos/done-working) :one-left)');
    await expect(dot).toHaveCount(1);

    await evalClj(window, '(do (lt.objs.notifos/done-working) :none)');
    await expect(dot).toHaveCount(0);
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

test('a role in the kit resolves through the token sheet, not the markup', async ({ window }) => {
    // That the catalogue draws every alias the registry holds is asked in
    // `catalogue.spec.ts`, against the registry rather than against counts
    // written here — counts that went stale the first time the page changed.
    // What is left is the claim this file is for and that one does not make:
    // a component names a role, and the colour is somewhere else entirely.
    await evalClj(window, '(do (cmd/exec! :kit.catalogue) :opened)');

    const kit = window.locator('.kit');
    await expect(kit).toHaveCount(1);

    // The class is what the component names, the colour is the CSS.
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
    // Wait for the projection to have caught up before writing into the state.
    // `lt.state.objects/sync!` runs from editor behaviors and owns `:results`,
    // so a write that lands before the last one is overwritten by it — which is
    // a race the CodeMirror 5 timing happened to hide.
    await expect.poll(async () => await evalClj(window,
        `(contains? (:editors @lt.state/app) "${file}")`)).toBe('true');

    // Put a result in the state. Nothing else is touched: no editor call, no
    // widget, no DOM.
    await evalClj(window, `
        (do (swap! lt.state/app assoc :results
                   {["${file}" 1] {:status :finished :value "({:count 2})" :mime "application/edn"}
                    ["${file}" 3] {:status :executing}})
            :put)`);

    // And it is on screen, inside the editor, under the line it belongs to.
    const bands = window.locator(`${EDITOR} .band`);
    await expect.poll(async () => await bands.count()).toBe(2);
    expect(await window.textContent(`${EDITOR} .band--result .band__value`))
        .toContain('{:count 2}');
    // The gutter column is reserved so the band's content lands on the code.
    expect(await window.textContent(`${EDITOR} .band .band__gutter`)).toBe('1');

    // A watch on the same line is a second band, not the same band changing
    // shape — teal, because it re-reads itself.
    await evalClj(window, `
        (do (swap! lt.state/app assoc :watches
                   {["${file}" 1 [:count]] {:reads 8 :value 7 :expression "(count xs)"}})
            :watched)`);
    await expect.poll(async () => await window.locator(`${EDITOR} .band--watch`).count()).toBe(1);
    await expect.poll(async () => await bands.count()).toBe(3);

    // Updating a value patches the band in place rather than redrawing it: the
    // widget stays, which is what stops the buffer jumping while you read.
    const before = await evalClj(window, `(count (lt.ui.bands/drawn))`);
    await evalClj(window, `
        (do (swap! lt.state/app assoc-in [:results ["${file}" 1] :value] "({:count 9})") :changed)`);
    await expect.poll(async () => await window.textContent(`${EDITOR} .band--result .band__value`))
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
    // Wait for the projection to have caught up before writing into the state.
    // `lt.state.objects/sync!` runs from editor behaviors and owns `:results`,
    // so a write that lands before the last one is overwritten by it — which is
    // a race the CodeMirror 5 timing happened to hide.
    await expect.poll(async () => await evalClj(window,
        `(contains? (:editors @lt.state/app) "${file}")`)).toBe('true');

    await evalClj(window, `
        (do (swap! lt.state/app assoc :results
                   {["${file}" 0] {:status :finished :mime "text/html" :value "<b>hi</b>"}
                    ["${file}" 1] {:status :finished :mime "text/plain" :value "just words"}
                    ["${file}" 2] {:status :finished :mime "video/mp4" :value "..."}})
            :put)`);

    // Sandboxed, because a value is not trusted markup — it came from whatever
    // was evaluated.
    const frame = window.locator(`${EDITOR} .band__frame`);
    await expect.poll(async () => await frame.count()).toBe(1);
    expect(await frame.getAttribute('sandbox')).toBe('');
    // Text stays text.
    expect(await window.textContent(`${EDITOR} .band .band__value`)).toBe('just words');
    // And a mime nothing can draw says so rather than rendering an object.
    expect(await window.textContent(`${EDITOR} .band .band__note`)).toContain('video/mp4');

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
    // Wait for the projection to have caught up before writing into the state.
    // `lt.state.objects/sync!` runs from editor behaviors and owns `:results`,
    // so a write that lands before the last one is overwritten by it — which is
    // a race the CodeMirror 5 timing happened to hide.
    await expect.poll(async () => await evalClj(window,
        `(contains? (:editors @lt.state/app) "${file}")`)).toBe('true');

    await evalClj(window, `
        (do (swap! lt.state/app assoc :watches
                   {["${file}" 1 [:i]] {:expression "(recur (inc i))"}})
            :watching)`);
    await expect.poll(async () => await window.locator(`${EDITOR} .band--watch`).count()).toBe(1);

    // Sixty readings. The main atom is not touched by any of them.
    const before = await evalClj(window, '(hash @lt.state/app)');
    await evalClj(window, `
        (do (dotimes [i 60] (lt.state/observe! ["${file}" 1 [:i]] i)) :ticked)`);

    await expect.poll(async () => await window.textContent(`${EDITOR} .band--watch`))
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

// ---------------------------------------------------------------------------
// The design's remaining open questions, answered in code.
// ---------------------------------------------------------------------------

test('an agent is a client in the same list as the REPL', async ({ window }) => {
    // Question 2, and the design calls it a real commitment: is the agent a
    // peer or a subsystem? A peer. What it does to this editor is what a REPL
    // does to it, so a second mechanism would mean two answers to "what is
    // connected" and a panel honest about one of them.
    //
    // The control surface is the agent's door, and calling it is what connects
    // one — an editor nobody is driving should not claim an agent.
    const clients = await evalClj(window, `
        (do (lt.objs.control/request "snapshot" #js {})
            (->> (:clients (lt.state.objects/snapshot))
                 vals
                 (filter #(= :agent (:kind %)))
                 (map :name)
                 vec))`);
    expect(clients).toContain('claude');

    // And the one thing an agent has that the others do not: where its work
    // actually runs.
    expect(await evalClj(window, '(some? (:via (lt.objs.clients.agent/state)))')).toBe('true');

    // It reaches the connections panel because it is in the same registry, not
    // because the panel was taught about agents.
    await evalClj(window, '(do (lt.state.objects/sync!) :synced)');
    const row = await evalClj(window, `
        (->> (:clients @lt.state/app) vals (filter #(= :agent (:kind %))) count)`);
    expect(row).toBe('1');
});

test('a behavior that throws says which file asked for it', async ({ window }) => {
    // Question 3: do behaviors stay EDN on disk, or become a queryable store?
    // On disk — a file you can read, diff and version is most of what a store
    // would be for. What the store would genuinely have added is the answer to
    // "where did this come from", and that is one atom filled while merging.
    expect(await evalClj(window, `
        (lt.objs.settings/where-from :editor :lt.ui.window/track-cursor)`))
        .toContain('default.behaviors');

    // Every contributing file, and how much each contributed.
    expect(Number(await evalClj(window,
        '(count (lt.objs.settings/sources))'))).toBeGreaterThan(1);

    // And the payoff: a failing behavior names the file, not just itself.
    // A behavior that throws on purpose, attached the way any plugin's is.
    await evalClj(window, `
        (do (object/clear-errors!)
            (object/behavior* :lt.probe/throws
                              :triggers #{:lt-probe-throw}
                              :reaction (fn [_] (throw (js/Error. "on purpose"))))
            (swap! object/behavior-source assoc :lt.probe/throws "somebody.behaviors")
            (object/add-behavior! lt.objs.app/app :lt.probe/throws)
            (object/raise lt.objs.app/app :lt-probe-throw)
            :raised)`);
    const said = await window.evaluate(
        () => (globalThis as any).lt.objs.control.request('errors', {})
            .errors.map((e: { message: string }) => e.message).join(' ')) as string;
    expect(said).toContain('lt.probe/throws');
    expect(said).toContain('somebody.behaviors');
    await evalClj(window, '(do (object/clear-errors!) :cleared)');
});

test('the kit is data, so a component can be replaced while the editor runs', async ({ window }) => {
    // Question 4: are aliases the right home, or should the seventeen be plain
    // functions? Aliases — because this is possible, and it is the same claim
    // as [tag behavior-keyword]: what the editor is made of is a table, and a
    // table can be edited from inside the thing it describes.
    expect(Number(await evalClj(window, '(count (lt.ui.kit/aliases))'))).toBeGreaterThanOrEqual(25);

    await evalClj(window, '(do (cmd/exec! :kit.catalogue) :opened)');
    await expect.poll(async () => await window.locator('.kit .row').count()).toBeGreaterThan(0);
    expect(await window.locator('.kit .row--swapped').count()).toBe(0);

    // One keyword, replaced at runtime. No view was recompiled and nothing
    // that draws a row knows this happened.
    await evalClj(window, `
        (do (lt.ui.kit/redefine! :lt.ui.row/list-row
                                 (fn [attrs body] [:div.row.row--swapped body]))
            :swapped)`);
    await expect.poll(async () => await window.locator('.kit .row--swapped').count())
        .toBeGreaterThan(0);
    expect(await evalClj(window, '(lt.ui.kit/redefined)')).toContain('list-row');

    await evalClj(window, '(do (lt.ui.kit/restore!) :restored)');
    await expect.poll(async () => await window.locator('.kit .row--swapped').count()).toBe(0);
    await expect.poll(async () => await window.locator('.kit .row').count()).toBeGreaterThan(0);
});

test('an editor is hosted inside the chrome, and never diffed', async ({ window }) => {
    // The fourth kind in the design's split, and the last mechanism it names:
    // foreign DOM we must never diff. The hiccup is an empty keyed element and
    // a mount hook; everything inside belongs to CodeMirror.
    //
    // Asked for by keyword, the way `lt.ui.view` asks: an alias rather than a
    // function is what keeps the view layer loadable without a DOM.
    //
    // Rendered into a root of its own rather than through the whole window,
    // because that is the mechanism — the window adds a projection whose
    // timing has nothing to do with what is being asserted here.
    const dir = scratchDir('pane');
    const file = path.join(dir, 'hosted.txt');
    fs.writeFileSync(file, 'alpha\nbeta\ngamma\n');

    await evalClj(window, `
        (do (def host (js/document.createElement "div"))
            (js/document.body.appendChild host)
            (replicant.dom/render host [:div.probe-chrome [:lt.ui.pane/pane {:path "${file}"}]])
            :mounted)`);

    // A real editor, with the real content, inside a view.
    await expect.poll(async () => await evalClj(window, '(count (lt.ui.pane/mounted))')).toBe('1');
    const pane = window.locator('.probe-chrome .pane');
    await expect.poll(async () => await pane.locator(EDITOR).count()).toBe(1);
    expect(await pane.locator(EDITOR).textContent()).toContain('gamma');

    // Re-rendering the chrome does not touch it. The node the editor is in is
    // the node it was in, which is what :replicant/key buys — without it a
    // scroll would destroy and rebuild a CodeMirror per excerpt.
    await evalClj(window, `
        (do (replicant.dom/render host [:div.probe-chrome
                                        [:span.noise "something changed"]
                                        [:lt.ui.pane/pane {:path "${file}"}]])
            :rendered)`);
    expect(await evalClj(window, '(count (lt.ui.pane/mounted))')).toBe('1');
    expect(await pane.locator(EDITOR).count()).toBe(1);

    // And taking the pane out of the hiccup destroys the editor rather than
    // leaking it — the unmount hook is the other half of the handoff.
    await evalClj(window, '(do (replicant.dom/render host [:div.probe-chrome]) :hidden)');
    await expect.poll(async () => await evalClj(window, '(count (lt.ui.pane/mounted))')).toBe('0');
    expect(await pane.locator(EDITOR).count()).toBe(0);

    await evalClj(window, `
        (do (lt.ui.pane/clear!)
            (.remove host)
            (doseq [ed (pool/by-path "${file}")] (object/raise ed :close))
            :closed)`);
    fs.rmSync(dir, { recursive: true, force: true });
});

test('the actions the views emit are all handled', async ({ window }) => {
    // Every handler in the chrome is a vector, and a vector nothing answers is
    // a click that does nothing — which looks exactly like a click that did
    // something invisible.
    //
    // Read off the views rather than listed here. A list goes stale silently
    // and did: the tree's whole right-click menu dispatched actions that were
    // only ever registered as effects, so every one of them reported
    // `:error/unknown-action` into a console nobody was looking at. This walks
    // what the views actually emit, with both branches of the two panels that
    // have one open.
    const unhandled = await evalClj(window, `
        (let [s (assoc @lt.state/app
                       :command-bar {:open? true :query "" :at 0
                                     :commands [{:label "x" :action [:cmd/exec :x]}]}
                       :workspace {:roots ["/p"]
                                   :nodes {"/p" {:dir? true :open? true
                                                 :children ["/p/a.txt"]}
                                           "/p/a.txt" {:dir? false}}
                                   :recents nil}
                       :keymap {"⌘⏎" [[:eval/form]]}
                       ;; Enough of a window that every list has a row in it —
                       ;; a view with nothing to draw emits no handlers, and
                       ;; would pass this by drawing nothing.
                       :tabsets [{:id 0 :tabs ["a.cljs"] :active 0}]
                       :clients {51423 {:name "nREPL" :kind :nrepl :status :finished}}
                       :review {:run "r" :at 0}
                       :runs {"r" {:label "r" :status :executing
                                   :edits [{:at ["a.cljs" 1] :summary "s" :applied? false}]}})
              both (fn [k v] [(assoc-in s k v) s])
              drawn (concat [(lt.ui.view/window s) (lt.ui.view/settings s)]
                            (map lt.ui.view/workspace
                                 (both [:workspace :recents] [{:path "/w" :folders [] :files []}]))
                            (map lt.ui.view/connections
                                 (both [:connect] {:choosing? true
                                                   :connectors [{:name-of "Ports" :desc "d"}]})))
              nodes (filter vector? (tree-seq #(and (coll? %) (not (map? %))) seq drawn))
              attrs (keep #(when (map? (second %)) (second %)) nodes)
              handlers (mapcat (fn [a]
                                 (concat (vals (select-keys a [:on-select :on-menu]))
                                         (vals (:on a))))
                               attrs)
              kinds (set (for [h handlers
                               :when (vector? h)
                               action (if (vector? (first h)) h [h])
                               :when (keyword? (first action))]
                           (first action)))]
          ;; The count comes back too, so a walk that found nothing — a
          ;; refactor that moved where handlers live, say — fails here rather
          ;; than passing by looking at an empty set.
          (str "unhandled " (vec (sort (remove (set (keys (lt.actions/registered))) kinds)))
               " · emitted " (vec (sort kinds))))`);
    expect(unhandled, 'actions a view emits that nothing is registered for')
        .toContain('unhandled []');
    // And the walk found the chrome rather than an empty tree, which is the way
    // a guard like this goes quiet.
    for (const a of [':tab/activate', ':review/goto', ':client/bind', ':tree/toggle',
                     ':tree/menu', ':cmd/exec', ':workspace/show-recents']) {
        expect(unhandled).toContain(a);
    }

    // And an effect with no handler is reported rather than swallowed, which
    // is how the gap above was found in the first place.
    await evalClj(window, '(do (object/clear-errors!) :cleared)');
    await evalClj(window, '(do (lt.actions/dispatch! [[:nope/not-a-thing]]) :ran)');
    const said = await window.evaluate(
        () => (globalThis as any).lt.objs.control.request('errors', {})
            .errors.map((e: { message: string }) => e.message).join(' ')) as string;
    expect(said.length).toBeGreaterThan(0);
    await evalClj(window, '(do (object/clear-errors!) :cleared)');
});
