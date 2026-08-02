(ns lt.support.hiccup
  "Walking hiccup, for the tests that assert on what a view drew.

  Hiccup is data, which is the whole reason the view layer is shaped the way it
  is — a map goes in and a tree of vectors comes out, so an assertion is a
  question about a value rather than about a rendered document. These are the
  questions that get asked.

  Not a `-test` namespace, so the runner does not try to run it: shadow-cljs
  selects test namespaces by `-test$` and this is support."
  (:require [clojure.string :as string]))

(defn nodes
  "Every vector in `hiccup`, depth first.

  Hiccup is a tree of vectors and seqs — `for` returns a seq, and a view is
  full of them — so this flattens both into the nodes to assert about."
  [hiccup]
  (cond
    (vector? hiccup) (cons hiccup (mapcat nodes hiccup))
    (seq? hiccup) (mapcat nodes hiccup)
    :else nil))

(defn find-all
  "Every node whose tag is `tag`.

  The tag of an alias is its keyword — `:lt.ui.chrome/tab` — so this is how a
  test asks which components a view used, without expanding any of them."
  [hiccup tag]
  (filter #(= tag (first %)) (nodes hiccup)))

(defn attrs-of
  "The attribute map of `node`, or nil if it has none."
  [node]
  (let [a (second node)]
    (when (map? a) a)))

(defn text-of
  "Every string in the tree, joined — what a person would read.

  Attribute maps are not descended into: a key and a handler are both data a
  reader never sees, and counting them as text made `:replicant/key` look like
  a label."
  [hiccup]
  (->> (tree-seq #(and (coll? %) (not (map? %))) seq hiccup)
       (filter string?)
       (string/join " ")))

(defn classes-in
  "Every class `hiccup` names anywhere, however it was written.

  Three ways to write one and a test has to see all three: a keyword tag's own
  classes — `:div.row.row--selected` — a `:class` string, or a `:class` vector
  with nils in it, which is what a component produces when it writes
  `(when selected? \"row--selected\")`. A test that looked in one place would
  pass while the component named the class in another."
  [hiccup]
  (->> (nodes hiccup)
       (mapcat (fn [node]
                 (let [tag (name (first node))
                       c (:class (attrs-of node))]
                   (concat (rest (string/split tag #"\."))
                           (cond
                             (string? c) (string/split c #"\s+")
                             (coll? c) (mapcat #(when (string? %) (string/split % #"\s+")) c)
                             :else nil)))))
       (remove string/blank?)
       set))
