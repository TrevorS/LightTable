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
   :focus nil
   ;; The statusbar's own facts. They belong to the window rather than to any
   ;; editor, which is why they are here and not projected: nothing in the
   ;; object world owns "what the editor last said to you".
   ;; {:text s :tone :error|:info|nil}
   :message nil
   ;; How many things are working. A count rather than a flag, because two
   ;; overlapping tasks finishing must not turn the indicator off once.
   :loading 0
   :console {:unread 0 :tone nil}
   ;; The file tree, as a map of paths rather than a tree of nodes.
   ;;
   ;; A tree of maps would have to be walked to change one folder, and every
   ;; walk is a chance to lose a subtree. Flat, a path is a lookup: opening a
   ;; folder writes one entry, and the shape on screen is `:children` read
   ;; depth-first from `:roots`. It also means a folder that is closed still
   ;; remembers what was in it, which is what makes reopening one instant.
   ;;
   ;; path -> {:dir? :open? :loaded? :children [path]}
   :workspace {:roots [] :nodes {} :renaming nil :recents nil}
   ;; The connect panel shows the clients or the kinds of client, and
   ;; `:choosing?` is which. The kinds are what the language plugins registered.
   :connect {:choosing? false :connectors []}
   ;; key -> [action …]. The keymap, which is the dispatch table seen from the
   ;; keyboard. Projected from `lt.objs.keyboard/keys` for the context you are
   ;; in — see [[lt.state.objects/keymap]].
   ;;
   ;; `:behavior/rebind` has written here since the design, against a key that
   ;; `initial` did not have and nothing populated. Two surfaces read it now, so
   ;; it does.
   :keymap {}
   ;; The settings screen's own state, which is which half of it you are
   ;; looking at and what you have typed — not the settings themselves.
   ;;
   ;; `:entries` is the *settable* behaviors: those a person can change, with
   ;; their parameters, their current values and the file each value came from.
   ;; It is a projection like `:keymap`, and for the same reason — the
   ;; configuration is a value in the object world, and a view is a function of
   ;; one value.
   ;;
   ;; `:capturing` is the binding whose keystroke is being read, which is a
   ;; mode: while it is set, the keyboard belongs to the screen rather than to
   ;; the editor.
   :settings {:showing :settings   ; :settings | :keys
              :query ""
              :entries []
              :capturing nil}})

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

;; `results-for`, `watches-for` and `edits-for` lived here — three readers of
;; the keying decisions above, written when this namespace was the design and
;; not yet the running editor. `lt.ui.bands` reads `:results`, `:watches` and
;; `:runs` directly, so none of them ever acquired a caller. The keying they
;; demonstrated is documented in the docstring at the top of the file, which is
;; where the argument belonged in the first place.

(defn watch-render!
  "Re-render `f` of the state whenever the state changes.

  One watcher drives everything: the chrome by value, the bands by effect. The
  object model's own rendering is unaffected — see [[lt.ui/node]] — so this can
  arrive one surface at a time rather than as one change nobody can review."
  [key f]
  (add-watch app key (fn [_ _ _ s] (f s)))
  (f @app))
