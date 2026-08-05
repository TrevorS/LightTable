(ns lt.objs.providers-test
  "Who answers a surface.

  Worth testing away from a window because the symptom of getting it wrong is
  a feature quietly not appearing: an LSP surface that stands down for a REPL
  that was never going to answer, or two things drawing into one doc bar."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [lt.objs.providers :as providers]))

(def repl
  "A client of the shape lt.plugins.clojure.nrepl registers, which predates
  `:provides` and has to keep working."
  {:name "LightTable-REPL"
   :commands [:editor.eval.clj :editor.clj.doc :editor.clj.hints]})

(def declared
  {:name "Something newer"
   :provides #{:doc :code-action}
   :commands [:editor.eval.clj :editor.clj.doc :editor.clj.hints]})

(deftest a-client-that-declares-is-taken-at-its-word
  (is (providers/provides? declared :doc))
  (is (providers/provides? declared :code-action))
  (is (not (providers/provides? declared :completion))
      "a declaration says what a client does not do as well as what it does")
  (is (not (providers/provides? declared :jump))))

(deftest a-client-that-declares-is-not-read-behind-its-back
  ;; It advertises :editor.clj.hints, which the suffix rule would call a
  ;; completion provider. Having said what it provides, it is not second-guessed.
  (is (providers/infers? declared :completion))
  (is (not (providers/provides? declared :completion))))

(deftest a-client-that-declares-nothing-is-read-from-its-commands
  (is (providers/provides? repl :doc))
  (is (providers/provides? repl :completion))
  (is (not (providers/provides? repl :jump))
      "the Clojure REPL has no jump command, so the language server keeps it"))

(deftest the-suffix-is-matched-whatever-the-language
  (testing "the command belongs to the language, so only the ending is common"
    (is (providers/provides? {:commands [:editor.python.doc]} :doc))
    (is (providers/provides? {:commands [:editor.rust.doc]} :doc))
    (is (providers/provides? {:commands [:editor.clj.jump-to-definition]} :jump))))

(deftest a-command-that-merely-contains-the-word-is-not-a-match
  (is (not (providers/provides? {:commands [:editor.doc.toggle]} :doc))
      "ends-with, not contains — the doc bar's own toggle is not a provider"))

(deftest an-unknown-surface-provides-nothing
  (is (not (providers/provides? repl :teleportation)))
  (is (nil? (providers/provider [repl declared] :teleportation))))

(deftest the-first-client-that-answers-wins
  (is (= declared (providers/provider [declared repl] :doc)))
  (is (= repl (providers/provider [repl declared] :doc)))
  (is (= repl (providers/provider [declared repl] :completion))
      "the one that declared :doc only is skipped for completions"))

(deftest nothing-connected-provides-nothing
  (is (not (providers/provided? [] :doc)))
  (is (nil? (providers/provider [] :doc))))

(deftest an-empty-declaration-means-it-provides-nothing
  ;; `:provides #{}` is a client saying "I answer none of these", which is
  ;; different from saying nothing at all.
  (let [quiet {:provides #{} :commands [:editor.clj.doc]}]
    (is (not (providers/provides? quiet :doc)))
    (is (providers/infers? quiet :doc))))

;;*********************************************************
;; Can a client be evaluated through?
;;*********************************************************

;; The guard `lt.objs.eval/bind!` needs before it makes a client the one a buffer
;; evaluates through. It is the only guard that matters there: `get-client!`
;; reuses whatever is bound if it is merely *available*, and never asks whether
;; it can serve the command being sent.

(deftest a-client-that-advertises-an-eval-command-can-be-bound
  (is (providers/evaluates? {:commands #{:editor.eval.cljs}}))
  (is (providers/evaluates? {:commands #{:editor.eval.python}}))
  (is (providers/evaluates? {:commands #{:editor.eval.cljs.exec}})))

(deftest a-client-with-no-eval-command-cannot
  (testing "a language server answers about code and does not run it"
    (is (not (providers/evaluates? {:commands #{:editor.clj.doc :editor.clj.hints}}))))
  (is (not (providers/evaluates? {:commands #{}})))
  (is (not (providers/evaluates? {})))
  (testing "and a name that merely contains the prefix is not one"
    ;; A prefix rather than a substring, because `editor.eval` at the front is
    ;; what an evaluation command is; anywhere else it is a coincidence.
    (is (not (providers/evaluates? {:commands #{:my.editor.eval.thing}})))))

(deftest the-eval-commands-are-reported-so-a-refusal-can-say-why
  (is (= [:editor.eval.cljs :editor.eval.cljs.exec]
         (vec (providers/eval-commands
               {:commands #{:editor.eval.cljs.exec :editor.clj.doc :editor.eval.cljs}}))))
  (testing "sorted, because it is shown to a person"
    (is (= [:editor.eval.a :editor.eval.b]
           (vec (providers/eval-commands {:commands #{:editor.eval.b :editor.eval.a}}))))))

(deftest evaluating-is-not-one-of-the-declared-surfaces
  ;; `:provides` is taken as complete for doc, completion, jump and code-action —
  ;; a client that lists them is saying what it does *not* do. Evaluation is not
  ;; in that vocabulary, so a client declaring `:provides #{:doc}` and
  ;; advertising an eval command can still be evaluated through.
  (let [c {:provides #{:doc} :commands #{:editor.eval.cljs}}]
    (is (providers/provides? c :doc))
    (is (not (providers/provides? c :completion)))
    (is (providers/evaluates? c)
        "declaring what it answers says nothing about whether it runs code")))
