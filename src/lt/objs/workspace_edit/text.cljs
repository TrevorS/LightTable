(ns lt.objs.workspace-edit.text
  "Applying a list of edits to a string.

  The pure half of [[lt.objs.workspace-edit]], and separate from it for the
  reason `lt.objs.clients.lsp.wire` is separate from the connection that uses
  it: this is where the arithmetic is, arithmetic is what gets edits wrong, and
  a function of a string and a list is something a test can hold."
  (:require [clojure.string :as string]))

(defn- before?
  "Whether position `a` comes before `b`."
  [a b]
  (or (< (:line a) (:line b))
      (and (= (:line a) (:line b)) (< (:ch a) (:ch b)))))

(defn ordered
  "`edits` sorted so the last one in the document is applied first.

  Ties broken by `:to`, so two edits starting at the same place still have a
  defined order rather than whichever the sort happened to leave."
  [edits]
  (sort (fn [a b]
          (cond
            (before? (:from a) (:from b)) 1
            (before? (:from b) (:from a)) -1
            (before? (:to a) (:to b)) 1
            (before? (:to b) (:to a)) -1
            :else 0))
        edits))

(defn- offset
  "The index into `lines` of position `pos`, counting the newlines between."
  [lines {:keys [line ch]}]
  (+ ch (reduce + (map #(inc (count %)) (take line lines)))))

(defn apply-to-text
  "`text` with `edits` applied.

  Pure, and the only part of this namespace that is — which is why it is the
  part with tests. Everything else is a file or a buffer."
  [text edits]
  (let [lines (string/split text #"\n" -1)]
    (reduce (fn [t {:keys [from to text]}]
              (str (subs t 0 (offset lines from))
                   text
                   (subs t (offset lines to))))
            text
            (ordered edits))))

