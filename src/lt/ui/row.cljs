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
;;
;; `:leading` and `:trailing` are the two slots every one of the five surfaces
;; turned out to want: something that identifies the row before the label — a
;; dot, a disclosure twist, a line number — and something that qualifies it
;; after, which is always a count, a time or a hint and never a control.
;; `:style` is read rather than ignored, and that is not a convenience. An
;; alias receives the attrs of its call site as an argument and Replicant
;; merges none of them onto what it returns — so an attribute this does not
;; name is silently dropped. [[tree-row]] passed its indent that way and had
;; been drawing a flat tree ever since.
(defalias list-row
  [{:keys [selected? focused? tone origin leading trailing style on-select]} body]
  [:div.row
   {:class [(when selected? "row--selected")
            (when focused? "row--focus")
            (when tone (str "row--" (name tone)))]
    :style style
    :on {:click on-select}}
   (when leading [:span.row__leading leading])
   body
   (when (= origin :run) [:span.dot.dot--agent])
   (when trailing [:span.row__trailing trailing])])

;; the tree is list-row with depth. no icons — see the refused list.
;;
;; The disclosure twist is `:leading` rather than the first child, which is the
;; slot existing for exactly this: every row in the tree reserves the same
;; width for it, so a file and the folder above it start their names in the
;; same column.
(defalias tree-row [{:keys [depth open? dirty?] :as attrs} body]
  [::list-row (assoc attrs
                     :style {:padding-left (str (+ 8 (* 14 (or depth 0))) "px")}
                     :leading [:span.tree__twist (when (some? open?) (if open? "▾" "▸"))])
   body
   ;; Dirty is a dot, not a colour change: a modified file is still the same
   ;; file, and tinting the row would put it in the same vocabulary as a
   ;; conflict or a dead process.
   (when dirty? [:span.dot.dot--result])])
