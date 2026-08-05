(ns lt.background.search
  "Project-wide search and replace, run off the main thread so that walking a
  large workspace does not block the editor.

  Two implementations, and this namespace is which one runs:

  | | |
  |---|---|
  | [[lt.background.rg]] | the bundled ripgrep. ~17x faster, and what does the finding |
  | [[lt.background.file-search]] | the tree walk. Does the rewriting, and is the fallback |

  Neither knows about the worker, so both can be tested. This is only the
  wiring: pick one, turn the message from the renderer into a call, and turn
  each match back into a message.

  **The fallback is not a formality.** `deploy/core/bin/rg` is fetched at build
  time and deliberately not committed, so a fresh checkout that has not run
  `make deps` does not have it, and there is no pinned build for every platform
  anyone might run this on. Search that is slower than it could be beats search
  that is missing, and the walk is the tested one."
  (:require [lt.background.file-search :as file-search]
            [lt.background.rg :as rg]
            [lt.background.runtime :as bg]))

(defn binary
  "Where the bundled ripgrep is.

  `core/` is part of the path and is easy to leave out, which is what happened:
  `lt.util.load/dir` is `app-dir + \"/..\"`, so Light Table's *home* is the
  directory **containing** `core` rather than `core` itself — the same reason
  `lt.objs.settings` reaches for `core/User/…`. Without it this resolved to
  `deploy/bin/rg`, found nothing, and search fell back to the tree walk while
  every test still passed, because the two implementations agree about results.

  What caught it is the smoke check that asserts which engine answered, added
  for exactly that reason and failing on its first run."
  []
  (let [windows? (= "win32" (.-platform js/process))]
    (bg/lt-file (str "core/bin/rg" (when windows? ".exe")))))

(defn- replace-in
  "Rewrite `files`, which ripgrep has already established contain a match.

  The whole of why this exists: **ripgrep does not write.** So the rewriting is
  still the walk's — the same `->pattern`, the same per-line replacement, the
  same 27 tests — and ripgrep's contribution is that it looks at the files that
  matched rather than at the tree.

  `:paths` takes files as happily as directories, so handing it a list of
  matched files is a search of exactly those."
  [files search replacement on-file]
  (file-search/search {:paths files
                       :pattern search
                       :replacement replacement
                       :on-file on-file}))

(defn search
  "Search `paths` for `:search`, sending each matching file back as it is found
  and a summary once finished. With a `:replacement` the matches are rewritten.

  The summary's `:total` is how many files were really read, whichever
  implementation answered — ripgrep reports it as `stats.searches` and the walk
  counts it. Reporting the tree size instead would be the honest-file-count bug
  the walk was written to fix, arriving from the other direction.

  `:engine` says which one answered, because the two agree about results and
  nothing else could tell them apart.

  Nothing is returned. ripgrep is read asynchronously — see [[lt.background.rg]]
  — so the answer leaves as a message from a callback, which is what the worker's
  job table wants anyway: it ignores return values."
  [obj-id {:keys [search exclude replacement paths ignore-files?]}]
  (let [;; :raw because the searcher reads .file and .results off these
        ;; directly, one message per matching file.
        emit (fn [res] (bg/send! obj-id :result (clj->js res) :raw))
        exclude-re (when exclude (js/RegExp. exclude))
        done! (fn [summary engine]
                (bg/send! obj-id :done-searching
                          {:total (:total-files summary)
                           :time (:time summary)
                           :engine engine
                           :replace? (boolean replacement)}))
        walk! (fn []
                (done! (file-search/search {:paths paths
                                            :pattern search
                                            :exclude exclude-re
                                            :replacement replacement
                                            :on-file emit})
                       :walk))
        rg-binary (binary)]
    (if-not (rg/available? rg-binary)
      (walk!)
      (rg/search
       {:binary rg-binary
        :paths paths
        :pattern search
        :exclude exclude-re
        ;; Absent means on, which is what the renderer sends when the behavior
        ;; has not been changed. `false` has to survive the trip, so it cannot
        ;; be an `or`.
        :ignore-files? (if (nil? ignore-files?) true (boolean ignore-files?))
        ;; Without a replacement the matches ripgrep found are the answer and go
        ;; straight back. With one they are a work list, and what the renderer
        ;; should see is the results the rewrite produced.
        :on-file (when-not replacement emit)
        :on-done (fn [found]
                   (done! (if-not replacement
                            found
                            (merge found (replace-in (map :file (:files found))
                                                     search replacement emit)
                                   ;; Files actually read is ripgrep's count; the
                                   ;; rewrite only ever looked at the ones that
                                   ;; matched.
                                   {:total-files (:total-files found)}))
                          :rg))
        ;; Falling back rather than letting the search report nothing. The
        ;; reachable causes are the binary going away between `available?` and
        ;; the spawn, and anything thrown while parsing — and a search that
        ;; quietly takes longer is a far better outcome than one that answers
        ;; with silence. `:engine` and this console line are what stop "quietly"
        ;; being the whole story.
        :on-error (fn [message]
                    (bg/send! obj-id :search-engine-failed (str message))
                    (walk!))}))))
