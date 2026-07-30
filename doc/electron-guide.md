## Intro

Light Table is an [Electron](https://www.electronjs.org/) application. If you
have not written one before, Electron's [process
model](https://www.electronjs.org/docs/latest/tutorial/process-model) is the
thing to read first — the rest of this document assumes it.

## Layout

```
src-electron/            TypeScript, compiled by `npm run build:main`
├── main.ts              the main process
└── preload.ts           the bridge between the window and the main process

deploy/core/
├── package.json         Electron's manifest, plus browserWindowOptions
├── main.js              compiled from src-electron/main.ts     (not in git)
├── preload.js           compiled from src-electron/preload.ts  (not in git)
├── LightTable.html      the window
├── lighttable/          compiled from src/, by shadow-cljs     (not in git)
└── node_modules/        packages the window and plugins load at runtime
```

* **package.json** sets the app name, version and entry point. Its extra
  `browserWindowOptions` key is Light Table's own: `createWindow()` passes it
  to [BrowserWindow](https://www.electronjs.org/docs/latest/api/browser-window).
  It lives here because it declares the Electron app, like the rest of the file.
* **main.ts** is the privileged half. It owns the windows, the menus, the
  dialogs and every ipc channel the window is allowed to reach. It is
  TypeScript because that boundary is worth having checked.
* **preload.ts** runs in the window, before the page, and is what the window
  talks to. See below.
* **src/** is the ClojureScript that runs in the window.

## The bridge

The window does not use Electron's modules, and does not `require('electron')`.
Everything it can ask the desktop for is a named capability on the object
`preload.ts` exposes as `lightTable`:

| | |
|---|---|
| `shell` | open a path or url, reveal in file manager, move to trash |
| `clipboard` | read and write text |
| `zoom` | this window's zoom factor |
| `window` | close, focus, minimize, geometry, devtools, app events |
| `dialog` | open and save dialogs |
| `menu` | context menus and the menubar |
| `host` | app path, platform, argv, files to open — read once at startup |

`lt.util.bridge` is the only ClojureScript namespace that names that object;
everything else goes through it. It replaced `lt.util.ipc`, which handed out
the raw `ipcRenderer` — with that, the set of things the window could do was
however many `ipc/send` calls happened to exist, discoverable only by grep.

Two reasons for the shape. The first is that Electron has sandboxed renderers
by default since v20, and a sandboxed preload has no Node — only `electron`,
`events`, `timers` and `url`. So a preload cannot be a thin wrapper over
`require()` even if one wanted it to be: every privileged operation has to run
in the main process. The second is that a named list is something you can hold
plugins to, and an ambient `require` is not.

A few things are answered in the preload rather than forwarded: `webFrame` is
renderer-side, and the preload shares this window's frame, so zoom does not
round-trip.

`main.ts` is the other end. `lt:window-call` carries the eight window methods
Light Table actually uses and refuses anything else; nothing that acts on a
window takes a window id, so a window can only act on itself.

## Miscellaneous pointers

* `IPC_DEBUG=1 script/light.sh` logs ipc messages arriving at the main process.
* [webview](https://www.electronjs.org/docs/latest/api/webview-tag) backs the
  browser tab.
* [remote-debugging-port](https://www.electronjs.org/docs/latest/reference/command-line-switches)
  is how the browser eval client attaches.

## Where this got to

`contextIsolation` is on and the window has no Node. Everything privileged goes
through the capability list in `src-electron/preload.ts`, and plugins reach
Node through a `require` Light Table serves rather than Node's own — which is
what let precompiled plugins survive a change nobody could rebuild them for.
`doc/context-isolation.md` has the design, the measurements and the order it
went in.

What is not done is `sandbox: true`, which would take Node out of the preload
as well. That is a different design and a much more expensive one; the same
document explains why it buys less than the step before it.

## Additional links

* [Electron docs](https://www.electronjs.org/docs/latest) — modules to know:
  BrowserWindow, app, dialog, ipcMain, contextBridge
* [Context isolation](https://www.electronjs.org/docs/latest/tutorial/context-isolation)
* [Electron's security checklist](https://www.electronjs.org/docs/latest/tutorial/security)
