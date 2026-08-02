// The editor changing itself while it runs — see doc/live-editing.md.
//
// This is the feature Light Table is named for, and it rests on things no
// unit test can see: that the release bundle still exposes lt.* as globals,
// that the analysis cache shipped and can be read from a file:// page, and
// that a compiled form reaches the same functions the window is holding.

import * as fs from 'node:fs';
import * as path from 'node:path';
import { test, expect, evalClj, evalData, scratchDir, connectLocalClient, waitFor } from './fixtures';
import type { Page } from '@playwright/test';

/** Open `file` and wait until the editor has parsed it into top-level forms. */
async function openForms(window: Page, file: string, expected: number) {
    await evalClj(window, `(do (cmd/exec! :open-path "${file}") :opening)`);
    await waitFor(window, `
        (= ${expected} (some-> (first (pool/by-path "${file}"))
                               lt.plugins.clojure/forms-in
                               count))`, { timeout: 60_000 });
}

/** Evaluate the whole buffer, the way ctrl-enter on the file does. */
async function evalAll(window: Page, file: string) {
    await evalClj(window, `(do (object/raise (first (pool/by-path "${file}")) :eval) :evaluating)`);
}

/** The results drawn beside each form, in order. */
async function inlineResults(window: Page, file: string): Promise<string[]> {
    return await evalData<string[]>(window, `
        (let [ed (first (pool/by-path "${file}"))]
          (mapv (fn [[_ res]]
                  (if-let [el (:content @res)]
                    ;; An inline result carries a truncated span beside the
                    ;; full one, so textContent says everything twice.
                    (or (some-> ^js (.querySelector el ".full") .-textContent)
                        (.-textContent ^js el))
                    ""))
                (:widgets @ed)))`);
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

    const defined = '(some? (cmd/by-id :e2e.defined-while-running))';
    expect(await evalData(window, defined)).toBe(false);

    await openForms(window, file, 3);
    await evalAll(window, file);

    // The first evaluation of a session loads cljs.core's analysis, which is
    // most of the cost and is paid once.
    await waitFor(window, defined, { timeout: 90_000 });

    // The command did not exist when the editor started. It does now, it runs,
    // and it can see a def from the same buffer.
    expect(await evalData(window, '(cmd/exec! :e2e.defined-while-running)')).toBe(42);

    // A result beside each form, which is what makes it a REPL rather than an
    // eval button: the ns form, the def as a var, and the command as nil.
    expect(await inlineResults(window, file)).toEqual(['nil', "#'lt.e2e-probe/answer", 'nil']);

    expect(await evalData(window, '(:status (lt.objs.cljs-compiler/describe))')).toBe('ready');
    expect(await ltErrors()).toEqual([]);

    fs.rmSync(dir, { recursive: true, force: true });
});

test('a CSS buffer restyles the running editor', async ({ window, ltErrors }) => {
    await connectLocalClient(window);
    const dir = scratchDir('css');
    const file = path.join(dir, 'probe.css');
    // Both names, because the rule has to reach whichever engine drew the
    // editor. That the stylesheets in deploy/core/css say only the first is
    // the gap this exposes rather than the one it is testing.
    fs.writeFileSync(file,
        '.CodeMirror, .cm-editor { background: rgb(17, 34, 51) !important; }\n');

    // Opened first: a fresh window shows the Welcome tab, which is not a
    // CodeMirror, so there is nothing to read a background off until a file is
    // in front of you.
    await evalClj(window, `(do (cmd/exec! :open-path "${file}") :opening)`);
    await waitFor(window, `
        (and (some? (first (pool/by-path "${file}")))
             (some? (lt.util.dom/$ ".CodeMirror, .cm-editor")))`, { timeout: 30_000 });

    const before = await window.evaluate(
        "getComputedStyle(document.querySelector('.CodeMirror, .cm-editor')).backgroundColor");

    await evalAll(window, file);

    await expect.poll(async () => await window.evaluate(
        "getComputedStyle(document.querySelector('.CodeMirror, .cm-editor')).backgroundColor"))
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

    await evalClj(window, `(do (cmd/exec! :open-path "${file}") :opening)`);
    await waitFor(window, `(some? (first (pool/by-path "${file}")))`, { timeout: 30_000 });

    await evalAll(window, file);

    await expect.poll(async () => await window.evaluate("window.__e2e_marker"))
        .toBe('evaluated in the window');
    expect(await ltErrors()).toEqual([]);

    fs.rmSync(dir, { recursive: true, force: true });
});
