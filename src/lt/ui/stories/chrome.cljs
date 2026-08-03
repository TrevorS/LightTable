(ns lt.ui.stories.chrome
  "What the chrome components are, as data.

  Beside the components rather than inside them: `lt.ui.chrome` is eighteen
  aliases and nothing else, and it stays that way — a component namespace that
  also holds its own documentation is a component namespace you cannot read.

  Required by both [[lt.ui.catalogue]] and [[lt.ui.storybook]], which is the
  point. One description, two surfaces: the catalogue draws it inside the
  editor where the component lives, Storybook draws it in a browser where it
  can be poked at."
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
   ;; In the order a run moves through them, which is why this is a map
   ;; literal read in order rather than a sorted set. `queued` before
   ;; `executing` before `finished` is information; alphabetical is not.
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
