(ns lt.ui
  "Rendering an object's content with Replicant.

  The other way to write an `:init`. Where [[lt.macros/defui]] builds a node
  once and binds parts of it to atoms, this re-runs a function of the object and
  lets Replicant work out what changed. Both are supported and both are in use —
  see doc/rendering.md for which to reach for.

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
  (:require [replicant.dom :as r]
            [singultus.core :as crate]))

(defonce ^:private rendered
  ;; Every object [[node]] is drawing, so that something which changes what a
  ;; component *is* — rather than what the data is — can ask them all to draw
  ;; again. See [[lt.ui.kit/redefine!]].
  ;;
  ;; Weak in the only sense that matters here: an entry whose object has been
  ;; destroyed is dropped on the next pass rather than held.
  (atom #{}))

(defonce ^:private redraws (atom {}))

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
          :let [redraw (get @redraws obj)]
          :when redraw]
    (redraw)))

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
  unsubscribing — [[watch]] is for that."
  [obj root view]
  (swap! rendered conj obj)
  (let [el (crate/html root)
        draw! (fn []
                ;; A destroyed object is nil, and the watch fires on the way
                ;; there. Rendering nothing is right: the node is about to go.
                (when @obj
                  (r/render el (view obj))))]
    (draw!)
    (add-watch obj ::render (fn [_ _ _ _] (draw!)))
    (swap! redraws assoc obj (fn []
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
