(ns lt.objs.popup
  "The modal, and the only thing in Light Table that can stop a caller dead.

  A popup is what it was created with — a header, a body and a list of buttons
  — plus which button is active. That is the whole of its state, and it is on
  the object, so the view is a function of it and moving the selection is a
  number changing rather than two classes being swapped in the DOM.

  It used to be the other way, and the cost showed up somewhere unexpected:
  `lt.objs.control` exposes open popups over MCP, and had to read the header
  back out of the rendered `h2` because the object did not keep it."
  (:require [lt.object :as object]
            [lt.objs.command :as cmd]
            [lt.objs.context :as ctx]
            [lt.objs.canvas :as canvas]
            [lt.objs.keyboard :as keyboard]
            [lt.ui :as ui]
            [lt.util.dom :as dom])
  (:require-macros [lt.macros :refer [behavior]]))

(def ^{:dynamic true} *no-close* nil)

(behavior ::on-click-destroy
          :triggers #{:click}
          :reaction (fn [this]
                      (object/raise this :close!)))

(behavior ::close!
          :triggers #{:close!}
          :reaction (fn [this]
                      (object/raise this :close)
                      (object/destroy! this)
                      (if-let [others (-> (object/by-tag :popup)
                                          (seq))]
                        (ctx/in! :popup (last others))
                        (ctx/out! :popup)
                        )))

(behavior ::refocus-on-close
          :triggers #{:close}
          :reaction (fn [this]
                      (cmd/exec! :tabs.focus-active)))

(defn remain-open []
  (set! *no-close* true))

(defn- drawn-buttons
  "The buttons in the order they are on screen.

  Reversed, because they are floated right — so the last one declared is the
  leftmost, and `:button` counts along the row you are looking at rather than
  the list a caller wrote."
  [state]
  (vec (reverse (:buttons state))))

(defn- choose!
  "Run what button `b` says, and close unless it asked to stay."
  [this {:keys [action post-action]}]
  (binding [*no-close* nil]
    (when (fn? action)
      (action))
    (when-not *no-close*
      (object/raise this :close!))
    (when (fn? post-action)
      (post-action))))

(behavior ::change-active-button
          :triggers #{:move-active}
          :reaction (fn [this dir]
                      ;; The buttons used to be counted in the DOM and the
                      ;; class moved between two of them. They are a list on
                      ;; the object, so this is arithmetic and the view draws
                      ;; the consequence.
                      (let [n (count (:buttons @this))]
                        (when (pos? n)
                          (object/merge! this {:button (mod (+ (:button @this) dir) n)})))))

(behavior ::exec-active
          :triggers #{:exec-active}
          :reaction (fn [this]
                      (when-let [b (nth (drawn-buttons @this) (:button @this) nil)]
                        (choose! this b))))

(defn- popup-ui
  "Four nested divs, which is not decoration: `deploy/core/css/structure.css`
  centres a modal by making them a table, a cell, an inline-block and the card.
  The outermost is the view's, because [[lt.ui/node]] hands the `.popup` itself
  to the object and renders inside it."
  [this]
  (let [{:keys [header body button] :as state} @this]
    [:div
     [:div
      ;; Clicking the card is not clicking the backdrop. Without this every
      ;; click inside a modal would dismiss it, because the backdrop's own
      ;; handler is on an ancestor.
      [:div {:on {:click (fn [e] (dom/prevent e) (dom/stop-propagation e))}}
       [:h2 header]
       [:p body]
       [:ul.buttons
        (for [[i b] (map-indexed vector (drawn-buttons state))]
          [:li.button {:replicant/key i
                       :class (when (= i button) "active")
                       :on {:click (fn [] (choose! this b))}}
           (:label b)])]]]]))

(object/object* ::popup
                :tags #{:popup}
                :button 0
                :init (fn [this opts]
                        ;; Before the node, because [[lt.ui/node]] draws once
                        ;; on the way out and the view reads this.
                        (object/merge! this (assoc opts :button 0))
                        (doto (ui/node this [:div.popup {:tabindex -1}] popup-ui)
                          ;; On the root rather than in the view: the root is
                          ;; the object's and Replicant owns only what is
                          ;; inside it. This is the click that lands on the
                          ;; backdrop, and it is what dismisses the modal.
                          (dom/on :click (fn [] (object/raise this :click))))))

(cmd/command {:command :popup.exec-active
              :desc "Popup: execute active option"
              :hidden true
              :exec (fn []
                      (when-let [p (ctx/->obj :popup)]
                        (object/raise p :exec-active)))})

(cmd/command {:command :popup.move-active
              :desc "Popup: move selection"
              :hidden true
              :exec (fn [dir]
                      (when-let [p (ctx/->obj :popup)]
                        (object/raise p :move-active dir)))})

(cmd/command {:command :popup.escape
              :desc "Popup: escape"
              :hidden true
              :exec (fn []
                      (when-let [p (ctx/->obj :popup)]
                        (object/raise p :click)))})

(defn popup! [options]
  (let [p (object/create ::popup options)]
    (object/raise p :move-active 0)
    (dom/append (dom/$ :body) (object/->content p))
    (.focus (object/->content p))
    (ctx/in! :popup p)
    p))

(def cancel-button {:label "cancel" :action :cancel})
