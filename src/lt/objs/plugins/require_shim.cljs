(ns lt.objs.plugins.require-shim
  "Deciding what `require` gives a plugin.

  Published plugins were written against Node and call `require` directly. Once
  `contextIsolation` is on the window has none, so Light Table stands one in:
  it works out which plugin is calling from the call stack, looks up that
  plugin's capabilities, and serves a bridge-backed module — or refuses and
  says why, which is a denial the plugin can see rather than a crash.

  This namespace is the deciding, and nothing else. The modules themselves are
  in [[lt.objs.plugins.node-modules]], which needs the bridge; keeping the two
  apart is what lets the part with the judgement in it be tested.

  What the shim is for is worth being exact about. It is compatibility, and it
  is telling an honest plugin what it may use. It is not containment: a plugin
  runs in the window, so it can reach the bridge directly whatever `require`
  says. What contains a plugin is the bridge's surface — the list in
  src-electron/preload.ts — and that is true before and after this exists.

  Attribution is by stack frame, and it lives in
  [[lt.objs.plugins.attribution]] — it moved there when the bridge needed the
  same answer. Measured at 1.3-2.7µs per stack on Electron 43, against a
  `require` that happens once at plugin load rather than per operation."
  (:require [lt.objs.plugins.attribution :as attribution]
            [lt.objs.plugins.capabilities :as caps]))

;; `frames` and `plugin-for-frames` were here. They are
;; [[lt.objs.plugins.attribution]]'s now, and re-exported rather than inlined at
;; their call sites below because the shim's own tests name them and because a
;; plugin author reading this namespace should not have to follow a require to
;; find out how attribution works.
(def frames attribution/frames)
(def plugin-for-frames attribution/plugin-for-frames)

(defn allowed-for
  "Capabilities `require` will serve to `plugin`.

  A plugin with a manifest gets what it declared. One without gets `infer`'s
  answer — what Light Table reads its code to be doing — which is the
  migration: nothing breaks on the day this turns on, and an author sees the
  same list `:plugins.capabilities` already reports to them. `infer` is called
  only in that case, because reading a plugin costs a walk.

  `nil` — a caller no plugin directory claims — gets everything. That is Light
  Table's own code, which after the flip does not call this at all; until then
  refusing it would break the editor rather than a plugin."
  [plugin infer]
  (cond
    (nil? plugin) caps/known
    (caps/declared plugin) (caps/declared plugin)
    :else (set (infer plugin))))

(defn requirer
  "A `require` over `modules`, for `plugins-fn`'s plugins.

  `modules` maps a module name to `[capability factory]`, where `capability` is
  nil for one that reaches nothing. `plugins-fn` is called per require rather
  than closed over, because plugins are still being installed while plugins are
  loading. `infer` is [[allowed-for]]'s. `local` loads a file the plugin
  shipped — `(local from-dir request requirer)`, nil when it resolves to
  nothing — which is [[lt.objs.plugins.local-modules/require-from]].

  All four are passed in rather than reached for, so that what this decides can
  be tested without an editor around it.

  A name Light Table serves wins over a file, which is Node's rule too: a
  plugin vendoring a directory called `net` still gets the socket capability."
  [modules plugins-fn infer local]
  (letfn [(serve [from-dir module-name]
            (let [[capability factory] (get modules module-name)
                  plugin (plugin-for-frames (plugins-fn) (frames))
                  ;; Relative to the requiring module when there is one, and
                  ;; otherwise to the plugin the stack says is asking.
                  dir (or from-dir (:dir plugin))
                  who (if plugin (str "Plugin '" (:name plugin) "'") "This code")]
              (cond
                (and factory capability (not (contains? (allowed-for plugin infer) capability)))
                (throw (js/Error. (str "Cannot find module '" module-name "': " who
                                       " has not declared the '" (name capability)
                                       "' capability")))

                factory (factory)

                :else
                (or (when dir (local dir module-name #(partial serve %)))
                    (throw (js/Error.
                            (str "Cannot find module '" module-name
                                 "': it is neither one Light Table serves to plugins nor a "
                                 "file " (if dir (str "under " dir) "the caller")
                                 " ships")))))))]
    (partial serve nil)))
