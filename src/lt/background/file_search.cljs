(ns lt.background.file-search
  "Search, and optionally rewrite, the files under a set of paths.

  This replaced the `replace` npm package, which was the last source of the
  project's remaining npm advisories — three `brace-expansion` denial-of-service
  reports, reached through `minimatch`. Pinning a newer `minimatch` breaks
  `replace`, whose export shape changed, so the package had to go rather than be
  upgraded.

  It turned out `replace` was not working either. Light Table calls it with a
  `result` callback and reads `totalFiles` and `time` off what it returns; the
  1.x package has no `result` option and returns a plain array. So every
  workspace search reported nothing at all, and the summary line said it had
  searched `undefined` files in `NaN` seconds. Whatever version that API
  belonged to, it was several major versions ago.

  Nothing here touches the worker's plumbing, so it can be tested directly —
  see test/lt/background/file_search_test.cljs. `lt.background.search` is the
  thin job that connects it to the renderer."
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as string]))

(def ^:private line-limit
  "Longest line reported back, in characters. A match inside a minified bundle
  should not put a megabyte of text into the results list."
  400)

(def ^:private binary-sniff-bytes
  "How much of a file to look at before deciding it is not text."
  8000)

(defn- escape-regex [s]
  (string/replace s #"[.*+?^${}()|\[\]\\]" "\\$&"))

(defn case-sensitive?
  "Searches are case insensitive until the user types a capital, which is taken
  as a signal that case was meant."
  [search]
  (boolean (re-seq #"[A-Z]" search)))

(defn ->pattern
  "The regex to search with.

  A search is a literal string unless it is written /like this/, in which case
  what is between the slashes is a regex. That distinction is the whole reason
  this function exists, and it is now honoured: `replace` compiled every search
  as a regex, so looking for `a.b` quietly matched `axb`, and looking for `(`
  was an error rather than a search."
  [search]
  (let [[_ body] (first (re-seq #"^/(.+)/$" search))
        flags (str "g" (when-not (case-sensitive? search) "i"))]
    (js/RegExp. (if body body (escape-regex search)) flags)))

(defn- binary?
  "True if `buf` looks like something other than text.

  A NUL byte is the signal, which is what `grep` uses, and it separates a
  compiled artifact from source without a table of file extensions to keep up
  to date. Searching a binary as if it were UTF-8 produces matches nobody asked
  for, and replacing in one would corrupt it."
  [^js buf]
  (let [n (min (.-length buf) binary-sniff-bytes)]
    (loop [i 0]
      (cond
        (>= i n) false
        (zero? (.readUInt8 buf i)) true
        :else (recur (inc i))))))

(defn matching-lines
  "Every line of `text` that `re` matches, as `{:line n :text s}` with `n`
  counted from 1 — which is what `:go-to-line` expects.

  One entry per line rather than per match: two hits on one line are one place
  to go and one line to show.

  `re` is global, so its `lastIndex` carries between calls and would make every
  other line miss. It is reset per line rather than per search, so a caller
  reusing a pattern cannot be caught out by it either."
  [text re]
  (let [lines (string/split text #"\n")]
    (loop [i 0, acc (transient [])]
      (if (>= i (count lines))
        (persistent! acc)
        (let [line (nth lines i)]
          (set! (.-lastIndex re) 0)
          (recur (inc i)
                 (if (.test re line)
                   ;; A trailing \r is stripped for display only, so a CRLF file
                   ;; does not render with a stray character on every hit.
                   (let [shown (string/replace line #"\r$" "")]
                     (conj! acc {:line (inc i)
                                 :text (if (> (count shown) line-limit)
                                         (subs shown 0 line-limit)
                                         shown)}))
                   acc)))))))

(defn- excluded?
  "Whether `exclude` rejects a directory entry.

  Matched against the entry's own name rather than its full path, with a
  trailing separator for directories, so that `target/` excludes a directory
  called target without also excluding a file of that name. This is exactly
  what src-worker/walkdir.ts does, so the navigate bar and the searcher now
  agree on what is not worth looking at. They did not before: this was handed
  to `replace` as an `exclude` option, which fed a regex source string to
  minimatch as though it were a glob, and matched approximately nothing."
  [^js exclude name dir?]
  (boolean (and exclude
                (re-seq exclude (if dir? (str name path/sep) name)))))

(defn- search-file
  "Search one file, and rewrite it when `replacement` is given.

  Returns one of:

    {:file f :results [...]}  matched
    {:searched true}          read, no match
    {:skipped :binary}        not text, so not searched
    {:file f :error e}        could not be read

  The distinction between the last two and `:searched` is what keeps the
  reported file count honest: a binary was never searched, and an unreadable
  file should cost itself and nothing else."
  [file re replacement]
  (try
    (let [buf (.readFileSync fs file)]
      (if (binary? buf)
        {:skipped :binary}
        (let [text (.toString buf "utf8")
              results (matching-lines text re)]
          (if (seq results)
            ;; With a replacement, the rewritten text goes back rather than to
            ;; disk. Writing here meant writing behind an open tab — see
            ;; lt.objs.workspace-edit, which is what applies it now.
            (if replacement
              (do (set! (.-lastIndex re) 0)
                  {:file file :results results :text (.replace text re replacement)})
              {:file file :results results})
            {:searched true}))))
    (catch :default e
      {:file file :error (str e)})))

(defn search
  "Search every file under `:paths` for `:pattern`, calling `:on-file` with
  `{:file :results}` for each file that matches. With a `:replacement` the
  matches are rewritten in place as they are found.

  `:pattern` is a string, read by [[->pattern]], or a regex to use as given.
  `:exclude` is a regex applied to each directory entry's name; the paths
  passed in are searched whether or not they match it, since asking for a
  directory by name is a clearer signal than a default ignore list.

  Symbolic links are not followed. That is what the old implementation did, and
  it is also what stops a cyclic link walking forever.

  Returns `{:total-files :matched-files :matches :time :errors}`. `:time` is in
  milliseconds, because that is what the searcher divides by 1000 to display."
  [{:keys [paths pattern exclude replacement on-file]}]
  (let [re (if (regexp? pattern) pattern (->pattern pattern))
        started (.now js/Date)
        ;; Directories still to walk. Files are searched the moment they are
        ;; found, so nothing is stat'd twice and nothing accumulates.
        pending (array)
        zero {:total-files 0 :matched-files 0 :matches 0 :errors []}
        visit (fn [acc full ^js stat]
                (cond
                  (or (nil? stat) (.isSymbolicLink stat))
                  acc

                  (.isDirectory stat)
                  (do (.push pending full) acc)

                  (.isFile stat)
                  (let [res (search-file full re replacement)]
                    (cond
                      (:skipped res) acc
                      (:error res) (update acc :errors conj res)

                      (:results res)
                      (do
                        (when on-file (on-file res))
                        (-> acc
                            (update :total-files inc)
                            (update :matched-files inc)
                            (update :matches + (count (:results res)))))

                      :else (update acc :total-files inc)))

                  :else acc))
        lstat (fn [p] (try (.lstatSync fs p) (catch :default _ nil)))
        acc (reduce (fn [acc p] (visit acc p (lstat p))) zero paths)]
    (loop [acc acc]
      (if (zero? (.-length pending))
        (assoc acc :time (- (.now js/Date) started))
        (let [dir (.shift pending)
              children (try (.readdirSync fs dir) (catch :default _ #js []))]
          (recur
           (reduce (fn [acc child]
                     (let [full (.join path dir child)
                           stat (lstat full)]
                       (if (excluded? exclude child
                                      (boolean (and stat (.isDirectory stat))))
                         acc
                         (visit acc full stat))))
                   acc
                   children)))))))
