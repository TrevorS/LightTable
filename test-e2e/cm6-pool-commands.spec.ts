// The editor commands Light Table registers, run the way a keybinding runs them.
//
// `lt.objs.editor.pool` registers a command per CodeMirror command name, and
// every one of them used to call `js/CodeMirror.commands.<name>` directly. That
// table belongs to CodeMirror 5 and its functions reach into a CodeMirror 5
// editor, so on CodeMirror 6 all thirty-two were a TypeError — and nothing
// noticed, because no test pressed those keys.
//
// This presses them. Through `cmd/exec!`, which is what a keybinding does.

import * as fs from 'node:fs';
import * as path from 'node:path';
import { test, expect, evalClj, scratchDir } from './fixtures';


// Every command in pool.cljs whose whole body is a CodeMirror command, and one
// that is not — :editor.select-all — so the list is not only the mechanical ones.
const COMMANDS = [
    'editor.select-all', 'editor.kill-line', 'editor.delete-line', 'editor.line-start',
    'editor.line-end', 'editor.doc-start', 'editor.doc-end', 'editor.char-left',
    'editor.char-right', 'editor.line-up', 'editor.line-down', 'editor.undo',
    'editor.redo', 'editor.sublime.duplicateLine', 'editor.sublime.swapLineUp',
    'editor.sublime.selectNextOccurrence', 'editor.sublime.sortLines'
];

/** One engine now. The constant stays so the file reads as it did. */
const engine = ':cm6';
test(`every registered editor command runs without throwing, on ${engine}`,
    async ({ window, ltErrors }) => {
    const dir = scratchDir('pool');
    const file = path.join(dir, `probe${engine.slice(1)}.js`);
    fs.writeFileSync(file, 'const alpha = 1;\nconst beta = alpha + alpha;\nconst gamma = 3;\n');

    await evalClj(window, `
        (do (cmd/exec! :open-path "${file}") :opened)`);
    await expect.poll(async () => await evalClj(window,
        `(count (pool/by-path "${file}"))`)).toBe('1');

    // Each one from a known cursor, so a command that moves does not leave
    // the next one somewhere that makes it a no-op.
    const failures = await evalClj(window, `
        (let [ed (first (pool/by-path "${file}"))]
          (object/raise ed :active)
          (vec (remove nil?
            (for [c ${JSON.stringify(COMMANDS).replace(/"/g, '"')}]
              (try
                (lt.objs.editor/move-cursor ed {:line 1 :ch 3})
                (cmd/exec! (keyword c))
                nil
                (catch :default e (str c ": " e)))))))`);

    expect(failures).toBe('[]');
    expect(await ltErrors()).toEqual([]);

    await evalClj(window, `
        (do (doseq [ed (pool/by-path "${file}")] (object/raise ed :close)) :closed)`);
    fs.rmSync(dir, { recursive: true, force: true });
});
