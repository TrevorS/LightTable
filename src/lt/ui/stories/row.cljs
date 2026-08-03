(ns lt.ui.stories.row
  "What the two row components are, as data.

  Two aliases and five surfaces built out of them, which is the claim the
  catalogue makes about this namespace and the reason the states below are
  worth naming: `selected?` and `focused?` are not the same thing, and a row
  that tints for a warning does not also grow a left border."
  (:require [lt.ui.row :as row]
            [lt.ui.story :as story]))

(story/of ::row/list-row
  {:doc "The one row primitive. Tree, queue, runs, commands, connections are all this."
   :props [[":selected?" "boolean" ""]
           [":focused?" "boolean" "a keyboard has both"]
           [":tone" ":warning | :error | :agent | :disabled" ""]
           [":leading" "node?" "dot, disclosure or line number"]
           [":trailing" "node?" "count, time or hint"]
           [":on-select" "actions" "clicking it"]
           [":on-menu" "actions" "right-clicking it, which is the only other thing"]]
   :usage "view/workspace · view/review-queue · view/command-bar · view/settings"
   :note "no left-border accent, ever — the whole row tints or nothing does"
   :states (array-map
            :rest {:children ["rest"]}
            :selected {:selected? true
                       :children ["selected — element.selected, sky @ 15%"]}
            :focused {:focused? true
                      :children ["focused — a ring, because focus is not selection"]}
            :disabled {:tone :disabled :children ["unavailable — text.disabled"]}
            :warning {:tone :warning :children ["touches your edit — warning.tint"]}
            :error {:tone :error :children ["the process died — error.tint"]}
            :agent {:tone :agent :origin :run :trailing "6"
                    :children ["port fuzzy to ranges"]})})

(story/of ::row/tree-row
  {:doc "list-row with depth and disclosure. Dirty is a dot, not a colour change."
   :props [[":name" "string" "as the body"]
           [":depth" "number" "14px each"]
           [":open?" "boolean?" "folders only"]
           [":dirty?" "boolean" "a dot, right-aligned"]]
   :usage "view/workspace — the tree in the left sidebar"
   :note "indent 14px per level · dirty dot is sky · counts only on collapsed folders"
   :states (array-map
            :open-folder {:depth 0 :open? true :children ["src-worker"]}
            :file {:depth 1 :children ["walkdir.ts"]}
            :dirty {:depth 1 :dirty? true :selected? true :children ["fuzzy.ts"]}
            :collapsed {:depth 0 :open? false :trailing "12" :children ["src-window"]})})
