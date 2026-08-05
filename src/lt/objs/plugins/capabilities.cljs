(ns lt.objs.plugins.capabilities
  "What a plugin needs, named after what it does.

  A plugin can reach a capability two ways: through Node directly, or through
  Light Table's own privileged namespaces. Surveying twenty published plugins
  showed why that distinction cannot be a manifest's organising principle —
  ten of the twenty require nothing from Node at all, and the Terminal plugin
  is one of them while still spawning processes, because it goes through
  `lt.objs.proc`. A manifest that only covered `require` would have called it
  safe.

  So both routes map onto one name, and the names describe behaviour. That is
  also the more useful boundary: Light Table's API is a chokepoint the editor
  controls, and an ambient `require` is not — which is what lets a manifest be
  enforced before `contextIsolation` is ever turned on.

  This namespace is deliberately dependency-free so it can be tested without an
  editor around it. Walking a plugin lives in [[lt.objs.plugins]], and where a
  capability *reaches* is [[lt.objs.plugins.scopes]] — which is dependency-free
  for the same reason."
  (:require [clojure.string :as string]
            [lt.objs.plugins.scopes :as scopes]))

(def capabilities
  "The capability vocabulary, each with the evidence that implies it.

  Patterns match compiled JavaScript, because that is what a plugin actually
  loads — ClojureScript sources in a plugin's repository do not run. They match
  member access rather than a bare mention, so `goog.require('lt.objs.plugins')`
  on its own is not treated as use of it."
  [{:capability :files
    :desc "Read and write the filesystem"
    :patterns [#"lt\.objs\.files\.[a-zA-Z_]"
               #"lt\.util\.bridge\.(?:raw_)?files"
               #"require\(\s*['\"](?:fs|path)['\"]"]}

   {:capability :processes
    :desc "Start and talk to other programs"
    :patterns [#"lt\.objs\.proc\.[a-zA-Z_]"
               #"lt\.util\.bridge\.processes"
               #"require\(\s*['\"]child_process['\"]"]}

   {:capability :network
    :desc "Open sockets and make network requests"
    :patterns [#"lt\.objs\.clients\.(?:tcp|ws)\.[a-zA-Z_]"
               #"lt\.objs\.deploy\.[a-zA-Z_]"
               #"lt\.util\.bridge\.(?:net|sockets|servers)"
               #"require\(\s*['\"](?:net|http|https|tls|dns)['\"]"]}

   {:capability :desktop
    :desc "Open files and links outside Light Table"
    :patterns [#"lt\.objs\.platform\.(?:open|show_item)"
               #"lt\.util\.bridge\.shell"]}

   {:capability :clipboard
    :desc "Read and write the clipboard"
    :patterns [#"lt\.objs\.platform\.(?:copy|paste)"
               #"lt\.util\.bridge\.clipboard"]}

   {:capability :environment
    :desc "Read and change environment variables"
    ;; Its own capability rather than folded into :processes, because the two
    ;; reasons to want it are not the same. Setting PATH so a spawned compiler
    ;; can be found is ordinary; reading the environment is reading whatever
    ;; credentials the user started Light Table with, and a plugin doing that
    ;; is worth saying out loud.
    ;;
    ;; Matched per member, not per namespace: `host` also answers appDir, cwd
    ;; and versions, and a plugin asking where it is installed has not asked
    ;; for this.
    :patterns [#"lt\.util\.bridge\.host\.(?:env|setEnv)"
               #"lt\.objs\.proc\.custom_env"]}

   {:capability :worker
    :desc "Run work on the background thread"
    :patterns [#"lt\.objs\.thread\.[a-zA-Z_]"]}

   {:capability :plugins
    :desc "Install, update or inspect other plugins"
    :patterns [#"lt\.objs\.plugins\.[a-zA-Z_]"]}])

(def known
  "Every capability name a manifest may declare."
  (into #{} (map :capability) capabilities))

(def bridge-surface
  "Every namespace `lt.util.bridge` exposes, and the capability that reaching
  it implies — nil where reaching it implies none.

  This exists to close a hole rather than to be read. Inference started by
  looking for `require` and for `lt.objs.*`, which is how a plugin written
  before contextIsolation reaches a capability. A plugin written now goes
  straight to the bridge, and three of those namespaces were invisible to the
  scanner until the first plugin that used one was written — which is a bad way
  to find out.

  So the surface is enumerated here and checked against `lt.util.bridge` by a
  test. A capability added to the bridge without being classified fails that
  test, which is the point: the omission is the failure mode, and an omission
  cannot be caught by a pattern nobody wrote."
  {"shell"     :desktop
   "clipboard" :clipboard
   "files"     :files
   ;; The unguarded filesystem, which exists so that the policy can resolve a
   ;; path without asking itself for permission to resolve it. Classified the
   ;; same as `files` and matched by the same patterns, because a plugin that
   ;; found this name would be reaching the filesystem by a route with no check
   ;; on it — which is exactly the thing worth reporting.
   "raw-files" :files
   "processes" :processes
   "net"       :network
   "servers"   :network
   "sockets"   :network
   ;; Per member — see the :environment patterns above.
   "host"      :environment

   ;; Reaching these implies nothing, and each for a stated reason rather than
   ;; because nobody got to it:
   "path"      nil ;; string arithmetic; touches no filesystem
   "os"        nil ;; the line ending and which drive letters exist
   "zoom"      nil ;; this window's zoom factor
   "window"    nil ;; this window, and only ever itself — no method takes an id
   "menu"      nil ;; native menus, described as data
   "dialog"    nil ;; asks the user for a path; reading it needs :files
   "bridge"    nil ;; the root object the rest hang off
   "app-dir"   nil ;; where Light Table is installed
   "app-info"  nil});; platform, argv and the like, read once at startup

(defn describe
  "Human-readable description of `capability`, or nil if it is not one."
  [capability]
  (some #(when (= capability (:capability %)) (:desc %)) capabilities))

(defn scan
  "The capabilities `text` shows evidence of, as a set."
  [text]
  (if-not (string? text)
    #{}
    (into #{}
          (comp (filter (fn [{:keys [patterns]}]
                          (boolean (some #(re-find % text) patterns))))
                (map :capability))
          capabilities)))

(defn evidence
  "Like [[scan]], but a map of capability to the matched text that implied it.

  Reporting `lt.objs.proc.exec` rather than `:processes` is the difference
  between a plugin's author being able to check a finding and having to take it
  on faith."
  [text]
  (if-not (string? text)
    {}
    (reduce (fn [acc {:keys [capability patterns]}]
              (if-let [hits (seq (distinct (keep #(re-find % text) patterns)))]
                (assoc acc capability (vec hits))
                acc))
            {}
            capabilities)))

(defn declared
  "The capability set a plugin declares, or nil when it declares nothing.

  Nil and the empty set mean different things: nil is a plugin that predates
  manifests, and `#{}` is one that asserts it needs nothing.

  Two manifest forms reach here and both answer this question the same way — a
  set of names, and a map from a name to where it may reach. Which one a plugin
  wrote is [[lt.objs.plugins.scopes]]'s business; every existing caller of this
  only wants the names, and gets them unchanged."
  [plugin]
  (scopes/declared-capabilities (:capabilities plugin)))

(defn roots
  "Where `plugin` may use `capability`, as `:all` or a vector of roots.

  Unresolved: `:self` and `:workspace` are still keywords here and `~` is still
  a tilde. Resolving them needs the filesystem and this namespace does not have
  one — see [[lt.objs.plugins/plugin-roots]]."
  [plugin capability]
  (scopes/declared-roots (:capabilities plugin) capability))

(defn scoped?
  "Has `plugin` narrowed any capability below `:all`?

  What the bridge's fast path asks about every loaded plugin before it reads a
  stack."
  [plugin]
  (scopes/scoped? (:capabilities plugin)))

(defn undeclared
  "Capabilities a plugin uses without declaring them.

  Empty for an unmanifested plugin: it claimed nothing, so it broke no claim.
  Judging those is [[declared]]'s caller's problem, not this function's."
  [plugin used]
  (if-let [caps (declared plugin)]
    (into (sorted-set) (remove caps) used)
    (sorted-set)))

(def modes
  "What Light Table does when a plugin uses more than it declared.

  `:report` — say nothing at load; the report command still tells you.
  `:warn`   — say so on the console, and load the plugin anyway.
  `:refuse` — do not load the plugin's code.

  `:warn` is the default, and the reason is worth stating: inference reads
  JavaScript with regular expressions, so it can be wrong, and a false positive
  that refuses to load a working plugin is a worse failure than a warning
  nobody reads. `:refuse` is there for anyone who wants it, and it is what
  level 3 does by construction rather than by cooperation."
  #{:report :warn :refuse})

(defn violation?
  "True when `report` shows a plugin exceeding or misstating its manifest."
  [report]
  (boolean (or (seq (:undeclared report)) (seq (:unknown report)))))

(defn verdict
  "What to do about `report` under `mode`: `:allow`, `:warn` or `:refuse`.

  Note what this can and cannot do. It checks a plugin against its own
  declaration by reading its code, which catches drift, mistakes, and a
  manifest that stopped being true — the things that actually go wrong. It is
  not a sandbox: nothing stops a plugin reaching `lt.objs` through a computed
  name, and a plugin that wanted to hide would. Enforcement that holds against
  someone trying is the process boundary, which is level 3."
  [mode report]
  (cond
    (not (violation? report)) :allow
    (= mode :refuse) :refuse
    (= mode :report) :allow
    :else :warn))

(defn- names
  "Capability names as prose: `clipboard files`, not `:clipboard :files`."
  [caps]
  (string/join " " (map name (sort caps))))

(defn describe-violation
  "One line explaining why `report` is a violation, or nil when it is not."
  [report]
  (when (violation? report)
    (let [parts (cond-> []
                  (seq (:undeclared report))
                  (conj (str "declares "
                             (if (seq (:declared report)) (names (:declared report)) "nothing")
                             " but uses " (names (:undeclared report))))

                  (seq (:unknown report))
                  (conj (str "declares " (names (:unknown report)) ", which "
                             (if (= 1 (count (:unknown report)))
                               "is not a capability"
                               "are not capabilities"))))]
      (str (:name report) " " (string/join ", and " parts)))))

(defn unknown
  "Names a plugin declared that are not capabilities — typos, or a manifest
  written against a newer Light Table."
  [plugin]
  (into (sorted-set) (remove known) (declared plugin)))
