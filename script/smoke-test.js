#!/usr/bin/env node
/*jshint esversion: 8 */
"use strict";

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

const path = require('path');
const { spawn } = require('child_process');
const fs = require('fs');
const os = require('os');

const ROOT = path.join(__dirname, '..');
const CORE = path.join(ROOT, 'deploy', 'core');
const ELECTRON = path.join(ROOT, 'deploy', 'electron', 'node_modules', 'electron', 'dist', 'electron');
const SAMPLE = path.join(ROOT, 'src', 'lt', 'objs', 'platform.cljs');
// Arguments for the background scan the smoke test uses to prove the worker
// round trip. Note that walkdir's pattern is an exclusion, matching how
// lt.objs.sidebar.navigate passes files/ignore-pattern through: this skips
// dotfiles and collects everything else under a directory with several files.
const SCAN_ARGS = JSON.stringify({
    lim: 200,
    pattern: '^\\.',
    ws: { folders: [path.join(ROOT, 'src', 'lt', 'background')], files: [] }
});

// The harness reuses the real main.js so that ipc handlers, command line
// parsing and window options are the ones that ship, not a copy that can drift.
const HARNESS = `
const CORE = ${JSON.stringify(CORE)};
const SCAN_ARGS = ${JSON.stringify(SCAN_ARGS)};
const REPORT = process.env.LT_SMOKE_REPORT;
const fsx = require('fs');
let report = { ok: false };
function finish(code) { try { fsx.writeFileSync(REPORT, JSON.stringify(report, null, 1)); } catch (e) {} app.exit(code); }

global.browserOpenFiles = [];
global.browserParsedArgs = { _: [] };
app.commandLine.appendSwitch('remote-debugging-port', '8315');

setTimeout(function () { report.failure = 'timed out before the window reported back'; finish(1); }, 90000);
process.on('uncaughtException', function (e) { report.failure = 'main process threw: ' + e.message; finish(1); });

app.on('ready', function () {
    registerRendererApi();
    const pkg = require(CORE + '/package.json');
    const opts = Object.assign({}, pkg.browserWindowOptions, { show: false, width: 1280, height: 820 });
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
                        return !!(lt.plugins['hello-ts']) &&
                               cljs.core.contains_QMARK_(cmds, cljs.core.keyword.call(null, 'hello-ts.say-hello')) &&
                               cljs.core.contains_QMARK_(cmds, cljs.core.keyword.call(null, 'hello-ts.copy-greeting'));
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
                    // Capability inference, against the real installed plugins.
                    // HelloTS is the interesting case: it is the only plugin
                    // carrying a manifest, so what it declared and what it uses
                    // should agree.
                    capabilities: (function () {
                        var plugins = lt.objs.plugins.available_plugins();
                        var out = {};
                        ['HelloTS', 'Clojure'].forEach(function (name) {
                            var p = cljs.core.get.call(null, plugins, name);
                            if (!p) return;
                            var r = lt.objs.plugins.capability_report(p);
                            out[name] = {
                                declared: cljs.core.pr_str(cljs.core.get.call(null, r, cljs.core.keyword.call(null,'declared'))),
                                used: cljs.core.pr_str(cljs.core.get.call(null, r, cljs.core.keyword.call(null,'used'))),
                                undeclared: cljs.core.pr_str(cljs.core.get.call(null, r, cljs.core.keyword.call(null,'undeclared')))
                            };
                        });
                        return out;
                    })(),
                    // The enforcement gate, driven directly: default mode,
                    // then refuse against the plugin that carries a manifest.
                    // HelloTS is inside its manifest, so it stays allowed —
                    // an under-declaring plugin is what refusal is for, and
                    // that case is covered by unit tests.
                    gate: (function () {
                        var dir = cljs.core.get.call(null,
                                    cljs.core.get.call(null, lt.objs.plugins.available_plugins(), 'HelloTS'),
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
                            // The Javascript plugin's vendored harbor, which is
                            // a CommonJS package inside the plugin rather than
                            // anything Light Table serves.
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
                    errors: (function () {
                        try {
                            var el = lt.object.__GT_content(lt.objs.console.console);
                            return Array.from(el.querySelectorAll('li.error')).map(function (n) { return (n.innerText || '').slice(0, 200); });
                        } catch (e) { return ['could not read the console: ' + e.message]; }
                    })()
                })\`));
                // The menubar is set by a behavior at startup, and it lands
                // over here, so this is the only side it can be seen from.
                step = 'reading the application menu';
                report.appMenu = !!Menu.getApplicationMenu();

                // The browser tab, last, so its devtools client cannot add to
                // the console errors collected above. <webview> is inert unless
                // webviewTag is set, which it was not for several years, and
                // the guest's preload only reaches the page if the guest's
                // contextIsolation is explicitly off.
                step = 'opening a browser tab';
                await w.webContents.executeJavaScript(
                    'lt.objs.command.exec_BANG_(cljs.core.keyword.call(null,"add-browser-tab"))');
                await new Promise(function (r) { setTimeout(r, 6000); });
                Object.assign(report, JSON.parse(await w.webContents.executeJavaScript(\`(async function () {
                    var v = document.querySelector('webview');
                    return JSON.stringify({
                        webviewUpgraded: !!(v && typeof v.getWebContentsId === 'function' && typeof v.loadURL === 'function'),
                        guestInjection: v && typeof v.executeJavaScript === 'function'
                            ? await v.executeJavaScript('typeof window.lttools')
                            : 'no webview api'
                    });
                })()\`)));
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

function fail(message, detail) {
    console.error('FAIL  ' + message);
    if (detail) console.error(detail);
    process.exit(1);
}

async function main() {
    for (const [what, where] of [['Electron', ELECTRON],
                                 ['the compiled bundle', path.join(CORE, 'lighttable', 'bootstrap.js')],
                                 ['the compiled main process', path.join(CORE, 'main.js')],
                                 ['the compiled preload', path.join(CORE, 'preload.js')],
                                 ['the compiled browser injection', path.join(CORE, 'browserInjection.js')]]) {
        if (!fs.existsSync(where)) fail(what + ' is missing at ' + where, 'Run script/build.sh first.');
    }

    // Plugins are cloned by build.sh. Without the directory the plugin loader
    // reports an error that has nothing to do with what is being tested.
    //
    // In-tree plugins are placed here too, so presence of the directory no
    // longer means the published ones were fetched — the behavior count check
    // below only makes sense when the flagships are there, so look for them by
    // name rather than counting entries.
    const pluginDir = path.join(ROOT, 'deploy', 'plugins');
    const madePluginDir = !fs.existsSync(pluginDir);
    const installed = madePluginDir ? [] : fs.readdirSync(pluginDir);
    const pluginsPresent = installed.includes('Clojure') && installed.includes('Javascript');
    if (installed.length) console.log('plugins present: ' + installed.join(', '));
    if (madePluginDir) fs.mkdirSync(pluginDir, { recursive: true });

    const appDir = fs.mkdtempSync(path.join(os.tmpdir(), 'lt-smoke-'));
    const reportPath = path.join(appDir, 'report.json');
    fs.writeFileSync(path.join(appDir, 'package.json'), JSON.stringify({ name: 'lt-smoke', version: '1.0.0', main: 'main.js' }));
    fs.writeFileSync(path.join(appDir, 'main.js'),
        fs.readFileSync(path.join(CORE, 'main.js'), 'utf8').replace(/^start\(\);$/m, '') + HARNESS);
    // main.js requires yargs-free node builtins only, but the harness resolves
    // the core package.json, so give it the same module paths.
    fs.symlinkSync(path.join(CORE, 'node_modules'), path.join(appDir, 'node_modules'));
    // main.js pins the webview guest's preload relative to its own directory,
    // so the temporary app directory has to carry it too.
    fs.symlinkSync(path.join(CORE, 'browserInjection.js'), path.join(appDir, 'browserInjection.js'));

    await new Promise(function (resolve) {
        const child = spawn(ELECTRON, [appDir, '--no-sandbox'],
            { env: Object.assign({}, process.env, { LT_SMOKE_REPORT: reportPath }), stdio: 'ignore' });
        child.on('exit', resolve);
    });

    if (madePluginDir) { try { fs.rmSync(pluginDir, { recursive: true }); } catch (e) {} }

    if (!fs.existsSync(reportPath)) fail('the app never reported back', 'It most likely failed before the window finished loading.');
    const r = JSON.parse(fs.readFileSync(reportPath, 'utf8'));
    r.pluginsPresent = pluginsPresent;
    if (!r.ok) fail(r.failure || 'the app did not report success', JSON.stringify(r, null, 1));

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
        ['the Clojure plugin got its net, Buffer and bencode from the shim',
         !!r.shim && r.shim.clojureNet === true && r.shim.clojureBuffer === true &&
         r.shim.clojureBencode === true],
        ['a plugin loads the CommonJS package it vendored',
         !!r.shim && r.shim.vendoredModule === true],
        ['capability inference matches the one declared manifest',
         !!r.capabilities && !!r.capabilities.HelloTS &&
         r.capabilities.HelloTS.declared === '#{:clipboard}' &&
         r.capabilities.HelloTS.used === '#{:clipboard}' &&
         r.capabilities.HelloTS.undeclared === '#{}'],
        // Only meaningful when the flagships were cloned.
        ['capability inference reads an unmanifested plugin',
         !r.pluginsPresent || (!!r.capabilities.Clojure &&
                               r.capabilities.Clojure.declared === 'nil' &&
                               r.capabilities.Clojure.used.indexOf(':processes') !== -1)],
        ['the preload bridge is exposed', r.bridge === true],
        ['the window reaches the desktop through the bridge', r.bridgeInUse === true],
        ['zoom is served by the bridge', typeof r.zoomFactor === 'number' && r.zoomFactor > 0],
        ['the clipboard round-trips over the bridge', r.clipboard === 'lt-smoke-clipboard'],
        ['the application menu was built over the bridge', r.appMenu === true],
        ['the browser tab has a real webview', r.webviewUpgraded === true],
        ['the browser injection reached the guest page', r.guestInjection === 'object'],
        // Only meaningful when the published flagships were cloned, which
        // build.sh does and CI does not.
        ['bundled plugins loaded', !r.pluginsPresent || r.behaviors > 500],
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
    console.log('\nwindow globals (require, process, __dirname, module): ' + r.noNodeInTheWindow);
    console.log('capability reports: ' + JSON.stringify(r.capabilities));
    console.log('behaviors registered: ' + r.behaviors);
    console.log('CodeMirror modes registered: ' + r.codeMirrorModes);
    console.log('worker connected: ' + r.workerConnected + ', files found by background scan: ' + r.workerFilesFound);
    if (r.errors && r.errors.length) {
        console.error('\nErrors reported by Light Table:');
        r.errors.forEach(function (e) { console.error('  - ' + e.split('\n')[0]); });
    }
    if (failed) fail(failed + ' of ' + checks.length + ' checks failed');
    console.log('\nAll ' + checks.length + ' checks passed.');
}

main().catch(function (e) { fail('smoke test crashed', e.stack); });
