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
import { test, expect, evalClj as evalWith, evalData, insideEditor, scratchDir } from './fixtures';
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

    const doc = async () => await insideEditor<string>(window, file,
        '(some-> ^js (.querySelector root ".inline-doc") .-innerText)');

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

test('and a server that has not finished starting says so', async ({ window }) => {
    // The reported failure, which was not "no docs" but "nothing at all".
    // `request-at-cursor!` declined before the handshake and said nothing, and
    // a language server takes seconds to minutes to index a project — so every
    // press in that window did nothing, while the statusbar was separately
    // reporting "Language server ready" about a different connection.
    //
    // Declining is still right: a cursor request answered thirty seconds later
    // is about a cursor that has moved. Declining silently is not.
    const file = project('lspnotready');

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

    await expect.poll(async () => await evalClj(window, `
        (boolean (object/has-tag? (first (pool/by-path "${file}")) :docable))`),
    { timeout: 30000 }).toBe('true');

    await evalClj(window, `
        (let [ed (first (pool/by-path "${file}"))]
          (lt.objs.editor/focus ed)
          (lt.objs.editor/move-cursor ed {:line 1 :ch 13})
          :ready)`);

    // Put the connection back where it was a moment after it started.
    await evalClj(window, `
        (do (doseq [c (lt.objs.editor.lsp/conns (first (pool/by-path "${file}")))]
              (swap! c assoc :initialized? false))
            :mid-handshake)`);

    await evalClj(window, '(do (cmd/exec! :editor.doc.toggle) :toggled)');
    await expect.poll(async () => await evalClj(window, '(:text (:message @lt.state/app))'))
        .toContain('still starting');
    // And nothing was drawn, which is what it was doing before — the change is
    // that you are told.
    expect(await window.locator('.inline-doc').count()).toBe(0);

    // Jump to definition is the same request shape and had the same silence.
    await evalClj(window, '(do (lt.objs.notifos/set-msg! "") (cmd/exec! :editor.jump-to-definition-at-cursor) :jumped)');
    await expect.poll(async () => await evalClj(window, '(:text (:message @lt.state/app))'))
        .toContain('still starting');

    // Ready again, and the same keypress works.
    await evalClj(window, `
        (do (doseq [c (lt.objs.editor.lsp/conns (first (pool/by-path "${file}")))]
              (swap! c assoc :initialized? true))
            :ready)`);
    await evalClj(window, '(do (cmd/exec! :editor.doc.toggle) :toggled)');
    await expect.poll(async () => await window.locator('.inline-doc').count(),
                      { timeout: 20000 }).toBe(1);

    await evalClj(window, `
        (do (doseq [ed (pool/by-path "${file}")] (object/raise ed :close)) :closed)`);
    fs.rmSync(path.dirname(path.dirname(file)), { recursive: true, force: true });
});

test('and a REPL that takes the surface has to answer for it', async ({ window }) => {
    // The second report of "toggle docs isn't working", and a different cause
    // from the first. `lt.objs.providers` lets a connected REPL take a surface
    // from the language server — deliberately: a REPL knows what is actually
    // loaded, and there is one doc bar. So the language server stands down.
    //
    // Which is fine right up until the REPL has nothing to say. `::clj-doc`
    // skipped silently when the cursor was not on a symbol, and
    // `::print-clj-doc` guarded with `(if-not result …)` on a value it had just
    // read a key from — so that branch could not fire, and cider-nrepl
    // answering `info` with no-info drew an empty box or nothing.
    //
    // Measured before it was believed: with no client the doc appears, and with
    // a client claiming `:doc` the widget count was 0, the message nil and the
    // error list empty. Complete silence.
    const file = project('lspreplsurface');

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
    await expect.poll(async () => await evalClj(window, `
        (boolean (object/has-tag? (first (pool/by-path "${file}")) :docable))`),
    { timeout: 30000 }).toBe('true');
    await evalClj(window, `
        (let [ed (first (pool/by-path "${file}"))]
          (lt.objs.editor/focus ed)
          (lt.objs.editor/move-cursor ed {:line 1 :ch 13})
          :ready)`);

    // The language server answers, because nothing else claims the surface.
    await evalClj(window, '(do (cmd/exec! :editor.doc.toggle) :toggled)');
    await expect.poll(async () => await insideEditor<number>(window, file,
        '(.-length (.querySelectorAll root ".inline-doc"))'), { timeout: 20000 }).toBe(1);
    await evalClj(window, '(do (cmd/exec! :editor.doc.toggle) :away)');

    // Now a client that says it provides :doc, the way the nREPL client does.
    // Nothing else about the editor changes.
    await evalClj(window, `
        (do (def surface-taker
              (object/create (object/object* :lt.probe/repl :tags #{:client} :init (fn [_] nil))))
            (object/merge! surface-taker {:name "probe-repl" :provides #{:doc}})
            (swap! lt.objs.clients/cs assoc (lt.objs.clients/->id surface-taker) surface-taker)
            (object/update! (first (pool/by-path "${file}")) [:client] assoc :default surface-taker)
            :connected)`);

    expect(await evalClj(window, `
        (let [ed (first (pool/by-path "${file}"))]
          (lt.objs.providers/provided?
            (->> (vals (:client @ed)) (filter #(and % (lt.objs.clients/available? %))) (map deref))
            :doc))`), 'the client should have taken the surface').toBe('true');

    // And the server stands down, which is the design. What must not happen is
    // that it stands down into nothing.
    await evalClj(window, '(do (lt.objs.notifos/set-msg! "") (cmd/exec! :editor.doc.toggle) :toggled)');
    await window.waitForTimeout(1500);
    expect(await insideEditor<number>(window, file,
        '(.-length (.querySelectorAll root ".inline-doc"))')).toBe(0);

    // And what happens when the REPL comes back with nothing, which for
    // cider-nrepl is most of what you point at: it hands the question back
    // rather than reporting, and the language server answers after all. That is
    // the difference between connecting a REPL and losing clojure-lsp.
    //
    // Driven by name, because the plugin's behaviors hang off `:editor.clj.*`
    // and a TypeScript file never carries them.
    //
    // Both languages, and that is the point rather than thoroughness. This test
    // named `print-clj-doc` and `clj-doc`, the two that had been fixed — so the
    // ClojureScript twins kept the identical pair of dead ends and the identical
    // silence, and it took a fourth report to find them. A third language would
    // go in this list.
    for (const lang of ['clj', 'cljs']) {
        await evalClj(window, `
            (do (lt.objs.notifos/set-msg! "")
                (lt.object/call-behavior-reaction :lt.plugins.clojure/print-${lang}-doc
                                                  (first (pool/by-path "${file}"))
                                                  {:result-type :doc :name "greet" :doc nil :args nil})
                :handed-back)`);
        await expect.poll(async () => await insideEditor<number>(window, file,
            '(.-length (.querySelectorAll root ".inline-doc"))'),
        { timeout: 20000, message: `${lang}: an empty answer must go back to the server` }).toBe(1);
        await evalClj(window, '(do (cmd/exec! :editor.doc.toggle) :away)');

        expect(await evalClj(window, `
            (do (lt.objs.notifos/set-msg! "")
                (let [ed (first (pool/by-path "${file}"))]
                  ;; Column 0 of a blank line: no symbol to ask about.
                  (lt.objs.editor/move-cursor ed {:line 2 :ch 0})
                  (lt.object/call-behavior-reaction :lt.plugins.clojure/${lang}-doc ed))
                (:text (:message @lt.state/app)))`), lang)
            .toContain('No symbol at the cursor');
    }

    // An answer with something to say and no name for it. `:editor.doc.show!`
    // looks a doc with no `:file` and no `:doc` up as one of Light Table's own
    // behaviors, which meant `(subs nil 2)` — a throw from inside a behavior,
    // which is a line in a console nobody reads and no doc bar. It is what the
    // silence above actually looked like from the inside.
    await window.evaluate(() => (globalThis as any).lt.objs.control.request('clear-errors', {}));
    await evalClj(window, `
        (do (lt.object/call-behavior-reaction :lt.plugins.doc/editor.doc.show!
                                              (first (pool/by-path "${file}"))
                                              {:result-type :doc :ns nil :name nil
                                               :args "[x]" :doc nil :file nil
                                               :loc {:line 1 :ch 13}})
            :shown)`);
    expect(await evalClj(window, '(count @lt.object/errors)'),
           'a nameless doc must not throw').toBe('0');
    await evalClj(window, '(do (cmd/exec! :editor.doc.toggle) :away)');

    await evalClj(window, `
        (do (swap! lt.objs.clients/cs dissoc (lt.objs.clients/->id surface-taker))
            (object/merge! (first (pool/by-path "${file}")) {:client {}})
            (doseq [ed (pool/by-path "${file}")] (object/raise ed :close))
            :closed)`);
    fs.rmSync(path.dirname(path.dirname(file)), { recursive: true, force: true });
});

test('and the bar says what the language server is doing', async ({ window }) => {
    // "I cannot tell if the language server is doing anything" is a real
    // report, and it was fair: the four ways this can be quiet — no server
    // configured, one configured and not installed, one starting, one
    // answering — looked identical from the outside, and telling them apart
    // meant knowing that `:lsp.status` exists.
    //
    // The shapes are asserted from a map in `test/lt/ui/view_test.cljs`. This
    // says the projection reaches the bar in a real window, which that cannot.
    const file = project('lspstatusbar');
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
    await expect.poll(async () => await evalClj(window, `
        (boolean (object/has-tag? (first (pool/by-path "${file}")) :docable))`),
    { timeout: 30000 }).toBe('true');
    await evalClj(window, `
        (do (lt.objs.editor/focus (first (pool/by-path "${file}")))
            (lt.state.objects/sync!)
            :synced)`);

    // Connected and answering, named, and the dot is the kit's.
    const lsp = window.locator('#statusbar .statusbar__lsp');
    await expect.poll(async () => await lsp.count()).toBe(1);
    // Named after whichever server the status reports rather than a name
    // written here: earlier tests in this run declare servers of their own, and
    // `status` describes the last one declared.
    expect(await lsp.innerText())
        .toContain(await evalData<string>(window, '(:command (:lsp @lt.state/app))'));
    await expect(lsp.locator('.dot--finished')).toHaveCount(1);

    // What clicking it asks for. The handler itself is a vector and is asserted
    // as one in `test/lt/ui/view_test.cljs`; that a data handler on this bar
    // reaches the dispatch table is asserted by the console pill in
    // `renderer.spec.ts`. What is left, and what this is for, is that the
    // command those two lead to says something useful — it existed before this
    // and nobody could be expected to find it.
    await evalClj(window, `
        (do (lt.objs.editor/focus (first (pool/by-path "${file}")))
            (lt.objs.notifos/set-msg! "")
            (lt.actions/dispatch! [[:cmd/exec :lsp.status]])
            :asked)`);
    await expect.poll(async () => await evalClj(window, '(:text (:message @lt.state/app))'))
        .toContain('Connected to');

    // A file with no server configured for it draws nothing at all — this is
    // a fact about the file you are in, so most files have none.
    await evalClj(window, `
        (do (doseq [ed (pool/by-path "${file}")] (object/raise ed :close))
            (cmd/exec! :new-file)
            (lt.state.objects/sync!)
            :plain)`);
    await expect.poll(async () => await lsp.count()).toBe(0);

    fs.rmSync(path.dirname(path.dirname(file)), { recursive: true, force: true });
});

test('and a server that is ready says which one, and where', async ({ window }) => {
    // "Language server ready" was said on every `:lsp.ready`, with nothing tying
    // it to the editor you were looking at. A connection is per `[root command
    // args]`, so with two servers declared — or two projects open — that was a
    // true sentence about something else, and it is what sent three separate
    // investigations of "toggle docs isn't working" to the wrong place while
    // the connection the open editor used had `initialized? false`.
    const file = project('lspreadyname');

    await evalClj(window, `
        (do (lt.objs.notifos/set-msg! "")
            (lt.object/call-behavior-reaction
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
    await expect.poll(async () => await evalClj(window, `
        (boolean (object/has-tag? (first (pool/by-path "${file}")) :docable))`),
    { timeout: 30000 }).toBe('true');

    // The server is `node`, the project is the scratch directory this test
    // made — so the message names both halves of what the connection is keyed
    // by, and a second connection could not produce the same sentence.
    const said = String(await evalClj(window, `
        (lt.objs.editor.lsp/ready-message
          (first (lt.objs.editor.lsp/conns (first (pool/by-path "${file}")))))`));
    expect(said).toContain('node ready in ');
    expect(said).toContain(path.basename(path.dirname(path.dirname(file))));

    await evalClj(window, `
        (do (doseq [ed (pool/by-path "${file}")] (object/raise ed :close)) :closed)`);
    fs.rmSync(path.dirname(path.dirname(file)), { recursive: true, force: true });
});

test('and a doc press that nothing answers says so', async ({ window }) => {
    // The guard that does not need to know what went wrong. Five reports of
    // "toggle docs isn't working" with four different causes, and every one
    // looked the same from the outside: no widget, no message, no console line.
    // Each fix closed the path it was about and the next silence was identical.
    //
    // So the command checks whether anything happened. Every way this can fail
    // ends in no widget and nothing said, whatever the reason and whether or
    // not anybody has found it yet.
    const file = project('lspunanswered');

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
    await expect.poll(async () => await evalClj(window, `
        (boolean (object/has-tag? (first (pool/by-path "${file}")) :docable))`),
    { timeout: 30000 }).toBe('true');

    // A client that claims the surface and never replies — which is what the
    // local ClojureScript client does for `:editor.cljs.doc`, because its
    // `on-message` has a `:default` method that is a no-op. Nothing declines,
    // nothing errors, nothing arrives.
    await evalClj(window, `
        (do (def mute
              (object/create (object/object* :lt.probe/mute :tags #{:client}
                                             :init (fn [_] nil))))
            (object/merge! mute {:name "mute" :provides #{:doc}})
            (swap! lt.objs.clients/cs assoc (lt.objs.clients/->id mute) mute)
            (object/update! (first (pool/by-path "${file}")) [:client] assoc :default mute)
            :muted)`);

    await evalClj(window, `
        (let [ed (first (pool/by-path "${file}"))]
          (lt.objs.editor/focus ed)
          (lt.objs.editor/move-cursor ed {:line 1 :ch 13})
          (lt.objs.notifos/set-msg! "")
          (cmd/exec! :editor.doc.toggle)
          :pressed)`);

    await expect.poll(async () => await evalClj(window, '(:text (:message @lt.state/app))'),
                      { timeout: 15000 }).toContain('Nothing answered');
    expect(await insideEditor<number>(window, file,
        '(.-length (.querySelectorAll root ".inline-doc"))')).toBe(0);

    // And it does not talk over a decline that did report. A feature saying
    // "no" is a feature working, and replacing its sentence with "nothing
    // answered" would lose the only part that was useful. The decline used is
    // the real one: a server still doing its handshake.
    await evalClj(window, `
        (do (swap! lt.objs.clients/cs dissoc (lt.objs.clients/->id mute))
            (object/merge! (first (pool/by-path "${file}")) {:client {}})
            (doseq [c (lt.objs.editor.lsp/conns (first (pool/by-path "${file}")))]
              (swap! c assoc :initialized? false))
            (lt.objs.notifos/set-msg! "")
            (cmd/exec! :editor.doc.toggle)
            :pressed)`);
    await expect.poll(async () => await evalClj(window, '(:text (:message @lt.state/app))'))
        .toContain('still starting');
    // Past the window in which the guard would have spoken.
    await window.waitForTimeout(4000);
    expect(await evalClj(window, '(:text (:message @lt.state/app))'),
           'the decline must survive the guard').toContain('still starting');

    await evalClj(window, `
        (do (doseq [c (lt.objs.editor.lsp/conns (first (pool/by-path "${file}")))]
              (swap! c assoc :initialized? true))
            (object/merge! (first (pool/by-path "${file}")) {:client {}})
            (doseq [ed (pool/by-path "${file}")] (object/raise ed :close))
            :closed)`);
    fs.rmSync(path.dirname(path.dirname(file)), { recursive: true, force: true });
});

test('and the bar follows the server without being nudged', async ({ window }) => {
    // Reported as "it says connected while still saying connecting and flashing
    // the light", which is exactly what it was: two indicators of one fact,
    // one live and one a snapshot from before the handshake. `::on-ready` says
    // its sentence the moment it happens; the dot beside it is drawn from the
    // `:lsp` key the projection writes, and `lt.ui.window/sync-from-objects`
    // listens for `:active :dirty :clean :close :focus :set-client` — none of
    // which a language server ever raises.
    //
    // So the key kept whatever it held when the editor was last focused, which
    // for a file opened before its server finished indexing is `:connecting`,
    // pulsing, for as long as the window is open. Measured on this repository
    // against the real clojure-lsp: ready at 1.2s, and the dot still pulsing
    // `connecting` with a diagnostic count of 0 at nine seconds and rising.
    //
    // Nothing here focuses, switches tab or edits after opening — that is the
    // whole test. Any of them would sync for another reason and hide this.
    const file = project('lspbarfollows');

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
    await expect.poll(async () => await evalClj(window, `
        (count (pool/by-path "${file}"))`)).toBe('1');

    // The truth, from the objects. Waited for so that what follows is about
    // the projection lagging rather than about the server being slow.
    await expect.poll(async () => await evalData<boolean>(window, `
        (:ready? (lt.objs.editor.lsp/status (first (pool/by-path "${file}"))))`),
    { timeout: 30000 }).toBe(true);

    // And the projection, which is what the bar draws. This was `:connecting`
    // forever.
    await expect.poll(async () => await evalData<string>(window,
        '(str (:status (:lsp @lt.state/app)))'), { timeout: 10000 }).toBe(':finished');

    // The count moves too, and for the same reason: it is the same key, drawn
    // beside the server's name, and it only changed when you switched tabs.
    await expect.poll(async () => await evalData<number>(window,
        '(:diagnostics (:lsp @lt.state/app))'), { timeout: 20000 }).toBeGreaterThan(0);

    const lsp = window.locator('#statusbar .statusbar__lsp');
    await expect(lsp.locator('.dot--finished')).toHaveCount(1);
    expect(await lsp.innerText()).toMatch(/ · \d+$/);

    await evalClj(window, `
        (do (doseq [ed (pool/by-path "${file}")] (object/raise ed :close)) :closed)`);
    fs.rmSync(path.dirname(path.dirname(file)), { recursive: true, force: true });
});
