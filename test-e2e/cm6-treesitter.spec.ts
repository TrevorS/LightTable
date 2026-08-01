// Tree-sitter highlighting, on both engines, asserted on the painted DOM.
//
// The parse and the span table were never either engine's. Only the last step
// was: CodeMirror 5 pulls colours out of a mode line by line, CodeMirror 6 is
// handed decorations. So what has to be proved is not that a tree exists — the
// smoke test covers that — but that the same tree comes out the same colour on
// both, down to the class names, because css/treesitter.css is written against
// those and there is exactly one copy of it.
//
// The DOM, and not the span table, for the same reason: the span table agreeing
// with itself proves nothing.

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

// Small, and every line of it there to make a capture the CodeMirror mode
// cannot: a type annotation, a parameter, a builtin type, nested brackets.
const PROBE = `type Pair = { left: number; right: string };

function pick(pair: Pair, which: boolean): number | string {
    return which ? pair.left : pair.right;
}
`;

/** Open the probe on `engine` and wait for tree-sitter to have parsed it. */
async function open(window: Page, engine: string): Promise<string> {
    const file = path.join(scratchDir('ts'), `probe${engine.slice(1)}.ts`);
    fs.writeFileSync(file, PROBE);
    await evalClj(window, `
        (do (lt.objs.editor/set-engine! ${engine})
            (cmd/exec! :open-path "${file}")
            :opened)`);
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

const classesFor = async (window: Page, engine: string): Promise<string[]> => {
    const file = await open(window, engine);
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

test('the same tree comes out the same colour on both engines', async ({ window }) => {
    const five = await classesFor(window, ':cm5');
    const six = await classesFor(window, ':cm6');

    // The vocabulary is the point, so it is named rather than counted. A
    // per-line tokenizer cannot produce any of these: it does not know a type
    // from a value, a parameter from a local, or one bracket's depth from
    // another's.
    for (const wanted of ['cm-ts-type', 'cm-ts-variable-parameter',
                          'cm-ts-punctuation', 'cm-ts-punctuation-bracket']) {
        expect(six, `CodeMirror 6 should paint ${wanted}`).toContain(wanted);
    }

    // And the whole set matches, which is the claim that matters: one
    // stylesheet, written once, correct on either engine. Sized as well as
    // compared, so two empty sets cannot agree with each other.
    expect(six.length).toBeGreaterThanOrEqual(8);
    expect(six).toEqual(five);
});

test('a keystroke repaints, and only the tree decides the colours', async ({ window }) => {
    const file = await open(window, ':cm6');

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
