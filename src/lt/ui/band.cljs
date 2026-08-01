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

;; **Values are not always data.** A table renders from EDN. A plot is a
;; canvas, an HTML embed is a sandboxed frame, and a stream arrives in pieces.
;; Those are hosted widgets *inside* the band: mounted once and fed
;; imperatively, because describing a canvas as hiccup is describing the wrong
;; thing. So the band dispatches on `:mime` and hands off.
(def ^:private renders-as-text
  #{nil "" "application/edn" "text/plain" "application/json"})

(defn- host
  "A node the band owns and Replicant does not look inside.

  `mount` is called once with the node, and whatever it puts there stays there.
  The same mechanism the editor pane uses, at a smaller scale."
  [mount]
  [:div.band__host
   {:replicant/on-mount (fn [{:replicant/keys [node]}] (mount node))}])

(defn- html-embed
  "A sandboxed frame. Sandboxed because a value is not trusted markup — it came
  from whatever was evaluated, which may be anything."
  [html]
  (host (fn [node]
          (let [frame (js/document.createElement "iframe")]
            (.setAttribute frame "sandbox" "")
            (.setAttribute frame "class" "band__frame")
            (.appendChild node frame)
            (set! (.-srcdoc frame) (str html))))))

(defn- image-embed [mime data]
  (host (fn [node]
          (let [img (js/document.createElement "img")]
            (.setAttribute img "class" "band__image")
            (set! (.-src img) (str "data:" mime ";base64," data))
            (.appendChild node img)))))

(defn value-content
  "How a value of `mime` is shown: as text, or handed to something that can.

  Public because it is the decision, not the markup — a plugin that teaches
  Light Table a new mime is changing this and nothing else."
  [mime value]
  (cond
    (contains? renders-as-text mime) [:div.band__value (str value)]
    (= mime "text/html") (html-embed value)
    (and (string? mime) (re-find #"^image/" mime)) (image-embed mime value)
    :else [:div.band__note (str mime " — nothing here can draw that yet")]))

;; A value beside the line that produced it. Addressed by [path line], which is
;; what lets it be promoted to a watch with one gesture.
(defalias result [{:keys [line status value mime note against-unapplied]}]
  [:div.band.band--result
   (gutter line)
   [:div.band__body
    (when (and status (not= status :finished))
      [::chrome/status {:status status :pulse (= status :executing)} (name status)])
    (when value (value-content mime value))
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
