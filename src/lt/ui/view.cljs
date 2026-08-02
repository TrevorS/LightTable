(ns lt.ui.view
  "The nine views. Pure functions of the whole state, composing aliases.

  These are not components. They take data and return hiccup, and there is
  nowhere for them to keep a secret — no local state, no instance, no lifecycle.
  A view that needs to know something must be handed it, which is why each one
  takes the whole state rather than a slice: the slicing is done here, in the
  open, rather than by a subscription nobody can see.

  The useful consequence is the reason to do it this way. Only these nine ever
  read state, so every question about whether the window is right is a question
  about nine functions — and every one of them can be asked in a test, with a
  map, in milliseconds. `test/lt/ui/view_test.cljs` is that.

  Eight are the design's. `workspace` is not: the document draws the chrome
  around a run and takes the file tree as given, so that one is written from
  the editor's own requirements — and it needed nothing added to the kit, which
  is the useful half of the answer.

  Handlers are vectors. See [[lt.actions]]."
  (:require [clojure.string :as string]
            [lt.ui.band :as band]
            [lt.ui.chrome :as chrome]
            [lt.ui.row :as row]))

(defn- leaf [path]
  (if-let [cut (string/last-index-of (str path) "/")]
    (subs path (inc cut))
    path))

;;*********************************************************
;; 1 · titlebar
;;*********************************************************

(defn titlebar
  "The tabs of the active tabset. A run is a tab like any other.

  Which is not a flourish: a run has a buffer of proposed edits behind it, so
  it is the same kind of thing as a file, and giving it a different chrome
  would be claiming otherwise.

  A run that is not already in the tab list is appended to it. In the design's
  state the run is simply one of `:tabs`, because the list is authoritative;
  here it is projected from the objects that own the real tabs, and those know
  nothing about runs. When tabs are state rather than a projection this line
  goes away and the run is just an id like the others."
  [{:keys [tabsets runs editors]}]
  (let [{:keys [tabs active]} (first tabsets)
        tabs (concat tabs (remove (set tabs) (keys runs)))]
    [:div.titlebar
     (map-indexed
      (fn [i id]
        (let [run (get runs id)]
          [::chrome/tab
           {:replicant/key id
            :active? (= i active)
            :origin (when run :run)
            :dirty? (boolean (get-in editors [id :dirty?]))
            :count (when run (count (remove :applied? (:edits run))))
            :on-select [[:tab/activate i]]}
           (if run (:label run) (leaf id))]))
      tabs)]))

;;*********************************************************
;; 2 · review queue
;;*********************************************************

(defn review-queue
  "The edits a run proposes for the buffer you are looking at.

  Straight out of the design, including `:replicant/key` being the edit's
  address: an edit that moves keeps its row, and a row that is selected stays
  selected while the list around it changes."
  [{:keys [review runs focus]}]
  (let [run (get runs (:run review))
        edits (:edits run)]
    [:div.panel
     [::chrome/panel-header {:count (count edits)} "In this buffer"]
     (for [[i e] (map-indexed vector edits)]
       [::row/list-row
        {:replicant/key (:at e)                 ; [path line]
         :selected? (= i (:at review))
         :focused? (= focus [:review i])
         :tone (when (:conflict e) :warning)
         :leading [:span.row__num (second (:at e))]
         :on-select [[:review/goto i]]}
        [:span (:summary e)]])
     (when (empty? edits)
       [::chrome/empty-state {:what "Nothing proposed here"}
        "A run that touches this file will put its edits in this list before changing anything."])]))

;;*********************************************************
;; 3 · connections
;;*********************************************************

(def ^:private kind-label
  {:nrepl "clj" :agent "agent" :self "self" :browser "browser"})

(defn connections
  "What an eval will actually reach. The agent is a client like the others.

  `:via` is what makes that true rather than said: an agent that evaluates
  through a REPL is drawn as reaching it, so the question \"where does this
  actually run\" has an answer on screen.

  `:bound?` is the other half of that and is read rather than declared: a
  client is bound when the buffer you are in already evaluates through it.

  What you can do to a connection is in its menu, not on the row. The design's
  rule for a row holds here — what follows the label is a count, a time or a
  hint, and never a control — and disconnecting is not something to put one
  mis-click away from the thing you are reading."
  [{:keys [clients connect]}]
  [:div.panel
   [::chrome/panel-header {:count (count clients)} "Connections"]
   ;; The panel's own affordance rather than a row's, so it is a cluster above
   ;; the list. Choosing shows the kinds instead of the list, the same way the
   ;; workspace panel shows saved workspaces instead of the tree.
   [::chrome/action-cluster {}
    (if (:choosing? connect)
      [::chrome/action {:weight :secondary :on-select [[:client/choose false]]} "cancel"]
      [::chrome/action {:weight :tertiary :on-select [[:client/choose true]]} "add connection"])]
   (if (:choosing? connect)
     [:div.connectors
      ;; A kind is a name and a sentence about it, so the sentence is a child
      ;; rather than `:trailing` — the row's trailing slot is a count, a time
      ;; or a hint, and a paragraph in it is neither of those and does not fit.
      (for [{:keys [name-of desc]} (:connectors connect)]
        [::row/list-row {:replicant/key name-of
                         :on-select [[:client/connect name-of]]}
         [:div
          [:div.connector__name name-of]
          [:div.connector__desc desc]]])
      (when (empty? (:connectors connect))
        [::chrome/empty-state {:what "Nothing to connect to"}
         "A language plugin adds the kinds of connection it knows how to make."])]
     (list
      (for [[id c] (sort-by (comp str key) clients)]
        [::chrome/connection-row
         {:replicant/key id
          :name-of (or (:name c) (str id))
          :kind (:kind c)
          :status (:status c)
          :bound? (:bound? c)
          :what (str (get kind-label (:kind c) (some-> (:kind c) name))
                     (when-let [via (:via c)] (str " · through " via)))
          :trailing (:note c)
          :on-select [[:client/bind id]]
          :on-menu [[:client/menu id]]}])
      (when (empty? clients)
        [::chrome/empty-state {:what "No connection for this editor"}
         "Evaluate something, or add a connection above — a buffer needs a client before anything can run."])))])

;;*********************************************************
;; 3½ · the workspace tree
;;*********************************************************

;; Not one of the design's eight. The document draws the chrome around a run —
;; tabs, review, evidence — and takes the file tree as given, so this is the
;; first view written from the editor's own requirements rather than from a
;; picture. It uses the kit unchanged, which is the useful part of the answer:
;; a tree is `row/tree-row` with a depth, and there was nothing to add.

(defn- visible
  "The rows of the tree, in the order they are drawn.

  Depth-first from the roots, descending only into folders that are open — so
  the sequence is what is on screen and nothing else. The old tree rendered
  every child of every folder it had ever opened and hid the closed ones in
  CSS, which is a `ul` per folder and a DOM node per file in a repository."
  ([nodes roots] (visible nodes roots 0))
  ([nodes paths depth]
   (mapcat (fn [path]
             (let [{:keys [dir? open? children]} (get nodes path)]
               (cons {:path path :depth depth :dir? dir? :open? open?}
                     (when (and dir? open?)
                       (visible nodes children (inc depth))))))
           paths)))

(defn- tree-rows [{ws :workspace :keys [editors]} active]
  (let [{:keys [nodes roots renaming]} ws]
    (for [{:keys [path depth dir? open?]} (visible nodes roots)]
      (if (= path renaming)
        ;; The row becomes its own name, editable. `on-mount` rather than a
        ;; behavior reaching for the node afterwards: this render is what
        ;; creates the input, so this render is where it can be focused — and
        ;; the selection stops at the extension, because that is not the part
        ;; you are changing.
        ;;
        ;; Enter and Escape are the keymap's, in the `:tree.rename` context,
        ;; the same two bindings as before. Enter blurs, and blur is what
        ;; submits, so there is one path out rather than two.
        [:div.row.row--renaming {:replicant/key path
                                 :style {:padding-left (str (+ 22 (* 14 depth)) "px")}}
         [:input.tree__rename
          {:value (leaf path)
           :replicant/on-mount
           (fn [{:replicant/keys [node]}]
             (.focus node)
             (let [n (leaf path)
                   dot (string/last-index-of n ".")]
               (.setSelectionRange node 0 (if (and dot (pos? dot)) dot (count n)))))
           :on {:blur [[:tree/rename-submit path :event/value]]}}]]
        [::row/tree-row
         {:replicant/key path
          :depth depth
          ;; A file has no twist, and `:open?` is how the row knows: `nil`
          ;; reserves the column without drawing anything in it, so names line
          ;; up whether or not the row can be opened.
          :open? (when dir? (boolean open?))
          :selected? (= path active)
          :dirty? (boolean (get-in editors [path :dirty?]))
          :on-select [[(if dir? :tree/toggle :tree/open) path]]
          :on-menu [[:tree/menu path]]}
         [:span.tree__name {:class (when dir? "tree__name--dir")} (leaf path)]]))))

(defn- recents-list [recents]
  [:div.wstree__recents
   [:div.wstree__back {:on {:click [[:workspace/show-tree]]}} "Select a workspace"]
   (for [{:keys [path folders files]} recents]
     [::row/list-row {:replicant/key path
                      :on-select [[:workspace/open path]]}
      [:div
       (for [f folders]
         [:div.wstree__recent-folder {:replicant/key f} (leaf f) "/"])
       (for [f files]
         [:div.wstree__recent-file {:replicant/key f} (leaf f)])]])
   (when (empty? recents)
     [::chrome/empty-state {:what "No workspace has been saved yet"}
      "A workspace is the folders you added, remembered by name."])])

(defn workspace
  "The folders and files you are working on, and what is in them.

  Two things share the panel and only one is ever shown: the tree, and the list
  of workspaces you can switch to. `:recents` being `nil` rather than empty is
  what says which — an empty list of saved workspaces is a thing to say, not a
  reason to show the tree."
  [{ws :workspace :keys [tabsets] :as state}]
  (let [{:keys [tabs active]} (first tabsets)
        current (get (vec tabs) (or active 0))]
    [:div.wstree
     [::chrome/action-cluster {}
      [::chrome/action {:weight :tertiary :on-select [[:workspace/add-folder]]} "folder"]
      [::chrome/action {:weight :tertiary :on-select [[:workspace/add-file]]} "file"]
      [::chrome/action {:weight :tertiary :on-select [[:workspace/show-recents]]} "recent"]]
     (if-let [recents (:recents ws)]
       (recents-list recents)
       [:div.wstree__tree
        (tree-rows state current)
        (when (empty? (:roots ws))
          [::chrome/empty-state {:what "Nothing in this workspace"}
           "Add a folder, or open one you had before — everything else follows from what is in it."])])]))

;;*********************************************************
;; 4 · sidebar
;;*********************************************************

(defn sidebar
  "The panels, in the order the work goes: what is proposed, then what it runs
  against."
  [state]
  [:div.sidebar
   (review-queue state)
   (connections state)])

;;*********************************************************
;; 5 · statusbar
;;*********************************************************

(defn statusbar
  "Line and column, what is working, what it last said, and what waits on you.

  Everything here is a thing you might act on. A status bar that reports what
  is fine is a status bar nobody reads, which is why the loader, the message
  and both counts are each absent rather than zero when there is nothing to
  say — and why the console's unread count is the only way the console appears
  here at all. That is not new: the old bar hid its toggle in CSS whenever it
  was clean, and this says the same thing in the place the decision is made."
  [{:keys [cursor runs message loading console]}]
  (let [waiting (->> (vals runs) (mapcat :edits) (remove :applied?) count)
        running (->> (vals runs) (filter (comp #{:executing} :status)) count)
        unread (:unread console 0)]
    [:div.statusbar
     [:span.statusbar__pos (str (inc (:line cursor 0)) " / " (inc (:ch cursor 0)))]
     ;; Working, without saying what at. The message beside it is what says
     ;; that, and it is set and cleared by whatever is doing the work.
     (when (pos? (or loading 0))
       [::chrome/status-dot {:status :executing :pulse true}])
     (when-let [text (:text message)]
       [:span.statusbar__message
        {:class (when-let [tone (:tone message)] (str "statusbar__message--" (name tone)))}
        text])
     (when (pos? running)
       [::chrome/status {:status :executing :pulse true}
        (str running " run" (when-not (= 1 running) "s"))])
     (when (pos? waiting)
       [:span.statusbar__waiting "waiting on you " [::chrome/count-pill {:count waiting :tone :result}]])
     ;; Last, and only when there is something in it. Clicking runs the command
     ;; rather than reaching for the console object: the bar is a view, and a
     ;; view that held a reference to a panel would be the thing this is
     ;; getting rid of.
     (when (pos? unread)
       [:span.statusbar__console {:on {:click [[:cmd/exec :toggle-console]]}}
        [::chrome/count-pill {:count unread :tone (or (:tone console) :result)}]])]))

;;*********************************************************
;; 6 · command bar
;;*********************************************************

(defn command-bar
  "A fuzzy search across the keys of the dispatch table.

  One of the three surfaces that fall out of actions being data — this one, the
  keymap, and the settings screen below are all views over the same map."
  [{:keys [command-bar keymap]}]
  (when (:open? command-bar)
    (let [q (string/lower-case (or (:query command-bar) ""))
          matches (->> (:commands command-bar)
                       (filter #(string/includes? (string/lower-case (str (:label %))) q))
                       (take 12))]
      [:div.commandbar
       [:input.commandbar__input {:value (:query command-bar) :placeholder "command"}]
       (for [[i c] (map-indexed vector matches)]
         [::row/list-row
          {:replicant/key (:action c)
           :selected? (= i (:at command-bar 0))
           :on-select [(:action c)]
           :trailing (when-let [k (some (fn [[k acts]] (when (= acts [(:action c)]) k)) keymap)]
                       [::chrome/kbd {:keys k}])}
          [:span (:label c)]])
       (when (empty? matches)
         [::chrome/empty-state {:what "No command matches"} "Nothing in the table has that name."])])))

;;*********************************************************
;; 7 · multibuffer
;;*********************************************************

(def ^:private window-size
  "How many excerpts either side of the cursor are real.

  Six excerpts is hiccup. Six hundred is a virtual list, and each excerpt is a
  real editable region — an editor instance rather than markup. So only the
  ones near where you are looking are rendered, and the rest are their own
  height and nothing else."
  12)

(defn- in-view
  "The excerpts to render, and how many are hidden either side.

  `at` is clamped into the list rather than trusted. The review cursor is state
  and the list is a projection, so the two are allowed to disagree for a moment
  — and a view that threw when they did would take the window with it."
  [edits at]
  (let [n (count edits)
        at (min (max (or at 0) 0) (max 0 (dec n)))
        from (max 0 (- at window-size))
        to (min n (+ at window-size 1))]
    {:before from
     :items (map vector (range from to) (subvec (vec edits) from to))
     :after (- n to)}))

(defn editor-pane
  "The file you are looking at, as a real editor inside the chrome.

  The one place a view returns something Replicant must not describe. It is a
  keyed empty element with a mount hook; everything inside it belongs to the
  editor.

  The alias is named by keyword rather than required, because requiring
  `lt.ui.pane` loads an editor and everything under it — see the docstring
  there. This namespace stays a pure function of a value, which is what lets it
  be tested without a DOM."
  [{:keys [tabsets editors]}]
  (let [{:keys [tabs active]} (first tabsets)
        path (get (vec tabs) (or active 0))]
    (when (contains? editors path)
      [:lt.ui.pane/pane {:path path}])))

(defn multibuffer
  "Excerpts assembled by run rather than by file.

  Possible only because runs own edits: the regions of six files a run touched
  are one list here, and none of those files has been changed.

  Keyed by `[path start-line]`, and `:replicant/key` is doing load-bearing work
  — an excerpt that scrolls out and back must be the same node or the editor
  inside it is thrown away and rebuilt."
  [{:keys [review runs]}]
  (let [run (get runs (:run review))
        {:keys [before items after]} (in-view (:edits run) (:at review))]
    [:div.multibuffer
     (when (pos? before)
       [::chrome/fold-row {:lines before :replicant/key :before}])
     (for [[i e] items
           :let [[path line] (:at e)]]
       [:div.excerpt-group {:replicant/key (:at e)}
        [::chrome/excerpt-header
         {:path path
          :range (str "line " line)
          :origin (cond (:conflict e) :conflict
                        (not (:applied? e)) :proposed
                        :else :yours)}]
        (if (:conflict e)
          [::band/conflict {:line line :yours (:yours e) :note (:conflict e)}]
          [::band/proposed-edit {:line line
                                 :before (get-in e [:evidence :as-written])
                                 :after (get-in e [:evidence :if-applied])}])
        (when-let [ev (:evidence e)]
          [::band/evidence {:label "Evidence"
                            :before (:as-written ev)
                            :after (:if-applied ev)}])
        (when (:folded e) [::chrome/fold-row {:lines (:folded e)}])])
     (when (pos? after)
       [::chrome/fold-row {:lines after :replicant/key :after}])]))

;;*********************************************************
;; 8 · settings
;;*********************************************************

(defn settings
  "A view over the keymap, which is a view over the dispatch table.

  This is the payoff of handlers being data, and it is why the screen is eight
  lines rather than a subsystem: there is nothing to build, only something to
  show."
  [{:keys [keymap]}]
  [:div.panel
   [::chrome/panel-header {:count (count keymap)} "Keys"]
   (for [[k actions] (sort-by key keymap)]
     [::row/list-row {:replicant/key k}
      [::chrome/kbd {:keys k}]
      [:span.row__actions (pr-str actions)]])
   (when (empty? keymap)
     [::chrome/empty-state {:what "No keys bound"}
      "A binding is a key and an action vector, and this is the map of them."])])

;;*********************************************************
;; the window
;;*********************************************************

(defn window
  "Everything, from one value.

  The whole window is a function call. That is the claim worth checking, and
  the reason the state is one atom rather than several.

  The statusbar is here because it belongs on screen and because a test should
  be able to ask for the whole window. The *live* chrome renders it into a root
  of its own instead — it reads the cursor, which is a different clock. See
  [[lt.ui.window]]."
  [state]
  [:div.window
   (titlebar state)
   [:div.window__body
    (sidebar state)
    ;; The active file when there is one, and the run's excerpts when there is
    ;; not. A multibuffer is what you look at while reviewing a run; a buffer is
    ;; what you look at the rest of the time.
    (or (editor-pane state) (multibuffer state))]
   (statusbar state)
   (command-bar state)])
