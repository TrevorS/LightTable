(ns lt.objs.workspace-edit.text-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [lt.objs.workspace-edit.text :as we]))

(defn- edit [from-line from-ch to-line to-ch text]
  {:path "/x.clj"
   :from {:line from-line :ch from-ch}
   :to {:line to-line :ch to-ch}
   :text text})

(def sample "(ns probe)\n\n(defn add [a b] (+ a b))\n\n(add 20 22)\n")

(deftest one-edit
  (is (= "(ns probe)\n\n(defn plus [a b] (+ a b))\n\n(add 20 22)\n"
         (we/apply-to-text sample [(edit 2 6 2 9 "plus")]))))

(deftest two-edits-in-one-file
  ;; Given in document order, which is how a server sends them.
  (is (= "(ns probe)\n\n(defn plus [a b] (+ a b))\n\n(plus 20 22)\n"
         (we/apply-to-text sample
                           [(edit 2 6 2 9 "plus")
                            (edit 4 1 4 4 "plus")]))))

(deftest order-does-not-matter
  ;; The same edits shuffled produce the same document, which is what applying
  ;; last-first buys: no offset ever has to be adjusted for an earlier edit.
  (let [a (edit 2 6 2 9 "plus")
        b (edit 4 1 4 4 "plus")]
    (is (= (we/apply-to-text sample [a b])
           (we/apply-to-text sample [b a])))))

(deftest replacement-of-a-different-length
  (testing "longer"
    (is (= "(ns probe)\n\n(defn addition [a b] (+ a b))\n\n(addition 20 22)\n"
           (we/apply-to-text sample
                             [(edit 2 6 2 9 "addition")
                              (edit 4 1 4 4 "addition")]))))
  (testing "shorter"
    (is (= "(ns probe)\n\n(defn a [a b] (+ a b))\n\n(a 20 22)\n"
           (we/apply-to-text sample
                             [(edit 2 6 2 9 "a")
                              (edit 4 1 4 4 "a")])))))

(deftest an-insertion-is-an-empty-range
  (is (= "(ns probe)\n;; here\n\n(defn add [a b] (+ a b))\n\n(add 20 22)\n"
         (we/apply-to-text sample [(edit 1 0 1 0 ";; here\n")]))))

(deftest spanning-lines
  (is (= "(ns probe)\n\n(defn add [a b] 42)\n\n(add 20 22)\n"
         (we/apply-to-text sample [(edit 2 16 2 23 "42")]))))

(deftest empty-edits-change-nothing
  (is (= sample (we/apply-to-text sample []))))

(deftest ordered-puts-the-last-edit-first
  (let [a (edit 0 0 0 1 "a")
        b (edit 4 0 4 1 "b")
        c (edit 2 0 2 1 "c")]
    (is (= [b c a] (we/ordered [a b c]))))
  (testing "two starting in the same place are still ordered"
    (let [short-one (edit 1 0 1 2 "s")
          long-one (edit 1 0 1 5 "l")]
      (is (= [long-one short-one] (we/ordered [short-one long-one]))))))

(deftest a-file-without-a-trailing-newline
  (is (= "(a)\n(c)" (we/apply-to-text "(a)\n(b)" [(edit 1 1 1 2 "c")]))))
