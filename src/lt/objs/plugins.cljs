(ns lt.objs.plugins
  "Provide plugin manager with ability to search, install (download and unpack),
  remove and update plguins"
  (:require [lt.object :as object]
            [lt.objs.command :as cmd]
            [lt.objs.context :as ctx]
            [lt.objs.console :as console]
            [lt.objs.plugins.edn-format :as edn-format]
            [lt.objs.app :as app]
            [lt.objs.files :as files]
            [lt.objs.settings :as settings]
            [lt.objs.editor.pool :as pool]
            [lt.objs.popup :as popup]
            [lt.objs.deploy :as deploy]
            [lt.objs.notifos :as notifos]
            [lt.objs.tabs :as tabs]
            [lt.util.js :as js-util :refer [wait]]
            [lt.objs.platform :as platform]
            [lt.objs.plugins.attribution :as attribution]
            [lt.objs.plugins.capabilities :as caps]
            [lt.objs.plugins.local-modules :as local-modules]
            [lt.objs.plugins.node-modules :as node-modules]
            [lt.objs.plugins.require-shim :as require-shim]
            [lt.objs.plugins.scopes :as scopes]
            [lt.objs.workspace :as workspace]
            [lt.util.bridge :as bridge]
            [lt.util.bridge.guard :as guard]
            [cljs.reader :as reader]
            [lt.ui :as ui]
            [lt.util.kahn :as kahn]
            [lt.util.load :as load]
            [lt.util.dom :as dom]
            [clojure.string :as string]
            [clojure.walk :as walk])
  (:require-macros [lt.macros :refer [behavior]]))


(def plugins-dir (files/lt-home "plugins"))
(def user-plugins-dir (files/lt-user-dir "plugins"))
(def ^:dynamic *plugin-dir* nil)

(declare manager)
(declare available-plugins)

(defn EOF-read [s]
  (when (and s
             (seq s))
    (reader/read-string s)))

(defn munge-plugin-name [n]
  (when n
    (-> n
        (string/replace " " "_")
        (string/replace "-" "_")
        (string/replace "." "_"))))

(defn adjust-path [path]
  (if (files/absolute? path)
    path
    (files/join (or (::dir object/*behavior-meta*) (files/lt-home)) path)))

(defn find-plugin [plugin-name]
  (let [plugin-name (munge-plugin-name plugin-name)]
    (cond
     (::dir object/*behavior-meta*) (::dir object/*behavior-meta*)
     (files/exists? (files/join user-plugins-dir plugin-name)) (files/join user-plugins-dir plugin-name)
     (files/exists? (files/join plugins-dir plugin-name)) (files/join plugins-dir plugin-name)
     :else nil)))

(defn local-module [plugin-name module-name]
  (when-let [plugin-path (find-plugin plugin-name)]
    (files/join plugin-path "node_modules" module-name)))

(defn by-name [plugin-name]
  (-> @app/app ::plugins (get plugin-name)))

(defn installed? [plugin-name]
  (boolean (by-name plugin-name)))

(cmd/command {:command :build
              :desc "Editor: build file or project"
              :exec (fn []
                      (when-let [ed (pool/last-active)]
                        (object/raise ed :build)))})

(cmd/command {:command :behaviors.force-reload
              :desc "Plugins: Ignore cache and force reload the current behaviors file"
              :exec (fn []
                      (when-let [ed (pool/last-active)]
                        (when (object/has-tag? ed :editor.behaviors)
                          (swap! manager update-in [::force-reload] #(conj (or % #{}) (get-in @ed [:info :path])))
                          (cmd/exec! :behaviors.reload))))})

;;*********************************************************
;; Plugin reading
;;*********************************************************

(defn validate [plugin filename]
  (let [valid? (every? plugin [:name :author :behaviors :desc])]
    (if-not valid?
      (do
        (console/error (str "Invalid " filename " file: " (:dir plugin) "/" filename "\nPlugins "
                            "must include values for name, version, author, behaviors, and desc."))
        nil)
      plugin)))

(defn plugin-edn [dir]
  (let [file (files/join dir "plugin.edn")]
    (when-let [content (and (files/exists? file) (files/open-sync file))]
      (try
        (-> (EOF-read (:content content))
            (assoc :dir dir)
            (validate "plugin.edn"))
        (catch :default e
          (console/error (str "FAILED to load plugin.edn: " dir)))))))

(defn plugin-json [dir]
  (let [file (files/join dir "plugin.json")]
    (when-let [content (and (files/exists? file) (files/open-sync file))]
      (-> (js/JSON.parse (:content content))
          (js->clj :keywordize-keys true)
          (assoc :dir dir)
          (validate "plugin.json")))))

(defn plugin-info [dir]
  (or (plugin-json dir) (plugin-edn dir)))

;;*********************************************************
;; Capabilities
;;
;; A plugin can declare what it needs in plugin.edn. Almost none do, because
;; almost all of them predate the idea — so the same evidence is inferred by
;; reading what a plugin actually loads. That makes the question "what does
;; this plugin want?" answerable for the whole ecosystem today, rather than
;; only for plugins that have been updated.
;;
;; Nothing here denies anything yet. Enforcement without inference would mean
;; every existing plugin breaking on the day it shipped; inference first means
;; authors can see what they would have to declare, and users can see what they
;; are installing, while everything keeps working.
;;*********************************************************

(def ^:private scan-limits
  "Reading a plugin has to stay cheap enough to do on demand. The Clojure
  plugin is about 15MB, nearly all of it a vendored nREPL runner, so the walk
  skips dependency trees and stops rather than growing without bound."
  {:max-files 200
   :max-bytes (* 4 1024 1024)
   :skip-dirs #{"node_modules" ".git" "target" "test" "tests"}})

(defn- plugin-js-files
  "JavaScript under `dir` that could be loaded, bounded by [[scan-limits]].

  Only JavaScript: a plugin's ClojureScript sources are not what runs, and
  judging a plugin by code it does not load would be judging the wrong thing."
  [dir]
  (let [{:keys [max-files skip-dirs]} scan-limits]
    (loop [pending [dir]
           found []]
      (if (or (empty? pending) (>= (count found) max-files))
        (vec (take max-files found))
        (let [cur (first pending)
              entries (or (files/full-path-ls cur) [])
              subdirs (->> entries
                           (filter files/dir?)
                           (remove #(skip-dirs (files/basename %))))
              js (filter #(= "js" (files/ext %)) entries)]
          (recur (concat (rest pending) subdirs) (concat found js)))))))

(defn inferred-capabilities
  "What the plugin in `dir` looks like it needs, with the evidence for each.

  Returns `{:capabilities #{..} :evidence {cap [matched ..]} :files n
            :truncated? bool}`."
  [dir]
  (let [{:keys [max-bytes]} scan-limits
        js (plugin-js-files dir)]
    (loop [remaining js
           read-bytes 0
           found {}
           n 0]
      (if (or (empty? remaining) (> read-bytes max-bytes))
        {:capabilities (set (keys found))
         :evidence found
         :files n
         :truncated? (boolean (seq remaining))}
        (let [file (first remaining)
              content (:content (files/open-sync file))
              hits (caps/evidence content)]
          (recur (rest remaining)
                 (+ read-bytes (count (or content "")))
                 (merge-with #(vec (distinct (concat %1 %2))) found hits)
                 (inc n)))))))

(defn capability-report
  "Declared and inferred capabilities for `plugin`, and where they disagree."
  [plugin]
  (let [{:keys [capabilities evidence truncated?]} (inferred-capabilities (:dir plugin))]
    {:name (:name plugin)
     :declared (caps/declared plugin)
     :used capabilities
     :evidence evidence
     ;; Used but not declared. Empty for a plugin with no manifest: it made no
     ;; claim, so it broke none.
     :undeclared (caps/undeclared plugin capabilities)
     :unknown (caps/unknown plugin)
     :truncated? truncated?}))

(defn- report-line [{:keys [name declared used undeclared unknown]}]
  (str name ": "
       (if declared
         (str "declares " (if (seq declared) (string/join " " (sort declared)) "nothing"))
         "no manifest")
       ", uses " (if (seq used) (string/join " " (sort used)) "nothing")
       (when (seq undeclared)
         (str "  [undeclared: " (string/join " " undeclared) "]"))
       (when (seq unknown)
         (str "  [not a capability: " (string/join " " unknown) "]"))))

(def ^:private enforcement-key :plugins.capability-enforcement)

(defn enforcement
  "How to treat a plugin that uses more than it declared.

  Stored rather than set by a behavior, and the reason is ordering:
  `:object.instant-load` is raised before `:object.instant`, deliberately, so
  that loading a plugin's JavaScript can define the behaviors about to be
  captured. A behavior therefore cannot configure anything the load path
  consults — it would be read after the plugins it was meant to govern had
  already loaded. See [[lt.objs.plugins.capabilities/modes]] for the values."
  []
  (or (caps/modes (keyword (app/fetch enforcement-key))) :warn))

(def ^:private audited
  "Capability reports by plugin directory. Reading a plugin costs a walk and a
  few file reads, and the answer only changes when the plugin does."
  (atom {}))

(defn audit
  "Capability report for `plugin`, computed once per directory."
  [plugin]
  (or (@audited (:dir plugin))
      (let [report (capability-report plugin)]
        (swap! audited assoc (:dir plugin) report)
        report)))

(defn plugin-for-dir
  "The installed plugin whose directory is `dir`."
  [dir]
  (when dir
    (first (filter #(= dir (:dir %)) (vals (::plugins @app/app))))))

(defn allowed-to-load?
  "Whether the plugin in `dir` may load its code, saying so when it may not.

  Unmanifested plugins always pass: they made no claim, so they broke none.
  This is the whole of level 2 — a plugin held to its own word — and it is
  worth being clear that it is not more than that. It reads code with regular
  expressions, so it catches drift and mistakes rather than concealment."
  [dir]
  (if-let [plugin (plugin-for-dir dir)]
    (let [report (audit plugin)]
      (case (caps/verdict (enforcement) report)
        :allow true
        :warn (do (console/error (str "Plugin capabilities: " (caps/describe-violation report)))
                  true)
        :refuse (do (console/error (str "Plugin not loaded: " (caps/describe-violation report)
                                        ". Capability enforcement is set to refuse."))
                    false)))
    true))

(def plugin-require
  "What `require` means to a plugin.

  Wired here because this is where the plugin registry and the audit are; what
  it decides is in [[lt.objs.plugins.require-shim]] and what it serves is in
  [[lt.objs.plugins.node-modules]]."
  (require-shim/requirer node-modules/modules
                         #(::plugins @app/app)
                         #(:used (audit %))
                         local-modules/require-from))

;;*********************************************************
;; The bridge as a permission system
;;*********************************************************

;; Level 2, at call time. `allowed-to-load?` above holds a plugin to its
;; manifest by reading its code once; this holds it while it runs, which is the
;; half that was missing — see doc/permissions.md and
;; [[lt.util.bridge.guard]].
;;
;; Everything here is the *editor's* half of that: which plugins exist, what
;; each declared, and where `:self` and `:workspace` actually are. The guard
;; knows none of it and is handed all four of the functions it needs.

(defonce ^:private constrained
  ;; Whether any loaded plugin could be refused anything, cached against the
  ;; identity of the plugin map it was computed from.
  ;;
  ;; This is the fast path, and it is the reason the guard costs nothing in a
  ;; window with no plugins: attribution reads a stack, a stack costs 1.3-2.7µs,
  ;; and `files/existsSync` itself costs about 0.4µs. Paying for a stack on
  ;; every call in order to discover there was nothing to check would be a
  ;; permission system that made the editor slower for the people not using it.
  (atom {:for ::none :answer false}))

(defn- any-constrained?
  []
  (let [plugins (::plugins @app/app)
        cached @constrained]
    (if (identical? plugins (:for cached))
      (:answer cached)
      (let [answer (boolean (some (fn [plugin]
                                    (or (caps/scoped? plugin)
                                        (when-let [declared (caps/declared plugin)]
                                          (not= caps/known declared))))
                                  (vals plugins)))]
        (reset! constrained {:for plugins :answer answer})
        answer))))

(defn- resolved-path
  "`path`, absolute, and with symlinks resolved as far as it exists.

  The walk is [[lt.objs.plugins.scopes/resolve-through]], which is pure and
  therefore tested; this supplies the two filesystem answers it needs and makes
  the path absolute first, because a relative one resolves against the working
  directory and a scope is not a relative question.

  Both come from [[lt.util.bridge/raw-files]] deliberately. The guarded `files`
  would ask this function whether it may resolve the path it is resolving."
  [path]
  (scopes/resolve-through #(.existsSync bridge/raw-files %)
                          #(.realpathSync bridge/raw-files %)
                          (.resolve bridge/path path)))

(defn plugin-roots
  "Where `plugin` may use `capability`, resolved.

  `:all` stays `:all`. Everything else becomes a list of absolute resolved
  paths, with the three names the manifest may use expanded here because this
  is the only layer that knows what they mean:

  | | |
  |---|---|
  | `:self` | the plugin's own directory |
  | `:workspace` | every folder and file the workspace holds |
  | `~/…` | under the user's home |"
  [plugin capability]
  (let [roots (caps/roots plugin capability)]
    (if (= :all roots)
      :all
      (into []
            (comp (mapcat (fn [root]
                            (case root
                              :self [(:dir plugin)]
                              :workspace (concat (:folders @workspace/current-ws)
                                                 (:files @workspace/current-ws))
                              [(if (string/starts-with? (str root) "~")
                                 (files/home (subs (str root) 1))
                                 (str root))])))
                  (remove nil?)
                  (map resolved-path))
            roots))))

(defn bridge-verdict
  "Whether `plugin` may make this call: `:allow`, `:warn` or `:refuse`.

  Two questions with deliberately different answers, and the difference is the
  one design decision in here worth arguing about.

  **A capability it never declared** is governed by [[enforcement]], the same
  mode `allowed-to-load?` uses, because it is the same claim. Refusing by
  default would break a plugin whose manifest is honestly incomplete, and the
  shipped default has always preferred a warning to that.

  **A path or host outside a root it did declare** is refused whatever the mode
  says. Roots exist only because an author wrote them, so enforcing them is
  keeping a promise rather than second-guessing one — and a root that is not
  enforced is a comment. Nothing in this repository declares roots yet, so this
  is not a break; it is the shape the first one will meet.

  Note what is *not* here: inference. `allowed-to-load?` reads JavaScript with
  regular expressions and can be wrong. This reads a stack and an argument, and
  is a fact about the call being made. That is the argument for eventually
  moving the default, and it is module 4's open question rather than something
  to change quietly."
  [plugin capability paths hosts]
  (let [declared (caps/declared plugin)]
    (cond
      ;; Reaching it implies nothing — path arithmetic, this window's zoom.
      (nil? capability) :allow

      ;; No manifest at all: a plugin that predates them, which is the
      ;; migration `allowed-for` already describes. It claimed nothing, so it
      ;; broke nothing.
      (nil? declared) :allow

      (not (contains? declared capability))
      (case (enforcement)
        :refuse :refuse
        :report :allow
        :warn)

      (and (seq paths)
           (not (scopes/permits? (plugin-roots plugin capability)
                                 (map resolved-path paths))))
      :refuse

      (and (seq hosts)
           (not (scopes/permits-hosts? (caps/roots plugin capability) hosts)))
      :refuse

      :else :allow)))

(defn- bridge-refused
  "Say what was refused, and answer the call.

  Shaped after the shim's refusals on purpose: a denial the plugin can see,
  naming the capability and the path, rather than a stack trace from inside
  `fs`. A plugin author debugging one of these is the common case.

  A warning returns nil and the call proceeds; a refusal throws, because there
  is no honest value to return for `readFileSync` and a nil would be read as an
  empty file."
  [plugin capability paths hosts group member refuse?]
  (let [subject (or (first paths) (first hosts) (str group "/" member))
        line (scopes/describe-denial (:name plugin) capability subject
                                     (if (seq hosts)
                                       (caps/roots plugin capability)
                                       (plugin-roots plugin capability)))]
    (if refuse?
      (do (console/error line)
          (throw (js/Error. (str line " (called " group "/" member ")"))))
      (console/error (str line " — allowed, because capability enforcement is set to "
                          (name (enforcement)) ".")))))

(defn install-bridge-policy!
  "Start checking bridge calls against manifests.

  Called from [[install-node-compatibility!]], which already runs before any
  plugin loads and for the same reason: everything the window does before that
  point is Light Table's own, and a policy installed earlier would be answering
  questions about a registry that is still empty."
  []
  (guard/install!
   {:checking? any-constrained?
    :caller #(attribution/caller-plugin (::plugins @app/app))
    :verdict bridge-verdict
    :refused bridge-refused}))

(defn install-node-compatibility!
  "Put [[plugin-require]] in the window as `require`, and Node's globals with
  it.

  Called before any plugin loads. Both shadow Node's own, deliberately: it is
  how a plugin's Node dependencies get exercised while `contextIsolation` is
  still off, rather than finding out on the day of the flip which ones Light
  Table does not serve. Light Table's own code no longer calls `require` or
  touches a Buffer, so there is nothing left for this to take away."
  []
  (aset js/window "require" plugin-require)
  (doseq [[nm value] (node-modules/globals)]
    (aset js/window nm value))
  ;; The other half of the same decision. `require` is what a plugin written
  ;; before contextIsolation reaches a capability through; the bridge is what
  ;; one written today reaches it through, and until this line the second was
  ;; unguarded.
  (install-bridge-policy!))

(defn set-enforcement!
  "Persist `mode`. Takes effect for plugins loaded from here on, which in
  practice means the next start."
  [mode]
  (app/store! enforcement-key (name mode))
  (notifos/set-msg! (str "Plugin capability enforcement: " (name mode))))

(cmd/command {:command :plugins.set-capability-enforcement
              :desc "Plugins: Set what happens when a plugin exceeds its manifest"
              :exec (fn []
                      (popup/popup!
                       {:header "Plugin capability enforcement"
                        :body [:div
                               [:p "A plugin may declare what it needs. Light Table can check that against what the plugin's code actually does."]
                               [:p (str "Currently: " (name (enforcement)) ".")]
                               [:p "Checking reads JavaScript and can be wrong, so refusing is not the default."]]
                        :buttons [{:label "Report only"
                                   :action #(set-enforcement! :report)}
                                  {:label "Warn (default)"
                                   :action #(set-enforcement! :warn)}
                                  {:label "Refuse to load"
                                   :action #(set-enforcement! :refuse)}
                                  {:label "Cancel"}]}))})

(cmd/command {:command :plugins.capabilities
              :desc "Plugins: Report what each plugin can do"
              :exec (fn []
                      ;; available-plugins rather than the cached ::plugins on
                      ;; app, which the uninstall path notes can be stale. A
                      ;; report is worth a re-read.
                      (let [reports (->> (vals (available-plugins))
                                         (filter :dir)
                                         (mapv capability-report)
                                         (sort-by :name))]
                        (console/log
                         (string/join "\n"
                                      (concat ["Plugin capabilities. Declared comes from :capabilities in"
                                               "plugin.edn; used is inferred from the JavaScript each plugin loads."
                                               ;; First, because "is the guard
                                               ;; even on" is the first question
                                               ;; anyone debugging a denial has,
                                               ;; and the report above is only
                                               ;; about what was *declared*.
                                               (str "Bridge guard: " (guard/describe))
                                               (str "Enforcement: " (name (enforcement))
                                                    " (a capability never declared). A path outside a"
                                                    " declared root is always refused.")
                                               ""]
                                              (map report-line reports))))
                        (notifos/set-msg! (str "Reported on " (count reports) " plugins"))))})

(defn missing-deps [all]
  (let [deps (->> (vals all)
                  (mapcat (comp seq :dependencies)))]
    (-> (reduce (fn [final [name version]]
                  (let [name (cljs.core/name name)]
                    (if-let [cur (or (get all name) (get final name))]
                      (if (deploy/is-newer? (:version cur) version)
                        (assoc! final name {:name name
                                            :version version})
                        final)
                      (assoc! final name {:name name
                                          :version version}))))
                (transient {})
                deps)
        (persistent!)
        (vals)
        (seq))))

(defn outdated? [plugin]
  (let [cached (-> @manager :server-plugins (get (:name plugin)) :latest-version)]
    (if cached
      (deploy/is-newer? (:version plugin) cached))))

(defn plugin-behaviors [plug]
  (when (seq plug)
    (try
     (let [{:keys [behaviors dir]} plug
           file (files/join dir behaviors)
           file (files/real-path file)
           behs (settings/parse-file file)
           force? (get (::force-reload @manager) file)]
       (when force?
         (swap! manager update-in [::force-reload] disj file))
       (when behs
         (walk/prewalk (fn [x]
                         (when (coll? x)
                           (alter-meta! x assoc ::dir dir ::force-reload force?))
                         x)
                       behs)
         behs))
     (catch :default e
       (console/error (str "Could not load behaviors for plugin: " (:name plug)))
       {}))))

(defn plugin-dependency-graph [plugins]
  (into {}
      (for [[nme v] plugins]
        [nme (set (map name (keys (:dependencies v))))])))

(defn find-cycles [cur {:keys [seen root stack graph] :as state}]
  (first (filter identity (for [c (remove seen cur)]
                            (if (= c root)
                              (conj stack c)
                              (find-cycles (get graph c) (-> state
                                                             (update-in [:stack] conj c)
                                                             (update-in [:seen] conj c))))))))

(defn ->cycles [graph]
  (filterv identity
           (for [[root deps] graph
                 :let [stack (find-cycles deps {:seen #{} :stack [root] :graph graph :root root})]]
             stack)))

(defn cycle-desc [cycles]
  (for [cycle cycles]
    [:div
     (reduce str (interpose " => " cycle))]))

;;*********************************************************
;; Metadata
;;*********************************************************

(declare install-failed)

(def metadata-commits "https://api.github.com/repos/LightTable/plugin-metadata/commits")
(def metadata-download "https://api.github.com/repos/LightTable/plugin-metadata/tarball/master")
(def metadata-dir (files/lt-user-dir "metadata"))
(def metadata-cache (files/join metadata-dir "cache.json"))

(defn version-sort [a b]
  (cond
   (= a b) 0
   (deploy/is-newer? a b) -1
   :else 1))

(defn- valid-plugin-dir?
  [path]
  (and (files/dir? path)
       (not (= "script" (files/basename path)))))

(defn build-cache [sha]
  (let [items (filter valid-plugin-dir? (files/full-path-ls metadata-dir))
        cache (into {:__sha sha}
                    (for [plugin items
                          :let [versions (->> (files/full-path-ls plugin)
                                              (filter files/dir?)
                                              (map plugin-info)
                                              (sort-by :version version-sort)
                                              (vec))
                                latest (last versions)]]
                      [(:name latest) {:versions (into {} (map (juxt :version identity) versions))
                                       :latest-version (:version latest)}]
                      ))]
    cache))

(defn save-cache [cache]
  (files/save metadata-cache (js/JSON.stringify (clj->js cache))))

(defn latest-metadata-sha []
  (js-util/fetch-text metadata-commits
             (fn [data]
               (when-let [parsed (try (js/JSON.parse data)
                                   (catch :default e
                                     (console/error (str "Invalid JSON response from " metadata-commits ": " (pr-str data)))))]
                 (let [sha (-> (aget parsed 0)
                               (aget "sha"))]
                   (object/raise manager :metadata.sha sha))))
             ;; The plugin manager refreshes this whenever it opens, and a
             ;; machine with no network reported `TypeError: Failed to fetch`
             ;; with a stack trace into the console every time.
             (deploy/unreachable "the plugin metadata on GitHub" false)))

(defn download-metadata [sha]
  (let [tmp-gz (files/lt-user-dir "metadata-temp.tar.gz")
        tmp-dir (files/lt-user-dir "metadata-temp")]
    (notifos/working "Updating plugin metadata")
    (deploy/download-file metadata-download tmp-gz (fn []
                                                     (deploy/untar tmp-gz tmp-dir
                                                                   (fn []
                                                                     (notifos/done-working)
                                                                     (let [munged-dir (first (files/full-path-ls tmp-dir))]
                                                                       (when munged-dir
                                                                         (when (files/exists? metadata-dir)
                                                                           (files/delete! metadata-dir))
                                                                         (files/move! munged-dir metadata-dir))
                                                                       (files/delete! tmp-dir)
                                                                       (files/delete! tmp-gz)
                                                                       (if munged-dir
                                                                         (do
                                                                           (save-cache (build-cache sha))
                                                                           (notifos/done-working "Plugin metadata updated. ")
                                                                           (object/raise manager :metadata.updated))
                                                                         (install-failed "metadata")))))))))

(defn read-cache []
  (if (files/exists? metadata-cache)
    (-> (files/open-sync metadata-cache)
        (:content)
        (js/JSON.parse)
        (js->clj :keywordize-keys true))))

(defn search-plugins [plugins search]
  (let [search (.toLowerCase search)]
    (filter (fn [plugin]
              (or (> (.indexOf (.toLowerCase (:name plugin "")) search) -1)
                  (> (.indexOf (.toLowerCase (:author plugin "")) search) -1)
                  (> (.indexOf (.toLowerCase (:desc plugin "")) search) -1)))
            plugins)))


(defn latest-version-merge [neue old]
  (let [neue (seq neue)]
    (reduce
     (fn [final [name ver]]
       (if-let [cur-ver (-> name final :version)]
         (if (deploy/is-newer? ver cur-ver)
           (assoc final name ver)
           final)
         (assoc final name ver)))
     old
     neue)))

(defn transitive-deps [plugins [name ver] seen]
  (let [name (keyword name)]
    (if-let [cur (get-in plugins [name :versions (keyword ver)])]
      (let [deps (-> cur :dependencies)
            unique (remove seen (keys deps))
            seen (latest-version-merge {name cur} seen)]
        (reduce
         (fn [seen cur]
           (transitive-deps plugins cur seen))
         seen
         (select-keys deps unique)))
      seen)))


(defn latest-version [plugin]
  (get (:versions plugin) (keyword (:latest-version plugin))))

(defn all-latest [plugins]
  (->> (dissoc plugins :__sha)
       (vals)
       (map latest-version)))

;; (plugin->tar (:Rainbow (transitive-deps (:server-plugins @manager) ["Rainbow" "0.0.8"] {})))
;; (save-cache (build-cache))
;; (object/raise manager :plugin-results (vals (read-cache)))
;; (object/merge! manager {:server-plugins (read-cache)})


;; (build-cache)

;; (download-metadata "foo")

;;*********************************************************
;; Plugin install/uninstall
;;*********************************************************

(defn install-failed [name]
  (when (and (by-name name)
             (not (:version (by-name name))))
    (object/update! app/app [::plugins] dissoc name))
  (notifos/done-working (str "Plugin install failed for: " name)))

(defn plugin->tar [plugin]
  (let [[repo username] (->> (string/split (:source plugin) "/")
                   (reverse)
                   (filter #(not= % ""))
                   (take 2))
        repo (.replace repo #".git" "")]
    (str "https://api.github.com/repos/" username "/" repo "/tarball/" (:version plugin))))

(defn fetch-and-install [url name cb]
  (let [munged-name (munge-plugin-name name)
        tmp-gz (str user-plugins-dir "/" munged-name "-tmp.tar.gz")
        tmp-dir (str user-plugins-dir "/" munged-name "-tmp")]
    (notifos/working (str "Downloading plugin: " name))
    (deploy/download-file url tmp-gz (fn []
                                       (notifos/done-working)
                                       (notifos/working "Extracting plugin...")
                                       (deploy/untar tmp-gz tmp-dir
                                                     (fn []
                                                       (let [munged-dir (first (files/full-path-ls tmp-dir))
                                                             final-path (str user-plugins-dir "/" munged-name "/")]
                                                         (when munged-dir
                                                           (when (files/exists? final-path)
                                                             (files/delete! final-path))
                                                           (files/move! munged-dir final-path))
                                                         (files/delete! tmp-dir)
                                                         (files/delete! tmp-gz)
                                                         (if munged-dir
                                                           (do
                                                             (notifos/done-working (str "Plugin fetched: " name))
                                                             (object/raise manager :plugin.fetched)
                                                             (when cb
                                                               (cb)))
                                                           (install-failed name)))))))))

(defn install-version [plugin cb]
  (let [name (-> plugin :name)
        ver (-> plugin :version)
        installed? (-> @app/app ::plugins (get name))]
    (if (or (not installed?)
            (and (:version installed?)
                 (deploy/is-newer? (:version installed?) ver)))
      (do
        (object/update! app/app [::plugins] assoc name {})
        (fetch-and-install (plugin->tar plugin) name
                           (fn []
                             (when cb
                               (cb true))
                             )))
      (do
        (notifos/set-msg! (str name " is already installed"))
        (when cb
          (cb false))))))

(defn transitive-install [plugin deps cb]
  (let [cur (-> plugin :name keyword)
        others (dissoc deps cur)
        counter (atom (count others))
        count-down (fn []
                     (swap! counter dec)
                     ;;then install the actual plugin
                     (when (<= @counter 0)
                       (install-version (deps cur) (fn [installed?]
                                                     (when cb
                                                       (cb installed?))))))]
    ;;first get and install all the deps
    ;;count them down and then install the real plugin and reload.
    (if (seq others)
      (doseq [[_ dep] others]
        (install-version dep count-down))
      (count-down))))

(defn discover-deps [plugin cb]
  (let [deps (transitive-deps (:server-plugins @manager) [(:name plugin) (:version plugin)] {})]
    (if-not (seq deps)
      (install-failed (:name plugin))
      (transitive-install plugin deps cb))))

(defn install-missing [missing]
  (let [counter (atom (count missing))
        count-down (fn []
                     (swap! counter dec)
                     ;;then install the actual plugin
                     (when (<= @counter 0)
                       (cmd/exec! :behaviors.reload)
                       (object/raise manager :refresh!)
                       (notifos/set-msg! "All missing dependencies installed.")
                       ))]
    ;;first get and install all the deps
    ;;count them down and then install the real plugin and reload.
    (doseq [dep missing]
      (discover-deps dep count-down))))

(defn check-missing
  "Check a plugins map for outdated or missing :dependencies and prompt
  to install missing ones"
  [deps]
  (when-let [missing? (missing-deps deps)]
    (popup/popup! {:header "Some plugin dependencies are missing."
                   :body [:div
                          [:span "We found that the following plugin dependencies are missing: "]
                          (for [{:keys [name version]} missing?]
                            [:div name " " version " "])
                          [:span "Would you like us to install them?"]]
                   :buttons [{:label "Cancel"}
                             {:label "Install all"
                              :action (fn []
                                        (install-missing missing?))}]})))

(defn available-plugins
  "Return a map of plugins by plugin name based on what's read from filesystem"
  []
  (let [ds (concat (files/dirs user-plugins-dir)
                   (files/dirs plugins-dir)
                   [settings/user-plugin-dir])
        plugins (->> ds
                     (map plugin-info)
                     (filterv identity))]
    (-> (reduce (fn [final p]
                  (if-let [cur (get final (:name p))]
                    ;;check if it's newer
                    (if (deploy/is-newer? (:version cur) (:version p))
                      (assoc! final (:name p) p)
                      final)
                    (assoc! final (:name p) p)))
                (transient {})
                plugins)
        (persistent!))))

(defn uninstall [plugin]
  (when (:dir plugin)
    (files/delete! (:dir plugin))
    ;; :ignore-missing b/c uninstalled shows up missing
    (object/raise manager :refresh! :ignore-missing true)
    (notifos/set-msg! (str "Uninstalled " (:name plugin) " " (:version plugin)))))

;;*********************************************************
;; Manager ui
;;*********************************************************

(defn- stop [e]
  (dom/prevent e)
  (dom/stop-propagation e))

(defn- tab [this tab-name label]
  [:button {:class (when (= tab-name (:tab @this)) "active")
            :on {:click (fn [] (object/merge! this {:tab tab-name}))}}
   label])

(defn- tabs-and-search [this]
  [:div.tabs
   (tab this :installed "Installed")
   (tab this :server "Available")
   [:input {:placeholder "Search available plugins"
            :on {:focus (fn [] (ctx/in! :plugin-manager.search this))
                 :blur (fn [] (ctx/out! :plugin-manager.search))}}]])

(defn- source-button [plugin]
  (let [url (:url plugin (:source plugin))]
    [:span.source {:on {:click (fn [e] (stop e) (platform/open-url url))}}
     [:a.plugin-action__label {:href url} "website"]]))

(defn- update! [plugin]
  (discover-deps plugin (fn []
                          (object/raise manager :refresh!)
                          (cmd/exec! :behaviors.reload)
                          ;; Wait for behaviors.reload to write its message
                          (wait 1000 (fn []
                                       (notifos/set-msg! (str "Updated " (:name plugin) " " (:version plugin))))))))

(defn- update-button [plugin]
  ;; The word is here rather than in the stylesheet. The skin drew all three of
  ;; these labels with `content: "update"` on a `:before`, so the only text in
  ;; the button was in CSS — invisible to a screen reader, unreachable by a
  ;; search of the source for the thing you clicked, and impossible to translate.
  ;; The collapsed width plus `overflow:hidden` is what hides it until hover, and
  ;; that is a stylesheet's job; the word is not.
  [:span.update {:on {:click (fn [e] (stop e) (update! plugin))}}
   [:span.plugin-action__label "update"]])

(defn- install-button [plugin]
  ;; The row this is in used to be removed from the DOM by the handler, with
  ;; `this-as` reaching for its own parent. `:refresh!` below is what actually
  ;; makes it go: the available list is drawn minus what is installed, and
  ;; refreshing is what changes that answer.
  [:span.install
   {:on {:click (fn [e]
                  (stop e)
                  (discover-deps plugin
                                 (fn []
                                   (object/raise manager :refresh!)
                                   (cmd/exec! :behaviors.reload)
                                   (wait 1000 (fn []
                                                (notifos/set-msg! (str "Installed " (:name plugin) " " (:version plugin))))))))}}
   [:span.plugin-action__label "install"]])

(defn- plugin-title [plugin]
  (let [url (:url plugin (:source plugin))]
    [:h1
     [:span.link {:on {:click (fn [e] (stop e) (platform/open-url url))}} (:name plugin)]
     [:span.version (:version plugin)]]))

(defn- server-plugin-ui
  "A plugin you could install. `installed` is the manager's copy of what is."
  [installed plugin]
  (let [ver (:version plugin)
        have (get installed (:name plugin))
        update? (and (:version have) (deploy/is-newer? (:version have) ver))]
    [:li {:replicant/key (:name plugin)
          :class (when update? "has-update")}
     (if-not have
       (install-button plugin)
       (if update?
         (update-button plugin)
         [:span.installed]))
     (source-button plugin)
     (plugin-title plugin)
     [:h3 (:author plugin)]
     [:p (:desc plugin)]]))

(defn- uninstall-button [plugin]
  [:span.uninstall
   {:on {:click (fn []
                  (popup/popup! {:header "Uninstall plugin?"
                                 :body [:div "This will delete the plugin from your system, removing any local
                                 changes you may have made, and cannot be undone."]
                                 :buttons [{:label "Delete plugin"
                                            :action (fn [] (uninstall plugin))}
                                           {:label "Cancel"}]}))}}
   [:span.plugin-action__label "uninstall"]])

(defn- installed-plugin-ui [server-plugins plugin]
  (let [cached (-> server-plugins (get (keyword (:name plugin))) :latest-version)
        update? (when cached (deploy/is-newer? (:version plugin) cached))]
    [:li {:replicant/key (:name plugin)
          :class (when update? "has-update")}
     (when update? (update-button (assoc plugin :version cached)))
     (uninstall-button plugin)
     (source-button plugin)
     (plugin-title plugin)
     [:h3 (:author plugin)]
     [:p (:desc plugin)]]))

;;*********************************************************
;; Manager object
;;*********************************************************

(defn- manager-ui
  "Both lists, and which one you are looking at.

  The available list is drawn minus what is installed, and the installed list
  is `(::plugins @app/app)` as `::render-installed-plugins` last read it. Two
  behaviors used to empty a `ul` and append a fragment into it; the lists are
  values on the object now and this is the only thing that draws them."
  [this]
  (let [{:keys [server-results installed server-plugins]} @this]
    (list
     (tabs-and-search this)
     [:ul.server-plugins
      (for [p server-results
            :when (not (installed? (:name p)))]
        (server-plugin-ui installed p))]
     [:ul.plugins
      (for [p (sort-by #(.toUpperCase (str (:name %))) (vals installed))]
        (installed-plugin-ui server-plugins p))])))

(object/object* ::plugin-manager
                :tags #{:plugin-manager}
                :name "Plugins"
                :tab :installed
                :server-results []
                :init (fn [this]
                        (object/merge! this {:server-plugins (read-cache)
                                             :installed (::plugins @app/app)})
                        (ui/node this [:div] manager-ui
                                 (fn [obj]
                                   {:class (str "plugin-manager"
                                                (when (= (:tab @obj) :server) " server"))}))))

(def manager (object/create ::plugin-manager))



;;*********************************************************
;; Manager behaviors
;;*********************************************************

(behavior ::check-local-metadata-cache
          :triggers #{:metadata.sha}
          :desc "Plugin Manager: check local metadata cache for update"
          :reaction (fn [this sha]
                      (if-not (= (-> @this :server-plugins :__sha) sha)
                        (download-metadata sha)
                        (object/raise this :metadata.updated))))

(behavior ::draw-plugins-on-updated
          :triggers #{:metadata.updated}
          :desc "Plugin Manager: draw plugins on metadata update"
          :reaction (fn [this sha]
                      (object/merge! this {:server-plugins (read-cache)})
                      (object/raise this :plugin-results (all-latest (:server-plugins @this)))))

(behavior ::get-latest-metadata-sha
          :triggers #{:fetch-plugins}
          :desc "Plugin Manager: get the latest metadata sha"
          :reaction (fn [this sha]
                      (latest-metadata-sha)))


(behavior ::render-server-plugins
          :triggers #{:plugin-results}
          :desc "Plugin Manager: render plugin results"
          :reaction (fn [this plugins]
                      ;; Kept whole. The view drops what is installed, so
                      ;; installing one takes it out of this list without the
                      ;; list being sent again — which is what the handler
                      ;; removing its own row was standing in for.
                      (object/merge! this {:server-results (vec plugins)})))

(behavior ::search-server-plugins
          :triggers #{:search-plugins!}
          :desc "Plugin Manager: search plugins"
          :reaction (fn [this search]
                      (let [plugins (all-latest (:server-plugins @manager))]
                        (object/raise this
                                      :plugin-results
                                      (if (empty? search)
                                        plugins
                                        (search-plugins plugins search))))))

(defn save-plugins
  "Writes the installed plugins into the user plugin's `:dependencies`.

  This destroyed the file it was writing. `clojure.string/replace` hands a
  replacement *function* the match as a string when the pattern has no capture
  groups, and a **vector of `[match & groups]`** when it has any — and this one
  wrapped its whole alternation in a group it never used. So `(str % \"\\n\")`
  stringified a vector, and the first time anybody opened the plugin manager their
  `User/plugin.edn` became:

  ```
  [\"{\" \"{\"]
  :name \"User[\"\\\",\" \"\\\",\"]
  ```

  Which then explained every symptom after it. `read-string` on that returns the
  leading *vector*, so the next read logged `FAILED to load plugin.edn` and the
  next save threw `Vector's key for assoc must be a number` from the `assoc`
  below — reported as `Invalid behavior: save-user-plugin-dependencies`, which is
  what [[lt.object/raise]] says about a behavior that threw. Three different
  messages, one missing `first`.

  Found by `script/uiscan.sh`, because the second state that opens the plugin
  manager is the one that reads back what the first state wrote."
  [plugin-maps]
  (let [plugin-edn-file (files/join settings/user-plugin-dir "plugin.edn")
        plugin-edn (-> plugin-edn-file files/open-sync :content (settings/safe-read plugin-edn-file))]
    ;; Refuse rather than throw, and say what to do about it. A file already
    ;; corrupted by the bug above cannot be read as a map, and the `assert` this
    ;; used to reach reported that as "User plugin doesn't have a :name" — true,
    ;; unhelpful, and raised on every refresh for ever. Writing into it anyway
    ;; would be the same mistake a second time.
    (if-not (map? plugin-edn)
      (console/error (str "Not writing dependencies: " plugin-edn-file " is not a map. "
                          "Delete it and restart, and a fresh one will be written from the build."))
      (let [plugin-name (:name plugin-edn)
            deps (->> plugin-maps
                      vals
                      (remove #(contains? #{plugin-name} (:name %)))
                      (map (juxt :name :version))
                      (into (sorted-map)))
            plugin-edn-body (pr-str (assoc plugin-edn :dependencies deps))]
        (files/save plugin-edn-file (edn-format/format-edn plugin-edn-body))))))

(behavior ::save-user-plugin-dependencies
          :triggers #{:refresh!}
          :desc "Saves dependencies to user's plugin.edn"
          :reaction (fn [this & opts]
                      ;; Use available-plugins b/c ::plugins aren't always up to date e.g. uninstall
                      (save-plugins (available-plugins))))

(behavior ::render-installed-plugins
          :triggers #{:refresh!}
          :desc "Plugin Manager: refresh installed plugins"
          :reaction (fn [this & {:keys [ignore-missing]}]
                      (object/merge! app/app {::plugins (available-plugins)})
                      (when-not ignore-missing
                        (check-missing (::plugins @app/app)))
                      (object/merge! this {:installed (::plugins @app/app)})))

(behavior ::on-close
          :triggers #{:close}
          :reaction (fn [this]
                      (tabs/rem! this)))

;;*********************************************************
;; Manager commands
;;*********************************************************

(cmd/command {:command :plugin-manager.search
              :hidden true
              :desc "Plugins: Search"
              :exec (fn [term]
                      (let [term (or term
                                     (dom/val (dom/$ :input (object/->content manager))))]
                        (object/merge! manager {:tab :server})
                        (object/raise manager :search-plugins! term)))})

(cmd/command {:command :plugin-manager.refresh
              :desc "Plugins: Refresh plugin list"
              :exec (fn []
                      (object/raise manager :refresh!)
                      (object/raise manager :fetch-plugins))})

(cmd/command {:command :plugin-manager.show
              :desc "Plugins: Show plugin manager"
              :exec (fn []
                      (tabs/add-or-focus! manager)
                      (dom/focus (dom/$ :input (object/->content manager)))
                      (cmd/exec! :plugin-manager.refresh))})

(cmd/command {:command :plugin-manager.update-outdated
              :desc "Plugins: Update all outdated"
              :exec (fn []
                      (let [outdated (filter outdated? (->> @app/app ::plugins vals))
                            names (atom #{})
                            countdown (atom (count outdated))]
                        (doseq [plugin outdated
                                :when (seq outdated)
                                :let [cached (-> @manager :server-plugins (get (:name plugin)) :latest-version)]]
                          (discover-deps (assoc plugin :version cached)
                                         (fn []
                                           (swap! names conj (:name plugin))
                                           (swap! countdown dec)
                                           (object/raise manager :refresh!)
                                           (when (<= @countdown 0)
                                             (cmd/exec! :behaviors.reload)
                                             (notifos/set-msg! (apply str "Updated: "
                                                                      (interpose ", " @names)))))))))})


;;*********************************************************
;; App-level plugin behaviors
;;*********************************************************

(behavior ::init-plugins
          :triggers #{:pre-load}
          :reaction (fn [app]
                      ;; Before anything reads a plugin, and well before one
                      ;; runs: ::load-js is raised on :object.instant-load,
                      ;; which comes later.
                      (install-node-compatibility!)
                      (when-not (files/exists? user-plugins-dir)
                        (files/mkdir user-plugins-dir))
                      (object/raise app/app :create-user-plugin)
                      (object/raise app/app :flatten-map-settings)
                      ;;load enabled plugins
                      (object/merge! app/app {::plugins (available-plugins)})
                      (check-missing (::plugins @app/app))))

(behavior ::behaviors.refreshed-load-keys
          :triggers #{:behaviors.refreshed}
          :reaction (fn []
                      (cmd/exec! :keymaps.reload)))

(behavior ::plugin-behavior-diffs
          :triggers #{:behaviors.diffs.plugin+}
          :reaction (fn [this diffs]
                      (let [plugins (::plugins @this)
                            dep-graph (plugin-dependency-graph plugins)
                            dep-ordered (-> dep-graph
                                            (kahn/kahn-sort)
                                            (seq))
                            mapped (if dep-ordered
                                     (map plugins dep-ordered)
                                     (vals plugins))]
                        (when (and plugins
                                   (not dep-ordered)
                                   (not (::cycle-warned @this)))
                          (object/merge! this {::cycle-warned true})
                          (popup/popup! {:header "There's a cycle in your plugin dependencies."
                                         :body [:div "As a result, we can't come up with an optimal way to load them.
                                                This means there may be unexpected consequences to being loaded out of order.
                                                Here are the plugins causing the cycle: "
                                                (-> dep-graph
                                                    (->cycles)
                                                    (cycle-desc))]
                                         :buttons [{:label "ok"}]}))
                        (concat diffs (mapv plugin-behaviors mapped)))))

(behavior ::plugin-keymap-diffs
          :triggers #{:keymap.diffs.plugin+}
          :reaction (fn [this diffs]
                      (concat diffs (filter identity (mapv settings/parse-key-file (::keymaps @this))))))

(behavior ::load-js
          :triggers #{:object.instant-load}
          :desc "App: Load javascript file(s)"
          :params [{:label "path(s)"}]
          :type :user
          :reaction (fn [this path]
                      (binding [*plugin-dir* (::dir object/*behavior-meta*)
                                load/*force-reload* (::force-reload object/*behavior-meta*)]
                        (let [paths (if (coll? path)
                                      path
                                      [path])]
                          (doseq [path paths]
                            (let [path (adjust-path path)]
                              (when (and (or load/*force-reload*
                                             (not (get (::loaded-files @this) path)))
                                         ;; The only point where refusing means
                                         ;; anything: after this the plugin's
                                         ;; code is in the window.
                                         (allowed-to-load? *plugin-dir*))
                                (try
                                  (load/js path true)
                                  (object/update! this [::loaded-files] #(conj (or % #{}) path))
                                  ;; Reported rather than logged: a plugin that
                                  ;; did not load is exactly the kind of thing
                                  ;; something driving the editor from outside
                                  ;; needs to be able to ask about, and a
                                  ;; console line is not an answer.
                                  (catch :default e
                                    (object/safe-report-error (str "Error loading JS file: " path))
                                    (object/safe-report-error e))))))))))

(behavior ::load-css
          :triggers #{:object.instant}
          :desc "App: Load css file(s)"
          :params [{:label "path(s)"}]
          :type :user
          :reaction (fn [this path]
                      (let [paths (map adjust-path (if (coll? path) path [path]))]
                        (doseq [path paths]
                          (when (or load/*force-reload*
                                    (not (get (::loaded-files @this) path)))
                            (object/update! this [::loaded-files] #(conj (or % #{}) path))
                            (load/css path))))))

(behavior ::load-keymap
          :triggers #{:object.instant}
          :desc "App: Load a keymap file"
          :params [{:label "path"}]
          :type :user
          :reaction (fn [this path]
                      (let [path (adjust-path path)]
                        (if (::keymaps @this)
                          (object/update! this [::keymaps] conj path)
                          (object/merge! this {::keymaps #{path}})))))

(behavior ::check-for-plugin-file
          :triggers #{:create}
          :desc "Plugin: Determine if this is a plugin file"
          :reaction (fn [this]
                      (let [path (-> @this :info :path)
                            plugin-edn (or (files/walk-up-find path "plugin.json") (files/walk-up-find path "plugin.edn"))]
                        (when plugin-edn
                          (object/merge! this {::plugin-path (files/parent plugin-edn)})
                          (object/add-tags this [:plugin.file])))))

;;*********************************************************
;; App-level init
;;*********************************************************

;;This call to tag-behaviors is necessary as there are no behaviors loaded when the
;;app is first run.
(object/tag-behaviors :app [::init-plugins ::plugin-behavior-diffs ::plugin-keymap-diffs])
