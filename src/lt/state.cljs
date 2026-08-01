(ns lt.state
  "One atom, and where the design forced its shape.

  Two keying decisions carry most of the product.

  **Results are keyed by `[path line]`** — not by expression, not by an opaque
  id. A band belongs to a line, so the line is the key, which is what makes a
  result addressable and therefore promotable to a watch with one gesture. A
  watch is that same address plus a path into the value.

  **Runs own edits, editors do not.** An unapplied edit lives under its run,
  not in the buffer. That is why a multibuffer can be assembled by run, why
  abandoning a run cannot corrupt a file, and why `:against-unapplied` can be a
  count on a result — the value knows it was computed from a buffer that is not
  the file.

  Not in here: editor instances and DOM nodes. They are not data. See
  [[lt.ui]] and doc/rendering.md for where they live instead.

  Nothing here reaches the window, which is what lets the actions over it be
  tested by calling them.")

(def initial
  {:tabsets []
   :editors {}
   :clients {}
   ;; [path line] -> {:status :value :mime :from :against-unapplied}
   :results {}
   ;; [path line path-into-value] -> {:reads n}
   :watches {}
   ;; id -> {:label :status :grants :edits}
   :runs {}
   :review nil
   :focus nil})

(defonce app (atom initial))

;;*********************************************************
;; The clocks that are not the window's
;;*********************************************************

(defonce cursor
  ;; **Cursor position in the atom.** The statusbar shows line and column, so
  ;; the cursor has to reach state — but mirroring every keystroke into `app`
  ;; means a full window render per keypress. This is the design's second
  ;; option, and the cheaper one: a small atom that only the statusbar's own
  ;; root observes. It also admits something true, which is that the statusbar
  ;; is a different clock from the window.
  (atom {:line 0 :ch 0}))

(defonce watch-values
  ;; **A watch ticking at 60fps.** A streaming watch would re-render the whole
  ;; window on every frame. The history lives here with its own render root per
  ;; band, and `app` holds only the fact that the watch exists. The design
  ;; already draws watches as a distinct colour; here that distinction is also
  ;; a rendering boundary.
  ;;
  ;; [path line into-value] -> {:value v :reads n}
  (atom {}))

(defn observe!
  "Record a reading of the watch at `address`. Does not touch [[app]]."
  [address value]
  (swap! watch-values update address
         (fn [w] {:value value :reads (inc (:reads w 0))})))

(defn results-for
  "Every result belonging to `path`, as `[line result]` pairs.

  The key is what makes this a `filter` rather than a lookup table per file —
  and what makes a result findable from a line number, which is how a band gets
  drawn."
  [state path]
  (for [[[p line] r] (:results state)
        :when (= p path)]
    [line r]))

(defn watches-for
  "Every watch belonging to `path`, as `[line watch]` pairs."
  [state path]
  (for [[[p line _] w] (:watches state)
        :when (= p path)]
    [line w]))

(defn edits-for
  "Every unapplied edit any run proposes for `path`, as `[line edit]` pairs.

  Assembled across runs rather than read out of the buffer, because the buffer
  does not have them: that is the point of runs owning edits."
  [state path]
  (for [[_ run] (:runs state)
        edit (:edits run)
        :let [[p line] (:at edit)]
        :when (and (= p path) (not (:applied? edit)))]
    [line edit]))

;; The window's own error ring already exists; this is the same idea for state.
;; Anything that changes `app` outside an action is a bug, so there is exactly
;; one writer and it is lt.actions/dispatch!.
(defn reset-for-test!
  "Put the atom back to `initial`. Only tests should call this."
  []
  (reset! app initial))

(defn watch-render!
  "Re-render `f` of the state whenever the state changes.

  One watcher drives everything: the chrome by value, the bands by effect. The
  object model's own rendering is unaffected — see [[lt.ui/node]] — so this can
  arrive one surface at a time rather than as one change nobody can review."
  [key f]
  (add-watch app key (fn [_ _ _ s] (f s)))
  (f @app))

(defn stop-render!
  [key]
  (remove-watch app key))
