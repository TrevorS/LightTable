(ns lt.ui.kit
  "The kit, as data you can read and rewrite while the editor runs.

  The design's fourth open question: *are aliases the right home for the kit,
  or should the seventeen be plain functions?* — noting that aliases give a
  data representation of the UI that behaviors could rewrite, which is very
  Light Table and more machinery than a function call.

  Aliases, and this namespace is the argument. A plain function is reachable
  only by the code that calls it; an alias is a keyword in a registry, so the
  set of components is a value — enumerable, and replaceable one at a time
  without touching a view. That is the same claim as `[tag behavior-keyword]`
  in `default.behaviors`: what the editor is made of is a table, and a table
  can be edited from inside the thing it describes.

  ```clojure
  (kit/redefine! :lt.ui.row/list-row
                 (fn [attrs body] [:div.row.row--fancy body]))
  ```

  Every row in the window is that, on the next render, and nothing that draws
  a row was recompiled."
  (:require [clojure.string :as string]
            [lt.objs.command :as cmd]
            [lt.state :as state]
            [lt.ui :as ui]
            [replicant.alias :as alias]))

(defn aliases
  "Every component the kit registers, as keywords.

  Sorted, because this is what a settings screen or an agent lists and an
  unstable order in a list of components is noise."
  []
  (vec (sort (keys (alias/get-registered-aliases)))))

(defonce ^:private originals (atom {}))

(defn redefine!
  "Replace the alias `k` with `f`, remembering what was there.

  `f` is `(fn [attrs children] hiccup)`, the same shape `defalias` compiles to.
  The next render uses it — including in the catalogue, which is the point: a
  component swapped here is swapped everywhere it appears, because everywhere
  it appears is the same keyword."
  [k f]
  (when-not (contains? @originals k)
    (swap! originals assoc k (get (alias/get-registered-aliases) k)))
  ;; What `defalias` registers is a two-argument function carrying the alias in
  ;; its metadata. `aliasfn` is the macro that builds one from literal forms,
  ;; which is no use here — the whole point is that this arrives at runtime.
  (alias/register! k (with-meta f {:replicant/alias k}))
  ;; Nothing re-renders on its own: the registry is not the state, and a
  ;; renderer that watched it would be watching for something that changes
  ;; twice a year. Asking is how it is done, and there are two kinds of thing
  ;; to ask — the roots driven by the state, and the objects rendering
  ;; themselves.
  (ui/redraw-all!)
  (swap! state/app identity)
  k)

(defn restore!
  "Put `k` back to what it was defined as, or every alias if given nothing."
  ([]
   (doseq [k (keys @originals)] (restore! k))
   nil)
  ([k]
   (when-let [f (get @originals k)]
     (alias/register! k f)
     (swap! originals dissoc k)
     (swap! state/app identity)
     (ui/redraw-all!))
   k))

(defn redefined
  "Which components are not what they were compiled as."
  []
  (vec (sort (keys @originals))))

(cmd/command {:command :kit.aliases
              :desc "Light Table: List the component kit"
              :exec (fn []
                      (js/lt.objs.console.log
                       (str (count (aliases)) " components: "
                            (string/join ", " (map str (aliases))))))})
