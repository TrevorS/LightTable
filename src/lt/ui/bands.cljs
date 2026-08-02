(ns lt.ui.bands
  "The boundary: hiccup rendered into DOM Replicant does not own.

  An inline result is not beside the code, it is *between two lines of it*. So
  the band's parent is a node the code editor created and reflows, and Replicant
  renders a tree from a root it controls, which the editor's content explicitly
  is not.

  Put the editor inside the chrome tree and one of two things breaks: either
  Replicant diffs away the editor's own DOM, or the bands sit outside the state
  model and stop being declarative. Both are fatal.

  **The answer is N+1 render roots.** One for the chrome. One per visible band,
  into the node the editor hands us. Both are ordinary Replicant renders of
  ordinary hiccup; the second just has a foreign parent.

  It costs one discipline, which the kit already pays: a band's hiccup must be
  complete from its props alone, with no inherited context. So
  `:lt.ui.band/result` is the same alias whether it renders in the catalogue, in
  a documentation page, or here.

  What this namespace does *not* do any more is remember. It answers one
  question — given a state and a path, which bands exist — and hands the answer
  over whole. Nothing here removes a band, because a band that left is one this
  function stopped returning. The table of what is drawn, the orphan sweep and
  the retire-on-close all moved to [[lt.objs.editor.bands]], which is where the
  engine that needs them lives.

  Editor instances are **not** in the state atom. They are not data."
  (:require [lt.object :as object]
            [lt.objs.editor.bands :as ed-bands]
            [lt.state :as state]
            [lt.ui.band :as band]
            [lt.ui :as ui]))

(defn- path-of [ed]
  (-> @ed :info :path))

(defn- editors-for
  "Every open editor showing `path`. Usually one, occasionally two."
  [path]
  (filter #(= path (path-of %)) (object/by-tag :editor)))

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

(defn- band
  "One band, as [[lt.objs.editor.bands]] wants it.

  The key carries the line as well as the address because a band that moved is a
  different widget, not the same one relocated — and it carries the kind because
  a line can hold a result and a diagnostic at once, which are two bands rather
  than one band that changes shape.

  `:content` is the hiccup itself. Equal hiccup is a band that needs no work,
  which is why a streaming watch does not disturb the result two lines below
  it."
  [path line kind value]
  (let [hiccup (band-for kind line value)]
    {:line line
     :key (pr-str [path line kind])
     :content hiccup
     ;; Through `render-safely!` so a band that throws names itself.
     ;; A band's parent is a node the editor owns, so a Replicant exception
     ;; here is otherwise five identical console lines and no address.
     :mount #(ui/render-safely! % (str "a " (name kind) " band at " path ":" line)
                                (constantly hiccup))}))

(defn bands-for
  "Every band `state` asks for in `path`, as data.

  Pure, and the whole decision. What is on screen is a function of this — see
  [[sync-bands!]], which does nothing but hand the answer to an editor."
  [state path]
  (let [values @state/watch-values]
    (concat
     (for [[[p line] r] (:results state)
           :when (= p path)]
       (band path line :result r))

     ;; The value comes from the other atom. `app` holds only the fact that the
     ;; watch exists — see [[lt.state/watch-values]].
     (for [[[p line _ :as address] w] (:watches state)
           :when (= p path)]
       (band path line :watch (merge w (get values address))))

     (for [[_ run] (:runs state)
           e (:edits run)
           :let [[p line] (:at e)]
           :when (and (= p path) (not (:applied? e)))]
       (band path line (if (:conflict e) :conflict :edit) e)))))

(defn sync-bands!
  "Show what `state` asks for in every editor on `path`."
  [state path]
  (let [bands (bands-for state path)]
    (doseq [ed (editors-for path)]
      (ed-bands/set-bands! ed bands))))

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
  "What is on screen, as keys. For tests and for asking.

  A key is a printed `[path line kind]`, which is what [[band]] built it from."
  []
  (into #{} (mapcat ed-bands/drawn) (object/by-tag :editor)))

(defn clear!
  "Take every band off. Used by tests."
  []
  (doseq [ed (object/by-tag :editor)]
    (ed-bands/set-bands! ed [])))

(defn install!
  "One watcher drives both: the chrome by value, the bands by effect.

  The effect half is here rather than in the chrome's render because a band is
  not part of that tree — that is the whole point of the boundary. The watch
  values get a second, because they tick at a rate the chrome must not: a
  streaming watch redraws its own band and nothing else, since every other
  band's hiccup compares equal and is left alone."
  []
  (state/watch-render! ::bands sync-all!)
  (add-watch state/watch-values ::bands (fn [_ _ _ _] (sync-all!))))
