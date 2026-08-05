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
  (:require [cljs.reader :as reader]
            [clojure.string :as string]
            [lt.state :as state]
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

(defn register-passthrough!
  "Register `kind` as an action whose whole content is the effect of that name.

  Some of what a view emits changes nothing about the state: deleting a file
  asks for a confirmation and then the disk, and the tree hears about it from
  the watcher like any other change. Those still have to be actions, because
  `dispatch!` resolves actions and an effect that no action asks for is an
  `:error/unknown-action` nobody sees until they click the thing.

  Which is exactly what happened, twice, before this existed."
  [kind]
  (register! kind (fn [state & args]
                    {:state state :effects [(into [kind] args)]})))

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

(defonce ^:private keystr
  ;; How a `keydown` becomes the string a keymap is keyed by. Installed rather
  ;; than required, for the same reason `report` above is: naming
  ;; `lt.objs.keyboard` here would drag `lt.objs.platform` and the bridge in
  ;; with it, and this namespace has to load under node so that the actions can
  ;; be tested by calling them.
  ;;
  ;; The default is deliberately not a stub. It is the same rule with the
  ;; platform's answer missing — `meta-` rather than `cmd-` — so a test can
  ;; assert what a keystroke produces without a window, and the window installs
  ;; the version that knows which machine it is on.
  (atom (fn [e]
          (str (when (.-ctrlKey e) "ctrl-")
               (when (.-metaKey e) "meta-")
               (when (.-altKey e) "alt-")
               (when (.-shiftKey e) "shift-")
               (some-> (.-key e) string/lower-case)))))

(defn keystr-with!
  "Build keystrings with `f` instead. [[lt.ui.settings]] installs
  `lt.objs.keyboard/->keystr`, which is what the keymap is really keyed by."
  [f]
  (reset! keystr f))

(def ^:private from-event
  "The placeholders an action may carry in place of an argument.

  A handler is a value, which means it cannot read the event — and some of
  them have to: renaming a file needs what you typed. So the argument is named
  rather than fetched, and the one place that has both the action and the event
  fills it in. `[:tree/rename-submit path :event/value]` is still data, still
  loggable, still replayable — the placeholder is what was replayed.

  `:event/keystr` is the same idea for a keystroke, and it is what lets a key be
  rebound by pressing it while the view stays a pure function: the view says
  \"the key that was pressed goes here\" and never touches the event."
  {:event/value (fn [e] (.. e -target -value))
   :event/checked (fn [e] (.. e -target -checked))
   :event/keystr (fn [e] (@keystr e))})

(defn- fill
  "`action` with its placeholders replaced by what the event holds."
  [action e]
  (mapv (fn [arg]
          (if-let [f (and e (get from-event arg))]
            (f e)
            arg))
        action))

(defn install!
  "Teach Replicant that a handler may be data.

  Without this, `:on {:click [[:review/goto 3]]}` is a vector where a function
  was expected — Replicant throws `Cannot use non-function event handler`,
  catches it itself, logs `you may have misbehaving aliases` with the exception
  as `[object Object]`, and *skips that render*.

  Called at the bottom of this namespace as well as from [[lt.core]], and that
  is not belt and braces. Every namespace that draws requires this one, and a
  required namespace loads first — so by the time `lt.objs.tabs` or
  `lt.objs.sidebar.workspace` creates its object and draws for the first time,
  this has to already be true. It was not: `lt.core` called `install!` at the
  bottom of its own load, which is after every require, so the first paint of
  the tab strip, the workspace tree and the connect panel was thrown away.
  Nothing looked broken, because the second render is a state change away."
  []
  (r/set-dispatch!
   (fn [event-data handler-data]
     (let [e (:replicant/js-event event-data)
           actions (if (vector? (first handler-data))
                     handler-data
                     [handler-data])]
       (dispatch! (mapv #(fill % e) actions))))))

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

;;*********************************************************
;; The settings screen
;;*********************************************************

;; Two surfaces over two registries, and neither needed anything built. The
;; behaviors carry their own parameters and types; the keymap is a map from a
;; key to an action. What follows is presenting them and writing the answer
;; back — see [[lt.ui.view/settings]] and [[lt.ui.view/keys-screen]].

;; state only — which half of the screen, and what has been typed into it.
(register! :settings/show
           (fn [state showing]
             (assoc-in state [:settings :showing] showing)))

(register! :settings/query
           (fn [state query]
             (assoc-in state [:settings :query] (str query))))

(defn- entry-at
  "The projected settings entry for `[tag behavior]`, and where it is."
  [state tag behavior]
  (first (for [[i e] (map-indexed vector (get-in state [:settings :entries]))
               :when (and (= tag (:tag e)) (= behavior (:behavior e)))]
           [i e])))

(defn parse-value
  "A control's answer, as the value the behavior should receive.

  This is in the action rather than in the effect on purpose, and it is the only
  place in the settings path where a decision is made: a checkbox answers with a
  boolean, a number box with a string that has to become a number, and a
  parameter with no declared `:type` answers with whatever `cljs.reader` makes
  of it — which is what editing `user.behaviors` by hand already does.

  That last case is why this cannot be `js/parseFloat` everywhere. 21 of the 49
  settable behaviors declare a `:label` and no type, and their values include
  `[1 80]`, `{:a 1}` and `\"a string\"`. A control that coerced those to numbers
  would quietly destroy a ruler setting; one that left them as strings would
  quietly turn a vector into `\"[1 80]\"`.

  An unreadable value comes back as the string, because a half-typed EDN literal
  is a normal thing for a text box to contain for a moment and losing what was
  typed is worse than storing something the reader cannot parse yet."
  [param raw]
  (case (:type param)
    :boolean (boolean raw)
    :number (let [n (js/parseFloat raw)] (if (js/isNaN n) nil n))
    :string (str raw)
    :list (str raw)
    (let [s (str raw)]
      (if (string/blank? s)
        nil
        (try (reader/read-string s) (catch :default _ s))))))

;; state + effect — one parameter of one behavior. The state is written first so
;; the control does not snap back while the file is being written, and the
;; projection catches up when `:behaviors.reload` has run.
(register! :settings/set
           (fn [state tag behavior i raw]
             (if-let [[at entry] (entry-at state tag behavior)]
               (let [param (get (:params entry) i)
                     value (parse-value param raw)
                     values (assoc (into [] (concat (:values entry)
                                                    (repeat (max 0 (- (inc i)
                                                                      (count (:values entry))))
                                                            nil)))
                                   i value)]
                 {:state (assoc-in state [:settings :entries at :values] values)
                  :effects [[:settings/write tag behavior values]]})
               {:state state :effects [[:error/no-such-setting tag behavior]]})))

;; state + effect — a behavior that is attached or is not. Distinct from
;; `:settings/set` because it changes whether the entry exists at all, which is
;; a different edit to `user.behaviors`: a line added, or a `-` line that
;; negates one the defaults attached.
(register! :settings/attach
           (fn [state tag behavior on?]
             {:state state
              :effects [[:settings/attach tag behavior (boolean on?)]]}))

;;*********************************************************
;; Rebinding a key
;;*********************************************************

;; state + effect — while a binding is being captured the keyboard belongs to
;; this screen. Without disabling it, pressing the shortcut you want to assign
;; runs the command it is currently bound to, which for most interesting keys
;; means the capture is interrupted by the thing you were trying to rebind.
(register! :keymap/capture-start
           (fn [state key]
             {:state (assoc-in state [:settings :capturing] (or key ::new))
              :effects [[:keys/capturing true]]}))

(register! :keymap/capture-cancel
           (fn [state]
             {:state (assoc-in state [:settings :capturing] nil)
              :effects [[:keys/capturing false]]}))

;; state + effect — the keystroke arrived. `actions` is what the old binding
;; ran, so rebinding moves a command rather than inventing one, and a capture
;; that produced no key is a cancel rather than a binding of "".
(register! :keymap/capture
           (fn [state key actions]
             (let [was (get-in state [:settings :capturing])]
               (cond
                 (nil? was) {:state state :effects []}

                 (string/blank? (str key))
                 {:state (assoc-in state [:settings :capturing] nil)
                  :effects [[:keys/capturing false]]}

                 :else
                 {:state (-> state
                             (assoc-in [:settings :capturing] nil)
                             (update :keymap #(-> (if (= ::new was) % (dissoc % was))
                                                  (assoc key actions))))
                  :effects [[:keys/capturing false]
                            [:keymap/write (when-not (= ::new was) was) key actions]]}))))

;; state + effect — a binding removed. The state drops it so the row goes
;; immediately, and the file gets a negation.
(register! :keymap/unbind
           (fn [state key]
             {:state (update state :keymap dissoc key)
              :effects [[:keymap/write key nil nil]]}))

;; What you can do to a binding beyond rebinding it. In the menu rather than on
;; the row, which is the kit's rule: what follows a label is a count, a time or
;; a hint, and never a control — and unbinding is not something to put one
;; mis-click away from the thing you are reading.
(register-passthrough! :keymap/menu)

;;*********************************************************
;; The file tree
;;*********************************************************

;; A path is a lookup rather than a position in a tree, so every one of these
;; is one `assoc-in` — see [[lt.state]] for why the shape is flat. Reading a
;; directory is an effect; what was read comes back as `:tree/loaded`.

(defn- node [state path]
  (get-in state [:workspace :nodes path]))

(defn- subtree
  "`path` and everything under it that the tree has an entry for.

  Closing a folder keeps its children, which is what makes reopening one
  instant. Deleting one must not, or a path that comes back as a file would
  find the folder it used to be."
  [nodes path]
  (cons path (mapcat #(subtree nodes %) (:children (get nodes path)))))

;; state + effect — the only one of these that reads the disk, and only the
;; first time. A folder you have opened before opens from what it remembers.
(register! :tree/toggle
           (fn [state path]
             (let [{:keys [open? loaded?]} (node state path)]
               {:state (assoc-in state [:workspace :nodes path :open?] (not open?))
                :effects (when-not (or open? loaded?) [[:tree/read path]])})))

;; state only — what a directory turned out to contain. Children arrive sorted
;; because sorting is a decision about what to show and the view is not where
;; decisions go.
(register! :tree/loaded
           (fn [state path children]
             (let [nodes (get-in state [:workspace :nodes])
                   gone (remove (set (map first children)) (:children (get nodes path)))
                   dropped (mapcat #(subtree nodes %) gone)]
               (assoc-in state [:workspace :nodes]
                         (-> (apply dissoc nodes dropped)
                             (assoc-in [path :children] (mapv first children))
                             (assoc-in [path :loaded?] true)
                             (merge (into {} (for [[child dir?] children
                                                   :when (not (get nodes child))]
                                               [child {:dir? dir?}]))))))))

;; state + effect — a file is opened by the opener, which is where the mode,
;; the tags and the language server are already decided.
(register! :tree/open
           (fn [state path]
             {:state state :effects [[:file/open path]]}))

;; state only — the roots of the tree, from the workspace object that owns
;; them. Folders before files, which is the order everything else in the tree
;; is in.
(register! :tree/roots
           (fn [state folders files]
             (let [roots (vec (concat folders files))]
               (update state :workspace merge
                       {:roots roots
                        :nodes (let [nodes (get-in state [:workspace :nodes])]
                                 (into (select-keys nodes (mapcat #(subtree nodes %) roots))
                                       (for [f roots :when (not (get nodes f))]
                                         [f {:dir? (boolean ((set folders) f))}])))}))))

;; state + effect — something appeared or went away under a path we are
;; watching. Re-reading the parent rather than splicing the one child in: the
;; directory is the answer, and a sorted insert is a second implementation of
;; what `:tree/read` already does.
(register! :tree/changed
           (fn [state dir]
             {:state state
              :effects (when (:loaded? (node state dir)) [[:tree/read dir]])}))

;; state + effect — the row becomes an input, and `esc` and `enter` become the
;; two commands the keymap already binds in `:tree.rename`. The context is the
;; effect; the input focusing itself is the view's, because the view is what
;; creates it.
(register! :tree/rename-start
           (fn [state path]
             {:state (assoc-in state [:workspace :renaming] path)
              :effects [[:ctx/in :tree.rename]]}))

(register! :tree/rename-cancel
           (fn [state]
             {:state (assoc-in state [:workspace :renaming] nil)
              :effects [[:ctx/out :tree.rename]]}))

;; state + effect — and a no-op unless this is the row that is being renamed.
;; Blur is what submits, so a rename that was cancelled must not submit itself
;; on the way out, and the guard is what makes those two orders the same.
(register! :tree/rename-submit
           (fn [state path name-of]
             (if (= path (get-in state [:workspace :renaming]))
               {:state (assoc-in state [:workspace :renaming] nil)
                :effects (into [[:ctx/out :tree.rename]]
                               (when (seq (str name-of)) [[:file/rename path (str name-of)]]))}
               {:state state :effects []})))

;;*********************************************************
;; The workspace the tree is of
;;*********************************************************

;; The panel shows one of two things and this is which. `nil` recents is the
;; tree — an empty list of saved workspaces is something to say rather than a
;; reason to show something else.
(register! :workspace/show-tree
           (fn [state]
             (assoc-in state [:workspace :recents] nil)))

(register! :workspace/show-recents
           (fn [state]
             {:state state :effects [[:workspace/read-recents]]}))

(register! :workspace/recents-loaded
           (fn [state recents]
             (assoc-in state [:workspace :recents] (vec recents))))

;;*********************************************************
;; The connect panel
;;*********************************************************

;; Which of the two things the panel is showing. The same shape as the
;; workspace panel's tree-or-recents, and for the same reason: one panel, two
;; lists, and the state says which rather than CSS hiding one of them.
(register! :client/choose
           (fn [state choosing?]
             (assoc-in state [:connect :choosing?] (boolean choosing?))))

;; The kinds of connection there are to make, from the plugins that know how.
(register! :client/connectors
           (fn [state connectors]
             (assoc-in state [:connect :connectors] (vec connectors))))

;;*********************************************************
;; The statusbar's own facts
;;*********************************************************

;; The bar is a view, so what it shows has to be state, and everything that
;; used to reach into a statusbar object now goes through here instead. All
;; three are state only: telling you something is not an effect.

;; state only — what the editor last said. `nil` clears it, which is what the
;; timeout in [[lt.objs.notifos]] does.
(register! :status/message
           (fn [state text & [tone]]
             (assoc state :message (when (seq (str text)) {:text (str text) :tone tone}))))

;; state only — a count and not a flag, because two overlapping tasks
;; finishing must not turn the indicator off once. `:set` is the reset the
;; command offers for when a task died without saying so.
(register! :status/loading
           (fn [state op]
             (update state :loading
                     (fn [n] (case op
                               :inc (inc (or n 0))
                               :dec (max 0 (dec (or n 0)))
                               :set 0)))))

;; state only — how much the console has said that you have not looked at, and
;; in what tone. Cleared by looking, which is the console's own doing.
(register! :console/unread
           (fn [state op & [tone]]
             (case op
               :inc (update-in state [:console :unread] (fnil inc 0))
               :tone (assoc-in state [:console :tone] tone)
               :clear (assoc state :console {:unread 0 :tone nil}))))

;; Before anything can draw. See the docstring above: a namespace that renders
;; requires this one, so this line runs before any of them exist.
(install!)
