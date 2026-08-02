(ns lt.objs.find
  "Find and replace, in the editor you are in.

  Drawn once, with [[lt.ui/element]], and that is the whole of the rendering
  decision: the bar is two text fields and a button, and none of it is a
  function of anything. What changes when you search is in the editor.

  The fields are the browser's — `->val` reads `.value` rather than the object
  mirroring it. A view that put `:value` in the hiccup would be writing
  `.value` back on every render, and Replicant sets it unconditionally, so
  editing anywhere but the end of the query would send the caret to the end.
  `:default-value` is the other half of that story and is not wanted here
  either: the fields start empty."
  (:require [lt.object :as object]
            [lt.objs.context :as ctx]
            [lt.objs.statusbar :as statusbar]
            [lt.util.load :as load]
            [lt.objs.canvas :as canvas]
            [lt.objs.sidebar.command :as cmd]
            [lt.objs.editor.pool :as pool]
            [lt.objs.keyboard :as keyboard]
            [lt.objs.editor :as editor]
            [lt.ui :as ui]
            [lt.util.dom :as dom])
  (:require-macros [lt.macros :refer [behavior]]))

(declare bar)

(defn- input [this]
  [:input.find {:type "text"
                :placeholder "find"
                :on {:input (fn [e] (object/raise this :search! (.. ^js e -target -value)))
                     :focus (fn []
                              (ctx/in! :find-bar this)
                              (object/raise bar :active))
                     :blur (fn []
                             (ctx/out! :find-bar)
                             (object/raise bar :inactive))}}])

(defn- replace-input [this]
  [:input.replace {:type "text"
                   :placeholder "replace"
                   :on {:input (fn [e] (object/raise this :replace.changed (.. ^js e -target -value)))
                        :focus (fn []
                                 (ctx/in! :find-bar.replace this)
                                 (object/raise bar :active))
                        :blur (fn []
                                (ctx/out! :find-bar.replace)
                                (object/raise bar :inactive))}}])

(defn- replace-all-button []
  [:button {:on {:click (fn [] (cmd/exec! :find.replace-all))}} "all"])

(defn ->val [this]
  (dom/val (dom/$ :input.find (object/->content this))))

(defn ->replacement [this]
  (dom/val (dom/$ :input.replace (object/->content this))))

(defn set-val [this v]
  (dom/val (dom/$ :input.find (object/->content this)) v))

(behavior ::show!
          :triggers #{:show!}
          :reaction (fn [this]
                      (object/merge! this {:shown true
                                           :pos (when-let [ed (pool/last-active)]
                                                  (editor/->cursor ed))})))

(behavior ::hide!
          :triggers #{:hide!}
          :reaction (fn [this]
                      (object/merge! this {:shown false})
                      (when-let [ed (pool/last-active)]
                            (editor/focus ed))))

(behavior ::next!
          :triggers #{:next!}
          :reaction (fn [this]
                      (when-let [cur (pool/last-active)]
                        (if (and (:searching? @this)
                                 (= (:searching.for @cur) (->val this)))
                          (editor/find-next! cur (:reverse? @this))
                          (object/raise this :search! (->val this))))))

(behavior ::prev!
          :triggers #{:prev!}
          :reaction (fn [this]
                      (when-let [cur (pool/last-active)]
                        (if (and (:searching? @this)
                                 (= (:searching.for @cur) (->val this)))
                          (editor/find-next! (pool/last-active) (not (:reverse? @this)))
                          (object/raise this :search! (->val this))))))

(behavior ::focus!
          :triggers #{:focus!}
          :reaction (fn [this]
                      (let [input (dom/$ :input (object/->content this))]
                        (dom/focus input)
                        (.select input))))

(behavior ::clear!
          :triggers #{:clear!}
          :reaction (fn [this]
                      (object/merge! this {:searching? false})
                      (when-let [ed (pool/last-active)]
                        (editor/clear-search! ed))))


(behavior ::replace!
          :triggers #{:replace!}
          :reaction (fn [this all?]
                      (when-not (:searching? @this)
                        (object/raise this :search! (->val this)))
                      (editor/replace! (pool/last-active) (->replacement this) (:reverse? @this) (boolean all?))
                      (object/raise this :next!)))

(behavior ::search!
          :triggers #{:search!}
          :debounce 50
          :reaction (fn [this v]
                      (if (empty? v)
                        (object/raise this :clear!)
                        (when-let [e (pool/last-active)]
                          (when-let [pos (:pos @this)]
                            (editor/move-cursor e pos))
                          (object/merge! this {:searching? true})
                          (object/merge! e {:searching.for v})
                          (editor/find! e v (:reverse? @this))))))

(object/object* ::find-bar
                :tags #{:find-bar}
                :height 30
                :order -1
                :searching? false
                :reverse? false
                :shown false
                :pos nil
                :init (fn [this]
                        (ui/element [:div#find-bar
                                     (input this)
                                     (replace-input this)
                                     (replace-all-button)])))

(behavior ::init
          :triggers #{:init}
          :reaction (fn [this]
                      ;; TODO: use addon/search/search.js
                      ;; The search commands arrive with the bundle — see
                      ;; lt.window.modules, which lt.core requires.
                      ))

(def bar (object/create ::find-bar))
(statusbar/add-container bar)

(cmd/command {:command :find.show
              :desc "Find: In current editor"
              :exec (fn [rev?]
                      (object/merge! bar {:reverse? rev?})
                      (object/raise bar :show!)
                      (object/raise bar :focus!))})

(cmd/command {:command :find.fill-selection
              :desc "Find: Fill with selection"
              :exec (fn []
                      (when-let [e (pool/last-active)]
                        (when-let [sel (editor/selection e)]
                          (when-not (empty? sel)
                            (set-val bar sel)))))})

(cmd/command {:command :find.clear
              :desc "Find: Clear the find bar"
              :hidden true
              :exec (fn []
                      (object/raise bar :clear!))})

(cmd/command {:command :find.hide
              :desc "Find: Hide the find bar"
              :exec (fn []
                      (object/raise bar :hide!))})

(cmd/command {:command :find.next
              :desc "Find: Next find result"
              :exec (fn []
                      (object/raise bar :next!))})

(cmd/command {:command :find.prev
              :desc "Find: Previous find result"
              :exec (fn []
                      (object/raise bar :prev!))})

(cmd/command {:command :find.replace
              :desc "Find: Replace current"
              :exec (fn []
                      (object/raise bar :replace!))})

(cmd/command {:command :find.replace-all
              :desc "Find: Replace all occurrences"
              :exec (fn []
                      (object/raise bar :replace! :all))})

(def line-input (cmd/options-input {:placeholder "line number"}))

(behavior ::exec-active!
          :triggers #{:select}
          :reaction (fn [this l]
                      (cmd/exec-active! l)))

(object/add-behavior! line-input ::exec-active!)

(cmd/command {:command :go-to-line
              :desc "Editor: Go to line"
              :options line-input
              :exec (fn [l]
                      (when (or (number? l) (not (empty? l)))
                        (let [cur (pool/last-active)]
                          (editor/move-cursor cur {:ch 0
                                                   :line (dec (if-not (number? l)
                                                                (js/parseInt l)
                                                                l))})
                          (editor/center-cursor cur))))})

(cmd/command {:command :find.toggle
              :desc    "Find: Toggle the find bar"
              :exec    (fn []
                         (if (get @bar :shown)
                           (cmd/exec! :find.hide)
                           (cmd/exec! :find.show)))})
