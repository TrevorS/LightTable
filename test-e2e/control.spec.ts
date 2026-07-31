// The control surface: driving the editor from outside it.
//
// These assert the thing an MCP wrapper will depend on — data in, data out,
// no DOM scraping. Every other spec in this directory reaches into the window
// with hand-munged ClojureScript names, which is exactly what this exists to
// stop being necessary.

import * as fs from 'node:fs';
import * as path from 'node:path';
import { test, expect, scratchDir } from './fixtures';
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
    // lt.objs.editor.pool/by-path by hand every time.
    const count = await evalClj(window, '(count (object/by-tag :editor))');
    expect(count.status).toBe('completed');
    expect(Number(count.result)).toBeGreaterThanOrEqual(0);

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
