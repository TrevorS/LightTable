(ns lt.ui.chrome
  "The atoms and the chrome: fifteen aliases that carry no state.

  Markup with no data access, so they expand at render time and cost nothing at
  the call site. Together with [[lt.ui.row]] and [[lt.ui.band]] this is the
  whole kit, and only the views above them ever read state.

  Every colour here is a class, resolved by `deploy/core/css/kit.css`. A
  component that named a colour could not be reskinned, which is the point of
  the token sheet."
  (:require [clojure.string :as string]
            [replicant.alias :refer-macros [defalias]]))

;; ---------------------------------------------------------------------------
;; Atoms
;; ---------------------------------------------------------------------------

;; One indicator for all seven execution states plus connection health.
;; Hollow means nothing has run yet.
(defalias status-dot [{:keys [status hollow pulse]}]
  [:span.dot {:class [(str "dot--" (name (or status :idle)))
                      (when hollow "dot--hollow")
                      (when pulse "dot--pulse")]}])

(defalias status [{:keys [status hollow pulse]} body]
  [:span.status
   [::status-dot {:status status :hollow hollow :pulse pulse}]
   body])

;; A count that belongs to the thing beside it. Never a notification.
;; mauve = proposed · sky = waiting on you · neutral = just a number
(defalias count-pill [{:keys [count tone]}]
  [:span.pill {:class (when (and tone (not= tone :neutral)) (str "pill--" (name tone)))}
   count])

;; A binding, never a button. Symbols only, never spelled out.
(defalias kbd [{:keys [keys]}]
  [:span.kbd keys])

;; A scoped fact: what a run will carry, or which frames a trace shows.
(defalias chip [{:keys [selected tone]} body]
  [:span.chip {:class [(when selected "chip--selected")
                       (when (and tone (not= tone :neutral)) (str "chip--" (name tone)))]}
   body])

;; A path where only the leaf matters. Directories recede, the file does not.
(defalias path-label [{:keys [path range]}]
  (let [cut (string/last-index-of (str path) "/")]
    [:span.path
     (when cut [:span.path__dir (subs path 0 (inc cut))])
     [:span.path__leaf (if cut (subs path (inc cut)) path)]
     (when range [:span.path__range (str " · " range)])]))

;; Time, only while it is still running or still relevant.
(defalias elapsed [{:keys [ms live]}]
  (let [s (quot (or ms 0) 1000)]
    [:span.elapsed {:class (when live "elapsed--live")}
     (cond
       (< s 60) (str s "s")
       (< s 3600) (str (quot s 60) "m")
       :else (str (quot s 3600) "h"))]))

;; ---------------------------------------------------------------------------
;; Chrome
;; ---------------------------------------------------------------------------

;; Lives in the titlebar. Active is the editor ground pulled up into the
;; chrome. A run is a tab like any other.
(defalias tab [{:keys [active? origin count on-select]} body]
  [:div.tab {:class (when active? "tab--active")
             :on {:click on-select}}
   (when (= origin :run) [:span.dot.dot--agent])
   body
   (when count [::count-pill {:count count :tone (when (= origin :run) :agent)}])])

;; Names the file and range a multibuffer region came from, and whose edit it is.
(defalias excerpt-header [{:keys [path range whose]}]
  [:div.excerpt {:class (case whose
                          :run "excerpt--agent"
                          :conflict "excerpt--conflict"
                          nil)}
   [:span.excerpt__path [::path-label {:path path}]]
   (when range [:span range])
   (when whose [:span.excerpt__whose (name whose)])])

;; Stands in for the lines nobody needs to see. Never a number without a count.
(defalias fold-row [{:keys [lines on-select]}]
  [:div.fold {:on {:click on-select}}
   (str "⋯  " lines " unchanged line" (when-not (= 1 lines) "s"))])

;; The path you walked into a value. Every segment is still addressable.
(defalias breadcrumb [{:keys [root segments on-select]}]
  [:div.crumbs
   [:span.crumbs__seg root]
   (map-indexed
    (fn [i seg]
      (list [:span.crumbs__sep {:replicant/key [:sep i]} "›"]
            [:span.crumbs__seg
             {:replicant/key [:seg i]
              :class (when (= i (dec (count segments))) "crumbs__seg--last")
              :on {:click (when on-select (conj (vec on-select) i))}}
             (pr-str seg)]))
    segments)])

;; One link in a cause chain. The root is the only one that gets actions.
(defalias cause-row [{:keys [depth kind root?]} body]
  [:div.cause {:class (when root? "cause--root")}
   [:span.cause__depth depth]
   [:div
    [:span.cause__kind kind]
    " "
    body]])

;; Three weights, one row, and the narrowest grant is always leftmost.
(defalias action [{:keys [weight on-select]} body]
  [:button.action {:class (str "action--" (name (or weight :tertiary)))
                   :on {:click on-select}}
   body])

(defalias action-cluster [_ body]
  [:div.actions body])

;; What an eval will actually reach. The agent is a client like the others.
(defalias connection-row [{:keys [name-of what status bound? kind trailing on-select]}]
  [:div.connection {:class [(when bound? "connection--bound")
                            (when (= kind :agent) "connection--agent")]
                    :on {:click on-select}}
   [::status-dot {:status status}]
   [:span.connection__name name-of]
   [:span.connection__what what]
   (when trailing [:span.connection__trailing trailing])])

;; A label and a count. Panels do not get toolbars.
(defalias panel-header [{:keys [count]} body]
  [:div.panel-header
   body
   (when count [::count-pill {:count count}])])

;; Says what is missing and offers the narrowest way to fix it.
;; Never an illustration.
(defalias empty-state [{:keys [what]} body]
  [:div.empty
   [:div.empty__what what]
   [:div.empty__why body]])
