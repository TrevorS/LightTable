(ns lt.objs.statusbar
  "The statusbar, which is the first chrome you use that is a view.

  It was three objects — a cursor, a loader and a console toggle — each with a
  node, spliced into a `ul` by `map-bound`. That is the shape doc/rendering.md
  names as the one thing that cannot be swapped one component at a time: a
  list composed of other objects' DOM. So it is not swapped, it is replaced.
  `lt.ui.view/statusbar` draws the whole bar from the state, and what used to
  be an `object/merge!` into an item is now an action.

  What is left here is the strip itself: where it sits, how tall it is, and
  that the tabs above it end where it starts. That part is still the object
  model's, because the layout around it is.

  **Two clocks.** The bar shows the cursor, which moves on every keypress, and
  the cursor has its own atom for exactly that reason — a projection into
  `state/app` per keystroke would be a window render per keystroke. So this
  root watches both and is the only thing that does. See [[lt.state]]."
  (:require [lt.actions :as actions]
            [lt.object :as object]
            [lt.objs.tabs :as tabs]
            [lt.state :as state]
            [lt.ui :as ui]
            [lt.ui.view :as view]
            [lt.util.cljs :as cljs]
            [lt.util.dom :as dom])
  (:require-macros [lt.macros :refer [behavior]]))

;;**********************************************************
;; statusbar container
;;**********************************************************

(object/object* ::statusbar-container
                :tags #{:statusbar}
                :items (sorted-set-by #(-> % deref :order))
                :init (fn [this]
                        [:div#statusbar-container]))

(def container (object/create ::statusbar-container))

(defn add-container
  "Put `obj` in the strip along the bottom, in `:order`.

  Still here, and still an object splicing another object's node, because that
  is what the strip is: the find bar is in it too, at `:order -1`, and a find
  bar is not a statusbar item that happens to be tall. `:order` and `:height`
  are the contract — raise `:show!` or `:hide!` and the tabs above give the
  space back."
  [obj]
  (object/add-tags obj [:statusbar-item])
  (object/update! container [:items] conj obj)
  (let [items (vec (:items @container))
        i (cljs/index-of obj items)]
    (if (zero? i)
      (dom/prepend (object/->content container) (object/->content obj))
      ;; After the one before it. The original indexed the sorted set by
      ;; position with `get`, which on a set is a lookup by value and returns
      ;; nil — unreachable with two items, and wrong the moment there is a
      ;; third.
      (dom/after (object/->content (nth items (dec i))) (object/->content obj)))))

(behavior ::on-show!
          :triggers #{:show!}
          :reaction (fn [this]
                      (when-not (::shown @this)
                        (dom/css (object/->content this) {:height (:height @this)})
                        (object/merge! this {::shown true})
                        (object/raise tabs/multi :tabset-bottom! (:height @this)))))

(behavior ::on-hide!
          :triggers #{:hide!}
          :reaction (fn [this]
                      (when (::shown @this)
                        (dom/css (object/->content this) {:height 0})
                        (object/merge! this {::shown false})
                        (object/raise tabs/multi :tabset-bottom! (- (:height @this))))))

(behavior ::init-statusbar
          :triggers #{:init}
          :reaction (fn [app]
                      ;; A slot on `#multi` rather than an append into it.
                      ;; `lt.objs.tabs` draws its own children now, and anything
                      ;; put there behind its back is taken out again on the
                      ;; next draw — which is how the cursor position went
                      ;; missing from the bar.
                      (object/merge! tabs/multi
                                     {:statusbar (object/->content container)})))

;;**********************************************************
;; the bar
;;**********************************************************

(defn- bar-ui
  "The whole bar, from the two atoms it reads.

  The cursor is merged in rather than read by the view, because the view takes
  one value — that is what makes it testable with a map — and which atom each
  key came from is this namespace's problem."
  []
  (view/statusbar (assoc @state/app :cursor @state/cursor)))

(object/object* ::statusbar
                :height 28
                :order 0
                :init (fn [this]
                        (ui/state-node this [:div#statusbar] bar-ui
                                       [state/app state/cursor])))

(def statusbar (object/create ::statusbar))

(add-container statusbar)

(behavior ::show-statusbar
          :desc "App: Show statusbar at the bottom of the editor"
          :type :user
          :triggers #{:init}
          :reaction (fn [this]
                      (object/raise statusbar :show!)))

;;**********************************************************
;; what the rest of the editor puts in it
;;**********************************************************

;; Every one of these is a dispatch rather than a write into an item. Callers
;; are unchanged in shape — `notifos/working` still says "something is working"
;; — but there is one writer of the state now and the bar is a function of it,
;; so what the statusbar shows can be asserted by folding actions over a map.
;; See `test/lt/actions_test.cljs`.

(defn message!
  "Say `m` in the bar. `class` is the old vocabulary — \"error\", \"tip\" — and
  is narrowed to a tone here, which is the last place it is a string."
  ([m] (message! m nil))
  ([m class]
   (actions/dispatch! [[:status/message m (when (= class "error") :error)]])))

(defn loader-set []
  (actions/dispatch! [[:status/loading :set]]))

(defn loader-inc []
  (actions/dispatch! [[:status/loading :inc]]))

(defn loader-dec []
  (actions/dispatch! [[:status/loading :dec]]))

(defn dirty
  "The console said something you have not looked at."
  []
  (actions/dispatch! [[:console/unread :inc]]))

(defn clean
  "You looked."
  []
  (actions/dispatch! [[:console/unread :clear]]))

(defn console-class
  "The tone of what the console last said. Only an error changes the count's
  colour; everything else is a number of things to read."
  [class]
  (actions/dispatch! [[:console/unread :tone (when (= class "error") :error)]]))
