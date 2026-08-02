(ns lt.objs.bottombar
  "Provide bottombar object and associated behaviors"
  (:require [lt.object :as object]
            [lt.objs.tabs :as tabs]
            [lt.objs.animations :as anim]
            [lt.objs.canvas :as canvas]
            [lt.util.cljs]
            [lt.ui :as ui]
            [lt.util.style :refer [->px]]
            [singultus.binding :refer [bound subatom]])
  (:require-macros [lt.macros :refer [behavior]]))


(def min-height 30)
(def default-height 130)

(defn- horizontal-grip
  "The handle you drag to resize the bottombar.

  A node rather than a view, and spliced into the singultus hiccup below —
  which is what the `:init` around it stays. The bar's geometry is bound to
  atoms (`#multi`'s insets, its own height) and that is layout the object model
  owns; the grip inside it is three handlers on an empty div, and nothing
  redraws it."
  [this]
  (ui/element [:div.horizontal-grip
               {:draggable "true"
                :on {:dragstart (fn [_] (object/raise this :start-drag))
                     :dragend (fn [_] (object/raise this :end-drag))
                     :drag (fn [e] (object/raise this :height! e))}}]))

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

(defn add-item [item]
  (object/update! bottombar [:items] assoc (:order @item) item))

;;*********************************************************
;; Object
;;*********************************************************

(object/object* ::bottombar
                :tags #{:bottombar}
                :items (sorted-map-by >)
                :height 0
                :max-height default-height
                :init (fn [this]
                        [:div#bottombar {:class (bound this ->active-class)
                                         :style {:left (bound (subatom tabs/multi :left) ->px)
                                                 :right (bound (subatom tabs/multi :right) ->px)
                                                 :height (bound (subatom this :height) ->px)}}
                         (horizontal-grip this)
                         [:div.content
                          (bound (subatom this :active) active-content)]]))

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

