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
  ;;
  ;; The rest are not the design's; they are what a surface needed once it
  ;; stopped being objects with nodes. They are here rather than in
  ;; `lt.actions.effects` because every one of them changes state — the ones
  ;; that only reach the object world live there, and are absent from this
  ;; suite because that namespace needs a window, which is the point of this
  ;; suite not having one.
  (let [registered (set (keys (actions/registered)))
        of (fn [& nses] (into #{} (filter (comp (set nses) namespace) registered)))]
    (is (= #{:review/goto :eval/form :watch/promote :edit/apply
             :run/grant :ns/refresh :behavior/rebind}
           (into #{} (remove (comp #{"status" "console" "tree" "workspace" "client"
                                     "settings" "keymap"}
                                   namespace)
                             registered))))
    (testing "the settings screen's, from when :keymap stopped being written to by nothing"
      (is (= #{:settings/show :settings/query :settings/set :settings/attach}
             (of "settings")))
      (is (= #{:keymap/capture-start :keymap/capture-cancel :keymap/capture
               :keymap/unbind :keymap/menu}
             (of "keymap"))))
    (testing "the statusbar's, from when the bar stopped being three objects"
      (is (= #{:status/message :status/loading :console/unread}
             (of "status" "console"))))
    (testing "the tree's, from when it stopped being an object per file"
      (is (= #{:tree/toggle :tree/loaded :tree/open :tree/roots :tree/changed
               :tree/rename-start :tree/rename-cancel :tree/rename-submit
               :workspace/show-tree :workspace/show-recents :workspace/recents-loaded}
             (of "tree" "workspace"))))
    (testing "and the connect panel's, which is which of its two lists is up"
      ;; `:client/bind` is not here: it is registered in `lt.actions.effects`
      ;; with the rest of the ones that reach the object world.
      (is (= #{:client/choose :client/connectors} (of "client"))))))

(deftest a-folder-is-read-once-and-remembered
  ;; The reason the tree is a map of paths rather than a tree of nodes: opening
  ;; a folder is one write, and closing it is another — what was in it stays,
  ;; so reopening asks the disk nothing.
  (let [state {:workspace {:roots ["/p"] :nodes {"/p" {:dir? true}}}}
        opened (act state [:tree/toggle "/p"])]
    (is (true? (get-in (:state opened) [:workspace :nodes "/p" :open?])))
    (is (= [[:tree/read "/p"]] (:effects opened)) "the disk is read the first time")

    (let [loaded (:state (act (:state opened)
                              [:tree/loaded "/p" [["/p/src" true] ["/p/a.clj" false]]]))
          closed (act loaded [:tree/toggle "/p"])
          again (act (:state closed) [:tree/toggle "/p"])]
      (is (= ["/p/src" "/p/a.clj"] (get-in loaded [:workspace :nodes "/p" :children])))
      (is (true? (get-in loaded [:workspace :nodes "/p/src" :dir?])))
      (is (false? (get-in (:state closed) [:workspace :nodes "/p" :open?])))
      (is (= [] (:effects again)) "and never again, because it is still remembered")
      (is (= ["/p/src" "/p/a.clj"] (get-in (:state again) [:workspace :nodes "/p" :children]))))))

(deftest what-is-no-longer-in-a-folder-is-no-longer-in-the-tree
  ;; Re-reading a directory is how the tree hears about a change, so a stale
  ;; entry that survived one would be a path that is drawn and does not exist —
  ;; and a folder that came back as a file would find the old folder's children.
  (let [state {:workspace
               {:roots ["/p"]
                :nodes {"/p" {:dir? true :open? true :loaded? true :children ["/p/src" "/p/a.clj"]}
                        "/p/src" {:dir? true :open? true :loaded? true :children ["/p/src/deep.clj"]}
                        "/p/src/deep.clj" {:dir? false}
                        "/p/a.clj" {:dir? false}}}}
        after (:state (act state [:tree/loaded "/p" [["/p/a.clj" false]]]))]
    (is (= ["/p/a.clj"] (get-in after [:workspace :nodes "/p" :children])))
    (is (nil? (get-in after [:workspace :nodes "/p/src"])))
    (is (nil? (get-in after [:workspace :nodes "/p/src/deep.clj"]))
        "and its children with it, however deep")
    (is (some? (get-in after [:workspace :nodes "/p/a.clj"])) "what is still there stays")))

(deftest only-a-folder-that-is-open-is-worth-re-reading
  ;; A watcher reports every change under everything it watches. Re-reading a
  ;; folder nobody has opened would be reading a directory to draw nothing.
  (let [state {:workspace {:nodes {"/p" {:dir? true :loaded? true}
                                   "/q" {:dir? true}}}}]
    (is (= [[:tree/read "/p"]] (:effects (act state [:tree/changed "/p"]))))
    (is (= [] (:effects (act state [:tree/changed "/q"]))))))

(deftest a-rename-that-was-cancelled-does-not-submit-itself
  ;; Blur is what submits, and a cancelled rename removes the input — which
  ;; blurs it. So the guard is what makes the two orders the same, and there is
  ;; no other reason for it.
  (let [renaming {:workspace {:renaming "/p/a.clj"}}
        cancelled (:state (act renaming [:tree/rename-cancel]))
        after-cancel (act cancelled [:tree/rename-submit "/p/a.clj" "b.clj"])]
    (is (nil? (get-in cancelled [:workspace :renaming])))
    (is (= [] (:effects after-cancel)) "nothing is moved on disk")

    (let [submitted (act renaming [:tree/rename-submit "/p/a.clj" "b.clj"])]
      (is (= [[:ctx/out :tree.rename] [:file/rename "/p/a.clj" "b.clj"]] (:effects submitted)))
      (is (nil? (get-in (:state submitted) [:workspace :renaming])))
      (testing "and an empty name is not a rename to nothing"
        (is (= [[:ctx/out :tree.rename]]
               (:effects (act renaming [:tree/rename-submit "/p/a.clj" ""]))))))))

(deftest the-roots-are-the-workspace-and-nothing-underneath-survives-losing-one
  (let [state {:workspace {:roots ["/p" "/q"]
                           :nodes {"/p" {:dir? true :open? true :children ["/p/a.clj"]}
                                   "/p/a.clj" {:dir? false}
                                   "/q" {:dir? true}}}}
        after (:state (act state [:tree/roots ["/p"] ["/notes.md"]]))]
    (is (= ["/p" "/notes.md"] (get-in after [:workspace :roots])) "folders before files")
    (is (true? (get-in after [:workspace :nodes "/p" :open?])) "a folder you had open stays open")
    (is (false? (get-in after [:workspace :nodes "/notes.md" :dir?])))
    (is (nil? (get-in after [:workspace :nodes "/q"])) "and one that left is gone")))

(deftest the-statusbar-says-only-what-is-still-true
  ;; The bar is a view, so every one of these used to be a write into an object
  ;; and is now a value. Which means what the editor is telling you can be
  ;; asserted by folding, with no window anywhere.
  (testing "a message clears by being emptied, which is what the timeout does"
    (is (= {:text "saved fuzzy.ts" :tone nil}
           (:message (:state (act empty-state [:status/message "saved fuzzy.ts"])))))
    (is (= :error (get-in (act empty-state [:status/message "boom" :error]) [:state :message :tone])))
    (is (nil? (:message (:state (act {:message {:text "saved"}} [:status/message ""]))))))

  (testing "working is a count, so the second task finishing is what stops it"
    (let [state (reduce (fn [s a] (:state (act s a)))
                        empty-state
                        [[:status/loading :inc] [:status/loading :inc] [:status/loading :dec]])]
      (is (= 1 (:loading state)))
      (is (zero? (:loading (:state (act state [:status/loading :dec])))))
      (testing "and it cannot go below nothing, so a stray :dec is not a negative bar"
        (is (zero? (:loading (:state (act {:loading 0} [:status/loading :dec]))))))))

  (testing "the console's count is cleared by looking, and its tone with it"
    (let [state (reduce (fn [s a] (:state (act s a)))
                        empty-state
                        [[:console/unread :inc] [:console/unread :inc] [:console/unread :tone :error]])]
      (is (= {:unread 2 :tone :error} (:console state)))
      (is (= {:unread 0 :tone nil} (:console (:state (act state [:console/unread :clear]))))))))

(deftest actions-fold-so-a-run-can-be-replayed
  ;; Several actions are one dispatch, and the state they produce is the fold.
  ;; That is what makes a sequence of actions a recording.
  (let [state (reduce (fn [s a] (:state (act s a)))
                      {:runs {"r" {:grants #{}}} :review {:at 0}}
                      [[:review/goto 2] [:run/grant "r" :write/a] [:run/grant "r" :write/b]])]
    (is (= 2 (get-in state [:review :at])))
    (is (= #{:write/a :write/b} (get-in state [:runs "r" :grants])))))

;;*********************************************************
;; The settings screen
;;*********************************************************

(def ^:private with-settings
  {:keymap {"cmd-s" [[:cmd/exec :save]]}
   :settings
   {:showing :settings
    :query ""
    :capturing nil
    :entries [{:tag :editor
               :behavior :lt.objs.editor/tab-settings
               :desc "Editor: Set tab settings"
               :params [{:label "Use tabs?" :type :boolean}
                        {:label "Tab size in spaces" :type :number}]
               :values [false 2]}
              {:tag :app
               :behavior :lt.objs.style/set-rulers
               :desc "Style: Set rulers"
               :params [{:label "Vector of rulers" :example [80]}]
               :values [[80]]}]}})

(deftest a-control-answers-with-the-type-its-parameter-declared
  ;; The one place in the settings path where a decision is made, so the one
  ;; place worth testing exhaustively.
  (is (= true (actions/parse-value {:type :boolean} true)))
  (is (= false (actions/parse-value {:type :boolean} nil)))
  (is (= 13 (actions/parse-value {:type :number} "13")))
  (is (= 13.5 (actions/parse-value {:type :number} "13.5")))
  (is (nil? (actions/parse-value {:type :number} "")) "an emptied number box is unset, not zero")
  (is (= "Menlo" (actions/parse-value {:type :string} "Menlo")))
  (is (= "dark" (actions/parse-value {:type :list} "dark"))))

(deftest an-untyped-parameter-is-read-the-way-the-file-would-read-it
  ;; 21 of the 49 settable behaviors declare a label and no type, and their
  ;; values are not strings. Coercing them to numbers would destroy a ruler
  ;; setting; leaving them as strings would turn a vector into "[1 80]".
  (is (= [1 80] (actions/parse-value {:label "Vector of rulers"} "[1 80]")))
  (is (= {:a 1} (actions/parse-value {:label "map"} "{:a 1}")))
  (is (= 42 (actions/parse-value {:label "Number"} "42")))
  (testing "and a half-typed literal keeps what was typed rather than losing it"
    (is (= "[1 80" (actions/parse-value {:label "Vector of rulers"} "[1 80")))))

(deftest setting-a-parameter-writes-the-whole-argument-list
  ;; A behavior takes positional arguments, so setting the second one means
  ;; writing both — there is no way to say "the second one is now 4" in the file.
  (let [{:keys [state effects]} (act with-settings
                                     [:settings/set :editor :lt.objs.editor/tab-settings 1 "4"])]
    (is (= [false 4] (get-in state [:settings :entries 0 :values])))
    (is (= [[:settings/write :editor :lt.objs.editor/tab-settings [false 4]]] effects))))

(deftest setting-a-parameter-nobody-has-set-yet-pads-the-ones-before-it
  (let [state (assoc-in with-settings [:settings :entries 0 :values] [])
        {:keys [state effects]} (act state
                                     [:settings/set :editor :lt.objs.editor/tab-settings 1 "4"])]
    (is (= [nil 4] (get-in state [:settings :entries 0 :values]))
        "or the value would land in the first argument's position")
    (is (= [[:settings/write :editor :lt.objs.editor/tab-settings [nil 4]]] effects))))

(deftest setting-something-that-is-not-there-is-reported-rather-than-written
  (let [{:keys [state effects]} (act with-settings [:settings/set :editor :nope/at-all 0 "x"])]
    (is (= with-settings state))
    (is (= [[:error/no-such-setting :editor :nope/at-all]] effects))))

(deftest the-filter-and-the-half-you-are-looking-at-are-state-only
  (is (= :keys (get-in (:state (act with-settings [:settings/show :keys])) [:settings :showing])))
  (is (= "font" (get-in (:state (act with-settings [:settings/query "font"])) [:settings :query])))
  (is (empty? (:effects (act with-settings [:settings/query "font"])))))

;;*********************************************************
;; Rebinding
;;*********************************************************

(deftest capturing-a-key-takes-the-keyboard-and-gives-it-back
  ;; Without this, pressing the shortcut you want to assign runs whatever it is
  ;; currently bound to — so the capture is interrupted by the very thing you
  ;; were trying to rebind.
  (let [{:keys [state effects]} (act with-settings [:keymap/capture-start "cmd-s"])]
    (is (= "cmd-s" (get-in state [:settings :capturing])))
    (is (= [[:keys/capturing true]] effects))
    (let [{:keys [state effects]} (act state [:keymap/capture-cancel])]
      (is (nil? (get-in state [:settings :capturing])))
      (is (= [[:keys/capturing false]] effects)))))

(deftest a-rebind-moves-the-binding-rather-than-copying-it
  (let [captured (:state (act with-settings [:keymap/capture-start "cmd-s"]))
        {:keys [state effects]} (act captured [:keymap/capture "ctrl-s" [[:cmd/exec :save]]])]
    (is (= {"ctrl-s" [[:cmd/exec :save]]} (:keymap state))
        "the old key is gone, which is what makes it a move")
    (is (= [[:keys/capturing false]
            [:keymap/write "cmd-s" "ctrl-s" [[:cmd/exec :save]]]]
           effects))))

(deftest a-capture-that-produced-no-key-is-a-cancel
  (let [captured (:state (act with-settings [:keymap/capture-start "cmd-s"]))
        {:keys [state effects]} (act captured [:keymap/capture "" [[:cmd/exec :save]]])]
    (is (= {"cmd-s" [[:cmd/exec :save]]} (:keymap state)) "nothing moved")
    (is (nil? (get-in state [:settings :capturing])))
    (is (= [[:keys/capturing false]] effects))))

(deftest a-keystroke-arriving-when-nothing-is-capturing-does-nothing
  ;; The blur that ends a capture and the keydown that completes one can arrive
  ;; in either order, and only one of them may write a binding.
  (let [{:keys [state effects]} (act with-settings [:keymap/capture "ctrl-s" [[:cmd/exec :save]]])]
    (is (= with-settings state))
    (is (= [] effects))))

(deftest unbinding-drops-the-row-and-negates-the-key
  (let [{:keys [state effects]} (act with-settings [:keymap/unbind "cmd-s"])]
    (is (= {} (:keymap state)))
    (is (= [[:keymap/write "cmd-s" nil nil]] effects))))
