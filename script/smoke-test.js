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

    w.webContents.on('did-finish-load', function () {
        setTimeout(async function () {
            try {
                await w.webContents.executeJavaScript(
                    'lt.objs.command.exec_BANG_(cljs.core.keyword.call(null,"open-path"),' + JSON.stringify(${JSON.stringify(SAMPLE)}) + ')');
                await new Promise(function (r) { setTimeout(r, 5000); });

                // Exercise a background job end to end. The worker runs compiled
                // code now rather than source shipped across, so a round trip
                // proves the whole path: fork, init, dispatch, and reply. The
                // navigate scan is used because an object already exists to
                // receive its result.
                await w.webContents.executeJavaScript(
                    'lt.objs.sidebar.navigate.populate_bg.call(null,' +
                    ' lt.objs.sidebar.navigate.sidebar_navigate,' +
                    ' cljs.core.js__GT_clj.call(null, JSON.parse(' + JSON.stringify(SCAN_ARGS) + '),' +
                    ' cljs.core.keyword.call(null,"keywordize-keys"), true))');
                await new Promise(function (r) { setTimeout(r, 4000); });
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
                report.appMenu = !!Menu.getApplicationMenu();

                // The browser tab, last, so its devtools client cannot add to
                // the console errors collected above. <webview> is inert unless
                // webviewTag is set, which it was not for several years, and
                // the guest's preload only reaches the page if the guest's
                // contextIsolation is explicitly off.
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
                report.failure = 'renderer probe failed: ' + e.message;
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
    const pluginDir = path.join(ROOT, 'deploy', 'plugins');
    const madePluginDir = !fs.existsSync(pluginDir);
    const pluginsPresent = !madePluginDir && fs.readdirSync(pluginDir).length > 0;
    if (pluginsPresent) console.log('plugins present: ' + fs.readdirSync(pluginDir).join(', '));
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
        ['the preload bridge is exposed', r.bridge === true],
        ['the window reaches the desktop through the bridge', r.bridgeInUse === true],
        ['zoom is served by the bridge', typeof r.zoomFactor === 'number' && r.zoomFactor > 0],
        ['the clipboard round-trips over the bridge', r.clipboard === 'lt-smoke-clipboard'],
        ['the application menu was built over the bridge', r.appMenu === true],
        ['the browser tab has a real webview', r.webviewUpgraded === true],
        ['the browser injection reached the guest page', r.guestInjection === 'object'],
        // Only meaningful when deploy/plugins is populated, which build.sh does.
        ['bundled plugins loaded', !r.pluginsPresent || r.behaviors > 500],
        ['nothing logged to the console', Array.isArray(r.errors) && r.errors.length === 0]
    ];

    let failed = 0;
    for (const [name, passed] of checks) {
        console.log((passed ? 'ok    ' : 'FAIL  ') + name);
        if (!passed) failed++;
    }
    console.log('\nbehaviors registered: ' + r.behaviors);
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
