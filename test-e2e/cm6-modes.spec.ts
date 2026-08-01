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

test('every mode Light Table bundles has an answer', async ({ window }) => {
    // The 121 in deploy/settings/default/default.behaviors. An unknown one is
    // a file type that opens with no highlighting and no explanation.
    const known = await window.evaluate(() =>
        (globalThis as any).ltCm6Modes.knownModes() as string[]);
    expect(known.length).toBeGreaterThanOrEqual(115);
    for (const mode of ['clojure', 'python', 'javascript', 'rust', 'go', 'ruby', 'php',
                        'markdown', 'yaml', 'toml', 'shell', 'sql', 'haskell',
                        'django', 'rst', 'haml']) {
        expect(known, `${mode} is not in the table`).toContain(mode);
    }
});

test('a mode nobody knows is a document with no highlighting, not a crash', async ({ window }) => {
    // Which is what CodeMirror 5 does for a mode it has not loaded, so an
    // unknown name stays the same non-event it always was.
    const found = await tokens(window, 'not-a-real-mode', 'some text\n');
    expect(found).toEqual([]);
});
