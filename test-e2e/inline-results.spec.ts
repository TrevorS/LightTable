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
    // A file each, so the two cases cannot see one another's widgets. That used
    // to be load-bearing for a different reason — `inline-doc` overwrote the
    // entry at `[line :underline]` without clearing it, so a doc over a result
    // orphaned the result's node — and it is now just isolation. The fix and the
    // test for it are below.
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

test(`a doc over a result replaces it rather than orphaning it, on ${engine}`, async ({ window }) => {
    // Two *different* producers on one line, which is the only way to reach this
    // and the reason it survived: `lt.objs.eval/::underline-results` cleared
    // whatever was at `[line :underline]` before writing, and
    // `lt.plugins.doc/inline-doc` did not. A widget owns a DOM node, so the
    // overwritten one stayed on screen with nothing holding it — a Python plot
    // followed by a doc on the same line was the case that found it.
    //
    // Both now go through `lt.objs.eval/put-underline!`, which is the one place
    // that knows the rule.
    const file = await open(window, engine, `orphan${engine.slice(1)}.txt`,
        'alpha\nbeta\ngamma\n');

    // A result first.
    await evalClj(window, `
        (do (let [ed (first (pool/by-path "${file}"))]
              (object/raise ed :editor.result.underline "the result" {:line 1 :ch 0} {}))
            :shown)`);
    await expect.poll(async () => (await inside(window, file, '.underline-result'))?.count)
        .toBe(1);

    // Then a doc on the same line.
    await evalClj(window, `
        (do (let [ed (first (pool/by-path "${file}"))]
              (object/add-tags ed [:docable])
              (object/raise ed :editor.doc.show!
                            {:name "beta" :ns "probe" :doc "what beta does"
                             :loc {:line 1 :ch 0}}))
            :shown)`);

    // One widget on that line, and it is the doc. Two would be the leak: the
    // count is the assertion, because the doc appearing was never the problem.
    await expect.poll(async () => (await inside(window, file, '.underline-result'))?.count)
        .toBe(1);
    expect((await inside(window, file, '.inline-doc'))?.count).toBe(1);
    expect((await inside(window, file, '.underline-result'))?.text)
        .toContain('what beta does');
    expect((await inside(window, file, '.underline-result'))?.text)
        .not.toContain('the result');

    // And the editor's own map agrees, which is what the DOM is drawn from.
    expect(await evalClj(window, `
        (count (:widgets @(first (pool/by-path "${file}"))))`)).toBe('1');

    await close(window, file);
});

test(`a result that is a DOM node is hosted, not dropped, on ${engine}`, async ({ window }) => {
    // `:result` is whatever the language handed over. Usually a string,
    // sometimes hiccup — and sometimes a node another object owns: evaluating
    // JavaScript against a connected browser answers an object with the
    // devtools inspector, and `lt.objs.browser/eval-js-form` passes that node
    // straight through to `:editor.result`.
    //
    // Converting the widget to Replicant broke that and said nothing. The mark
    // was drawn with nothing inside it and no error was reported, which needs a
    // browser client to reach — so this raises the trigger with a node
    // directly, which is the same path without the connection.
    const file = await open(window, engine, `hosted${engine.slice(1)}.txt`, 'alpha\nbeta\n');

    await evalClj(window, `
        (let [ed (first (pool/by-path "${file}"))
              n (js/document.createElement "span")]
          (set! (.-className n) "probe-hosted")
          (set! (.-textContent n) "an object somebody owns")
          (object/raise ed :editor.result n {:line 0 :ch 1})
          :raised)`);

    await expect.poll(async () => (await inside(window, file, '.result-mark .probe-hosted'))?.count)
        .toBe(1);
    expect((await inside(window, file, '.result-mark'))?.text).toContain('an object somebody owns');

    // Hosted as a span: a div in the middle of a line of code is a line break.
    expect(await insideEditor<string>(window, file,
        '(some-> ^js (.querySelector root ".result-mark .probe-hosted") .-parentNode .-tagName)'))
        .toBe('SPAN');

    await close(window, file);
});

// ── The collapsible exception ───────────────────────────────────────────────
//
// A Clojure exception is drawn as its first line, with the frames behind a
// click. It had been written and never wired: the two behaviors that start it
// were absent from `clojure.behaviors`, so nothing raised
// `:editor.exception.collapsible` and the object, its view and eight lines of
// tag wiring were all unreachable. These are the first time any of it has run.
//
// The trigger is raised directly rather than through nREPL, which is the same
// path from the editor's side and needs no server.

/** Ask a Clojure editor to draw an exception, as `nrepl/->exception` shapes one. */
async function raiseException(window: Page, file: string, res: string): Promise<void> {
    await evalClj(window, `
        (let [ed (first (pool/by-path "${file}"))]
          (object/raise ed :editor.eval.clj.exception ${res} :passed)
          :raised)`);
}

const DIVIDE_BY_ZERO = `
    {:result "java.lang.ArithmeticException: Divide by zero"
     :stack "java.lang.ArithmeticException: Divide by zero
\tat clojure.lang.Numbers.divide(Numbers.java:190)
\tat user$eval1.invokeStatic(NO_SOURCE_FILE:1)"
     :meta {:line 1 :end-line 2 :end-column 5}}`;

test('an exception is drawn collapsed, and the frames are behind a click', async ({ window }) => {
    const file = await open(window, engine, 'boom.clj', '(ns user)\n(/ 1 0)\n');

    await raiseException(window, file, DIVIDE_BY_ZERO);

    await expect.poll(async () => (await inside(window, file, '.inline-exception.result-mark'))?.count).toBe(1);

    // Collapsed: `.result-mark .full` is `display:none` until `.open` is on the
    // root, so both halves are always drawn and the class decides which you see.
    const collapsed = await insideEditor<{ open: boolean, truncated: string, full: string }>(
        window, file, `
        (let [^js root (.querySelector root ".inline-exception.result-mark")]
          {:open (.contains (.-classList root) "open")
           :truncated (.-textContent ^js (.querySelector root ".truncated"))
           :full (.-textContent ^js (.querySelector root ".full"))})`);
    expect(collapsed?.open).toBe(false);
    expect(collapsed?.truncated).toContain('Divide by zero');
    expect(collapsed?.truncated).not.toContain('Numbers.java');
    // Present, and not visible — which is what makes expanding free.
    expect(collapsed?.full).toContain('Numbers.java');

    await insideEditor(window, file,
        '(do (.click ^js (.querySelector root ".inline-exception.result-mark")) :clicked)');

    await expect.poll(async () => await insideEditor<boolean>(window, file, `
        (.contains (.-classList ^js (.querySelector root ".inline-exception.result-mark")) "open")`))
        .toBe(true);

    // And the editor made room for it. Toggling a class is not the feature —
    // the frames being readable is, and a block widget that grew inside a
    // CodeMirror 6 layout that did not remeasure would be clipped instead.
    // Nothing raises the `:changed` that CodeMirror 5 needed here; this is
    // what says none is wanted.
    const grew = await insideEditor<{ before: number, after: number }>(window, file, `
        (let [^js el (.querySelector root ".inline-exception.result-mark")
              ^js full (.querySelector el ".full")]
          {:before (.-offsetHeight ^js (.querySelector el ".truncated"))
           :after (.-offsetHeight full)})`);
    expect(grew!.after).toBeGreaterThan(grew!.before);

    await close(window, file);
});

test('and a second exception on the line replaces the first, keeping it open', async ({ window }) => {
    // nREPL sends a Clojure exception twice on purpose — once instantly from
    // what it printed, once with orchard's frames when they arrive. The second
    // has to replace the first rather than stack on it, and if you had expanded
    // the first the better one arrives expanded.
    const file = await open(window, engine, 'twice.clj', '(ns user)\n(/ 1 0)\n');

    await raiseException(window, file, DIVIDE_BY_ZERO);
    await expect.poll(async () => (await inside(window, file, '.inline-exception.result-mark'))?.count).toBe(1);

    await insideEditor(window, file,
        '(do (.click ^js (.querySelector root ".inline-exception.result-mark")) :clicked)');
    await expect.poll(async () => await insideEditor<boolean>(window, file, `
        (.contains (.-classList ^js (.querySelector root ".inline-exception.result-mark")) "open")`))
        .toBe(true);

    await raiseException(window, file, `
        {:result "java.lang.ArithmeticException: Divide by zero"
         :stack "java.lang.ArithmeticException: Divide by zero
\tat clojure.lang.Numbers.divide(Numbers.java:190)
\tat user/eval1 (NO_SOURCE_FILE:1)
\tat clojure.main/repl (main.clj:437)"
         :meta {:line 1 :end-line 2 :end-column 5}}`);

    // One widget, not two.
    await expect.poll(async () => (await inside(window, file, '.inline-exception.result-mark'))?.count).toBe(1);
    expect(await insideEditor<string>(window, file,
        '(.-textContent ^js (.querySelector root ".inline-exception.result-mark .full"))'))
        .toContain('clojure.main/repl');
    expect(await insideEditor<boolean>(window, file, `
        (.contains (.-classList ^js (.querySelector root ".inline-exception.result-mark")) "open")`))
        .toBe(true);

    await close(window, file);
});

test('and an exception nREPL could not place draws nothing rather than throwing', async ({ window }) => {
    // A reader error has no end line, and `(dec nil)` is -1 in ClojureScript
    // rather than an error — so this asked for line -1. The guard is the one
    // `lt.objs.eval/inline-exceptions` has; `(>= nil 0)` is `null >= 0`, which
    // is true, so a bounds check alone would not have been one.
    const file = await open(window, engine, 'unplaced.clj', '(ns user)\n(/ 1 0)\n');
    await window.evaluate(() => (globalThis as any).lt.objs.control.request('clear-errors', {}));

    await raiseException(window, file, `
        {:result "RuntimeException: EOF while reading" :stack "RuntimeException: EOF" :meta {}}`);

    expect((await inside(window, file, '.inline-exception.result-mark'))?.count).toBe(0);
    const errors = await window.evaluate(() =>
        (globalThis as any).lt.objs.control.request('errors', {}));
    expect(errors.errors).toEqual([]);

    await close(window, file);
});
