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
