(ns lt.ui.catalogue
  "The kit, rendered from the kit.

  The design document draws twenty-five components and this draws the same
  twenty-five from the aliases the editor actually uses — so the document and
  the implementation cannot disagree about one of them without the disagreement
  being visible. That was the design's own proposed next step, and it is the
  only way a component catalogue is worth having: a picture of a component that
  is not the component is a picture that goes stale.

  **Light Table: Component kit** opens it."
  (:require [lt.object :as object]
            [lt.objs.command :as cmd]
            [lt.objs.tabs :as tabs]
            [lt.ui :as ui]
            [lt.ui.band :as band]
            [lt.ui.chrome :as chrome]
            [lt.ui.row :as row])
  (:require-macros [lt.macros :refer [behavior]]))

(defn- card*
  "One component, in every state it has, on the ground it actually sits on."
  [class nm desc demo]
  [:div {:class ["kit__card" class]}
   [:div.kit__card-head
    [:span.kit__name nm]
    [:span.kit__desc desc]]
   [:div.kit__demo demo]])

(defn- card [nm desc & demo]
  (card* nil nm desc demo))

;; A band is the width of a buffer, so it gets the width of the page.
(defn- wide [nm desc & demo]
  (card* "kit__card--wide" nm desc demo))

(defn- section [nm & cards]
  [:div.kit__section
   [:div.kit__section-name nm]
   [:div.kit__cards cards]])

(def ^:private statuses
  [:queued :connecting :executing :finished :restarting :shutting-down :lost :idle])

(defn- atoms []
  (section
   "01 · atoms"
   (card "StatusDot"
         "One indicator for all seven execution states plus connection health. Hollow means nothing has run yet."
         [:div.kit__demo-row
          (for [s (take 4 statuses)]
            [::chrome/status {:replicant/key s :status s} (name s)])]
         [:div.kit__demo-row
          (for [s (drop 4 statuses)]
            [::chrome/status {:replicant/key s :status s} (name s)])])
   (card "CountPill"
         "A count that belongs to the thing beside it. Never a notification."
         [:div.kit__demo-row
          [::chrome/count-pill {:count 6}]
          [::chrome/count-pill {:count 11 :tone :result}]
          [::chrome/count-pill {:count 34 :tone :agent}]])
   (card "Kbd"
         "A binding, never a button. Symbols only, never spelled out."
         [:div.kit__demo-row
          (for [k ["⏎" "⌥⏎" "⌘R" "⌘↓" "⎋" "⌃⇥"]]
            [::chrome/kbd {:replicant/key k :keys k}])])
   (card "Chip"
         "A scoped fact: what a run will carry, or which frames a trace shows."
         [:div.kit__demo-row
          [::chrome/chip {} "fuzzy.ts:15"]
          [::chrome/chip {:selected true} "selection, 1 line"]
          [::chrome/chip {:tone :agent} "write: src-window"]])
   (card "PathLabel"
         "A path where only the leaf matters. Directories recede, the file does not."
         [:div.kit__demo-row [::chrome/path-label {:path "src-worker/fuzzy.ts"}]]
         [:div.kit__demo-row
          [::chrome/path-label {:path "src-window/navigate.cljs" :range "lines 29–33 of 118"}]])
   (card "Elapsed"
         "Time, only while it is still running or still relevant."
         [:div.kit__demo-row
          [::chrome/elapsed {:ms 8000 :live true}]
          [::chrome/elapsed {:ms 14000}]
          [::chrome/elapsed {:ms 180000}]])))

(defn- rows []
  (section
   "02 · rows"
   (card "ListRow"
         "The one row primitive. Tree, queue, runs, commands and connections are all this."
         [::row/list-row {} "rest"]
         [::row/list-row {:selected? true} "selected — sky @ 15%"]
         [::row/list-row {:focused? true} "focused"]
         [::row/list-row {:tone :disabled} "unavailable"]
         [::row/list-row {:tone :warning} "touches your edit"]
         [::row/list-row {:tone :error} "the process died"]
         [::row/list-row {:origin :run :trailing "6"} "port fuzzy to ranges"])
   (card "TreeRow"
         "ListRow with depth and disclosure. Dirty is a dot, not a colour change."
         [::row/tree-row {:depth 0 :open? true} "src-worker"]
         [::row/tree-row {:depth 1} "walkdir.ts"]
         [::row/tree-row {:depth 1 :dirty? true} "fuzzy.ts"]
         [::row/tree-row {:depth 0 :open? false :trailing "12"} "src-window"])))

(defn- chrome []
  (section
   "03 · chrome"
   (card "Tab"
         "Lives in the titlebar. Active is the editor ground pulled up into the chrome."
         [:div.kit__demo-row
          [::chrome/tab {:active? true} "fuzzy.ts"]
          [::chrome/tab {} "tabs.cljs"]
          [::chrome/tab {:origin :run :count 6} "port fuzzy to ranges"]])
   (wide "ExcerptHeader"
         "Names the file and range a multibuffer region came from, and whose edit it is."
         [::chrome/excerpt-header {:path "src-worker/fuzzy.ts"
                                   :range "lines 12–24 of 96 · 2 edits proposed"
                                   :whose :run}]
         [::chrome/excerpt-header {:path "src-window/behaviors.cljs"
                                   :range "line 44 of 210 · conflicts with your edit"
                                   :whose :conflict}])
   (card "FoldRow"
         "Stands in for the lines nobody needs to see. Never a number without a count."
         [::chrome/fold-row {:lines 7}])
   (card "Breadcrumb"
         "The path you walked into a value. Every segment is still addressable."
         [:div.kit__demo-row
          [::chrome/breadcrumb {:root "@multi" :segments [:tabsets 0]}]])
   (wide "CauseRow"
         "One link in a cause chain. The root is the only one that gets actions."
         [::chrome/cause-row {:depth 2 :kind "SocketException"} "Connection reset by peer"]
         [::chrome/cause-row {:depth 1 :kind "root cause" :root?  true}
          "nREPL on 51423 stopped 8s ago, exit 137"])
   (card "ActionCluster"
         "Three weights, one row, and the narrowest grant is always leftmost."
         [::chrome/action-cluster {}
          [::chrome/action {:weight :primary} "Allow src-window too"]
          [::chrome/action {:weight :secondary} "See what it wants to write"]
          [::chrome/action {:weight :tertiary} "End the run"]])
   (wide "ConnectionRow"
         "What an eval will actually reach. The agent is a client like the others."
         [::chrome/connection-row {:name-of "nREPL 51423" :what "clj · lt.objs.tabs"
                                   :status :finished :bound? true :trailing "this tab"}]
         [::chrome/connection-row {:name-of "claude · terminal" :kind :agent
                                   :what "agent · evaluates through the first"
                                   :status :executing :trailing "3 runs"}]
         [::chrome/connection-row {:name-of "Light Table itself" :what "self · the window you are in"
                                   :status :idle :trailing "idle"}])
   (card "PanelHeader"
         "A label and a count. Panels do not get toolbars."
         [::chrome/panel-header {:count 11} "Waiting on you"])
   (wide "EmptyState"
         "Says what is missing and offers the narrowest way to fix it. Never an illustration."
         [::chrome/empty-state {:what "No connection for this editor"}
          "A .cljs buffer needs a ClojureScript client before anything can be evaluated."
          [::chrome/action-cluster {}
           [::chrome/action {:weight :primary} "Connect shadow-cljs"]
           [::chrome/action {:weight :tertiary} "Pick a client"]]])))

(defn- bands []
  (section
   "04 · bands"
   (wide "ResultBand"
         "A value beside the line that produced it. Addressed by [path line], which is what makes it promotable."
         [::band/result {:line 7 :status :finished :value "({:count 2})"}]
         [::band/result {:line 12 :status :executing}]
         [::band/result {:line 14 :status :finished :value "[{:start 0}]" :against-unapplied 2}])
   (wide "WatchBand"
         "A value under observation. Teal, because it re-reads itself."
         [::band/watch {:line 19 :expression "(recur (inc i))  i " :value "7" :reads 8}])
   (wide "EvidenceBlock"
         "The whole argument of the design: what the value was, and what it becomes."
         [::band/evidence {:label "Evidence · run just now through tsserver and node"
                           :before "walk(\"src-worker\") → TypeError"
                           :after "walk(\"src-worker\") → (\"walkdir.ts\")"}])
   (wide "ProposedEdit"
         "Struck original, tinted replacement, both on the same code column."
         [::band/proposed-edit {:line 14
                                :before "const e = fs.readdir(dir)"
                                :after "const e = fsp.readdir(dir)"}])
   (wide "ConflictBand"
         "You edited a line a run had already read. Routine, not an error."
         [::band/conflict {:line 44
                           :yours "(score nm q)  ; you changed this 40s ago"
                           :note "Yours changes the threshold, the proposal changes the return type — not the same change."}
          [::chrome/action-cluster {}
           [::chrome/action {:weight :primary} "Show both evaluated"]
           [::chrome/action {:weight :secondary} "Re-run against mine"]
           [::chrome/action {:weight :tertiary} "Keep mine"]]])
   (wide "DiagnosticBand"
         "An LSP diagnostic, in the buffer, with the reason beside the line."
         [::band/diagnostic {:line 27 :code "ts2769"
                             :message "No overload matches this call — the options form is only on fs/promises."}])))

(defn- catalogue-ui [_]
  (list
   [:div.kit__title "Twenty-five components, and only one of them is the idea"]
   [:div.kit__blurb
    "Rendered from the same aliases the editor uses, so this page and the
     implementation cannot disagree about a component. Each cell is the
     component in every state it has, on the ground it actually sits on."]
   (atoms)
   (rows)
   (chrome)
   (bands)))

(behavior ::on-close-destroy
          :triggers #{:close}
          :reaction (fn [this]
                      (object/raise this :destroy)))

(object/object* ::catalogue
                :tags #{:kit.catalogue}
                :behaviors [::on-close-destroy]
                :name "Component kit"
                :init (fn [this]
                        (ui/node this [:div.kit] catalogue-ui)))

(cmd/command {:command :kit.catalogue
              :desc "Light Table: Component kit"
              :exec (fn []
                      (let [c (object/create ::catalogue)]
                        (tabs/add! c)
                        (tabs/active! c)))})
