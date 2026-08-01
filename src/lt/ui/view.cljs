(ns lt.ui.view
  "The eight views. Pure functions of the whole state, composing aliases.

  These are not components. They take data and return hiccup, and there is
  nowhere for them to keep a secret — no local state, no instance, no lifecycle.
  A view that needs to know something must be handed it, which is why each one
  takes the whole state rather than a slice: the slicing is done here, in the
  open, rather than by a subscription nobody can see.

  The useful consequence is the reason to do it this way. Only these eight ever
  read state, so every question about whether the window is right is a question
  about eight functions — and every one of them can be asked in a test, with a
  map, in milliseconds. `test/lt/ui/view_test.cljs` is that.

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
            :count (when run (count (remove :applied? (:edits run))))
            :on-select [[:tab/activate i]]}
           (if run (:label run) (leaf id))
           (when (get-in editors [id :dirty?]) [:span.dot.dot--result])]))
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
         :on-select [[:review/goto i]]}
        [:span.row__num (second (:at e))]
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
  actually run\" has an answer on screen."
  [{:keys [clients focus]}]
  [:div.panel
   [::chrome/panel-header {:count (count clients)} "Connections"]
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
       :on-select [[:client/bind id]]}])
   (when (empty? clients)
     [::chrome/empty-state {:what "No connection for this editor"}
      "A buffer needs a client before anything can be evaluated."])])

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
  "Line and column, what is running, and nothing else.

  The counts are of things that are waiting for you — unapplied edits and
  executing runs — because a status bar that reports what is fine is a status
  bar nobody reads."
  [{:keys [cursor runs message]}]
  (let [waiting (->> (vals runs) (mapcat :edits) (remove :applied?) count)
        running (->> (vals runs) (filter (comp #{:executing} :status)) count)]
    [:div.statusbar
     [:span.statusbar__pos (str (inc (:line cursor 0)) " / " (inc (:ch cursor 0)))]
     (when message [:span.statusbar__message message])
     (when (pos? running)
       [::chrome/status {:status :executing :pulse true}
        (str running " run" (when-not (= 1 running) "s"))])
     (when (pos? waiting)
       [:span.statusbar__waiting "waiting on you " [::chrome/count-pill {:count waiting :tone :result}]])]))

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
          :whose (cond (:conflict e) :conflict
                       (not (:applied? e)) :run)}]
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
    (multibuffer state)]
   (statusbar state)
   (command-bar state)])
