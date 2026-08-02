// Editing behaviour that has to be reachable, not merely present.
//
// Multiple cursors are the reason this file exists. The CodeMirror addon was
// bundled, the commands were defined, and the whole thing was unreachable for
// want of a keybinding — which is a failure no unit test can see, because
// every piece of it works.

import * as fs from 'node:fs';
import * as path from 'node:path';
import { test, expect, evalData, scratchDir, openFile } from './fixtures';


/** One engine now. The constant stays so the file reads as it did. */
const engine = ':cm6';
test(`multiple cursors select, edit and clear, on ${engine}`, async ({ window, ltErrors }) => {
// The sublime commands were a CodeMirror 5 addon registering on the
// CodeMirror 5 global. CodeMirror 6 keeps multiple selections in the state, so
// most of them turn out to be a few lines about `EditorSelection` — but they
// had to be written, because nothing carried them over.

const dir = scratchDir('cursors');
const file = path.join(dir, 'probe.js');
fs.writeFileSync(file, 'const total = 1;\nconst other = total + total;\n');

await openFile(window, file);

// One round trip, because each step depends on the one before it: the
// selections are the editor's own state and reading them back between
// commands would be four more crossings for nothing.
const result = await evalData<{ selected: number; afterUndo: number; text: string; afterClear: number }>(
    window, `
    (let [cm ^js (editor/->cm-ed (first (pool/by-path "${file}")))
          selections #(.-length (.listSelections cm))]
      (.setCursor cm #js {:line 0 :ch 6})
      (dotimes [_ 3] (cmd/exec! :editor.sublime.selectNextOccurrence))
      (let [selected (selections)]
        (cmd/exec! :editor.sublime.undoSelection)
        (let [after-undo (selections)]
          (cmd/exec! :editor.sublime.redoSelection)
          (.replaceSelections cm (into-array (repeat (selections) "sum")))
          (let [text (second (clojure.string/split (.getValue cm) "\n"))]
            (cmd/exec! :editor.sublime.singleSelectionTop)
            {:selected selected
             :afterUndo after-undo
             :text text
             :afterClear (selections)}))))`);

// Three occurrences of `total`, all selected, all replaced at once.
expect(result.selected).toBe(3);
expect(result.afterUndo).toBe(2);
expect(result.text).toBe('const other = sum + sum;');
expect(result.afterClear).toBe(1);
expect(await ltErrors()).toEqual([]);

fs.rmSync(dir, { recursive: true, force: true });
});

test('and every one of them is reachable from a key', async ({ window }) => {
    // The gap this whole file is about: a command nothing can invoke is a
    // command that does not exist as far as anyone using the editor knows.
    const bound = await evalData<string[]>(window, `
        (->> (:editor.keys.normal @lt.objs.keyboard/keys)
             (map (comp pr-str val))
             (filter #(clojure.string/includes? % "sublime"))
             vec)`).then((found) => found.join(' '));

    for (const command of ['selectNextOccurrence', 'addCursorToNextLine', 'addCursorToPrevLine',
                           'splitSelectionByLine', 'undoSelection', 'singleSelectionTop']) {
        expect(bound).toContain(command);
    }
});

test('a platform-only binding does not leak onto other platforms', async ({ window }) => {
    // mac:pmeta-d and linux:ctrl-alt-d are the same command spelled for two
    // platforms; exactly one of them should have survived being read.
    const keys = await evalData<string[]>(window, `
        (->> (:editor.keys.normal @lt.objs.keyboard/keys)
             (filter #(clojure.string/includes? (pr-str (val %)) "selectNextOccurrence"))
             (mapv key))`);

    expect(keys.length).toBe(1);
    // And the prefix itself never reaches the key table.
    expect(keys[0]).not.toContain(':');
});
