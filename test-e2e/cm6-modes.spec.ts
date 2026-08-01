// The modes, carried forward.
//
// Light Table bundles 121. 103 are the CodeMirror 5 mode code running under a
// CodeMirror 6 StreamLanguage — the same tokenizer, a different host. Five have
// a real Lezer grammar instead, which is the upgrade. Thirteen have neither and
// are named with what happens instead.
//
// The check that matters is not that a mode resolves but that it *tokenizes*:
// a StreamLanguage that loaded and produces one token for the whole line is
// indistinguishable from no highlighting at all.

import { test, expect } from './fixtures';
import type { Page } from '@playwright/test';

/** Highlight `text` as `mode` and report the distinct token classes found. */
async function tokens(window: Page, mode: string, text: string): Promise<string[]> {
    return await window.evaluate(([m, doc]) => {
        const w = globalThis as any;
        const host = document.createElement('div');
        document.body.appendChild(host);
        const ed = w.ltCm6Editor.makeCm6Editor(host, { value: doc });
        ed.setOption('mode', m);
        // The parser runs on the view's own schedule; measuring forces it.
        ed.refresh();
        const classes = new Set<string>();
        host.querySelectorAll('.cm-line span').forEach((n) => {
            n.className.split(/\s+/).filter(Boolean).forEach((c) => classes.add(c));
        });
        host.remove();
        return [...classes].sort();
    }, [mode, text] as [string, string]);
}

test('a legacy mode tokenizes rather than merely loading', async ({ window }) => {
    // Clojure is the one this editor cares most about, and it is a legacy mode
    // rather than a Lezer grammar — so this is the path most languages take.
    const clojure = await tokens(window, 'clojure', '(defn add [a b]\n  ;; sums\n  (+ a b))\n');
    expect(clojure.length).toBeGreaterThan(1);

    // And a handful across the alphabet, so a table entry that names the wrong
    // export is caught rather than assumed.
    for (const [mode, text] of [
        ['python', 'def add(a, b):\n    # sums\n    return a + b\n'],
        ['rust', 'fn add(a: i32) -> i32 { a + 1 }\n'],
        ['go', 'func add(a int) int { return a + 1 }\n'],
        ['yaml', 'key: value\nlist:\n  - one\n'],
        ['shell', 'echo "hello" # a comment\n'],
        ['sql', 'select * from t where a = 1;\n']
    ] as [string, string][]) {
        const found = await tokens(window, mode, text);
        expect(found.length, `${mode} produced ${JSON.stringify(found)}`).toBeGreaterThan(1);
    }
});

test('the five with a real grammar use it', async ({ window }) => {
    for (const [mode, text] of [
        ['markdown', '# Title\n\nSome **bold** text.\n'],
        ['gfm', '# Title\n\nSome **bold** text.\n'],
        ['htmlmixed', '<div class="x">hi</div>\n'],
        ['jsx', 'const a = <div className="x">{x}</div>;\n'],
        ['php', '<?php echo "hi"; ?>\n']
    ] as [string, string][]) {
        const found = await tokens(window, mode, text);
        expect(found.length, `${mode} produced ${JSON.stringify(found)}`).toBeGreaterThan(1);
    }
});

test('a template mode falls back to what it was overlaying', async ({ window }) => {
    // Django and friends are CodeMirror 5's way of running one mode inside
    // another, and CodeMirror 6 has no equivalent. HTML is what the overlay was
    // decorating, so HTML is the nearest true answer — better than plain text
    // and honest about not being Django.
    const django = await tokens(window, 'django', '<div>{% if x %}hi{% endif %}</div>\n');
    // Something rather than nothing is the claim: HTML gives this snippet one
    // token class, and one is the difference between a highlighted template and
    // a wall of plain text.
    expect(django.length).toBeGreaterThan(0);

    const fallbacks = await window.evaluate(() =>
        (globalThis as any).ltCm6Modes.unsupportedModes() as Record<string, string | null>);
    expect(fallbacks['django']).toBe('htmlmixed');
    expect(fallbacks['rst']).toBe(null);
    // Thirteen, named. A list you have to look up is worse than one you read.
    expect(Object.keys(fallbacks).length).toBe(13);
});

test('every mime Light Table opens a file as has an answer', async ({ window }) => {
    // Asked of the file-type table rather than of a list written here, and that
    // is the whole point of the test. The hand-written list this replaces had
    // sixteen names in it, all of which passed, while `text/typescript` — which
    // every `.ts` file in this repository opens as — had no language at all.
    //
    // A mime is not always a mode name: CodeMirror 5 configures one mode
    // several ways through the mime, which is how `javascript` becomes
    // TypeScript. Whatever the table says a file is, this has to answer.
    const unanswered = await window.evaluate(() => {
        const w = globalThis as any;
        const cljs = w.cljs.core;
        const kw = (n: string) => cljs.keyword.call(null, n);
        const types = cljs.get.call(null, cljs.deref(w.lt.objs.files.files_obj), kw('types'));
        const mimes = new Set<string>();
        cljs.doall.call(null, cljs.map.call(null, (t: unknown) => {
            const mime = cljs.get.call(null, t, kw('mime'));
            if (typeof mime === 'string') mimes.add(mime);
            return null;
        }, cljs.vals.call(null, types)));
        return [...mimes].filter((m) => {
            const ext = w.ltCm6Modes.modeExtension(m);
            return !ext || (Array.isArray(ext) && ext.length === 0);
        }).sort();
    });

    // Four, and each is a gap on purpose. Three are modes with no CodeMirror 6
    // grammar at all, declared as such where the editor can be asked; the
    // fourth is plain text, which has no highlighting because it is plain text.
    expect(unanswered, 'mimes with no CodeMirror 6 language')
        .toEqual(['application/x-slim', 'plaintext', 'text/x-haml', 'text/x-rst']);

    const declared = await window.evaluate(() =>
        (globalThis as any).ltCm6Modes.unsupportedModes() as Record<string, string | null>);
    for (const mode of ['haml', 'slim', 'rst']) {
        expect(declared, `${mode} should be a named gap, not a silent one`)
            .toHaveProperty(mode, null);
    }
});

test('and the table itself still covers the modes that ship', async ({ window }) => {
    const known = await window.evaluate(() =>
        (globalThis as any).ltCm6Modes.knownModes() as string[]);
    expect(known.length).toBeGreaterThanOrEqual(115);
});

test('a mode nobody knows is a document with no highlighting, not a crash', async ({ window }) => {
    // Which is what CodeMirror 5 does for a mode it has not loaded, so an
    // unknown name stays the same non-event it always was.
    const found = await tokens(window, 'not-a-real-mode', 'some text\n');
    expect(found).toEqual([]);
});
