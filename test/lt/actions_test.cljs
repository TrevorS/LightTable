(ns lt.actions-test
  "The dispatch table, tested by calling it.

  This is what handlers-as-data buys and the reason the design argues for it:
  an action is a function of state and arguments, so what a click does can be
  asserted without a window, a DOM, or a click."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [lt.actions :as actions]))

(def ^:private empty-state
  {:results {} :watches {} :runs {} :editors {} :review nil})

(defn- act [state action]
  (actions/apply-action state action))

(deftest an-action-is-a-vector-and-a-value
  ;; No closure, no object, no event. The handler in the hiccup is this.
  (let [{:keys [state effects]} (act {:review {:run "port-fuzzy" :at 0}} [:review/goto 3])]
    (is (= 3 (get-in state [:review :at])))
    (is (= [] effects) "moving a cursor asks for nothing")))

(deftest an-unknown-action-is-answered-rather-than-thrown
  ;; A rebindable dispatch table will be asked for actions that do not exist —
  ;; from a keymap, from a plugin that has gone away. That is a report, not a
  ;; crash in whichever handler happened to be running.
  (let [{:keys [state effects]} (act empty-state [:nope/at-all 1])]
    (is (= empty-state state))
    (is (= [[:error/unknown-action [:nope/at-all 1]]] effects))))

(deftest evaluating-writes-the-address-before-it-sends
  ;; Results are keyed by [path line], so the queued state has somewhere to
  ;; live before anything has been evaluated — which is what lets a band be
  ;; drawn the instant you ask rather than when the answer arrives.
  (let [{:keys [state effects]} (act {:editors {"fuzzy.ts" {:client 51423}}}
                                     [:eval/form "fuzzy.ts" 7])]
    (is (= {:status :queued :from 51423} (get-in state [:results ["fuzzy.ts" 7]])))
    (is (= [[:client/eval "fuzzy.ts" 7]] effects))))

(deftest a-watch-is-a-result-address-plus-a-path-into-the-value
  ;; The one-gesture claim, as one action: nothing about promoting a walked
  ;; path is a different mechanism from the result it was walked from.
  (let [{:keys [state]} (act empty-state [:watch/promote ["fuzzy.ts" 19] [:tabsets 0 :count]])]
    (is (= {:reads 0} (get-in state [:watches ["fuzzy.ts" 19 [:tabsets 0 :count]]])))))

(deftest applying-an-edit-refuses-when-the-line-moved-under-it
  (let [run {:edits [{:at ["fuzzy.ts" 14]
                      :applied? false
                      :evidence {:as-written "0.82" :if-applied "[{:start 0}]"}}]}
        state {:runs {"port-fuzzy" run}}]
    (testing "the ordinary case writes the proposal and marks it applied"
      (let [{:keys [state effects]} (act state [:edit/apply "port-fuzzy" 0])]
        (is (true? (get-in state [:runs "port-fuzzy" :edits 0 :applied?])))
        (is (= [[:buffer/write ["fuzzy.ts" 14] "[{:start 0}]"]] effects))))
    (testing "a conflicted edit is refused, and the refusal is not an error"
      (let [conflicted (assoc-in state [:runs "port-fuzzy" :edits 0 :conflict] true)
            {:keys [state effects]} (act conflicted [:edit/apply "port-fuzzy" 0])]
        (is (false? (get-in state [:runs "port-fuzzy" :edits 0 :applied?]))
            "still unapplied — the conflict band is what the user sees next")
        (is (= [] effects) "and nothing was written")))
    (testing "an edit that is not there says so"
      (is (= [[:error/no-such-edit "port-fuzzy" 9]]
             (:effects (act state [:edit/apply "port-fuzzy" 9])))))))

(deftest a-grant-is-one-capability-rather-than-a-boolean
  ;; Narrow by construction: there is no action that grants everything, because
  ;; the payload is a capability and not a yes.
  (let [state {:runs {"port-fuzzy" {:grants #{:write/src-worker}}}}
        {:keys [state]} (act state [:run/grant "port-fuzzy" :write/src-window])]
    (is (= #{:write/src-worker :write/src-window}
           (get-in state [:runs "port-fuzzy" :grants])))))

(deftest the-table-can-rewrite-itself
  ;; The claim the modernization rests on: a keymap is a map from key to action
  ;; vector, so rebinding is an ordinary action over ordinary data — the same
  ;; thing default.behaviors has been since 2013.
  (let [{:keys [state]} (act empty-state [:behavior/rebind "⌘⏎" [[:eval/form "fuzzy.ts" 7]]])]
    (is (= [[:eval/form "fuzzy.ts" 7]] (get-in state [:keymap "⌘⏎"])))))

(deftest every-action-the-design-names-is-registered
  ;; The document lists seven. A missing one is a screen that renders and does
  ;; nothing when clicked, which no other test here would notice.
  (is (= #{:review/goto :eval/form :watch/promote :edit/apply
           :run/grant :ns/refresh :behavior/rebind}
         (set (keys (actions/registered))))))

(deftest actions-fold-so-a-run-can-be-replayed
  ;; Several actions are one dispatch, and the state they produce is the fold.
  ;; That is what makes a sequence of actions a recording.
  (let [state (reduce (fn [s a] (:state (act s a)))
                      {:runs {"r" {:grants #{}}} :review {:at 0}}
                      [[:review/goto 2] [:run/grant "r" :write/a] [:run/grant "r" :write/b]])]
    (is (= 2 (get-in state [:review :at])))
    (is (= #{:write/a :write/b} (get-in state [:runs "r" :grants])))))
