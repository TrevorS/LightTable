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

  This is not yet the chrome you use. It opens in a tab beside the real one, so
  the two can be compared while the projection in [[lt.state.objects]] is still
  being filled in. Moving it is a matter of moving the root.

  **Light Table: Window as a view** opens it."
  (:require [lt.object :as object]
            [lt.objs.command :as cmd]
            [lt.objs.editor :as editor]
            [lt.objs.tabs :as tabs]
            [lt.state :as state]
            [lt.state.objects :as from-objects]
            [lt.ui.bands :as bands]
            [lt.ui.view :as view]
            [replicant.dom :as r]
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
          :triggers #{:move}
          :desc "State: Keep the cursor where the statusbar can see it"
          :doc "Its own atom, observed by its own render root. The statusbar is
                a different clock from the window and this is where that is
                said — see doc/rendering.md."
          :reaction (fn [ed & _]
                      (reset! state/cursor (editor/->cursor ed))))

(behavior ::retire-bands
          :triggers #{:close :destroy}
          :desc "State: Take this editor's bands off when it closes"
          :doc "A band is a line widget in an editor, and an editor that has
                gone takes its widgets with it — but not the table that
                remembers them, which would then hand out a node nobody can
                see."
          :reaction (fn [_ & _]
                      (bands/forget-editor!)))

(behavior ::on-close-destroy
          :triggers #{:close}
          :reaction (fn [this]
                      (object/raise this :destroy)))

(defn- roots!
  "Two render roots inside one node, and the watches that drive them.

  Built here rather than through [[lt.ui/node]] because that gives an object
  one root and this needs two. The watches are removed when the object is
  destroyed — `state/app` and `state/cursor` both outlive it."
  [this]
  (let [el (crate/html [:div.window-host])
        chrome (js/document.createElement "div")
        status (js/document.createElement "div")
        draw-chrome! (fn [] (r/render chrome (view/window @state/app)))
        draw-status! (fn [] (r/render status (view/statusbar
                                              (assoc @state/app :cursor @state/cursor))))]
    (.appendChild el chrome)
    (.appendChild el status)
    (draw-chrome!)
    (draw-status!)
    (add-watch state/app [::chrome (object/->id this)] (fn [_ _ _ _] (draw-chrome!)))
    (add-watch state/cursor [::status (object/->id this)] (fn [_ _ _ _] (draw-status!)))
    ;; The statusbar reads runs and edits too, so a change to those has to
    ;; reach it — but through the chrome's clock rather than the cursor's.
    (add-watch state/app [::status-slow (object/->id this)] (fn [_ _ _ _] (draw-status!)))
    (add-watch this [::teardown (object/->id this)]
               (fn [_ _ _ new-value]
                 (when-not new-value
                   (remove-watch state/app [::chrome (object/->id this)])
                   (remove-watch state/cursor [::status (object/->id this)])
                   (remove-watch state/app [::status-slow (object/->id this)]))))
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
