(ns lt.objs.bottombar
  "Provide bottombar object and associated behaviors"
  (:require [lt.object :as object]
            [lt.objs.tabs :as tabs]
            [lt.objs.animations :as anim]
            [lt.objs.canvas :as canvas]
            [lt.util.cljs]
            [lt.ui :as ui]
            [lt.ui.host :as host]
            [lt.util.style :refer [->px]])
  (:require-macros [lt.macros :refer [behavior]]))


(def min-height 30)
(def default-height 130)

(defn- horizontal-grip
  "The handle you drag to resize the bottombar. Plain hiccup — the bar is a
  view, so Replicant draws this along with the rest of it."
  [this]
  [:div.horizontal-grip
   {:draggable "true"
    :on {:dragstart (fn [_] (object/raise this :start-drag))
         :dragend (fn [_] (object/raise this :end-drag))
         :drag (fn [e] (object/raise this :height! e))}}])

(defn active-content [active]
  (when active
    (object/->content active)))

(declare bottombar)

(defn active? [item]
  (= (:active @bottombar) item))

(defn ->active-class [{:keys [active]}]
  (if active
    "open"
    "closed"))

;; `add-item` was here, and `:items` — a `(sorted-map-by >)` on the object that
;; it was the only writer of and **nothing ever read**. The bar draws
;; `(:active @this)` and has always drawn only that, so the map was a registry of
;; things that could be shown, consulted by nobody, kept in step by one caller.
;;
;; Exactly one thing ever registered: the console. doc/hygiene.md's judgement was
;; that deleting the generality is cheaper than solving it, and this half of it
;; cost nothing to delete because it was not doing anything.
;;
;; What is *not* deleted is the `host` seam below. The bar still splices another
;; object's `object/->content` into its own DOM, which is the shape that does not
;; convert to a view — but the reason is the console, which renders itself
;; imperatively because its streaming append is genuinely imperative. That is the
;; next entry in hygiene rather than this one, and making the bar draw the console
;; directly before the console is a value would move the problem rather than
;; close it.

;;*********************************************************
;; Object
;;*********************************************************

(object/object* ::bottombar
                :tags #{:bottombar}
                :height 0
                :max-height default-height
                :init (fn [this]
                        ;; Two atoms: its own height, and `#multi`'s insets,
                        ;; which move when a sidebar is dragged. `ui/watch` is
                        ;; what redraws this when the other one changes.
                        (ui/watch this tabs/multi)
                        (ui/node this [:div#bottombar]
                                 (fn [t]
                                   (list (horizontal-grip t)
                                         [::host/host {:class "content"
                                                       :content (active-content (:active @t))}]))
                                 (fn [obj]
                                   {:class (->active-class @obj)
                                    :style {:left (->px (:left @tabs/multi))
                                            :right (->px (:right @tabs/multi))
                                            :height (->px (:height @obj))}}))))

(def bottombar (object/create ::bottombar))

(canvas/add! bottombar)

;;*********************************************************
;; Behaviors
;;*********************************************************

(behavior ::no-anim-on-drag
          :triggers #{:start-drag}
          :reaction (fn [this]
                      (anim/off)))

(behavior ::reanim-on-drop
          :triggers #{:end-drag}
          :reaction (fn [this]
                      (anim/on)))

(behavior ::height!
          :triggers #{:height!}
          :throttle 16
          :reaction (fn [this e]
                      (when-not (= 0 (.-clientY e))
                        (let [win-height (.-innerHeight js/window)
                              height (max (- win-height (.-clientY e)) min-height)]
                          (object/raise tabs/multi :bottom! (- height (:height @this)))
                          (object/merge! this {:height height
                                               :max-height height})))
                      ))

(behavior ::show-item
          :triggers #{:show!}
          :reaction (fn [this item]
                      (when (not= item (:active @this))
                        (object/merge! this {:active item
                                             :height (:max-height @this)})
                        (object/raise tabs/multi :bottom! (:max-height @this)))))

(behavior ::hide-item
          :triggers #{:hide!}
          :reaction (fn [this item force?]
                      (when (or (= item (:active @this)) force?)
                          (object/raise tabs/multi :bottom! (- (:max-height @this)))
                          (object/merge! this {:active nil
                                               :height 0}))))

(behavior ::item-toggled
          :triggers #{:toggle}
          :reaction (fn [this item force?]
                      (if (or (not= item (:active @this))
                              force?)
                        (object/raise this :show! item)
                        (object/raise this :hide! item))))

