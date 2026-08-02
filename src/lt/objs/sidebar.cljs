(ns lt.objs.sidebar
  "Provide sidebar (left bar) and rightbar objects"
  (:require [lt.object :as object]
            [lt.objs.tabs :as tabs]
            [lt.objs.command :as cmd]
            [lt.objs.animations :as anim]
            [lt.objs.canvas :as canvas]
            [lt.ui :as ui]
            [lt.ui.host :as host]
            [lt.util.dom :as dom]
            [lt.util.cljs])
  (:require-macros [lt.macros :refer [behavior]]))

(def default-width 200)

(defn- vertical-grip
  "The handle you drag to resize a sidebar.

  Plain hiccup: this is inside a view now, so Replicant draws it. It was an
  `lt.ui/element` while the bar around it was still plain hiccup, and a
  node left in hiccup after the bar became a view is dropped without a word —
  which is what happened, and what the grip test in `renderer.spec.ts` is for."
  [this]
  [:div.vertical-grip
   {:draggable "true"
    :on {:dragstart (fn [_] (object/raise this :start-drag))
         :dragend (fn [_] (object/raise this :end-drag))
         :drag (fn [e] (object/raise this :width! e))}}])

(behavior ::no-anim-on-drag
          :triggers #{:start-drag}
          :reaction (fn [this]
                      (anim/off)))

(behavior ::reanim-on-drop
          :triggers #{:end-drag}
          :reaction (fn [this]
                      (anim/on)))

(behavior ::width!
          :triggers #{:width!}
          :throttle 5
          :reaction (fn [this e]
                      (when-not (= 0 (.-clientX e))
                        (let [width (if (= (:side @this) :left)
                                      (.-clientX e)
                                      (- (dom/width js/document.body) (.-clientX e)))]
                          (object/merge! tabs/multi {(:side @this) width})
                          (object/merge! this {:width width
                                               :max-width width})
                          ))))

(behavior ::pop-transient
          :triggers #{:pop!}
          :reaction (fn [this]
                      (object/raise this :close!)))

(behavior ::open!
          :triggers #{:open!}
          :reaction (fn [this]
                      (object/merge! this {:width (:max-width @this)
                                           :open true})
                      (object/merge! tabs/multi {(:side @this) (+ (:max-width @this) )})
                      (dom/add-class (object/->content (:active @this)) :active)
                      ))

(behavior ::close!
          :triggers #{:close!}
          :reaction (fn [this no-focus]
                      (when (:active @this)
                        (dom/remove-class (object/->content (:active @this)) :active))
                      (object/merge! tabs/multi {(:side @this) 0})
                      (object/merge! this {:width 0
                                           :active false
                                           :open false})
                      (when-not no-focus
                        (cmd/exec! :tabs.focus-active))))


(behavior ::item-toggled
          :triggers #{:toggle}
          :reaction (fn [this item {:keys [force? transient? soft?]}]
                      (if (and (not force?)
                               (= (:active @this) item)
                               (:open @this))
                        (object/raise this :close!)
                        (when (not= (:active @this) item)
                          (when (:active @this)
                            (dom/remove-class (object/->content (:active @this)) :active))
                          (object/merge! this {:active item})
                          (object/raise this :open!)
                          (when-not soft?
                            (object/raise item :show)
                            )
                          ))))

(defn active-content [active]
  (when active
    (object/->content active)))

(defn ->width [width]
  (str (or width 0) "px"))

(defn- panels
  "Every panel registered with this bar, in `:order`.

  All of them, in one element, which is how a sidebar has always worked:
  `#side .content > *` and `#right-bar .content > *` are sized to nothing and
  the one with `active` on it is given the space. So which panel you are
  looking at is a class another behavior writes, not something drawn here —
  see `::open!`.

  `add-item` used to append into this element itself. It puts the panel in
  `:items` and this places it."
  [this]
  [::host/host {:class "content"
                :content (map object/->content (vals (:items @this)))}])

(defn- width-attrs [obj]
  {:style {:width (->width (:width @obj))}})

(object/object* ::sidebar
                :tags #{:sidebar}
                :items {}
                :width 0
                :side :left
                :transients '()
                :max-width default-width
                :init (fn [this]
                        (ui/node this [:div#side]
                                 (fn [t] (list (panels t) (vertical-grip t)))
                                 width-attrs)))

(object/object* ::right-bar
                :items {}
                :tags #{:sidebar}
                :width 0
                :side :right
                :max-width 300
                :init (fn [this]
                        (ui/node this [:div#right-bar]
                                 (fn [t] (list (vertical-grip t) (panels t)))
                                 width-attrs)))

(def sidebar (object/create ::sidebar))
(def rightbar (object/create ::right-bar))

(canvas/add! sidebar)
(canvas/add! rightbar)

(defn add-item [bar item]
  ;; No `dom/append`: `:items` is what the bar draws from.
  (object/update! bar [:items] assoc (:order @item) item))

(cmd/command {:command :close-sidebar
              :desc "Sidebar: close"
              :hidden true
              :exec (fn []
                      (object/raise rightbar :close!))})
