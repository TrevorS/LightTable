#!/usr/bin/env node

// Boots the real application, opens a file, and checks that the things which
// have actually broken here in the past still work.
//
// Every failure this project has hit through modernization has been a load-time
// or cross-process one: a namespace throwing while loading, an Electron API that
// no longer exists, an ipc channel with nobody listening, a worker that cannot
// talk back. None of those are reachable from unit tests, so this drives the
// assembled app instead.
//
// Run with:  script/smoke-test.sh
// (it needs a display; the wrapper supplies one via xvfb when there isn't one)

import * as path from 'node:path';
import { spawn } from 'node:child_process';
import * as fs from 'node:fs';
import * as os from 'node:os';
import { createRequire } from 'node:module';
import { ROOT, CORE, SCRIPT_DIR, electronBinary } from './lib/paths.mts';

// deploy/core/package.json is data this reads, and an ES module has no
// `require` to read JSON with.
const require = createRequire(import.meta.url);
// The electron package reports where its own binary is, which differs by
// platform: dist/electron on Linux, dist/Electron.app/Contents/MacOS/Electron
// on a Mac. Hard-coding the Linux one worked here and nowhere else.
const ELECTRON = await electronBinary();
const SAMPLE = path.join(ROOT, 'src', 'lt', 'objs', 'platform.cljs');
// A page for the browser tab to navigate to. A file:// url rather than a real
// site, so the check tests Light Table rather than the network.
const PROBE_PAGE = path.join(os.tmpdir(), 'lt-smoke-browser-probe.html');
fs.writeFileSync(PROBE_PAGE, '<!doctype html><title>lt probe</title><body>lt-browser-probe-loaded');
// A small tree for the workspace searcher. Its own directory rather than a
// corner of the repository, so the expected counts are exact.
//
// Nothing covered project-wide search before, and that is how it came to be
// broken for who knows how long: the `replace` package it called had no
// `result` callback and returned a plain array, so the searcher displayed
// nothing and reported searching `undefined` files. Every other check passed
// throughout. This one runs the real path — searcher, worker, engine, and the
// message back — and asserts on the numbers.
const SEARCH_DIR = path.join(os.tmpdir(), 'lt-smoke-search');
fs.rmSync(SEARCH_DIR, { recursive: true, force: true });
fs.mkdirSync(path.join(SEARCH_DIR, 'nested'), { recursive: true });
fs.writeFileSync(path.join(SEARCH_DIR, 'one.txt'), 'first\nSMOKENEEDLE here\nthird\n');
fs.writeFileSync(path.join(SEARCH_DIR, 'two.txt'), 'SMOKENEEDLE\nand SMOKENEEDLE again\n');
fs.writeFileSync(path.join(SEARCH_DIR, 'nested', 'three.txt'), 'deep SMOKENEEDLE\n');
fs.writeFileSync(path.join(SEARCH_DIR, 'quiet.txt'), 'nothing to find\n');
// Arguments for the background scan the smoke test uses to prove the worker
// round trip. Note that walkdir's pattern is an exclusion, matching how
// lt.objs.sidebar.navigate passes files/ignore-pattern through: this skips
// dotfiles and collects everything else under a directory with several files.
const SCAN_ARGS = JSON.stringify({
    lim: 200,
    pattern: '^\\.',
    ws: { folders: [path.join(ROOT, 'src', 'lt', 'background')], files: [] }
});
// What the search box would hold: the term, an empty replacement, and the
// location to search. `loc` is a plain path, which lt.objs.search/string->loc
// turns into a single search root.
const SEARCH_ARGS = JSON.stringify({ search: 'SMOKENEEDLE', replace: '', loc: SEARCH_DIR });
// A TypeScript file for the tree-sitter highlighting check. Written here rather
// than pointed at one in the repository so the expected captures are stable.
const TS_PROBE = path.join(os.tmpdir(), 'lt-smoke-highlight.ts');
fs.writeFileSync(TS_PROBE, [
    'import { readFile } from "node:fs/promises";',
    '',
    'interface User { id: number; name: string; }',
    '',
    'export async function loadUsers(p: string): Promise<User[]> {',
    '  const raw = await readFile(p, "utf8");',
    '  return JSON.parse(raw).filter((u) => u.id > 0);',
    '}',
    ''
].join('\n'));
// A file that is not text, for the byte-reading capability. A WebAssembly
// header is the case that turned up the gap, plus one byte that is not valid
// UTF-8 — 0xFF cannot begin a sequence.
//
// The header alone would not have shown anything: `\0asm\x01\0\0\0` is
// eight bytes all below 0x80, so it survives a UTF-8 decode untouched. Real
// modules are full of bytes that do not, which is the whole problem, so the
// probe has to contain one for the contrast below to mean anything.
const BINARY_PROBE = path.join(os.tmpdir(), 'lt-smoke-bytes.wasm');
fs.writeFileSync(BINARY_PROBE, Buffer.from([0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00, 0xFF]));

// A project with a language server in it, for the LSP check.
//
// Laid out the way a real one is — a root marker, and the server under
// node_modules/.bin — because that layout *is* the thing being tested: the
// server is resolved relative to the project rather than to Light Table, on
// purpose, and a check that skipped it would not be checking the rule.
//
// The server is script/fixtures/fake-language-server.mts, which reports back
// what it was told. That is what makes this able to assert on synchronisation
// instead of on a message merely arriving: both bugs in the first diagnostics
// slice produced a document version that incremented correctly while
// `didChange` never left the process, and Light Table catches exceptions
// inside behavior reactions, so neither said anything.
// A file to edit and save. Saving is the operation an editor exists for, and
// it was broken for an entire release without anything noticing: a stat
// crossing the preload boundary lost its `mtime`, `check-mtime` threw reading
// it, the throw was swallowed by the behavior that raised it, and `save`
// aborted before writing a byte. The tab stayed dirty and the console carried
// the only evidence. Nothing here looked at the bytes on disk.
const SAVE_PROBE = path.join(os.tmpdir(), 'lt-smoke-save.txt');
fs.writeFileSync(SAVE_PROBE, 'before\n');

// The editor changing itself while running — see doc/live-editing.md. This is
// the feature Light Table is named for, and it rests on build configuration
// (`:optimizations :simple`, `:output-wrapper false`, and the analysis cache
// built by `shadow-cljs release bootstrap`) that nothing else here would
// notice the loss of. Compiled in the window, evaluated in the window: if this
// check fails the editor is still an editor and is no longer Light Table.
const SELFEVAL_PROBE = path.join(os.tmpdir(), 'lt-smoke-selfeval.cljs');
fs.writeFileSync(SELFEVAL_PROBE,
    '(ns lt.smoke-probe\n' +
    '  (:require [lt.objs.command :as cmd]))\n' +
    '\n' +
    '(+ 20 22)\n' +
    '\n' +
    '(cmd/command {:command :lt-smoke-live\n' +
    '              :desc "Defined by evaluating a buffer, while running"\n' +
    '              :exec (fn [] :ok)})\n');

const SELFEVAL_EVAL = `(function () {
    var ed = cljs.core.first.call(null, lt.objs.editor.pool.by_path(${JSON.stringify(SELFEVAL_PROBE)}));
    if (!ed) { return 'no editor'; }
    // :eval rather than the command, for the reason SAVE_EDIT gives: the
    // command starts from pool/last-active and this window is never focused.
    lt.object.raise.cljs$core$IFn$_invoke$arity$variadic(
        ed, cljs.core.keyword.call(null, 'eval'),
        cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([], 0));
    return 'evaluating';
})()`;

const SELFEVAL_REPORT = `JSON.stringify((function () {
    var kw = function (n) { return cljs.core.keyword.call(null, n); };
    var ed = cljs.core.first.call(null, lt.objs.editor.pool.by_path(${JSON.stringify(SELFEVAL_PROBE)}));
    if (!ed) { return { error: 'no editor' }; }
    var client = cljs.core.get.call(null,
        cljs.core.get.call(null, cljs.core.deref(ed), kw('client')), kw('default'));
    return {
        // The whole point: a command that did not exist when the editor
        // started, defined by evaluating a buffer open in it.
        defined: !!lt.objs.command.by_id(kw('lt-smoke-live')),
        client: client ? cljs.core.get.call(null, cljs.core.deref(client), kw('name')) : null,
        compiler: cljs.core.pr_str(lt.objs.cljs_compiler.describe()),
        // One result per top-level form, which is what makes them land beside
        // the form that produced them rather than all at the end.
        results: cljs.core.pr_str(cljs.core.mapv.call(null, function (kv) {
            var el = cljs.core.get.call(null, cljs.core.deref(cljs.core.second(kv)), kw('content'));
            if (!el) { return ''; }
            // .full, not textContent: an inline result carries a truncated
            // span beside the full one, so textContent says everything twice.
            var full = el.querySelector ? el.querySelector('.full') : null;
            return (full || el).textContent || '';
        }, cljs.core.get.call(null, cljs.core.deref(ed), kw('widgets'))))
    };
})())`;

const LSP_DIR = path.join(os.tmpdir(), 'lt-smoke-lsp');
const LSP_PROBE = path.join(LSP_DIR, 'src', 'probe.ts');
fs.rmSync(LSP_DIR, { recursive: true, force: true });
fs.mkdirSync(path.join(LSP_DIR, 'src'), { recursive: true });
fs.mkdirSync(path.join(LSP_DIR, 'node_modules', '.bin'), { recursive: true });
fs.writeFileSync(path.join(LSP_DIR, 'tsconfig.json'), '{"compilerOptions":{"strict":true}}\n');
fs.writeFileSync(LSP_PROBE, 'export const first = 1;\nexport const second = 2;\nexport const third = 3;\n');
// Spawned as `node <file>.mts` rather than copied to an extensionless shim in
// .bin. Node decides whether to strip types by the extension, so a TypeScript
// fixture has to keep one — and this is closer to how a real server is
// declared anyway, which is a command and its arguments.
const FAKE_SERVER = path.join(SCRIPT_DIR, 'fixtures', 'fake-language-server.mts');

// The three snippets the LSP check evaluates in the renderer.
//
// Built out here and passed in as strings rather than written inside the
// harness template literal, because ClojureScript's munged arity names are
// full of `$` and a `${` inside a template literal is an interpolation. That
// has already cost this file twice.
// Declaring servers the way a user.behaviors entry does, rather than writing
// into a table: language servers are :lt.objs.editor.lsp/language-servers now,
// a non-exclusive :user behavior that accumulates. Running the real reaction
// appends these after the TypeScript plugin's.
//
// Two of them, because two servers for one language is the arrangement this
// has to support — a type checker and a linter. The first carries :id "vtsls",
// which is the plugin's own entry, so it *replaces* it: that is the precedence
// rule, and it is also what keeps this check from starting a real vtsls on
// whichever machine has one installed. The second is a new id, so it stacks.
const LSP_START = `(function () {
    var kw = function (n) { return cljs.core.keyword.call(null, n); };
    var assoc = cljs.core.assoc.cljs$core$IFn$_invoke$arity$3;
    var vec = function () { return cljs.core.vec.call(null, cljs.core.PersistentVector.fromArray(Array.prototype.slice.call(arguments), true)); };
    var server = function (id, args) {
        var entry = cljs.core.PersistentArrayMap.EMPTY;
        entry = assoc(entry, kw('tags'), vec(kw('editor.typescript')));
        entry = assoc(entry, kw('language-id'), 'typescript');
        entry = assoc(entry, kw('root'), vec('tsconfig.json'));
        entry = assoc(entry, kw('id'), id);
        // node, and the fixture as its argument. lt.objs.editor.lsp looks the
        // command up under the project's node_modules/.bin first, then on PATH.
        entry = assoc(entry, kw('command'), 'node');
        return assoc(entry, kw('args'), cljs.core.vec.call(null, cljs.core.PersistentVector.fromArray(args, true)));
    };
    lt.object.call_behavior_reaction.call(
        null, kw('lt.objs.editor.lsp/language-servers'),
        lt.objs.editor.lsp.lsp_client,
        vec(server('vtsls', [${JSON.stringify(FAKE_SERVER)}]),
            // Diagnostics and nothing else, on lines of its own — so what it
            // says has to survive the other one publishing, and the surfaces
            // it does not offer have to be routed past it.
            server('linter', [${JSON.stringify(FAKE_SERVER)},
                              '--source', 'linter', '--line', '2', '--diagnostics-only'])));
    lt.objs.command.exec_BANG_(kw('open-path'), ${JSON.stringify(LSP_PROBE)});
})()`;

const LSP_EDIT = `(function () {
    var ed = cljs.core.first.call(null, lt.objs.editor.pool.by_path(${JSON.stringify(LSP_PROBE)}));
    lt.objs.editor.__GT_cm_ed(ed).replaceRange('X', { line: 0, ch: 0 });
})()`;

const LSP_ACTIONS = `(function () {
    var kw = function (n) { return cljs.core.keyword.call(null, n); };
    var ed = cljs.core.first.call(null, lt.objs.editor.pool.by_path(${JSON.stringify(LSP_PROBE)}));
    // Saved first, and not incidentally. An action's edits go through
    // lt.objs.workspace-edit, which refuses to start when a file it would
    // touch has unsaved changes — the keystroke check above left this buffer
    // dirty, and without this the action is correctly declined.
    lt.object.raise.cljs$core$IFn$_invoke$arity$variadic(
        ed, kw('save'), cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([], 0));
    lt.object.raise.call(null, ed, kw('editor.code-actions!'));
})()`;

// Clicking the first button in the selector, which is how a person chooses.
const LSP_ACTION_PICK = `(function () {
    var popup = document.querySelector('.popup');
    if (!popup) return 'no popup';
    // .lsp-action, not li.button: the popup's own cancel is one of those.
    var buttons = popup.querySelectorAll('li.lsp-action');
    if (!buttons.length) { lt.objs.command.exec_BANG_(cljs.core.keyword.call(null,'popup.escape')); return 'no actions'; }
    buttons[0].click();
    return buttons.length + ' offered';
})()`;

// A modal left open is a modal every later step is behind, and on a headed
// run it is an editor that looks hung. Whatever happened above, nothing is
// waiting for a click after this.
const LSP_DISMISS = `(function () {
    var closed = 0;
    for (var i = 0; i < 5 && document.querySelector('.popup'); i++) {
        lt.objs.command.exec_BANG_(cljs.core.keyword.call(null, 'popup.escape'));
        closed += 1;
    }
    return { closed: closed, stillOpen: !!document.querySelector('.popup') };
})()`;

const LSP_FORMAT = `(function () {
    var kw = function (n) { return cljs.core.keyword.call(null, n); };
    var ed = cljs.core.first.call(null, lt.objs.editor.pool.by_path(${JSON.stringify(LSP_PROBE)}));
    lt.object.raise.call(null, ed, kw('editor.format!'));
})()`;

const LSP_REPORT = `JSON.stringify((function () {
    var kw = function (n) { return cljs.core.keyword.call(null, n); };
    var out = {};
    try {
        var ed = cljs.core.first.call(null, lt.objs.editor.pool.by_path(${JSON.stringify(LSP_PROBE)}));
        if (!ed) return { error: 'no editor' };
        var st = cljs.core.deref(ed);
        var doc = cljs.core.get.call(null, st, kw('lt.objs.editor.lsp/doc'));
        var widgets = cljs.core.get.call(null, st, kw('lt.objs.editor.lsp/widgets'));
        out.connected = cljs.core.count(lt.objs.editor.lsp.conns(ed)) > 0;
        // Both of them, which is the point: a language with a type checker and
        // a linter runs both, and one publishing does not erase the other.
        out.connections = cljs.core.count(lt.objs.editor.lsp.conns(ed));
        // Which entry in the table this editor resolved to. The TypeScript
        // plugin declares vtsls for :editor.typescript and the harness
        // replaced it by declaring the same id afterwards, so the command here
        // says which declaration won.
        var status = lt.objs.editor.lsp.status(ed);
        out.command = String(cljs.core.get.call(null, status, kw('command')));
        out.servers = cljs.core.count(cljs.core.get.call(null, status, kw('servers')));
        out.declared = cljs.core.count(lt.objs.editor.lsp.servers());
        out.version = doc ? cljs.core.get.call(null, doc, kw('version')) : null;
        out.uri = doc ? String(cljs.core.get.call(null, doc, kw('uri'))) : null;
        // One widget per line with a diagnostic, not one per diagnostic.
        out.widgets = widgets ? cljs.core.count(widgets) : -1;
        out.tags = cljs.core.pr_str(cljs.core.get.call(null, st, kw('tags')));
        out.formattable = out.tags.indexOf(':formattable') !== -1;
        out.actionable = out.tags.indexOf(':actionable') !== -1;
        out.diagnosticsKept = cljs.core.count(lt.objs.editor.lsp.diagnostics(ed));
        out.firstLine = String(lt.objs.editor.__GT_val(ed)).split('\\n')[0];
        var el = lt.object.__GT_content(ed);
        out.messages = Array.from(el.querySelectorAll('.inline-diagnostic')).map(function (n) {
            return n.className.replace('inline-diagnostic ', '') + ': ' +
                   ((n.querySelector('.message') || {}).textContent || '');
        });
        // Which server said each one. Two servers publishing about one file is
        // the case where a count says nothing: three diagnostics is what one
        // server alone produces.
        out.sources = Array.from(el.querySelectorAll('.inline-diagnostic .source'))
            .map(function (n) { return n.textContent; });
    } catch (e) { out.error = String((e && e.message) || e); }
    return out;
})())`;

const SAVE_EDIT = `(function () {
    var ed = cljs.core.first.call(null, lt.objs.editor.pool.by_path(${JSON.stringify(SAVE_PROBE)}));
    if (!ed) { return 'no editor'; }
    lt.objs.editor.__GT_cm_ed(ed).replaceRange('after\\n', { line: 0, ch: 0 });
    // What the :save command does once it has an editor. Not the command
    // itself: that starts from pool/last-active, which is maintained by focus,
    // and this window is created with show:false and never focused. Testing
    // through it would be testing the harness.
    lt.object.raise.cljs$core$IFn$_invoke$arity$variadic(
        ed, cljs.core.keyword.call(null, 'save'),
        cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2([], 0));
    return 'saved';
})()`;

const SAVE_REPORT = `JSON.stringify((function () {
    var ed = cljs.core.first.call(null, lt.objs.editor.pool.by_path(${JSON.stringify(SAVE_PROBE)}));
    if (!ed) { return { error: 'no editor' }; }
    return {
        // A tab still marked dirty after a save is the symptom a user sees.
        dirty: cljs.core.get.call(null, cljs.core.deref(ed), cljs.core.keyword.call(null, 'dirty')) === true
    };
})())`;

// The harness reuses the real main.js so that ipc handlers, command line
// parsing and window options are the ones that ship, not a copy that can drift.
const HARNESS = `
const CORE = ${JSON.stringify(CORE)};
const SCAN_ARGS = ${JSON.stringify(SCAN_ARGS)};
const SEARCH_ARGS = ${JSON.stringify(SEARCH_ARGS)};
const REPORT = process.env.LT_SMOKE_REPORT;
const fsx = require('fs');
let report = { ok: false };
function finish(code) { try { fsx.writeFileSync(REPORT, JSON.stringify(report, null, 1)); } catch (e) {} app.exit(code); }

global.browserOpenFiles = [];
global.browserParsedArgs = { _: [] };
// The debugging port and its origin allowance come from main.js at module
// scope, so this harness gets them without repeating them.

setTimeout(function () { report.failure = 'timed out before the window reported back'; finish(1); }, 180000);
process.on('uncaughtException', function (e) { report.failure = 'main process threw: ' + e.message; finish(1); });

app.on('ready', function () {
    registerRendererApi();
    const pkg = require(CORE + '/package.json');
    // show comes from the environment the same way createWindow's does, so
    // smoke-test.sh --headed shows this window too. No backticks in here:
    // this whole harness is a template literal, and one ends it.
    const opts = Object.assign({}, pkg.browserWindowOptions,
                               { show: !!process.env.LT_HEADED, width: 1280, height: 820 });
    opts.icon = CORE + '/' + pkg.browserWindowOptions.icon;
    // Electron resolves neither of these relative to the app directory, the
    // same way createWindow() does not.
    opts.webPreferences = Object.assign({}, pkg.browserWindowOptions.webPreferences,
                                        { preload: CORE + '/' + pkg.browserWindowOptions.webPreferences.preload });
    const w = new BrowserWindow(opts);

    // Anything the renderer throws before Light Table's own console exists is
    // otherwise invisible; the probe just reports that it could not run.
    const consoleErrors = [];
    w.webContents.on('console-message', function (a, b, c) {
        // The signature changed to a single event object in recent Electron.
        const msg = (a && typeof a === 'object' && 'message' in a) ? a.message : c;
        const lvl = (a && typeof a === 'object' && 'level' in a) ? a.level : b;
        if (lvl === 'error' || lvl === 3 || lvl === 'warning' || lvl === 2) {
            consoleErrors.push(String(lvl) + ': ' + String(msg).slice(0, 300));
        }
    });

    w.webContents.on('did-finish-load', function () {
        setTimeout(async function () {
            // Which step failed, so a probe error names a cause rather than
            // sending the reader back to the renderer console.
            let step = 'starting';
            try {
                step = 'opening a file';
                await w.webContents.executeJavaScript(
                    'lt.objs.command.exec_BANG_(cljs.core.keyword.call(null,"open-path"),' + JSON.stringify(${JSON.stringify(SAMPLE)}) + ')');
                await new Promise(function (r) { setTimeout(r, 5000); });

                // Exercise a background job end to end. The worker runs compiled
                // code now rather than source shipped across, so a round trip
                // proves the whole path: fork, init, dispatch, and reply. The
                // navigate scan is used because an object already exists to
                // receive its result.
                step = 'running a background job';
                await w.webContents.executeJavaScript(
                    'lt.objs.sidebar.navigate.populate_bg.call(null,' +
                    ' lt.objs.sidebar.navigate.sidebar_navigate,' +
                    ' cljs.core.js__GT_clj.call(null, JSON.parse(' + JSON.stringify(SCAN_ARGS) + '),' +
                    ' cljs.core.keyword.call(null,"keywordize-keys"), true))');
                await new Promise(function (r) { setTimeout(r, 4000); });

                // The workspace searcher, driven the way the search box drives
                // it: an info map straight to the :search! behavior, so the
                // path under test is the shipped one rather than a direct call
                // to the engine.
                step = 'searching the workspace';
                await w.webContents.executeJavaScript(
                    'lt.object.raise.call(null, lt.objs.search.searcher,' +
                    ' cljs.core.keyword.call(null,"search!"),' +
                    ' cljs.core.js__GT_clj.call(null, JSON.parse(' + JSON.stringify(SEARCH_ARGS) +
                    '), cljs.core.keyword.call(null,"keywordize-keys"), true))');
                await new Promise(function (r) { setTimeout(r, 4000); });

                // Process stdio, both directions. Its own step because the
                // report is a classic script and cannot await.
                //
                // The child is cat, which echoes stdin, so this is a real
                // process round trip. The payload is deliberately multi-byte
                // and written in two
                // pieces: a character split across two chunks is exactly what
                // decoding each chunk on its own gets wrong, which is why the
                // byte channel exists at all.
                step = 'writing to and reading from a process';
                const stdio = JSON.parse(await w.webContents.executeJavaScript(\`(async function () {
                    var b = lt.util.bridge;
                    var out = { wrote: false, bytesLen: 0, decoded: '', text: '' };
                    try {
                        var h = b.processes.spawn('cat', [], {});
                        var chunks = [];
                        h.onStdoutBytes(function (c) { chunks.push(c); });
                        h.onStdout(function (c) { out.text += c; });
                        var done = new Promise(function (r) { h.onExit(function () { r(); }); });
                        h.write('\\u4f60\\u597d');
                        h.write('\\u4e16\\u754c');
                        h.endStdin();
                        out.wrote = true;
                        await Promise.race([done, new Promise(function (r) { setTimeout(r, 5000); })]);
                        var joined = [];
                        chunks.forEach(function (c) { joined.push.apply(joined, Array.from(c)); });
                        out.bytesLen = joined.length;
                        out.isBytes = chunks.length > 0 && chunks[0] instanceof Uint8Array;
                        out.decoded = new TextDecoder().decode(new Uint8Array(joined));
                    } catch (e) { out.error = String((e && e.message) || e); }
                    return JSON.stringify(out);
                })()\`));

                // Tree-sitter highlighting, end to end: open a TypeScript
                // file and read the classes CodeMirror actually rendered.
                //
                // Asserted on capture names rather than a count, because the
                // point is the vocabulary. A per-line CodeMirror mode cannot
                // produce ts-type or ts-variable-parameter at all — it does not
                // know a type from a value or a parameter from a local — so
                // their presence is proof the parser is driving the colours.
                step = 'highlighting a TypeScript file with tree-sitter';
                await w.webContents.executeJavaScript(
                    'lt.objs.command.exec_BANG_(cljs.core.keyword.call(null,"open-path"),' +
                    JSON.stringify(${JSON.stringify(TS_PROBE)}) + ')');
                await new Promise(function (r) { setTimeout(r, 6000); });
                const treesitter = JSON.parse(await w.webContents.executeJavaScript(\`(function () {
                    var PROBE_TS = ${JSON.stringify(TS_PROBE)};
                    var out = { classes: [] };
                    try {
                        // By path, not last-active: the harness window is
                        // never focused, so last-active can be nil, and other
                        // steps have opened tabs since.
                        // by-path returns a sequence, not an editor. Handing
                        // the sequence on recurses through every editor object
                        // it contains and blows the stack.
                        var ed = cljs.core.first.call(null, lt.objs.editor.pool.by_path(PROBE_TS));
                        if (!ed) { out.error = 'no editor for ' + PROBE_TS; return JSON.stringify(out); }
                        out.report = cljs.core.clj__GT_js(lt.objs.editor.treesitter.report(ed));
                        var cm = lt.objs.editor.__GT_cm_ed(ed);
                        out.modeName = cm.getMode().name;
                        // Asserted on what the mode tokenizes rather than on
                        // the painted DOM. The harness window is created with
                        // show:false, so nothing drives a repaint and the
                        // rendered lines keep the tokens they had before the
                        // mode was swapped in — getLineTokens asks the mode
                        // directly and does not care whether anything is
                        // visible.
                        var seen = {};
                        var lineCount = cm.lineCount();
                        for (var l = 0; l < lineCount; l++) {
                            cm.getLineTokens(l, true).forEach(function (t) {
                                if (!t.type) return;
                                t.type.split(' ').forEach(function (c) {
                                    if (c) seen['cm-' + c] = true; });
                            });
                        }
                        out.classes = Object.keys(seen).sort();
                        out.lines = lineCount;
                    } catch (e) { out.error = String((e && e.message) || e); }
                    return JSON.stringify(out);
                })()\`));

                // Open a file, change it, save it, and look at the bytes.
                step = 'saving a file';
                await w.webContents.executeJavaScript(
                    'lt.objs.command.exec_BANG_(cljs.core.keyword.call(null,"open-path"),' +
                    JSON.stringify(${JSON.stringify(SAVE_PROBE)}) + ')');
                await new Promise(function (r) { setTimeout(r, 2500); });
                await w.webContents.executeJavaScript(${JSON.stringify(SAVE_EDIT)});
                await new Promise(function (r) { setTimeout(r, 2500); });
                const save = JSON.parse(await w.webContents.executeJavaScript(${JSON.stringify(SAVE_REPORT)}));
                // From this side, so it is the file on disk being asserted on
                // and not the editor's opinion of it.
                save.onDisk = fsx.readFileSync(${JSON.stringify(SAVE_PROBE)}, 'utf8');

                // ClojureScript, compiled in this window and run in it.
                step = 'compiling ClojureScript in the window';
                await w.webContents.executeJavaScript(
                    'lt.objs.command.exec_BANG_(cljs.core.keyword.call(null,"open-path"),' +
                    JSON.stringify(${JSON.stringify(SELFEVAL_PROBE)}) + ')');
                // Long enough for tree-sitter to have parsed, so the buffer is
                // split into top-level forms rather than evaluated whole.
                await new Promise(function (r) { setTimeout(r, 3000); });
                await w.webContents.executeJavaScript(${JSON.stringify(SELFEVAL_EVAL)});
                // The first evaluation loads cljs.core's analysis, which is
                // most of the cost and is paid once. Polled rather than slept
                // through, so a fast machine does not wait for a slow one.
                let selfEval = null;
                for (let i = 0; i < 60; i++) {
                    await new Promise(function (r) { setTimeout(r, 1000); });
                    selfEval = JSON.parse(await w.webContents.executeJavaScript(${JSON.stringify(SELFEVAL_REPORT)}));
                    if (selfEval && selfEval.defined) break;
                }

                // A language server, end to end: spawn, frame, handshake,
                // synchronise, and render.
                //
                // The server table is repointed at the fixture rather than the
                // check installing typescript-language-server, because what is
                // being tested is Light Table's half. A real server would make
                // this slow, network-dependent, and — since it reports only
                // about the code — unable to say whether the edit reached it.
                step = 'starting a language server';
                await w.webContents.executeJavaScript(${JSON.stringify(LSP_START)});
                await new Promise(function (r) { setTimeout(r, 4000); });

                // Then an edit, which is the half that has actually broken.
                // Made through CodeMirror rather than through a command so it
                // travels the same path a keystroke does — the :change event,
                // whose arguments are the editor instance and *then* the
                // change.
                step = 'editing a file a language server is watching';
                const lspBeforeEdit = JSON.parse(
                    await w.webContents.executeJavaScript(${JSON.stringify(LSP_REPORT)}));
                await w.webContents.executeJavaScript(${JSON.stringify(LSP_EDIT)});
                await new Promise(function (r) { setTimeout(r, 3000); });
                // Held aside rather than put straight on the report, which the
                // collecting step below replaces wholesale.
                const lsp = {
                    before: lspBeforeEdit,
                    after: JSON.parse(
                        await w.webContents.executeJavaScript(${JSON.stringify(LSP_REPORT)}))
                };

                // Code actions, which is the surface that offers a choice.
                // The fixture sends two, so this exercises the selector rather
                // than the shortcut for a single action.
                step = 'asking the language server for code actions';
                await w.webContents.executeJavaScript(${JSON.stringify(LSP_ACTIONS)});
                await new Promise(function (r) { setTimeout(r, 2500); });
                const actionOffer = await w.webContents.executeJavaScript(${JSON.stringify(LSP_ACTION_PICK)});
                await new Promise(function (r) { setTimeout(r, 2500); });
                lsp.actions = { offered: actionOffer };
                lsp.afterAction = JSON.parse(
                    await w.webContents.executeJavaScript(${JSON.stringify(LSP_REPORT)}));
                lsp.dismissed = await w.webContents.executeJavaScript(${JSON.stringify(LSP_DISMISS)});

                // Formatting: the one LSP surface that changes the buffer the
                // user is looking at, so it goes into the editor rather than
                // through lt.objs.workspace-edit.
                step = 'formatting through the language server';
                await w.webContents.executeJavaScript(${JSON.stringify(LSP_FORMAT)});
                await new Promise(function (r) { setTimeout(r, 2500); });
                lsp.formatted = JSON.parse(
                    await w.webContents.executeJavaScript(${JSON.stringify(LSP_REPORT)}));

                // Back to the sample, so the checks that read the visible
                // editor still see what every other step set up.
                await w.webContents.executeJavaScript(
                    'lt.objs.command.exec_BANG_(cljs.core.keyword.call(null,"open-path"),' +
                    JSON.stringify(${JSON.stringify(SAMPLE)}) + ')');
                await new Promise(function (r) { setTimeout(r, 2500); });

                step = 'collecting the report';
                report = JSON.parse(await w.webContents.executeJavaScript(\`JSON.stringify({
                    appInitialized: typeof lt.objs.app === 'object',
                    platform: String(lt.objs.platform.platform),
                    dataPath: String(lt.objs.platform.get_data_path()),
                    windowNumber: String(lt.objs.app.window_number()),
                    codeMirror: typeof CodeMirror === 'function',
                    codeMirrorAddons: typeof (CodeMirror && CodeMirror.overlayMode) === 'function',
                    codeMirrorModes: CodeMirror && CodeMirror.modes ? Object.keys(CodeMirror.modes).length : 0,
                    // Fold addons register helpers and extensions rather than
                    // anything on the constructor itself.
                    codeMirrorFold: !!(CodeMirror && CodeMirror.fold && CodeMirror.fold.brace),
                    editors: document.querySelectorAll('.CodeMirror').length,
                    editorText: (function () { var e = document.querySelector('.CodeMirror-code'); return e ? e.innerText.slice(0, 40) : ''; })(),
                    behaviors: cljs.core.count(cljs.core.deref(lt.object.behaviors)),
                    crateShim: typeof (window.crate && window.crate.core && window.crate.core.html) === 'function',
                    // The default user plugin, which is compiled from source in
                    // this repo rather than shipped as a checked-in artifact.
                    userPlugin: (function () {
                        var cmds = cljs.core.get.call(null, cljs.core.deref(lt.objs.command.manager),
                                                      cljs.core.keyword.call(null, 'commands'));
                        return !!(lt.plugins.user && lt.plugins.user.hello) &&
                               cljs.core.contains_QMARK_(cmds, cljs.core.keyword.call(null, 'user.say-hello'));
                    })(),
                    // The TypeScript plugin: loaded, and its commands really
                    // reached the command manager through the cljs interop.
                    tsPlugin: (function () {
                        var cmds = cljs.core.get.call(null, cljs.core.deref(lt.objs.command.manager),
                                                      cljs.core.keyword.call(null, 'commands'));
                        return !!(lt.plugins['typescript']) &&
                               cljs.core.contains_QMARK_(cmds, cljs.core.keyword.call(null, 'typescript.type-check')) &&
                               cljs.core.contains_QMARK_(cmds, cljs.core.keyword.call(null, 'typescript.open-tsconfig'));
                    })(),
                    // The window-side TypeScript modules, required rather than
                    // evaluated. Exercised, not just present: the command bar
                    // scoring is what fuzzy.ts is for, and it replaced a
                    // String.prototype patch, so a wrong port would rank
                    // results wrongly rather than throw.
                    windowModules: (function () {
                        var f = lt.window.modules.fuzzy;
                        var d = lt.window.modules.dragdrop;
                        if (!f || !d || typeof d.sortable !== 'function') return 'missing';
                        if (String.prototype.score) return 'String.prototype still patched';
                        var exact = f.stringScore('platform.cljs', 'platform.cljs');
                        var partial = f.stringScore('platform.cljs', 'plat');
                        var absent = f.stringScore('platform.cljs', 'zzz');
                        var m = f.score('lt/objs/platform.cljs', 'platform');
                        return [exact === 1, partial > 0 && partial < 1, absent === 0,
                                f.fastScore('platform.cljs', 'ptc') === true,
                                f.fastScore('platform.cljs', 'zqx') === false,
                                m.score > 0,
                                f.wrapMatch('abc', {matched: {0: true}}) === '<em>a</em>bc'].join(',');
                    })(),
                    // lt.util.load reaches the filesystem through the bridge
                    // now, and everything else loads through it. If this were
                    // wrong the window would not have booted at all — but the
                    // async path is only used by non-sync load/js, so it is
                    // worth exercising separately.
                    loadViaBridge: (function () {
                        var b = window.lightTable;
                        if (!b.files || !b.path) return 'no files capability';
                        if (lt.util.load.dir !== b.host.appDir() + '/..') return 'dir mismatch';
                        return [typeof lt.util.load.separator === 'string',
                                b.files.existsSync(b.host.appDir() + '/package.json'),
                                b.files.existsSync(b.host.appDir() + '/nope.json') === false,
                                b.files.readFileSync(b.host.appDir() + '/package.json').indexOf('LightTable') !== -1,
                                b.path.join('a', 'b') === 'a' + b.path.sep + 'b'].join(',');
                    })(),
                    // Workspace watching, which went from a watchFile/
                    // unwatchFile pair keyed on callback identity to a handle.
                    // Driven through lt.objs.workspace rather than the bridge
                    // so the ClojureScript side is what is being checked.
                    watching: (function () {
                        var dir = lt.util.load.dir;
                        try {
                            lt.objs.workspace.watch_BANG_.cljs$core$IFn$_invoke$arity$1(dir);
                            var ws = cljs.core.get.call(null, cljs.core.deref(lt.objs.workspace.current_ws),
                                                        cljs.core.keyword.call(null, 'watches'));
                            var n = cljs.core.count(ws);
                            lt.objs.workspace.unwatch_BANG_.cljs$core$IFn$_invoke$arity$1(dir);
                            return n > 0 ? 'watched ' + (n > 0) : 'nothing watched';
                        } catch (e) { return 'THREW ' + e.message; }
                    })(),
                    consoleLog: typeof lt.objs.console.core_log === 'string',
                    // Both client servers moved into the preload whole, with
                    // connections identified by number. Listening on a real
                    // port is the thing that proves they started.
                    servers: (function () {
                        return [lt.objs.clients.tcp.__GT_port() > 0,
                                lt.objs.clients.ws.__GT_port() > 0,
                                !!lt.objs.clients.tcp.server,
                                !!lt.objs.clients.ws.server].join(',');
                    })(),
                    // The window has no Node left. This is the property the
                    // whole migration is for, and it is worth failing loudly
                    // if any of it comes back. require and process are present
                    // but are Light Table's own — the isolation check below is
                    // what distinguishes them from Node's.
                    noNodeInTheWindow: [typeof require, typeof process, typeof __dirname,
                                        typeof module].join(','),
                    // contextIsolation itself, established three ways rather
                    // than by reading a config file back: the window's world
                    // is separate from the preload's, Node's own globals are
                    // gone, and what is left came from Light Table.
                    isolated: (function () {
                        return {
                            noDirname: typeof __dirname === 'undefined',
                            noModule: typeof module === 'undefined',
                            // With contextIsolation off the bridge is assigned
                            // to the same global object the window sees, so
                            // this object would be the preload's own. Through
                            // contextBridge it is a proxy: its constructor
                            // belongs to the window's world, not the preload's.
                            bridgeIsProxied: window.lightTable.constructor === Object &&
                                             Object.getPrototypeOf(window.lightTable) ===
                                             Object.prototype,
                            // Node's require resolves anything on disk; the
                            // shim serves a list. Asking for a builtin Light
                            // Table does not serve tells them apart.
                            requireIsNotNodes: (function () {
                                try { require('vm'); return false; } catch (e) { return true; }
                            })(),
                            processIsNotNodes: typeof process.binding === 'undefined' &&
                                               process.mainModule === null
                        };
                    })(),
                    // Present and wired, not exercised: downloading needs the
                    // network, and a smoke test that fails when the network is
                    // down is a smoke test people learn to ignore. The real
                    // download is checked with script/lt-repl.sh.
                    download: typeof (window.lightTable.net && window.lightTable.net.download) === 'function',
                    inspect: lt.objs.console.inspect({ a: 1 }),
                    // Keyboard handling, end to end: a real key event reaching
                    // Light Table's handler through the forked Mousetrap. The
                    // fork exists precisely to route keydown/keypress/keyup
                    // differently, and nothing else here would notice if that
                    // routing broke.
                    keyboard: (function () {
                        if (typeof Mousetrap !== 'function') return 'no Mousetrap';
                        if (typeof Mousetrap.prototype.handleKeyUp !== 'function') return 'no handleKeyUp';
                        var seen = null;
                        var original = Mousetrap.prototype.handleKey;
                        Mousetrap.prototype.handleKey = function (key) {
                            seen = String(key);
                            return original.apply(this, arguments);
                        };
                        try {
                            document.dispatchEvent(new KeyboardEvent('keydown', {
                                key: 'a', code: 'KeyA', keyCode: 65, which: 65,
                                ctrlKey: true, bubbles: true
                            }));
                        } finally {
                            Mousetrap.prototype.handleKey = original;
                        }
                        return seen === null ? 'not reached' : seen;
                    })(),
                    // Paredit, built here from ClojureScript as a module of
                    // the app build. It declares no capabilities, so this also
                    // exercises the case where a manifest asserts nothing.
                    paredit: (function () {
                        var cmds = cljs.core.get.call(null, cljs.core.deref(lt.objs.command.manager),
                                                      cljs.core.keyword.call(null, 'commands'));
                        return !!lt.plugins.paredit &&
                               cljs.core.contains_QMARK_(cmds, cljs.core.keyword.call(null, 'paredit.select.parent'));
                    })(),
                    // Capability inference, against the real installed
                    // plugins. Every plugin in this repository carries a
                    // manifest now, so this is read over all of them: what a
                    // plugin declared should cover what inference says it
                    // does. Two of these manifests were written wrong on the
                    // first attempt and this is what said so.
                    capabilities: (function () {
                        var plugins = lt.objs.plugins.available_plugins();
                        var out = {};
                        ['TypeScript', 'Clojure', 'CSS', 'HTML', 'Javascript',
                         'Paredit', 'Python'].forEach(function (name) {
                            var p = cljs.core.get.call(null, plugins, name);
                            if (!p) return;
                            var r = lt.objs.plugins.capability_report(p);
                            // Sorted: a set's print order is not defined, and a
                            // check that depends on it fails for no reason.
                            var names = function (k) {
                                var v = cljs.core.get.call(null, r, cljs.core.keyword.call(null, k));
                                if (v === null || v === undefined) return 'nil';
                                return cljs.core.clj__GT_js.call(null,
                                    cljs.core.sort.call(null, cljs.core.map.call(null, cljs.core.name, v))
                                ).join(' ');
                            };
                            out[name] = { declared: names('declared'), used: names('used'),
                                          undeclared: names('undeclared') };
                        });
                        return out;
                    })(),
                    // The enforcement gate, driven directly: default mode,
                    // then refuse against the plugin that carries a manifest.
                    // TypeScript is inside its manifest, so it stays allowed —
                    // an under-declaring plugin is what refusal is for, and
                    // that case is covered by unit tests.
                    gate: (function () {
                        var dir = cljs.core.get.call(null,
                                    cljs.core.get.call(null, lt.objs.plugins.available_plugins(), 'TypeScript'),
                                    cljs.core.keyword.call(null, 'dir'));
                        var before = String(lt.objs.plugins.enforcement());
                        var allowedByDefault = lt.objs.plugins.allowed_to_load_QMARK_(dir);
                        lt.objs.plugins.set_enforcement_BANG_(cljs.core.keyword.call(null, 'refuse'));
                        var mode = String(lt.objs.plugins.enforcement());
                        var stillAllowed = lt.objs.plugins.allowed_to_load_QMARK_(dir);
                        lt.objs.plugins.set_enforcement_BANG_(cljs.core.keyword.call(null, 'warn'));
                        return { before: before, allowedByDefault: allowedByDefault,
                                 refuseMode: mode, compliantStillAllowed: stillAllowed,
                                 unknownDir: lt.objs.plugins.allowed_to_load_QMARK_('/nope') };
                    })(),
                    // The plugin require shim, which is what stands in for
                    // Node's require once contextIsolation is on. Installed
                    // now, ahead of the flip, so that a plugin needing
                    // something it does not serve fails here rather than on
                    // the day the window loses require.
                    shim: (function () {
                        var refused = null;
                        try { require('vm'); } catch (e) { refused = e.message; }
                        var nrepl = lt.plugins.clojure && lt.plugins.clojure.nrepl;
                        var jsnode = lt.plugins.js && lt.plugins.js.node;
                        return {
                            // Node's net has dozens of exports; the shim's has four.
                            servedNet: Object.keys(require('net')).sort().join(','),
                            refusedUnserved: refused,
                            bufferIsBundled: Buffer.from('hi').constructor.name !== 'Buffer',
                            processShimmed: typeof process.nextTick === 'function' &&
                                            process.mainModule === null,
                            // The Clojure plugin captures these at load, so
                            // these are what it actually got.
                            clojureNet: !!(nrepl && nrepl.net && nrepl.net.connect),
                            clojureBuffer: !!(nrepl && nrepl.Buffer && nrepl.Buffer.Buffer.concat),
                            // bencode comes from the bundle, not from disk.
                            clojureBencode: !!(nrepl && nrepl.bencode &&
                                               nrepl.bencode.decode(nrepl.encode(
                                                 cljs.core.clj__GT_js.call(null, {op: 'clone'})),
                                                 'utf-8').op === 'clone'),
                            // The Javascript plugin's harbor, a CommonJS
                            // package installed into the plugin's own
                            // node_modules rather than anything Light Table
                            // serves — so this exercises the resolver in
                            // lt.objs.plugins.local-modules, not the shim's
                            // fixed list.
                            vendoredModule: !!(jsnode && jsnode.harbor && jsnode.harbor.claim)
                        };
                    })(),
                    // The preload bridge, and that the window is actually going
                    // through it rather than still reaching Electron directly.
                    bridge: (function () {
                        var b = window.lightTable;
                        return !!(b && b.shell && b.clipboard && b.zoom && b.host);
                    })(),
                    bridgeInUse: lt.util.bridge.shell === (window.lightTable && window.lightTable.shell),
                    zoomFactor: lt.objs.app.zoom_level(),
                    // A .wasm header is the case that turned this up: read as
                    // UTF-8 the magic bytes come back as replacement
                    // characters. Any file with a high byte would do; this one
                    // is written by the harness.
                    readBytes: (function () {
                        var b = lt.util.bridge;
                        try {
                            var bytes = b.files.readFileBytesSync(${JSON.stringify(BINARY_PROBE)});
                            var text = b.files.readFileSync(${JSON.stringify(BINARY_PROBE)});
                            return {
                                length: bytes.length,
                                magic: Array.from(bytes.slice(0, 4)).join(','),
                                isBytes: bytes instanceof Uint8Array,
                                // The contrast that makes the point: the same
                                // file through the text reader loses a byte to
                                // the replacement character.
                                textIsLossy: text.indexOf('\uFFFD') !== -1,
                                textLength: text.length
                            };
                        } catch (e) { return { error: String(e && e.message || e) }; }
                    })(),
                    // Every mime the file-type table maps: does it produce a
                    // working mode?
                    //
                    // Rust did not. CodeMirror's simple-mode addon decides
                    // whether a rule's token is a function by asking it for
                    // an apply method, and extending js/String with IFn puts
                    // one on every string — so the addon called a string
                    // and threw, and opening a .rs file threw with it. Six
                    // modes are built on that addon. Nothing noticed, because
                    // counting bundled modes says 130 either way.
                    //
                    // Instantiating each one is the only check that would have
                    // caught it: a mode that registers and then throws on the
                    // first token is indistinguishable from a working one
                    // until something tokenizes.
                    modes: (function () {
                        var types = cljs.core.get.call(null, cljs.core.deref(lt.objs.files.files_obj),
                                                       cljs.core.keyword.call(null, 'types'));
                        var mimes = {};
                        cljs.core.doall.call(null, cljs.core.map.call(null, function (kv) {
                            var v = cljs.core.nth.call(null, kv, 1);
                            var mime = cljs.core.get.call(null, v, cljs.core.keyword.call(null, 'mime'));
                            var name = cljs.core.get.call(null, v, cljs.core.keyword.call(null, 'name'));
                            if (mime) mimes[mime] = String(name);
                            return null;
                        }, types));
                        var broken = [], noMode = [], ok = 0;
                        Object.keys(mimes).forEach(function (mime) {
                            var host = document.createElement('div');
                            document.body.appendChild(host);
                            try {
                                // Content that reaches a keyword, a string, a
                                // number and a comment in most languages, so a
                                // mode has to actually tokenize rather than
                                // return null for an empty document.
                                // Built rather than written as a literal: this
                                // string passes through two template literals
                                // on its way here, and each one would eat a
                                // backslash escape.
                                var probeSrc = ['let x = 42;', '"a string"',
                                                '// a comment', ''].join(String.fromCharCode(10));
                                var ed = CodeMirror(host, { value: probeSrc, mode: mime });
                                var m = ed.getMode();
                                if (!m || m.name === 'null') noMode.push(mime);
                                else ok++;
                            } catch (e) {
                                broken.push(mime + ' (' + mimes[mime] + '): ' + String(e.message).slice(0, 40));
                            }
                            host.remove();
                        });
                        return { total: Object.keys(mimes).length, ok: ok, noMode: noMode, broken: broken };
                    })(),
                    // A clipboard round trip covers both directions of the
                    // bridge: a send out and a sendSync back.
                    clipboard: (function () {
                        lt.objs.platform.copy('lt-smoke-clipboard');
                        return lt.objs.platform.paste();
                    })(),
                    workerConnected: cljs.core.boolean$(new cljs.core.Keyword(null,"connected","connected",-169833045).cljs$core$IFn$_invoke$arity$1(cljs.core.deref(lt.objs.thread.worker))),
                    workerFilesFound: (function () {
                        var files = cljs.core.get.call(null, cljs.core.deref(lt.objs.sidebar.navigate.sidebar_navigate), cljs.core.keyword.call(null, "files"));
                        return files ? cljs.core.count(files) : 0;
                    })(),
                    // What the searcher actually holds after a workspace
                    // search: the counts it displays, and the first result as
                    // the searcher stores it, so a wrong line number or a
                    // missing file name fails here rather than in a user's
                    // session.
                    search: (function () {
                        var s = cljs.core.deref(lt.objs.search.searcher);
                        var get = function (kw) { return cljs.core.get.call(null, s, kw); };
                        var results = get(cljs.core.keyword.call(null, 'results'));
                        var first = results && results.length ? results[0] : null;
                        return {
                            count: get(cljs.core.keyword.call(null, 'result-count')),
                            files: get(cljs.core.keyword.call(null, 'lt.objs.search/filesSearched')),
                            seconds: get(cljs.core.keyword.call(null, 'lt.objs.search/time')),
                            reported: results ? results.length : 0,
                            firstFile: first ? String(first.file) : '',
                            firstLine: first && first.results && first.results.length ? first.results[0].line : null,
                            firstText: first && first.results && first.results.length ? String(first.results[0].text) : '',
                            // From the searcher's own content rather than the
                            // document: its tab was never opened here, so the
                            // element is real but not attached. What matters is
                            // that ->result-item built nodes from the message.
                            rendered: (function () {
                                try {
                                    return lt.object.__GT_content(lt.objs.search.searcher)
                                        .querySelectorAll('.res .entry').length;
                                } catch (e) { return -1; }
                            })()
                        };
                    })(),
                    errors: (function () {
                        try {
                            var el = lt.object.__GT_content(lt.objs.console.console);
                            return Array.from(el.querySelectorAll('li.error')).map(function (n) { return (n.innerText || '').slice(0, 200); });
                        } catch (e) { return ['could not read the console: ' + e.message]; }
                    })()
                })\`));
                report.stdio = stdio;
                report.treesitter = treesitter;
                report.lsp = lsp;
                report.save = save;
                report.selfEval = selfEval;
                // The menubar is set by a behavior at startup, and it lands
                // over here, so this is the only side it can be seen from.
                step = 'reading the application menu';
                report.appMenu = !!Menu.getApplicationMenu();

                // The browser tab, last, so its devtools client cannot add to
                // the console errors collected above. <webview> is inert unless
                // webviewTag is set, which it was not for several years, and
                // the guest's preload only reaches the page if the guest's
                // contextIsolation is explicitly off.
                // Opened *with* a url, because navigating is the part that
                // broke: the src attribute was bound to :url, and an Electron
                // webview keeps src in step with what it has actually loaded,
                // so every assignment was overwritten and the tab sat on
                // about:blank. Nothing noticed, because a tab that opens is
                // most of what a check would look at.
                step = 'opening a browser tab';
                await w.webContents.executeJavaScript(
                    'lt.objs.command.exec_BANG_(cljs.core.keyword.call(null,"add-browser-tab"),' +
                    JSON.stringify(${JSON.stringify('file://' + PROBE_PAGE)}) + ')');
                await new Promise(function (r) { setTimeout(r, 6000); });
                Object.assign(report, JSON.parse(await w.webContents.executeJavaScript(\`(async function () {
                    var v = document.querySelector('webview');
                    return JSON.stringify({
                        webviewUpgraded: !!(v && typeof v.getWebContentsId === 'function' && typeof v.loadURL === 'function'),
                        guestInjection: v && typeof v.executeJavaScript === 'function'
                            ? await v.executeJavaScript('typeof window.lttools')
                            : 'no webview api',
                        // Did it actually go there, or is it still on about:blank?
                        browserLanded: v && typeof v.getURL === 'function' ? v.getURL() : 'no webview api',
                        browserPageText: v && typeof v.executeJavaScript === 'function'
                            ? await v.executeJavaScript('document.body.innerText.trim()')
                            : 'no webview api',
                        // The devtools client is how the browser tab evaluates.
                        // Chromium 111 began checking the Origin header on
                        // debugger WebSockets, so this failed with a 403 and
                        // three red lines in the console — visible to anyone
                        // who opened devtools, invisible to every check.
                        browserDevtools: (function () {
                            // By tag, not by context: the harness window is
                            // hidden, so nothing ever becomes the active
                            // browser and :global.browser stays unset.
                            var bs = lt.object.by_tag.call(null, cljs.core.keyword.call(null, 'browser'));
                            var b = cljs.core.first.call(null, bs);
                            if (!b) return 'no browser object';
                            var dt = cljs.core.get.call(null, cljs.core.deref(b), cljs.core.keyword.call(null, 'devtools-client'));
                            return dt ? String(cljs.core.get.call(null, cljs.core.deref(dt), cljs.core.keyword.call(null, 'connected'))) : 'no devtools client';
                        })()
                    });
                })()\`)));

                // A second window, through main.js's own createWindow rather
                // than through this harness's copy of the options — which is
                // the whole point. createWindow used to prepend __dirname to
                // the object require() caches, so the second window asked for
                // <core>/<core>/preload.js, got no bridge, and came up white.
                // Every check above passed throughout, because they all run in
                // the first window.
                step = 'opening a second window';
                const w2 = createWindow();
                const w2Errors = [];
                w2.webContents.on('console-message', function (a, b, c) {
                    const msg = (a && typeof a === 'object' && 'message' in a) ? a.message : c;
                    const lvl = (a && typeof a === 'object' && 'level' in a) ? a.level : b;
                    if (lvl === 'error' || lvl === 3) w2Errors.push(String(msg).slice(0, 200));
                });
                await new Promise(function (r) {
                    if (!w2.webContents.isLoading()) return r();
                    w2.webContents.once('did-finish-load', r);
                    setTimeout(r, 30000);
                });
                let secondWindow = 'never built';
                for (let i = 0; i < 90; i++) {
                    await new Promise(function (r) { setTimeout(r, 500); });
                    try {
                        secondWindow = await w2.webContents.executeJavaScript(
                            "(typeof lt !== 'undefined' && lt.objs && typeof lt.objs.app === 'object')" +
                            " ? 'built' : ('not yet: lt is ' + typeof lt)");
                    } catch (e) { secondWindow = 'threw: ' + e.message; }
                    if (secondWindow === 'built') break;
                }
                report.secondWindow = secondWindow;
                report.secondWindowErrors = w2Errors.slice(0, 4);
                // And the object createWindow read is still what package.json
                // said.
                //
                // Do not delete this as redundant with the check above: this
                // harness builds its own first window, so the call above is
                // createWindow's *first* and gets the path right even when the
                // prepend is destructive. Reverting the fix leaves "a second
                // window builds the editor too" passing and fails only here.
                // In the shipped app onReady calls createWindow first, so the
                // window a user opens is the second one and comes up white.
                report.preloadStillRelative =
                    packageJSON.browserWindowOptions.webPreferences.preload;
                w2.destroy();

                report.ok = true;
            } catch (e) {
                report.failure = 'renderer probe failed while ' + step + ': ' + e.message;
                report.consoleErrors = consoleErrors.slice(0, 6);
            }
            finish(0);
        }, 8000);
    });

    w.loadURL('file://' + CORE + '/LightTable.html?id=' + w.id);
});
`;

function fail(message: string, detail?: string): never {
    console.error('FAIL  ' + message);
    if (detail) console.error(detail);
    process.exit(1);
}

/**
 * Whether something already holds the debugging port.
 *
 * A second Electron cannot bind it, so the app boots without a debugging
 * endpoint and the browser tab's devtools client never connects — which
 * surfaces as one baffling check failure rather than as the port conflict it
 * is. script/lt-repl.sh keeps an editor running on the same port, so this is
 * the normal way to hit it.
 */
function portInUse(port: number): Promise<boolean> {
    return new Promise(function (resolve) {
        const socket = require('net').connect({ port: port, host: '127.0.0.1' });
        socket.on('connect', function () { socket.destroy(); resolve(true); });
        socket.on('error', function () { resolve(false); });
        setTimeout(function () { socket.destroy(); resolve(false); }, 1000);
    });
}

async function main(): Promise<void> {
    if (await portInUse(8315)) {
        fail('something is already listening on port 8315',
             'That is the debugging port this test needs. If you have an editor open from ' +
             'script/lt-repl.sh, run `script/lt-repl.sh stop` first.');
    }
    for (const [what, where] of [['Electron', ELECTRON],
                                 ['the compiled bundle', path.join(CORE, 'lighttable', 'bootstrap.js')],
                                 ['the compiled main process', path.join(CORE, 'main.js')],
                                 ['the compiled preload', path.join(CORE, 'preload.js')],
                                 ['the compiled browser injection', path.join(CORE, 'browserInjection.js')]]) {
        if (!fs.existsSync(where)) fail(what + ' is missing at ' + where, 'Run script/build.sh first.');
    }

    // Every plugin is in this repository now and script/place-plugins.js puts
    // it here, so the checks below that used to be gated on "were the
    // published flagships cloned?" are unconditional. That gate existed
    // because CI did not clone them and build.sh did, which meant the shim,
    // the local-module loader and the capability report were checked on a
    // developer's machine and not in CI — precisely backwards.
    //
    // A missing directory is now a build that did not run rather than an
    // environment difference, and it is worth saying so plainly.
    const pluginDir = path.join(ROOT, 'deploy', 'plugins');
    if (!fs.existsSync(pluginDir)) {
        fail('deploy/plugins does not exist',
             'Plugins are built from source in this repository. Run `npm run build:plugins`.');
    }
    const installed = fs.readdirSync(pluginDir);
    console.log('plugins present: ' + installed.join(', '));
    for (const name of ['Clojure', 'CSS', 'HTML', 'Javascript', 'Paredit', 'Python', 'TypeScript']) {
        if (!installed.includes(name)) {
            fail('the ' + name + ' plugin was not placed',
                 'plugins/' + name + ' is in this repository. Run `npm run build:plugins`.');
        }
    }

    const appDir = fs.mkdtempSync(path.join(os.tmpdir(), 'lt-smoke-'));
    const reportPath = path.join(appDir, 'report.json');
    // browserWindowOptions too, because main.js's own createWindow reads them
    // from the app directory's package.json — and the second-window check
    // calls that rather than building options the way this harness does.
    fs.writeFileSync(path.join(appDir, 'package.json'), JSON.stringify({
        name: 'lt-smoke', version: '1.0.0', main: 'main.js',
        browserWindowOptions: require(path.join(CORE, 'package.json')).browserWindowOptions
    }));
    fs.writeFileSync(path.join(appDir, 'main.js'),
        fs.readFileSync(path.join(CORE, 'main.js'), 'utf8').replace(/^start\(\);$/m, '') + HARNESS);
    // Everything else in core, symlinked, so that __dirname there behaves like
    // the shipped application directory: main.js resolves the preload, the
    // html and the plugins against it, and the second-window check calls the
    // real createWindow rather than reimplementing what it does.
    for (const entry of fs.readdirSync(CORE)) {
        if (entry === 'main.js' || entry === 'package.json') continue;
        fs.symlinkSync(path.join(CORE, entry), path.join(appDir, entry));
    }
    // That loop already covers browserInjection.js, which main.js pins to the
    // webview guest relative to its own directory.

    // A home directory of its own. Settings, the workspace, the session and
    // every cache live under LT_USER_DIR, so without this the smoke test runs
    // against whatever the developer left in ~/.lighttable — and once Light
    // Table started restoring sessions, that meant it reopened their files and
    // started language servers for their projects before a single check ran.
    const smokeHome = fs.mkdtempSync(path.join(os.tmpdir(), 'lt-smoke-home-'));

    await new Promise(function (resolve) {
        const child = spawn(ELECTRON, [appDir, '--no-sandbox'],
            { env: Object.assign({}, process.env, {
                LT_SMOKE_REPORT: reportPath,
                LT_USER_DIR: smokeHome
              }), stdio: 'ignore' });
        child.on('exit', resolve);
    });

    if (!fs.existsSync(reportPath)) fail('the app never reported back', 'It most likely failed before the window finished loading.');
    const r = JSON.parse(fs.readFileSync(reportPath, 'utf8'));
    if (!r.ok) fail(r.failure || 'the app did not report success', JSON.stringify(r, null, 1));

    // Which capture classes the parser emitted that treesitter.css says nothing
    // about. A capture with no rule renders as body text — invisible in exactly
    // the way numbers were before this — so the stylesheet has to keep up with
    // the grammars, and this is what says when it has not.
    const themeCss = fs.readFileSync(path.join(CORE, 'css', 'treesitter.css'), 'utf8');
    const unstyledCaptures = (r.treesitter.classes || []).filter(
        (c: string) => !new RegExp('\\.' + c + '\\s*[,{]').test(themeCss));

    // The fixture server states what it has been told in its first diagnostic,
    // so this one string carries the whole synchronisation answer.
    const lsp = r.lsp || {};
    const lspFirst = (lsp.after && lsp.after.messages && lsp.after.messages[0]) || '';

    const checks = [
        ['lt.objs.app initialized', r.appInitialized === true],
        ['platform resolved over ipc', r.platform === ':linux' || r.platform === ':mac' || r.platform === ':windows'],
        ['app path resolved over ipc', typeof r.dataPath === 'string' && r.dataPath.length > 0 && r.dataPath !== 'undefined'],
        ['window number resolved over ipc', r.windowNumber !== 'undefined' && r.windowNumber !== 'null'],
        ['CodeMirror loaded', r.codeMirror === true],
        ['forked CodeMirror addons loaded', r.codeMirrorAddons === true],
        ['a file opened into an editor', r.editors >= 1],
        ['the editor rendered its contents', typeof r.editorText === 'string' && r.editorText.indexOf('ns lt.objs.platform') !== -1],
        ['behaviors registered', r.behaviors > 400],
        ['language modes registered', r.codeMirrorModes > 50],
        ['fold addons registered', r.codeMirrorFold === true],
        ['the worker thread connected', r.workerConnected === true],
        ['a background job round-tripped', r.workerFilesFound >= 5],
        // Four matching lines across three files, out of four files searched.
        // Asserted exactly: "greater than zero" would have passed for a search
        // that found the wrong things, and "not undefined" is the bug this
        // check exists for.
        ['the workspace searcher found every match', r.search.count === 4],
        ['it reported one message per matching file', r.search.reported === 3],
        ['it counted the files it searched', r.search.files === 4],
        ['it timed the search', typeof r.search.seconds === 'number' && r.search.seconds >= 0],
        ['a result carries a file, a 1-based line and its text',
            /one\.txt$/.test(r.search.firstFile) && r.search.firstLine === 2 &&
            r.search.firstText === 'SMOKENEEDLE here'],
        ['the matches were rendered into the results list', r.search.rendered === 4],
        // plaintext is the one deliberate no-mode: it exists so a file can be
        // opened with no highlighting at all. Zig and Elixir share it — they
        // have a language server and no CodeMirror mode, so they are readable
        // and not coloured until a grammar covers them.
        ['every mapped file type has a mode that tokenizes', r.modes.broken.length === 0],
        ['only plaintext resolves to no mode',
            r.modes.noMode.length === 1 && r.modes.noMode[0] === 'plaintext'],
        ['the file-type table still covers what it used to', r.modes.total >= 100],
        // Tree-sitter highlighting. The capture names are the assertion: a
        // CodeMirror mode emits seven token types for TypeScript and cannot
        // tell a type from a value, so these names existing at all means the
        // parse is what is driving the colours.
        ['tree-sitter highlighting is active for TypeScript',
            r.treesitter.report && r.treesitter.report.active === true &&
            r.treesitter.report.grammar === 'tree-sitter-typescript'],
        ['it distinguishes types, which a per-line mode cannot',
            r.treesitter.classes.includes('cm-ts-type') &&
            r.treesitter.classes.includes('cm-ts-type-builtin')],
        ['it distinguishes parameters from locals',
            r.treesitter.classes.includes('cm-ts-variable-parameter')],
        ['capture names cascade, so a theme can be broad or precise',
            r.treesitter.classes.includes('cm-ts-punctuation') &&
            r.treesitter.classes.includes('cm-ts-punctuation-bracket')],
        ['it distinguishes far more than a CodeMirror mode managed',
            r.treesitter.classes.length >= 12],
        // A capture the parser emits and the stylesheet never heard of renders
        // as body text, which is the bug this whole change exists to fix.
        ['every capture it emits has a rule in treesitter.css', unstyledCaptures.length === 0],
        // The capabilities added for language servers and for WebAssembly.
        // Twelve bytes for four three-byte characters, decoded back to what
        // was written: that is stdin working, stdout working, and no character
        // mangled by a chunk boundary.
        ['a process can be written to', r.stdio.wrote === true && !r.stdio.error],
        ['its stdout can be read as bytes', r.stdio.isBytes === true && r.stdio.bytesLen === 12],
        ['multi-byte characters survive the chunk boundary',
            r.stdio.decoded === '\u4f60\u597d\u4e16\u754c'],
        ['a file can be read as bytes, not decoded text',
            r.readBytes.isBytes === true && r.readBytes.length === 9 &&
            r.readBytes.magic === '0,97,115,109'],
        ['and reading the same file as text is visibly lossy', r.readBytes.textIsLossy === true],
        ['the crate compatibility shim is published', r.crateShim === true],
        ['the default user plugin loaded', r.userPlugin === true],
        ['the TypeScript plugin loaded and registered its commands', r.tsPlugin === true],
        ['Paredit, built here from ClojureScript, loaded', r.paredit === true],
        ['a key event reaches Light Table through the forked Mousetrap', r.keyboard === 'a'],
        ['workspace watching works through the bridge handle', r.watching === 'watched true'],
        ['downloading is a bridge capability', r.download === true],
        ['both client servers are listening through the bridge', r.servers === 'true,true,true,true'],
        ['the console log is a path and inspect still formats', r.consoleLog === true && r.inspect === '{ a: 1 }'],
        ['lt.util.load reaches the filesystem through the bridge',
         r.loadViaBridge === 'true,true,true,true,true'],
        ['the window TypeScript modules are required, not evaluated',
         r.windowModules === 'true,true,true,true,true,true,true'],
        ['the enforcement gate defaults to warn and persists a change',
         !!r.gate && r.gate.before === ':warn' && r.gate.refuseMode === ':refuse'],
        ['a plugin inside its manifest loads even when refusing',
         !!r.gate && r.gate.allowedByDefault === true && r.gate.compliantStillAllowed === true &&
         r.gate.unknownDir === true],
        ['contextIsolation is on and the window has no Node of its own',
         !!r.isolated && r.isolated.noDirname === true && r.isolated.noModule === true &&
         r.isolated.bridgeIsProxied === true && r.isolated.requireIsNotNodes === true &&
         r.isolated.processIsNotNodes === true],
        ['require is the shim, serving a fixed list and refusing the rest',
         !!r.shim && r.shim.servedNet === 'Server,connect,createConnection,createServer' &&
         /neither one Light Table serves/.test(r.shim.refusedUnserved || '')],
        ['the Node globals a plugin expects come from the bundle',
         !!r.shim && r.shim.bufferIsBundled === true && r.shim.processShimmed === true],
        // The two the shim exists for: the Clojure plugin's nREPL client wants
        // net, Buffer and bencode, and the Javascript plugin loads acorn out
        // of its own node_modules through the CommonJS loader.
        ['the Clojure plugin got its net, Buffer and bencode from the shim',
         !!r.shim && r.shim.clojureNet === true &&
         r.shim.clojureBuffer === true &&
         r.shim.clojureBencode === true],
        ['a plugin loads the CommonJS package it depends on',
         !!r.shim && r.shim.vendoredModule === true],
        ['capability inference matches the declared manifest',
         !!r.capabilities && !!r.capabilities.TypeScript &&
         r.capabilities.TypeScript.declared === 'files processes' &&
         r.capabilities.TypeScript.used === 'files processes' &&
         r.capabilities.TypeScript.undeclared === ''],
        // Every plugin in this repository declares one now, so the interesting
        // question is no longer "can inference read a plugin that declared
        // nothing" but "does what a plugin declared cover what it does".
        // :undeclared empty across all of them is the whole point of the
        // manifest, and it is a real answer only because inference reads the
        // JavaScript the plugin actually loads.
        ['no in-tree plugin uses a capability it did not declare',
         !!r.capabilities &&
         Object.keys(r.capabilities).every((n) => r.capabilities[n].undeclared === '')],
        ['the Clojure plugin declares the process it spawns',
         !!r.capabilities.Clojure &&
         r.capabilities.Clojure.declared.indexOf('processes') !== -1 &&
         r.capabilities.Clojure.used.indexOf('processes') !== -1],
        ['the preload bridge is exposed', r.bridge === true],
        ['the window reaches the desktop through the bridge', r.bridgeInUse === true],
        ['zoom is served by the bridge', typeof r.zoomFactor === 'number' && r.zoomFactor > 0],
        ['the clipboard round-trips over the bridge', r.clipboard === 'lt-smoke-clipboard'],
        ['the application menu was built over the bridge', r.appMenu === true],
        ['the browser tab has a real webview', r.webviewUpgraded === true],
        ['the browser injection reached the guest page', r.guestInjection === 'object'],
        ['the browser tab can evaluate through the devtools protocol',
         r.browserDevtools === 'true'],
        ['the browser tab navigates where it was told',
         typeof r.browserLanded === 'string' && r.browserLanded.startsWith('file://') &&
         r.browserPageText === 'lt-browser-probe-loaded'],
        ['bundled plugins loaded', r.behaviors > 500],
        ['a save reaches the disk', !!r.save && r.save.onDisk === 'after\nbefore\n'],
        ['and the tab stops saying it is dirty', !!r.save && r.save.dirty === false],
        // The editor changing itself. Each of these fails on its own for a
        // different reason: the first if the analysis cache is missing or the
        // loader cannot read it, the second if the bundle stopped exposing
        // lt.* as globals, the third if forms stopped being split per form.
        ['the ClojureScript compiler starts in the window',
         !!r.selfEval && /:status :ready/.test(r.selfEval.compiler || '')],
        ['evaluating a buffer defines a command that did not exist',
         !!r.selfEval && r.selfEval.defined === true],
        ['through the client that is this window',
         !!r.selfEval && r.selfEval.client === 'LightTable-UI'],
        ['with a result beside each top-level form',
         !!r.selfEval && r.selfEval.results === '["nil" "42" "nil"]'],
        // The language server spine. `before` is after didOpen, `after` is
        // after one keystroke.
        ['a language server starts for a project that provides one',
         !!lsp.before && lsp.before.connected === true],
        // The TypeScript plugin declares typescript-language-server for this
        // tag in plugins/TypeScript/typescript.behaviors; the harness declared
        // fake-language-server after it, the way user.behaviors would. Later
        // wins, which is the rule lt.objs.editor.lsp.registry states and the
        // reason a server can be pointed somewhere else without editing source.
        ['a later declaration beats the plugin that came before it',
         // `node`, because the fixture is a TypeScript file run by it. What
         // matters is that it is not typescript-language-server, which the
         // TypeScript plugin declared for this tag first.
         !!lsp.before && lsp.before.command === 'node' &&
         lsp.before.declared >= 3],
        ['its file is addressed as a uri', !!lsp.before &&
         String(lsp.before.uri || '').startsWith('file:///')],
        ['diagnostics are drawn inline, grouped by line',
         !!lsp.before && lsp.before.widgets === 4 && lsp.before.messages.length === 6],
        ['severities reach the markup',
         !!lsp.before && /^error: /.test(lsp.before.messages[0] || '') &&
         /^warning: /.test(lsp.before.messages[1] || '') &&
         /^info: /.test(lsp.before.messages[2] || '')],
        // Everything above passes when didOpen works and didChange does not,
        // which is exactly the state two separate bugs produced. These are the
        // ones that noticed.
        ['a keystroke reaches the server', !!lsp.after && /changes=1 /.test(lspFirst)],
        ['as the incremental change the server asked for, not the whole file',
         !!lsp.after && /last="X"/.test(lspFirst)],
        ['carrying the version the document is now at',
         !!lsp.before && !!lsp.after &&
         lsp.after.version === lsp.before.version + 1 && /version=2 /.test(lspFirst)],
        ['and redrawing rather than accumulating',
         !!lsp.after && lsp.after.widgets === 4 && lsp.after.messages.length === 6],
        ['diagnostics are kept, not only drawn',
         !!lsp.before && lsp.before.diagnosticsKept === 6],
        // Two servers for one language, which is what a modern setup is: a
        // type checker and a linter. Both are started, and — the part that is
        // easy to get wrong — publishDiagnostics replaces what *that* server
        // said rather than everything on screen.
        ['both servers declared for the language are running',
         !!lsp.before && lsp.before.connections === 2],
        ['and neither one publishing erases the other',
         !!lsp.before && lsp.before.sources.indexOf('fake') !== -1 &&
         lsp.before.sources.indexOf('linter') !== -1],
        ['still, after a keystroke reaches both of them',
         !!lsp.after && lsp.after.sources.indexOf('linter') !== -1],
        // The linter advertises nothing but synchronisation, so formatting and
        // code actions have to be routed past it to the server that offers
        // them — which is what makes biome-beside-vtsls work without either
        // being told about the other. The FORMATTED and FIXED checks below are
        // that routing arriving somewhere.
        ['a surface goes to the server that says it can answer',
         !!lsp.before && lsp.before.formattable === true &&
         lsp.before.actionable === true],
        ['an editor is tagged actionable when its server offers code actions',
         !!lsp.before && lsp.before.actionable === true],
        ['a choice of actions is offered rather than one applied',
         lsp.actions && lsp.actions.offered === '2 offered'],
        // The fixture's first action edits the file, and writes into the edit
        // how many diagnostics the client sent with the request — so this
        // says the context went out, which is what a real server needs to
        // have anything to fix.
        ['choosing one applies its workspace edit',
         !!lsp.afterAction && /^FIXED /.test(lsp.afterAction.firstLine || '')],
        ['carrying the diagnostics the fix is for',
         !!lsp.afterAction && /withDiagnostics=[1-9]/.test(lsp.afterAction.firstLine || '')],
        // Choosing an action closes the popup. Left open it would sit in
        // front of every later step, and on a headed run it reads as a hang.
        ['and choosing one closes the popup',
         !!lsp.dismissed && lsp.dismissed.closed === 0 && lsp.dismissed.stillOpen === false],
        // Formatting. The fixture replaces the first line with a fixed string
        // that echoes the options back, so this says both that the edit was
        // applied and that the client sent what the editor is configured with
        // rather than a guess.
        ['an editor is tagged formattable when its server formats',
         !!lsp.formatted && lsp.formatted.formattable === true],
        ['and formatting applies the edit to the buffer',
         !!lsp.formatted && /^FORMATTED /.test(lsp.formatted.firstLine || '')],
        ['carrying this editor\'s own indent settings',
         !!lsp.formatted && /tabSize=\d+ insertSpaces=(true|false)$/.test(lsp.formatted.firstLine || '')],
        ['a second window builds the editor too', r.secondWindow === 'built'],
        ['and creating one leaves the shared window options alone',
         r.preloadStillRelative === 'preload.js'],
        ['nothing logged to the console', Array.isArray(r.errors) && r.errors.length === 0]
    ];

    let failed = 0;
    for (const [name, passed] of checks) {
        console.log((passed ? 'ok    ' : 'FAIL  ') + name);
        if (!passed) failed++;
    }
    // Still present, because nodeIntegration is on for plugins. Light Table's
    // own code no longer touches any of it, so this is what changes on the day
    // contextIsolation is turned on.
    console.log('\nwindow globals (require, process, SCRIPT_DIR, module): ' + r.noNodeInTheWindow);
    console.log('browser devtools client: ' + r.browserDevtools);
    console.log('capability reports: ' + JSON.stringify(r.capabilities));
    console.log('behaviors registered: ' + r.behaviors);
    console.log('CodeMirror modes registered: ' + r.codeMirrorModes);
    console.log('worker connected: ' + r.workerConnected + ', files found by background scan: ' + r.workerFilesFound);
    console.log('workspace search: ' + r.search.count + ' results in ' + r.search.reported +
                ' files, ' + r.search.files + ' searched, ' + r.search.seconds + 's');
    console.log('file types: ' + r.modes.total + ' mimes, ' + r.modes.ok + ' tokenizing, ' +
                r.modes.broken.length + ' broken' +
                (r.modes.broken.length ? ': ' + r.modes.broken.join('; ') : ''));
    console.log('tree-sitter: ' + r.treesitter.classes.length + ' capture classes (' +
                (r.treesitter.report ? r.treesitter.report.grammar : '?') + ')' +
                (r.treesitter.error ? ' — ' + r.treesitter.error : '') +
                (unstyledCaptures.length ? ' UNSTYLED: ' + unstyledCaptures.join(', ') : ''));
    console.log('process stdio: ' + r.stdio.bytesLen + ' bytes back, decoded "' + r.stdio.decoded +
                '"; file bytes: [' + r.readBytes.magic + ']');
    console.log('save: on disk ' + JSON.stringify((r.save || {}).onDisk) +
                ', dirty ' + (r.save || {}).dirty);
    console.log('second window: ' + r.secondWindow + ', shared preload option still ' +
                JSON.stringify(r.preloadStillRelative));
    console.log('self-eval: ' + ((r.selfEval && r.selfEval.compiler) || 'no report') +
                ', results ' + ((r.selfEval && r.selfEval.results) || '-'));
    console.log('code actions: ' + ((lsp.actions && lsp.actions.offered) || 'no report') +
                ', applied ' + ((lsp.afterAction && lsp.afterAction.firstLine) || '-'));
    console.log('formatting: ' + ((lsp.formatted && lsp.formatted.firstLine) || 'no report'));
    console.log('language server: ' + (lsp.after ? lsp.after.widgets + ' widgets, ' +
                lsp.after.messages.length + ' diagnostics, said "' + lspFirst + '"'
                : 'no report' + (lsp.before && lsp.before.error ? ' — ' + lsp.before.error : '')));
    if (r.errors && r.errors.length) {
        console.error('\nErrors reported by Light Table:');
        r.errors.forEach(function (e: string) { console.error('  - ' + e.split('\n')[0]); });
    }
    if (failed) fail(failed + ' of ' + checks.length + ' checks failed');
    console.log('\nAll ' + checks.length + ' checks passed.');
}

main().catch(function (e: Error) { fail('smoke test crashed', e.stack); });
