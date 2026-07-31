#!/usr/bin/env node

// Boots Light Table, opens files, and writes a PNG of the window per file.
//
// The smoke test can tell you an editor exists and holds the right text. It
// cannot tell you the result looks right — that a mode actually highlighted, or
// that the theme still applies. This is for the questions a person has to
// answer by looking.
//
// Run with:  script/screenshot.sh <file> [file ...]
// (it needs a display; the wrapper supplies one via xvfb when there isn't one)
//
// PNGs are written to builds/screenshots/, named after each file.

import * as path from 'node:path';
import { spawn } from 'node:child_process';
import * as fs from 'node:fs';
import * as os from 'node:os';
import { ROOT, CORE, electronBinary } from './lib/paths.mts';
// The electron package reports where its own binary is, which differs by
// platform: dist/electron on Linux, dist/Electron.app/Contents/MacOS/Electron
// on a Mac. Hard-coding the Linux one worked here and nowhere else.
const ELECTRON = await electronBinary();
const OUT = path.join(ROOT, 'builds', 'screenshots');

const files = process.argv.slice(2).map(function (f) { return path.resolve(f); });
if (files.length === 0) {
    console.error('usage: script/screenshot.sh <file> [file ...]');
    process.exit(1);
}

// Reuses the real main.js, so window options, ipc handlers and webContents
// policy are the ones that ship rather than a copy that can drift.
const HARNESS = `
const CORE = ${JSON.stringify(CORE)};
const FILES = ${JSON.stringify(files)};
const OUT = ${JSON.stringify(OUT)};
const fsx = require('fs');
const pathx = require('path');

setTimeout(function () { console.error('timed out'); app.exit(1); }, 120000);
process.on('uncaughtException', function (e) { console.error('main threw: ' + e.message); app.exit(1); });

global.browserOpenFiles = [];
global.browserParsedArgs = { _: [] };

app.on('ready', function () {
    registerRendererApi();
    const pkg = require(CORE + '/package.json');
    // Shown, and a fixed size: capturePage on a hidden window returns whatever
    // the compositor last had, which is usually blank.
    const opts = Object.assign({}, pkg.browserWindowOptions, { show: true, width: 1440, height: 900 });
    opts.icon = CORE + '/' + pkg.browserWindowOptions.icon;
    opts.webPreferences = Object.assign({}, pkg.browserWindowOptions.webPreferences,
                                        { preload: CORE + '/' + pkg.browserWindowOptions.webPreferences.preload });
    const w = new BrowserWindow(opts);

    w.webContents.on('did-finish-load', function () {
        setTimeout(async function () {
            try {
                fsx.mkdirSync(OUT, { recursive: true });
                for (const file of FILES) {
                    await w.webContents.executeJavaScript(
                        'lt.objs.command.exec_BANG_(cljs.core.keyword.call(null,"open-path"),' + JSON.stringify(file) + ')');
                    // Modes are applied after the editor is in the DOM, and the
                    // theme transitions, so let it settle before capturing.
                    await new Promise(function (r) { setTimeout(r, 6000); });

                    // Every tab keeps its editor in the DOM, so this has to be
                    // the visible one rather than the first one.
                    const info = JSON.parse(await w.webContents.executeJavaScript(\`(function () {
                        var shown = Array.from(document.querySelectorAll('.CodeMirror'))
                                         .filter(function (e) { return e.offsetParent !== null; });
                        var cm = shown[shown.length - 1];
                        return JSON.stringify({
                            mode: cm && cm.CodeMirror ? String(cm.CodeMirror.getOption('mode')) : 'no editor',
                            // Highlighting is spans with cm-* classes. A mode
                            // that failed to load leaves the text as plain nodes.
                            tokens: cm ? cm.querySelectorAll('.CodeMirror-code span[class*="cm-"]').length : 0
                        });
                    })()\`));

                    const image = await w.webContents.capturePage();
                    const name = pathx.basename(file).replace(/[^a-zA-Z0-9._-]/g, '_') + '.png';
                    fsx.writeFileSync(pathx.join(OUT, name), image.toPNG());
                    console.log(name + '  mode=' + info.mode + '  highlighted tokens=' + info.tokens);
                }
            } catch (e) {
                console.error('failed: ' + e.message);
                app.exit(1);
            }
            app.exit(0);
        }, 9000);
    });

    w.loadURL('file://' + CORE + '/LightTable.html?id=' + w.id);
});
`;

async function main(): Promise<void> {
    for (const [what, where] of [['Electron', ELECTRON],
                                 ['the compiled bundle', path.join(CORE, 'lighttable', 'bootstrap.js')],
                                 ['the compiled main process', path.join(CORE, 'main.js')]]) {
        if (!fs.existsSync(where)) {
            console.error(what + ' is missing at ' + where + '\nRun script/build.sh first.');
            process.exit(1);
        }
    }
    for (const f of files) {
        if (!fs.existsSync(f)) { console.error('no such file: ' + f); process.exit(1); }
    }

    const appDir = fs.mkdtempSync(path.join(os.tmpdir(), 'lt-shot-'));
    fs.writeFileSync(path.join(appDir, 'package.json'),
                     JSON.stringify({ name: 'lt-shot', version: '1.0.0', main: 'main.js' }));
    fs.writeFileSync(path.join(appDir, 'main.js'),
        fs.readFileSync(path.join(CORE, 'main.js'), 'utf8').replace(/^start\(\);$/m, '') + HARNESS);
    fs.symlinkSync(path.join(CORE, 'node_modules'), path.join(appDir, 'node_modules'));
    fs.symlinkSync(path.join(CORE, 'browserInjection.js'), path.join(appDir, 'browserInjection.js'));

    const code = await new Promise<number | null>(function (resolve) {
        spawn(ELECTRON, [appDir, '--no-sandbox'], { stdio: 'inherit' }).on('exit', resolve);
    });
    if (code !== 0) process.exit(code || 1);
    console.log('\nWritten to ' + OUT);
}

main();
