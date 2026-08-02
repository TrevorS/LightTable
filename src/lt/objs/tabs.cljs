(ns lt.objs.tabs
  "Manage tabsets and tabs"
  (:require [lt.actions :as actions]
            [lt.object :refer [object*] :as object]
            [lt.objs.canvas :as canvas]
            [lt.objs.command :as cmd]
            [lt.objs.animations :as anim]
            [lt.objs.context :as ctx]
            [lt.state :as state]
            [lt.ui :as ui]
            [lt.ui.host :as host]
            [lt.ui.view :as view]
            [lt.util.dom :refer [append] :as dom]
            [lt.util.style :refer [->px]]
            [lt.util.js]
            [singultus.core :as crate]
            [singultus.binding :refer [bound subatom]])
  (:require-macros [lt.macros :refer [behavior]]))



(def multi-def (object* ::multi-editor2
                        :tags #{:tabs}
                        :tabsets []
                        :left 0
                        :right 0
                        :bottom 0
                        :init (fn [this]
                                (let [tabsets (crate/html [:div.tabsets {:style {:bottom (bound (subatom this :tabset-bottom) ->px)}}])]
                                  (object/merge! this {:tabsets-elem tabsets})
                                  (ctx/in! :tabs this)
                                  [:div#multi {:style {:left (bound (subatom this :left) ->px)
                                                       :right (bound (subatom this :right) ->px)
                                                       :bottom (bound (subatom this :bottom) ->px)}}
                                   tabsets]
                                  ))))

(def multi (object/create multi-def))

(defn ensure-visible [idx tabset]
  (when-let [cur (aget (dom/$$ ".titlebar .tab" (object/->content tabset)) idx)]
    (let [left (.-offsetLeft cur)
          width (.-clientWidth cur)
          right (+ left width)
          gp (dom/parent (dom/parent cur))
          pwidth (.-clientWidth gp)
          pleft (.-scrollLeft gp)
          pright (+ pleft pwidth)
          inside (and (>= left pleft)
                      (<= right pright))]
      (when-not inside
        (if (> pleft left)
          (set! (.-scrollLeft gp) (- left 50))
          (set! (.-scrollLeft gp) (+ (- right pwidth) 50)))
        ))))

(defn ->index [obj]
  (when (and obj @obj (::tabset @obj))
    (first (first (filter #(= obj (second %)) (map-indexed vector (:objs @(::tabset @obj))))))))

(defn active! [obj]
  (when (and obj
             (::tabset @obj))
    (object/merge! (::tabset @obj) {:active-obj obj})
    (object/raise obj :show)
    (object/raise (::tabset @obj) :tab.updated)
    (ensure-visible (->index obj) (::tabset @obj))))

(defn update-tab-order
  "Put `objs` in `ts` in that order, keeping whichever tab was active active.

  Took a list of DOM nodes and read a `:pos` attribute off each. The order of
  the tabs is the order of `:objs`, and reading it back out of the document was
  only ever necessary because the document is what a sortable library moved."
  [ts objs]
  (let [prev-active (:active-obj @ts)]
    (object/merge! ts {:objs (vec objs) :active-obj nil})
    (active! prev-active)
    (object/raise ts :tab.updated)))

(defn ->name [e]
  (or
   (get-in @e [:info :name])
   (:name @e)
   "unknown"))

(defn ->path [e]
  (or
   (get-in @e [:info :path])
   (:path @e)
   ""))

(defn- strip!
  "The tab strip of `ts`, as a Replicant root.

  This was a `<ul>` of `::tab-label` objects, rebuilt from nothing every time
  anything about the tabset changed — every label destroyed and recreated, the
  drag-and-drop wiring reattached, on a dirty flag. `lt.ui.view/titlebar` draws
  it from the projection instead, and patches.

  The node belongs to the tabset, which is why this is a node spliced into the
  singultus hiccup below rather than the tabset itself becoming a view: the
  content area beside it hosts the DOM of every tab object, and that stays."
  [ts]
  (let [el (ui/state-node ts [:div.titlebar]
                          #(view/titlebar @state/app (object/->id ts))
                          [state/app])]
    ;; Two listeners that are not handlers. Allowing a drop is a property of
    ;; the element rather than something that happens — the browser wants
    ;; `preventDefault` on every `dragover` to hear it — and a drop on the
    ;; empty end of the strip is not on any tab, so there is nothing for a tab
    ;; to have carried.
    (dom/on el :dragover (fn [e] (dom/prevent e)))
    (dom/on el :drop (fn [e]
                       (when (= el (.-target e))
                         (dom/prevent e)
                         (actions/dispatch! [[:tab/drop (object/->id ts) nil]]))))
    (dom/on el :contextmenu (fn [_] (object/raise ts :menu!)))
    el))

(defn- tabbed-item
  "One tab's own DOM, in the slot the tabset keeps for it.

  Every tab is drawn and all but the active one are hidden, which is how a tab
  keeps its scroll position and its editor's state while another is in front.
  Hosted rather than described: what is inside belongs to the tab object — an
  editor, the plugin manager, a browser — and Replicant is told nothing about
  it. See [[lt.ui.host]].

  `#multi .content > *` is a direct-child rule, so the host element *is* the
  `.content` div rather than something wrapped in one."
  [active item]
  [::host/host {:replicant/key (object/->id item)
                :class "content"
                :style {:visibility (if (= item active) "visible" "hidden")}
                :content (object/->content item)}])

(defn- vertical-grip
  "The handle you drag to resize a tabset. See [[lt.objs.bottombar]].

  Plain hiccup rather than an `lt.ui/element`, unlike the other two: this one
  is inside a view, so Replicant draws it along with everything else and there
  is no node to make once and keep.

  It also has to tell the browser it is a move: without `setData` a drag never
  starts in Chromium, and without `dropEffect` the cursor says copy."
  [this]
  [:div.vertical-grip
   {:draggable "true"
    :on {:dragstart (fn [^js e]
                      (set! (.-dataTransfer.dropEffect e) "move")
                      (.dataTransfer.setData e "text/plain" nil)
                      (object/raise this :start-drag e))
         :dragend (fn [e] (object/raise this :end-drag e))
         :drag (fn [^js e]
                 (set! (.-dataTransfer.dropEffect e) "move")
                 (object/raise this :width! e))}}])

(defn ->perc [x]
  (if x
    (str x "%")
    "0"))

(defn floored [x]
  (cond
   (< x 0) 0
   (> x 100) 100
   :else x))

(defn to-perc [width x]
  (* (/ x width) 100))

(defn next-tabset [t]
  (let [ts (@multi :tabsets)]
    (second (drop-while #(not= t %) ts))
    ))

(defn prev-tabset [t]
  (let [ts (@multi :tabsets)]
    (-> (take-while #(not= t %) ts)
        (last))))

(defn previous-tabset-width [cur]
  (let [ts (@multi :tabsets)]
    (reduce + 0 (map (comp :width deref) (take-while #(not= cur %) ts)))
    ))

(defn add-tabset [ts]
  (object/update! multi [:tabsets] conj ts)
  (dom/append (:tabsets-elem @multi) (object/->content ts))
  )

(defn spawn-tabset []
  (let [ts (object/create ::tabset)
        width (- 100 (reduce + (map (comp :width deref) (@multi :tabsets))))]
    (object/merge! ts {:width width})
    (add-tabset ts)
    ts))

(defn equalize-tabset-widths []
  (let [tss (:tabsets @multi)
        width (/ 100.0 (count tss))]
    (doseq [ts tss]
      (object/merge! ts {:width width}))))


(defn temp-width [ts w]
  (dom/css (object/->content ts) {:width (->perc w)
                                  :border-width (if (= 0 w)
                                                  0
                                                  "")}))


(defn activate-tabset [ts]
  (when-not (= (ctx/->obj :tabset) ts)
    (when-let [old (ctx/->obj :tabset)]
      (dom/remove-class (object/->content old) :active))
    (ctx/in! :tabset ts)
    (dom/add-class (object/->content ts) :active)
    ;; Which tabset is active is drawn by every strip — the inactive ones dim —
    ;; so all of them have to hear about it, and the state is where they look.
    (object/raise ts :tab.updated)
    true))



(defn- tabset-ui [this]
  (let [{:keys [objs active-obj strip]} @this]
    (list
     ;; The strip is made once in `:init` and hosted, because it is a render
     ;; root of its own — `ui/state-node` watching `lt.state/app`. Building it
     ;; here would make a new one every time a tab opened.
     [::host/host {:class "list" :content strip}]
     [:div.items
      (for [o objs]
        (tabbed-item active-obj o))]
     (vertical-grip this))))

(object/object* ::tabset
                :objs []
                :active-obj nil
                :count 0
                :tags #{:tabset}
                :width 100
                :init (fn [this]
                        (object/merge! this {:strip (strip! this)})
                        ;; `:class` is deliberately not among the attrs:
                        ;; `activate-tabset` adds and removes `active` on this
                        ;; element directly, and a view writing the class would
                        ;; take it off again on the next draw. The width is the
                        ;; view's; the active flag is the context's.
                        (doto (ui/node this [:div.tabset] tabset-ui
                                       (fn [obj] {:style {:width (->perc (:width @obj))}}))
                          (dom/on :click (fn [] (object/raise this :active))))))

(defn ->tabsets [tabs]
  (for [k tabs]
    (object/->content k)))

(def tabset (object/create ::tabset))

(defn add!
  ([obj] (add! obj nil))
  ([obj ts]
   (when-let [cur-tabset (or ts (ctx/->obj :tabset))]
     (object/add-tags obj [:tabset.tab])
     (object/update! cur-tabset [:objs] conj obj)
     (object/merge! obj {::tabset cur-tabset})
     (add-watch (subatom obj [:dirty]) :tabs (fn [_ _ _ cur]
                                               (object/raise cur-tabset :tab.updated)
                                               ))
     (object/raise cur-tabset :tab.updated)
     obj)))

(defn rem-tabset
  ([ts] (rem-tabset ts false))
  ([ts prev?]
   (let [to-ts (if prev?
                 (or (prev-tabset ts) (next-tabset ts))
                 (or (next-tabset ts) (prev-tabset ts)))]
     (when to-ts
       (object/merge! to-ts {:width (floored (+ (:width @to-ts) (:width @ts)))})
       (dom/remove (object/->content ts))
       (doseq [t (:objs @ts)]
         (add! t to-ts))
       (object/update! multi [:tabsets] #(vec (remove #{ts} %)))
       (object/destroy! ts)
       (equalize-tabset-widths)
       (object/raise to-ts :active)))))

(defn rem! [obj]
  (when (and obj @obj (::tabset @obj))
    (let [cur-tabset (::tabset @obj)
          idx (->index obj)
          active (:active-obj @cur-tabset)
          aidx (->index active)]
      (remove-watch obj :tabs)
      (object/merge! obj {::tabset nil})
      (object/merge! cur-tabset {:objs (vec (remove #(= obj %) (@cur-tabset :objs)))})
      (if (= obj active)
        (object/raise cur-tabset :tab idx)
        (when (not= aidx (->index active))
          (object/merge! cur-tabset {:active-obj nil})
          (active! active)))
      (object/raise cur-tabset :tab.updated))))

(defn refresh! [obj]
  (when-let [ts (::tabset @obj)]
    (object/raise ts :tab.updated)))

(defn in-tab? [obj]
  (@obj ::tabset))

(defn add-or-focus! [obj]
  (if (in-tab? obj)
    (active! obj)
    (do
      (add! obj)
      (active! obj))))

(defn num-tabs []
  (reduce (fn [res cur]
            (+ res (count (:objs @cur))))
          0
          (:tabsets @multi)))

(defn active-tab []
  (when-let [cur-tabset (ctx/->obj :tabset)]
    (:active-obj @cur-tabset)))


(defn move-tab-to-tabset [obj ts]
  (rem! obj)
  (add! obj ts)
  (active! obj)
  (object/raise obj :move))

(defn- tabset-by-id [id]
  (first (filter #(= id (object/->id %)) (:tabsets @multi))))

(defn- move-within
  "`objs` with the item at `from` put back at `to`."
  [objs from to]
  (let [obj (nth objs from)
        without (vec (concat (subvec objs 0 from) (subvec objs (inc from))))
        to (min (max (or to (count without)) 0) (count without))]
    (vec (concat (subvec without 0 to) [obj] (subvec without to)))))

(defn reorder!
  "Move the tab at `from-i` in `from-ts` to `to-i` in `to-ts`.

  What dragging a tab means, as a function of four numbers. It was a DOM
  element handed over by a sortable library, read for an `obj-id` attribute and
  an index among its siblings — so the truth about tab order lived in the
  document and was copied back into the object afterwards. `nil` for `to-i` is
  the end of the strip, which is what dropping past the last tab means."
  [from-ts from-i to-ts to-i]
  (when-let [from (tabset-by-id from-ts)]
    (when-let [to (tabset-by-id to-ts)]
      (when-let [obj (get (:objs @from) from-i)]
        (if (= from to)
          (update-tab-order from (move-within (vec (:objs @from)) from-i to-i))
          (do
            (rem! obj)
            (add! obj to)
            (update-tab-order to (move-within (vec (:objs @to))
                                              (dec (count (:objs @to)))
                                              to-i))))
        (active! obj)
        (object/raise obj :move)
        (object/raise from :tab.updated)
        (object/raise to :tab.updated)))))

;;*********************************************************
;; What the strip emits
;;*********************************************************

;; Four effects and no state of their own. A tab is a window onto an object
;; that knows how to close itself and what belongs in its menu, so all of these
;; end up raising something — see [[lt.actions]] for the actions that ask.

(defn- tab-at [ts-id i]
  (when-let [ts (tabset-by-id ts-id)]
    (get (:objs @ts) i)))

(actions/register-effect! :tabs/activate
                          (fn [ts i]
                            (when-let [obj (tab-at ts i)]
                              (active! obj))))

(actions/register-effect! :tabs/close
                          (fn [ts i]
                            (when-let [obj (tab-at ts i)]
                              (object/raise obj :close))))

(actions/register-effect! :tabs/reorder reorder!)

(actions/register-effect! :tabs/menu
                          (fn [ts i]
                            (when-let [obj (tab-at ts i)]
                              ;; Raised on the tab object rather than on a
                              ;; label object that no longer exists, so what a
                              ;; tab offers is what the thing in it offers.
                              (object/raise obj :menu!))))

;;*********************************************************
;; Behaviors
;;*********************************************************

(behavior ::on-destroy-remove
          :triggers #{:destroy :closed}
          :reaction (fn [this]
                      (rem! this)
                      ))

(behavior ::active-tab-num
          :triggers #{:tab}
          :reaction (fn [this num]
                      (let [objs (@this :objs)]
                        (if (< num (count objs))
                          (active! (get objs num))
                          (active! (get objs (dec (count objs))))))
                      ))

(behavior ::prev-tab
          :triggers #{:tab.prev}
          :throttle 100
          :reaction (fn [this]
                      (let [objs (@this :objs)
                            idx (->index (:active-obj @this))]
                        (if (> idx 0)
                          (active! (get objs (dec idx)))
                          (active! (get objs (dec (count objs))))))
                      ))

(behavior ::next-tab
          :triggers #{:tab.next}
          :throttle 100
          :reaction (fn [this]
                      (let [objs (@this :objs)
                            idx (inc (->index (:active-obj @this)))]
                        (if (< idx (count objs))
                          (active! (get objs idx))
                          (active! (get objs 0))))
                      ))

(behavior ::tab-close
          :triggers #{:tab.close}
          :reaction (fn [this]
                      (try
                        (let [orig (:active-obj @this)]
                          (object/raise orig :close))
                        (catch :default e
                          (js/lt.objs.console.error e)))))

(behavior ::on-destroy-objs
          :triggers #{:destroy}
          :reaction (fn [this]
                      (doseq [e (:objs @this)]
                        (object/destroy! e))
                      ))

(behavior ::no-anim-on-drag
          :triggers #{:start-drag}
          :reaction (fn [this]
                      (anim/off)))

(behavior ::reanim-on-drop
          :triggers #{:end-drag}
          :reaction (fn [this]
                      (anim/on)))

(behavior ::set-dragging
          :triggers #{:start-drag}
          :reaction (fn [this]
                      (dom/add-class (dom/$ :body) :dragging)
                      ))

(behavior ::unset-dragging
          :triggers #{:end-drag}
          :reaction (fn [this]
                      (dom/remove-class (dom/$ :body) :dragging)
                      ))

(behavior ::set-width-final!
          :triggers #{:end-drag}
          :reaction (fn [this e]
                      (when-let [ts (next-tabset this)]
                        (let [width (dom/width (object/->content multi))
                              left (:left @multi)
                              cx (.-clientX e)
                              new-loc (- (+ width left) cx)
                              new-perc (floored (int (- 100 (previous-tabset-width this) (to-perc width new-loc))))
                              prev-width (:width @this)
                              new-perc (if (>= new-perc (+ (:width @ts) prev-width))
                                         (+ (:width @ts) prev-width)
                                         new-perc)
                              next-width (floored
                                          (if-not ts
                                            1
                                            (+ (:width @ts) (- prev-width new-perc))))]
                          (cond
                           (= new-perc 0) (rem-tabset this)
                           (= next-width 0) (rem-tabset ts :prev)
                           :else
                           (when-not (= cx 0)
                             (if (< new-perc 0)
                               (object/merge! this {:width 100})
                               (when (and (not= cx 0)
                                          ts
                                          (>= new-perc 0)
                                          (>= next-width 0))
                                 (object/merge! this {:width new-perc})
                                 (if ts
                                   (object/merge! ts {:width next-width})
                                   (spawn-tabset)
                                   )))))))))

(behavior ::width!
          :triggers #{:width!}
          :reaction (fn [this e]
                      (let [width (dom/width (object/->content multi))
                            left (:left @multi)
                            cx (.-clientX e)
                            new-loc (- (+ width left) cx)
                            new-perc (floored (int (- 100 (previous-tabset-width this) (to-perc width new-loc))))
                            prev-width (:width @this)
                            ts (next-tabset this)
                            new-perc (if (and ts
                                              (>= new-perc (+ (:width @ts) prev-width)))
                                       (+ (:width @ts) prev-width)
                                       new-perc)
                            next-width (floored
                                        (if-not ts
                                          1
                                          (+ (:width @ts) (- prev-width new-perc))))]
                        (when-not (= cx 0)
                          (if (< new-perc 0)
                            (temp-width this 100)
                            (when (and (not= cx 0)
                                       ts
                                       (>= new-perc 0)
                                       (>= next-width 0))
                              (temp-width this new-perc)
                              (if ts
                                (temp-width ts next-width)
                                (spawn-tabset))))))
                      ))


(behavior ::tab-active
          :triggers #{:active}
          :reaction (fn [this]
                      (activate-tabset (::tabset @this))))

(behavior ::tab-menu+
          :triggers #{:menu+}
          :desc "Tab: The right-click menu for a tab"
          :reaction (fn [this items]
                      ;; On the tab object now, because the label it used to be
                      ;; on is not an object any more. Which is also the better
                      ;; place: what a tab offers is what the thing in it does.
                      (conj items
                            {:label "Move tab to new tabset"
                             :order 1
                             :click (fn [] (cmd/exec! :tabs.move-new-tabset this))}
                            {:label "Close tab"
                             :order 2
                             :click (fn [] (object/raise this :close))})))

(behavior ::tabset-active
          :triggers #{:active}
          :reaction (fn [this]
                      (when (activate-tabset this)
                        (when-let [active (:active-obj @this)]
                          (object/raise active :focus!)))))

(behavior ::tabset-menu+
          :triggers #{:menu+}
          :reaction (fn [this items]
                      (conj items
                            {:label "New tabset"
                             :order 1
                             :click (fn [] (cmd/exec! :tabset.new))}
                            {:label "Close tabset"
                             :order 2
                             :click (fn [] (rem-tabset this))})))

(behavior ::left!
          :triggers #{:left!}
          :reaction (fn [this v]
                      (object/update! this [:left] + v)))

(behavior ::right!
          :triggers #{:right!}
          :reaction (fn [this v]
                      (object/update! this [:right] + v)))

(behavior ::bottom!
          :triggers #{:bottom!}
          :reaction (fn [this v]
                      (object/update! this [:bottom] + v)))

(behavior ::tabset-bottom!
          :triggers #{:tabset-bottom!}
          :reaction (fn [this v]
                      (object/update! this [:tabset-bottom] + v)))


(behavior ::init
          :triggers #{:init}
          :reaction (fn [this]
                      (add-tabset tabset)
                      (object/raise tabset :active)
                      ))

(behavior ::show-close-button
          :desc "Tab: Show close button on tabs"
          :type :user
          :triggers #{:close-button+}
          :reaction (fn [this]
                      true))


;;*********************************************************
;; Commands
;;*********************************************************

(cmd/command {:command :tabs.move-new-tabset
              :desc "Tab: Move tab to new tabset"
              :exec (fn [tab]
                      (when-let [ts (ctx/->obj :tabset)]
                        (when-let [cur (or tab (@ts :active-obj))]
                          (let [new (cmd/exec! :tabset.new)]
                            (move-tab-to-tabset cur new)))))})

(cmd/command {:command :tabs.move-next-tabset
              :desc "Tab: Move tab to next tabset"
              :exec (fn []
                      (when-let [ts (ctx/->obj :tabset)]
                        (let [cur (@ts :active-obj)
                              next (or (next-tabset ts) (prev-tabset ts))]
                          (when (and cur next (not= next ts))
                            (move-tab-to-tabset cur next)))))})

(cmd/command {:command :tabs.move-prev-tabset
              :desc "Tab: Move tab to previous tabset"
              :exec (fn []
                      (when-let [ts (ctx/->obj :tabset)]
                        (let [cur (@ts :active-obj)
                              next (or (prev-tabset ts) (next-tabset ts))]
                          (when (and cur next (not= next ts))
                            (move-tab-to-tabset cur next)))))})

(cmd/command {:command :tabs.next
              :desc "Tab: Next tab"
              :exec (fn []
                      (object/raise (ctx/->obj :tabset) :tab.next))})

(cmd/command {:command :tabs.prev
              :desc "Tab: Previous tab"
              :exec (fn []
                      (object/raise (ctx/->obj :tabset) :tab.prev))})

(cmd/command {:command :tabs.close
              :desc "Tab: Close current tab"
              :exec (fn []
                      (when (= 0 (num-tabs))
                        (cmd/exec! :window.close))
                      (when-let [ts (ctx/->obj :tabset)]
                        (when (and (:active-obj @ts)
                                   @(:active-obj @ts))
                          (object/raise ts :tab.close)))
                      )})

(cmd/command {:command :tabs.close-all
              :desc "Tabs: Close all tabs"
              :exec (fn []
                      (let [objs (object/by-tag :tabset.tab)]
                        (doseq [obj objs]
                          (object/raise obj :close))))})

(cmd/command {:command :tabs.close-others
              :desc "Tabs: Close tabs except current tab"
              :exec (fn []
                      (let [cur (active-tab)
                            objs (object/by-tag :tabset.tab)]
                        (doseq [obj objs]
                          (if-not (identical? cur obj)
                            (object/raise obj :close)))))})

(cmd/command {:command :tabs.goto
              :hidden true
              :desc "Tab: Goto tab # or :last"
              :exec (fn [x]
                      (let [ts (ctx/->obj :tabset)
                            tab-count (count (:objs @ts))
                            idx (dec tab-count)]
                        (object/raise (ctx/->obj :tabset)
                                      :tab (if (= x :last) idx x))))})

(cmd/command {:command :tabset.next
              :desc "Tabset: Next tabset"
              :exec (fn []
                      (if-let [n (next-tabset (ctx/->obj :tabset))]
                        (object/raise n :active)
                        (if-let [n (get (:tabsets @multi) 0)]
                          (object/raise n :active))))})

(cmd/command {:command :tabset.prev
              :desc "Tabset: Previous tabset"
              :exec (fn []
                      (if-let [n (prev-tabset (ctx/->obj :tabset))]
                        (object/raise n :active)
                        (if-let [n (last (:tabsets @multi))]
                          (object/raise n :active))))})

(cmd/command {:command :tabset.close
              :desc "Tabset: Remove active tabset"
              :exec (fn [ts]
                      (rem-tabset (ctx/->obj :tabset)))})

(cmd/command {:command :tabset.new
              :desc "Tabset: Add a tabset"
              :exec (fn []
                      (let [ts (spawn-tabset)]
                        (equalize-tabset-widths)
                        ts))})

(cmd/command {:command :tabs.focus-active
              :desc "Tab: focus active"
              :hidden true
              :exec (fn []
                      (when-let [active (:active-obj @(ctx/->obj :tabset))]
                        (object/raise active :focus!)))})

(append (object/->content canvas/canvas) (:content @multi))
