(ns lt.objs.plugins.local-modules
  "CommonJS, for the JavaScript a plugin ships alongside its own.

  Published plugins vendor node_modules. The Javascript plugin requires
  `harbor` out of its own directory to find a free port; that is not a Node
  builtin Light Table could serve a facade for, it is a file on disk that has
  to be read and run. `require` did that, and the window is losing `require`.

  So Light Table does it: resolve the way Node resolves, wrap the source in the
  function CommonJS says wraps it, and run it with `js/window.eval` — the same
  way [[lt.util.load/js]] already runs a plugin's own code, and with the same
  `sourceURL`, so a frame from inside one of these still names the plugin's
  directory and attribution keeps working.

  Worth being clear about what this is not. It reads and runs whatever path it
  is given, which sounds alarming until you notice that this is what `require`
  did and what `load-js` still does — a plugin's code is arbitrary code in the
  window either way. What the capability list governs is what that code can
  reach, and this reaches nothing a plugin did not already ship."
  (:require [clojure.string :as string]
            [lt.util.bridge :as bridge]))

(def ^:private loaded
  "Resolved path to its `module` object. Entered before the module runs, so a
  cycle sees a partial `exports` rather than recurring forever — which is
  exactly what Node does."
  (atom {}))

(defn path?
  "Whether `request` names a file rather than a package: Node treats anything
  beginning with `/`, `./` or `../` as a path, and so does Windows `C:\\`."
  [request]
  (boolean (re-find #"^(?:\.{1,2}/|/|[A-Za-z]:[\\/])" request)))

(defn- file? [path]
  (when-let [stat (.statSync bridge/files path)]
    (.-isFile stat)))

(defn- as-file
  "`path`, `path.js` or `path.json`, whichever exists."
  [path]
  (first (filter file? [path (str path ".js") (str path ".json")])))

(defn- as-directory
  "A directory's entry point: `main` from its package.json, then `index`."
  [dir]
  (let [pkg (.join bridge/path dir "package.json")
        main (when (file? pkg)
               (try (.-main (js/JSON.parse (.readFileSync bridge/files pkg)))
                    (catch :default _ nil)))]
    (or (when main (as-file (.join bridge/path dir main)))
        (as-file (.join bridge/path dir "index")))))

(defn- resolve-as
  [path]
  (or (as-file path) (as-directory path)))

(defn- node-modules-dirs
  "Every node_modules directory Node would look in from `dir`, innermost
  first."
  [dir]
  (loop [dir dir acc []]
    (let [parent (.dirname bridge/path dir)]
      (if (= parent dir)
        acc
        (recur parent (conj acc (.join bridge/path dir "node_modules")))))))

(defn resolve-request
  "The file `request` names when required from `from-dir`, or nil.

  Node's algorithm, minus the parts nothing in a plugin uses: no `exports`
  maps, no conditional resolution, no self-reference."
  [from-dir request]
  (if (path? request)
    (resolve-as (.resolve bridge/path from-dir request))
    (some #(resolve-as (.join bridge/path % request))
          (node-modules-dirs from-dir))))

(defn- run
  "Evaluate `path` as a CommonJS module and return its exports.

  `requirer` builds the `require` the module sees, given the module's own
  directory, so that a relative require inside it resolves from there."
  [path requirer]
  (let [dir (.dirname bridge/path path)
        source (.readFileSync bridge/files path)
        module (js-obj "exports" (js-obj) "id" path "filename" path "loaded" false)]
    (swap! loaded assoc path module)
    (try
      (if (string/ends-with? path ".json")
        (aset module "exports" (js/JSON.parse source))
        (let [;; The CommonJS wrapper, and the sourceURL that makes a frame
              ;; from inside this module name the file it came from.
              wrapper (js/window.eval
                       (str "(function (exports, require, module, __filename, __dirname) {\n"
                            source
                            "\n})\n//# sourceURL=" (js/encodeURI path)))]
          (wrapper (aget module "exports") (requirer dir) module path dir)))
      (aset module "loaded" true)
      (catch :default e
        ;; A module that threw is not a module. Leaving it cached would serve
        ;; half of one to the next caller.
        (swap! loaded dissoc path)
        (throw e)))
    (aget module "exports")))

(defn require-from
  "Load `request` as `from-dir` would see it, or nil when it resolves to
  nothing.

  Nil rather than throwing, so the caller can fall through to Light Table's
  own module table and report a name that is in neither."
  [from-dir request requirer]
  (when-let [path (resolve-request from-dir request)]
    (if-let [module (@loaded path)]
      (aget module "exports")
      (run path requirer))))

(defn forget!
  "Drop everything loaded, so a plugin reloading gets fresh copies."
  []
  (reset! loaded {}))
