(ns lt.util.bridge.guard
  "The bridge, as a permission system.

  [[lt.util.bridge]] is a list of capabilities rather than a re-export of Node,
  which is what makes this possible at all: there are 67 functions to reason
  about instead of the whole standard library. What was missing is that the
  list did not know **who was calling it**. `js/lightTable` is a global, so a
  plugin held to `:files` by the load-time audit was held to nothing once it
  was running.

  This closes that, and the shape of the answer is two questions rather than
  one:

  | | |
  |---|---|
  | may this caller use this capability | every checked function |
  | may it reach *this path* | the 21 that take one |

  The second is the one worth having. Gating who may call `readFile` is worth
  much less if the answer is still the whole disk.

  ## What this is, said plainly

  **Level 2 — a plugin held to its own word.** A plugin runs in the window, so
  it can reach `js/lightTable` before this wraps it, or keep a reference to an
  unwrapped function. This catches drift, honest mistakes and a manifest that
  stopped being true, which is what actually goes wrong in a single-user editor
  with a handful of plugins. It does not stop a plugin that is trying. What
  does is the process boundary, which is level 3, and doc/permissions.md is
  about why that is the right thing to build second rather than first.

  Saying so is load-bearing. A permission system whose limits are not written
  down gets trusted for things it does not do.

  ## The layering

  Nothing here reaches the editor. The tables are the bridge's own shape and
  the policy is *installed* — [[lt.objs.plugins/install-bridge-policy!]] does
  it, because that is where the plugin registry, the manifests and the
  enforcement mode already live. Until it does, every call is allowed, which is
  what the window needs during the several hundred bridge calls it makes before
  a single plugin exists."
  (:require [clojure.string :as string]))

;;*********************************************************
;; What the surface is, as three tables
;;*********************************************************

(def groups
  "Group → the capability that reaching any of its functions implies.

  The same classification as
  [[lt.objs.plugins.capabilities/bridge-surface]], which exists for the
  scanner rather than for this, and a test asserts the two agree. Two tables
  saying the same thing is a smell; two tables *checked* against each other is
  how the omission gets caught, and an omission is this design's failure mode —
  a group nobody classified is a group nobody guards."
  {"shell"     :desktop
   "clipboard" :clipboard
   "files"     :files
   "processes" :processes
   "net"       :network
   "servers"   :network
   "sockets"   :network
   ;; Per member rather than per group — see `members` below. `host` also
   ;; answers appDir, cwd and versions, and a plugin asking where it is
   ;; installed has not asked to read the environment.
   "host"      nil})

(def members
  "`\"group/member\"` → a capability that differs from its group's.

  Only `host` needs this today. It is a table rather than two special cases
  because the alternative is a condition inside the wrapper, and a condition is
  where the next exception hides."
  {"host/env"    :environment
   "host/setEnv" :environment})

(def path-args
  "`\"group/member\"` → which of its arguments are filesystem paths.

  Every entry was read off `src-electron/preload.ts` rather than inferred from
  the name, and a test parses that file and fails when a function taking a
  `path` is missing from here. The failure mode this design has is an
  unguarded argument, so the check is that the table is *complete*, not that it
  is correct.

  Two-path entries are the interesting ones: a copy out of a scope is not half
  allowed, so both ends are checked.

  Counting these turned out to correct doc/permissions.md, which put the
  filesystem group at twelve functions. It is fifteen — the scout counted from
  `lt.util.bridge`'s docstring for `files`, which named three of them and had
  gone stale. The docstring is fixed too."
  {"files/existsSync"         [0]
   "files/readFileSync"       [0]
   "files/readFileBytesSync"  [0]
   "files/readFile"           [0]
   "files/writeFileSync"      [0]
   "files/appendFileSync"     [0]
   "files/mkdirSync"          [0]
   "files/readdirSync"        [0]
   "files/realpathSync"       [0]
   "files/renameSync"         [0 1]
   "files/rmSync"             [0]
   "files/cpSync"             [0 1]
   "files/statSync"           [0]
   "files/watch"              [0]
   "files/extract"            [0 1]

   ;; `open` takes a path or a url and decides by looking. Checked as a path,
   ;; because a string that is not one cannot be under a root and a plugin
   ;; opening `https://…` wants `openExternal` anyway.
   "shell/open"               [0]
   "shell/showItemInFolder"   [0]
   "shell/trashItem"          [0]

   ;; Where a download lands. The url it comes from is in `host-args`.
   "net/download"             [1]

   ;; The script a fork runs. `spawn` and `exec` take a command that is
   ;; resolved against PATH rather than a path, and scoping those by root would
   ;; mean claiming to know what a shell string will do — which is the kind of
   ;; half-guarantee this namespace is trying not to make. `:processes` stays
   ;; the whole of that question.
   "processes/fork"           [0]})

(def host-args
  "`\"group/member\"` → which of its arguments name a host, as a url or a
  hostname.

  Four functions, and the smaller half of the design: a `:network` root is a
  hostname rather than a path, matched on a dot boundary the same way a path is
  matched on a separator — see [[lt.objs.plugins.scopes/host-under?]]."
  {"shell/openExternal" [0]
   "net/download"       [0]
   "sockets/connect"    [1]
   "sockets/listen"     [1]})

(defn capability-for
  "The capability `member` of `group` needs, or nil when reaching it implies
  none."
  [group member]
  (let [k (str group "/" member)]
    (if (contains? members k)
      (get members k)
      (get groups group))))

;;*********************************************************
;; The policy, which arrives from outside
;;*********************************************************

(defonce ^{:doc "How to decide, once something knows. Nil until installed, and
  nil allows everything — see the namespace docstring for why that is the right
  default rather than a hole."}
  policy
  (atom nil))

(defn install!
  "Start checking, using `p`.

  `p` carries four functions, all injected so that nothing in this namespace
  has to know what a plugin is:

  | key | |
  |---|---|
  | `:checking?` | is any loaded plugin constrained at all — the fast path |
  | `:caller` | who is asking, or nil for Light Table's own code |
  | `:verdict` | `[plugin capability paths hosts]` → `:allow`, `:warn` or `:refuse` |
  | `:refused` | tell someone, and return what the refused call should return |"
  [p]
  (reset! policy p))

(defn uninstall!
  "Stop checking. For tests, and for turning it off from the console when a
  denial is in the way of work."
  []
  (reset! policy nil))

(defn- args-at
  "The arguments of `args` at `positions`, dropping any that are absent.

  `nil` rather than a throw for a position that is not there: `rmSync(path)`
  and `rmSync(path, true)` are the same function, and an optional argument is
  not a missing one."
  [args positions]
  (keep (fn [i] (let [v (nth args i nil)]
                  (when (string? v) v)))
        positions))

(defn- guarded-fn
  "`f`, checked.

  The order of the three early exits is the performance design. No policy and
  nothing constrained are both a single deref; only past those does anything
  read a stack, and reading a stack is the only part that costs — 1.3-2.7µs
  against the ~0.4µs a `files/existsSync` itself takes."
  [group member capability path-positions host-positions f]
  (fn [& args]
    (let [p @policy]
      (if (or (nil? p) (not ((:checking? p))))
        (apply f args)
        (let [plugin ((:caller p))]
          (if (nil? plugin)
            ;; Light Table's own code. The editor is the thing granting
            ;; permission, so it is not asking itself for any.
            (apply f args)
            (let [paths (args-at args path-positions)
                  hosts (args-at args host-positions)]
              (case ((:verdict p) plugin capability paths hosts)
                :allow (apply f args)
                :warn (do ((:refused p) plugin capability paths hosts group member false)
                          (apply f args))
                ((:refused p) plugin capability paths hosts group member true)))))))))

(defn guard
  "`obj`, with every function on it checked.

  Built at load rather than by mutating what the preload exposed, and that is
  not a style preference: `contextBridge.exposeInMainWorld` hands the window a
  proxied object whose properties are not reliably writable, so a wrapper that
  assigned over them would work or silently not, depending on the Electron
  version. A facade cannot half-apply.

  Non-function properties are copied as they are — `path/sep` is a string and
  `os/EOL` is a string, and a facade that dropped them would be a facade that
  broke path arithmetic.

  Enumeration is the risk here, so it is checked rather than trusted: a group
  that yields no keys throws at load. A guarded group that silently ended up
  empty would be an editor with no filesystem, which is obvious, but a *partly*
  enumerated one would be a hole, which is not."
  [group ^js obj]
  (let [ks (js-keys obj)]
    (when (zero? (count ks))
      (throw (js/Error. (str "lt.util.bridge.guard: the '" group
                             "' group exposed no enumerable properties, so it "
                             "cannot be guarded. This means contextBridge "
                             "stopped enumerating and every capability in this "
                             "group would be unchecked."))))
    (let [out #js {}]
      (doseq [k ks]
        (let [v (aget obj k)]
          (aset out k
                (if (fn? v)
                  (guarded-fn group k
                              (capability-for group k)
                              (get path-args (str group "/" k) [])
                              (get host-args (str group "/" k) [])
                              ;; Bound to the original object: these are
                              ;; contextBridge proxies and several of them read
                              ;; `this`.
                              (fn [& args] (.apply v obj (to-array args))))
                  v))))
      out)))

(defn describe
  "What is being guarded, as a line for the console.

  `Plugins: Report what each plugin can do` prints this, because \"is the guard
  even on\" is the first question anyone debugging a denial has."
  []
  (str (if @policy "on" "off")
       " · " (count groups) " groups"
       " · " (count path-args) " path-checked functions"
       " · " (count host-args) " host-checked"
       " · groups: " (string/join " " (sort (keys groups)))))
