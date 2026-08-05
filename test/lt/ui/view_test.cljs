(ns lt.ui.view-test
  "The eight views, tested by calling them with a map.

  This is what the design buys, stated as a test file rather than as a claim:
  a view has nowhere to keep a secret, so a map goes in and hiccup comes out,
  and every question about what the window shows is answerable in milliseconds
  without an editor, a DOM, or a render.

  Hiccup is data, so the assertions walk it. `find-all` is the whole harness."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as string]
            [lt.support.hiccup :as h]
            [lt.ui.view :as view]))

(def ^:private state
  {:tabsets [{:id 0
              :active? true
              :active 0
              :tabs [{:id "src-worker/fuzzy.ts" :label "fuzzy.ts"
                      :path "src-worker/fuzzy.ts" :dirty? true :closable? true}
                     {:id "port-fuzzy" :label "port fuzzy to ranges" :dirty? false}]}]
   :editors {"src-worker/fuzzy.ts" {:lang :ts :dirty? true}}
   :clients {51423 {:name "nREPL 51423" :kind :nrepl :status :finished :bound? true}
             :claude {:name "claude" :kind :agent :status :executing :via 51423}}
   :results {["src-worker/fuzzy.ts" 7] {:status :finished :value "({:count 2})"}}
   :watches {["src-worker/fuzzy.ts" 19 [:tabsets 0 :count]] {:reads 8}}
   :runs {"port-fuzzy" {:label "port fuzzy to ranges"
                        :status :executing
                        :grants #{:write/src-worker}
                        :edits [{:at ["src-worker/fuzzy.ts" 14]
                                 :summary "readdir → fsp.readdir"
                                 :applied? false
                                 :evidence {:as-written "fs.readdir(dir)"
                                            :if-applied "fsp.readdir(dir)"}}
                                {:at ["src-window/behaviors.cljs" 44]
                                 :summary "threshold"
                                 :applied? false
                                 :conflict "you changed this 40s ago"
                                 :yours "(score nm q)"}]}}
   :review {:run "port-fuzzy" :at 1}
   :focus [:review 1]
   :cursor {:line 6 :ch 3}
   :keymap {"⌘⏎" [[:eval/form "src-worker/fuzzy.ts" 7]]}})

(deftest a-run-is-a-tab-like-any-other
  (let [tabs (h/find-all (view/titlebar state) :lt.ui.chrome/tab)]
    (is (= 2 (count tabs)))
    (testing "a tab carries its own label, because most tabs are not files"
      ;; The console, the plugin manager and the component kit have a name and
      ;; no path at all — a strip that took the leaf of one would draw `obj-42`.
      (is (= "fuzzy.ts" (h/text-of (first tabs)))))
    (testing "and the run is named by its label, with what it is waiting on"
      (let [run (second tabs)]
        (is (= :run (:origin (h/attrs-of run))))
        (is (= 2 (:count (h/attrs-of run))) "two edits, neither applied")))
    (testing "a run that is not in the tab list is still a tab"
      ;; The projection owns the tab list and knows nothing about runs, so the
      ;; view is what makes "a run is a tab like any other" true on screen.
      (let [projected (update-in state [:tabsets 0 :tabs] (comp vec (partial take 1)))]
        (is (= 2 (count (h/find-all (view/titlebar projected) :lt.ui.chrome/tab))))))
    (testing "and it is not listed twice when it is in both"
      (is (= 2 (count tabs))))
    (testing "the active one says so"
      (is (true? (:active? (h/attrs-of (first tabs))))))
    (testing "a dirty editor says so in the tab, as a prop rather than a child"
      ;; The dot is the tab's to draw. A view that appended one would be
      ;; deciding what dirty looks like, which is the component's decision and
      ;; would have to be made the same way in every other place a tab appears.
      (is (true? (:dirty? (h/attrs-of (first tabs)))))
      (is (false? (:dirty? (h/attrs-of (second tabs))))))
    (testing "and what you can do to it is a handler it is given"
      (is (= [[:tab/activate 0 0]] (:on-select (h/attrs-of (first tabs)))))
      (is (= [[:tab/menu 0 0]] (:on-menu (h/attrs-of (first tabs)))))
      (is (= [[:tab/close 0 0]] (:on-close (h/attrs-of (first tabs)))))
      (testing "closing only when the user behavior asks for it"
        (is (nil? (:on-close (h/attrs-of (second tabs))))))
      (testing "and picking it up is two state changes, not a library"
        (is (= [[:tab/drag-start 0 0]] (:on-drag-start (h/attrs-of (first tabs)))))
        (is (= [[:tab/drop 0 1]] (:on-drop (h/attrs-of (second tabs)))))))))

(deftest a-strip-belongs-to-its-tabset
  ;; There is one per tabset, because a tabset is a column of the window and
  ;; its tabs are its own. The one-argument form is what a window with no
  ;; splits has.
  (let [split (assoc state
                     :runs {}
                     :tabsets
                     [{:id 0 :active 0 :tabs [{:id "a" :label "a"}]}
                      {:id 1 :active 0 :tabs [{:id "b" :label "b"} {:id "c" :label "c"}]}])]
    (is (= ["a"] (map h/text-of (h/find-all (view/titlebar split 0) :lt.ui.chrome/tab))))
    (is (= ["b" "c"] (map h/text-of (h/find-all (view/titlebar split 1) :lt.ui.chrome/tab))))
    (testing "and the tabset it belongs to is in every action it emits"
      (is (= [[:tab/activate 1 1]]
             (:on-select (h/attrs-of (second (h/find-all (view/titlebar split 1)
                                                     :lt.ui.chrome/tab)))))))
    (testing "no argument is the first, which is a window that was never split"
      (is (= ["a"] (map h/text-of (h/find-all (view/titlebar split) :lt.ui.chrome/tab)))))
    (testing "and a run with no tab is appended to the first strip and only it"
      ;; It has to be somewhere, and "wherever you happen to be looking" would
      ;; put one in every column of a split window.
      (let [with-run (assoc split :runs (:runs state))]
        (is (= ["a" "port fuzzy to ranges"]
               (map h/text-of (h/find-all (view/titlebar with-run 0) :lt.ui.chrome/tab))))
        (is (= ["b" "c"]
               (map h/text-of (h/find-all (view/titlebar with-run 1) :lt.ui.chrome/tab))))))))

(deftest the-review-queue-is-rows-keyed-by-address
  (let [rows (h/find-all (view/review-queue state) :lt.ui.row/list-row)]
    (is (= 2 (count rows)))
    (testing "keyed by [path line], so a row survives the list changing under it"
      (is (= ["src-worker/fuzzy.ts" 14] (:replicant/key (h/attrs-of (first rows))))))
    (testing "the selected one is the review cursor, not a remembered click"
      (is (false? (:selected? (h/attrs-of (first rows)))))
      (is (true? (:selected? (h/attrs-of (second rows))))))
    (testing "a conflict is a tone, and tone is a role rather than a colour"
      (is (= :warning (:tone (h/attrs-of (second rows))))))
    (testing "focus is separate from selection, because a keyboard has both"
      (is (true? (:focused? (h/attrs-of (second rows))))))
    (testing "and the handler is a vector"
      (is (= [[:review/goto 0]] (:on-select (h/attrs-of (first rows))))))))

(deftest an-empty-queue-says-what-would-fill-it
  (let [empty-state (assoc-in state [:runs "port-fuzzy" :edits] [])]
    (is (seq (h/find-all (view/review-queue empty-state) :lt.ui.chrome/empty-state)))))

(deftest the-agent-is-a-client-like-the-others
  (let [rows (h/find-all (view/connections state) :lt.ui.chrome/connection-row)]
    (is (= 2 (count rows)))
    (testing "and where it evaluates through is drawn rather than assumed"
      (let [agent (first (filter #(= :agent (:kind (h/attrs-of %))) rows))]
        (is (some? agent))
        (is (re-find #"through 51423" (:what (h/attrs-of agent))))))))

(deftest the-statusbar-counts-what-is-waiting-on-you
  (let [bar (view/statusbar state)]
    (testing "line and column are one-based on screen and zero-based in the data"
      (is (re-find #"7 / 4" (h/text-of bar))))
    (testing "two unapplied edits, one executing run"
      (is (= 2 (:count (h/attrs-of (first (h/find-all bar :lt.ui.chrome/count-pill))))))
      (is (re-find #"1 run" (h/text-of bar))))
    (testing "and a quiet editor says nothing"
      (let [quiet (assoc state :runs {})]
        (is (empty? (h/find-all (view/statusbar quiet) :lt.ui.chrome/count-pill)))))))

(deftest the-statusbar-is-the-bar-at-the-bottom-of-the-window
  ;; The three facts that used to be three objects with three nodes. They are
  ;; state now, so what the real bar shows is answerable here rather than only
  ;; in a window — which is the whole reason the surface moved.
  (let [quiet (assoc state :runs {})]
    (testing "working is a count, so two tasks finishing does not stop it once"
      (is (empty? (h/find-all (view/statusbar quiet) :lt.ui.chrome/status-dot)))
      (is (= 1 (count (h/find-all (view/statusbar (assoc quiet :loading 2))
                                :lt.ui.chrome/status-dot))))
      (is (empty? (h/find-all (view/statusbar (assoc quiet :loading 0))
                            :lt.ui.chrome/status-dot))))

    (testing "a message is shown, and an error one is toned rather than reworded"
      (let [said (view/statusbar (assoc quiet :message {:text "saved fuzzy.ts"}))
            failed (view/statusbar (assoc quiet :message {:text "no language server" :tone :error}))]
        (is (re-find #"saved fuzzy.ts" (h/text-of said)))
        (is (nil? (:class (h/attrs-of (first (h/find-all said :span.statusbar__message))))))
        (is (= "statusbar__message--error"
               (:class (h/attrs-of (first (h/find-all failed :span.statusbar__message))))))))

    (testing "the console appears only when it has something you have not read"
      (is (empty? (h/find-all (view/statusbar quiet) :span.statusbar__console)))
      (is (empty? (h/find-all (view/statusbar (assoc quiet :console {:unread 0})) :span.statusbar__console)))
      (let [unread (view/statusbar (assoc quiet :console {:unread 4}))]
        (is (= 4 (:count (h/attrs-of (first (h/find-all unread :lt.ui.chrome/count-pill))))))
        (testing "and clicking it runs the command rather than reaching for the console"
          (is (= [[:cmd/exec :toggle-console]]
                 (get-in (h/attrs-of (first (h/find-all unread :span.statusbar__console))) [:on :click]))))))

    (testing "an error in it colours the count and nothing else"
      (let [bad (view/statusbar (assoc quiet :console {:unread 2 :tone :error}))]
        (is (= :error (:tone (h/attrs-of (first (h/find-all bad :lt.ui.chrome/count-pill))))))))))

(deftest the-connect-panel-shows-the-clients-or-the-kinds-of-client
  ;; One panel, two lists, and the state says which — the same shape the
  ;; workspace panel has for tree-or-recents, and for the same reason: CSS
  ;; hiding one of them is a second place the answer lives.
  (let [drawn (view/connections state)]
    (is (= 2 (count (h/find-all drawn :lt.ui.chrome/connection-row))))
    (is (empty? (h/find-all drawn :lt.ui.row/list-row)) "the kinds are not underneath")
    (testing "and what you can do to one is in its menu, never on the row"
      (let [row (h/attrs-of (first (h/find-all drawn :lt.ui.chrome/connection-row)))]
        (is (= [[:client/menu 51423]] (:on-menu row)))
        (is (nil? (:trailing row)) "which is a hint when there is one, not a control"))))

  (let [choosing (assoc state :connect
                        {:choosing? true
                         :connectors [{:name-of "Ports" :desc "the local TCP and WebSocket ports"}]})
        drawn (view/connections choosing)]
    (is (empty? (h/find-all drawn :lt.ui.chrome/connection-row)) "the clients are not underneath")
    (is (= [[:client/connect "Ports"]]
           (:on-select (h/attrs-of (first (h/find-all drawn :lt.ui.row/list-row))))))
    (testing "and none registered is a thing to say"
      (is (seq (h/find-all (view/connections (assoc state :connect {:choosing? true :connectors []}))
                         :lt.ui.chrome/empty-state))))))

(deftest a-connection-is-drawn-as-bound-when-the-buffer-evaluates-through-it
  ;; The claim the panel makes — this is where an eval goes — is read from the
  ;; editor rather than stored, so it cannot be stale in the way a flag can.
  (let [rows (h/find-all (view/connections state) :lt.ui.chrome/connection-row)]
    (is (= [true false] (map (comp boolean :bound? h/attrs-of) rows)))
    (testing "and an agent evaluating through a REPL says which one, on the row"
      (is (= "agent · through 51423" (:what (h/attrs-of (second rows))))))))

(def ^:private with-tree
  (assoc state :workspace
         {:roots ["/p" "/notes.md"]
          :nodes {"/p" {:dir? true :open? true :loaded? true
                        :children ["/p/src" "/p/deps.edn"]}
                  "/p/src" {:dir? true :open? false :loaded? true
                            :children ["/p/src/core.cljs"]}
                  "/p/src/core.cljs" {:dir? false}
                  "/p/deps.edn" {:dir? false}
                  "/notes.md" {:dir? false}}}))

(deftest the-tree-draws-what-is-open-and-nothing-else
  ;; The reason to draw from the state rather than to keep a node per file: a
  ;; closed folder is not hidden, it is not there. `/p/src` is loaded and shut,
  ;; so what is in it is remembered and undrawn.
  (let [rows (h/find-all (view/workspace with-tree) :lt.ui.row/tree-row)
        paths (map (comp :replicant/key h/attrs-of) rows)]
    (is (= ["/p" "/p/src" "/p/deps.edn" "/notes.md"] paths))

    (testing "depth is the tree, and the row is what turns it into an indent"
      (is (= [0 1 1 0] (map (comp :depth h/attrs-of) rows))))

    (testing "a file has no twist and a folder has one either way"
      (is (= [true false nil nil] (map (comp :open? h/attrs-of) rows)))
      (is (nil? (:open? (h/attrs-of (last rows)))) "nil reserves the column without drawing in it"))

    (testing "clicking a folder opens it and clicking a file opens the file"
      (is (= [[:tree/toggle "/p"]] (:on-select (h/attrs-of (first rows)))))
      (is (= [[:tree/open "/p/deps.edn"]] (:on-select (h/attrs-of (nth rows 2))))))

    (testing "and the file you are looking at is the row that is selected"
      (is (empty? (filter (comp :selected? h/attrs-of) rows)))
      (let [here (h/find-all (view/workspace
                            (assoc-in with-tree [:tabsets 0 :tabs]
                                      [{:id "/p/deps.edn" :label "deps.edn" :path "/p/deps.edn"}]))
                           :lt.ui.row/tree-row)]
        (is (= ["/p/deps.edn"] (map (comp :replicant/key h/attrs-of)
                                    (filter (comp :selected? h/attrs-of) here))))))))

(deftest opening-a-folder-shows-what-was-already-read
  (let [opened (assoc-in with-tree [:workspace :nodes "/p/src" :open?] true)
        paths (map (comp :replicant/key h/attrs-of)
                   (h/find-all (view/workspace opened) :lt.ui.row/tree-row))]
    (is (= ["/p" "/p/src" "/p/src/core.cljs" "/p/deps.edn" "/notes.md"] paths))))

(deftest a-row-being-renamed-is-an-input-rather-than-a-row
  ;; And only that row: renaming one file must not take the rest of the tree
  ;; with it, which a modal dialog would.
  (let [renaming (assoc-in with-tree [:workspace :renaming] "/p/deps.edn")
        drawn (view/workspace renaming)]
    (is (= ["/p" "/p/src" "/notes.md"]
           (map (comp :replicant/key h/attrs-of) (h/find-all drawn :lt.ui.row/tree-row))))
    (let [input (first (h/find-all drawn :input.tree__rename))]
      (is (= "deps.edn" (:value (h/attrs-of input))))
      (testing "and what you typed reaches the action, which is what a placeholder is for"
        (is (= [[:tree/rename-submit "/p/deps.edn" :event/value]]
               (get-in (h/attrs-of input) [:on :blur])))))))

(deftest an-empty-workspace-says-what-would-fill-it
  (let [empty-ws (assoc state :workspace {:roots [] :nodes {}})]
    (is (seq (h/find-all (view/workspace empty-ws) :lt.ui.chrome/empty-state)))
    (is (empty? (h/find-all (view/workspace empty-ws) :lt.ui.row/tree-row)))))

(deftest the-panel-shows-the-tree-or-the-workspaces-and-nil-is-which
  ;; `nil` recents rather than an empty list, because having saved no
  ;; workspaces is something to say and not a reason to show the tree.
  (is (empty? (h/find-all (view/workspace with-tree) :div.wstree__back)))
  (let [switching (assoc-in with-tree [:workspace :recents]
                            [{:path "/ws/a.clj" :folders ["/p"] :files []}])
        drawn (view/workspace switching)]
    (is (= 1 (count (h/find-all drawn :div.wstree__back))) "and a way back to the tree")
    (is (empty? (h/find-all drawn :lt.ui.row/tree-row)) "the tree is not underneath it")
    (is (= [[:workspace/open "/ws/a.clj"]]
           (:on-select (h/attrs-of (first (h/find-all drawn :lt.ui.row/list-row))))))
    (testing "and none saved is a thing to say"
      (is (seq (h/find-all (view/workspace (assoc-in with-tree [:workspace :recents] []))
                         :lt.ui.chrome/empty-state))))))

(deftest the-command-bar-is-a-view-over-the-table
  (let [with-bar (assoc state :command-bar
                        {:open? true :query "eva" :at 0
                         :commands [{:label "Evaluate this form" :action [:eval/form "f" 1]}
                                    {:label "Rename symbol" :action [:editor/rename]}]})]
    (testing "it is a search across labels"
      (let [rows (h/find-all (view/command-bar with-bar) :lt.ui.row/list-row)]
        (is (= 1 (count rows)))
        (is (= "Evaluate this form" (h/text-of (first rows))))))
    (testing "choosing one runs the action it names, which is the same value the keymap holds"
      (is (= [[:eval/form "f" 1]]
             (:on-select (h/attrs-of (first (h/find-all (view/command-bar with-bar)
                                                    :lt.ui.row/list-row)))))))
    (testing "and it is not there when it is not open"
      (is (nil? (view/command-bar state))))
    (testing "a query that matches nothing says so"
      (is (seq (h/find-all (view/command-bar (assoc-in with-bar [:command-bar :query] "zzz"))
                         :lt.ui.chrome/empty-state))))))

(deftest the-multibuffer-is-assembled-by-run
  (let [mb (view/multibuffer state)]
    (testing "two files, because a run touches files rather than a file"
      (is (= 2 (count (h/find-all mb :lt.ui.chrome/excerpt-header)))))
    (testing "the ordinary edit is a proposal"
      (is (= 1 (count (h/find-all mb :lt.ui.band/proposed-edit)))))
    (testing "the one you also edited is a conflict, not an error"
      (is (= 1 (count (h/find-all mb :lt.ui.band/conflict)))))
    (testing "and evidence is what the value was and what it becomes"
      (let [ev (h/attrs-of (first (h/find-all mb :lt.ui.band/evidence)))]
        (is (= "fs.readdir(dir)" (:before ev)))
        (is (= "fsp.readdir(dir)" (:after ev)))))))

;;*********************************************************
;; The settings screen
;;*********************************************************

;; doc/hygiene.md listed "the settings and keymap UI | nothing, at any layer" as
;; the worst of the coverage gaps. This is most of the answer, and the reason it
;; is cheap is the reason the views are functions: a settings screen is hard to
;; drive and trivial to *ask*.

(def ^:private settables
  "Two settable behaviors, shaped exactly as
  [[lt.state.objects/settings-entries]] projects them.

  One with typed parameters and one with none, because those are the two kinds
  of row: a behavior with parameters is a form, and a behavior without is a
  switch, since attaching it is the setting."
  [{:behavior :lt.objs.editor/tab-settings
    :tag :editor
    :desc "Editor: Set tab settings"
    :params [{:label "Use tabs?" :type :boolean}
             {:label "Tab size in spaces" :type :number}]
    :values [false 2]
    :exclusive? false
    :from "/home/u/.lighttable/User/user.behaviors"}
   {:behavior :lt.objs.style/set-theme
    :tag :app
    :desc "Style: Set theme"
    :params []
    :values []
    :exclusive? true
    :from nil}])

(def ^:private settings-state
  (assoc state
         :keymap {"cmd-s" [[:cmd/exec :save]]
                  "cmd-shift-p" [[:cmd/exec :command-bar]]}
         :settings {:showing :settings :query "" :entries settables :capturing nil}))

(deftest a-setting-draws-a-control-per-parameter-from-its-declared-type
  (let [s (view/settings settings-state)]
    (testing "the types the behaviors declare are the controls, with nothing mapping them by hand"
      (is (= 1 (count (h/find-all s :lt.ui.field/toggle))))
      (is (= 1 (count (h/find-all s :lt.ui.field/number-input)))))
    (testing "and the current value is what the control shows"
      (is (= "2" (str (:value (h/attrs-of (first (h/find-all s :lt.ui.field/number-input))))))))))

(deftest a-setting-says-which-file-its-value-came-from
  (testing "the question a settings screen usually cannot answer: why is this not the default"
    (let [sources (h/find-all (view/settings settings-state) :lt.ui.field/source)]
      ;; Two entries, and only one of them has a value that came from a file.
      (is (= 1 (count (filter (comp :from h/attrs-of) sources)))))))

(deftest a-behavior-with-no-parameters-is-a-switch
  (let [rows (h/find-all (view/settings settings-state) :lt.ui.row/list-row)
        leading (keep (comp :leading h/attrs-of) rows)]
    (is (= 1 (count leading))
        "attaching it is the setting, so the row leads with a toggle and has no fields")))

(deftest the-filter-is-over-both-the-description-and-the-tag
  (is (= 1 (count (h/find-all (view/settings (assoc-in settings-state [:settings :query] "theme"))
                              :lt.ui.row/list-row))))
  (is (= 1 (count (h/find-all (view/settings (assoc-in settings-state [:settings :query] "editor"))
                              :lt.ui.row/list-row))))
  (testing "and a query that matches nothing says so rather than drawing an empty list"
    (let [s (view/settings (assoc-in settings-state [:settings :query] "zzz"))]
      (is (empty? (h/find-all s :lt.ui.row/list-row)))
      (is (seq (h/find-all s :lt.ui.chrome/empty-state))))))

(deftest keys-is-a-view-over-the-keymap
  (let [rows (h/find-all (view/keys-screen settings-state) :lt.ui.row/list-row)]
    (is (= 2 (count rows)))
    (testing "and a binding reads as the command it runs rather than as its action vector"
      ;; It used to print the vector, which is what it is and not what anyone is
      ;; looking for.
      (is (re-find #"save" (h/text-of (first rows)))))))

(deftest the-key-being-captured-is-the-only-one-with-an-input
  (let [s (view/keys-screen (assoc-in settings-state [:settings :capturing] "cmd-s"))
        rows (h/find-all s :lt.ui.row/list-row)
        selected (filter (comp :selected? h/attrs-of) rows)]
    (is (= 1 (count selected)))
    (testing "and it is the one that was clicked"
      (is (re-find #"save" (h/text-of (first selected)))))))

(deftest the-keymap-filter-searches-the-command-not-only-the-key
  (is (= 1 (count (h/find-all (view/keys-screen (assoc-in settings-state [:settings :query] "save"))
                              :lt.ui.row/list-row)))))

(deftest the-screen-shows-one-half-at-a-time
  ;; Asserted on the text rather than on the components, because a component
  ;; handed to a row as `:leading` is invisible to `find-all`: `nodes` descends
  ;; a vector's children and an attribute map is not one of them. That is worth
  ;; knowing about this harness rather than working around silently — the
  ;; keyboard hint in this screen and in the command bar both live there, so
  ;; neither can be found by tag.
  (testing "settings by default"
    (let [s (view/settings-screen settings-state)]
      (is (seq (h/find-all s :lt.ui.field/number-input)))
      (is (re-find #"Set tab settings" (h/text-of s)))))
  (testing "and keys when that is what is showing"
    (let [s (view/settings-screen (assoc-in settings-state [:settings :showing] :keys))]
      (is (empty? (h/find-all s :lt.ui.field/number-input)))
      (is (re-find #"save" (h/text-of s)))
      (is (not (re-find #"Set tab settings" (h/text-of s)))))))

(deftest an-empty-projection-says-which-kind-of-empty-it-is
  (testing "nothing loaded is a different sentence from nothing matching"
    (let [none (view/settings (assoc settings-state :settings {:entries [] :query ""}))
          no-match (view/settings (assoc-in settings-state [:settings :query] "zzz"))]
      (is (re-find #"have not loaded" (h/text-of none)))
      (is (re-find #"None of them are called that" (h/text-of no-match))))))

(deftest an-empty-keymap-says-which-kind-of-empty-it-is-too
  ;; Both branches, because neither was covered and clj-kondo found an
  ;; unresolved symbol on one of them that every test here had walked past.
  ;; On the body rather than on `:what`, which is an attribute and so invisible
  ;; to `text-of` — the same blind spot the half-at-a-time test records.
  (let [none (view/keys-screen (assoc settings-state :keymap {}))
        no-match (view/keys-screen (assoc-in settings-state [:settings :query] "zzz"))]
    (is (re-find #"is the map of them" (h/text-of none)))
    (is (re-find #"Try the command's name" (h/text-of no-match)))))

(deftest the-window-is-one-function-of-one-value
  (let [w (view/window state)]
    (is (= :div.window (first w)))
    (testing "and it contains every view that has something to show"
      (is (seq (h/find-all w :lt.ui.chrome/tab)))
      (is (seq (h/find-all w :lt.ui.row/list-row)))
      (is (seq (h/find-all w :lt.ui.chrome/connection-row)))
      ;; The pane, not the multibuffer: the window shows one or the other, and
      ;; this state has an editor open. What the multibuffer contains is
      ;; `the-multibuffer-is-a-window-onto-the-edits` below.
      (is (seq (h/find-all w :lt.ui.pane/pane)))
      (is (empty? (h/find-all w :lt.ui.band/proposed-edit)))))
  (testing "an empty state renders rather than throwing, which is what a new window is"
    (is (vector? (view/window {})))))

(deftest the-multibuffer-is-a-window-onto-the-edits
  ;; Six excerpts is hiccup, six hundred is a virtual list — so only the ones
  ;; near where you are looking are rendered, and the rest are a count.
  (let [many (vec (for [i (range 60)]
                    {:at ["a.ts" i] :summary (str "edit " i) :applied? false
                     :evidence {:as-written "x" :if-applied "y"}}))
        state (-> state
                  (assoc-in [:runs "port-fuzzy" :edits] many)
                  (assoc-in [:review :at] 30))
        mb (view/multibuffer state)]
    (is (= 25 (count (h/find-all mb :lt.ui.chrome/excerpt-header)))
        "twelve either side of the cursor, and the cursor's own")
    (testing "and what is not rendered is said rather than dropped"
      (let [folds (map h/attrs-of (h/find-all mb :lt.ui.chrome/fold-row))]
        (is (= [18 17] (map :lines folds)))))
    (testing "keyed by address, so an excerpt that scrolls out and back is the same node"
      (is (= ["a.ts" 18] (:replicant/key (h/attrs-of (first (h/find-all mb :div.excerpt-group)))))))))

(deftest a-review-cursor-outside-the-list-does-not-take-the-window-down
  ;; The cursor is state and the list is a projection, so the two are allowed
  ;; to disagree for a moment.
  (let [past-the-end (assoc-in state [:review :at] 99)]
    (is (vector? (view/multibuffer past-the-end)))
    (is (= 2 (count (h/find-all (view/multibuffer past-the-end) :lt.ui.chrome/excerpt-header)))))
  (let [nothing (-> state (assoc-in [:runs "port-fuzzy" :edits] []) (assoc-in [:review :at] 5))]
    (is (vector? (view/multibuffer nothing)))
    (is (empty? (h/find-all (view/multibuffer nothing) :lt.ui.chrome/excerpt-header)))))

(deftest statusbar-says-what-the-language-server-is-doing
  ;; "I cannot tell if the language server is doing anything" is a real report,
  ;; and the four ways it can be quiet look identical from the outside. Three of
  ;; them are worth acting on and the fourth is worth being able to rule out.
  ;;
  ;; Asserted on the alias and its attributes rather than on the classes it
  ;; expands to: an alias is a keyword until a renderer expands it, so `dot--lost`
  ;; does not exist in this tree. `test-e2e/renderer.spec.ts` reads the classes,
  ;; in a window, where they do.
  (letfn [(bar [lsp] (view/statusbar (cond-> {:cursor {:line 0 :ch 0}} lsp (assoc :lsp lsp))))
          (indicator [lsp] (first (h/find-all (bar lsp) :lt.ui.chrome/status)))]

    (testing "no server configured for this file type draws nothing"
      (is (empty? (h/find-all (bar nil) :span.statusbar__lsp))))

    (testing "one that is declared and not installed is lost"
      (is (= 1 (count (h/find-all (bar {:command "clojure-lsp" :status :lost})
                                  :span.statusbar__lsp))))
      (is (= :lost (:status (h/attrs-of (indicator {:command "clojure-lsp" :status :lost})))))
      (is (string/includes? (h/text-of (bar {:command "clojure-lsp" :status :lost}))
                            "clojure-lsp")))

    (testing "one that has not been reached yet is hollow, and a starting one pulses"
      (is (:hollow (h/attrs-of (indicator {:command "x" :status :queued}))))
      (let [a (h/attrs-of (indicator {:command "x" :status :connecting}))]
        (is (= :connecting (:status a)))
        (is (:pulse a))))

    (testing "and one that is answering says how many diagnostics it has drawn"
      (is (= :finished (:status (h/attrs-of (indicator {:command "clojure-lsp"
                                                        :status :finished})))))
      (is (string/includes? (h/text-of (bar {:command "clojure-lsp" :status :finished
                                             :diagnostics 3}))
                            "clojure-lsp · 3"))
      ;; None is not a number worth drawing.
      (is (not (string/includes? (h/text-of (bar {:command "clojure-lsp" :status :finished
                                                  :diagnostics 0}))
                                 "·"))))

    (testing "clicking it asks for the sentence"
      (is (= [[:cmd/exec :lsp.status]]
             (-> (bar {:command "clojure-lsp" :status :finished})
                 (h/find-all :span.statusbar__lsp)
                 first
                 h/attrs-of
                 :on
                 :click))))))

;;*********************************************************
;; Splits
;;*********************************************************

(def ^:private split-state
  "Two tabsets, each with its own active tab — which is what a split is.

  Every other test here has one, and that is exactly why the window view could
  read `(first tabsets)` twice and look correct: a window with no splits has one
  tabset, so nothing ever disagreed with it."
  (assoc state
         :tabsets [{:id 0 :active? true :active 0
                    :tabs [{:id "a.cljs" :label "a.cljs" :path "a.cljs"}]}
                   {:id 1 :active? false :active 1
                    :tabs [{:id "b.cljs" :label "b.cljs" :path "b.cljs"}
                           {:id "c.cljs" :label "c.cljs" :path "c.cljs"}]}]
         :editors {"a.cljs" {:lang :cljs} "b.cljs" {:lang :cljs} "c.cljs" {:lang :cljs}}))

(deftest a-tabset-draws-its-own-strip
  (let [w (view/window split-state)
        strips (h/find-all w :div.titlebar)]
    (is (= 2 (count strips))
        "one strip per tabset — this drew one, whatever the number of tabsets")))

(deftest a-tabset-draws-its-own-active-file
  (testing "not the first tabset's, which is what `(first tabsets)` gave every column"
    (let [panes (h/find-all (view/window split-state) :lt.ui.pane/pane)]
      (is (= ["a.cljs" "c.cljs"] (mapv (comp :path h/attrs-of) panes))
          "the second column shows its own active tab, which is index 1"))))

(deftest each-column-is-keyed-by-its-tabset
  ;; Without a key, adding a split re-creates every column's node — and a pane's
  ;; node holds a real editor, so rebuilding one throws the editor away.
  (let [columns (h/find-all (view/window split-state) :div.window__column)]
    (is (= [0 1] (mapv (comp :replicant/key h/attrs-of) columns)))))

(deftest a-window-with-no-splits-is-unchanged
  (testing "one tabset, one strip, one pane — the case every other test asserts"
    (let [w (view/window state)]
      (is (= 1 (count (h/find-all w :div.titlebar))))
      (is (= 1 (count (h/find-all w :div.window__column)))))))

(deftest editor-pane-still-defaults-to-the-first-tabset
  (testing "the one-arity is what the catalogue and the kit call"
    (is (= "src-worker/fuzzy.ts" (:path (h/attrs-of (view/editor-pane state)))))
    (is (= "c.cljs" (:path (h/attrs-of (view/editor-pane split-state 1)))))))
