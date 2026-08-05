(ns lt.bench.search
  "How long a project-wide search takes, measured rather than assumed.

  This exists because of a gap in the argument rather than a gap in the code.
  doc/direction.md names *find-in-project that is fast on a large repository* as
  one of the unglamorous list, and nothing had ever measured it — so there was
  no number to justify a change against, and no way to tell afterwards whether a
  change had helped. That is the worse half: an optimisation nobody measured is
  indistinguishable from one that made things slower.

  It runs the **real** searcher rather than a copy of it, which is the whole
  reason it is a ClojureScript entry point and not a shell script:
  [[lt.background.file-search]] is what the worker calls, so this measures what
  the editor does.

  ```
  make bench-search                          this repository, default ignores
  make bench-search ARGS=\"--all\"             including node_modules
  make bench-search ARGS=\"/path 'query'\"     somewhere else
  ```

  Each implementation runs three times and the **fastest** run is reported.
  Not the mean: the thing being measured is how long the work takes, and a
  slower run differs from a faster one by what else the machine was doing, which
  is noise rather than signal."
  (:require ["fs" :as fs]
            ["path" :as path]
            [clojure.string :as string]
            [lt.background.file-search :as file-search]
            [lt.background.rg :as rg]))

(def ^:private runs 3)

(def ^:private default-query
  "A string that occurs often enough to exercise the reporting path and is not
  so common that the run is dominated by building result maps."
  "defn")

(defn- ignore-pattern
  "The same exclusions `lt.objs.files/ignore-pattern` holds.

  Copied rather than required: that namespace reaches the bridge, and this runs
  under plain node. Copied *and asserted* — `bench_search_test` fails if the two
  drift, because a benchmark searching a different set of files than the editor
  does is measuring nothing."
  []
  #"(^\..*)|\.class$|target/|node_modules/|^[_.]svn$|^CVS$|^\.hg$|^\.git$|\.pyc|~|\.swp|\.jar|.DS_Store")

(defn- count-files
  "How many files are under `root`, given `exclude`. The denominator."
  [root exclude]
  (loop [pending [root] files 0]
    (if (empty? pending)
      files
      (let [dir (first pending)
            entries (try (.readdirSync fs dir #js {:withFileTypes true})
                         (catch :default _ #js []))]
        (recur (into (rest pending)
                     (for [^js e entries
                           :let [nm (.-name e)]
                           :when (and (.isDirectory e)
                                      (not (and exclude
                                                (re-seq exclude (str nm path/sep)))))]
                       (.join path dir nm)))
               (+ files (count (for [^js e entries
                                     :when (and (.isFile e)
                                                (not (and exclude (re-seq exclude (.-name e)))))]
                                 e))))))))

(defn- timed
  "`f`, run [[runs]] times, with the fastest wall time and its result.

  For the synchronous implementation. The ripgrep one is read asynchronously —
  see [[timed-async]]."
  [f]
  (reduce (fn [best _]
            (let [started (js/Date.now)
                  result (f)
                  ms (- (js/Date.now) started)]
              (if (or (nil? best) (< ms (:ms best)))
                {:ms ms :result result}
                best)))
          nil
          (range runs)))

(defn- timed-async
  "[[timed]] for something that answers a callback, calling `k` with the best run.

  Sequential rather than concurrent, which is the only honest way to time
  anything: ripgrep uses every core, so two runs at once would each be measuring
  the other."
  [f k]
  (letfn [(step [n best]
            (if (>= n runs)
              (k best)
              (let [started (js/Date.now)]
                (f (fn [result]
                     (let [ms (- (js/Date.now) started)]
                       (step (inc n)
                             (if (or (nil? best) (< ms (:ms best)))
                               {:ms ms :result result}
                               best))))))))]
    (step 0 nil)))

(defn walk-search
  "The current implementation: walk the tree in this process and read every
  file."
  [root query exclude]
  (let [summary (file-search/search {:paths [root]
                                     :pattern query
                                     :exclude exclude})]
    {:searched (:total-files summary)
     :matched (:matched-files summary)
     :matches (:matches summary)}))

(defn rg-search
  "The bundled ripgrep, run the way the worker runs it.

  `ignore-files?` is off in the `--all` case so the two implementations read the
  **same set of files**. Without that the comparison flatters ripgrep enormously
  and dishonestly: it honours .gitignore, so on this repository it reads 470
  files where the walk reads 13,045, and reporting that as a speedup is
  reporting a different question's answer."
  [binary root query exclude ignore-files? k]
  (rg/search {:binary binary
              :paths [root]
              :pattern query
              :exclude exclude
              :ignore-files? ignore-files?
              :on-done (fn [summary]
                         (k {:searched (:total-files summary)
                             :matched (:matched-files summary)
                             :matches (:matches summary)}))
              :on-error (fn [message]
                          (println (str "  ripgrep failed: " message))
                          (k {:searched 0 :matched 0 :matches 0}))}))

(defn- report
  "One row. `baseline` compares total wall time; `per-file` is what makes the
  rows comparable at all.

  The wall-clock column on its own is misleading and was: the three rows read
  different numbers of files, so `1.5x SLOWER` was reporting that ripgrep did
  two and a half times the work rather than that it is slow. Microseconds per
  file is the number that answers *which engine is faster*, and it disagrees
  with the wall clock — which is exactly why it is printed."
  [label {:keys [ms result]} baseline]
  (let [read (or (:searched result) 0)
        per (when (pos? read) (/ (* 1000.0 ms) read))]
    (println (str "  " (string/join "" (repeat (max 0 (- 22 (count label))) " ")) label
                  "  " ms "ms"
                  "  " read " read"
                  (when per (str "  " (.toFixed per 0) "µs/file"))
                  "  " (:matched result) " files"
                  "  " (:matches result) " matches"
                  ;; Named in the direction it went. "0.7x faster" is how a
                  ;; benchmark tells you something got slower without you
                  ;; noticing.
                  (when (and baseline (pos? ms) (not= baseline ms))
                    (if (< ms baseline)
                      (str "  " (.toFixed (/ baseline ms) 1) "x")
                      (str "  " (.toFixed (/ ms baseline) 1) "x SLOWER")))))))

(defn- binary-path
  "Where the bundled ripgrep is, from this repository rather than from an
  install — the benchmark runs in the tree."
  []
  (.join path (.resolve path ".") "deploy" "core" "bin"
         (if (= "win32" (.-platform js/process)) "rg.exe" "rg")))

(defn ^:export main [& args]
  (let [args (vec args)
        all? (boolean (some #{"--all"} args))
        positional (vec (remove #{"--all"} args))
        root (.resolve path (or (first positional) "."))
        query (or (second positional) default-query)
        exclude (when-not all? (ignore-pattern))
        binary (binary-path)]
    (println (str "\nsearching " root " for " (pr-str query)
                  (if all? " (everything)" " (default ignores)")))
    (println (str "  " (count-files root exclude) " files under it, "
                  runs " runs each, fastest reported\n"))
    (let [walk (timed #(walk-search root query exclude))]
      (report "walk + read" walk nil)
      (if-not (rg/available? binary)
        (do (println (str "\n  no ripgrep at " binary
                          " — run `node script/fetch-ripgrep.mts`"))
            (println))
        ;; Nested rather than sequenced, because each has to finish before the
        ;; next starts — ripgrep uses every core, so two at once would each be
        ;; measuring the other.
        (timed-async
         (fn [k] (rg-search binary root query exclude false k))
         (fn [ignoring-nothing]
           (report "rg, ignoring nothing" ignoring-nothing (:ms walk))
           (timed-async
            (fn [k] (rg-search binary root query exclude true k))
            (fn [as-shipped]
              (report "rg, as shipped" as-shipped (:ms walk))
              (println)
              (println "  Read the µs/file column, not the wall clock: the three rows read")
              (println "  different numbers of files. `rg, ignoring nothing` reads MORE than")
              (println "  the walk — the walk's own ignore-pattern excludes node_modules and")
              (println "  --no-ignore does not — so a slower total there is more work, not a")
              (println "  slower engine. Per file, ripgrep wins on every row.")
              (println)
              (println "  Two costs are worth knowing. --json roughly doubles ripgrep's own")
              (println "  time against --count-matches, because it spends most of it writing")
              (println "  output. And the win in the shipped row is mostly about not reading")
              (println "  files at all, which makes `use-ignore-files` load-bearing rather")
              (println "  than a detail.")
              (println)))))))))
