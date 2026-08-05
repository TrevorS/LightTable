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
            [lt.objs.keyboard :as kb]
            [lt.objs.settings :as settings]
            [lt.objs.tabs :as tabs]
            [lt.state :as state]))

;;*********************************************************
;; Actions the views emit
;;*********************************************************

;; state + effect — a tab is chosen in the chrome and the tabset is told.
;; The state is written first so the click feels instant, and the tabset
;; catches up in the same turn.
;;
;; By tabset id rather than by position: a window can be split, and the strip
;; you clicked in is not always the first one.
(actions/register! :tab/activate
                   (fn [state ts i]
                     {:state (update state :tabsets
                                     (fn [tss]
                                       (mapv #(if (= ts (:id %)) (assoc % :active i) %) tss)))
                      :effects [[:tabs/activate ts i]]}))

;; state only — which tab is being dragged. Picking one up changes nothing
;; else, which is the point: a drag is a fact about the window, so it lives
;; where the rest of them do rather than in a variable inside a library.
(actions/register! :tab/drag-start
                   (fn [state ts i]
                     (assoc state :dragging {:tabset ts :index i})))

;; state + effect — and it is the *state* that says what was picked up, so the
;; drop needs nothing from the event. `nil` for the index means the empty part
;; of a strip, which is "put it at the end".
(actions/register! :tab/drop
                   (fn [state to-ts to-i]
                     (let [{from-ts :tabset from-i :index} (:dragging state)]
                       {:state (assoc state :dragging nil)
                        :effects (when from-ts
                                   [[:tabs/reorder from-ts from-i to-ts to-i]])})))

;; state + effect — closing and the menu are both the object's, because a tab
;; is a window onto something that knows how to close itself.
(actions/register! :tab/close
                   (fn [state ts i]
                     {:state state :effects [[:tabs/close ts i]]}))

(actions/register! :tab/menu
                   (fn [state ts i]
                     {:state state :effects [[:tabs/menu ts i]]}))

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

;; The tab effects are `lt.objs.tabs`'s, registered there — that is where the
;; tabsets are, and an effect belongs beside the thing it reaches.

(actions/register-effect! :cmd/exec
                          (fn [k & args]
                            (apply cmd/exec! k args)))

;; `:client/bind`'s effect was here and was a stub: it wrote a `::client` key on
;; the editor that nothing ever read, so clicking a connection row did nothing at
;; all while the row's whole purpose is to say where an evaluation goes.
;;
;; The real one is in `lt.objs.sidebar.clients`, beside `:client/unset` and
;; `:client/disconnect` — the effects that reach a client belong with the panel
;; that offers them, and this namespace is for the ones the design named.

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

;; The settings screen's writes. One line each, because the decisions — what a
;; control's answer means, which parameter changed, whether a rebind is a move
;; or a new binding — are all in [[lt.actions]], and the file format is
;; [[lt.objs.settings]]'s. This namespace only joins them up.
(actions/register-effect! :settings/write
                          (fn [tag behavior values]
                            (settings/set-user-behavior! tag behavior values)))

(actions/register-effect! :settings/attach
                          (fn [tag behavior on?]
                            (settings/attach-user-behavior! tag behavior on?)))

(actions/register-effect! :keymap/write
                          (fn [old-key new-key actions]
                            (settings/set-user-key! old-key new-key actions)))

;; While a key is being captured the keyboard belongs to the settings screen.
;; `lt.objs.keyboard/disable` is what stops a command firing, and it existed for
;; this shape of problem already — the find bar uses it.
(actions/register-effect! :keys/capturing
                          (fn [capturing?]
                            (if capturing? (kb/disable) (kb/enable))))

(actions/register-effect! :error/no-such-setting
                          (fn [tag behavior]
                            (object/safe-report-error
                             (str "No setting " (pr-str behavior) " on " (pr-str tag)
                                  ". The projection is `lt.state.objects/settings-entries`."))))

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
