// The things Light Table draws inside a document.
//
// An inline result and a doc are the same idea in two shapes: a block under the
// line, and a widget at the end of it. Neither is a nicety — showing a value
// beside the expression that produced it is the reason this editor exists — and
// both reach the editor through an API written for the one before it.

import * as fs from 'node:fs';
import * as path from 'node:path';
import { test, expect, evalClj as evalWith, insideEditor, scratchDir } from './fixtures';
import type { Page } from '@playwright/test';

/** Longer than the default, because an evaluation round-trips through a client. */
const evalClj = (window: Page, source: string) => evalWith(window, source, { tries: 200 });


async function open(window: Page, engine: string, name: string,
                    contents: string): Promise<string> {
    const file = path.join(scratchDir('inline'), name);
    fs.writeFileSync(file, contents);
    await evalClj(window, `
        (do (cmd/exec! :open-path "${file}") :opened)`);
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
    insideEditor<{ count: number, text: string }>(window, file, `
        (let [found (array-seq (.querySelectorAll root "${selector}"))]
          {:count (count found)
           :text (or (some-> ^js (first found) .-innerText) "")})`);

/** One engine now. The constant stays so the file reads as it did. */
const engine = ':cm6';
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

test(`a long result is truncated until you click it, on ${engine}`, async ({ window }) => {
    // The class on the *root* is the whole of this: both halves are always
    // drawn and `.result-mark.open` decides which one displays. That is the one
    // thing a view cannot reach — Replicant renders inside the element the
    // object hands out, never the element itself — so it goes through
    // `lt.ui/node`'s `attrs`, and it is worth asserting rather than assuming.
    const file = await open(window, engine, `long${engine.slice(1)}.txt`,
        'alpha\nbeta\ngamma\n');

    const long = 'x'.repeat(120);
    await evalClj(window, `
        (let [ed (first (pool/by-path "${file}"))]
          (object/raise ed :editor.result "${long}" {:line 0 :ch 5})
          :shown)`);

    const mark = async () => await insideEditor<{ classes: string, shown: string }>(window, file, `
        (when-let [^js el (.querySelector root ".result-mark")]
          {:classes (.-className el)
           ;; innerText is what is visible, so this is the CSS answering rather
           ;; than the markup: both spans are in the DOM either way.
           :shown (.-innerText el)})`);

    await expect.poll(async () => (await mark())?.classes).not.toContain('open');
    expect((await mark())!.shown).toContain('…');
    expect((await mark())!.shown.length).toBeLessThan(long.length);

    // Clicking is `::expand-on-click`, which sets `:open` on the object. The
    // root's class follows it, and the full text is what displays.
    await window.locator('.result-mark .truncated').first().click();
    await expect.poll(async () => (await mark())?.classes).toContain('open');
    expect((await mark())!.shown).toBe(long);

    // And double-clicking shrinks it back, which is the same class going the
    // other way rather than a second mechanism.
    await window.locator('.result-mark .full').first().dblclick();
    await expect.poll(async () => (await mark())?.classes).not.toContain('open');
    expect((await mark())!.shown).toContain('…');

    await close(window, file);
});

test(`copying an underline result copies what it says, on ${engine}`, async ({ window }) => {
    // `:result` used to be a DOM node a caller built, and copying it read that
    // node's element children. It is hiccup now, so the copy reads what was
    // drawn — and that fixes a case the old one never handled: for a result
    // whose `:result` is a plain string there were no element children to read,
    // and mapping over a string's `.children` threw.
    //
    // A file each, because the two paths do not clean up after one another:
    // `lt.plugins.doc/inline-doc` puts its widget in the map without clearing
    // whatever was already at that line, so a doc over an existing underline
    // result leaves the old node on screen. That is a real if narrow leak and
    // not what this is about.
    for (const [what, name, raise, expected] of [
        ['a string', 'copystr',
         `(object/raise ed :editor.result.underline "a docstring" {:line 1 :ch 0} {})`,
         'a docstring'],
        ['hiccup', 'copydoc',
         `(do (object/add-tags ed [:docable])
              (object/raise ed :editor.doc.show!
                            {:name "beta" :ns "probe" :doc "what beta does"
                             :loc {:line 1 :ch 0}}))`,
         'what beta does']] as const) {
        const file = await open(window, engine, `${name}${engine.slice(1)}.txt`,
            'alpha\nbeta\ngamma\n');

        await evalClj(window, `
            (do (lt.objs.platform/copy "")
                (let [ed (first (pool/by-path "${file}"))] ${raise})
                :shown)`);
        await expect.poll(async () => (await inside(window, file, '.underline-result'))?.count,
                          { message: what }).toBe(1);

        await evalClj(window, `
            (do (doseq [w (vals (:widgets @(first (pool/by-path "${file}"))))]
                  (object/raise w :copy))
                :copied)`);
        expect(await evalClj(window, '(lt.objs.platform/paste)'), what).toContain(expected);

        await close(window, file);
    }
});
