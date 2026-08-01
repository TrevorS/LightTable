(ns lt.ui.row
  "The one row primitive.

  Tree, queue, runs, commands and connections are all this. Five surfaces are
  one component, which is the reason to get it right and the reason there is a
  namespace for it alone.

  Two rules the kit enforces, both visible here: a row tints entirely or not at
  all — no left-border accent strips — and no component owns a colour. `:tone`
  names a role and `deploy/core/css/kit.css` resolves it."
  (:require [replicant.alias :refer-macros [defalias]]))

;; tone is a role, never a colour. the row tints whole or not at all.
(defalias list-row
  [{:keys [selected? focused? tone origin trailing on-select]} body]
  [:div.row
   {:class [(when selected? "row--selected")
            (when focused? "row--focus")
            (when tone (str "row--" (name tone)))]
    :on {:click on-select}}
   body
   (when (= origin :run) [:span.dot.dot--agent])
   (when trailing [:span.row__trailing trailing])])

;; the tree is list-row with depth. no icons — see the refused list.
(defalias tree-row [{:keys [depth open? dirty?] :as attrs} body]
  [::list-row (assoc attrs :style
                     {:padding-left (str (+ 8 (* 14 (or depth 0))) "px")})
   [:span.tree__twist (when (some? open?) (if open? "▾" "▸"))]
   body
   (when dirty? [:span.dot.dot--result])])
