(ns lt.actions.effects
  "What an action asks for, carried out.

  [[lt.actions]] is a table of pure functions returning `{:state s :effects e}`,
  and it must stay that way — it is what makes a click testable by calling it,
  with no window in the room. So the effects are described there as data and
  performed here, where the objects are.

  The split is the same one `:state only` and `:state + effect` name in the
  design. An action that only writes state has nothing in this file; an action
  that has to reach a REPL, a buffer or a tab has exactly one line here.

  Nothing in this namespace decides anything. If an effect looks like it is
  making a choice, the choice belongs in the action."
  (:require [lt.actions :as actions]
            [lt.object :as object]
            [lt.objs.command :as cmd]
            [lt.objs.context :as ctx]
            [lt.objs.editor :as editor]
            [lt.objs.editor.pool :as pool]
            [lt.objs.tabs :as tabs]
            [lt.state :as state]))

;;*********************************************************
;; Actions the views emit
;;*********************************************************

;; state + effect — a tab is chosen in the chrome and the tabset is told.
;; The state is written first so the click feels instant, and the tabset
;; catches up in the same turn.
(actions/register! :tab/activate
                   (fn [state i]
                     {:state (assoc-in state [:tabsets 0 :active] i)
                      :effects [[:tabs/activate i]]}))

;; state + effect — which client an evaluation reaches. Bound in the state so
;; the panel can draw it, and told to the editor so it is true.
(actions/register! :client/bind
                   (fn [state id]
                     {:state (update state :clients
                                     (fn [cs]
                                       (into {} (for [[k c] cs]
                                                  [k (assoc c :bound? (= k id))]))))
                      :effects [[:client/bind id]]}))

;; state only, and deliberately: a command is Light Table's own registry and
;; running one is the effect. Nothing about the state changes.
(actions/register! :cmd/exec
                   (fn [state k & args]
                     {:state state
                      :effects [(into [:cmd/exec k] args)]}))


;;*********************************************************
;; Carrying them out
;;*********************************************************

(defn- tab-at
  "The object in the active tabset at index `i`."
  [i]
  (when-let [ts (first (object/by-tag :tabset))]
    (get (:objs @ts) i)))

(actions/register-effect! :tabs/activate
                          (fn [i]
                            (when-let [obj (tab-at i)]
                              (tabs/active! obj))))

(actions/register-effect! :cmd/exec
                          (fn [k & args]
                            (apply cmd/exec! k args)))

(actions/register-effect! :client/bind
                          (fn [id]
                            ;; Recorded on the editor rather than globally: a
                            ;; client is bound to a buffer, which is why two
                            ;; tabs can evaluate into different places.
                            (when-let [ed (pool/last-active)]
                              (object/merge! ed {::client id}))))

(actions/register-effect! :buffer/write
                          (fn [[path line] text]
                            (doseq [ed (pool/by-path path)]
                              (editor/replace ed
                                              {:line line :ch 0}
                                              {:line line :ch (count (editor/line ed line))}
                                              (str text)))))

(actions/register-effect! :client/eval
                          (fn [path line]
                            ;; Through the editor's own evaluation, which is
                            ;; where the client, the language and the form
                            ;; boundaries are already decided. An action that
                            ;; re-decided them would be a second answer.
                            (when-let [ed (first (pool/by-path path))]
                              (editor/move-cursor ed {:line line :ch 0})
                              (object/raise ed :eval.one))))

(actions/register-effect! :client/refresh
                          (fn []
                            (cmd/exec! :behaviors.reload)))

;; Opening a file is the opener's, which is where the mime, the tags and
;; therefore the mode and the language server are already decided. A view that
;; opened a file another way would be a second kind of open.
(actions/register-effect! :file/open
                          (fn [path]
                            (object/raise (first (object/by-tag :opener)) :open! path)))

;; A context is what the keymap dispatches through, and an action that puts you
;; in one — renaming a file in the tree — is asking for `esc` and `enter` to
;; mean something else for a moment.
(actions/register-effect! :ctx/in (fn [c] (ctx/in! c)))
(actions/register-effect! :ctx/out (fn [c] (ctx/out! c)))

(actions/register-effect! :client/watch
                          (fn [path line into-value]
                            ;; A watch has to start somewhere, and what it
                            ;; reads is whatever the result at that address
                            ;; already was. It ticks from its own atom after
                            ;; that — see [[lt.state/watch-values]].
                            (let [address [path line into-value]
                                  seed (get-in @state/app [:results [path line] :value])]
                              (state/observe! address (get-in seed into-value seed)))))

(actions/register-effect! :error/unknown-action
                          (fn [action]
                            (object/safe-report-error
                             (str "No action " (pr-str (first action))
                                  ". The table is data — `lt.actions/registered` lists it."))))

(actions/register-effect! :error/no-such-edit
                          (fn [run-id idx]
                            (object/safe-report-error
                             (str "Run " (pr-str run-id) " has no edit " idx))))

(defn install!
  "Send failures to the error ring rather than the console.

  [[lt.actions]] defaults to `console.error` because it must load without a
  window. Here there is one, and the ring is what the control surface reads."
  []
  (actions/report-with! object/safe-report-error))
