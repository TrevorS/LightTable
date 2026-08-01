(ns lt.ui.bands
  "The boundary: hiccup rendered into DOM Replicant does not own.

  An inline result is not beside the code, it is *between two lines of it*. So
  the band's parent is a node the code editor created and reflows — a line
  widget — and Replicant renders a tree from a root it controls, which the
  editor's content explicitly is not.

  Put the editor inside the chrome tree and one of two things breaks: either
  Replicant diffs away the editor's own DOM, or the bands sit outside the state
  model and stop being declarative. Both are fatal.

  **The answer is N+1 render roots.** One for the chrome. One per visible band,
  into the widget node the editor hands us. Both are ordinary Replicant renders
  of ordinary hiccup; the second just has a foreign parent.

  It costs one discipline, which the kit already pays: a band's hiccup must be
  complete from its props alone, with no inherited context. So
  `:lt.ui.band/result` is the same alias whether it renders in the catalogue,
  in a documentation page, or here — and the impurity is confined to
  [[ensure-widget!]] and [[retire-orphans!]], two functions in one file.

  Editor instances are **not** in the state atom. They are not data."
  (:require [lt.object :as object]
            [lt.objs.editor :as editor]
            [lt.state :as state]
            [lt.ui.band :as band]
            [replicant.dom :as r]))

;; [path line kind] -> {:widget cm-widget :node dom :editor ed}
;;
;; Keyed by kind as well as address because a line can carry a result and a
;; diagnostic at once, and they are different bands rather than one band that
;; changes shape.
(defonce ^:private roots (atom {}))

(defn- path-of [ed]
  (-> @ed :info :path))

(defn- editors-for
  "Every open editor showing `path`. Usually one, occasionally two."
  [path]
  (filter #(= path (path-of %)) (object/by-tag :editor)))

(defn- ensure-widget!
  "The DOM node for this band, adding a line widget if there is not one yet.

  Half of the impurity in this namespace. The node is created empty and handed
  to Replicant, which owns everything inside it from then on — the editor owns
  where it sits and how tall it is, and the two never touch each other's half."
  [ed path line kind]
  (let [key [path line kind]]
    (or (:node (get @roots key))
        (when (<= 0 line (editor/last-line ed))
          (let [node (js/document.createElement "div")
                widget (editor/line-widget ed line node {:coverGutter false})]
            (swap! roots assoc key {:widget widget :node node :editor ed})
            node)))))

(defn- retire!
  "Take one band off the screen and out of the table."
  [key]
  (when-let [{:keys [widget editor]} (get @roots key)]
    (swap! roots dissoc key)
    (try
      (editor/remove-line-widget editor widget)
      (catch :default _
        ;; The editor may already be gone, which is not a failure — the widget
        ;; went with it.
        nil))))

(defn- retire-orphans!
  "Remove every band whose reason to exist is no longer in `wanted`.

  The other half of the impurity. A band is not undrawn by a render — nothing
  renders it any more, which is a different thing — so what is on screen and
  no longer in the state has to be taken off explicitly."
  [wanted]
  (doseq [key (keys @roots)
          :when (not (contains? wanted key))]
    (retire! key)))

(defn- band-for
  "The hiccup for one band. The same alias the kit documents.

  The mime goes through untouched: what to do with a value that is not data is
  the band's decision, in [[lt.ui.band/value-content]], and duplicating it here
  would be two places to teach about a new one."
  [kind line value]
  (case kind
    :result [::band/result {:line line
                            :status (:status value)
                            :value (:value value)
                            :mime (:mime value)
                            :against-unapplied (:against-unapplied value)}]
    :watch [::band/watch {:line line
                          :expression (:expression value)
                          :value (str (:value value))
                          :reads (:reads value)}]
    :edit [::band/proposed-edit {:line line
                                 :before (get-in value [:evidence :as-written])
                                 :after (get-in value [:evidence :if-applied])}]
    :conflict [::band/conflict {:line line
                                :yours (:yours value)
                                :note (:conflict value)}]
    nil))

(defn sync-bands!
  "Draw every band `state` asks for in the editors showing `path`, and retire
  the rest.

  This is the `doseq` from the design, with the address it keys on doing the
  work: a result is `[path line]`, so finding the line to draw it under is a
  lookup rather than a search."
  [state path]
  (let [wanted (volatile! #{})]
    (doseq [ed (editors-for path)]
      (doseq [[[p line] r] (:results state)
              :when (= p path)
              :let [node (ensure-widget! ed path line :result)]
              :when node]
        (vswap! wanted conj [path line :result])
        (r/render node (band-for :result line r)))

      (doseq [[[p line _ :as address] w] (:watches state)
              :when (= p path)
              :let [node (ensure-widget! ed path line :watch)]
              :when node]
        (vswap! wanted conj [path line :watch])
        ;; The value comes from the other atom. `app` holds only the fact that
        ;; the watch exists — see [[lt.state/watch-values]].
        (r/render node (band-for :watch line (merge w (get @state/watch-values address)))))

      (doseq [[_ run] (:runs state)
              e (:edits run)
              :let [[p line] (:at e)
                    kind (if (:conflict e) :conflict :edit)]
              :when (and (= p path) (not (:applied? e)))
              :let [node (ensure-widget! ed path line kind)]
              :when node]
        (vswap! wanted conj [path line kind])
        (r/render node (band-for kind line e))))

    ;; Only this path's bands are candidates for retirement: another file's are
    ;; not orphans just because this one was synchronised.
    (retire-orphans! (into @wanted (remove #(= path (first %)) (keys @roots))))))

(defn paths
  "Every path a band could be drawn in: the open editors that have one."
  []
  (into #{} (keep path-of (object/by-tag :editor))))

(defn sync-all!
  "One pass over every open file."
  ([] (sync-all! @state/app))
  ([state]
   (doseq [path (paths)]
     (sync-bands! state path))))

(defn drawn
  "What is on screen, as addresses. For tests and for asking."
  []
  (set (keys @roots)))

(defn clear!
  "Take every band off. Used when an editor closes and by tests."
  []
  (doseq [key (keys @roots)]
    (retire! key)))

(defn sync-watch-values!
  "Redraw the watch bands, and only those.

  A streaming watch would re-render the whole window on every frame if its
  readings went through `app`. They go through [[lt.state/watch-values]]
  instead, and this is the root that observes it: one render per band, and
  none of them the chrome's."
  []
  (let [state @state/app
        values @state/watch-values]
    (doseq [[[path line kind] {:keys [node]}] @roots
            :when (= kind :watch)]
      (when-let [[address w] (first (for [[[p l _ :as a] w] (:watches state)
                                          :when (and (= p path) (= l line))]
                                      [a w]))]
        (r/render node (band-for :watch line (merge w (get values address))))))))

(defn install!
  "One watcher drives both: the chrome by value, the bands by effect.

  The effect half is here rather than in the chrome's render because a band is
  not part of that tree — that is the whole point of the boundary. The watch
  values get a third, because they tick at a rate the other two must not."
  []
  (state/watch-render! ::bands sync-all!)
  (add-watch state/watch-values ::bands (fn [_ _ _ _] (sync-watch-values!))))

(defn forget-editor!
  "Retire every band whose editor is no longer open.

  A widget goes with the editor that held it, but the table remembering it does
  not — and a table holding a node nobody can see hands one out on the next
  render."
  []
  (let [open (set (object/by-tag :editor))]
    (doseq [[key {:keys [editor]}] @roots
            :when (not (contains? open editor))]
      (swap! roots dissoc key))))
