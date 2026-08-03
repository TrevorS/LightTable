// Undo, redo, and the dirty dot, on a real file.
//
// Reported together and they are one mechanism: what CodeMirror 5 gave a
// buffer was a history that the file's own contents were never in, and a
// `changeGeneration`/`isClean` pair that undo could walk back to. Both had to
// survive the engine swap and neither did.
//
// Asserted on a file opened the way a person opens one — through `:open-path`,
// which goes via `lt.objs.document` — because that is the path with the bug in
// it. An editor created with `:content` takes a different branch.

import * as fs from 'node:fs';
import * as path from 'node:path';
import { test, expect, evalClj, scratchDir } from './fixtures';

const ORIGINAL = 'one\ntwo\nthree\n';

/** Open a file and hand back its path, plus a reader for what the editor shows. */
async function open(window: Parameters<typeof evalClj>[0]) {
    const file = path.join(scratchDir('undo'), 'a.txt');
    fs.writeFileSync(file, ORIGINAL);
    await evalClj(window, `(do (cmd/exec! :open-path "${file}") :opened)`);
    await expect.poll(async () => await evalClj(window,
        `(boolean (seq (pool/by-path "${file}")))`)).toBe('true');
    return file;
}

const value = (window: Parameters<typeof evalClj>[0], file: string) =>
    evalClj(window, `(lt.objs.editor/->val (first (pool/by-path "${file}")))`);
const dirty = (window: Parameters<typeof evalClj>[0], file: string) =>
    evalClj(window, `(boolean (:dirty @(first (pool/by-path "${file}"))))`);
const close = (window: Parameters<typeof evalClj>[0], file: string) =>
    evalClj(window, `(do (doseq [ed (pool/by-path "${file}")] (object/raise ed :close)) :closed)`);

test('undo of the first edit returns the file, not an empty buffer', async ({ window }) => {
    // The reported bug, and the sharpest form of it: one edit, one undo. If
    // loading the file is itself in the history then undo goes past the edit to
    // the empty document the editor was created with, and the buffer is wiped.
    const file = await open(window);
    expect(JSON.parse(await value(window, file))).toBe(ORIGINAL);

    await evalClj(window, `
        (let [ed (first (pool/by-path "${file}"))]
          (lt.objs.editor/insert-at-cursor ed "edited ") :typed)`);
    expect(JSON.parse(await value(window, file))).not.toBe(ORIGINAL);

    await evalClj(window, `
        (do (lt.objs.editor/undo (first (pool/by-path "${file}"))) :undone)`);
    expect(JSON.parse(await value(window, file)),
           'undo went past the edit and wiped the buffer').toBe(ORIGINAL);

    await close(window, file);
});

test('and redo puts the edit back', async ({ window }) => {
    const file = await open(window);
    await evalClj(window, `
        (let [ed (first (pool/by-path "${file}"))]
          (lt.objs.editor/insert-at-cursor ed "edited ") :typed)`);
    const edited = JSON.parse(await value(window, file));

    await evalClj(window, `(do (lt.objs.editor/undo (first (pool/by-path "${file}"))) :undone)`);
    expect(JSON.parse(await value(window, file))).toBe(ORIGINAL);

    await evalClj(window, `(do (lt.objs.editor/redo (first (pool/by-path "${file}"))) :redone)`);
    expect(JSON.parse(await value(window, file)), 'redo did nothing').toBe(edited);

    await close(window, file);
});

test('there is nothing to undo in a file just opened', async ({ window }) => {
    // The same bug asked the other way, and the way that cannot pass by
    // accident: a file nobody has edited has an empty undo history, so undoing
    // is a no-op rather than a way to reach a document that was never on screen.
    const file = await open(window);
    await evalClj(window, `(do (lt.objs.editor/undo (first (pool/by-path "${file}"))) :undone)`);
    expect(JSON.parse(await value(window, file))).toBe(ORIGINAL);
    await close(window, file);
});

test('the dirty flag follows the buffer back', async ({ window }) => {
    // A file you edit and then undo is a file that matches what is on disk, so
    // the dot goes away. It did not, because the generation counter behind
    // `isClean` only ever counted up — two changes and an undo is three
    // increments, never equal to the number marked clean at open.
    const file = await open(window);
    expect(await dirty(window, file), 'a freshly opened file is not dirty').toBe('false');

    await evalClj(window, `
        (let [ed (first (pool/by-path "${file}"))]
          (lt.objs.editor/insert-at-cursor ed "edited ") :typed)`);
    await expect.poll(() => dirty(window, file)).toBe('true');

    await evalClj(window, `(do (lt.objs.editor/undo (first (pool/by-path "${file}"))) :undone)`);
    await expect.poll(() => dirty(window, file),
                      { message: 'undone back to the file on disk, still marked dirty' })
        .toBe('false');

    await close(window, file);
});

test('saving marks clean, and undoing past the save marks dirty again', async ({ window }) => {
    // The other end of the same mechanism. A save marks where clean is; editing
    // moves away from it and undoing moves back, and undoing *past* it is a
    // buffer that no longer matches disk in the other direction.
    const file = await open(window);
    await evalClj(window, `
        (let [ed (first (pool/by-path "${file}"))]
          (lt.objs.editor/insert-at-cursor ed "kept ") :typed)`);
    await expect.poll(() => dirty(window, file)).toBe('true');

    await evalClj(window, `(do (object/raise (first (pool/by-path "${file}")) :save) :saved)`);
    await expect.poll(() => dirty(window, file)).toBe('false');
    expect(fs.readFileSync(file, 'utf8')).toContain('kept ');

    await evalClj(window, `
        (let [ed (first (pool/by-path "${file}"))]
          (lt.objs.editor/insert-at-cursor ed "more ") :typed)`);
    await expect.poll(() => dirty(window, file)).toBe('true');

    // Back to what was saved.
    await evalClj(window, `(do (lt.objs.editor/undo (first (pool/by-path "${file}"))) :undone)`);
    await expect.poll(() => dirty(window, file)).toBe('false');

    // And one further, which is behind the save point: the buffer and the file
    // differ again, so the dot comes back.
    await evalClj(window, `(do (lt.objs.editor/undo (first (pool/by-path "${file}"))) :undone)`);
    expect(JSON.parse(await value(window, file))).toBe(ORIGINAL);
    await expect.poll(() => dirty(window, file)).toBe('true');

    await close(window, file);
});

test('a keystroke is undoable; showing a different document is not', async ({ window }) => {
    // What `setValue` no longer entering the history means, stated as the two
    // cases it separates. Typing is a thing a person did and `⌘Z` is for it.
    // Replacing the whole document is a load — opening a file, showing another
    // one in the same editor, the `:save+` chain on the way to disk — and none
    // of those is a keystroke anyone expects to walk back through.
    const file = await open(window);

    await evalClj(window, `
        (let [ed (first (pool/by-path "${file}"))]
          (lt.objs.editor/insert-at-cursor ed "typed ") :typed)`);
    await evalClj(window, `
        (do (lt.objs.editor/set-val (first (pool/by-path "${file}")) "replaced\\n") :replaced)`);
    expect(JSON.parse(await value(window, file))).toBe('replaced\n');

    // One undo, and it takes the keystroke rather than the replacement — so
    // what is left is the replacement without the typing, not the file back.
    await evalClj(window, `(do (lt.objs.editor/undo (first (pool/by-path "${file}"))) :undone)`);
    expect(JSON.parse(await value(window, file)),
           'the replacement was undoable, so undo walked back through a load').toBe('replaced\n');

    await close(window, file);
});
