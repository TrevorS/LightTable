(ns lt.objs.version
  "Provide LT version command"
  (:require [lt.object :as object]
            [lt.objs.sidebar.command :as cmd]
            [lt.objs.editor.pool :as pool]
            [lt.objs.editor :as editor]
            [lt.objs.files :as files]
            [lt.objs.tabs :as tabs]
            [lt.objs.deploy :as deploy]
            [lt.ui :as ui])
  (:require-macros [lt.macros :refer [behavior]]))

(defn- check-button []
  (ui/element [:div.button {:on {:click (fn [] (deploy/check-version true))}}
               "Check for updates"]))

(behavior ::on-show-refresh-eds
          :triggers #{:show}
          :reaction (fn [this]
                      (object/raise (:ed @this) :show)
                      ))

(behavior ::destroy-on-close
          :triggers #{:close}
          :reaction (fn [this]
                      (object/destroy! this)))

(object/object* ::version-pane
                :tags #{:version}
                :name "Version"
                :init (fn [this]
                        (let [main (pool/create {:mime "markdown" :content (-> (files/lt-home "/core/changelog.md")
                                                                               (files/open-sync)
                                                                               (:content))})]
                          (object/merge! this {:ed main})
                          [:div#version-info
                           [:div.info
                            [:dl
                             [:dt "Light Table version"] [:dd (:version deploy/version)]
                             [:dt "Binary version"] [:dd (deploy/binary-version)]
                             ;; What this window was actually compiled from,
                             ;; which the release number cannot say: it is the
                             ;; same string for every build between two
                             ;; releases, and the question people have is "is
                             ;; my change in here".
                             [:dt "Build"] [:dd (deploy/build-line (deploy/build-stamp))]
                             [:dt "Plugins directory" [:dd (files/lt-user-dir "plugins")]]
                             ]
                            (check-button)
                            ]
                           (editor/->elem main)
                           ]
                          )))

(defn add []
  (let [v (object/create ::version-pane)]
    (tabs/add! v)
    (tabs/active! v)
    ))

(cmd/command {:command :version
              :desc "App: Light Table version"
              :exec (fn [_]
                       (add))})
