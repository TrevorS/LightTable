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
import { test, expect, evalClj as evalWith, scratchDir } from './fixtures';
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
    return await window.evaluate(([p]) => {
        const w = globalThis as any;
        const cljs = w.cljs.core;
        const ed = cljs.first.call(null, w.lt.objs.editor.pool.by_path(p));
        if (!ed) return ['no editor'];
        const root = w.lt.objs.editor.__GT_elem(ed) as HTMLElement;
        const seen = new Set<string>();
        for (const el of Array.from(root.querySelectorAll('[class*="cm-ts-"]'))) {
            for (const c of Array.from((el as HTMLElement).classList)) {
                if (c.startsWith('cm-ts-')) seen.add(c);
            }
        }
        return [...seen].sort();
    }, [file]);
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

    const look = await window.evaluate(([p]) => {
        const w = globalThis as any;
        const ed = w.cljs.core.first.call(null, w.lt.objs.editor.pool.by_path(p));
        const root = w.lt.objs.editor.__GT_elem(ed) as HTMLElement;
        const type = root.querySelector('.cm-ts-type') as HTMLElement | null;
        return {
            background: getComputedStyle(root).backgroundColor,
            colour: getComputedStyle(root).color,
            type: type ? getComputedStyle(type).color : null
        };
    }, [file]);

    expect(look.background).toBe('rgb(30, 30, 46)');   // base
    expect(look.colour).toBe('rgb(205, 214, 244)');    // text
    // Mocha's yellow, which is what the palette assigns to a type. The default
    // in treesitter.css is a teal; if that were showing, this theme's rules
    // would be losing to the file they are meant to override.
    expect(look.type).toBe('rgb(249, 226, 175)');

    await close(window, file);
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
