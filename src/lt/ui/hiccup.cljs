(ns lt.ui.hiccup
  "Hiccup to a DOM node, once.

  The last thing singultus was for. `crate/html` took a hiccup vector and
  returned an element, and four places wanted that: an object's `:init` that
  returns hiccup, the static root [[lt.ui/node]] renders into, and the widgets
  and console lines nothing ever redraws.

  Replicant does the same job and does it with handlers as data, so this is
  that — and it lives in a namespace of its own with one dependency, because
  `lt.object` needs it too and [[lt.ui]] needs `lt.object`."
  (:require [replicant.dom :as r]))

(defn- report!
  "Say something went wrong without requiring `lt.object`, which requires this.

  A hard rule of this namespace: nothing above it in the graph. So the console
  is spoken to directly, and the one caller that can report properly does."
  [msg]
  (js/console.error msg))

(defonce ^:private holder
  ;; Somewhere to render a one-shot node into before handing it out. One
  ;; element, reused, because Replicant keeps a map keyed by the container it
  ;; renders into and a fresh holder per call would leave an entry in it
  ;; forever — an inline result is built once per evaluation and there are a
  ;; lot of evaluations.
  ;;
  ;; Detached from the document on purpose: nothing here is ever on screen.
  (delay (js/document.createElement "div")))

(defn element
  "A detached DOM node for `hiccup`, rendered once.

  The third way to draw something, and the one [[node]] cannot do. A
  CodeMirror widget, a console line and a button in a dialog have this in
  common: something else takes the node and owns it from then on, and there is
  no object whose changes would mean \"draw this again\" — the widget is
  replaced wholesale or it is not replaced at all.

  So this is what [[lt.macros/defui]] was for, with handlers as data instead of
  `addEventListener` closures. `[:li.button {:on {:click [[:popup/choose 2]]}}]`
  works here exactly as it does in a view, because Replicant attaches handlers
  to the nodes themselves rather than delegating from the container — moving
  the node into the document later takes them with it.

  Rendered into a shared holder and then rendered away again, which is what
  detaches it. Handing out `.-firstChild` and leaving it there would work just
  as well for the node and leak the holder's vdom; taking it out behind
  Replicant's back would leave that vdom describing a child that is gone. So
  Replicant removes it, and the holder is clean for the next caller.

  One node, and no `:replicant/unmounting` on it — there is no later render to
  observe either one."
  [hiccup]
  (let [h @holder]
    (r/render h hiccup)
    (let [el (.-firstChild h)]
      (when (> (.. h -childNodes -length) 1)
        (report! (str "lt.ui.hiccup/element was given hiccup with more than one "
                      "root node; only the first is used: " (pr-str hiccup))))
      ;; Detaches `el` without touching its listeners — `removeChild` is all
      ;; Replicant does here — and leaves the holder with no vdom to patch.
      (r/render h nil)
      el)))
