(ns lt.objs.editor.bands
  "One editor, one set of bands, declared rather than managed.

  A band is the thing Light Table is *for*: a result, a watch, a proposed edit,
  a diagnostic, sitting between two lines of code. Who decides which ones exist
  is [[lt.ui.bands]], from `lt.state`. This namespace is only how that answer
  reaches an editor, and it exists because the two engines answer it very
  differently.

  On CodeMirror 6 the set is state. You hand over the whole thing and the view
  reconciles: what left is gone because it is absent, not because anyone removed
  it. [[set-bands!]] is one dispatch and there is nothing else in the file's
  CodeMirror 6 half.

  On CodeMirror 5 a line widget is a handle you must keep and take back, so the
  bookkeeping has to live somewhere. It lives *here* — a table, an orphan sweep,
  and a node kept alive by the editor it belongs to — instead of in
  [[lt.ui.bands]], which used to carry all three. That is the point of the
  arrangement: the caller is declarative on both engines, and the day CodeMirror
  5 goes, its bookkeeping goes with it in one deletion rather than being
  untangled from the code that decides what to draw.

  A band is a map:

  * `:line` — zero-based, the way every address in `lt.state` is
  * `:key` — a string identifying it, usually a printed `[path line kind]`
  * `:content` — what it is showing; equal content means no work at all
  * `:mount` — a function of the node, called to fill it and again when
    `:content` changes. The node is empty and the caller owns everything inside
    it from then on."
  (:require [lt.objs.editor :as editor]))

(defn- ->js-band
  "A band as the adapter wants it.

  `:content` goes across as an opaque value with `=` as its comparator, so two
  hiccup trees that are equal are equal here too — which is what stops an
  unchanged band being touched at all."
  [{:keys [line key content mount]}]
  #js {:line line
       :key key
       :content content
       :equals (fn [a b] (= a b))
       :mount mount})

;; --- CodeMirror 5 -----------------------------------------------------------
;;
;; Everything from here to the next heading is what CodeMirror 6 does not need.

(defn- table!
  "This editor's drawn bands, `key -> {:widget w :node n}`.

  Kept on the CodeMirror object rather than in a namespace atom on purpose: a
  table that outlives its editor hands out a node nobody can see, which is a bug
  this codebase has already had. Here the table is reachable only through the
  editor, so it is collected with it and there is nothing to sweep on close."
  [^js cm]
  (or (.-ltBands cm)
      (let [t (atom {})]
        (set! (.-ltBands cm) t)
        t)))

(defn- retire-cm5!
  [^js cm t key]
  (when-let [{:keys [widget]} (get @t key)]
    (swap! t dissoc key)
    (try
      (.removeLineWidget cm widget)
      (catch :default _
        ;; The editor may already be gone, which is not a failure — the widget
        ;; went with it.
        nil))))

(defn- reconcile-cm5!
  [^js cm bands]
  (let [t (table! cm)
        wanted (into {} (map (juxt :key identity)) bands)]
    (doseq [key (keys @t)
            :when (not (contains? wanted key))]
      (retire-cm5! cm t key))
    (doseq [[key {:keys [line mount]}] wanted]
      (if-let [node (:node (get @t key))]
        (mount node)
        ;; A band past the end is one computed against text since changed.
        ;; Dropped rather than thrown — the same rule the CodeMirror 6 field
        ;; applies when it builds its decorations.
        (when (<= 0 line (.lastLine cm))
          (let [node (js/document.createElement "div")]
            (set! (.-className node) "lt-band")
            ;; The same stamp CodeMirror 6's widget puts on its node, so a band
            ;; is identifiable in the DOM whichever engine drew it.
            (.setAttribute node "data-band" key)
            (swap! t assoc key {:widget (.addLineWidget cm line node #js {:coverGutter false})
                                :node node})
            (mount node)))))))

;; --- both -------------------------------------------------------------------

(defn set-bands!
  "Show exactly `bands` in editor `e`, and nothing else.

  Idempotent, and cheap when nothing changed: a band whose `:content` is equal
  to what is drawn costs one comparison."
  [e bands]
  (let [cm (editor/->cm-ed e)]
    (if (editor/cm6? e)
      (.setBands cm (into-array (map ->js-band bands)))
      (reconcile-cm5! cm bands))))

(defn drawn
  "The keys of what is on screen in editor `e`. For tests and for asking."
  [e]
  (let [cm (editor/->cm-ed e)]
    (set (if (editor/cm6? e)
           (.bandKeys cm)
           (keys @(table! cm))))))
