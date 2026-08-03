(ns lt.ui.stories.host
  "What `host` is, as data.

  The one component whose whole job is to contain something this registry
  cannot hold. `:content` is a DOM node another object owns, so the states here
  are functions rather than values — see [[lt.ui.story/hiccup-for]]. Building a
  node at namespace load would make this file unreadable under node, which is
  where the story manifest is produced."
  (:require [lt.ui.host :as host]
            [lt.ui.story :as story]))

(defn- node
  "A node standing in for one an object owns. Not a component and not pretending
  to be: what is under test is that `host` places what it is given and leaves it
  alone, and a paragraph proves that as well as a CodeMirror would."
  [text]
  (let [el (js/document.createElement "p")]
    (set! (.-textContent el) text)
    (set! (.-className el) "kit__hosted")
    el))

(story/of ::host/host
  {:doc "DOM another object owns, placed inside hiccup. Replicant is told
         nothing about the subtree and never diffs it."
   :props [[":content" "node | node[]" "what to place, owned by somebody else"]
           [":tag" "keyword" "div by default — an inline result is a span in a span"]
           [":class" "string" ""]
           [":style" "map" ""]]
   :usage "the tabsets, the sidebars, every inline result"
   :badge "hosts foreign DOM"
   :width :wide
   :states (array-map
            :one {:hiccup #(vector ::host/host {:content (node "a node another object owns")})}
            :several {:hiccup #(vector ::host/host
                                       {:content [(node "first") (node "second")]})}
            :inline {:hiccup #(vector :span "before "
                                      [::host/host {:tag :span :content (node "inline")}]
                                      " after")})})

;; There is no `:empty` state, and the reason is worth writing down rather than
;; leaving as an absence. `host` with nil content correctly renders an empty
;; element — that is the right behaviour and there is nothing to look at, so a
;; card for it would be a blank rectangle. `script/check-stories.mts` fails a
;; story that draws nothing, which is the check doing its job on a state that
;; should not have been a story.
