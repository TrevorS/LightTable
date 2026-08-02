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

(defn gutter
  "The line number column, reserved so a band's content lands on the code.

  Not an alias, which is the distinction the kit sorts by: the registry is the
  set of components, and this is not one. Nothing in a window calls it except
  the bands below.

  Public only because [[lt.ui.catalogue]] draws it, and a catalogue card that
  hand-rolled this markup instead would be the exact drift the catalogue exists
  to prevent — a picture of a component that is not the component.

  A marker *replaces* the number rather than sitting beside it: there is one
  column, and a line with something wrong with it has that instead of its
  address. No band passes one. Diagnostics in this editor are drawn inline,
  beside the code, rather than as a mark in a column you then have to hover —
  see [[lt.objs.editor.lsp]], where that is the whole argument. The column
  still takes a marker because the gutter of an *editor* line is where one
  would go, and that column is drawn by CodeMirror rather than by this."
  ([line] (gutter line nil))
  ([line {:keys [active? marker]}]
   [:div.band__gutter {:class [(when active? "band__gutter--active")
                               (when marker "band__gutter--marked")]}
    (if marker
      [:span.dot {:class (str "dot--" (name marker))}]
      line)]))

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
;;
;; Seven states, and the one that draws nothing is the interesting one: queued
;; shows no band at all, because a value that has not been asked for yet has
;; nothing to say and the gutter dot already says it is coming.
(defalias result [{:keys [line status value mime note stale? against-unapplied]}]
  [:div.band.band--result {:class (when stale? "band--stale")}
   (gutter line)
   [:div.band__body
    (when (and status (not= status :finished))
      [::chrome/status {:status status :pulse (= status :executing)} (name status)])
    ;; The last value it had, marked as such. Clearing the band when the
    ;; process dies would be throwing away the only thing you still know.
    ;;
    ;; The mark rides in the same row as the value rather than under it:
    ;; `value-content` returns a block — it has to, a table or a frame is not
    ;; an inline thing — so a sibling span would wrap to its own line and read
    ;; as a second, unrelated fact about the band.
    (when value
      (if stale?
        [:div.band__row (value-content mime value) [:span.band__stale "stale"]]
        (value-content mime value)))
    (when against-unapplied
      [:div.band__note (str "computed against " against-unapplied
                            " unapplied edit" (when-not (= 1 against-unapplied) "s"))])
    (when note [:div.band__note note])]])

;; A value under observation. Teal, because it re-reads itself.
(defalias watch [{:keys [line expression value history reads]}]
  [:div.band.band--watch
   (gutter line)
   [:div.band__body
    (when expression [:span.band__code expression])
    ;; A watch inside a loop is a sequence, not a value. Showing only the last
    ;; read makes a recurrence look like a constant.
    (if (seq history)
      [:span.band__history
       (map-indexed (fn [i v] [:span.band__read {:replicant/key i} (str v)]) history)]
      [:span.band__value value])
    (when reads [:span.band__reads (str " · " reads " reads")])]])

;; The whole argument of the design: what the value was, and what it becomes.
;;
;; `:rows` rather than a fixed before/after pair, because the evidence for an
;; edit is rarely two lines: what it returned before, what it returns after,
;; and what the type checker says about it are three separate claims, and a
;; component that could only hold two would have decided which one to drop.
;;
;; `:ran-at` is an age in milliseconds, not a timestamp. Reading the clock
;; inside a component would make it render differently on two calls with the
;; same arguments, and every test of this file depends on that not happening.
(defalias evidence [{:keys [label rows ran-at client before after]}]
  (let [rows (or (seq rows)
                 (when (or before after)
                   [{:label "before" :value before :tone :before}
                    {:label "after" :value after :tone :after}]))]
    [:div.evidence
     [:div.evidence__label
      (or label "Evidence")
      (when ran-at [:span.evidence__age (str " · run " (chrome/ago ran-at))])
      (when client [:span.evidence__client (str " through " client)])]
     (map-indexed
      ;; `:label` on a row rather than `:side`, because that is what the props
      ;; table calls it — the binding is renamed here only so it does not shadow
      ;; the block's own label three lines up.
      (fn [i {side :label :keys [expr value tone]}]
        [:div.evidence__row {:replicant/key i}
         [:span.evidence__side side]
         (when expr [:span.evidence__expr expr])
         [:span.evidence__value
          {:class (when tone (str "evidence__value--" (name tone)))}
          value]])
      rows)]))

;; Struck original, tinted replacement, both on the same code column.
(defalias proposed-edit [{:keys [line before after]} body]
  [:div.band.band--agent
   (gutter line)
   [:div.band__body
    [:div.edit__before before]
    [:div.edit__after after]
    body]])

;; You edited a line a run had already read. Routine, not an error — which is
;; why it is a warning tone and why the age is shown: the answer is usually
;; obvious once you remember what you typed and when.
(defalias conflict [{:keys [line yours theirs age note]} body]
  [:div.band.band--conflict
   (gutter line)
   [:div.band__body
    (when yours
      [:div.band__code yours
       (when age [:span.band__age (str "  ; you changed this " (chrome/ago age))])])
    (when theirs [:div.band__code.band__code--theirs theirs])
    (when note [:div.band__note note])
    body]])

;; An LSP diagnostic, in the buffer, with the reason beside the line.
;; No left border — Zed uses one here, this does not.
(defalias diagnostic [{:keys [line severity code message fix]}]
  (let [severity (or severity :error)]
    [:div.band.band--diagnostic {:class (str "band--" (name severity))}
     (gutter line)
     [:div.band__body
      (when code [:span.diagnostic__code (str code "  ")])
      [:span.diagnostic__message message]
      ;; Only when the server offers one. A band that always had a button
      ;; would be inventing fixes it cannot perform.
      (when fix [:div.diagnostic__fix fix])]]))
