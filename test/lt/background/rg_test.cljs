(ns lt.background.rg-test
  "Tests for searching through ripgrep.

  Everything that decides anything is a pure function of its arguments, so this
  needs no binary and no process: the command line is built by [[rg/argv]], one
  line of output is read by [[rg/parse-line]], and the only thing left for
  [[rg/search]] to do is spawn.

  The fixtures are **real ripgrep output**, pasted from `rg --json` rather than
  written by hand. A parser tested against a schema somebody remembered is a
  parser tested against the wrong thing."
  (:require [cljs.test :refer [deftest is testing]]
            [lt.background.rg :as rg]))

;;*********************************************************
;; The command line
;;*********************************************************

(deftest a-plain-search-is-a-literal
  ;; The distinction the walk exists to make: `replace` compiled every search as
  ;; a regex, so `a.b` quietly matched `axb` and `(` was an error rather than a
  ;; search. ripgrep must not undo that.
  (let [args (rg/argv "a.b" ["/p"])]
    (is (some #{"--fixed-strings"} args))
    (is (= ["--regexp" "a.b" "--" "/p"] (take-last 4 args)))))

(deftest a-slashed-search-is-a-regex
  (let [args (rg/argv "/a.b/" ["/p"])]
    (is (not (some #{"--fixed-strings"} args)))
    (is (= ["--regexp" "a.b" "--" "/p"] (take-last 4 args))
        "the slashes are the marker and are not part of the pattern")))

(deftest case-is-stated-rather-than-guessed
  ;; `--smart-case` was here and was one answer too many: Light Table has already
  ;; decided, by [[lt.background.file-search/case-sensitive?]]'s rule, and asking
  ;; ripgrep to infer it again meant two things deciding the same question. VS
  ;; Code states it too.
  (is (some #{"--case-sensitive"} (rg/argv "Foo" ["/p"])))
  (is (some #{"--ignore-case"} (rg/argv "foo" ["/p"])))
  (is (not (some #{"--smart-case"} (rg/argv "foo" ["/p"]))))
  (testing "and the rule is the walk's, not a new one"
    (is (some #{"--case-sensitive"} (rg/argv "fooBar" ["/p"])))))

(deftest a-users-ripgrep-config-cannot-reach-the-editors-search
  ;; RIPGREP_CONFIG_PATH would otherwise inject flags into every workspace
  ;; search. `--max-columns` or `--type-add` in somebody's dotfiles changing what
  ;; the editor finds is not a bug anybody could locate. VS Code always passes
  ;; this.
  (is (some #{"--no-config"} (rg/argv "x" ["/p"]))))

(deftest end-of-line-anchors-work-on-crlf-files
  ;; Without `--crlf`, `$` matches before the \r rather than the \n, so a regex
  ;; search anchored to the end of a line finds nothing in a CRLF file. Always
  ;; passed, as VS Code does.
  (is (some #{"--crlf"} (rg/argv "x" ["/p"])))
  (is (some #{"--crlf"} (rg/argv "/x$/" ["/p"]))))

(deftest a-regex-that-rusts-engine-cannot-do-falls-back-to-pcre2
  ;; Lookarounds and backreferences are errors in Rust's regex engine, so a
  ;; search somebody typed would be refused rather than run. `--engine auto` is
  ;; VS Code's answer and only applies to a regex search — a literal has nothing
  ;; to fall back for.
  (let [args (rg/argv "/(?<=foo)bar/" ["/p"])]
    (is (= ["--engine" "auto"] (->> args (drop-while #(not= "--engine" %)) (take 2)))))
  (is (not (some #{"--engine"} (rg/argv "plain" ["/p"])))))

(deftest symlinks-are-not-followed
  (testing "what the walk did, and what stops a cyclic link running forever"
    (is (some #{"--no-follow"} (rg/argv "x" ["/p"])))))

(deftest ignore-files-are-honoured-by-default-and-can-be-turned-off
  (let [on (rg/argv "x" ["/p"])
        off (rg/argv "x" ["/p"] {:ignore-files? false})]
    (is (not (some #{"--no-ignore"} on)))
    (is (some #{"--no-ignore"} off))
    (testing "and --hidden travels with it — told to ignore nothing means nothing"
      (is (not (some #{"--hidden"} on)))
      (is (some #{"--hidden"} off)))))

(deftest gitignore-is-honoured-outside-a-git-repository-too
  (testing "a workspace folder is often not one, and ripgrep would otherwise skip its .gitignore"
    (is (some #{"--no-require-git"} (rg/argv "x" ["/p"])))))

(deftest the-paths-come-last-behind-a-double-dash
  (testing "so a folder whose name begins with a dash is a path rather than a flag"
    (let [args (rg/argv "x" ["-weird" "/p"])]
      (is (= ["--" "-weird" "/p"] (take-last 3 args))))))

;;*********************************************************
;; Reading the output
;;*********************************************************

(def ^:private a-match
  (str "{\"type\":\"match\",\"data\":{\"path\":{\"text\":\"src/lt/object.cljs\"},"
       "\"lines\":{\"text\":\"(defn behavior*\\n\"},\"line_number\":344,"
       "\"absolute_offset\":11928,\"submatches\":[{\"match\":{\"text\":\"defn\"},"
       "\"start\":1,\"end\":5}]}}"))

(def ^:private a-summary
  (str "{\"data\":{\"elapsed_total\":{\"human\":\"0.000550s\",\"nanos\":550417,\"secs\":0},"
       "\"stats\":{\"bytes_printed\":274,\"bytes_searched\":19536,"
       "\"elapsed\":{\"human\":\"0.000021s\",\"nanos\":21042,\"secs\":0},"
       "\"matched_lines\":7,\"matches\":9,\"searches\":129,\"searches_with_match\":3}},"
       "\"type\":\"summary\"}"))

(deftest a-match-becomes-a-file-a-line-and-the-text
  (let [m (rg/parse-line a-match)]
    (is (= :match (:kind m)))
    (is (= "src/lt/object.cljs" (:file m)))
    (is (= 344 (:line m)))
    (is (= "(defn behavior*" (:text m))
        "the newline ripgrep matched up to is not part of the line")))

(deftest a-crlf-file-does-not-render-a-stray-carriage-return
  (let [line (.replace a-match "(defn behavior*\\n" "(defn behavior*\\r\\n")]
    (is (= "(defn behavior*" (:text (rg/parse-line line))))))

(deftest a-very-long-line-is-truncated-for-display
  ;; A match inside a minified bundle should not put a megabyte into the list.
  (let [long-text (apply str (repeat 900 "x"))
        line (.replace a-match "(defn behavior*\\n" (str long-text "\\n"))]
    (is (= 400 (count (:text (rg/parse-line line)))))))

(deftest the-summary-carries-the-honest-denominator
  (let [s (rg/parse-line a-summary)]
    (is (= :summary (:kind s)))
    (is (= 129 (:searched s)) "files ripgrep actually opened and read")
    (is (= 3 (:matched s)))
    (is (= 7 (:matches s)) "matched lines, because one line is one result")))

(deftest the-bracketing-messages-are-not-interesting
  (is (nil? (rg/parse-line "{\"type\":\"begin\",\"data\":{\"path\":{\"text\":\"a.cljs\"}}}")))
  (testing "and an end with no binary_offset says nothing either"
    (is (nil? (rg/parse-line (str "{\"type\":\"end\",\"data\":{\"path\":{\"text\":\"a.cljs\"},"
                                  "\"binary_offset\":null,\"stats\":{\"searches\":1}}}"))))))

(deftest an-end-with-a-binary-offset-is-how-a-binary-file-is-known
  ;; The walk sniffed the first 8KB for a NUL byte and skipped the file. ripgrep
  ;; goes the other way: it searches until the first NUL, reports whatever it
  ;; found, and only then says the file was binary — so `end` is the one message
  ;; that cannot be skipped, and the matches have to be dropped afterwards.
  ;;
  ;; Not academic. With `use-ignore-files` off on this repository, `builds/` holds
  ;; a 191MB Electron Framework and "defn" appears in it before any NUL does — so
  ;; without this, a workspace search reports matches inside a compiled binary.
  ;; It is also where the output volume comes from: the longest single JSON line
  ;; in that case is 21MB, because a binary has almost no newlines.
  (let [end (str "{\"type\":\"end\",\"data\":{\"path\":{\"text\":\"a.out\"},"
                 "\"binary_offset\":1234,\"stats\":{\"searches\":1}}}")
        parsed (rg/parse-line end)]
    (is (= :binary (:kind parsed)))
    (is (= "a.out" (:file parsed)))))

(deftest anything-unreadable-is-skipped-rather-than-thrown
  ;; stdout from a subprocess is not a place to be certain about.
  (is (nil? (rg/parse-line "")))
  (is (nil? (rg/parse-line "   ")))
  (is (nil? (rg/parse-line "not json at all")))
  (is (nil? (rg/parse-line "{\"type\":\"match\"}"))))

(deftest a-path-that-is-not-utf8-is-skipped-rather-than-guessed-at
  ;; ripgrep sends `{"bytes": base64}` instead. Light Table addresses editors by
  ;; path string, so a path it cannot spell is one it cannot open.
  (is (nil? (rg/parse-line (str "{\"type\":\"match\",\"data\":{\"path\":{\"bytes\":\"3q2+7w==\"},"
                                "\"lines\":{\"text\":\"x\\n\"},\"line_number\":1}}")))))

;;*********************************************************
;; Grouping
;;*********************************************************

(deftest matches-are-grouped-into-one-entry-per-file
  (let [grouped (vec (rg/group-by-file [{:file "a" :line 1 :text "one"}
                                        {:file "a" :line 5 :text "five"}
                                        {:file "b" :line 2 :text "two"}]))]
    (is (= ["a" "b"] (mapv :file grouped)))
    (is (= [1 5] (mapv :line (:results (first grouped)))))
    (is (= ["one" "five"] (mapv :text (:results (first grouped)))))))

(deftest two-hits-on-one-line-are-one-result
  (testing "one place to go and one line to show — the walk's rule"
    (let [grouped (vec (rg/group-by-file [{:file "a" :line 3 :text "x x"}
                                          {:file "a" :line 3 :text "x x"}]))]
      (is (= 1 (count (:results (first grouped))))))))

(deftest the-file-is-the-files-name-and-not-a-match-map
  ;; The first version destructured `partition-by`'s groups as `[file group]`,
  ;; which binds the first two *matches* instead of a key and a group. The shape
  ;; still looked right, so what caught it was the benchmark reporting 151 files
  ;; with 126 matches between them — arithmetic that cannot happen.
  (let [grouped (vec (rg/group-by-file [{:file "a" :line 1 :text "one"}
                                        {:file "a" :line 2 :text "two"}]))]
    (is (= 1 (count grouped)))
    (is (string? (:file (first grouped))))
    (is (= "a" (:file (first grouped))))
    (is (= 2 (count (:results (first grouped))))
        "and every match is kept, rather than two of them becoming the key")))

;;*********************************************************
;; The ignore pattern, which is an entry-name rule
;;*********************************************************

(def ^:private ignore
  "`lt.objs.files/ignore-pattern`, as the searcher receives it."
  #"(^\..*)|\.class$|target/|node_modules/|^[_.]svn$|^CVS$|^\.hg$|^\.git$|\.pyc|~|\.swp|\.jar|.DS_Store")

(deftest a-directory-rule-matches-a-directory-and-not-a-file-of-that-name
  (is (rg/excluded-path? ignore "/p/node_modules/x/y.js"))
  (is (rg/excluded-path? ignore "/p/target/classes/a.class"))
  (testing "and a file called target is not a directory called target"
    (is (not (rg/excluded-path? ignore "/p/target.txt")))))

(deftest a-dotfile-is-excluded-by-its-own-name
  (is (rg/excluded-path? ignore "/p/.git/config"))
  (is (rg/excluded-path? ignore "/p/src/.hidden")))

(deftest the-pattern-is-applied-per-segment-and-not-to-the-whole-path
  ;; The whole reason `excluded-path?` exists is to be the *same* rule the walk
  ;; applies, and the interesting case is one where segment-wise and
  ;; whole-string disagree.
  (is (rg/excluded-path? ignore "/p/notes~"))

  (testing "a directory whose own name matches is excluded, which is what the walk does"
    ;; Surprising and faithful: the walk tests each directory entry as it
    ;; descends, so a folder called `~odd` is one it never enters. Reproducing
    ;; that is the requirement — the two implementations agreeing matters more
    ;; than either being the rule someone would design today.
    (is (rg/excluded-path? ignore "/Users/~odd/p/src/a.cljs")))

  (testing "but a segment is tested on its own, not as part of a longer string"
    ;; `\\.class$` is anchored, so it can only ever match the last segment. On a
    ;; whole path a directory called `foo.class` would take every file under it
    ;; with it — segment-wise, only the ancestor itself is excluded, which is
    ;; also what the walk decides as it descends.
    (is (not (rg/excluded-path? ignore "/p/src/a.cljs")))
    (is (not (rg/excluded-path? ignore "/p/classes/a.cljs"))
        "a directory called classes is not a .class file")))

(deftest windows-paths-are-split-too
  (is (rg/excluded-path? ignore "C:\\p\\node_modules\\x\\y.js"))
  (is (not (rg/excluded-path? ignore "C:\\p\\src\\a.cljs"))))

(deftest no-pattern-excludes-nothing
  (is (not (rg/excluded-path? nil "/p/node_modules/x.js"))))

;;*********************************************************
;; Order
;;*********************************************************

(deftest files-come-back-in-a-stable-order
  ;; ripgrep searches in parallel, so the order it *reports* files in is not
  ;; stable between identical searches. The tree walk's order fell out of the
  ;; traversal and was therefore deterministic, and things depend on that —
  ;; which result `:searcher.next` visits first, and which one is on top of the
  ;; list. Results that reshuffle when nothing changed is how a feature comes to
  ;; feel broken without ever being wrong.
  ;;
  ;; Found by an e2e test that asserts the first result is the one in the parent
  ;; directory, failing about one run in three.
  (let [shuffled [{:file "/p/sub/two.txt" :line 1 :text "b"}
                  {:file "/p/one.txt" :line 1 :text "a"}]
        grouped (vec (sort-by :file (rg/group-by-file shuffled)))]
    (is (= ["/p/one.txt" "/p/sub/two.txt"] (mapv :file grouped))
        "and sorting is done here rather than with --sort path, which makes ripgrep single-threaded")))
