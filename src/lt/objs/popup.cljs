(ns lt.objs.popup
  "The modal, and the only thing in Light Table that can stop a caller dead.

  A popup is what it was created with — a header, a body, a list of `:options`
  and a list of `:buttons` — plus which choice is `:active`. That is the whole of
  its state, and it is on the object, so the view is a function of it and moving
  the selection is a number changing rather than two classes being swapped in the
  DOM.

  It used to be the other way, and the cost showed up somewhere unexpected:
  `lt.objs.control` exposes open popups over MCP, and had to read the header
  back out of the rendered `h2` because the object did not keep it.

  ## `:options` and `:buttons` are both choices and are not the same thing

  A `:button` confirms or cancels: *ok*, *cancel*, *save anyway*. There are one
  to three of them and they are floated right.

  An `:option` **is the question**: which of these clients should evaluate this,
  which of these code actions to run. There can be any number, they read top to
  bottom, and the popup exists in order to ask.

  Both are `{:label :action}` and both go through [[choose!]], so an option
  closes the popup and runs its action exactly as a button does. And `:active`
  indexes **both**, in the order they are on screen — so the arrow keys walk the
  options and then the buttons, and Enter runs whatever is highlighted.

  That last part changed, and it changes what Enter does. `:active` was `:button`
  and indexed the buttons alone, so a popup asking which of nine things you wanted
  let you arrow between *cancel* and nothing — and Enter on a chooser cancelled it.
  Enter now takes the highlighted option, which is what every other chooser does
  and what the highlight has been promising all along. A popup with no options is
  unaffected: the indices are just its buttons, as before.

  The distinction earns its keep in `lt.objs.control`, which exposes prompts over
  MCP. `:options` did not exist, so the two callers that needed them built
  `li.button` hiccup by hand in `:body` with a click closure inside it — which
  made the choices invisible to everything except the rendered document, so the
  control surface read them back out with `querySelectorAll`. A reader that
  trusted `:buttons` alone would have offered \"cancel\" and nothing else for
  exactly the prompts where the choice matters."
  (:require [lt.object :as object]
            [lt.objs.command :as cmd]
            [lt.objs.context :as ctx]
            [lt.objs.canvas :as canvas]
            [lt.objs.keyboard :as keyboard]
            [lt.ui :as ui]
            [lt.util.dom :as dom]
            [clojure.string :as string])
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
  leftmost, and the selection counts along the row you are looking at rather than
  the list a caller wrote."
  [state]
  (vec (reverse (:buttons state))))

(defn- drawn-choices
  "Every choice in the order it is on screen: options top to bottom, then the
  buttons right to left.

  `:active` indexes this, which is what makes the arrow keys reach an option. It
  used to index `:buttons` alone, so a popup whose whole purpose was to ask which
  of nine things you wanted let you arrow between *cancel* and nothing.

  The state's own copy rather than the object's, so this can be called from a
  view that already has it."
  [state]
  (into (vec (:options state)) (drawn-buttons state)))

(defn- choose!
  "Run what choice `c` says, and close unless it asked to stay."
  [this {:keys [action post-action]}]
  (binding [*no-close* nil]
    (when (fn? action)
      (action))
    (when-not *no-close*
      (object/raise this :close!))
    (when (fn? post-action)
      (post-action))))

(behavior ::change-active-choice
          :triggers #{:move-active}
          :reaction (fn [this dir]
                      ;; The buttons used to be counted in the DOM and the
                      ;; class moved between two of them. They are a list on
                      ;; the object, so this is arithmetic and the view draws
                      ;; the consequence.
                      (let [n (count (drawn-choices @this))]
                        (when (pos? n)
                          (object/merge! this {:active (mod (+ (:active @this) dir) n)})))))

(behavior ::exec-active
          :triggers #{:exec-active}
          :reaction (fn [this]
                      (when-let [c (nth (drawn-choices @this) (:active @this) nil)]
                        (choose! this c))))

(defn- popup-ui
  "Four nested divs, which is not decoration: `deploy/core/css/structure.css`
  centres a modal by making them a table, a cell, an inline-block and the card.
  The outermost is the view's, because [[lt.ui/node]] hands the `.popup` itself
  to the object and renders inside it."
  [this]
  (let [{:keys [header body active options] :as state} @this
        ;; The buttons continue the options' numbering, because `:active` indexes
        ;; the whole on-screen list — see [[drawn-choices]]. Getting this wrong
        ;; would highlight one thing and run another.
        offset (count options)]
    [:div
     [:div
      ;; Clicking the card is not clicking the backdrop. Without this every
      ;; click inside a modal would dismiss it, because the backdrop's own
      ;; handler is on an ancestor.
      [:div {:on {:click (fn [e] (dom/prevent e) (dom/stop-propagation e))}}
       [:h2 header]
       [:p body]
       ;; Between the body and the buttons, because an option is what the body
       ;; just explained and a button is what you do about it.
       ;;
       ;; `ul.options` against `ul.buttons` is what tells the two apart, and it
       ;; replaces a class each caller used to put on its own `li` for exactly
       ;; that purpose — `.lsp-action` was one, and its comment said why: *the
       ;; popup's cancel is an `li.button` too*. The structure says it now.
       (when (seq options)
         [:ul.options
          (for [[i o] (map-indexed vector options)]
            [:li.button {:replicant/key i
                         :class (when (= i active) "active")
                         :on {:click (fn [] (choose! this o))}}
             (:label o)])])
       [:ul.buttons
        (for [[i b] (map-indexed vector (drawn-buttons state))]
          [:li.button {:replicant/key i
                       :class (when (= (+ offset i) active) "active")
                       :on {:click (fn [] (choose! this b))}}
           (:label b)])]]]]))

(object/object* ::popup
                :tags #{:popup}
                ;; Which choice is highlighted, indexing the whole on-screen
                ;; list — see `drawn-choices`. It was `:button` and indexed the
                ;; buttons alone, which is the name a key should not keep once it
                ;; stops being true.
                :active 0
                :init (fn [this opts]
                        ;; Before the node, because [[lt.ui/node]] draws once
                        ;; on the way out and the view reads this.
                        (object/merge! this (assoc opts :active 0))
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

(defn choices
  "Every choice `p` offers, in the order they are on screen.

  [[drawn-choices]] of the object, exposed. Two functions saying this would be two
  things to keep in step, and the one that must not drift is the pair
  *what is highlighted* and *what the control surface reports* — a popup that
  listed its choices in one order and numbered them in another would answer the
  wrong one.

  Here rather than in `lt.objs.control` because the order is this namespace's
  business: the control surface asks what a popup offers, and should not have to
  know that buttons are laid out backwards to know what it is looking at."
  [p]
  (drawn-choices @p))

(defn choose-by-label!
  "Make `p`'s choice whose label is `label`, case-insensitively. Returns the
  choice, or nil when there is none.

  Runs the choice rather than clicking it. `lt.objs.control` used to find the
  `li.button` and call `.click()` on it, which reaches the same place through the
  handler the view installed — but going through [[choose!]] is the thing that is
  actually meant, and it does not stop working if the view changes what it puts a
  handler on.

  Case-insensitive on the visible label, because that is what a caller was shown."
  [p label]
  (let [wanted (string/lower-case (string/trim (str label)))]
    (when-let [choice (first (filter #(= wanted (string/lower-case
                                                 (string/trim (str (:label %)))))
                                     (choices p)))]
      (choose! p choice)
      choice)))

(defn popup! [options]
  (let [p (object/create ::popup options)]
    (object/raise p :move-active 0)
    (dom/append (dom/$ :body) (object/->content p))
    (.focus (object/->content p))
    (ctx/in! :popup p)
    p))

(def cancel-button {:label "cancel" :action :cancel})
