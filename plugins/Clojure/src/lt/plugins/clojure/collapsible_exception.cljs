(ns lt.plugins.clojure.collapsible-exception
  (:require [clojure.string :as string]
            [lt.util.dom :as dom]
            [lt.object :as object]
            [lt.objs.editor :as ed]
            [lt.objs.notifos :as notifos]
            [lt.ui :as ui])
  (:require-macros [lt.macros :refer [behavior]]))

(def ^:private ^:const NOT_FOUND -1)

(defn truncate
  "truncate a string at newline or at 100 characters long"
  [text]
  (when-not (empty? text)
    (if (= NOT_FOUND (.indexOf text "\n"))
      (subs text 0 100); take 100 characters
      (first (string/split-lines text)))))

(defn ->collapse-class [this]
  (str "inline-exception result-mark"
        (when (:open this) " open")))

(defn- collapsible-exception-ui [this]
  (let [{:keys [result summary]} @this]
    ;; A seq rather than a vector: Replicant renders one node or a list of
    ;; them, and a vector of two would read as a tag and its children.
    (list [:span.truncated (str summary " ...")]
          [:span.full result])))

(defn- collapsible-exception-UI
  "The same shape as `lt.objs.eval/->inline-res`, and for the same reason: both
  halves are always drawn and the class on the root decides which you see. So
  the class goes through [[lt.ui/node]]'s `attrs` — Replicant renders inside
  the element the object hands out, never the element itself."
  [this]
  (doto (ui/node this
                 [:span {:style "background: #73404c; color: #ffa6a6;
                                 max-width:initial; max-height:initial"}]
                 collapsible-exception-ui
                 (fn [obj] {:class (->collapse-class @obj)}))
    (dom/on :mousewheel (fn [e] (dom/stop-propagation e)))
    (dom/on :click (fn [e] (dom/prevent e) (object/raise this :click)))
    (dom/on :contextmenu (fn [e] (dom/prevent e) (object/raise this :menu! e)))
    (dom/on :dblclick (fn [e] (dom/prevent e) (object/raise this :double-click)))))

(object/object* ::collapsible-exception
                :triggers #{:click :double-click :clear!}
                :tags #{:inline :collapsible.exception}
                :init
  (fn [this info]
    (when-let [ed (ed/->cm-ed (:ed info))]
      ;; Before the node, because the view is a function of the object and the
      ;; summary and stack have to be on it before the first draw.
      (object/merge! this info)
      (let [content (collapsible-exception-UI this)]
        (object/merge! this
                       {:widget (ed/line-widget (ed/->cm-ed (:ed info)) (:line (:loc info))
                                                content, {:coverGutter false})})
        content))))

(behavior ::expandable-exceptions
          :triggers #{:editor.exception.collapsible}
          :reaction
  (fn [this summary stack loc]
    (let [ed      (:ed @this)
          line    (ed/line-handle ed (:line loc))
          ex-obj  (object/create ::collapsible-exception
                                 {:ed this, :result stack,
                                  :summary summary
                                  :loc loc, :line line})]
      (when-let [prev (get (@this :widgets) [line :inline])]
        (when (:open @prev) (object/merge! ex-obj {:open true}))
        (object/raise prev :clear!))
      (when (:start-line loc)
        (doseq [widget (map #(get (@this :widgets) [(ed/line-handle ed %) :inline])
                             (range (:start-line loc) (:line loc)))
                :when widget]
          (object/raise widget :clear!)))
      (object/update! this [:widgets] assoc [line :inline] ex-obj))))


(behavior ::clj-expandable-exception
          :triggers #{:editor.eval.clj.exception}
          :reaction (fn [obj res passed?]
                      (when-not passed?
                        (notifos/done-working ""))
                      (let [meta (:meta res)
                            loc {:line (dec (:end-line meta)) :ch (:end-column meta 0)
                                 :start-line (dec (:line meta 1))}]
                        (notifos/set-msg! (:result res) {:class "error"})
                        (object/raise obj :editor.exception.collapsible (:result res) (:stack res) loc))))

(behavior ::cljs-expandable-exception
          :triggers #{:editor.eval.cljs.exception}
          :reaction (fn [obj res passed?]
                      (when-not passed?
                        (notifos/done-working ""))
                      (let [meta (:meta res)
                            loc {:line (dec (:end-line meta)) :ch (:end-column meta)
                                 :start-line (dec (:line meta))}
                            msg (or (:stack res) (truncate (:ex res)))
                            stack (cond
                                    (:stack res)                           (:stack res)
                                    (and (:ex res) (.-stack (:ex res)))    (.-stack (:ex res))
                                    (and (:ex res) (:verbatim meta))       (:ex res)
                                    (and (:ex res) (not (:verbatim meta))) (pr-str (:ex res))
                                    (not (nil? msg))                       (or (:stack res) (:ex res)); untruncated stacktrace
                                    :else "Unknown error")]
                        (notifos/set-msg! msg {:class "error"})
                        (object/raise obj :editor.exception.collapsible msg stack loc))))
