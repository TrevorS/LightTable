(ns lt.objs.intro
  "Provide intro panel for introducing LT to new users"
  (:require [lt.object :as object]
            [lt.objs.style :as style]
            [lt.objs.repo :as repo]
            [lt.objs.cli :as cli]
            [lt.objs.command :as cmd]
            [lt.objs.app :as app]
            [lt.objs.tabs :as tabs]
            [lt.util.dom :as dom]
            [singultus.core]
            [singultus.binding :refer [bound]])
  (:require-macros [lt.macros :refer [behavior defui]]))

(behavior ::on-close-destroy
                  :triggers #{:close}
                  :reaction (fn [this]
                              (object/raise this :destroy)))

(def ->lt-image (constantly "img/lighttabletextdark.png"))

(defui docs []
  [:button "Light Table's online docs"]
  :click (fn []
           (cmd/exec! :show-docs)))

(defui reports []
  [:button "GitHub"]
  :click (fn []
           (cmd/exec! :add-browser-tab (repo/at "issues"))))


(defui changelog []
  [:button "changelog"]
  :click (fn []
           (cmd/exec! :version)))

(object/object* ::intro
                :tags #{:intro}
                :behaviors [::on-close-destroy]
                :name "Welcome"
                :init (fn [this]
                        [:div#intro
                         [:h1
                          [:img {:height 40 :src (bound style/styles ->lt-image)}]]
                         ;; What this said until now was the release notes for
                         ;; 0.8, announcing Python eval as a new feature. It had
                         ;; been the first thing every user saw for a decade.
                         [:p "Light Table connects you to your creation with instant feedback: evaluate
                          code as you write it, see the results beside it, and reshape the editor from
                          inside itself."]
                         [:p "This build is a modernized Light Table — a current runtime, an isolated
                          window, and a named list of capabilities where ambient system access used to
                          be. What changed and why is in the " (changelog) "."]
                         [:p "New here? " (docs) " is the place to start. Something broken? It probably
                          is — say so on " (reports) "."]
                         ]))

(behavior ::show-intro
          :triggers #{:post-init}
          :type :user
          :exclusive [::show-new-file]
          :desc "App: Open the welcome screen when Light Table starts"
          :reaction (fn [this]
                      (when-not (cli/args)
                        (let [intro (object/create ::intro)]
                          (dom/focus (dom/$ :body))
                          (tabs/add! intro)
                          (tabs/active! intro)))))

(behavior ::show-new-file
          :triggers #{:post-init}
          :type :user
          :exclusive [::show-intro]
          :desc "App: Open a new file when Light Table starts"
          :reaction (fn [this]
                      (when-not (cli/args)
                        (cmd/exec! :new-file))))
