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

(defalias host [{:keys [content class style]}]
  [:div
   {:class class
    :style style
    ;; `on-render` rather than `on-mount`, because the content can change
    ;; without the element around it being replaced: a tabset shows whichever
    ;; tab is active in the same slot. It fires on both, and doing nothing when
    ;; the node is already the right one is what keeps that cheap.
    :replicant/on-render
    (fn [{:replicant/keys [node]}]
      (let [^js el node]
        (when-not (identical? content (.-firstChild el))
          ;; Removed rather than replaced wholesale: the old one is another
          ;; object's and may well be hosted again somewhere else.
          (when-let [old (.-firstChild el)]
            (.removeChild el old))
          (when content
            (.appendChild el content)))))}])
