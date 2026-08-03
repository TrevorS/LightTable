(ns lt.objs.search
  "Provide search and replace functionality across files"
  (:require [lt.object :as object]
            [lt.objs.tabs :as tabs]
            [lt.objs.files :as files]
            [lt.objs.command :as cmd]
            [lt.objs.context :as ctx]
            [lt.objs.notifos :as notifos]
            [lt.objs.platform :as platform]
            [lt.objs.thread :as thread]
            [lt.util.dom :as dom]
            [lt.objs.workspace :as workspace2]
            [lt.objs.workspace-edit :as workspace-edit]
            [lt.ui :as ui]
            ;; No alias: required so it loads, not so it can be called. The
            ;; process client registers behaviors and nothing else in the
            ;; bundle reaches it — `lt.core-test` is what says so.
            [lt.objs.proc]
            [lt.util.js]
            [clojure.string :as string]
            [lt.objs.editor :as editor]
            [lt.objs.editor.pool :as pool])
  (:require-macros [lt.macros :refer [behavior extract]]))

(def search! (thread/job :search))

(def result-threshold 500)

(defmulti location identity)

(defmethod location "<workspace>" [_]
  (apply concat ((juxt :folders :files) (workspace2/serialize @workspace2/current-ws))))

(defmethod location :default [loc]
  [loc])

(defn string->loc [loc-str]
  (mapcat (comp location string/trim) (remove empty? (string/split loc-str ","))))

(defn- entry
  "One matching line.

  Hiccup, and never a string of markup: `crate/raw` relied on
  `goog.dom/htmlToDocumentFragment`, which Closure deleted, and interpolating
  matched file content into markup let that content render as HTML. What is
  drawn here came out of somebody's file."
  [file i ^js r]
  [:p.entry {:replicant/key i
             :on {:click (fn []
                           (cmd/exec! :open-path file)
                           (cmd/exec! :go-to-line (.-line r)))}}
   [:span.line (.-line r)]
   [:pre (.-text r)]])

(defn- result-item [^js r]
  (let [file (.-file r)]
    [:li {:replicant/key file}
     [:p.path [:span.file (files/basename file)] "(" (files/parent file) ")"]
     (map-indexed (partial entry file) (array-seq (.-results r)))]))

(defn ->search-info [this]
  (extract (object/->content this)
           [search :.search
            replace :.replace
            loc :.loc]
           {:search (dom/val search)
            :replace (dom/val replace)
            :loc (dom/val loc)}))

(behavior ::on-close
          :triggers #{:close}
          :reaction (fn [this]
                      (tabs/rem! this)))

(behavior ::clear!
          :triggers #{:clear!}
          :reaction (fn [this]
                      ;; No `dom/empty`: the list is drawn from `:results`, so a
                      ;; fresh array is what clears it.
                      (object/merge! this {:timeout nil :results (array) :result-count 0 ::time nil ::filesSearched nil ::rewritten {} :position [0 -1]})))

(behavior ::search!
          :triggers #{:search!}
          :reaction (fn [this search-info]
                      (object/raise this :clear!)
                      (let [info (or search-info (->search-info this))]
                        (when-not (empty? (:search info))
                          (object/merge! this info)
                          (notifos/working "Searching workspace...")
                          (search! this (assoc info
                                          :exclude (.-source files/ignore-pattern)
                                          :paths (string->loc (:loc info))))))))

(behavior ::replace!
          :triggers #{:replace!}
          :reaction (fn [this]
                      (object/raise this :clear!)
                      (let [info (->search-info this)]
                        (when-not (empty? (:search info))
                          (object/merge! this info)
                          (notifos/working "Replacing all in workspace...")
                          (search! this (assoc info
                                          :replacement (:replace info)
                                          :exclude (.-source files/ignore-pattern)
                                          :paths (string->loc (:loc info))))))))

(behavior ::done-searching
          :triggers #{:done-searching}
          :reaction (fn [this info]
                      (object/merge! this {::time (/ (:time info) 1000)
                                           ::filesSearched (:total info)})
                      (if (:replace? info)
                        (let [rewritten (::rewritten @this)
                              res (when (seq rewritten)
                                    (workspace-edit/apply-texts!
                                     (str "Replace \"" (:search @this) "\" in workspace")
                                     rewritten))]
                          (object/merge! this {::rewritten {}})
                          (if (:error res)
                            (notifos/set-msg! (:error res) {:class "error"})
                            (notifos/done-working
                             (str "Replaced " (:result-count @this) " results in "
                                  (:files res 0) " files in " (/ (:time info) 1000) "s.")))
                          ;; The list goes, the count stays — which is what
                          ;; emptying the `ul` did, and the line above has
                          ;; already read the count it reports.
                          (object/merge! this {:results (array)}))
                        (notifos/done-working (str "Found " (:result-count @this) " results searching " (:total info) " files in " (/ (:time info) 1000) "s." )))))

(behavior ::next!
          :triggers #{:next!}
          :reaction (fn [this]
                      (when (> (.-length (:results @this)) 0)
                        (let [all (:results @this)
                              [file result] (:position @this)
                              ^js cur (aget all file)
                              [file result] (if (>= (inc result) (.-results.length cur))
                                              (if (>= (inc file) (.-length all))
                                                [0 0]
                                                [(inc file) 0])
                                              [file (inc result)])
                              neue (aget all file)]
                          (object/merge! this {:position [file result]})
                          (cmd/exec! :open-path (.-file neue))
                          (cmd/exec! :go-to-line (-> (.-results neue)
                                                    (aget result)
                                                    (.-line)))))))

(behavior ::prev!
          :triggers #{:prev!}
          :reaction (fn [this]
                      (when (> (.-length (:results @this)) 0)
                        (let [all (:results @this)
                              [file result] (:position @this)
                              cur (aget all file)
                              [file result] (if (< (dec result) 0)
                                              (if (< (dec file) 0)
                                                [(dec (.-length all)) (let [^js last-file (aget all (dec (.-length all)))]
                                                                        (dec (.-results.length last-file)))]
                                                [(dec file) (let [^js prev-file (aget all (dec file))]
                                                              (dec (.-results.length prev-file)))])
                                              [file (dec result)])
                              neue (aget (:results @this) file)]
                          (object/merge! this {:position [file result]})
                          (cmd/exec! :open-path (.-file neue))
                          (cmd/exec! :go-to-line (-> (.-results neue)
                                                    (aget result)
                                                    (.-line)))))))
(behavior ::on-result
          :triggers #{:result}
          :reaction (fn [this result]
                      (let [total (count (.-results result))
                            ;; Was `(> (+ total ...))`, a single-operand >, which is
                            ;; always true — so results were truncated whether or not the
                            ;; threshold had been reached.
                            result (if (> (+ total (:result-count @this)) result-threshold)
                                     (js-obj "file" (.-file result)
                                             "results" (.slice (.-results result) 0 (- result-threshold (:result-count @this))))
                                     result)]
                        ;; Pushed in place, which does not swap the atom and so
                        ;; does not draw. The `:result-count` update below is
                        ;; what does — every result has one, and that is the
                        ;; only reason this streams.
                        (.push (:results @this) result)
                        ;; A replace sends the rewritten text back instead of
                        ;; writing it. Held until the walk finishes so the
                        ;; whole replace is one action and one undo.
                        (when-let [text (.-text result)]
                          (object/update! this [::rewritten] assoc (.-file result) text))
                        (object/update! this [:result-count] + total))))

(behavior ::focus
          :triggers #{:focus! :show}
          :reaction (fn [this]
                      (.focus (dom/$ :.search (object/->content this)))))

(defn result-count [this]
  (list "Found  " [:span (:result-count this) " results"]
        (when (> (:result-count this) result-threshold)
          (list " (showing " [:span result-threshold] ")"))
        (when (::time this)
          (list " in " (::time this) "s")
          )
        ))

(defn- searcher-ui
  "The whole panel, from the object.

  The fields stay the browser's — `->search-info` reads them back with
  `dom/val`, and `:default-value` seeds the location box by setting the
  attribute rather than the property, so a redraw cannot type over you. See
  [[lt.objs.find]] for the same rule and why it matters.

  A file with no results left is not drawn. `::on-result` slices each result to
  what is still under `result-threshold`, so once the threshold is reached the
  entries it keeps pushing are empty — and an empty one would otherwise be a
  path with nothing under it."
  [this]
  (let [{:keys [results] :as state} @this]
    (list
     [:ul.res
      (for [^js r (array-seq results)
            :when (pos? (.-length (.-results r)))]
        (result-item r))]
     [:div.searcher
      [:p (result-count state)]
      [:input.search {:type "text" :placeholder "Search"
                      :on {:focus (fn []
                                    (ctx/in! :searcher.search this)
                                    ;; Selected so that a search filled in from
                                    ;; the editor's selection can be typed over.
                                    (.select ^js (dom/$ :input.search (object/->content this))))
                           :blur (fn [] (ctx/out! :searcher.search))}}]
      [:div
       [:input.replace {:type "text" :placeholder "Replace"
                        :on {:focus (fn [] (ctx/in! :searcher.replace this))
                             :blur (fn [] (ctx/out! :searcher.replace))}}]
       [:button.replace {:on {:click (fn [] (cmd/exec! :searcher.replace-all))}} "Replace All"]]
      [:input.loc {:type "text" :placeholder "Locations" :default-value "<workspace>"
                   :on {:focus (fn [] (ctx/in! :searcher.location this))
                        :blur (fn [] (ctx/out! :searcher.location))}}]])))

(object/object* ::workspace-search
                :tags #{:searcher}
                :results (array)
                :name "Search results"
                :init (fn [this]
                        (object/add-tags this [(if (platform/win?)
                                                 :searcher.win
                                                 :searcher.unix)])
                        (ui/node this [:div.search-results] searcher-ui)))

(def searcher (object/create ::workspace-search))

(cmd/command {:command :searcher.search
              :desc "Searcher: Execute search"
              :hidden true
              :exec (fn [info]
                      (let [info (or info (->search-info searcher))]
                        (object/raise searcher :search! info)))})

(cmd/command {:command :searcher.show
              :desc "Searcher: Show"
              :exec (fn []
                      (when-let [e (pool/last-active)]
                        (when-let [sel (editor/selection e)]
                          (when-not (string/blank? sel)
                            (let [search (dom/$ :.search (object/->content searcher))]
                              (dom/val search sel)))))
                      (tabs/add-or-focus! searcher))})

(cmd/command {:command :searcher.next
              :desc "Searcher: Next result"
              :exec (fn []
                      (object/raise searcher :next!))})

(cmd/command {:command :searcher.prev
              :desc "Searcher: Prev result"
              :exec (fn []
                      (object/raise searcher :prev!))})

(cmd/command {:command :searcher.replace-all
              :desc "Searcher: Replace all"
              :hidden true
              :exec (fn []
                      (object/raise searcher :replace!))})
