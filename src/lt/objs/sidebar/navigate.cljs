(ns lt.objs.sidebar.navigate
  "Provide sidebar for finding and opening files"
  (:require [lt.object :as object]
            [lt.objs.workspace :as workspace]
            [lt.objs.sidebar.command :as cmd]
            [lt.objs.files :as files]
            [lt.objs.opener :as opener]
            [lt.objs.sidebar :as sidebar]
            [lt.ui :as ui]
            [lt.ui.host :as host]
            [lt.objs.thread :as thread])
  (:require-macros [lt.macros :refer [behavior]]))


(defn file-filters [f]
  (re-seq files/ignore-pattern f))

(def populate-bg (thread/job :workspace-files))

(declare sidebar-navigate)

(behavior ::workspace-files
          :triggers #{:workspace-files}
          :reaction (fn [this files]
                      (object/merge! this {:files (js->clj files :keywordize-keys true)})
                      (object/raise (:filter-list @this) :refresh!)
                      ))

(behavior ::populate-on-ws-update
          :triggers #{:updated :refresh}
          :debounce 150
          :reaction (fn [ws]
                      (populate-bg sidebar-navigate {:lim (dec (:file-limit @sidebar-navigate))
                                                     :pattern (.-source files/ignore-pattern)
                                                     :ws (workspace/serialize @ws)})))

(behavior ::watched.create
          :triggers #{:watched.create}
          :reaction (fn [ws path]
                      (when-not (file-filters (files/basename path))
                        (let [ws-parent (files/parent (first (filter #(= 0 (.indexOf path %)) (:folders @ws))))
                              rel-length (inc (count ws-parent))]
                          (object/update! sidebar-navigate [:files] conj {:full path :rel (subs path rel-length)})
                          (object/raise (:filter-list @sidebar-navigate) :refresh!)))))

(behavior ::watched.delete
          :triggers #{:watched.delete}
          :reaction (fn [ws path]
                      ;;TODO: this is terribly inefficient
                      (object/update! sidebar-navigate [:files] #(remove (fn [x] (= 0 (.indexOf (:full x) path))) %))
                      (object/raise (:filter-list @sidebar-navigate) :refresh!)))

(behavior ::focus!
          :triggers #{:focus!}
          :reaction (fn [this]
                      (object/raise (:filter-list @this) :focus!)
                      ))

(behavior ::focus-on-show
          :triggers #{:show}
          :reaction (fn [this]
                      (object/raise this :focus!)))

(behavior ::open-on-select
          :triggers #{:select}
          :reaction (fn [this cur]
                      (object/raise opener/opener :open! (:full cur))))

(behavior ::escape!
          :triggers #{:escape!}
          :reaction (fn [this]
                      (cmd/exec! :escape-navigate)
                      (cmd/exec! :focus-last-editor)))

(behavior ::pop-transient-on-select
          :triggers #{:selected}
          :reaction (fn [this]
                      (object/raise sidebar/rightbar :close!)))

(behavior ::set-file-limit
          :triggers #{:object.instant}
          :type :user
          :desc "Navigate: set maximum number of indexed files"
          :params [{:label "Number"
                    :example 8000}]
          :reaction (fn [this n]
                      (object/merge! this {:file-limit n})))

(object/object* ::sidebar.navigate
                :tags #{:navigator}
                :label "navigate"
                :order -3
                :selected 0
                :files []
                :file-limit 8000
                :search ""
                :init (fn [this]
                        (let [list (cmd/filter-list {:key :rel
                                                     ;; Hiccup: the leaf in bold
                                                     ;; and the path under it,
                                                     ;; with the match marked.
                                                     ;; It was a string of HTML
                                                     ;; built around a filename.
                                                     :transform (fn [orig _ marked _]
                                                                  (list [:h2 (files/basename orig)]
                                                                        [:p marked]))
                                                     ;; A function rather than a subatom: `->items` takes either, and
                                                     ;; this is the only thing that wanted one.
                                                     :items (fn [] (:files @this))
                                                     :placeholder "file"
                                                     :empty-what "No files to navigate"
                                                     :empty-why "Add a folder to the workspace and every file in it is one keystroke away."})]
                        (object/add-tags list [:navigate.selector])
                        (object/merge! this {:filter-list list})
                        ;; Hosted rather than spliced. This used to be hiccup
                        ;; with the filter list's node dropped into it, which
                        ;; worked while `->dom` went through singultus and does
                        ;; not now: Replicant renders hiccup and leaves a node
                        ;; out without saying so.
                        (ui/node this [:div.navigate]
                                 (fn [t]
                                   [::host/host {:content (object/->content (:filter-list @t))}])))))

(def sidebar-navigate (object/create ::sidebar.navigate))

(sidebar/add-item sidebar/rightbar sidebar-navigate)

(cmd/command {:command :navigate-workspace
              :desc "Navigate: open navigate"
              :exec (fn []
                      (object/raise sidebar/rightbar :toggle sidebar-navigate {:transient? false})
                      )})

(cmd/command {:command :navigate-workspace-transient
              :desc "Navigate: open navigate transient"
              :hidden true
              :exec (fn []
                      (object/raise sidebar/rightbar :toggle sidebar-navigate {:transient? true})
                      )})

(cmd/command {:command :escape-navigate
              :desc "Navigate: exit navigate"
              :hidden true
              :exec (fn []
                      (cmd/exec! :close-sidebar)
                      (cmd/exec! :focus-last-editor))})
