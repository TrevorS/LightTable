(ns lt.ui.stories.chrome
  "What the seventeen chrome components are, as data.

  Beside the components rather than inside them: `lt.ui.chrome` is seventeen
  aliases and nothing else, and it stays that way — a component namespace that
  also holds its own documentation is a component namespace you cannot read.

  Every sentence, prop table and usage line here came out of
  [[lt.ui.catalogue]], where they were written first and where they used to be
  the only copy. Both surfaces read this now.

  A state is props where a component is worth seeing alone and `:hiccup` where
  it is not — an action cluster with no actions in it is not a component in a
  state, it is an empty box."
  (:require [lt.ui.chrome :as chrome]
            [lt.ui.story :as story]))

(story/of ::chrome/status-dot
  {:doc "One indicator for all seven execution states plus connection health.
         Hollow means nothing has run yet."
   :props [[":status" "one of the eight" "what it is doing now"]
           [":hollow" "boolean" "nothing has run yet"]
           [":pulse" "boolean" "connecting and restarting only"]]
   :usage "band/result · view/statusbar · every titlebar"
   :width :narrow
   ;; In the order a run moves through them, which is why these are array-maps
   ;; throughout this file. `queued` before `executing` before `finished` is
   ;; information; alphabetical is not.
   :states (array-map
            :queued {:status :queued}
            :connecting {:status :connecting}
            :executing {:status :executing}
            :finished {:status :finished}
            :restarting {:status :restarting}
            :shutting-down {:status :shutting-down}
            :lost {:status :lost}
            :idle {:status :idle}
            :hollow {:status :finished :hollow true}
            :pulsing {:status :connecting :pulse true})})

(story/of ::chrome/status
  {:doc "A dot with a word. The statusbar and the titlebar both need the pair,
         so the pair is the component."
   :props [[":status" "one of the eight" "passed straight to status-dot"]
           [":pulse" "boolean" "executing and connecting"]
           ["body" "children" "the label"]]
   :usage "view/statusbar · view/titlebar · band/result"
   :note "the label is body text, not the status colour — the dot carries the state"
   :states (array-map
            :executing {:status :executing :pulse true :children ["executing"]}
            :queued {:status :queued :children ["3 runs"]}
            :finished {:status :finished :children ["nREPL 51423"]})})

(story/of ::chrome/count-pill
  {:doc "A count that belongs to the thing beside it. Never a notification."
   :props [[":count" "number" ""]
           [":tone" ":agent | :result | :error | :neutral" "neutral by default"]]
   :usage "row/list-row · chrome/tab · chrome/panel-header · view/statusbar"
   :width :narrow
   :note "mauve = proposed · sky = waiting on you · red = one of them went wrong ·
          neutral = just a number"
   :states (array-map
            :agent {:count 6 :tone :agent}
            :result {:count 11 :tone :result}
            :error {:count 2 :tone :error}
            :neutral {:count 34})})

(story/of ::chrome/kbd
  {:doc "A binding, never a button. Symbols only, never spelled out."
   :props [[":keys" "string" "the symbol run"]]
   :usage "view/statusbar · view/command-bar · view/settings"
   :width :narrow
   :note "subtext1 on text @ 6% — reads as a key, not a control"
   :states (array-map
            :enter {:keys "⏎"}
            :alt-enter {:keys "⌥⏎"}
            :run {:keys "⌘R"}
            :down {:keys "⌘↓"}
            :escape {:keys "⎋"}
            :next-tab {:keys "⌃⇥"})})

(story/of ::chrome/chip
  {:doc "A scoped fact: what a run will carry, or which frames a trace shows."
   :props [["body" "children" "the label"]
           [":selected" "boolean" "sky @ 15%"]
           [":tone" ":neutral | :agent | :warning" "capability chips are agent"]]
   :usage "view/command-bar"
   :states (array-map
            :selected {:selected true :children ["fuzzy.ts:15"]}
            :plain {:children ["selection, 1 line"]}
            :agent {:tone :agent :children ["write: src-window"]}
            :warning {:tone :warning :children ["unsandboxed"]})})

(story/of ::chrome/path-label
  {:doc "A path where only the leaf matters. Directories recede, the file does not."
   :props [[":path" "string" ""]
           [":range" "string?" "shown when it is an excerpt"]]
   :usage "chrome/excerpt-header · view/multibuffer"
   :states (array-map
            :file {:path "src-worker/fuzzy.ts"}
            :excerpt {:path "src-window/cm6-editor.ts" :range "lines 12–24 of 96"})})

(story/of ::chrome/elapsed
  {:doc "Time, only while it is still running or still relevant."
   :props [[":ms" "number" ""]
           [":live" "boolean" "sky while counting, overlay2 at rest"]]
   :usage "band/result · chrome/connection-row"
   :width :narrow
   :states (array-map
            :live {:ms 8000 :live true}
            :seconds {:ms 14000}
            :minutes {:ms 180000}
            :hours {:ms 7200000})})

(story/of ::chrome/tab
  {:doc "Lives in the titlebar. Active is the editor ground pulled up into the chrome."
   :props [["body" "children" "the label"]
           [":active?" "boolean" "background becomes base"]
           [":dirty?" "boolean" "sky dot"]
           [":origin" ":run" "a run is a tab like any other"]
           [":count" "number?" "pill, agent tone for runs"]
           [":on-close" "actions?" "only when the close-button behavior is on"]
           [":on-menu" "actions" "right-clicking it"]
           [":draggable?" "boolean" "picked up and put down as state"]]
   :usage "view/titlebar"
   :note "active = base · rest = overlay2 on crust · a run is a tab like any other"
   :states (array-map
            :active {:active? true :children ["fuzzy.ts"]}
            :dirty {:dirty? true :children ["tabs.cljs"]}
            :counted {:count 11 :children ["Review"]}
            :run {:origin :run :count 6 :children ["port fuzzy to ranges"]}
            :closable {:on-close [[:tab/close 0 4]] :children ["closable.md"]})})

(story/of ::chrome/excerpt-header
  {:doc "Names the file and range a multibuffer region came from, and whose edit it is."
   :props [[":path" "string" ""]
           [":range" "string" ""]
           [":origin" ":proposed | :yours | :conflict"
            "three, because your own edit is not unattributed"]]
   :usage "view/multibuffer"
   :width :wide
   :states (array-map
            :proposed {:path "src-worker/fuzzy.ts"
                       :range "lines 12–24 of 96 · 2 edits"
                       :origin :proposed}
            :conflict {:path "src-window/behaviors.cljs"
                       :range "line 44 of 210 · conflicts with your edit"
                       :origin :conflict}
            :yours {:path "src-window/cm6-editor.ts"
                    :range "line 88 of 940"
                    :origin :yours})})

(story/of ::chrome/fold-row
  {:doc "Stands in for the lines nobody needs to see. Never a number without a count."
   :props [[":lines" "number" ""]]
   :usage "view/multibuffer"
   :width :narrow
   :states (array-map :many {:lines 7} :one {:lines 1})})

(story/of ::chrome/breadcrumb
  {:doc "The path you walked into a value. Every segment is still addressable."
   :props [[":root" "string" ""]
           [":segments" "any[]" "printed, so a keyword still looks like one"]
           [":siblings" "[at total]" "position among peers"]]
   :usage "the inspector"
   :states (array-map
            :walked {:root "@multi" :segments [:tabsets 0] :siblings [2 3]}
            :root-only {:root "@multi" :segments []})})

(story/of ::chrome/cause-row
  {:doc "One link in a cause chain. The root is the only one that gets actions."
   :props [[":depth" "number" "counts down to the root"]
           [":kind" "string" "the exception's own name"]
           [":root?" "boolean" "only the root carries actions"]]
   :usage "the exception view"
   :width :wide
   :states (array-map
            :link {:depth 2 :kind "SocketException"
                   :children ["Connection reset by peer"]}
            ;; Hiccup, because the root's whole point is the cluster it carries
            ;; and a root row without one is the link above under another name.
            :root {:hiccup [::chrome/cause-row
                            {:depth 1 :kind "root cause · the process exited" :root? true}
                            "nREPL on 51423 stopped 8s ago, exit 137"
                            [::chrome/action-cluster {:tone :error}
                             [::chrome/action {:weight :primary} "Restart it"]
                             [::chrome/action {:weight :secondary} "Last 40 lines"]]]})})

(story/of ::chrome/action
  {:doc "One button. The cluster is a separate alias because the row is the thing
         with an opinion about order."
   :props [["body" "children" "the label"]
           [":weight" ":primary | :secondary | :tertiary" "tertiary by default"]
           [":on-select" "action vector" "[[:run/grant id cap]]"]]
   :usage "chrome/action-cluster, and nowhere on its own"
   :note "a real <button>, so it is focusable and a keyboard can reach it"
   :states (array-map
            :primary {:weight :primary :children ["Restart it"]}
            :secondary {:weight :secondary :children ["Last 40 lines"]}
            :tertiary {:weight :tertiary :children ["End the run"]})})

(story/of ::chrome/action-cluster
  {:doc "Three weights, one row, and the narrowest grant is always leftmost."
   :props [["body" "children" "the actions, in order"]
           [":tone" ":result | :agent | :warning | :error" "sets the primary fill"]]
   :usage "chrome/empty-state · chrome/cause-row · band/conflict"
   :note "primary = the accent of the situation · secondary = text @ 6% · tertiary = bare"
   ;; Hiccup throughout: a cluster is the arrangement of what is in it, so a
   ;; state of this component with no actions in it shows nothing about it.
   :states (array-map
            :agent {:hiccup [::chrome/action-cluster {:tone :agent}
                             [::chrome/action {:weight :primary} "Allow src-window too"]
                             [::chrome/action {:weight :secondary} "See what it wants to write"]
                             [::chrome/action {:weight :tertiary} "End the run"]]}
            :error {:hiccup [::chrome/action-cluster {:tone :error}
                             [::chrome/action {:weight :primary} "Restart it"]
                             [::chrome/action {:weight :secondary} "Last 40 lines"]]}
            :warning {:hiccup [::chrome/action-cluster {:tone :warning}
                               [::chrome/action {:weight :primary} "Show both evaluated"]
                               [::chrome/action {:weight :tertiary} "Keep mine"]]})})

(story/of ::chrome/connection-row
  {:doc "What an eval will actually reach. The agent is a client like the others."
   :props [[":name-of" "string" ""]
           [":kind" ":nrepl | :agent | :self | :browser" ""]
           [":status" "one of the eight" ""]
           [":bound?" "boolean" "this editor evaluates here — read, not stored"]
           [":what" "string" "and what it reaches through"]
           [":on-menu" "actions" "disconnecting is in the menu, not on the row"]]
   :usage "view/connections — the connect panel in the right bar"
   :width :wide
   :states (array-map
            :bound {:name-of "nREPL 51423" :what "clj · lt.objs.tabs"
                    :status :finished :bound? true :trailing "this tab"}
            :agent {:name-of "claude · terminal" :kind :agent
                    :what "agent · evaluates through the first"
                    :status :executing :trailing "3 runs"}
            :self {:name-of "Light Table itself" :kind :self
                   :what "self · the window you are in"
                   :status :idle :trailing "idle"})})

(story/of ::chrome/panel-header
  {:doc "A label and a count. Panels do not get toolbars."
   :props [["body" "children" "the label"]
           [":count" "number?" ""]]
   :usage "view/review-queue · view/connections · view/settings"
   :width :narrow
   :states (array-map
            :counted {:count 11 :children ["Waiting on you"]}
            :plain {:children ["Connections"]})})

(story/of ::chrome/empty-state
  {:doc "Says what is missing and offers the narrowest way to fix it.
         Never an illustration."
   :props [[":what" "string" "what is missing"]
           ["body" "children" "why, in one sentence, then the actions"]]
   :usage "every panel that can be empty"
   :width :wide
   :states (array-map
            :no-connection
            {:hiccup [::chrome/empty-state {:what "No connection for this editor"}
                      "A .cljs buffer needs a ClojureScript client before anything can be evaluated."
                      [::chrome/action-cluster {}
                       [::chrome/action {:weight :primary} "Connect shadow-cljs"]
                       [::chrome/action {:weight :tertiary} "Pick a client"]]]}
            :no-actions
            {:hiccup [::chrome/empty-state {:what "Nothing waiting on you"}
                      "Runs that need a decision appear here."]})})
