(ns lt.background.file-search-test
  "Project-wide search is the one feature here that both writes to disk and had
  been silently broken, so these run against a real directory tree rather than
  a stubbed filesystem. Nothing is mocked: a temporary directory is built, the
  search walks it, and for the replacement cases the files are read back.

  The bar is what a user would notice. Searching finds the right lines with the
  right numbers; a literal search stays literal; a replacement rewrites only
  what matched; and none of a symlink loop, a binary file, or an unreadable one
  takes the walk down with it."
  (:require ["fs" :as fs]
            ["os" :as os]
            ["path" :as path]
            [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as string]
            [lt.background.file-search :as fsearch]))

;;*********************************************************
;; A tree to search
;;*********************************************************

(def ^:dynamic *root* nil)

(defn- write! [root relative content]
  (let [full (.join path root relative)]
    (.mkdirSync fs (.dirname path full) #js {:recursive true})
    (.writeFileSync fs full content)
    full))

(defn- read-file [relative]
  (.readFileSync fs (.join path *root* relative) "utf8"))

(defn- build-tree! [root]
  (write! root "a.txt" "alpha beta\ngamma NEEDLE delta\nepsilon\n")
  (write! root "b.txt" "no match here\nNEEDLE again and NEEDLE twice\nNEEDLE alone\n")
  (write! root "nested/c.txt" "deep NEEDLE\n")
  ;; Lower case, so a capitalised search must not find it — case sensitivity
  ;; has to hold across the walk, not only in the pattern.
  (write! root "nested/deeper/d.txt" "deeper needle in lower case\n")
  (write! root "quiet.txt" "nothing of interest\n")
  (write! root "dots.txt" "a.b matches literally\naxb does not\n")
  ;; Excluded by the default ignore pattern, by directory name and by dotfile.
  (write! root "target/ignored.txt" "NEEDLE in target\n")
  (write! root ".hidden/ignored.txt" "NEEDLE in a dotdir\n")
  ;; A file named like the excluded directory, which must still be searched.
  (write! root "target.txt" "NEEDLE beside target\n")
  ;; Not text: a NUL byte in the first block.
  (.writeFileSync fs (.join path root "blob.bin")
                  (.from js/Buffer #js [78 69 69 68 76 69 0 78 69 69 68 76 69]))
  root)

(use-fixtures :each
  {:before (fn []
             (let [root (.mkdtempSync fs (.join path (.tmpdir os) "lt-search-"))]
               (set! *root* (build-tree! root))))
   :after (fn []
            (when *root*
              (.rmSync fs *root* #js {:recursive true :force true})
              (set! *root* nil)))})

(defn- run
  "Search *root* and return the summary plus the files reported, in the order
  they arrived."
  [opts]
  (let [seen (atom [])
        summary (fsearch/search (merge {:paths [*root*]
                                        :on-file #(swap! seen conj %)}
                                       opts))]
    (assoc summary :files @seen)))

(defn- relative-names [res]
  (set (map #(-> (:file %) (string/replace (str *root* (.-sep path)) "")
                 (string/replace "\\" "/"))
            (:files res))))

;;*********************************************************
;; Building the pattern
;;*********************************************************

(deftest a-plain-search-is-a-literal
  (testing "regex characters in a plain search match themselves"
    (let [re (fsearch/->pattern "a.b")]
      (is (true? (.test re "a.b")))
      (set! (.-lastIndex re) 0)
      (is (false? (.test re "axb"))
          "this is the bug replace had: every search was compiled as a regex")))

  (testing "a search that would be an invalid regex is still a search"
    (is (true? (.test (fsearch/->pattern "(") "fn(")))))

(deftest slashes-mean-regex
  (let [re (fsearch/->pattern "/a.b/")]
    (is (true? (.test re "axb")))
    (set! (.-lastIndex re) 0)
    (is (true? (.test re "a.b")))))

(deftest case-follows-the-search
  (testing "all lower case searches insensitively"
    (is (false? (fsearch/case-sensitive? "needle")))
    (is (true? (.test (fsearch/->pattern "needle") "NEEDLE"))))

  (testing "a capital means case was meant"
    (is (true? (fsearch/case-sensitive? "Needle")))
    (is (false? (.test (fsearch/->pattern "Needle") "needle")))))

;;*********************************************************
;; Finding lines
;;*********************************************************

(deftest lines-are-numbered-from-one
  (let [re (fsearch/->pattern "NEEDLE")
        hits (fsearch/matching-lines "first\nsecond NEEDLE\nthird\n" re)]
    (is (= [{:line 2 :text "second NEEDLE"}] hits)
        ":go-to-line subtracts one, so these are 1-based")))

(deftest two-matches-on-a-line-are-one-result
  (let [re (fsearch/->pattern "NEEDLE")
        hits (fsearch/matching-lines "NEEDLE and NEEDLE\n" re)]
    (is (= 1 (count hits)) "one place to go, one line to show")))

(deftest a-global-pattern-does-not-skip-every-other-line
  (testing "lastIndex is reset per line"
    (let [re (fsearch/->pattern "x")
          hits (fsearch/matching-lines "x\nx\nx\nx\n" re)]
      (is (= [1 2 3 4] (mapv :line hits))))))

(deftest a-reused-pattern-still-matches
  (let [re (fsearch/->pattern "x")]
    (is (= 1 (count (fsearch/matching-lines "x\n" re))))
    (is (= 1 (count (fsearch/matching-lines "x\n" re)))
        "a caller that reuses the regex must not get an empty second search")))

(deftest very-long-lines-are-truncated
  (let [long-line (str (string/join (repeat 1000 "z")) "NEEDLE")
        hits (fsearch/matching-lines (str long-line "\n") (fsearch/->pattern "NEEDLE"))]
    (is (= 400 (count (:text (first hits))))
        "a match inside a minified bundle should not be sent whole")))

(deftest carriage-returns-are-not-shown
  (let [hits (fsearch/matching-lines "a NEEDLE\r\nb\r\n" (fsearch/->pattern "NEEDLE"))]
    (is (= "a NEEDLE" (:text (first hits))))))

;;*********************************************************
;; Walking the tree
;;*********************************************************

(deftest finds-every-match-in-the-tree
  (let [res (run {:pattern "NEEDLE"})]
    (is (= #{"a.txt" "b.txt" "nested/c.txt" "target.txt"
             "target/ignored.txt" ".hidden/ignored.txt"}
           (relative-names res))
        "with no exclusions, everything textual is searched, at any depth")
    (is (= 6 (:matched-files res)))
    (is (= 7 (:matches res)) "b.txt has two matching lines")
    (is (empty? (:errors res)))))

(deftest a-capitalised-search-does-not-find-lower-case-in-the-tree
  (testing "case sensitivity holds across the walk, not only in the pattern"
    (is (not (contains? (relative-names (run {:pattern "NEEDLE"}))
                        "nested/deeper/d.txt")))
    (is (contains? (relative-names (run {:pattern "needle"}))
                   "nested/deeper/d.txt"))))

(deftest counts-files-searched-not-only-matched
  (let [res (run {:pattern "NEEDLE"})]
    (is (= 9 (:total-files res))
        "quiet.txt, dots.txt and d.txt were searched and did not match")
    (is (= 6 (:matched-files res)))))

(deftest a-binary-is-not-counted-as-searched
  (testing "it was opened and rejected, so the summary must not claim it"
    (let [before (:total-files (run {:pattern "NEEDLE"}))]
      (.writeFileSync fs (.join path *root* "another.bin")
                      (.from js/Buffer #js [0 78 69 69 68 76 69]))
      (is (= before (:total-files (run {:pattern "NEEDLE"})))
          "one more binary on disk, the same number of files searched"))))

(deftest reports-elapsed-milliseconds
  (let [res (run {:pattern "NEEDLE"})]
    (is (number? (:time res)))
    (is (>= (:time res) 0)
        "the searcher divides this by 1000, so undefined is not an option")))

(deftest exclusions-skip-directories-but-not-similar-files
  (write! *root* "node_modules/left-pad/index.js" "NEEDLE in a dependency\n")
  (let [res (run {:pattern "NEEDLE"
                  :exclude #"(^\..*)|target/|node_modules/"})
        names (relative-names res)]
    (is (not (contains? names "target/ignored.txt")) "target/ is excluded")
    (is (not (contains? names ".hidden/ignored.txt")) "dotdirs are excluded")
    (is (not (contains? names "node_modules/left-pad/index.js"))
        "and the directory that is most of a modern project")
    (is (contains? names "target.txt")
        "a file named like an excluded directory is still searched")
    (is (contains? names "a.txt"))))

(deftest an-excluded-directory-costs-nothing-to-skip
  ;; Not merely absent from the results — never opened. Excluding after
  ;; reading is the shape of a search that is correct and still slow, which is
  ;; what a project's dependencies would make of every search.
  (write! *root* "node_modules/left-pad/index.js" "NEEDLE in a dependency\n")
  (write! *root* "node_modules/left-pad/quiet.js" "nothing here\n")
  (let [with-it (run {:pattern "NEEDLE"})
        without (run {:pattern "NEEDLE" :exclude #"node_modules/"})]
    (is (= 2 (- (:total-files with-it) (:total-files without)))
        "both files under it are skipped, not just the matching one")))

(deftest an-explicitly-named-path-is-searched-even-if-excluded
  (let [res (fsearch/search {:paths [(.join path *root* "target")]
                             :pattern "NEEDLE"
                             :exclude #"target/"
                             :on-file identity})]
    (is (= 1 (:matched-files res))
        "asking for a directory by name is clearer than a default ignore list")))

(deftest binary-files-are-left-alone
  (let [res (run {:pattern "NEEDLE"})]
    (is (not (contains? (relative-names res) "blob.bin"))
        "the bytes spell NEEDLE, but a NUL byte says this is not text")))

(deftest a-literal-search-does-not-match-as-a-regex
  (let [res (run {:pattern "a.b"})]
    (is (= #{"dots.txt"} (relative-names res)))
    (is (= [{:line 1 :text "a.b matches literally"}]
           (:results (first (:files res))))
        "axb is on line 2 and must not be reported")))

(deftest a-single-file-can-be-searched
  (let [res (fsearch/search {:paths [(.join path *root* "a.txt")]
                             :pattern "NEEDLE"
                             :on-file identity})]
    (is (= 1 (:total-files res)))
    (is (= 1 (:matched-files res)))))

(deftest a-missing-path-is-not-fatal
  (let [res (fsearch/search {:paths [(.join path *root* "does-not-exist")
                                     (.join path *root* "a.txt")]
                             :pattern "NEEDLE"
                             :on-file identity})]
    (is (= 1 (:matched-files res))
        "the path that does exist is still searched")))

(deftest symlinks-are-not-followed
  (testing "a link pointing at its own parent would otherwise walk forever"
    (try
      (.symlinkSync fs *root* (.join path *root* "nested" "loop"))
      (catch :default _ nil))
    (let [res (run {:pattern "NEEDLE"})]
      (is (= 6 (:matched-files res))
          "the same six files, reached once each"))))

(deftest an-unreadable-file-is-reported-and-skipped
  (let [locked (.join path *root* "locked.txt")]
    (.writeFileSync fs locked "NEEDLE\n")
    (.chmodSync fs locked 0)
    ;; Running as root defeats the permission, so this only asserts when the
    ;; file is genuinely unreadable.
    (let [readable? (try (.readFileSync fs locked) true (catch :default _ false))
          res (run {:pattern "NEEDLE"})]
      (if readable?
        (is (contains? (relative-names res) "locked.txt"))
        (do
          (is (= 1 (count (:errors res))))
          (is (contains? (relative-names res) "a.txt")
              "one unreadable file must not end the search"))))))

;;*********************************************************
;; Replacing
;;*********************************************************

(defn- rewritten
  "The new text the searcher produced for `relative`, if it produced any.

  A replacement is handed back rather than written: the worker cannot see
  which files are open in a tab, and writing behind one is how a buffer and
  the disk come to disagree. lt.objs.workspace-edit applies these."
  [res relative]
  (->> (:files res)
       (filter #(= relative (-> (:file %)
                                (string/replace (str *root* (.-sep path)) "")
                                (string/replace "\\" "/"))))
       first
       :text))

(deftest replacement-rewrites-only-what-matched
  (let [res (run {:pattern "NEEDLE" :replacement "THREAD"})]
    (is (= 6 (:matched-files res)))
    (is (= "alpha beta\ngamma THREAD delta\nepsilon\n" (rewritten res "a.txt")))
    (is (= "no match here\nTHREAD again and THREAD twice\nTHREAD alone\n"
           (rewritten res "b.txt"))
        "both matches on a line are replaced, though the line is reported once")
    (is (nil? (rewritten res "quiet.txt"))
        "a file with no match is not rewritten")))

(deftest a-replacement-does-not-touch-the-disk
  ;; The bug this shape exists to stop: the worker used to writeFileSync here,
  ;; including over files that were open in a tab.
  (let [before (read-file "a.txt")]
    (run {:pattern "NEEDLE" :replacement "THREAD"})
    (is (= before (read-file "a.txt"))
        "the searcher reports what to write; it does not write it")))

(deftest replacement-is-case-insensitive-when-the-search-is
  (let [res (run {:pattern "needle" :replacement "THREAD"})]
    (is (= 7 (:matched-files res)))
    (is (= "deeper THREAD in lower case\n" (rewritten res "nested/deeper/d.txt")))
    (is (= "gamma THREAD delta" (second (string/split-lines (rewritten res "a.txt"))))
        "an insensitive search replaces the differently-cased match too")))

(deftest replacement-leaves-binaries-untouched
  (let [res (run {:pattern "NEEDLE" :replacement "THREAD"})]
    (is (nil? (rewritten res "blob.bin"))
        "rewriting a binary as UTF-8 would corrupt it")
    (is (= 13 (.-length (.readFileSync fs (.join path *root* "blob.bin")))))))

(deftest a-regex-replacement-can-use-a-capture
  (write! *root* "cap.txt" "value = 1\n")
  (let [seen (atom [])
        res (fsearch/search {:paths [(.join path *root* "cap.txt")]
                             :pattern "/value = (\\d+)/"
                             :replacement "value = $1$1"
                             :on-file #(swap! seen conj %)})]
    (is (= 1 (:matched-files res)))
    (is (= "value = 11\n" (:text (first @seen))))))

(deftest searching-without-a-replacement-changes-nothing
  (let [before (read-file "a.txt")]
    (run {:pattern "NEEDLE"})
    (is (= before (read-file "a.txt")))))
