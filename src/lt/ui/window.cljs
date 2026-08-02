(ns lt.ui.window
  "The window, rendered from the state atom.

  `(view/window @state/app)` is the chrome, and everything on it changed
  because the state changed — there is no other way for it to have changed.

  **Not one root.** Three, for the reason section 06 of the design gives: the
  chrome renders from `state/app`, the statusbar from `state/cursor`, and each
  band from wherever its value lives. A cursor that went through the main atom
  would re-render the window on every keypress, and a streaming watch would do
  it on every frame. Splitting the roots is what admits that these are
  different clocks.

  This is not yet the chrome you use, with one exception that is the point of
  the exercise: the statusbar along the bottom of the real editor is
  `view/statusbar`, rendered by [[lt.objs.statusbar]] through the same helper
  this uses. A surface moves by moving its root, and that one has moved.

  The rest opens in a tab beside the real chrome so the two can be compared
  while the projection in [[lt.state.objects]] is still being filled in. What
  is left is what composes other objects' DOM — the tabs above all — and that
  waits for those to be views rather than objects with nodes.

  **Light Table: Window as a view** opens it."
  (:require [lt.object :as object]
            [lt.objs.command :as cmd]
            [lt.objs.editor :as editor]
            [lt.objs.tabs :as tabs]
            [lt.state :as state]
            [lt.util.js :as util]
            [lt.state.objects :as from-objects]
            [lt.ui :as ui]
            [lt.ui.view :as view]
            [lt.ui.hiccup :as hiccup])
  (:require-macros [lt.macros :refer [behavior]]))

(behavior ::sync-from-objects
          :triggers #{:active :dirty :clean :close :focus :set-client}
          :desc "State: Keep the state atom current with this editor"
          :doc "The views are functions of one value and the editor's facts are
                spread across a hundred objects, so something has to carry them
                across. This is when. It goes away as each surface moves.

                Deliberately not `:move`: the cursor has its own atom, because
                a projection per keystroke is a window render per keystroke."
          :reaction (fn [_ & _]
                      (from-objects/sync!)))

(behavior ::sync-from-tabs
          :triggers #{:tab.updated}
          :desc "State: Keep the state atom current with the tabs"
          :doc "The tab strip is `lt.ui.view/titlebar` over the projection, so
                a tab opening, closing, moving, being renamed or going dirty
                has to reach it. `lt.objs.tabs` raises `:tab.updated` on the
                tabset for every one of those."
          :reaction (fn [_ & _]
                      (from-objects/sync!)))

(behavior ::sync-from-clients
          :triggers #{:connect :close :destroy}
          :desc "State: Keep the state atom current with the connections"
          :doc "The connect panel is `lt.ui.view/connections` and reads the
                projection, so a client arriving or going away has to reach it.
                Here rather than in `lt.objs.sidebar.clients` because the
                projection requires the clients and the clients would then
                require the projection — see the circular dependency that made
                this its own behavior."
          :reaction (fn [_ & _]
                      (from-objects/sync!)))

(behavior ::sync-after-destroy
          :triggers #{:destroy}
          :desc "State: Keep the state atom current when an object goes away"
          :doc "`::sync-from-objects` covers `:close`, and that is not enough.
                `lt.object/destroy!` raises `:destroy` and *then* removes the
                instance from the registry, so a projection taken while that
                trigger is in flight still finds the editor it is about — and
                `lt.state.objects/editors` reads `object/by-tag`.

                So a closed editor stayed in `:editors` until something else
                happened to sync. Found by `lt.objs.control/drift` on the first
                run of the check that compares the projection with the objects
                it is projected from, which is the entire reason that check
                exists.

                A tick later rather than a reordering of `destroy!`: behaviors
                reacting to `:destroy` are entitled to find the object still
                registered, and taking that away to fix a projection would be
                the projection dictating terms to the object model."
          :reaction (fn [_ & _]
                      (util/wait 0 from-objects/sync!)))

(behavior ::sync-from-language-servers
          :triggers #{:lsp.ready :lsp.diagnostics}
          :desc "State: Keep the state atom current with the language servers"
          :doc "The statusbar draws what the language server is doing, from the
                `:lsp` key the projection writes. Nothing here was listening
                for a server becoming ready, so that key kept whatever it held
                when the editor was last focused — which for a file opened
                before its server finished indexing is `:connecting`, pulsing,
                for as long as the window is open.

                Reported as \"it says connected while still saying connecting
                and flashing the light\", and that is exactly what it was: two
                indicators of one fact, one of them live and one of them a
                snapshot from before the handshake. `::on-ready` says the
                sentence the moment it happens; the dot beside it was stale.

                The diagnostic count is the same key and had the same problem —
                it is drawn beside the server's name and only moved when you
                changed tabs."
          :reaction (fn [_ & _]
                      (from-objects/sync!)))

(behavior ::track-cursor
          :triggers #{:move :active}
          :desc "State: Keep the cursor where the statusbar can see it"
          :doc "Its own atom, observed by its own render root. The statusbar is
                a different clock from the window and this is where that is
                said — see doc/rendering.md.

                `:active` as well as `:move`, because switching tabs moves the
                cursor to wherever it was in the other buffer without anything
                having moved it. The bar showing the last file's position is
                what `lt.objs.statusbar/report-cursor-location` used to prevent."
          :reaction (fn [ed & _]
                      (reset! state/cursor (editor/->cursor ed))))

;; There was a `::retire-bands` behavior here, which swept the table of drawn
;; bands when an editor closed. There is no such table any more: on CodeMirror 6
;; the widgets are state the editor holds, and on CodeMirror 5 the bookkeeping
;; lives on the CodeMirror object itself — see `lt.objs.editor.bands`. Either
;; way the bands go when the editor does, and nothing has to be told.

(behavior ::on-close-destroy
          :triggers #{:close}
          :reaction (fn [this]
                      (object/raise this :destroy)))

(defn- roots!
  "Two render roots inside one node, one per clock.

  [[lt.ui/state-node]] is each of them: a node, a view of atoms the object does
  not own, and watches that come off when it is destroyed. Two calls rather
  than one because the statusbar reads the cursor, which moves on every
  keypress — a single root over both atoms would redraw the whole window with
  it.

  The bar down the bottom of the real editor is the same view through the same
  helper, which is the thing worth noticing here: this tab is not a mock of the
  chrome, it is the chrome, drawn twice. See [[lt.objs.statusbar]]."
  [this]
  (let [el (hiccup/element [:div.window-host])]
    (.appendChild el (ui/state-node this [:div]
                                    (fn [] (view/window @state/app))
                                    [state/app]))
    (.appendChild el (ui/state-node this [:div]
                                    (fn [] (view/statusbar (assoc @state/app :cursor @state/cursor)))
                                    ;; Both, because the statusbar counts edits
                                    ;; and runs too — those just arrive on the
                                    ;; window's clock rather than the cursor's.
                                    [state/app state/cursor]))
    el))

(object/object* ::window
                :tags #{:ui.window}
                :behaviors [::on-close-destroy]
                :name "Window as a view"
                :init (fn [this]
                        (from-objects/sync!)
                        (roots! this)))

(cmd/command {:command :ui.window
              :desc "Light Table: Window as a view"
              :exec (fn []
                      (let [w (object/create ::window)]
                        (tabs/add! w)
                        (tabs/active! w)))})
