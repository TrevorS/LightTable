// Find and replace, through the find bar's own path.
//
// `lt.objs.find` used to call four CodeMirror 5 global commands directly. Those
// come from a vendored addon and operate on a CodeMirror 5 editor, so on
// CodeMirror 6 they were a TypeError in the search box. They are four functions
// on `lt.objs.editor` now, and the find bar names no editor at all.
//
// So this drives the commands a person's keys are bound to, and runs the whole
// thing twice.

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

const DOC = 'alpha one\nbeta two\nalpha three\ngamma four\n';

/** One engine now. The constant stays so the file reads as it did. */
const engine = ':cm6';
test(`find moves the cursor from match to match, on ${engine}`, async ({ window }) => {
    const dir = scratchDir('find');
    const file = path.join(dir, `probe${engine.slice(1)}.txt`);
    fs.writeFileSync(file, DOC);

    await evalClj(window, `
        (do (cmd/exec! :open-path "${file}") :opened)`);
    await expect.poll(async () => await evalClj(window,
        `(count (pool/by-path "${file}"))`)).toBe('1');

    // The cursor starts at the top, so the first match is on line 0 and the
    // next one is on line 2 — searching wraps rather than stopping.
    expect(await evalClj(window, `
        (let [ed (first (pool/by-path "${file}"))]
          (lt.objs.editor/move-cursor ed {:line 0 :ch 0})
          (lt.objs.editor/find! ed "alpha")
          [(:line (lt.objs.editor/->cursor ed))
           (do (lt.objs.editor/find-next! ed) (:line (lt.objs.editor/->cursor ed)))
           (do (lt.objs.editor/find-next! ed) (:line (lt.objs.editor/->cursor ed)))])`))
        .toBe('[0 2 0]');

    // Backwards from there is the other one.
    expect(await evalClj(window, `
        (let [ed (first (pool/by-path "${file}"))]
          (lt.objs.editor/find-next! ed true)
          (:line (lt.objs.editor/->cursor ed)))`)).toBe('2');

    // And clearing is not an error when there was nothing to clear.
    await evalClj(window, `
        (let [ed (first (pool/by-path "${file}"))]
          (lt.objs.editor/clear-search! ed)
          (lt.objs.editor/clear-search! ed)
          :cleared)`);

    await evalClj(window, `
        (do (doseq [ed (pool/by-path "${file}")] (object/raise ed :close)) :closed)`);
    fs.rmSync(dir, { recursive: true, force: true });
});

test(`replace rewrites one match and then all of them, on ${engine}`, async ({ window }) => {
    const dir = scratchDir('replace');
    const file = path.join(dir, `probe${engine.slice(1)}.txt`);
    fs.writeFileSync(file, DOC);

    await evalClj(window, `
        (do (cmd/exec! :open-path "${file}") :opened)`);
    await expect.poll(async () => await evalClj(window,
        `(count (pool/by-path "${file}"))`)).toBe('1');

    expect(await evalClj(window, `
        (let [ed (first (pool/by-path "${file}"))]
          (lt.objs.editor/move-cursor ed {:line 0 :ch 0})
          (lt.objs.editor/find! ed "alpha")
          (lt.objs.editor/replace! ed "ALPHA")
          (lt.objs.editor/->val ed))`))
        .toBe('"ALPHA one\\nbeta two\\nalpha three\\ngamma four\\n"');

    expect(await evalClj(window, `
        (let [ed (first (pool/by-path "${file}"))]
          (lt.objs.editor/find! ed "alpha")
          (lt.objs.editor/replace! ed "ALPHA" false true)
          (lt.objs.editor/->val ed))`))
        .toBe('"ALPHA one\\nbeta two\\nALPHA three\\ngamma four\\n"');

    await evalClj(window, `
        (do (doseq [ed (pool/by-path "${file}")] (object/raise ed :close)) :closed)`);
    fs.rmSync(dir, { recursive: true, force: true });
});

test('a lower-case query ignores case and a capital makes it matter', async ({ window }) => {
    // CodeMirror 5's rule, kept because nobody is told it and everybody relies
    // on it. Asserted on CodeMirror 6, where it had to be written out.
    const out = await window.evaluate(() => {
        const w = globalThis as any;
        const host = document.createElement('div');
        document.body.appendChild(host);
        // The capitalised one is on the second line, so case decides which line
        // the cursor lands on and the assertion cannot pass by accident.
        const ed = w.ltCm6Editor.makeCm6Editor(host, { value: 'aaa alpha\nbbb Alpha\n' });

        ed.setCursor({ line: 0, ch: 0 });
        ed.search('alpha');
        const loose = ed.getCursor().line;

        ed.setCursor({ line: 0, ch: 0 });
        ed.search('Alpha');
        const strict = ed.getCursor().line;

        host.remove();
        return [loose, strict];
    });

    expect(out).toEqual([0, 1]);
});
