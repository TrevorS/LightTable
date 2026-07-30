(ns lt.objs.files
  "Provide fns for doing file related operations. A number of fns
  use the node [fs library](https://nodejs.org/api/fs.html) or [path library](https://nodejs.org/api/path.html)."
  (:refer-clojure :exclude [open exists? resolve])
  (:require [lt.object :as object]
            [lt.util.load :as load]
            [clojure.string :as string]
            [lt.objs.platform :as platform]
            [lt.util.bridge :as bridge]
            [lt.util.js])
  (:require-macros [lt.macros :refer [behavior]]))


(def ^:private data-path (platform/get-data-path))

(defn- typelist->index [cur types]
  (let [full (map (juxt :name identity) types)
        ext (for [cur types
                  ext (:exts cur)]
              [ext (:name cur)])]
    {:types (into (:types cur {}) full)
     :exts (into (:exts cur {}) ext)}))

(defn join
  "Join path segments with the platform separator."
  [& segs]
  (apply (.-join bridge/path) (filter string? (map str segs))))

(def ignore-pattern
  "Regex pattern consisting of files, folders, etc... to ignore."
  #"(^\..*)|\.class$|target/|^[_.]svn$|^CVS$|^\.hg$|^\.git$|\.pyc|~|\.swp|\.jar|.DS_Store")

(declare files-obj)

(behavior ::file-types
          :triggers #{:object.instant}
          :type :user
          :desc "Files: Associate file types"
          :params [{:label "types"
                    :example "[{:exts [:wisp],\n  :mime \"text/x-clojurescript\",\n  :name \"Wisp\",\n  :tags [:editor.wisp]}]"}]
          :reaction (fn [this types]
                      (object/merge! files-obj (typelist->index @files-obj types))))

(behavior ::file.ignore-pattern
          :triggers #{:object.instant}
          :type :user
          :exclusive true
          :desc "Files: Set ignore pattern"
          :params [{:label "pattern"
                    :example "\"\\\\.git|\\\\.pyc\""}]
          :reaction (fn [this pattern]
                      (set! ignore-pattern (js/RegExp. pattern))))

(behavior ::open-failed
          :triggers #{:files.open.error}
          :reaction (fn [this path e]
                      ;; Do not log stacktrace because it would be too much noise if multiple file openings fail
                      (js/lt.objs.console.error (str "Failed to open path '" path "' with error: " e))))

(def files-obj (object/create (object/object* ::files
                                                        :tags [:files]
                                                        :exts {}
                                                        :types {})))

(def line-ending "Current platform-specific line ending." (.-EOL bridge/os))
(def separator "Current platform-specific file separator." (.-sep bridge/path))
(def ^:private available-drives #{})
(def cwd "Directory process is started in." (.cwd ^js (.-host bridge/bridge)))

;; Was a shell-out to wmic. The bridge answers "which drives exist" instead,
;; which is the question this was asking — see os.drives in the preload.
(when (= separator "\\")
  (-> (.drives bridge/os)
      (.then #(set! available-drives (into #{} %)))))

(defn basename
  "Extracts the basename of the `path`, typically the end of the path.

  If `ext` is provided then the result returned will not contain the extension.

  Example:
  ```
  (basename \"/foo/bar/baz.txt\")
  ;;=> \"baz.txt\"

  (basename \"/foo/bar/baz.txt\" \".txt\")
  ;;=> \"baz\"
  ```"
  ([path] (.basename bridge/path path))
  ([path ext] (.basename bridge/path path ext)))

(defn get-roots
  "Example:
  ```
  (get-roots) ;;=> #{\"/\"}
  ```"
  []
  (if (= separator "\\")
    available-drives
    #{"/"}))

(defn- get-file-parts
  "Returns the exploded basename of `path` with each element after the first split on `.`.

  Example:
  ```
  (get-file-parts \"./foo/bar/\")
  ;;=> [\"bar\"]

  (get-file-parts \"./foo/bar/bas.txt.md.clj\")
  ;;=> [\"bas.txt.md.clj\" \"txt.md.clj\" \"md.clj\" \"clj\"]
  ```"
  [path]
  (let [filename (basename path)
        file-parts (string/split filename #"\.")]
    (loop [parts file-parts
           acc []]
      (if (empty? parts)
        acc
        (recur (rest parts) (conj acc (string/join "." parts)))))))

(defn ext
  "Returns the last extention of `path`, without the leading `.`, determined by the final `.` of the path.

  Example:
  ```
  (ext \"foo.txt\")         ;;=> \"txt\"

  (ext \"foo/bar.txt.tar\") ;;=> \"tar\"

  (ext \"foo.\")            ;;=> \"\"

  (ext \"foo\")             ;;=> \"\"
  ```"
  [path]
  (subs (.extname bridge/path path) 1))

(defn without-ext
  "Returns the `path`, but without the last extension, determined by the final `.` of the path.

  Example:
  ```
  (without-ext \"foo.txt\")         ;;=> \"foo\"

  (without-ext \"foo/bar.txt.tar\") ;;=> \"foo/bar.txt\"

  (without-ext \"foo.\")            ;;=> \"foo\"

  (without-ext \"foo\")             ;;=> \"foo\"
  ```"
  [path]
  (let [i (.lastIndexOf path ".")]
    (if (> i 0)
      (subs path 0 i)
      path)))

(defn- ext->type
  "Extracts type information based on `ext`, which must be a keyword.

  Example:
  ```
  (ext->type :txt)
  ;;=> {:exts [:txt], :mime \"plaintext\", :tags [:editor.plaintext], :name \"Plain Text\"}

  (ext->type :cljs)
  ;;=> {:exts [:cljs], :mime \"text/x-clojurescript\", :tags [:editor.cljs :editor.clojurescript], :name \"ClojureScript\"}

  (ext->type :clj)
  ;;=> {:exts [:clj], :mime \"text/x-clojure\", :tags [:editor.clj :editor.clojure], :name \"Clojure\"}
  ```"
  [ext]
  (let [exts (:exts @files-obj)
        types (:types @files-obj)]
    (-> exts (get ext) types)))

(defn ext->mode
  "Extracts the `:mime` information from `ext`, which must be a keyword.

  Example:
  ```
  (ext->mode :txt)  ;;=> \"plaintext\"

  (ext->mode :cljs) ;;=> \"text/x-clojurescript\"

  (ext->mode :clj)  ;;=> \"text/x-clojure\"
  ```"
  [ext]
  (:mime (ext->type ext)))

(defn path->type
  "Given a `path`, returns type information if a file. Returns an empty string if `path` is a directory.

  Example:
  ```
  (path->type \"/foo/bar/baz.txt\")
  ;;=> {:exts [:txt], :mime \"plaintext\", :tags [:editor.plaintext], :name \"Plain Text\"}

  (path->type \"foo.cljs\")
  ;;=> {:exts [:cljs], :mime \"text/x-clojurescript\", :tags [:editor.cljs :editor.clojurescript], :name \"ClojureScript\"}

  (path->type \"foo.clj\")
  ;;=> {:exts [:clj], :mime \"text/x-clojure\", :tags [:editor.clj :editor.clojure], :name \"Clojure\"}

  (path->type \"/foo/bar/\")
  ;;=> \"\" ; No type information is returned as it is a directory.
  ```"
  [path]
  (->> path
       get-file-parts
       (map #(ext->type (keyword %)))
       (remove nil?)
       first))

(defn path->mode
  "Given a `path`, returns mime information.

  Example:
  ```
  (path->mode \"/foo/bar/baz.txt\") ;;=> \"plaintext\"

  (path->mode \"foo.cljs\")         ;;=> \"text/x-clojurescript\"

  (path->mode \"foo.clj\")          ;;=> \"text/x-clojure\"

  (path->mode \"foo\")              ;;=> \"\"
  ```"
  [path]
  (->> path
       get-file-parts
       (map #(ext->mode (keyword %)))
       (remove nil?)
       first))

(defn- determine-line-ending [text]
  (let [text (subs text 0 1000)
        rn (re-seq #"\r\n" text)
        n (re-seq #"[^\r]\n" text)]
    (cond
      (and rn n) line-ending
      (and (not rn) (not n)) line-ending
      (not n) "\r\n"
      :else "\n")))

(defn exists?
  "True if `path` exists on filesystem."
  [path]
  (.existsSync bridge/files path))

(defn stats
  "Facts about `path`, or nil when it does not exist.

  Was an [fs.Stats](https://nodejs.org/api/fs.html#fs_class_fs_stats), which
  carried isDirectory and isFile as methods. It is plain data now — a prototype
  does not survive the crossing into the window — with :isDirectory, :isFile,
  :size, :mode and :mtimeMs as properties."
  [path]
  (.statSync bridge/files path))

(defn dir?
  "True if `path` corresponds to a directory that exists."
  [path]
  (when-let [stat (.statSync bridge/files path)]
    (.-isDirectory stat)))

(defn file?
  "True if `path` corresponds to a file that exists."
  [path]
  (when-let [stat (.statSync bridge/files path)]
    (.-isFile stat)))

(defn absolute?
  "True if `path` is formatted as an absolute filepath. False otherwise.
  Does not check if `path` exists or otherwise valid.

  Example:
  ```
  (absolute? \"/foo/bar/baz\")     ;;=> true

  (absolute? \"/foo/bar/baz.txt\") ;;=> true

  (absolute? \"./foo/bar\")        ;;=> false

  (absolute? \"foo/bar\")          ;;=> false
  ```"
  [path]
  (boolean (re-seq #"^[\\\/]|([\w]+:[\\\/])" path)))

(defn writable?
  "Returns 7, 6, 3, or 2 based on file permissions. `path` must exist."
  [path]
  (let [perm (-> (.-mode ^js (.statSync bridge/files path))
                 (.toString 8)
                 (js/parseInt 10)
                 (str))
        perm (subs perm (- (count perm) 3))]
    (#{"7" "6" "3" "2"} (first perm))))

(defn resolve
  "See [path.resolve](https://nodejs.org/api/path.html#path_path_resolve_path).

  Example:
  ```
  (resolve \"/\" \"/home/user\")   ;;=> \"/home/user\"

  (resolve \"/foo\" \"./bar/baz\") ;;=> \"/foo/bar/baz\"

  (resolve \"./\" \"builds\")      ;;=> \"/home/user/dev/LightTable/builds\"
  ```"
  [base cur]
  (.resolve bridge/path base cur))

(defn real-path
  "Returns the canonicalized absolute pathname, expanding symbolic links.

  Example:

  Assume current directory is `/foo/bar/` and `/foo/bar/baz` exists too.
  ```
  (real-path \"./\")           ;;=> \"/foo/bar/\"

  (real-path \".././bar/baz\") ;;=> \"/foo/bar/baz/\"
  ```"
  [c]
  (.realpathSync bridge/files c))

(defn- ->file|dir
  "If `path` and `f` together form a valid directory, then `f` is returned as a directory. Otherwise, `f` is returned as a file

  Example:
  ```
  (->file|dir \"/foo/\" \"bar.txt\") ;;=> \"bar.txt\"

  (->file|dir \"./foo/\" \"bar/\")   ;;=> \"bar/\"
  ```"
  [path f]
  (if (dir? (str path separator f))
    (str f separator)
    (str f)))

(defn- bomless-read
  "Reads file at `path`, removes occurrences of `\uFEFF`, then returns modified result."
  [path]
  (let [content (.readFileSync bridge/files path "utf-8")]
    (string/replace content "\uFEFF" "")))

(defn open
  "Open file and in callback return map with file's content in `:content`"
  [path cb]
  (try
    (let [content (bomless-read path)]
      (when content
        (let [e (ext path)]
          (cb {:content content
               :line-ending (determine-line-ending content)
               :type (or (path->mode path) e)})
          (object/raise files-obj :files.open content))
        ))
    (catch :default e
      (object/raise files-obj :files.open.error path e)
      (when cb (cb nil e)))))

(defn open-sync
  "Open file and return map with file's content in `:content`."
  [path]
  (try
    (let [content (bomless-read path)]
      (when content
        (let [e (ext path)]
          (object/raise files-obj :files.open content)
          {:content content
           :line-ending (determine-line-ending content)
           :type (or (ext->mode (keyword e)) e)}))
        )
    (catch :default e
      (object/raise files-obj :files.open.error path e)
      nil)))

(defn save
  "Save `path` with given `content`. Optional callback called after save."
  [path content & [cb]]
  (try
    (.writeFileSync bridge/files path content)
    (object/raise files-obj :files.save path)
    (when cb (cb))
    (catch :default e
      (object/raise files-obj :files.save.error path e)
      (when cb (cb e)))))

(defn append
  "Append `content` to `path`. Optional callback called after append."
  [path content & [cb]]
  (try
    (.appendFileSync bridge/files path content)
    (object/raise files-obj :files.save path)
    (when cb (cb))
    (catch :default  e
      (object/raise files-obj :files.save.error path e)
      (when cb (cb e)))))

(defn trash!
  "Move file to trash. Returns a promise that resolves once the move completes
  and rejects if it fails."
  [path]
  ;; Was .moveItemTotrash — a typo, so this never resolved to a real method even
  ;; before Electron replaced moveItemToTrash with the promise-based trashItem.
  (.trashItem bridge/shell path))

(defn delete!
  "Delete file or directory from filesystem."
  [path]
  (.rmSync bridge/files path true))

(defn move!
  "Move file or directory to given `path`."
  [from to]
  (.renameSync bridge/files from to))

(defn copy
  "Copy file or directory `from` to the path `to`. `to` is the destination
  itself, not a directory to place the copy inside."
  [from to]
  (if (dir? from)
    (.cpSync bridge/files from to true)
    (save to (:content (open-sync from)))))

(defn mkdir
  "Make given directory."
  [path]
  (.mkdirSync bridge/files path))

(defn parent
  "Return directory of `path`."
  [path]
	(.dirname bridge/path path))

(defn next-available-name
  "Given a `path`, if it already exists then append a digit (starts at 1 and increments after) to the end of `path` and check again."
  [path]
  (if-not (exists? path)
    path
    (let [ext (ext path)
          name (without-ext (basename path))
          p (parent path)]
      (loop [x 1
             cur (join p (str name x "." ext))]
        (if-not (exists? cur)
          cur
          (recur (inc x) (join p (str name (inc x) (when ext (str "." ext))))))))))

(defn ls
  "Return directory's files."
  ([path] (ls path nil))
  ([path cb]
   (try
     (let [fs (map (partial ->file|dir path) (.readdirSync bridge/files path))]
       (if cb
         (cb fs)
         fs))
     (catch :default e
       (when cb
         (cb nil))
       nil))))

(defn ls-sync
  "Return directory's files applying ignore-pattern. Takes map of options with keys:

  * `:files` - When set only returns files
  * `:dirs` - When set only return directories"
  [path opts]
  (try
    (let [fs (remove #(re-seq ignore-pattern %) (map (partial ->file|dir path) (.readdirSync bridge/files path)))]
      (cond
       (:files opts) (filter #(file? (join path %)) fs)
       (:dirs opts) (filter #(dir? (join path %)) fs)
       :else fs))
    (catch :default e
      (js/lt.objs.console.error e))))

(defn full-path-ls
  "Return directory's files as full paths."
  [path]
  (try
    (doall (map (partial join path) (.readdirSync bridge/files path)))
    (catch :default e
      (js/lt.objs.console.error e))))

(defn dirs
  "Return directory's directories."
  [path]
  (try
    (filter dir? (map (partial join path) (.readdirSync bridge/files path)))
    (catch :default e
      (js/lt.objs.console.error e))))

(defn home
  "Return users' home directory (e.g. ~/) or path under it."
  ([] (home nil))
  ([path]
   (let [env (.env bridge/host)
         h (if (platform/win?)
             (aget env "USERPROFILE")
             (aget env "HOME"))]
     (join h (or path separator)))))

(defn lt-home
  "Return LT's home directory."
  ([] load/dir)
  ([path]
   (join (lt-home) path)))

(defn lt-user-dir
  "Return LT's user directory. Used for storing user-related content (e.g.,
  settings, plugins, logs, and caches)."
  ([] (lt-user-dir ""))
  ([path]
   (if-let [dir (aget (.env bridge/host) "LT_USER_DIR")]
     (join dir path)
     (join data-path path))))

(defn walk-up-find
  "Starting at `start` path, walk up parent directories and return first path
  whose basename matches find."
  [start find]
  (let [roots (get-roots)]
    (loop [cur start
           prev ""]
      (if (or (empty? cur)
              (roots cur)
              (= cur prev))
        nil
        (if (exists? (join cur find))
          (join cur find)
          (recur (parent cur) cur))))))

(defn relative
  "Returns a relative path, if there is one, from `a` to `b`."
  [a b]
  (.relative bridge/path a b))

(defn filter-walk
  "Returns files and directories under `path` where `func` returns true.

  Example:
  ```
  (filter-walk
    (fn [x] (= (basename x) \"LightTable\"))
    \"/home/sbauer/dev/LightTable/\")
  ;;=> (\"/home/sbauer/dev/LightTable/builds/lighttable-0.8.1-linux/LightTable\"
       \"/home/sbauer/dev/LightTable/.git/refs/remotes/LightTable\"
       \"/home/sbauer/dev/LightTable/.git/logs/refs/remotes/LightTable\")
  ```"
  [func path]
  (loop [to-walk (dirs path)
         found (filterv func (full-path-ls path))]
    (if-not (seq to-walk)
      found
      (let [cur (first to-walk)
            neue (filterv func (full-path-ls cur))]
        (recur (concat (rest to-walk) (dirs cur)) (concat found neue))))))
