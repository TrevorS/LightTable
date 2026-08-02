// The control surface: driving the editor from outside it.
//
// These assert the thing an MCP wrapper will depend on — data in, data out,
// no DOM scraping. Every other spec in this directory reaches into the window
// with hand-munged ClojureScript names, which is exactly what this exists to
// stop being necessary.

import * as fs from 'node:fs';
import * as path from 'node:path';
import { test, expect, evalData, scratchDir } from './fixtures';
import type { Page } from '@playwright/test';

/** Call the control surface the way `script/lt-repl.sh` and MCP will. */
async function control<T = any>(window: Page, op: string, arg: unknown = {}): Promise<T> {
    return await window.evaluate(
        ([o, a]) => (globalThis as any).lt.objs.control.request(o, a),
        [op, arg] as [string, unknown]) as T;
}

/** Run one expression and wait for its job to reach a terminal status. */
async function evalClj(window: Page, source: string): Promise<any> {
    let job = await control(window, 'eval', { source });
    for (let i = 0; i < 100 && job.status === 'working'; i++) {
        await window.waitForTimeout(100);
        job = await control(window, 'job', { job: job.id });
    }
    return job;
}

test('a snapshot says what is open, as data', async ({ window }) => {
    const dir = scratchDir('control');
    const file = path.join(dir, 'probe.txt');
    fs.writeFileSync(file, 'one\ntwo\n');

    const before = await control(window, 'snapshot');
    expect(Array.isArray(before.editors)).toBe(true);
    expect(before.editors.length).toBe(0);

    const opened = await control(window, 'open', { path: file });
    expect(['working', 'completed']).toContain(opened.status);

    await expect.poll(async () => (await control(window, 'snapshot')).editors.length).toBe(1);

    const after = await control(window, 'snapshot');
    const ed = after.editors[0];
    // A handle, so a later call can name this editor without "the current one"
    // meaning anything — which is what MCP's statelessness requires.
    expect(typeof ed.id).toBe('number');
    expect(ed.path).toBe(file);
    expect(ed.dirty).toBe(false);
    expect(ed.tags).toContain(':editor');

    const value = await control(window, 'value', { editor: ed.id });
    expect(value.value).toBe('one\ntwo\n');

    fs.rmSync(dir, { recursive: true, force: true });
});

test('ClojureScript goes in and a value comes back', async ({ window }) => {
    expect((await evalClj(window, '(+ 1 2)')).result).toBe('3');

    // The prepared namespace: short aliases, so a caller does not spell
    // lt.objs.editor.pool/by-path by hand every time. Asserted by spelling it
    // both ways and comparing — a count on its own is a number no matter what
    // it counted, which is what this used to check.
    const aliased = await evalClj(window, '(count (object/by-tag :editor))');
    const spelled = await evalClj(window, '(count (lt.object/by-tag :editor))');
    expect(aliased.status).toBe('completed');
    expect(aliased.result).toMatch(/^\d+$/);
    expect(aliased.result).toBe(spelled.result);

    expect((await evalClj(window, '(files/basename "/a/b/c.txt")')).result).toBe('"c.txt"');
});

test('a failure is a status, not a silence', async ({ window }) => {
    const job = await evalClj(window, '(this-is-not-defined)');
    expect(job.status).toBe('failed');
    expect(String(job.error).length).toBeGreaterThan(0);
});

test('behavior errors are readable as values', async ({ window }) => {
    await control(window, 'clear-errors');
    expect((await control(window, 'errors')).errors).toEqual([]);

    // lt.object catches what a reaction throws and reports it here, so a
    // failing behavior is invisible to a caller without this: raise returns
    // normally either way. Reported directly rather than by arranging a
    // behavior that throws, which would test lt.object's catch, not the ring.
    await window.evaluate("lt.object.safe_report_error('a behavior went wrong')");

    const after = await control(window, 'errors');
    expect(after.errors.length).toBeGreaterThan(0);
    expect(after.errors[0].message).toContain('a behavior went wrong');
    expect(typeof after.errors[0].at).toBe('number');

    await control(window, 'clear-errors');
    expect((await control(window, 'errors')).errors).toEqual([]);
});

test('a modal is enumerable and answerable', async ({ window }) => {
    const dir = scratchDir('control-prompt');
    const file = path.join(dir, 'dirty.txt');
    fs.writeFileSync(file, 'hello\n');

    await control(window, 'open', { path: file });
    await expect.poll(async () => (await control(window, 'snapshot')).editors.length).toBe(1);

    // Dirty the buffer, then close the window — which asks about it.
    await evalClj(window, `(let [ed (first (pool/by-path "${file}"))]
                             (editor/replace ed {:line 0 :ch 0} "X") :dirtied)`);
    await evalClj(window, '(do (cmd/exec! :window.close) :closing)');

    await expect.poll(async () => (await control(window, 'prompts')).prompts.length).toBe(1);
    const prompt = (await control(window, 'prompts')).prompts[0];
    expect(prompt.header).toContain('lose changes');
    expect(prompt.choices).toContain('cancel');

    // A wrong guess is answered with what the choices are, rather than thrown.
    const wrong = await control(window, 'answer', { prompt: prompt.id, choice: 'Explode' });
    expect(wrong.error).toContain('No choice');
    expect(wrong.choices).toContain('cancel');

    const right = await control(window, 'answer', { prompt: prompt.id, choice: 'cancel' });
    expect(right.answered).toBe(prompt.id);
    await expect.poll(async () => (await control(window, 'prompts')).prompts.length).toBe(0);

    fs.rmSync(dir, { recursive: true, force: true });
});

test('an unknown operation says what there is', async ({ window }) => {
    const out = await control(window, 'teleport', {});
    expect(out.error).toContain('teleport');
    expect(out.operations).toContain('snapshot');
    expect(out.operations).toContain('eval');
});

test('and a caller can have the value rather than a picture of it', async ({ window }) => {
    // `eval` answers the way a REPL prints, because the callers it was written
    // for are showing a person a value. A test is not: comparing against
    // pretty-printed EDN makes every assertion a string, and a mismatch a diff
    // of text. So `data` asks for the value through `clj->js`.
    const data = async (source: string) =>
        await evalData(window, source);

    expect(await data('[1 2 3]')).toEqual([1, 2, 3]);
    expect(await data('{:a 1 :b {:c "x"}}')).toEqual({ a: 1, b: { c: 'x' } });
    expect(await data('(mapv name [:one :two])')).toEqual(['one', 'two']);
    expect(await data('nil')).toBe(null);
    // A keyword is a string, which is the whole of what clj->js promises.
    expect(await data(':done')).toBe('done');

    // Printed is still the default, and still printed.
    expect((await evalClj(window, '[1 2 3]')).result).toBe('[1 2 3]');
});

test('and asking for data about something that is not data says so', async ({ window }) => {
    // Every one of these used to be a different bad answer. A function and an
    // atom took the window's stack down inside `clj->js`; a DOM node came back
    // as `{}`, which reads as an empty result rather than as a mistake.
    for (const [source, why] of [['(fn [x] x)', 'a function'],
                                 ['(atom {:a 1})', 'an object or an atom'],
                                 ['js/document.body', 'a DOM node']] as const) {
        const job = await control(window, 'eval', { source, data: true });
        expect(job.status, source).toBe('failed');
        expect(job.error, source).toContain(why);
    }
});

test('and printing an object is bounded rather than endless', async ({ window }) => {
    // A Light Table object is an atom whose state holds other objects, and the
    // graph has cycles — so `(first (pool/by-path f))`, which is the most
    // ordinary thing to evaluate in this editor, printed until the stack ran
    // out. The RangeError came out of whoever had called in, which through the
    // control surface meant the caller rather than the job.
    const dir = scratchDir('control-print');
    const file = path.join(dir, 'printed.txt');
    fs.writeFileSync(file, 'a\nb\n');
    await evalClj(window, `(do (lt.objs.command/exec! :open-path "${file}") :opened)`);
    await expect.poll(async () => await evalData(window, `(count (pool/by-path "${file}"))`)).toBe(1);

    const job = await evalClj(window, `(first (pool/by-path "${file}"))`);
    expect(job.status).toBe('completed');
    expect(job.result).toContain('cljs.core.Atom');
    // Bounded, so it is a page rather than a heap.
    expect(job.result.length).toBeLessThan(200_000);

    await evalClj(window, `(do (doseq [ed (pool/by-path "${file}")] (lt.object/raise ed :close)) :closed)`);
    fs.rmSync(dir, { recursive: true, force: true });
});

test('and Light Table\'s own TypeScript is reachable from the ClojureScript', async ({ window }) => {
    // The other direction, and the one that had a gap. `lt.window.modules` is
    // the single bridge — a `def ^js` per module with its exports in the
    // docstring — but three of the nine were required for their side effect
    // alone and so had no name, reachable only as `js/window.ltCm6Modes`.
    // Which is the hand-spelled global this whole namespace exists to replace.
    expect(await evalData(window, '(count (js->clj (.knownModes modules/cm6-modes)))'))
        .toBeGreaterThan(100);
    expect(await evalData(window, '(boolean (.-commands modules/cm6-commands))')).toBe(true);
    expect(await evalData(window, '(boolean (.-treeHighlighting modules/cm6-treesitter))')).toBe(true);
});

test('the window can say what it was built from', async ({ window }) => {
    // "Is my change in the window I am looking at?" had no answer. The bundle
    // is an artifact with no identity and `version.json` is the same string
    // across every build between two releases, so telling a stale window from a
    // fresh one meant grepping the compiled JavaScript for a string you had
    // just typed. That happened twice, and each time it cost a round of "it
    // still doesn't work" about code that was fixed and not loaded.
    //
    // `script/stamp-build.mts` writes the stamp at the end of `build:cljs`, so
    // reaching it here also says the build wrote it.
    const stamp = await evalData<{ commit: string, branch: string, built: string }>(
        window, '(lt.objs.deploy/build-stamp)');
    expect(stamp, 'build:cljs must leave a stamp beside the bundle').toBeTruthy();
    expect(stamp.commit).toMatch(/^[0-9a-f]{7,}$/);
    expect(stamp.built).toMatch(/^\d{4}-\d\d-\d\dT/);

    // And there is a command a person can run to see it, with a description
    // they could find it by.
    expect(await evalData<boolean>(window,
        '(boolean (get-in @lt.objs.command/manager [:commands :build.info]))')).toBe(true);
    expect(await evalData<string>(window,
        '(:desc (get-in @lt.objs.command/manager [:commands :build.info]))'))
        .toContain('build');

    // What it says, asserted where it is decided rather than off the status
    // bar: `eval` writes the bar itself — "Starting the ClojureScript
    // compiler" — so reading it back after an evaluation is a race with the
    // thing doing the reading.
    const said = await evalData<string>(window,
        '(lt.objs.deploy/build-line (lt.objs.deploy/build-stamp))');
    expect(said).toContain(stamp.commit);
    expect(said).toContain(stamp.branch);

    // The sentence is a function of the stamp, which is what makes the two
    // cases below assertable at all — neither depends on the tree this ran in.
    expect(await evalData<string>(window, `
        (lt.objs.deploy/build-line {:commit "abc1234" :branch "develop"
                                    :dirty true :built "2026-08-02T18:24:26.601Z"})`))
        .toContain('uncommitted changes');
    expect(await evalData<string>(window, `
        (lt.objs.deploy/build-line {:commit "abc1234" :branch "develop"
                                    :dirty false :built "2026-08-02T18:24:26.601Z"})`))
        .not.toContain('uncommitted');

    // A build made before the stamp existed, or outside a git checkout. Saying
    // "unknown" is an answer; saying nothing is what the command is for.
    expect(await evalData<string>(window, '(lt.objs.deploy/build-line nil)'))
        .toContain('no stamp');
});

test('and two commands cannot claim the same key in silence', async ({ window }) => {
    // `command`'s docstring has always said the key is unique and nothing
    // checked it, so a second namespace registering a taken key replaced the
    // first without a word. What you got was a command that ran something
    // else — worse than one that does not exist, because the command bar still
    // lists it and it still does a thing. It happened while adding
    // `:build.info`, which was first written as `:version` and quietly did
    // nothing at all.
    await window.evaluate(() => (globalThis as any).lt.objs.control.request('clear-errors', {}));

    // Re-registering the same command must stay quiet: evaluating a namespace
    // in this editor re-runs every `command` form in it, and that is the
    // feature rather than a mistake.
    await evalData(window, `
        (do (cmd/command {:command :lt.probe/twice :desc "Probe: once"
                          :exec (fn [] nil)})
            (cmd/command {:command :lt.probe/twice :desc "Probe: once"
                          :exec (fn [] nil)})
            :re-evaluated)`);
    expect((await control(window, 'errors')).errors,
           'live editing re-registers commands constantly').toEqual([]);

    await evalData(window, `
        (do (cmd/command {:command :lt.probe/twice :desc "Probe: something else"
                          :exec (fn [] nil)})
            :clashed)`);
    const errors = (await control(window, 'errors')).errors;
    expect(errors.length).toBe(1);
    expect(errors[0].message).toContain(':lt.probe/twice');
    expect(errors[0].message).toContain('Probe: once');
    expect(errors[0].message).toContain('unreachable');

    await window.evaluate(() => (globalThis as any).lt.objs.control.request('clear-errors', {}));
});

test('and it can say what the window is showing, not only what is open', async ({ window }) => {
    // `snapshot` answers what is open; a screenshot answers what is on screen
    // and cannot be asserted on. This is the third question, and it exists
    // because an hour went into querying the DOM for an expected widget while
    // a modal sat on top of it — which one screenshot answered instantly and
    // no command could.
    const dir = scratchDir('control-screen');
    const file = path.join(dir, 'seen.txt');
    fs.writeFileSync(file, 'alpha\nbeta\n');
    await control(window, 'open', { path: file });
    await expect.poll(async () => (await control(window, 'snapshot')).editors.length).toBe(1);

    const screen = await control(window, 'screen');
    expect(screen.tabs).toContain('seen.txt');
    expect(screen['active-tab']).toBe('seen.txt');
    expect(screen.modals).toEqual([]);
    expect(screen.overlays).toEqual([]);
    expect(typeof screen.statusbar).toBe('string');

    // And a modal is the thing it exists to report.
    await evalClj(window, `
        (do (lt.objs.popup/popup! {:header "A question" :body "Well?"
                                   :buttons [{:label "cancel"}]})
            :asked)`);
    await expect.poll(async () => (await control(window, 'screen')).modals.length).toBe(1);
    expect((await control(window, 'screen')).modals[0]).toContain('A question');
    expect((await control(window, 'screen')).overlays).toContain('popup');

    const p = (await control(window, 'prompts')).prompts[0];
    await control(window, 'answer', { prompt: p.id, choice: 'cancel' });
    await expect.poll(async () => (await control(window, 'screen')).modals.length).toBe(0);

    // Closed, not just deleted. The suite shares one application, so an editor
    // left open on a removed file is a fact the next test inherits — which the
    // drift check below caught the first time it ran.
    await evalClj(window, `
        (do (doseq [ed (pool/by-path "${file}")] (object/raise ed :close)) :closed)`);
    fs.rmSync(dir, { recursive: true, force: true });
});

test('and it can say where the state atom disagrees with the objects', async ({ window }) => {
    // `lt.state.objects/snapshot` is a pure function of the object world, so
    // the projection can be recomputed and compared at any instant. Nothing
    // did, and the two are kept in step by a list of triggers — so a fact
    // changing on a trigger not in that list is stale until you click
    // something. A statusbar stuck on "connecting" for as long as the window
    // was open is what that looks like.
    expect((await control(window, 'drift')).drifted,
           'a settled window should agree with itself').toEqual([]);

    // Made to disagree on purpose, so this is a check rather than a hope.
    await evalClj(window, '(do (swap! lt.state/app assoc :editors {"/nowhere" {:lang "x"}}) :bent)');
    const drifted = (await control(window, 'drift')).drifted;
    expect(drifted.length).toBe(1);
    expect(drifted[0].key).toBe(':editors');
    expect(drifted[0].drawn).toContain('/nowhere');

    await evalClj(window, '(do (lt.state.objects/sync!) :resynced)');
    expect((await control(window, 'drift')).drifted).toEqual([]);
});

test('and it can say which behaviors a trigger actually ran', async ({ window }) => {
    // This architecture's characteristic failure is a chain going quiet: a
    // trigger is raised, some behaviors run, one declines, and nothing appears.
    // Reading the code answers it — five times, for one bug. The hook was
    // already in the hot path; nothing recorded it.
    await evalClj(window, '(do (lt.objs.trace/on!) :tracing)');
    await evalClj(window, `
        (do (object/raise (first (object/by-tag :tabset)) :lt.probe/nobody-home)
            (cmd/exec! :toggle-console)
            (cmd/exec! :toggle-console)
            :did-things)`);

    const report = await evalData<string>(window, '(lt.objs.trace/report 500 nil)');
    expect(report).toContain(':lt.probe/nobody-home');
    // The half that is hardest to see any other way: raised, and nothing was
    // listening. No error, no message, nothing happens.
    expect(report).toContain('nothing is listening');
    // And a trigger that did run something names what ran.
    expect(await evalData<string>(window, '(lt.objs.trace/report 500 :toggle)'))
        .toMatch(/ran :/);

    await evalClj(window, '(do (lt.objs.trace/off!) :stopped)');
    // Off by default is the claim that makes this free to ship.
    const before = await evalData<number>(window, '(count @lt.objs.trace/records)');
    await evalClj(window, '(do (cmd/exec! :toggle-console) (cmd/exec! :toggle-console) :more)');
    expect(await evalData<number>(window, '(count @lt.objs.trace/records)')).toBe(before);
});

test('and a command registered after startup reaches the projection', async ({ window }) => {
    // `lt.state.objects/commands` projects `lt.objs.command/manager` so the
    // command bar can be a view over it, and nothing listened for a command
    // being registered — so a plugin's commands, which arrive after the window
    // does, were missing until something else happened to sync.
    //
    // The command bar you open was never wrong: `lt.objs.sidebar.command`
    // passes `:items` as a function and calls it when it needs the list, so it
    // reads the table live. This is the surface that replaces it.
    //
    // Found by `drift` once it stopped excluding the command bar wholesale.
    // Only half of that key is the state's own, and dropping all of it made
    // the check blind to the half it could judge — which is worse than not
    // checking, because a tool that reports nothing reads as agreement.
    await evalClj(window, '(do (lt.state.objects/sync!) :settled)');
    await evalClj(window, `
        (do (cmd/command {:command :lt.probe/late :desc "Probe: registered late"
                          :exec (fn [] nil)})
            :registered)`);

    const inProjection = async () => await evalData<boolean>(window, `
        (boolean (some #(= "Probe: registered late" (:label %))
                       (:commands (:command-bar @lt.state/app))))`);

    // Debounced, because 217 commands register at load and each raises this.
    await expect.poll(inProjection, { timeout: 5000 }).toBe(true);
    expect((await control(window, 'drift')).drifted).toEqual([]);
});
