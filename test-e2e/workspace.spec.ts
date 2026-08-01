// Adding a folder to the workspace, and taking it back out.
//
// Removing one threw: `The "path" argument must be of type string. Received
// null`, from Node, in a stack whose only Light Table frame is
// `object/destroy!`. The folder came out of the tree and the sidebar was left
// with an error in the console for an operation that had, as far as anyone
// could see, worked.

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

/** Whatever the editor's own console has been told about. */
const consoleErrors = (window: Page) => window.evaluate(() => {
    const w = globalThis as any;
    const el = w.lt.object.__GT_content(w.lt.objs.console.console) as HTMLElement;
    return Array.from(el.querySelectorAll('li.error')).map(
        (n) => ((n as HTMLElement).innerText || '').slice(0, 300));
});

test('a folder can be taken out of the workspace again', async ({ window }) => {
    const dir = scratchDir('ws');
    fs.writeFileSync(path.join(dir, 'a.txt'), 'a\n');

    // Counted against what is already open rather than from zero: the fixture
    // shares one boot, and other tests put folders in this workspace.
    const rows = () => evalClj(window, '(count (:folders @lt.objs.sidebar.workspace/tree))');
    const started = Number(await rows());

    await evalClj(window, `
        (do (object/raise lt.objs.workspace/current-ws :add.folder! "${dir}") :added)`);
    await expect.poll(async () => Number(await rows())).toBe(started + 1);

    const before = (await consoleErrors(window)).length;

    // The destroy that was throwing. A tree item is rendered from its own atom,
    // and `destroy!` resets that atom to nil — so every view still watching it
    // recomputed against nothing, and the one that reads the folder's path
    // handed nil to Node.
    await evalClj(window, `
        (do (object/raise lt.objs.workspace/current-ws :remove.folder! "${dir}") :removed)`);
    await expect.poll(async () => await evalClj(window,
        `(count (:folders @lt.objs.workspace/current-ws))`)).toBe(String(started));

    const after = await consoleErrors(window);
    expect(after.length, after.slice(before).join('\n')).toBe(before);
    expect(after.join(' ')).not.toContain('must be of type string');

    // And the sidebar agrees, rather than keeping a row for a folder that is
    // no longer in the workspace.
    expect(Number(await rows())).toBe(started);

    fs.rmSync(dir, { recursive: true, force: true });
});
