(ns lt.util.bridge
  "Light Table's window talking to its privileged half.

  Everything the window can ask the desktop for arrives here, and only here:
  this is the one namespace that names the object src-electron/preload.ts
  exposes. The surface is a list of capabilities rather than a re-export of
  Electron or of Node — see src-electron/preload.ts for why it has to be.

  It replaced lt.util.ipc, which handed out the raw ipcRenderer. That let any
  namespace open any channel to the browser process, so the set of things the
  window could do was however many `ipc/send` calls existed, discoverable only
  by grep. What that set is now is the list below.

  Eight of the fourteen groups are **guarded**: they answer to whoever is
  calling, and a plugin gets what its manifest said rather than everything the
  user can reach. That is [[lt.util.bridge.guard]], and until a policy is
  installed it is a passthrough. The six that are not guarded reach nothing —
  path arithmetic, the line ending, this window's own zoom and geometry, native
  menus, and a dialog that asks the user for a path but cannot read it."
  (:require [lt.util.bridge.guard :as guard]))

;; Not `lt`: the compiled ClojureScript owns that global.
(def ^js bridge js/lightTable)

(def ^js raw-files
  "The filesystem, unguarded.

  Private in spirit and public in fact, because there is exactly one caller and
  it cannot use the guarded one: the policy resolves a path with `realpathSync`
  in order to decide whether that path is allowed, and `realpathSync` is itself
  guarded. Going through the facade would be a permission check that needs a
  permission check.

  Nothing else in the window should name this. `files` below is what the editor
  uses."
  (.-files bridge))

(def ^js shell
  "Opening things in the desktop environment: open, openExternal,
  showItemInFolder, trashItem. Guarded — `:desktop`, and the three that take a
  path are scoped to one."
  (guard/guard "shell" (.-shell bridge)))

(def ^js clipboard
  "The system clipboard: readText, writeText. Guarded — `:clipboard`."
  (guard/guard "clipboard" (.-clipboard bridge)))

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

(def ^js files
  "The filesystem, in fifteen functions: existsSync, readFileSync,
  readFileBytesSync, readFile, writeFileSync, appendFileSync, mkdirSync,
  readdirSync, realpathSync, renameSync, rmSync, cpSync, statSync, watch and
  extract.

  Served in the preload's world rather than forwarded to the browser process,
  which is what keeps it as cheap as the direct call it replaces — see
  doc/context-isolation.md.

  Guarded — `:files`, and every one of the fifteen has its path checked against
  the caller's roots. This docstring used to name three of them, which is where
  doc/permissions.md got the figure of twelve it scoped the work against."
  (guard/guard "files" (.-files bridge)))

(def ^js path
  "Path arithmetic: join, dirname, basename, extname, relative, resolve,
  isAbsolute, sep."
  (.-path bridge))

(def ^js os
  "Facts about the operating system: EOL, drives."
  (.-os bridge))

(def ^js net
  "Fetching things over the network: download. One coarse capability rather
  than an http client — what the window wants is a file, not a socket.

  Guarded — `:network`, with the url checked against the caller's hosts and the
  destination against its paths. The only function on the bridge that is
  scoped both ways, because it is the only one that crosses from one to the
  other."
  (guard/guard "net" (.-net bridge)))

(def ^js processes
  "Other programs: spawn, exec, fork. Each returns a handle rather than a
  ChildProcess — a process object cannot cross, so what crosses is a set of
  functions over one that stays put.

  Guarded — `:processes`. `fork`'s script is scoped to a path; `spawn` and
  `exec` are not, because their command is resolved against PATH and pretending
  to scope that would be a guarantee this cannot keep."
  (guard/guard "processes" (.-processes bridge)))

(def ^js servers
  "The servers clients connect back to: tcp, ws. Whole servers rather than
  sockets — a socket cannot cross, and connections are identified by number.

  Guarded — `:network`. Neither takes a host or a port: the port is the
  operating system's answer, read back off the handle."
  (guard/guard "servers" (.-servers bridge)))

(def ^js sockets
  "Connecting out: connect. Returns a handle carrying write, end and destroy,
  with the socket itself staying on the other side. Data arrives as bytes
  rather than text — a chunk boundary can fall inside a character.

  Guarded — `:network`, with the host checked against the caller's."
  (guard/guard "sockets" (.-sockets bridge)))

(def ^js host
  "Facts about the process this window runs in: appInfo, appDir, cwd, env,
  setEnv, execPath, inspect.

  Guarded per member rather than per group, which is the only place the guard
  does that: `env` and `setEnv` need `:environment`, and the rest of this group
  answers where Light Table is installed and what version of Node it is running
  — questions a plugin asking them has not asked to read anyone's credentials."
  (guard/guard "host" (.-host bridge)))

(def app-dir
  "The directory the application was loaded from — what `js/__dirname` was in
  the window before it stopped having one."
  (.appDir host))

(def app-info
  "Values owned by the browser process that the window reads once at startup.
  Keys are :appPath, :platform, :parsedArgs, :openFiles and :argv."
  (js->clj (.appInfo host) :keywordize-keys true))
