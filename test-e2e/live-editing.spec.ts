// The editor changing itself while it runs — see doc/live-editing.md.
//
// This is the feature Light Table is named for, and it rests on things no
// unit test can see: that the release bundle still exposes lt.* as globals,
// that the analysis cache shipped and can be read from a file:// page, and
// that a compiled form reaches the same functions the window is holding.

import * as fs from 'node:fs';
import * as path from 'node:path';
import { test, expect, scratchDir, connectLocalClient } from './fixtures';

/** Open `file` and wait until the editor has parsed it into top-level forms. */
async function openForms(window: import('@playwright/test').Page, file: string, expected: number) {
    await window.evaluate(
        ([f]) => (globalThis as any).lt.objs.command.exec_BANG_(
            (globalThis as any).cljs.core.keyword.call(null, 'open-path'), f),
        [file]);

    await window.waitForFunction(
        ([f, n]) => {
            const lt = (globalThis as any).lt, cljs = (globalThis as any).cljs;
            const ed = cljs.core.first.call(null, lt.objs.editor.pool.by_path(f));
            if (!ed) return false;
            const forms = lt.plugins.clojure.forms_in(ed);
            return !!forms && cljs.core.count(forms) === n;
        },
        [file, expected] as [string, number],
        { timeout: 60_000 });
}

/** The results drawn beside each form, in order. */
async function inlineResults(window: import('@playwright/test').Page, file: string) {
    return await window.evaluate(([f]) => {
        const lt = (globalThis as any).lt, cljs = (globalThis as any).cljs;
        const kw = (n: string) => cljs.core.keyword.call(null, n);
        const ed = cljs.core.first.call(null, lt.objs.editor.pool.by_path(f));
        return cljs.core.clj__GT_js(cljs.core.mapv.call(null, (kv: unknown) => {
            const el = cljs.core.get.call(null, cljs.core.deref(cljs.core.second(kv)), kw('content'));
            if (!el) return '';
            // An inline result carries a truncated span beside the full one,
            // so textContent says everything twice.
            const full = el.querySelector ? el.querySelector('.full') : null;
            return (full || el).textContent || '';
        }, cljs.core.get.call(null, cljs.core.deref(ed), kw('widgets'))));
    }, [file]) as string[];
}

test('a ClojureScript buffer changes the editor it is open in', async ({ window, ltErrors }) => {
    const dir = scratchDir('live');
    const file = path.join(dir, 'probe.cljs');
    // A namespace starting with lt. routes to the window rather than to a
    // project REPL — see lt.plugins.clojure/connect-cljs.
    fs.writeFileSync(file, [
        '(ns lt.e2e-probe',
        '  (:require [lt.objs.command :as cmd]))',
        '',
        '(def answer (* 6 7))',
        '',
        '(cmd/command {:command :e2e.defined-while-running',
        '              :desc "Defined by evaluating a buffer"',
        '              :exec (fn [] answer)})',
        ''
    ].join('\n'));

    expect(await window.evaluate(
        "!!lt.objs.command.by_id(cljs.core.keyword.call(null,'e2e.defined-while-running'))")).toBe(false);

    await openForms(window, file, 3);
    await window.evaluate(([f]) => {
        const lt = (globalThis as any).lt, cljs = (globalThis as any).cljs;
        const ed = cljs.core.first.call(null, lt.objs.editor.pool.by_path(f));
        lt.object.raise.call(null, ed, cljs.core.keyword.call(null, 'eval'));
    }, [file]);

    // The first evaluation of a session loads cljs.core's analysis, which is
    // most of the cost and is paid once.
    await window.waitForFunction(
        "!!lt.objs.command.by_id(cljs.core.keyword.call(null,'e2e.defined-while-running'))",
        null, { timeout: 90_000 });

    // The command did not exist when the editor started. It does now, it runs,
    // and it can see a def from the same buffer.
    expect(await window.evaluate(
        "lt.objs.command.exec_BANG_(cljs.core.keyword.call(null,'e2e.defined-while-running'))")).toBe(42);

    // A result beside each form, which is what makes it a REPL rather than an
    // eval button: the ns form, the def as a var, and the command as nil.
    expect(await inlineResults(window, file)).toEqual(['nil', "#'lt.e2e-probe/answer", 'nil']);

    expect(await window.evaluate("cljs.core.pr_str(lt.objs.cljs_compiler.describe())"))
        .toContain(':status :ready');
    expect(await ltErrors()).toEqual([]);

    fs.rmSync(dir, { recursive: true, force: true });
});

test('a CSS buffer restyles the running editor', async ({ window, ltErrors }) => {
    await connectLocalClient(window);
    const dir = scratchDir('css');
    const file = path.join(dir, 'probe.css');
    fs.writeFileSync(file, '.CodeMirror { background: rgb(17, 34, 51) !important; }\n');

    // Opened first: a fresh window shows the Welcome tab, which is not a
    // CodeMirror, so there is nothing to read a background off until a file is
    // in front of you.
    await window.evaluate(
        ([f]) => (globalThis as any).lt.objs.command.exec_BANG_(
            (globalThis as any).cljs.core.keyword.call(null, 'open-path'), f),
        [file]);
    await window.waitForFunction(
        ([f]) => {
            const lt = (globalThis as any).lt, cljs = (globalThis as any).cljs;
            return !!cljs.core.first.call(null, lt.objs.editor.pool.by_path(f))
                && !!document.querySelector('.CodeMirror');
        }, [file], { timeout: 30_000 });

    const before = await window.evaluate(
        "getComputedStyle(document.querySelector('.CodeMirror')).backgroundColor");

    await window.evaluate(([f]) => {
        const lt = (globalThis as any).lt, cljs = (globalThis as any).cljs;
        const ed = cljs.core.first.call(null, lt.objs.editor.pool.by_path(f));
        lt.object.raise.call(null, ed, cljs.core.keyword.call(null, 'eval'));
    }, [file]);

    await expect.poll(async () => await window.evaluate(
        "getComputedStyle(document.querySelector('.CodeMirror')).backgroundColor"))
        .toBe('rgb(17, 34, 51)');
    expect(before).not.toBe('rgb(17, 34, 51)');
    expect(await ltErrors()).toEqual([]);

    fs.rmSync(dir, { recursive: true, force: true });
});

test('a JavaScript buffer evaluates straight into the window', async ({ window, ltErrors }) => {
    await connectLocalClient(window);
    const dir = scratchDir('js');
    const file = path.join(dir, 'probe.js');
    fs.writeFileSync(file, 'window.__e2e_marker = "evaluated in the window";\n');

    await window.evaluate(
        ([f]) => (globalThis as any).lt.objs.command.exec_BANG_(
            (globalThis as any).cljs.core.keyword.call(null, 'open-path'), f),
        [file]);
    await window.waitForFunction(
        ([f]) => {
            const lt = (globalThis as any).lt, cljs = (globalThis as any).cljs;
            return !!cljs.core.first.call(null, lt.objs.editor.pool.by_path(f));
        }, [file], { timeout: 30_000 });

    await window.evaluate(([f]) => {
        const lt = (globalThis as any).lt, cljs = (globalThis as any).cljs;
        const ed = cljs.core.first.call(null, lt.objs.editor.pool.by_path(f));
        lt.object.raise.call(null, ed, cljs.core.keyword.call(null, 'eval'));
    }, [file]);

    await expect.poll(async () => await window.evaluate("window.__e2e_marker"))
        .toBe('evaluated in the window');
    expect(await ltErrors()).toEqual([]);

    fs.rmSync(dir, { recursive: true, force: true });
});
