(ns lt.util.load
  "Provide functions to load js, css and node module assets into LT.

  Everything here goes through [[lt.util.bridge]] rather than through Node
  directly. This namespace is the first one to migrate because it is what the
  rest of Light Table loads through — nothing else can move until it has."
  (:require [clojure.string :as string]
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
  "Requires Light Table's bundled node modules located at `path`.

  Still a raw require, and one of the last: a node module is arbitrary code
  that has to run in the window, so it cannot be served across the bridge. It
  goes away by being bundled at build time instead — see
  doc/context-isolation.md."
  [path]
  (js/require (str dir "/core/node_modules/" path)))

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
