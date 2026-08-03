// Tree-sitter highlighting, asserted on the painted DOM.
//
// The parse and the span table were never an engine's. Only the last step was:
// CodeMirror 5 pulled colours out of a mode line by line, CodeMirror 6 is handed
// decorations. What has to be proved is not that a tree exists — the smoke test
// covers that — but that it comes out as the class names css/treesitter.css is
// written against, of which there is exactly one copy.
//
// The DOM, and not the span table, because the span table agreeing with itself
// proves nothing. The set of classes is recorded in cm5-answers.ts, where it
// sits beside CodeMirror 5's other answers — it was captured after that engine
// went, but the test it replaces asserted these two sets were equal while both
// were here, and passed.

import * as fs from 'node:fs';
import * as path from 'node:path';
import { test, expect, evalClj as evalWith, insideEditor, scratchDir } from './fixtures';
import type { Page } from '@playwright/test';

/** Longer than the default, because a tree-sitter grammar is fetched and compiled on first use. */
const evalClj = (window: Page, source: string) => evalWith(window, source, { tries: 200 });
import { cm5 } from './cm5-answers';


// Small, and every line of it there to make a capture the CodeMirror mode
// cannot: a type annotation, a parameter, a builtin type, nested brackets.
const PROBE = `type Pair = { left: number; right: string };

function pick(pair: Pair, which: boolean): number | string {
    return which ? pair.left : pair.right;
}
`;

/** Open the probe and wait for tree-sitter to have parsed it. */
async function open(window: Page): Promise<string> {
    const file = path.join(scratchDir('ts'), 'probe.ts');
    fs.writeFileSync(file, PROBE);
    await evalClj(window, `(do (cmd/exec! :open-path "${file}") :opened)`);
    // The grammar is ~400KB of WebAssembly loaded on first use, so this is a
    // wait for a download-sized thing rather than for a render.
    await expect.poll(async () => await evalClj(window, `
        (let [ed (first (pool/by-path "${file}"))]
          (boolean (and ed (:active (lt.objs.editor.treesitter/report ed)))))`),
    { timeout: 30000 }).toBe('true');
    return file;
}

async function close(window: Page, file: string): Promise<void> {
    await evalClj(window, `
        (do (doseq [ed (pool/by-path "${file}")] (object/raise ed :close)) :closed)`);
    fs.rmSync(path.dirname(file), { recursive: true, force: true });
}

/** Every `cm-ts-…` class present in the editor showing `file`, sorted. */
async function paintedClasses(window: Page, file: string): Promise<string[]> {
    return (await insideEditor<string[]>(window, file, `
        (->> (array-seq (.querySelectorAll root "[class*='cm-ts-']"))
             (mapcat #(array-seq (.-classList ^js %)))
             (filter #(clojure.string/starts-with? % "cm-ts-"))
             distinct
             sort
             vec)`)) ?? ['no editor'];
}

const classesFor = async (window: Page): Promise<string[]> => {
    const file = await open(window);
    // Poll: the parse finishing and the paint happening are two events, and on
    // CodeMirror 6 the second is a microtask behind the first by design.
    let classes: string[] = [];
    await expect.poll(async () => {
        classes = await paintedClasses(window, file);
        return classes.length;
    }, { timeout: 15000 }).toBeGreaterThan(0);
    await close(window, file);
    return classes;
};

test('the tree comes out the colours the stylesheet is written for', async ({ window }) => {
    const painted = await classesFor(window);

    // The vocabulary is the point, so it is named rather than counted. A
    // per-line tokenizer cannot produce any of these: it does not know a type
    // from a value, a parameter from a local, or one bracket's depth from
    // another's.
    for (const wanted of ['cm-ts-type', 'cm-ts-variable-parameter',
                          'cm-ts-punctuation', 'cm-ts-punctuation-bracket']) {
        expect(painted, `should paint ${wanted}`).toContain(wanted);
    }

    // And the whole set, which is what one stylesheet written once has to
    // cover. Sized as well as compared, so an empty set cannot pass.
    expect(painted.length).toBeGreaterThanOrEqual(8);
    expect(painted).toEqual(cm5('treesitter::painted-classes'));
});

test('a keystroke repaints, and only the tree decides the colours', async ({ window }) => {
    const file = await open(window);

    // `pick` is a function name in the probe. Renaming it to a type name is the
    // narrowest thing that can prove the parse is driving this: the characters
    // are ordinary either way, and only a parser knows the difference.
    await expect.poll(async () => (await paintedClasses(window, file)).length)
        .toBeGreaterThan(0);
    const before = await paintedClasses(window, file);
    expect(before).toContain('cm-ts-function');

    await evalClj(window, `
        (let [ed (first (pool/by-path "${file}"))]
          (lt.objs.editor/set-val ed "const x: number = 1;\\n")
          :rewritten)`);

    await expect.poll(async () => (await paintedClasses(window, file)).includes('cm-ts-function'))
        .toBe(false);
    const after = await paintedClasses(window, file);
    expect(after.length).toBeGreaterThan(0);

    await close(window, file);
});

test('the theme that ships is dark, and colours the captures itself', async ({ window }) => {
    // Catppuccin Mocha, and the two halves that have to meet: the editor wears
    // the theme's class — which it did not until the class moved onto
    // CodeMirror 6's own facet, because the view rewrites that attribute on
    // every update — and the theme's tree-sitter rules beat the defaults in
    // css/treesitter.css by being scoped to it.
    const file = await open(window);
    await expect.poll(async () => (await paintedClasses(window, file)).length)
        .toBeGreaterThan(0);

    const look = (await insideEditor<{ background: string, colour: string, type: string | null }>(
        window, file, `
        (let [style (fn [n] (js/getComputedStyle n))
              type ^js (.querySelector root ".cm-ts-type")]
          {:background (.-backgroundColor (style root))
           :colour (.-color (style root))
           :type (some-> type style .-color)})`))!;

    expect(look.background).toBe('rgb(30, 30, 46)');   // base
    expect(look.colour).toBe('rgb(205, 214, 244)');    // text
    // Mocha's yellow, which is what the palette assigns to a type. The default
    // in treesitter.css is a teal; if that were showing, this theme's rules
    // would be losing to the file they are meant to override.
    expect(look.type).toBe('rgb(249, 226, 175)');

    await close(window, file);
});

test('a language inside a language is drawn by its own grammar', async ({ window }) => {
    // The whole of tree-sitter-html's highlight query is six capture names:
    // tag, tag.error, constant, attribute, string, comment, punctuation.bracket.
    // It has no idea what a keyword or a number is, because in HTML there are
    // none — the `const` and the `42` below are JavaScript, and the `color` is
    // a CSS property. So the assertion is not "more colours appeared": it is
    // that captures this grammar *cannot produce* are on the screen, which only
    // a second grammar parsing the same bytes can explain.
    const file = path.join(scratchDir('inject'), 'page.html');
    fs.writeFileSync(file, `<html>
<head>
<style>
  .answer { color: red; }
</style>
<script>
  const answer = 42;
</script>
</head>
<body><p class="answer">hi</p></body>
</html>
`);
    await evalClj(window, `(do (cmd/exec! :open-path "${file}") :opened)`);

    // Two more grammars are fetched here, not one, and each is ~400KB of
    // WebAssembly loaded on first sighting rather than up front. The report
    // names them, so what is being waited for is the thing itself.
    await expect.poll(async () => await evalClj(window, `
        (let [ed (first (pool/by-path "${file}"))]
          (vec (sort (:injected (lt.objs.editor.treesitter/report ed)))))`),
    { timeout: 60000 }).toBe('["css" "javascript"]');

    await expect.poll(async () => await paintedClasses(window, file), { timeout: 15000 })
        .toContain('cm-ts-keyword');
    const painted = await paintedClasses(window, file);

    // From the JavaScript, and from nowhere else in this file.
    expect(painted).toContain('cm-ts-keyword');
    expect(painted).toContain('cm-ts-number');
    // From the CSS.
    expect(painted).toContain('cm-ts-property');
    // And HTML is still drawing HTML — an injection adds a language, it does
    // not replace the host.
    expect(painted).toContain('cm-ts-tag');

    await close(window, file);
});

test('the languages with a plugin and no CodeMirror mode are coloured too', async ({ window }) => {
    // Elixir, Zig and Shell each ship a plugin — file types, a language server,
    // a keymap — and until now the colouring was the one part missing: Elixir
    // and Zig have no CodeMirror 6 mode at all, and Shell's tag is `editor.shell`
    // where the grammar is called `bash`. Each is one line of registry, and this
    // is what says the line is wired to something.
    const cases: [string, string, string][] = [
        ['a.ex', 'defmodule Foo do\n  def bar(x), do: x + 1\nend\n', 'tree-sitter-elixir'],
        ['a.zig', 'const std = @import("std");\npub fn main() void {}\n',
         '@tree-sitter-grammars/tree-sitter-zig'],
        ['a.sh', '#!/bin/sh\nfor f in *.txt; do echo "$f"; done\n', 'tree-sitter-bash']
    ];

    for (const [name, source, grammar] of cases) {
        // A directory each, because `close` takes the directory with it.
        const file = path.join(scratchDir('langs'), name);
        fs.writeFileSync(file, source);
        await evalClj(window, `(do (cmd/exec! :open-path "${file}") :opened)`);
        await expect.poll(async () => await evalClj(window, `
            (let [ed (first (pool/by-path "${file}"))]
              (:grammar (lt.objs.editor.treesitter/report ed)))`),
        { timeout: 60000 }).toBe(`"${grammar}"`);

        // And it painted, rather than merely loading: a grammar that parses to
        // one big error node reports itself active and colours nothing.
        await expect.poll(async () => (await paintedClasses(window, file)).length,
                          { timeout: 15000 }).toBeGreaterThan(3);
        await close(window, file);
    }
});

test('installing a highlighter turns the language\'s own colouring off', async ({ window }) => {
    // Two things can colour the same characters, and only one may. They speak
    // different vocabularies — `cm-keyword` against `cm-ts-keyword` — and a
    // theme has no way to prefer one, so both applying would mean whichever
    // selector happened to be more specific won. The language itself stays
    // configured either way; it is what indents, folds and matches brackets,
    // and none of that is drawing.
    const out = await window.evaluate(async () => {
        const w = globalThis as any;
        const host = document.createElement('div');
        host.style.height = '300px';
        document.body.appendChild(host);
        const ed = w.ltCm6Editor.makeCm6Editor(host, { value: 'const x = 1;\n' });
        ed.setOption('mode', 'javascript');

        const classes = (): string[] => {
            const seen = new Set<string>();
            for (const el of Array.from(host.querySelectorAll('span[class]'))) {
                for (const c of Array.from((el as HTMLElement).classList)) seen.add(c);
            }
            return [...seen].sort();
        };
        const until = async (test: () => boolean) => {
            for (let i = 0; i < 100 && !test(); i++) {
                await new Promise((r) => setTimeout(r, 20));
            }
        };

        await until(() => classes().includes('cm-keyword'));
        const before = classes();

        // A span table of one span, standing in for a parse: what is under test
        // is the swap, not the parser.
        ed.setHighlighter({
            spansForLine: (n: number) =>
                (n === 0 ? [{ from: 0, to: 5, style: 'ts-keyword' }] : undefined)
        });
        await until(() => classes().includes('cm-ts-keyword'));
        const after = classes();

        host.remove();
        return { before, after };
    });

    expect(out.before).toContain('cm-keyword');
    expect(out.after).toContain('cm-ts-keyword');
    expect(out.after).not.toContain('cm-keyword');
});

test('the tree indents the languages that have no indenter', async ({ window }) => {
    // Elixir and Zig have a grammar and no CodeMirror parser, so nothing knew
    // where a line belonged: pressing Tab did nothing and a new line copied the
    // one above it. The tree knows — it is the thing that says what encloses
    // this line — and `indentLevel` reads it structurally rather than from a
    // query, because one of the grammars bundled here ships an indents.scm and
    // the rest do not.
    const file = path.join(scratchDir('indent'), 'a.ex');
    fs.writeFileSync(file, 'defmodule Foo do\ndef bar(x) do\nx + 1\nend\nend\n');
    await evalClj(window, `(do (cmd/exec! :open-path "${file}") :opened)`);
    await expect.poll(async () => await evalClj(window, `
        (let [ed (first (pool/by-path "${file}"))]
          (boolean (:active (lt.objs.editor.treesitter/report ed))))`),
    { timeout: 60000 }).toBe('true');

    const indented = await evalClj(window, `
        (let [ed (first (pool/by-path "${file}"))
              cm (lt.objs.editor/->cm-ed ed)]
          (dotimes [n 5] (.indentLine cm n))
          (lt.objs.editor/->val ed))`);

    // Two levels inside the nested `do`, one for each `do` that is still open,
    // and the `end`s back out to the level of what they close. Every one of
    // those is the same rule: count the lines something still open began on,
    // and do not count the block a line closes.
    expect(indented).toBe(JSON.stringify(
        'defmodule Foo do\n  def bar(x) do\n    x + 1\n  end\nend\n'));

    await close(window, file);

    // And the other half of the rule: TypeScript has a grammar too, and a real
    // indenter written against a real grammar, so the tree must *not* take the
    // job. Proved the only way it can be — by the result being right, which a
    // structural count would not be for a chained call broken over lines.
    const ts = path.join(scratchDir('indent-ts'), 'a.ts');
    fs.writeFileSync(ts, 'function pick(a: number) {\nreturn a;\n}\n');
    await evalClj(window, `(do (cmd/exec! :open-path "${ts}") :opened)`);
    await expect.poll(async () => await evalClj(window, `
        (let [ed (first (pool/by-path "${ts}"))]
          (boolean (:active (lt.objs.editor.treesitter/report ed))))`),
    { timeout: 60000 }).toBe('true');

    const tsIndented = await evalClj(window, `
        (let [ed (first (pool/by-path "${ts}"))
              cm (lt.objs.editor/->cm-ed ed)]
          (dotimes [n 3] (.indentLine cm n))
          (lt.objs.editor/->val ed))`);
    expect(tsIndented).toBe(JSON.stringify('function pick(a: number) {\n  return a;\n}\n'));
    await close(window, ts);
});
