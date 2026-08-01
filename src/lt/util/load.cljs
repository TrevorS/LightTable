(ns lt.util.load
  "Provide functions to load js, css and node module assets into LT.

  Everything here goes through [[lt.util.bridge]] rather than through Node
  directly. This namespace is the first one to migrate because it is what the
  rest of Light Table loads through — nothing else can move until it has."
  (:require [clojure.string :as string]
            [lt.objs.plugins.node-modules :as node-modules]
            [lt.util.bridge :as bridge]
            [lt.util.load.compiled :as compiled]))

(def dir "Directory where Light Table is being executed." (str bridge/app-dir "/.."))

(def ^:dynamic *force-reload* "When true, various parts of Light Table will reload."
  false)

(def separator
  "Current platform-specific file separator."
  (.-sep bridge/path))

(defn absolute?
  "True if `path` is formatted as an absolute filepath. False otherwise.
  Does not check if `path` exists or is otherwise valid.

  Example:
  ```
  (absolute? \"/foo/bar/baz\")     ;;=> true

  (absolute? \"/foo/bar/baz.txt\") ;;=> true

  (absolute? \"./foo/bar\")        ;;=> false

  (absolute? \"foo/bar\")          ;;=> false
  ```"
  [path]
  (boolean (re-seq #"^\s*[\\\/]|([\w]+:[\\\/])" path)))

(defn node-module
  "A node module Light Table ships, by name.

  The same table [[lt.objs.plugins.require-shim]] serves plugins from, because
  this is the other door onto it: a ClojureScript plugin calls this where a
  JavaScript one calls `require`. Served without a capability check, and
  deliberately — what governs the ClojureScript API is the manifest audit,
  which reads calls like this one and reports them.

  It used to read the module off disk with a real `require`. It cannot any
  more, and neither can anything else in the window.

  Nothing in this repository calls it. It is kept because published plugins do
  — `Clojure/clojure_compiled.js` reaches for `bencode` and `shelljs` through
  it, `Javascript/javascript_compiled.js` for `shelljs` — and those ship as
  precompiled JavaScript that no build here rebuilds. Removing it would break
  them in a user's session with nothing failing first."
  [path]
  (if-let [factory (second (get node-modules/modules path))]
    (factory)
    (throw (js/Error. (str "Cannot load node module '" path
                           "': it is not one Light Table bundles.")))))

(defn- abs-source-mapping-url
  "Converts source mapping to use absolute paths for URLs. Also converts `\\` to `/` in order to maintain compatibility with Windows."
  [code file]
  (if-let [path-to-source-map (second (re-find #"\n//# sourceMappingURL=(.*\.map)" code))]
    (if-not (absolute? path-to-source-map)
      (let [abs-path-to-source-map (string/replace (.join bridge/path (.dirname bridge/path file) path-to-source-map) "\\" "/")
            abs-path-to-source-map (if (= separator "\\")
                                     (str "/" abs-path-to-source-map)
                                     abs-path-to-source-map)]
        (string/replace-first code #"\n//# sourceMappingURL=.*" (str "\n//# sourceMappingURL=" (js/encodeURI abs-path-to-source-map))))
      code)
    code))

(defn- prep [code file]
  (-> code
      (abs-source-mapping-url file)
      (str "\n\n//# sourceURL="  (js/encodeURI file))))

(defn- defined-here?
  "Whether `name` is a global of the running bundle. A hoisted constant is a
  top-level `var` of an unwrapped `:simple` build, so it is one."
  [name]
  (not (undefined? (aget js/globalThis name))))

(defn- check-build!
  "Throws if `code` was compiled against a different build of Light Table.

  See [[lt.util.load.compiled]] for what that means and why the error it
  replaces is worth replacing. The check belongs here because this is the one
  door compiled ClojureScript comes through, and refusing code that cannot run
  in this window is the same job as running it."
  [code file]
  (when-let [missing (seq (compiled/mismatched-constants code defined-here?))]
    (throw (js/Error. (str (.basename bridge/path file)
                           " was compiled against a different build of Light Table"
                           " (it needs " (first missing) ", which this build does not have)."
                           " Update the plugin, or rebuild it if it is yours.")))))

(defn- eval-js [code file]
  (let [code (prep code file)]
    (check-build! code file)
    (js/window.eval code)))

(defn js
  "Loads `file`, into Light Table and evaluates it.

  If `sync` is not provided then it defaults to `false`. If `sync` is truthy then `file` will be loaded synchronously."
  ([file] (js file false))
  ([file sync]
   (let [file (if-not (absolute? file)
                (.join bridge/path dir file)
                file)]
     (if sync
       (eval-js (.readFileSync bridge/files file) file)
       (-> (.readFile bridge/files file)
           (.then #(eval-js % file)))))))

(defn css
  "Loads `file` into Light Table as CSS. Returns the resulting link."
  [file]
  (let [link (js/document.createElement "link")]
    (set! (.-type link) "text/css")
    (set! (.-rel link) "stylesheet")
    (set! (.-href link) (if (absolute? file)
                          (str "file://" file)
                          file))
    (js/document.head.appendChild link)
    link))


(defn obj-exists?
  "When string `s` corresponds to a Javascript object already existing in Light Table then return the found object."
  [s]
  (loop [parts (string/split s ".")
         cur js/window]
    (if-not (first parts)
      cur
      (if-let [cur (aget cur (first parts))]
        (recur (rest parts) cur)))))

(def provided
  "An empty Javascript object."
  #js {})

(defn provided-ancestors
  "Return the number of ancestors of `parent`."
  [parent]
  (count (.filter (js/Object.keys provided) #(> (.indexOf % parent) -1))))

(defn only-ancestors?
  "True if the number of keys is less than or equal to the number of ancestors found."
  [cur s]
  (<= (.-length (js/Object.keys cur)) (provided-ancestors s)))

(defn provided?
  "No usage was found in Light Table core and is a candidate for deprecation. Do not use."
  [s]
  (if *force-reload*
    false
    (let [res (if (aget provided s)
                true
                (when-let [cur (obj-exists? s)]
                  (not (only-ancestors? cur s))))]
      (aset provided s true)
      res)))
