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

(defn duration
  "`ms` as the shortest true unit — the one an editor shows beside a run.

  Truncating rather than rounding, so a number on screen is never larger than
  the thing it measures."
  [ms]
  (let [s (quot (or ms 0) 1000)]
    (cond
      (< s 60) (str s "s")
      (< s 3600) (str (quot s 60) "m")
      :else (str (quot s 3600) "h"))))

(defn ago
  "How long ago, from an age in milliseconds rather than a timestamp.

  An age and not a timestamp because the components that show one must render
  the same way twice from the same arguments; reading the clock inside a
  component is what stops that being true."
  [ms]
  (if (< (or ms 0) 5000) "just now" (str (duration ms) " ago")))

;; Time, only while it is still running or still relevant.
(defalias elapsed [{:keys [ms live]}]
  [:span.elapsed {:class (when live "elapsed--live")} (duration ms)])

;; ---------------------------------------------------------------------------
;; Chrome
;; ---------------------------------------------------------------------------

;; Lives in the titlebar. Active is the editor ground pulled up into the
;; chrome. A run is a tab like any other.
;;
;; Everything you can do to a tab is a handler it is given: choose it, close it,
;; ask what else there is, or pick it up. Dragging is `:draggable?` plus two
;; handlers rather than a library reaching into the DOM — a tab that moves is a
;; list that changed order, and that is a fact about state.
(defalias tab [{:keys [active? origin dirty? count draggable?
                       on-select on-close on-menu on-drag-start on-drop]} body]
  [:div.tab {:class (when active? "tab--active")
             :draggable (when draggable? "true")
             :on {:click on-select
                  :contextmenu on-menu
                  :dragstart on-drag-start
                  :drop on-drop}}
   (when (= origin :run) [:span.dot.dot--agent])
   [:span.tab__label body]
   ;; Dirty is a dot, never a colour change and never an asterisk in the label —
   ;; the label is the file's name and a name does not change when you type.
   (when dirty? [:span.dot.dot--result])
   (when count [::count-pill {:count count :tone (when (= origin :run) :agent)}])
   ;; Last, and only when there is somewhere for it to go. The close button is
   ;; a user behavior — `lt.objs.tabs/show-close-button` — because a row of
   ;; crosses is one mis-click per tab and not everyone wants the trade.
   (when on-close
     [:span.tab__close {:on {:click on-close}} "×"])])

;; Names the file and range a multibuffer region came from, and whose edit it is.
;; Three origins rather than two: an excerpt you wrote yourself is not an
;; unattributed one, and a multibuffer that could not say so would be claiming
;; every region in it came from the run.
(defalias excerpt-header [{:keys [path range origin]}]
  [:div.excerpt {:class (case origin
                          :proposed "excerpt--agent"
                          :yours "excerpt--yours"
                          :conflict "excerpt--conflict"
                          nil)}
   [:span.excerpt__path [::path-label {:path path}]]
   (when range [:span range])
   (when origin [:span.excerpt__whose (name origin)])])

;; Stands in for the lines nobody needs to see. Never a number without a count.
(defalias fold-row [{:keys [lines on-select]}]
  [:div.fold {:on {:click on-select}}
   (str "⋯  " lines " unchanged line" (when-not (= 1 lines) "s"))])

;; The path you walked into a value. Every segment is still addressable.
(defalias breadcrumb [{:keys [root segments siblings on-select]}]
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
    segments)
   ;; Where you are among the peers of where you are. Walking into the fourth
   ;; of forty entries and being shown only the fourth is how an inspector
   ;; loses you.
   (when siblings
     (let [[at total] siblings]
       [:span.crumbs__siblings (str at " of " total " sibling"
                                    (when-not (= 1 total) "s"))]))])

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

;; The row is the thing with an opinion about order, which is why it is a
;; component and not a `div`. `:tone` sets the primary fill: the accent belongs
;; to the situation — granting a run is mauve, restarting a dead process is red
;; — rather than to the button, which is why the button does not name it.
(defalias action-cluster [{:keys [tone]} body]
  [:div.actions {:class (when (and tone (not= tone :result)) (str "actions--" (name tone)))}
   body])

;; What an eval will actually reach. The agent is a client like the others.
(defalias connection-row [{:keys [name-of what status bound? kind trailing on-select on-menu]}]
  [:div.connection {:class [(when bound? "connection--bound")
                            (when (= kind :agent) "connection--agent")]
                    :on {:click on-select
                         :contextmenu on-menu}}
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
