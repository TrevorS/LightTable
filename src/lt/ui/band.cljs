(ns lt.ui.band
  "The six things that live between two lines of code.

  Aliases like [[lt.ui.chrome]]'s, and they never appear in the chrome tree at
  all: the editor hands us a DOM node per line widget and we render into it.
  That is the one structural claim of the design — not one render root, but one
  for the chrome plus one per visible band.

  The discipline it costs, and the kit already pays it: a band's hiccup must be
  complete from its props alone, with no inherited context. So `::result` is
  the same alias whether it renders in the catalogue, in a documentation page,
  or inside a line widget in a live buffer."
  (:require [lt.ui.chrome :as chrome]
            [replicant.alias :refer-macros [defalias]]))

(defn- gutter
  "The line number column, reserved so a band's content lands on the code."
  [line]
  [:div.band__gutter line])

;; A value beside the line that produced it. Addressed by [path line], which is
;; what lets it be promoted to a watch with one gesture.
(defalias result [{:keys [line status value note against-unapplied]}]
  [:div.band.band--result
   (gutter line)
   [:div.band__body
    (when (and status (not= status :finished))
      [::chrome/status {:status status :pulse (= status :executing)} (name status)])
    (when value [:div.band__value value])
    (when against-unapplied
      [:div.band__note (str "computed against " against-unapplied
                            " unapplied edit" (when-not (= 1 against-unapplied) "s"))])
    (when note [:div.band__note note])]])

;; A value under observation. Teal, because it re-reads itself.
(defalias watch [{:keys [line expression value reads]}]
  [:div.band.band--watch
   (gutter line)
   [:div.band__body
    (when expression [:span.band__code expression])
    [:span.band__value value]
    (when reads [:span.band__reads (str " · " reads " reads")])]])

;; The whole argument of the design: what the value was, and what it becomes.
(defalias evidence [{:keys [label before after]}]
  [:div.evidence
   (when label [:div.evidence__label label])
   [:div.evidence__row
    [:span.evidence__side "before"]
    [:span.evidence__before before]]
   [:div.evidence__row
    [:span.evidence__side "after"]
    [:span.evidence__after after]]])

;; Struck original, tinted replacement, both on the same code column.
(defalias proposed-edit [{:keys [line before after]} body]
  [:div.band.band--agent
   (gutter line)
   [:div.band__body
    [:div.edit__before before]
    [:div.edit__after after]
    body]])

;; You edited a line a run had already read. Routine, not an error.
(defalias conflict [{:keys [line yours note]} body]
  [:div.band.band--conflict
   (gutter line)
   [:div.band__body
    (when yours [:div.band__code yours])
    (when note [:div.band__note note])
    body]])

;; An LSP diagnostic, in the buffer, with the reason beside the line.
;; No left border — Zed uses one here, this does not.
(defalias diagnostic [{:keys [line code message]}]
  [:div.band.band--diagnostic
   (gutter line)
   [:div.band__body
    (when code [:span.diagnostic__code (str code "  ")])
    [:span.diagnostic__message message]]])
