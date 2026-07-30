(ns lt.util.bridge
  "Light Table's window talking to its privileged half.

  Everything the window can ask the desktop for arrives here, and only here:
  this is the one namespace that names the object src-electron/preload.ts
  exposes. The surface is a list of capabilities rather than a re-export of
  Electron or of Node — see src-electron/preload.ts for why it has to be.

  It replaced lt.util.ipc, which handed out the raw ipcRenderer. That let any
  namespace open any channel to the browser process, so the set of things the
  window could do was however many `ipc/send` calls existed, discoverable only
  by grep. What that set is now is the list below.")

;; Not `lt`: the compiled ClojureScript owns that global.
(def ^js bridge js/lightTable)

(def ^js shell
  "Opening things in the desktop environment: open, openExternal,
  showItemInFolder, trashItem."
  (.-shell bridge))

(def ^js clipboard
  "The system clipboard: readText, writeText."
  (.-clipboard bridge))

(def ^js zoom
  "This window's zoom factor: get, set."
  (.-zoom bridge))

(def ^js window
  "This window, in the browser process. Nothing here takes a window id, so a
  window can only act on itself: state, close, destroy, focus, minimize,
  maximize, setFullScreen, setSize, setPosition, openNew, init, toggleDevTools,
  and the onAppEvent/onDevtoolsEvent/onOpenFile notifications."
  (.-window bridge))

(def ^js dialog
  "Native file dialogs, parented to this window: open, save."
  (.-dialog bridge))

(def ^js menu
  "Native menus, described as data: popup, setApplicationMenu, onClick."
  (.-menu bridge))

(def app-info
  "Values owned by the browser process that the window reads once at startup.
  Keys are :appPath, :platform, :parsedArgs, :openFiles and :argv."
  (js->clj (.appInfo ^js (.-host bridge)) :keywordize-keys true))
