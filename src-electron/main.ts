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
// and so on. script/smoke-test.js appends a harness to the compiled output and
// refers to them by those names.
import * as electron from 'electron';
import * as fs from 'node:fs';
import { parseArgs } from 'node:util';

const { app, BrowserWindow, ipcMain, dialog, Menu, MenuItem, shell, clipboard } = electron;

/** Values the renderer reads once, synchronously, while starting up. */
interface AppInfo {
    appPath: string;
    platform: NodeJS.Platform;
    parsedArgs: ParsedArgs;
    openFiles: string[];
    argv: string[];
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
global.browserOpenFiles = []; // Track files for open-file event

const packageJSON = require(__dirname + '/package.json');

// Returns Window object
function createWindow(): electron.BrowserWindow {
    const browserWindowOptions = packageJSON.browserWindowOptions;
    browserWindowOptions.icon = __dirname + '/' + browserWindowOptions.icon;
    // Electron resolves neither of these relative to the app directory.
    if (browserWindowOptions.webPreferences?.preload) {
        browserWindowOptions.webPreferences.preload =
            __dirname + '/' + browserWindowOptions.webPreferences.preload;
    }
    const window = new BrowserWindow(browserWindowOptions);
    windows[window.id] = window;
    window.focus();
    window.webContents.on("will-navigate", function(e) {
        e.preventDefault();
        window.webContents.send("app", "will-navigate");
    });

    if (process.platform == 'win32') {
        window.on("blur", function() {
            if (window.webContents)
                window.webContents.send("app", "blur");
        });
        window.on("focus", function() {
            if (window.webContents)
                window.webContents.send("app", "focus");
        });
    } else {
        window.on("blur", function() {
            window.webContents.send("app", "blur");
        });
        window.on("focus", function() {
            window.webContents.send("app", "focus");
        });
    }
    // These are webContents events, not BrowserWindow ones. They were attached
    // to the window, where they never fired, so the devtools client was never
    // told to drop its connection when devtools opened — the two compete for
    // the same debugging port. The type checker caught it during the port.
    window.webContents.on("devtools-opened", function() {
        window.webContents.send("devtools", "disconnect");
    });
    window.webContents.on("devtools-closed", function() {
        window.webContents.send("devtools", "reconnect!");
    });

    // and load the index.html of the app.
    window.loadURL('file://' + __dirname + '/LightTable.html?id=' + window.id);

    // Notify LT that the user requested to close the window/app
    window.on("close", function(evt) {
        window.webContents.send("app", "close!");
        evt.preventDefault();
    });

    // Emitted when the window is closed.
    window.on('closed', function() {
        windows[window.id] = null;
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
 * the seam script/smoke-test.js drives, so anything a real window depends on
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
            argv: process.argv
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
    ipcMain.on("initWindow", function(event) {
        // Moving this to createWindow() causes js loading issues
        const window = windowFor(event);
        if (!window) return;
        window.on("focus", function() {
            window.webContents.send("app", "focus");
        });
    });

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

function start(): void {
    app.commandLine.appendSwitch('remote-debugging-port', '8315');
    app.commandLine.appendSwitch('js-flags', '--harmony');

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
                windows[Number(id)]?.webContents.send('openFileAfterStartup', path);
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
