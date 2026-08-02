// Toggle documentation, through a language server.
//
// Reported as "nothing shows up, no console error", which is the worst shape a
// failure can take: every step of this path is allowed to decline quietly. The
// command asks the editor for a doc; a behavior asks whichever server says it
// answers hover; the reply is raised as another trigger, and *that* one is
// answered only for editors tagged `:docable`. Any link missing is silence.
//
// So this drives the command a person presses and asserts on what appears in
// the document. The server is script/fixtures/fake-language-server.mts.

import * as fs from 'node:fs';
import * as path from 'node:path';
import { test, expect, evalClj as evalWith, scratchDir } from './fixtures';
import type { Page } from '@playwright/test';

/** Longer than the default, because a real language server has to start. */
const evalClj = (window: Page, source: string) => evalWith(window, source, { tries: 300 });

const FAKE_SERVER = path.join(__dirname, '..', 'script', 'fixtures',
                              'fake-language-server.mts');


/** A project the server will accept a root for, and a file in it. */
function project(name: string): string {
    const dir = scratchDir(name);
    fs.mkdirSync(path.join(dir, 'src'), { recursive: true });
    fs.writeFileSync(path.join(dir, 'tsconfig.json'), '{"compilerOptions":{"strict":true}}\n');
    const file = path.join(dir, 'src', 'probe.ts');
    fs.writeFileSync(file, 'export const first = 1;\nexport const second = 2;\n');
    return file;
}

/** One engine now. The constant stays so the file reads as it did. */
const engine = ':cm6';
test(`Toggle docs shows the server's hover, on ${engine}`, async ({ window }) => {
    const file = project(`lspdoc${engine.slice(1)}`);

    // Declared the way a user.behaviors entry declares one, by running the
    // behavior's own reaction — so what is under test includes how a server
    // gets registered at all.
    await evalClj(window, `
        (do (lt.object/call-behavior-reaction
              :lt.objs.editor.lsp/language-servers
              lt.objs.editor.lsp/lsp-client
              [{:tags [:editor.typescript]
                :language-id "typescript"
                :root ["tsconfig.json"]
                :id "vtsls"
                :command "node"
                :args ["${FAKE_SERVER}"]}])
            (cmd/exec! :open-path "${file}")
            :opened)`);

    await expect.poll(async () => await evalClj(window,
        `(count (pool/by-path "${file}"))`)).toBe('1');

    // The tag is the link that decides whether the answer can be drawn, and
    // it is earned from what the server said it could do rather than from a
    // table. Waited for rather than assumed: the server is a process, and
    // it has to start and answer `initialize` first.
    await expect.poll(async () => await evalClj(window, `
        (boolean (object/has-tag? (first (pool/by-path "${file}")) :docable))`),
    { timeout: 30000 }).toBe('true');

    // Focused because the command asks the pool which editor was last
    // active, the same way autocomplete does.
    await evalClj(window, `
        (let [ed (first (pool/by-path "${file}"))]
          (lt.objs.editor/focus ed)
          (lt.objs.editor/move-cursor ed {:line 1 :ch 13})
          :ready)`);
    await expect.poll(async () => await evalClj(window,
        `(= (pool/last-active) (first (pool/by-path "${file}")))`)).toBe('true');

    await evalClj(window, '(do (cmd/exec! :editor.doc.toggle) :toggled)');

    const doc = async () => await window.evaluate(([p]) => {
        const w = globalThis as any;
        const ed = w.cljs.core.first.call(null, w.lt.objs.editor.pool.by_path(p));
        const root = w.lt.objs.editor.__GT_elem(ed) as HTMLElement;
        const found = root.querySelector('.inline-doc') as HTMLElement | null;
        return found ? found.innerText : null;
    }, [file]);

    // The position is in the text the server sent back, so this also says
    // the request carried the cursor rather than the start of the file.
    await expect.poll(doc, { timeout: 20000 }).toContain('HOVER at 1:13');

    // And toggling again puts it away, which is the other half of "toggle".
    await evalClj(window, '(do (cmd/exec! :editor.doc.toggle) :toggled)');
    await expect.poll(doc).toBe(null);

    await evalClj(window, `
        (do (doseq [ed (pool/by-path "${file}")] (object/raise ed :close)) :closed)`);
    fs.rmSync(path.dirname(path.dirname(file)), { recursive: true, force: true });
});
