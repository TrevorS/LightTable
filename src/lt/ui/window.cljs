(ns lt.ui.window
  "The window, rendered from the state atom.

  One render root, one function, one value. `(view/window @state/app)` is the
  whole of it, and everything on screen here changed because the state changed —
  there is no other way for it to have changed.

  This is not yet the chrome you use. It opens in a tab beside the real one, so
  the two can be compared while the projection in [[lt.state.objects]] is still
  being filled in. Moving it is a matter of moving the root, which is the point
  of having exactly one.

  **Light Table: Window as a view** opens it."
  (:require [lt.object :as object]
            [lt.objs.command :as cmd]
            [lt.objs.tabs :as tabs]
            [lt.state :as state]
            [lt.state.objects :as from-objects]
            [lt.ui :as ui]
            [lt.ui.view :as view])
  (:require-macros [lt.macros :refer [behavior]]))

(behavior ::sync-from-objects
          :triggers #{:move :active :dirty :clean :close :focus}
          :desc "State: Keep the state atom current with this editor"
          :doc "The views are functions of one value and the editor's facts are
                spread across a hundred objects, so something has to carry them
                across. This is when. It goes away as each surface moves."
          :reaction (fn [_ & _]
                      (from-objects/sync!)))

(behavior ::on-close-destroy
          :triggers #{:close}
          :reaction (fn [this]
                      (object/raise this :destroy)))

(object/object* ::window
                :tags #{:ui.window}
                :behaviors [::on-close-destroy]
                :name "Window as a view"
                :init (fn [this]
                        ;; Two watches: the object's own, which is what
                        ;; `ui/node` renders on, and the state atom's, which is
                        ;; what actually changes. `ui/watch` ends the second
                        ;; when this object is destroyed — the state outlives
                        ;; it, so that one has to be taken off again.
                        (ui/watch this state/app)
                        (from-objects/sync!)
                        (ui/node this [:div.window-host] (fn [_] (view/window @state/app)))))

(cmd/command {:command :ui.window
              :desc "Light Table: Window as a view"
              :exec (fn []
                      (let [w (object/create ::window)]
                        (tabs/add! w)
                        (tabs/active! w)))})
