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
            [lt.state.objects :as from-objects]
            [lt.ui :as ui]
            [lt.ui.view :as view]
            [singultus.core :as crate])
  (:require-macros [lt.macros :refer [behavior]]))

(behavior ::sync-from-objects
          :triggers #{:active :dirty :clean :close :focus}
          :desc "State: Keep the state atom current with this editor"
          :doc "The views are functions of one value and the editor's facts are
                spread across a hundred objects, so something has to carry them
                across. This is when. It goes away as each surface moves.

                Deliberately not `:move`: the cursor has its own atom, because
                a projection per keystroke is a window render per keystroke."
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
  (let [el (crate/html [:div.window-host])]
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
