/*jshint esversion: 6 */
"use strict";

const {
    app,
    BrowserWindow,
    ipcMain,
    dialog,
    Menu,
    MenuItem
} = require('electron');


const { parseArgs } = require('node:util');

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
var windows = {};
global.browserOpenFiles = []; // Track files for open-file event

var packageJSON = require(__dirname + '/package.json');

// Returns Window object
function createWindow() {
    let browserWindowOptions = packageJSON.browserWindowOptions;
    browserWindowOptions.icon = __dirname + '/' + browserWindowOptions.icon;
    let window = new BrowserWindow(browserWindowOptions);
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
    window.on("devtools-opened", function() {
        window.webContents.send("devtools", "disconnect");
    });
    window.on("devtools-closed", function() {
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

function windowFor(event) {
    return BrowserWindow.fromWebContents(event.sender);
}

// Turn a plain menu description from the renderer into a real Menu. Renderer
// click handlers stay in the renderer: each clickable entry carries a token,
// and clicking sends that token back for the renderer to dispatch.
function toMenuTemplate(sender, item) {
    let out = Object.assign({}, item);
    if (out.submenu) {
        out.submenu = (out.submenu || []).filter(Boolean)
            .map(function(child) { return toMenuTemplate(sender, child); });
    }
    if (out.token !== undefined && out.token !== null) {
        let token = out.token;
        delete out.token;
        out.click = function() {
            if (!sender.isDestroyed()) sender.send("lt:menu-click", token);
        };
    }
    return out;
}

function buildMenu(sender, items) {
    let menu = new Menu();
    (items || []).filter(Boolean).forEach(function(item) {
        menu.append(new MenuItem(toMenuTemplate(sender, item)));
    });
    return menu;
}

function registerRendererApi() {
    // Read once during renderer startup, so it has to be synchronous.
    ipcMain.on("lt:app-info", function(event) {
        event.returnValue = {
            appPath: app.getAppPath(),
            parsedArgs: global.browserParsedArgs,
            openFiles: global.browserOpenFiles,
            argv: process.argv
        };
    });

    ipcMain.on("lt:window-state", function(event) {
        let window = windowFor(event);
        event.returnValue = window ? {
            id: window.id,
            size: window.getSize(),
            position: window.getPosition(),
            fullScreen: window.isFullScreen()
        } : null;
    });

    ipcMain.on("lt:window-call", function(event, method, args) {
        let window = windowFor(event);
        if (window && typeof window[method] === "function") {
            window[method].apply(window, args || []);
        }
    });

    ipcMain.handle("lt:dialog-open", function(event, options) {
        return dialog.showOpenDialog(windowFor(event), options);
    });

    ipcMain.handle("lt:dialog-save", function(event, options) {
        return dialog.showSaveDialog(windowFor(event), options);
    });

    ipcMain.on("lt:menu-popup", function(event, items) {
        buildMenu(event.sender, items).popup({ window: windowFor(event) });
    });

    ipcMain.on("lt:menu-app", function(event, items) {
        Menu.setApplicationMenu(buildMenu(event.sender, items));
    });
}

function onReady() {
    registerRendererApi();

    ipcMain.on("createWindow", function(event, info) {
        createWindow();
    });

    ipcMain.on("initWindow", function(event, id) {
        // Moving this to createWindow() causes js loading issues
        windows[id].on("focus", function() {
            windows[id].webContents.send("app", "focus");
        });
    });

    ipcMain.on("toggleDevTools", function(event, windowId) {
        if (windowId && windows[windowId]) {
            windows[windowId].toggleDevTools();
        }
    });

    createWindow();
}

// Replaced yargs, whose only job here was two boolean flags and a usage
// string. node:util covers that without the dependency.
function readArgs() {
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

    global.browserParsedArgs = Object.assign({}, parsed.values, { _: parsed.positionals });

    if (global.browserParsedArgs.help) {
        process.stdout.write("\nLight Table " + app.getVersion() + "\n" + USAGE);
        process.exit(0);
    }
}

function start() {
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
        if (Object.keys(windows).length > 0) {
            Object.keys(windows).forEach(function(id) {
                windows[id].webContents.send('openFileAfterStartup', path);
            });
        } else {
            global.browserOpenFiles.push(path);
        }
    });
    readArgs();
}

// Set $IPC_DEBUG to debug incoming and outgoing ipcMain messages for the main process
if (process.env["IPC_DEBUG"]) {
    let oldOn = ipcMain.on;
    ipcMain.on = function(channel, cb) {
        oldOn.call(ipcMain, channel, function() {
            console.log("\t\t\t\t\t->MAIN", channel, Array.prototype.slice.call(arguments).join(', '));
            cb.apply(null, arguments);
        });
    };
    let logSend = function(window) {
        let oldSend = window.webContents.send;
        window.webContents.send = function() {
            console.log("\t\t\t\t\tMAIN->", Array.prototype.slice.call(arguments).join(', '));
            oldSend.apply(window.webContents, arguments);
        };
    };
    let oldCreateWindow = createWindow;
    createWindow = function() {
        logSend(oldCreateWindow());
    };
}

start();