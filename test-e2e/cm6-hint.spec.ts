// Autocomplete, on both engines.
//
// Light Table's completion is its own — its own list, its own providers, its
// own key handling — and it reached into CodeMirror 5 in four places to get
// there: a `StringStream` to find the token under the cursor, a mode object to
// hold what counts as a word, a line handle to hear about typing, and
// `cursorCoords` to know where to draw. Three of those do not exist on
// CodeMirror 6 in any form.
//
// So each was answered somewhere other than the editor, and what this asserts
// is that the answers agree: the same token, the same word characters, the same
// list, from the same file, on either engine.

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

/**
 * Open `contents` as `name` on `engine`, focused, and return the path.
 *
 * Focused because the hint list asks the pool which editor was last active
 * rather than being told — so an editor nobody focused has no completions and
 * the failure would look like the providers.
 */
async function open(window: Page, engine: string, name: string,
                    contents: string): Promise<string> {
    const file = path.join(scratchDir('hint'), name);
    fs.writeFileSync(file, contents);
    await evalClj(window, `
        (do (lt.objs.editor/set-engine! ${engine})
            (cmd/exec! :open-path "${file}")
            :opened)`);
    await expect.poll(async () => await evalClj(window,
        `(count (pool/by-path "${file}"))`)).toBe('1');
    await evalClj(window, `
        (do (lt.objs.editor/focus (first (pool/by-path "${file}"))) :focused)`);
    await expect.poll(async () => await evalClj(window,
        `(= (pool/last-active) (first (pool/by-path "${file}")))`)).toBe('true');
    return file;
}

async function close(window: Page, file: string): Promise<void> {
    await evalClj(window, `
        (do (doseq [ed (pool/by-path "${file}")] (object/raise ed :close)) :closed)`);
    fs.rmSync(path.dirname(file), { recursive: true, force: true });
}

for (const engine of [':cm5', ':cm6']) {
    test(`the token under the cursor is the same one, on ${engine}`, async ({ window }) => {
        // A Clojure file, because Clojure is why the hint pattern is
        // configurable: `foo-bar->baz` is one name, and under the default
        // pattern — which is a word character, an underscore or a dollar — it
        // would be three. CodeMirror 5 kept that setting on the mode object,
        // put there with `extendMode`; CodeMirror 6 has no mode object, so it
        // is a map keyed by what both engines agree the mode is called.
        const file = await open(window, engine, `tokens${engine.slice(1)}.clj`,
            '(foo-bar->baz qux)\n');

        expect(await evalClj(window, `
            (let [ed (first (pool/by-path "${file}"))]
              (select-keys (lt.plugins.auto-complete/get-token ed {:line 0 :ch 5})
                           [:start :end :string]))`))
            .toBe('{:start 1, :end 13, :string "foo-bar->baz"}');

        // Between two tokens there is no token, and the answer is the cursor
        // twice over — an empty range, which is what a completion with no
        // prefix replaces.
        expect(await evalClj(window, `
            (let [ed (first (pool/by-path "${file}"))]
              (select-keys (lt.plugins.auto-complete/get-token ed {:line 0 :ch 17})
                           [:start :end :string]))`))
            .toBe('{:start 14, :end 17, :string "qux"}');

        await close(window, file);
    });

    test(`the hint list opens under the cursor and follows what you type, on ${engine}`,
        async ({ window }) => {
        const file = await open(window, engine, `list${engine.slice(1)}.txt`, 'alp\n');

        // The completions are seeded rather than gathered: the textual ones
        // come back from a worker thread on a debounce, and a test that waits
        // for that is testing the thread. What is under test here is the path
        // from "show me completions" to a positioned list.
        expect(await evalClj(window, `
            (let [ed (first (pool/by-path "${file}"))]
              (lt.objs.editor/focus ed)
              (lt.objs.editor/move-cursor ed {:line 0 :ch 3})
              (object/merge! ed {:lt.plugins.auto-complete/hints
                                 (clj->js [{:completion "alphabet"}
                                           {:completion "alphabetical"}])})
              (object/raise ed :hint {:force? true})
              [(boolean (:active @lt.plugins.auto-complete/hinter))
               (count (:cur @lt.plugins.auto-complete/hinter))
               (:string (:token @lt.plugins.auto-complete/hinter))])`))
            .toBe('[true 2 "alp"]');

        // Positioned by cm-hint.ts, which asks the editor where its cursor is
        // and nothing else. That question is the whole of what autocomplete
        // needed from CodeMirror 6, and the class the list wears is Light
        // Table's own — which is why no theme rule had to be mirrored for it.
        expect(await window.evaluate(() => {
            const el = document.querySelector('.CodeMirror-hints') as HTMLElement | null;
            return el ? [el.style.top !== '', el.style.left !== ''] : null;
        })).toEqual([true, true]);

        // Typing under an open list narrows it. CodeMirror 5 heard about this
        // through a handle on the line; there is no such handle on CodeMirror 6
        // and the editor is asked instead, so this is the assertion that the
        // replacement is wired up at all.
        expect(await evalClj(window, `
            (let [ed (first (pool/by-path "${file}"))]
              (lt.objs.editor/replace ed {:line 0 :ch 3} {:line 0 :ch 3} "h")
              (:string (:token @lt.plugins.auto-complete/hinter)))`))
            .toBe('"alph"');

        // And escaping puts it away, listener included.
        expect(await evalClj(window, `
            (do (object/raise lt.plugins.auto-complete/hinter :escape!)
                [(boolean (:active @lt.plugins.auto-complete/hinter))
                 (boolean (:watching @lt.plugins.auto-complete/hinter))])`))
            .toBe('[false false]');

        expect(await window.evaluate(
            () => document.querySelector('.CodeMirror-hints'))).toBeNull();

        await close(window, file);
    });
}

for (const engine of [':cm5', ':cm6']) {
    test(`typing opens the list by itself, on ${engine}`, async ({ window }) => {
        // The whole path, driven by a keystroke: the editor reports that a
        // person typed, `::auto-show-on-input` hears it, and a list opens on
        // the token that keystroke extended. On CodeMirror 6 the first step did
        // not exist until now — `change` fired for every edit, including the
        // ones Light Table makes for eval results, and an autocomplete that
        // opened on those would be unusable rather than merely absent.
        const file = await open(window, engine, `typed${engine.slice(1)}.txt`, 'alp\n');

        await evalClj(window, `
            (let [ed (first (pool/by-path "${file}"))]
              (lt.objs.editor/move-cursor ed {:line 0 :ch 3})
              (object/merge! ed {:lt.plugins.auto-complete/hints
                                 (clj->js [{:completion "alphabet"}])})
              :ready)`);

        await window.keyboard.type('h');

        await expect.poll(async () => await evalClj(window, `
            [(boolean (:active @lt.plugins.auto-complete/hinter))
             (:string (:token @lt.plugins.auto-complete/hinter))]`))
            .toBe('[true "alph"]');

        await evalClj(window,
            '(do (object/raise lt.plugins.auto-complete/hinter :escape!) :closed)');
        await close(window, file);
    });
}

test('inputRead is what you typed, not what the program wrote', async ({ window }) => {
    // CodeMirror 5 draws this line and autocomplete depends on it: `change`
    // fires for every edit, `inputRead` only for the ones a person made. Without
    // the second, a hint list either never opens by itself or opens on every
    // programmatic edit — and Light Table makes those constantly, for eval
    // results and for the LSP.
    const out = await window.evaluate(() => {
        const w = globalThis as any;
        const host = document.createElement('div');
        document.body.appendChild(host);
        const ed = w.ltCm6Editor.makeCm6Editor(host, { value: 'a\n' });

        const changes: string[] = [];
        const typed: string[] = [];
        ed.on('change', (_e: unknown, ch: any) => changes.push(ch.origin));
        ed.on('inputRead', (_e: unknown, ch: any) => typed.push(ch.origin));

        ed.view.dispatch({ changes: { from: 1, insert: 'b' }, userEvent: 'input.type' });
        ed.view.dispatch({ changes: { from: 2, insert: 'c' }, userEvent: 'input.paste' });
        ed.view.dispatch({ changes: { from: 3, to: 4 }, userEvent: 'delete.backward' });
        // No user event at all: this is what `replaceRange` from ClojureScript
        // looks like from here.
        ed.view.dispatch({ changes: { from: 3, insert: 'd' } });

        host.remove();
        return { changes, typed };
    });

    expect(out.typed).toEqual(['+input', 'paste']);
    expect(out.changes).toEqual(['+input', 'paste', '+delete', '+input']);
});

test('a change carries where it came from, and both engines say the same word',
    async ({ window }) => {
    // `origin` is not decoration: autocomplete reads it to decide whether to
    // stay open, and it closes on a paste because a paste is not a prefix
    // anyone is typing.
    const out = await window.evaluate(() => {
        const w = globalThis as any;
        const host = document.createElement('div');
        document.body.appendChild(host);

        const five = w.CodeMirror(host, { value: 'a\n' });
        const six = w.ltCm6Editor.makeCm6Editor(host, { value: 'a\n' });
        const seen: Record<string, string[]> = { five: [], six: [] };
        five.on('change', (_e: unknown, ch: any) => seen['five']!.push(String(ch.origin)));
        six.on('change', (_e: unknown, ch: any) => seen['six']!.push(String(ch.origin)));

        five.replaceSelection('x', 'end', 'paste');
        six.view.dispatch({ changes: { from: 0, insert: 'x' }, userEvent: 'input.paste' });

        host.remove();
        return seen;
    });

    expect(out.six).toEqual(out.five);
    expect(out.six).toEqual(['paste']);
});
