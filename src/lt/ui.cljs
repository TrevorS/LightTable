(ns lt.ui
  "Rendering an object's content with Replicant.

  The other way to write an `:init`. Where [[lt.macros/defui]] builds a node
  once and binds parts of it to atoms, this re-runs a function of the object and
  lets Replicant work out what changed. Both are supported and both are in use —
  see doc/rendering.md for which to reach for.

  Three primitives, and the question that picks between them is always the same
  one: what makes this draw again?

  - [[node]] — the object does. The facts are on the object, so the watch is on
    the object's own atom.
  - [[state-node]] — some other atom does. The view is a function of the state,
    and possibly of more than one clock; see [[lt.state]].
  - [[element]] — nothing does. Something else takes the node and owns it: a
    CodeMirror widget, a console line, a button in a dialog.

  A view is a plain function from the object to hiccup, so it can be redefined
  from the REPL and the next render is the new one. That is the reason to prefer
  it: nothing is captured at build time.

  ```clojure
  (defn- probe-ui [this]
    [:div.inner {:class (when (:busy @this) \"working\")}
     (count (:items @this))])

  (object/object* ::probe
                  :init (fn [this] (ui/node this [:div.probe] probe-ui)))
  ```"
  (:require [clojure.string :as string]
            [lt.object :as object]
            [lt.util.dom :as dom]
            [lt.ui.hiccup :as hiccup]
            [replicant.dom :as r]))

(def element
  "Hiccup as a detached node, rendered once. See [[lt.ui.element/element]].

  Re-exported rather than moved away: `element` is one of the three render
  primitives and belongs in the list with the other two, and it lives in its
  own namespace only because `lt.object` needs it and cannot require this."
  hiccup/element)

(defn render-safely!
  "Render what `view` returns into `el`, and say whose view it was if it throws.

  Replicant catches a render exception itself and logs \"you may have
  misbehaving aliases\" with the exception as a second console argument — which
  arrives as `[object Object]`, names neither the view nor the object it belongs
  to, and is identical for every root in the window. Five of those and nothing
  to bisect from is a real afternoon; this makes it one line naming a namespace.

  It is not enough to wrap the call: a view builds hiccup out of `for`, so the
  throw happens later, inside Replicant, while it walks the tree. Forcing the
  tree first is what puts the failure back in a frame that means something."
  [el what view]
  (let [hiccup (try
                 (let [h (view)]
                   ;; Realized here rather than by the renderer, so a lazy
                   ;; sequence that throws does it in this frame.
                   (doall (tree-seq #(and (coll? %) (not (map? %))) seq h))
                   h)
                 (catch :default e
                   (object/safe-report-error
                    (str "The view for " what " threw while drawing: "
                         (.-stack e)))
                   nil))]
    (when hiccup
      (try
        (r/render el hiccup)
        (catch :default e
          (object/safe-report-error
           (str "Rendering the view for " what " threw: " (.-stack e))))))))

(defonce ^:private rendered
  ;; Every object [[node]] is drawing, so that something which changes what a
  ;; component *is* — rather than what the data is — can ask them all to draw
  ;; again. See [[lt.ui.kit/redefine!]].
  ;;
  ;; Weak in the only sense that matters here: an entry whose object has been
  ;; destroyed is dropped on the next pass rather than held.
  (atom #{}))

(defonce ^:private redraws
  ;; Object -> the redraws of every root it owns. A vector rather than one
  ;; function because an object may have more than one: the statusbar is a
  ;; different clock from the window, so the window has two.
  (atom {}))

(defn redraw-all!
  "Ask every object rendering through [[node]] to draw itself from scratch.

  From scratch, and that is the point. Swapping the object would re-run the
  view and produce *identical* hiccup — the alias keyword has not changed, only
  what it expands to — so a diffing renderer correctly does nothing. The stored
  vdom has to go, which is what `replicant.dom/unmount` is for.

  Nothing here watches the alias registry: it is not state, and a renderer
  observing something that changes twice a year is watching the wrong thing.
  Redefining a component is rare and explicit, so it says so explicitly."
  []
  (swap! rendered (fn [objs] (into #{} (filter deref) objs)))
  (doseq [obj @rendered
          redraw (get @redraws obj)]
    (redraw)))

(defn- apply-attrs!
  "Write `attrs` onto `el` — the root element itself, not its contents.

  Replicant renders *into* the root and so never owns the root's own
  attributes, which is the one thing `bound` did that no view can: `[:span
  {:class (bound this ->result-class)}]` is a class on the element the object
  hands out. It comes up wherever the root is the styled thing — an inline
  result that opens when you click it, a tabset's width — and sometimes the
  root *is* the whole thing, as with the command bar's options input, whose
  content is one `input` with a placeholder and a value.

  Attributes only, and only on the root. That is the boundary: children and
  handlers are Replicant's, and a writer here that took those on would be a
  second renderer beside the one this namespace exists to use.

  `previous` is what was written last time, and only the differences are
  applied — which is what makes this a renderer rather than a writer. It
  matters for one attribute in particular: `value`. Writing it back on every
  draw means the field is reset whenever anything else about the object
  changes, so typing in the command bar's options input while its placeholder
  changed would lose what you typed. Comparing against the DOM instead does not
  fix that, because after you type the two genuinely differ; comparing against
  the last *drawn* value does, and says the right thing — the view is
  authoritative when its own answer changes, and not otherwise."
  [^js el previous attrs]
  (doseq [[k v] attrs
          :when (not= v (get previous k ::absent))]
    (case k
      :class (set! (.-className el) (if (coll? v)
                                      (string/join " " (remove nil? v))
                                      (str v)))
      :style (dom/css el v)
      ;; Properties rather than attributes: setting the `value` attribute
      ;; seeds a field and does not change what is in it.
      (:value :checked) (aset el (name k) v)
      (if (nil? v)
        (.removeAttribute el (name k))
        (.setAttribute el (name k) v)))))

(defn node
  "A DOM node for `obj`, whose contents Replicant renders from `view`.

  `root` is the element itself, as static hiccup — the tag, id and classes that
  do not change. `view` returns what goes inside it, and is re-run whenever
  `obj` changes.

  The split is not a limitation of Replicant, it is what the object model
  requires. `object/->content` hands this node out and the rest of the editor
  keeps the reference: the statusbar appends it, a tabset moves it, `dom/css`
  writes to it. A renderer able to replace the root would invalidate every one
  of those. So Replicant is given the root as its container and owns everything
  inside it — which is also why the document keeps exactly the shape it had,
  with no wrapper added anywhere.

  Rendering into a detached holder and handing out its first child looks
  simpler and does not work: once that node is moved into the document it is no
  longer the holder's child, so the next render patches nothing and the panel
  goes quiet. It fails silently, which is the worst way to fail.

  The watch is on the object's own atom, so it is collected with the object and
  there is nothing to unsubscribe. Watching another object's atom does need
  unsubscribing — [[watch]] is for that.

  `attrs` is for the root's own class and style, which the view cannot reach
  because Replicant renders inside the root rather than rendering it. It is a
  function of the object returning `{:class … :style …}`, re-run with the view,
  and it is what `bound` on a root element becomes. Only reach for it when the
  root really is the styled thing: an inline result that opens when you click
  it is one, a panel whose class never changes is not."
  ([obj root view] (node obj root view nil))
  ([obj root view attrs]
   (swap! rendered conj obj)
   (let [el (hiccup/element root)
         drawn (volatile! nil)
         draw! (fn []
                 ;; A destroyed object is nil, and the watch fires on the way
                 ;; there. Rendering nothing is right: the node is about to go.
                 (when @obj
                   (when attrs
                     (let [a (attrs obj)]
                       (apply-attrs! el @drawn a)
                       (vreset! drawn a)))
                   (render-safely! el (::object/type @obj) #(view obj))))]
     (draw!)
     (add-watch obj ::render (fn [_ _ _ _] (draw!)))
     (swap! redraws update obj (fnil conj []) (fn []
                                                (when @obj
                                                  (r/unmount el)
                                                  (draw!))))
     el)))

(defn state-node
  "A DOM node for `obj`, rendered from `atoms` rather than from the object.

  [[node]] watches the object, which is right when the object is where the
  facts are. A view is the other case: it is a function of the state, so the
  thing to watch is the state — and possibly more than one of them, because
  the statusbar reads the cursor on one clock and everything else on another.
  See doc/rendering.md.

  `view` takes no arguments and returns hiccup; it reads the atoms itself,
  which keeps the slicing in the view where the rest of it is. The watches are
  removed when `obj` is destroyed, unlike [[node]]'s — these atoms outlive it,
  so nothing else would ever take them off."
  [obj root view atoms]
  (swap! rendered conj obj)
  (let [el (hiccup/element root)
        key [::state (hash el)]
        draw! (fn [] (when @obj (render-safely! el (::object/type @obj) view)))]
    (draw!)
    (doseq [a atoms]
      (add-watch a key (fn [_ _ _ _] (draw!))))
    (add-watch obj key (fn [_ _ _ new-value]
                         (when-not new-value
                           (doseq [a atoms] (remove-watch a key)))))
    (swap! redraws update obj (fnil conj []) (fn []
                                               (when @obj
                                                 (r/unmount el)
                                                 (draw!))))
    el))

(defn watch
  "Re-render `obj`'s content when `other` changes, until `obj` is destroyed.

  For a view that reads state belonging to something else — a panel that sizes
  itself to the tabs, say. The watch is on an atom that outlives this object, so
  unlike the one [[node]] installs it has to be taken off again, and the object
  becoming nil is where that happens."
  [obj other]
  (let [key [::watch (hash obj)]]
    (add-watch other key (fn [_ _ _ _] (swap! obj identity)))
    (add-watch obj key (fn [_ _ _ new-value]
                         (when-not new-value
                           (remove-watch other key))))))
