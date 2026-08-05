(ns lt.ui.field
  "The controls a settings screen is made of: six aliases that carry no state.

  These exist because of one sentence in doc/direction.md — *a settings
  experience that does not require knowing what a behavior is before changing
  the font* — and the interesting part is how little they had to invent.

  A Light Table behavior has declared its own parameters since 2013:

  ```clojure
  (behavior ::set-font
            :desc \"Editor: Set font\"
            :type :user
            :params [{:label \"Font family\" :type :string}
                     {:label \"Size (pt)\" :type :number}])
  ```

  So a control per parameter is a lookup on `:type` rather than a form somebody
  wrote: `:string` is a text box, `:number` is a number box, `:boolean` is a
  toggle, `:list` is a choice over whatever `:items` returns. That is the whole
  of [[control]], and it is why the settings screen is a projection and a view
  rather than a subsystem.

  The parameters that have no `:type` are the honest gap — 21 of the 49 settable
  behaviors say only `:label`, some with an `:example`. Those get a text box and
  are read back with `cljs.reader`, which is exactly what editing the file by
  hand does, so nothing is lost and nothing is claimed. A control that guessed
  `[1 80]` was a number would corrupt a ruler setting.

  Handlers are vectors, like everywhere else — see [[lt.actions]]. A field emits
  `[[:settings/set tag behavior i :event/value]]` and the placeholder is filled
  in by the one place that has both the action and the event."
  (:require [clojure.string :as string]
            [replicant.alias :refer-macros [defalias]]))

;; ---------------------------------------------------------------------------
;; The controls
;; ---------------------------------------------------------------------------

;; A label and its control, which is the unit the screen lays out. The label is
;; a real `<label>` wrapping the control rather than a `for=` pointing at an id:
;; ids would have to be generated, generated ids are not stable across a
;; re-render, and clicking a label whose id moved focuses the wrong box.
(defalias field [{:keys [label note]} body]
  [:label.field
   [:span.field__label label]
   body
   (when note [:span.field__note note])])

(defalias text-input [{:keys [value placeholder on-change]}]
  [:input.field__input {:type "text"
                        :value (str value)
                        :placeholder placeholder
                        ;; `change` and not `input`: every keystroke would be a
                        ;; write to user.behaviors and a `:behaviors.reload`,
                        ;; which means reloading the editor's configuration
                        ;; eleven times while you type a font name.
                        :on {:change on-change}}])

(defalias number-input [{:keys [value placeholder on-change]}]
  [:input.field__input.field__input--number
   {:type "number"
    :value (str value)
    :placeholder placeholder
    :on {:change on-change}}])

;; A checkbox, drawn as one. A switch would be prettier and would also be a
;; component that has to explain which way is on.
(defalias toggle [{:keys [on? on-change]}]
  [:input.field__toggle {:type "checkbox"
                         :checked (boolean on?)
                         :on {:change on-change}}])

(defalias choice [{:keys [value items on-change]}]
  [:select.field__select {:value (str value)
                          :on {:change on-change}}
   (for [item items
         :let [v (if (coll? item) (first item) item)
               label (if (coll? item) (second item) item)]]
     [:option {:replicant/key (str v) :value (str v) :selected (= (str v) (str value))}
      (str label)])])

;; Where a value came from, when it did not come from the defaults. The one
;; thing a settings screen usually cannot tell you and this one can, because
;; `lt.objs.settings/where-from` has recorded it since provenance was added.
;;
;; Always an element, empty when there is nothing to say, and hidden by
;; `.field__source:empty` rather than by returning nil. **An alias that returns
;; nil cannot be rendered**: Replicant takes the return value as the node, so a
;; nil one becomes `createElement(":lt.ui.field/source")` and throws
;; `InvalidCharacterError` from inside its own render — which it catches, logs as
;; *you may have misbehaving aliases*, and then skips the whole render.
;;
;; That is not a hypothetical. `setting-row` passes `:from` for every setting and
;; most settings are the default, so the first unchanged one would have taken the
;; screen with it. `script/check-stories.mts` is what found it, from the state
;; written to show that the common case draws nothing.
(defalias source [{:keys [from]}]
  [:span.field__source
   (when from
     (let [cut (string/last-index-of (str from) "/")]
       (if cut (subs from (inc cut)) from)))])

;; ---------------------------------------------------------------------------
;; Choosing one
;; ---------------------------------------------------------------------------

(defn items-of
  "A `:list` parameter's options.

  Data by the time it gets here: `:items` is a function in every declaration in
  the tree — `get-themes` reads what the plugins provided — and
  [[lt.state.objects/settings-entries]] resolves it, because a function in the
  state atom makes the window permanently disagree with itself under
  `lt.objs.control/drift`.

  The function branch is kept anyway, for a caller handing a raw behavior
  parameter straight to [[control]]. Dropping it would turn that into an empty
  list rather than an error, and a silently empty select is worse than a
  redundant line."
  [param]
  (let [items (:items param)]
    (cond
      (coll? items) (vec items)
      (fn? items) (try (vec (items)) (catch :default _ []))
      :else [])))

(defn control
  "The control for one parameter, given its declaration and its current value.

  `on-change` is the action vector to emit, with `:event/value` or
  `:event/checked` already in it — the caller knows which parameter it is and
  this knows which kind of event carries the answer, so neither has to know
  both."
  [param value on-change]
  (case (:type param)
    :boolean [::toggle {:on? value :on-change on-change}]
    :number [::number-input {:value value
                             :placeholder (str (:example param ""))
                             :on-change on-change}]
    :list [::choice {:value value :items (items-of param) :on-change on-change}]
    ;; `:string` and, deliberately, everything with no `:type` at all.
    [::text-input {:value value
                   :placeholder (str (:example param ""))
                   :on-change on-change}]))

(defn value-placeholder
  "Which event placeholder carries a control's new value.

  A checkbox answers with `checked` and everything else with `value`. One line,
  in one place, so that a field and its action cannot disagree about it."
  [param]
  (if (= :boolean (:type param)) :event/checked :event/value))
