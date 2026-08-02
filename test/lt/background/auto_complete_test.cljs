(ns lt.background.auto-complete-test
  "Tokenizing a buffer into completion candidates.

  The one thing this namespace does, off the main thread, on every request —
  and the reason it is worth a test rather than a reading is the empty-match
  guard: a pattern that can match nothing makes `RegExp.exec` return without
  advancing `lastIndex`, and the loop around it never ends. That is a hung
  worker thread, which from the window looks like completions that stopped
  arriving."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [lt.background.auto-complete :as ac]))

(defn- completions
  "What `tokenize` found, as a sorted seq of strings."
  [text pattern]
  (sort (map #(aget % "completion") (ac/tokenize text pattern))))

(deftest a-run-of-pattern-characters-is-one-candidate
  (is (= ["one" "three" "two"] (completions "one two three" "[\\w]")))
  (testing "and the pattern says what a word is, so punctuation can be in one"
    (is (= ["a-b" "c"] (completions "a-b c" "[\\w-]")))
    (is (= ["a" "b" "c"] (completions "a-b c" "[\\w]")))))

(deftest the-same-token-twice-is-one-candidate
  ;; A buffer is mostly repetition — this is what stops a completion list being
  ;; as long as the file.
  (is (= ["def" "let"] (completions "let def let def let" "[\\w]")))
  (testing "case is not folded, because a symbol's case is part of its name"
    (is (= ["Let" "let"] (completions "let Let" "[\\w]")))))

(deftest nothing-in-is-nothing-out
  (is (= [] (completions "" "[\\w]")))
  (is (= [] (completions "   " "[\\w]")))
  (testing "and text with no run of the pattern in it at all"
    (is (= [] (completions "!!!" "[\\w]")))))

(deftest a-pattern-that-can-match-nothing-still-terminates
  ;; The guard. `(?:[\w]*)+` matches the empty string at every position, so
  ;; `exec` returns a zero-length match without moving `lastIndex` — and the
  ;; loop asking for the next match asks forever. If this test hangs rather
  ;; than fails, that is the bug it is about.
  (is (= ["ab"] (completions "ab" "[\\w]*")))
  (is (= [] (completions "..." "[\\w]*"))))
