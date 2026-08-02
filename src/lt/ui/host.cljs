(ns lt.ui.host
  "DOM another object owns, placed inside hiccup.

  The shape doc/rendering.md named as the one that cannot be swapped a
  component at a time: `map-bound` over a collection of objects, splicing
  `(object/->content %)`. Replicant renders hiccup, and a node another object
  owns is not hiccup — so a panel built that way stayed on singultus until the
  thing it was composing became a view.

  This is the way out that does not require that. The hiccup is an empty
  element and a hook, and what goes inside it belongs to whoever made it:
  Replicant is told nothing about the subtree and never diffs it. Same bargain
  as [[lt.ui.pane]], which hosts a CodeMirror, except that here the node
  already exists and something else is keeping it.

  ```clojure
  [::host/host {:class \"content\" :content (object/->content tab)}]
  ```

  It was proposed once before this and turned down for having no caller — the
  layout files it was meant for keep their bound roots either way, because a
  panel's width and height are object-owned geometry. What it is actually for
  is the case above.

  Moving a node is how it is placed, which is what `appendChild` does, so the
  same node can be hosted somewhere else later and a tabset can move a tab from
  one column to another. Nothing is destroyed on unmount: the object still owns
  it, and destroying it is `object/destroy!`'s job."
  (:require [replicant.alias :refer-macros [defalias]]))

(defalias host [{:keys [content class style tag]}]
  ;; `:tag` because where a host may appear is not up to it: an inline result
  ;; is a `span` inside a `span` and a block one is a `div`, and a `div` in the
  ;; middle of a line of code is a line break.
  [(or tag :div)
   {:class class
    :style style
    ;; `on-render` rather than `on-mount`, because the content can change
    ;; without the element around it being replaced: a tabset shows whichever
    ;; tab is active in the same slot. It fires on both, and doing nothing when
    ;; the node is already the right one is what keeps that cheap.
    :replicant/on-render
    (fn [{:replicant/keys [node]}]
      (let [^js el node
            wanted (cond
                     (nil? content) []
                     ;; One node or several. The sidebars keep every panel in
                     ;; the same element and show one by class, so a host that
                     ;; only took one would have had them reaching into their
                     ;; own DOM to append the rest.
                     (or (sequential? content) (seq? content)) (vec (remove nil? content))
                     :else [content])]
        (when-not (= wanted (vec (array-seq (.-childNodes el))))
          ;; Taken out rather than thrown away: each one is another object's
          ;; and may well be hosted again somewhere else.
          (while (.-firstChild el)
            (.removeChild el (.-firstChild el)))
          (doseq [^js n wanted]
            (.appendChild el n)))))}])
