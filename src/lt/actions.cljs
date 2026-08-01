(ns lt.actions
  "The dispatch table, which is the behavior registry.

  A handler is a vector, not a closure: `[:review/goto 3]`. That is the whole
  trick — the handler is data, so it is inspectable, loggable, replayable, and
  rewritable from a settings screen while the editor runs.

  This is the claim the modernization rests on, and it is not an analogy.
  `deploy/settings/default/default.behaviors` is already a file of
  `[tag behavior-keyword]` pairs merged from every plugin on load; Light Table
  has kept its behaviors as data since 2013. Replicant's actions-as-data is
  therefore not merely convenient here, it is the same design arriving from the
  other direction, and the two registries can be one.

  Because handlers are vectors, three surfaces fall out of one data structure:
  the keymap is a map from key to action, the settings screen is a view over
  that map, and the command bar is a fuzzy search across its keys. The work is
  in presenting it, not building it.

  ## Kinds

  An action is `state only` or `state + effect`. The first is a pure function
  of state and arguments and is trivially testable; the second returns effects
  as data too, so what an action *would* do can be read without doing it."
  (:require [lt.state :as state]
            [replicant.dom :as r]))

(defonce ^:private registry (atom {}))

(defonce ^:private report
  ;; Where a failure goes. `lt.object/safe-report-error` puts it in the error
  ;; ring the control surface reads, but this namespace must stay loadable
  ;; without a window so the actions can be tested by calling them — so the
  ;; reporter is installed rather than required.
  (atom (fn [e] (js/console.error e))))

(defn report-with!
  "Send failures to `f` instead of the console."
  [f]
  (reset! report f))

(defn register!
  "Register `f` for action `kind`.

  `f` is `(fn [state & args] …)` returning `{:state s :effects [[…]]}`, or just
  the new state for the common case that has no effects."
  [kind f]
  (swap! registry assoc kind f))

(defn registered
  "Every action there is, as data. The settings screen is a view over this."
  []
  @registry)

(defn- ->outcome [result before]
  (cond
    (nil? result) {:state before :effects []}
    (map? (:state result)) {:state (:state result) :effects (vec (:effects result))}
    :else {:state result :effects []}))

(defn apply-action
  "`state` after `action`, and the effects it asks for. Pure.

  Separate from [[dispatch!]] so an action can be tested by calling it, and so
  a run can be replayed by folding a list of actions over a starting state."
  [state [kind & args :as action]]
  (if-let [f (get @registry kind)]
    (->outcome (apply f state args) state)
    {:state state
     :effects [[:error/unknown-action action]]}))

(defonce ^:private effect-handlers (atom {}))

(defn register-effect!
  "Register the side effect `kind`. Kept apart from the actions so that the
  registry above stays a table of pure functions."
  [kind f]
  (swap! effect-handlers assoc kind f))

(defn dispatch!
  "Run `actions` against the state atom, in order, then perform their effects.

  The only writer of [[lt.state/app]]. One place to log from, one place to
  replay through, and one place a `[:behavior/rebind …]` has to reach."
  [actions]
  (let [folded (reduce (fn [acc action]
                         (let [outcome (apply-action (:state acc) action)]
                           {:state (:state outcome)
                            :effects (into (:effects acc) (:effects outcome))}))
                       {:state @state/app :effects []}
                       actions)]
    ;; Reset rather than swap: the fold is already done, and a swap whose
    ;; function collected effects would collect them twice if it ever retried.
    (reset! state/app (:state folded))
    (doseq [[kind & args :as effect] (:effects folded)]
      (if-let [f (get @effect-handlers kind)]
        (try
          (apply f args)
          (catch :default e
            (@report e)))
        (@report (str "No handler for effect " (pr-str effect)))))
    nil))

(defn install!
  "Teach Replicant that a handler may be data.

  Without this, `:on {:click [[:review/goto 3]]}` is a vector where a function
  was expected and nothing happens. With it, every event handler in the kit is
  a value that can be read."
  []
  (r/set-dispatch!
   (fn [_event-data handler-data]
     (dispatch! (if (vector? (first handler-data))
                  handler-data
                  [handler-data])))))

;;*********************************************************
;; The actions the design names
;;*********************************************************

;; state only — moves the review cursor. Pure swap!, no effects.
(register! :review/goto
           (fn [state i]
             (assoc-in state [:review :at] i)))

;; state + effect — writes :queued at that address, then sends to the bound
;; client. Was :eval.one, raised on the editor object in objs/eval.cljs.
(register! :eval/form
           (fn [state path line]
             {:state (assoc-in state [:results [path line]]
                               {:status :queued
                                :from (get-in state [:editors path :client])})
              :effects [[:client/eval path line]]}))

;; state + effect — turns a walked inspector path into a standing watch.
;; The one-gesture claim, as one action.
(register! :watch/promote
           (fn [state [path line] into-value]
             {:state (assoc-in state [:watches [path line into-value]] {:reads 0})
              :effects [[:client/watch path line into-value]]}))

;; state + effect — writes the proposal into the buffer and marks it applied.
;; Refuses if the line changed since the run read it; that refusal is the
;; conflict band, which is why this returns a conflict rather than throwing.
(register! :edit/apply
           (fn [state run-id idx]
             (let [edit (get-in state [:runs run-id :edits idx])]
               (cond
                 (nil? edit) {:state state :effects [[:error/no-such-edit run-id idx]]}
                 (:conflict edit) {:state state :effects []}
                 :else {:state (assoc-in state [:runs run-id :edits idx :applied?] true)
                        :effects [[:buffer/write (:at edit) (get-in edit [:evidence :if-applied])]]}))))

;; state only — adds one capability to a blocked run. Narrow by construction,
;; because the payload is a single capability rather than a boolean.
(register! :run/grant
           (fn [state id cap]
             (update-in state [:runs id :grants] (fnil conj #{}) cap)))

;; state + effect — reload in dependency order, tracking the half-loaded state
;; explicitly so a panel can show it.
(register! :ns/refresh
           (fn [state]
             {:state (assoc state :refreshing? true)
              :effects [[:client/refresh]]}))

;; rewrites itself — edits the dispatch table from inside the running editor.
(register! :behavior/rebind
           (fn [state key actions]
             (assoc-in state [:keymap key] actions)))
