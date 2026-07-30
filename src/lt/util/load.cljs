(ns lt.util.load
  "Provide functions to load js, css and node module assets into LT.

  Everything here goes through [[lt.util.bridge]] rather than through Node
  directly. This namespace is the first one to migrate because it is what the
  rest of Light Table loads through — nothing else can move until it has."
  (:require [clojure.string :as string]
            [lt.objs.plugins.node-modules :as node-modules]
            [lt.util.bridge :as bridge]))

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

(defn js
  "Loads `file`, into Light Table and evaluates it.

  If `sync` is not provided then it defaults to `false`. If `sync` is truthy then `file` will be loaded synchronously."
  ([file] (js file false))
  ([file sync]
   (let [file (if-not (absolute? file)
                (.join bridge/path dir file)
                file)]
     (if sync
       (js/window.eval (-> (.readFileSync bridge/files file)
                           (prep file)))
       (-> (.readFile bridge/files file)
           (.then #(js/window.eval (prep % file))))))))

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
