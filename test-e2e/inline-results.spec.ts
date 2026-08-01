// The things Light Table draws inside a document, on both engines.
//
// An inline result and a doc are the same idea in two shapes: a block under the
// line, and a widget at the end of it. Neither is a nicety — showing a value
// beside the expression that produced it is the reason this editor exists — and
// both reach CodeMirror through an API written for the other engine.

import * as fs from 'node:fs';
import * as path from 'node:path';
import { test, expect, scratchDir } from './fixtures';
import type { Page } from '@playwright/test';

async function evalClj(window: Page, source: string): Promise<any> {
    let job = await window.evaluate(
        ([s]) => (globalThis as any).lt.objs.control.request('eval', { source: s }), [source]);
    for (let i = 0; i < 200 && job.status === 'working'; i++) {
        await window.waitForTimeout(50);
        job = await window.evaluate(
            ([id]) => (globalThis as any).lt.objs.control.request('job', { job: id }), [job.id]);
    }
    if (job.status !== 'completed') throw new Error(`${job.status}: ${job.error}\n${source}`);
    return job.result;
}

async function open(window: Page, engine: string, name: string,
                    contents: string): Promise<string> {
    const file = path.join(scratchDir('inline'), name);
    fs.writeFileSync(file, contents);
    await evalClj(window, `
        (do (lt.objs.editor/set-engine! ${engine})
            (cmd/exec! :open-path "${file}")
            :opened)`);
    await expect.poll(async () => await evalClj(window,
        `(count (pool/by-path "${file}"))`)).toBe('1');
    return file;
}

async function close(window: Page, file: string): Promise<void> {
    await evalClj(window, `
        (do (doseq [ed (pool/by-path "${file}")] (object/raise ed :close)) :closed)`);
    fs.rmSync(path.dirname(file), { recursive: true, force: true });
}

/** What is drawn inside the editor showing `file`. */
const inside = (window: Page, file: string, selector: string) =>
    window.evaluate(([p, s]) => {
        const w = globalThis as any;
        const ed = w.cljs.core.first.call(null, w.lt.objs.editor.pool.by_path(p));
        if (!ed) return null;
        const root = w.lt.objs.editor.__GT_elem(ed) as HTMLElement;
        const found = root.querySelectorAll(s);
        return { count: found.length, text: (found[0] as HTMLElement)?.innerText ?? '' };
    }, [file, selector] as [string, string]);

for (const engine of [':cm5', ':cm6']) {
    test(`a doc appears under the line it is about, on ${engine}`, async ({ window }) => {
        // The path "Toggle docs" takes: a language answers `:editor.doc.show!`,
        // and what draws it is an underline result — a block widget under the
        // line, which on CodeMirror 6 is a band.
        const file = await open(window, engine, `doc${engine.slice(1)}.txt`,
            'alpha\nbeta\ngamma\n');

        await evalClj(window, `
            (let [ed (first (pool/by-path "${file}"))]
              (object/raise ed :editor.result.underline "a docstring" {:line 1 :ch 0} {})
              :shown)`);

        await expect.poll(async () => (await inside(window, file, '.underline-result'))?.count)
            .toBe(1);
        expect((await inside(window, file, '.underline-result'))?.text)
            .toContain('a docstring');

        await close(window, file);
    });

    test(`Toggle docs shows what a language answered, on ${engine}`, async ({ window }) => {
        // One step further out than the test above: this is the behavior a
        // language server's hover reply lands in, so it covers `inline-doc` and
        // the doc's own markup rather than only the widget underneath.
        const file = await open(window, engine, `show${engine.slice(1)}.txt`,
            'alpha\nbeta\ngamma\n');

        // Tagged by hand, the way a language server tags an editor whose server
        // says it answers hover. The behavior hangs off `:docable`, not
        // `:editor` — a plain text file has nothing to document.
        await evalClj(window, `
            (let [ed (first (pool/by-path "${file}"))]
              (object/add-tags ed [:docable])
              (object/raise ed :editor.doc.show!
                            {:name "beta" :ns "probe" :doc "what beta does"
                             :loc {:line 1 :ch 0}})
              :shown)`);

        await expect.poll(async () => (await inside(window, file, '.inline-doc'))?.count)
            .toBe(1);
        expect((await inside(window, file, '.inline-doc'))?.text).toContain('what beta does');

        await close(window, file);
    });

    test(`an inline result appears beside the line, on ${engine}`, async ({ window }) => {
        // The other shape: a bookmark carrying a widget, which sits at a
        // position in the text rather than under it.
        const file = await open(window, engine, `res${engine.slice(1)}.txt`,
            'alpha\nbeta\ngamma\n');

        await evalClj(window, `
            (let [ed (first (pool/by-path "${file}"))]
              (object/raise ed :editor.result "42" {:line 1 :ch 4})
              :shown)`);

        await expect.poll(async () => (await inside(window, file, '.inline-result'))?.count)
            .toBe(1);
        expect((await inside(window, file, '.inline-result'))?.text).toContain('42');

        await close(window, file);
    });
}
