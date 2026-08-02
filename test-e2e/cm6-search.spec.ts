// Find and replace.
//
// `lt.objs.find` used to call four CodeMirror 5 global commands directly. Those
// come from a vendored addon and operate on a CodeMirror 5 editor, so on
// CodeMirror 6 they were a TypeError in the search box. They are four functions
// on `lt.objs.editor` now, and the find bar names no editor at all.
//
// Most of what is here drives those four, which is what the find bar drives.
// The last test drives the bar itself — the fields, the button and the space
// it takes from the tabs — because nothing else did, and the bar's markup went
// from three `defui` to one `lt.ui/element` with none of it covered.

import * as fs from 'node:fs';
import * as path from 'node:path';
import { test, expect, evalClj, scratchDir } from './fixtures';


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

test('the find bar is the bar you type in, and it gives the space back', async ({ window }) => {
    // The bar is drawn once and never again, which is the whole rendering
    // decision: two fields and a button, none of it a function of anything.
    // What that leaves worth asserting is the wiring — the handlers, the
    // context it takes, and the height contract it has with the tabs above it.
    const dir = scratchDir('find-bar');
    const file = path.join(dir, 'typed.txt');
    fs.writeFileSync(file, DOC);

    await evalClj(window, `(do (cmd/exec! :open-path "${file}") :opened)`);
    await expect.poll(async () => await evalClj(window,
        `(count (pool/by-path "${file}"))`)).toBe('1');
    await evalClj(window, `
        (do (lt.objs.editor/move-cursor (first (pool/by-path "${file}")) {:line 0 :ch 0}) :top)`);

    const bar = window.locator('#find-bar');
    // Closed is height 0 rather than display:none — `#find-bar` is in the
    // statusbar strip at :order -1 and the strip animates the height.
    expect(await bar.evaluate((n) => n.getBoundingClientRect().height)).toBe(0);

    await evalClj(window, '(do (cmd/exec! :find.show) :shown)');
    await expect.poll(async () =>
        await bar.evaluate((n) => n.getBoundingClientRect().height)).toBe(30);

    // Focusing takes the keyboard context, which is what makes Enter mean
    // "next match" rather than "newline".
    await bar.locator('input.find').click();
    expect(await evalClj(window, '(boolean (lt.objs.context/->obj :find-bar))')).toBe('true');

    // Typed, not dispatched: the `:input` handler is the thing being asserted,
    // and `:search!` is debounced behind it.
    await bar.locator('input.find').fill('alpha');
    await expect.poll(async () => await evalClj(window, '(:searching? @lt.objs.find/bar)')).toBe('true');
    await expect.poll(async () => await evalClj(window,
        `(:line (lt.objs.editor/->cursor (first (pool/by-path "${file}"))))`)).toBe('0');

    await evalClj(window, '(do (cmd/exec! :find.next) :next)');
    await expect.poll(async () => await evalClj(window,
        `(:line (lt.objs.editor/->cursor (first (pool/by-path "${file}"))))`)).toBe('2');

    // The one control in the bar, clicked rather than commanded.
    await bar.locator('input.replace').fill('ALPHA');
    await bar.locator('button').click();
    await expect.poll(async () => await evalClj(window,
        `(lt.objs.editor/->val (first (pool/by-path "${file}")))`))
        .toBe('"ALPHA one\\nbeta two\\nALPHA three\\ngamma four\\n"');

    // And hiding gives the height back, which is the contract the strip has
    // with the tabs — a bar that closed without it would leave a gap.
    await evalClj(window, '(do (cmd/exec! :find.hide) :hidden)');
    await expect.poll(async () =>
        await bar.evaluate((n) => n.getBoundingClientRect().height)).toBe(0);

    await evalClj(window, `
        (do (doseq [ed (pool/by-path "${file}")] (object/raise ed :close)) :closed)`);
    fs.rmSync(dir, { recursive: true, force: true });
});
