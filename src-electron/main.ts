// Light Table's main process.
//
// This is the privileged half of the application: it owns the windows, the
// menus, the dialogs and every ipc channel the renderer is allowed to reach.
// It is written in TypeScript because that boundary is worth having checked —
// a channel that hands back the wrong shape fails in a user's session, not in a
// build.
//
// Compiled to deploy/core/main.js by `npm run build:main`.

// Imported as a namespace and destructured, rather than with named imports, so
// that the emitted JavaScript keeps local bindings called `app`, `BrowserWindow`
// and so on. script/smoke-test.mts appends a harness to the compiled output and
// refers to them by those names.
import * as electron from 'electron';
import * as fs from 'node:fs';
import { parseArgs } from 'node:util';
import { windowOptions, resolveDebugPort, headless, recoveryFor } from './config';
import type { WindowFailure } from './config';

const { app, BrowserWindow, ipcMain, dialog, Menu, MenuItem, shell, clipboard } = electron;

/** Values the renderer reads once, synchronously, while starting up. */
interface AppInfo {
    appPath: string;
    platform: NodeJS.Platform;
    parsedArgs: ParsedArgs;
    openFiles: string[];
    argv: string[];
    /** Where the DevTools endpoint is, or null when this run has none. The
     *  browser tab evaluates through it, and used to hard-code 8315 in the
     *  renderer — two places to change, one of which nothing would notice. */
    remoteDebuggingPort: number | null;
    /** Where this user's settings, plugins, logs and caches belong.
     *
     *  Not appPath. Light Table used appPath for both, which in a packaged
     *  build is Contents/Resources/app — so the first run wrote User/, logs/
     *  and ltcache/ inside the .app and broke its own code signature. It also
     *  meant an application in /Applications could not start for anyone
     *  without write access to it, and that upgrading discarded your
     *  settings. */
    userDataPath: string;
}

/** Geometry of the window a renderer belongs to. */
interface WindowState {
    id: number;
    size: number[];
    position: number[];
    fullScreen: boolean;
}

interface ParsedArgs {
    help?: boolean;
    add?: boolean;
    _: string[];
    [flag: string]: unknown;
}

/**
 * A menu as the renderer describes it: plain data, with `token` standing in for
 * a click handler that stays over there.
 */
interface MenuDescription {
    label?: string;
    sublabel?: string;
    toolTip?: string;
    role?: string;
    type?: string;
    accelerator?: string;
    enabled?: boolean;
    visible?: boolean;
    checked?: boolean;
    token?: number;
    submenu?: (MenuDescription | null)[];
}

declare global {
     
    var browserOpenFiles: string[];
     
    var browserParsedArgs: ParsedArgs;
}

const USAGE = [
    "",
    "Usage: light [options] [path ...]",
    "",
    "Paths are either a file or a directory.",
    "Files can take a line number e.g. file:line.",
    "",
    "Options:",
    "  -h, --help  Print help",
    "  -a, --add   Add path(s) to workspace",
    ""
].join("\n");

// Keep a global reference of the window object, if you don't, the window will
// be closed automatically when the javascript object is GCed.
const windows: Record<number, electron.BrowserWindow | null> = {};

/**
 * Tell a window something, if there is still anything there to tell.
 *
 * Every `webContents.send` in this file used to be bare, and a send to a
 * renderer that is gone **throws** — `Render frame was disposed before
 * WebFrameMain could be accessed`. Which turns one dead renderer into a
 * main-process exception on the next window event, from a handler that has no
 * business failing: blurring a window should not be able to raise anything.
 *
 * Not `if (window.webContents)`, which is what the win32 branch checked and is
 * not a check at all — `webContents` is a getter that is always truthy,
 * including on a window whose renderer has died. It read like a guard, which is
 * worse than not having one.
 *
 * **And not `isDestroyed()` alone either**, which is what this tried first and
 * what a crashed renderer walks straight through: the *frame* is disposed while
 * the `WebContents` object is still very much alive, so both `isDestroyed()`
 * calls answer false and the send throws anyway. Verified by killing a renderer
 * with `kill -9` and watching it come back out of here.
 *
 * So `isCrashed()`, which is the state Electron actually exposes for this — and
 * then a `try` around the send regardless. There is no predicate that makes a
 * cross-process call safe, and the thing being protected is a blur handler:
 * nothing about switching windows should be able to raise.
 */
function sendTo(window: electron.BrowserWindow, channel: string, ...args: unknown[]): void {
    if (window.isDestroyed() || window.webContents.isDestroyed() || window.webContents.isCrashed()) return;
    try {
        window.webContents.send(channel, ...args);
    } catch {
        // A renderer that went away between the check and the send. Nothing to
        // tell and nobody to tell it to.
    }
}

/**
 * What to do when a window's renderer dies, wedges, or never loads.
 *
 * There was nothing here, and nothing is what it looked like: the renderer
 * process goes away, the window stays exactly where it was — a white rectangle,
 * the right size, with a title bar — and the application carries on as though it
 * has a window. Nothing is logged and nothing is said. It is indistinguishable
 * from the editor having hung, so the reasonable thing to do is force-quit it,
 * and then it looks like Light Table crashes on startup.
 *
 * This is VS Code's `onWindowError`, read from `windowImpl.ts`: the same three
 * events, the same native dialog, and `destroy()` rather than `close()` for the
 * same reason.
 *
 * **It reloads rather than asking**, the first time. Asking looks more careful
 * and preserves nothing: a dead renderer has already lost whatever was unsaved,
 * so there is no choice being offered — only a dialog between the user and a
 * working editor. Light Table restores the open files from the session on load,
 * so reloading is close to where they were. VS Code asks because its editor
 * state lives in the renderer it is about to abandon; here the session is on
 * disk.
 *
 * **Then it stops.** A renderer that dies *while starting* would otherwise
 * reload, die, and reload for ever, which is a worse failure than a blank
 * window because it never settles and burns a core doing it. The second failure
 * for a window asks instead, and the count is per window and not global so one
 * bad window does not spend another's chance.
 *
 * **A dialog that is not parented to the window.** Attached to a `BrowserWindow`
 * a message box is a sheet, and a sheet on a window whose renderer is gone does
 * not draw — checked, and the reason the first version of this appeared to do
 * nothing at all. Unparented it is application-modal, which is both visible and
 * closer to true: the window it would be attached to is the thing that failed.
 *
 * **`destroy()`, not `close()`.** `close` is intercepted below and handed to the
 * renderer, because Light Table asks the editor about unsaved changes before
 * letting a window go. With no renderer nobody answers, so `close()` on a dead
 * window does nothing at all — which is how a blank window becomes one that
 * cannot even be dismissed.
 *
 * **And nothing modal when headless.** A dialog in a test run is a hang rather
 * than a failure, and there is no one to click it. VS Code makes the same
 * exception for its smoke driver. The test still hears about it, because the log
 * line happens either way — `windows.spec.ts` asserts that a window reports no
 * errors while starting, and this is exactly the kind of error that means.
 */
const windowFailures: Record<number, number> = {};

function onWindowError(window: electron.BrowserWindow, kind: WindowFailure, what: string,
                       details: { reason?: string; exitCode?: number | null }): void {
    const said = `reason: ${details.reason ?? '<unknown>'}, code: ${details.exitCode ?? '<unknown>'}`;
    console.error(`window ${window.id}: ${what} (${said})`);

    if (window.isDestroyed()) return;

    const failures = (windowFailures[window.id] ?? 0) + 1;
    windowFailures[window.id] = failures;

    const recovery = recoveryFor(kind, failures, headless(process.env));
    console.error(`window ${window.id}: ${recovery}`);

    if (recovery === 'reload') { window.reload(); return; }
    if (recovery === 'destroy') { window.destroy(); return; }

    const recoverable = kind !== 'unresponsive';

    // Asynchronous on purpose: the synchronous variant blocks the main process,
    // and this can arrive while another window is mid-event.
    dialog.showMessageBox({
        type: 'warning',
        buttons: ['Reload', 'Close'],
        defaultId: 0,
        cancelId: 1,
        message: `The Light Table window ${what}`,
        detail: `${said}\n\n`
                + (recoverable
                   ? 'It has already been reloaded once and failed again, so this may not be '
                     + 'recoverable. '
                   : '')
                + 'Reloading starts the editor again in this window and restores the files '
                + 'you had open from the session. Unsaved changes since the last save are '
                + 'gone either way.'
    }).then(({ response }) => {
        if (window.isDestroyed()) return;
        if (response === 0) window.reload();
        else window.destroy();
    }).catch((e: unknown) => { console.error(`window ${window.id}: could not ask`, e); });
}
global.browserOpenFiles = []; // Track files for open-file event

const packageJSON = require(__dirname + '/package.json');

// Returns Window object
function createWindow(): electron.BrowserWindow {
    // Built fresh per window rather than by adding __dirname to the cached
    // package.json object — see config.ts, and the white second window that
    // taught us the difference.
    // A hidden window under LT_HEADLESS, which is how a test run stops opening
    // windows across somebody's desktop. See config.ts.
    // Created hidden and shown once it has painted, which is the other white
    // screen. Electron shows a window as soon as it exists, and an Electron
    // window with nothing drawn in it yet is **white** — so every launch flashed
    // a white rectangle the size of the editor before the dark skin arrived.
    // `backgroundColor` in browserWindowOptions makes that gap the right colour
    // rather than white; not showing until `ready-to-show` means there is no gap
    // to colour. Both, because they fail differently.
    const window = new BrowserWindow(
        windowOptions(packageJSON.browserWindowOptions, __dirname, { show: false }));
    windows[window.id] = window;

    if (!headless(process.env)) {
        let shown = false;
        const reveal = () => {
            if (shown || window.isDestroyed()) return;
            shown = true;
            window.show();
            // A hidden window that focuses itself takes the keyboard from
            // whatever you were typing in, which under a test run is your
            // editor. So this waits for the window to be shown at all.
            window.focus();
        };
        window.once("ready-to-show", reveal);
        // And a deadline, for the same reason lt.objs.proc/on-env-ready has one:
        // `ready-to-show` is tied to the first paint, so anything that stops the
        // renderer painting — a script that throws while starting, a stylesheet
        // that never arrives — would leave the window hidden for ever. An
        // application that does not appear is a worse bug than one that appears
        // unpainted, and it is the same bug class as a gate only success opens.
        setTimeout(reveal, 4000);
    }
    window.webContents.on("will-navigate", function(e) {
        e.preventDefault();
        sendTo(window, "app", "will-navigate");
    });

    // A dead, wedged or unloadable renderer, which used to be a white rectangle
    // and silence. See onWindowError.
    window.webContents.on("render-process-gone", function(_e, details) {
        onWindowError(window, 'gone', "terminated unexpectedly",
                      { reason: details.reason, exitCode: details.exitCode });
    });
    window.webContents.on("did-fail-load", function(_e, errorCode, errorDescription, url, isMainFrame) {
        // Subframe failures are not the window failing, and `-3` is
        // ERR_ABORTED — what a navigation that was deliberately replaced
        // reports, including the `will-navigate` interception just above.
        if (!isMainFrame || errorCode === -3) return;
        onWindowError(window, 'load', "could not load", { reason: `${errorDescription} (${url})`, exitCode: errorCode });
    });
    // A window that loaded is a window that is well, so its history does not
    // count against it. Without this the second crash in a long session gets the
    // "already reloaded once and failed again" treatment for a reload that
    // worked fine an hour ago.
    window.webContents.on("did-finish-load", function() {
        delete windowFailures[window.id];
    });
    window.on("unresponsive", function() {
        // Not while a debugger is attached: a breakpoint stops the renderer, and
        // Electron reports that as unresponsive. VS Code carves out the same case
        // and gives the same reason.
        if (window.webContents.isDevToolsOpened()) return;
        onWindowError(window, 'unresponsive', "is not responding", {});
    });

    // The blur/focus branch used to differ by platform because the win32 side
    // had a guard that did nothing — see sendTo. One arrangement now.
    window.on("blur", function() { sendTo(window, "app", "blur"); });
    window.on("focus", function() { sendTo(window, "app", "focus"); });

    // These are webContents events, not BrowserWindow ones. They were attached
    // to the window, where they never fired, so the devtools client was never
    // told to drop its connection when devtools opened — the two compete for
    // the same debugging port. The type checker caught it during the port.
    window.webContents.on("devtools-opened", function() {
        sendTo(window, "devtools", "disconnect");
    });
    window.webContents.on("devtools-closed", function() {
        sendTo(window, "devtools", "reconnect!");
    });

    // and load the index.html of the app.
    window.loadURL('file://' + __dirname + '/LightTable.html?id=' + window.id);

    // Notify LT that the user requested to close the window/app.
    //
    // Intercepted, because Light Table asks the editor about unsaved changes
    // before a window goes — so the renderer decides, and calls back.
    //
    // Which means `preventDefault` had to stop being unconditional. With the
    // renderer gone there is nobody to call back, so cancelling the close made a
    // window that cannot be closed: not by the red button, not by ⌘W, not by
    // Quit. A blank window is a bad afternoon; a blank window that will not go
    // away is a force-quit and a bug report about Light Table hanging.
    window.on("close", function(evt) {
        if (window.webContents.isDestroyed()) return;
        sendTo(window, "app", "close!");
        evt.preventDefault();
    });

    // Emitted when the window is closed.
    window.on('closed', function() {
        windows[window.id] = null;
        delete windowFailures[window.id];
    });

    return window;
}

// The renderer used to reach these through the `remote` module, which Electron
// removed in v14. Everything it needs now goes over explicit channels.

function windowFor(event: electron.IpcMainEvent | electron.IpcMainInvokeEvent): electron.BrowserWindow | null {
    return BrowserWindow.fromWebContents(event.sender);
}

/** What a renderer is allowed to do to its own window. */
const WINDOW_METHODS = new Set([
    "close", "destroy", "focus", "minimize", "maximize",
    "setFullScreen", "setSize", "setPosition"
]);

// Turn a plain menu description from the renderer into a real Menu. Renderer
// click handlers stay in the renderer: each clickable entry carries a token,
// and clicking sends that token back for the renderer to dispatch.
function toMenuTemplate(sender: electron.WebContents, item: MenuDescription): electron.MenuItemConstructorOptions {
    const { token, submenu, ...rest } = item;
    const out = rest as electron.MenuItemConstructorOptions;
    if (submenu) {
        out.submenu = submenu
            .filter((child): child is MenuDescription => Boolean(child))
            .map(function(child) { return toMenuTemplate(sender, child); });
    }
    if (token !== undefined && token !== null) {
        out.click = function() {
            if (!sender.isDestroyed()) sender.send("lt:menu-click", token);
        };
    }
    return out;
}

function buildMenu(sender: electron.WebContents, items: (MenuDescription | null)[]): electron.Menu {
    const menu = new Menu();
    (items || [])
        .filter((item): item is MenuDescription => Boolean(item))
        .forEach(function(item) {
            menu.append(new MenuItem(toMenuTemplate(sender, item)));
        });
    return menu;
}

/**
 * Everything the main process has to set up before a window can work. This is
 * the seam script/smoke-test.mts drives, so anything a real window depends on
 * belongs in here rather than in onReady() — otherwise the test boots an
 * application that differs from the one that ships.
 */
function registerRendererApi(): void {
    // Read once during renderer startup, so it has to be synchronous.
    ipcMain.on("lt:app-info", function(event) {
        const info: AppInfo = {
            appPath: app.getAppPath(),
            // The window used to read this off its own `process`, which a
            // sandboxed renderer does not have.
            platform: process.platform,
            parsedArgs: global.browserParsedArgs,
            openFiles: global.browserOpenFiles,
            argv: process.argv,
            remoteDebuggingPort: debugPort.port,
            userDataPath: app.getPath('userData')
        };
        event.returnValue = info;
    });

    ipcMain.on("lt:window-state", function(event) {
        const window = windowFor(event);
        const state: WindowState | null = window ? {
            id: window.id,
            size: window.getSize(),
            position: window.getPosition(),
            fullScreen: window.isFullScreen()
        } : null;
        event.returnValue = state;
    });

    ipcMain.on("lt:window-call", function(event, method: string, args: unknown[]) {
        const window = windowFor(event);
        if (!window) return;
        // The renderer names the method, so this used to be any function on
        // BrowserWindow — a channel that reads "call whatever you like on the
        // window". These eight are what Light Table actually calls.
        if (!WINDOW_METHODS.has(method)) {
            console.warn("lt:window-call refused for", method);
            return;
        }
        const target = window as unknown as Record<string, (...a: unknown[]) => unknown>;
        target[method]!.apply(window, args || []);
    });

    ipcMain.handle("lt:dialog-open", function(event, options: electron.OpenDialogOptions) {
        const window = windowFor(event);
        return window ? dialog.showOpenDialog(window, options) : dialog.showOpenDialog(options);
    });

    ipcMain.handle("lt:dialog-save", function(event, options: electron.SaveDialogOptions) {
        const window = windowFor(event);
        return window ? dialog.showSaveDialog(window, options) : dialog.showSaveDialog(options);
    });

    ipcMain.on("lt:menu-popup", function(event, items: (MenuDescription | null)[]) {
        const window = windowFor(event);
        buildMenu(event.sender, items).popup(window ? { window } : {});
    });

    ipcMain.on("lt:menu-app", function(event, items: (MenuDescription | null)[]) {
        Menu.setApplicationMenu(buildMenu(event.sender, items));
    });

    registerBridge();
    secureWebContents();
}

/**
 * The capability surface src-electron/preload.ts exposes to the window.
 *
 * A sandboxed renderer has no Node at all, so anything the window needs has to
 * be named here and run on this side. That makes this list the permission
 * model: it is what the window can ask for, and the natural place to scope or
 * refuse a request once plugins are held to a narrower set than Light Table
 * itself.
 */
function registerBridge(): void {
    // Deciding between a path and a url takes a stat, and a sandboxed window
    // has no fs to take one with — so the choice is made here rather than
    // exposing the filesystem to make it. Resolves to an error message rather
    // than throwing, which is how shell.openPath reports failure.
    ipcMain.handle("lt:shell-open", async function(event, pathOrUrl: string) {
        if (fs.existsSync(pathOrUrl)) return shell.openPath(pathOrUrl);
        await shell.openExternal(pathOrUrl);
        return "";
    });

    ipcMain.handle("lt:shell-open-external", function(event, url: string) {
        return shell.openExternal(url);
    });

    ipcMain.on("lt:shell-show-item", function(event, path: string) {
        shell.showItemInFolder(path);
    });

    ipcMain.handle("lt:shell-trash-item", function(event, path: string) {
        return shell.trashItem(path);
    });

    ipcMain.on("lt:clipboard-read", function(event) {
        event.returnValue = clipboard.readText();
    });

    ipcMain.on("lt:clipboard-write", function(event, text: string) {
        clipboard.writeText(text);
    });
}

/**
 * Policy for every page this application creates, applied where it cannot be
 * forgotten for one of them.
 *
 * Light Table's browser tab is a <webview>, and a guest in it is an arbitrary
 * website. Electron lets the page hosting a webview choose the guest's
 * preferences through attributes, so those are settled here instead.
 */
function secureWebContents(): void {
    app.on('web-contents-created', function(_event, contents) {
        // Nothing in Light Table calls window.open, but the default handler
        // would create a BrowserWindow inheriting this app's preferences —
        // node integration included — and point it at whatever url it was
        // given. A link that wants a new window gets the desktop's browser.
        contents.setWindowOpenHandler(function({ url }) {
            if (url.startsWith('https:') || url.startsWith('http:')) {
                shell.openExternal(url);
            }
            return { action: 'deny' };
        });

        contents.on('will-attach-webview', function(_e, webPreferences) {
            webPreferences.nodeIntegration = false;
            webPreferences.nodeIntegrationInSubFrames = false;
            webPreferences.preload = __dirname + '/browserInjection.js';
            // Off for guests on purpose, and it has to be set rather than left
            // alone: it defaults to true, which puts the injection in an
            // isolated world where `lttools` and `eval` cannot see the page.
            // Evaluating against the page is what the browser tab is for.
            webPreferences.contextIsolation = false;
        });
    });
}

function onReady(): void {
    registerRendererApi();

    ipcMain.on("createWindow", function() {
        createWindow();
    });

    // Both of these took the window id from the renderer, which let a window
    // name one it did not own. They are only ever called with the sender's own
    // id, so the sender is where it comes from now.
    // The renderer saying it is up. It used to answer that by adding a
    // focus-forwarding listener — a second one, because `createWindow` already
    // attaches exactly that, so every focus was reported to the renderer twice.
    //
    // Worse, it *accumulated*: this arrives on every load, and a window can load
    // more than once. That had no way to happen before, so the leak was
    // unreachable and the comment here said only that moving it caused loading
    // issues. It has a way now — onWindowError offers to reload a window rather
    // than leaving it blank — so a crash and a reload would have meant three
    // focus messages, then four.
    //
    // Nothing to do, then, and no listener: an `ipcRenderer.send` with nobody
    // listening is dropped, which is what this amounted to already.
    // `lt.objs.app/notify-init-window` is now telling the main process something
    // it does not use, and is noted in doc/hygiene.md rather than unwired here.

    ipcMain.on("toggleDevTools", function(event) {
        windowFor(event)?.webContents.toggleDevTools();
    });

    createWindow();
}

// Replaced yargs, whose only job here was two boolean flags and a usage
// string. node:util covers that without the dependency.
function readArgs(): void {
    let parsed;
    try {
        parsed = parseArgs({
            args: process.argv.slice(1),
            options: {
                help: { type: 'boolean', short: 'h', default: false },
                add: { type: 'boolean', short: 'a', default: false }
            },
            allowPositionals: true,
            // Paths are handed straight through, so anything unrecognised is a
            // path rather than a mistake.
            strict: false
        });
    } catch (e) {
        parsed = { values: {}, positionals: process.argv.slice(1) };
    }

    global.browserParsedArgs = Object.assign({}, parsed.values, { _: parsed.positionals }) as ParsedArgs;

    if (global.browserParsedArgs.help) {
        process.stdout.write("\nLight Table " + app.getVersion() + "\n" + USAGE);
        process.exit(0);
    }
}

// How the browser tab evaluates: lt.objs.clients.devtools opens a WebSocket to
// this port and speaks the DevTools protocol to the guest page.
//
// At module scope rather than inside start(), and the pair has to stay
// together. Switches must be appended before the app is ready, and not every
// entry point calls start() — script/smoke-test.mts requires this file and
// drives it itself, so a switch set only in start() is one the harness never
// gets, which is how the second of these came to be missing there.
//
// Settable, and skippable, because a harness that launches the application
// appends its own and the two then disagree. resolveDebugPort says why.
const debugPort = resolveDebugPort(process.env);
if (debugPort.reason === 'invalid') {
    console.error('LT_REMOTE_DEBUGGING_PORT is not a port number; using ' + debugPort.port);
}
if (debugPort.port !== null) {
    app.commandLine.appendSwitch('remote-debugging-port', String(debugPort.port));
    // Without this, Chromium refuses that WebSocket with a 403 and the browser
    // tab cannot evaluate anything. Chromium 111 began checking the Origin
    // header on debugger connections, and Light Table's window is a file:// url
    // — so the origin is `file://`, which is exactly what has to be allowed.
    // Not `*`: that would let any page able to reach the port drive this
    // application's debugger.
    app.commandLine.appendSwitch('remote-allow-origins', 'file://');
}

function start(): void {

    // Before `ready`, and that is the whole point. macOS puts an application in
    // the Dock when it launches, not when it opens a window — so hiding the
    // icon from inside `onReady` is half a second too late and shows up as the
    // Dock growing and shrinking for every launch. `accessory` means it was
    // never a Dock application to begin with.
    //
    // Only under LT_HEADLESS. A real launch belongs in the Dock, and this is
    // the setting that would stop it appearing there at all.
    if (headless(process.env)) {
        app.setActivationPolicy?.('accessory');
        app.dock?.hide();
    }

    // This method will be called when electron has done everything
    // initialization and ready for creating browser windows.
    app.on('ready', onReady);

    // Quit when all windows are closed.
    app.on('window-all-closed', function() {
        app.quit();
    });

    // open-file operates in two modes - before and after startup.
    // On startup and before a window has opened, event paths are
    // saved and then opened once windows are available.
    // After startup, event paths are sent to available windows.
    app.on('open-file', function(event, path) {
        const open = Object.keys(windows);
        if (open.length > 0) {
            open.forEach(function(id) {
                const window = windows[Number(id)];
                if (window) sendTo(window, 'openFileAfterStartup', path);
            });
        } else {
            global.browserOpenFiles.push(path);
        }
    });
    readArgs();
}

// Set $IPC_DEBUG to debug incoming ipcMain messages for the main process
if (process.env["IPC_DEBUG"]) {
    const oldOn = ipcMain.on.bind(ipcMain);
    ipcMain.on = function(channel: string, listener: (...a: any[]) => void) {
        return oldOn(channel, function(this: unknown, ...args: any[]) {
            console.log("\t\t\t\t\t->MAIN", channel, args.join(', '));
            return listener.apply(this, args);
        });
    } as typeof ipcMain.on;
}

start();
