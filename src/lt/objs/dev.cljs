(ns lt.objs.dev
  "Provide commands for LT developer"
  (:require [lt.object :as object]
            [lt.util.js]
            [lt.objs.cache :as cache]
            [lt.objs.notifos :as notifos]
            [lt.objs.command :as cmd]
            [lt.util.bridge :as bridge]))

(cmd/command {:command :dev-inspector
              :desc "Dev: Open Developer Tools"
              :exec (fn []
                      (.toggleDevTools bridge/window))})

(cmd/command {:command :toggle-edge
              :desc "Toggle edge"
              :hidden true
              :exec (fn []
                      (if (cache/fetch :edge)
                        (do
                          (cache/store! :edge false)
                          (notifos/set-msg! "Tracking normal"))
                        (do
                          (cache/store! :edge true)
                          (notifos/set-msg! "Tracking edge"))))})
