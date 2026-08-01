(ns lt.objs.editor.bands
  "One editor, one set of bands, declared rather than managed.

  A band is the thing Light Table is *for*: a result, a watch, a proposed edit,
  a diagnostic, sitting between two lines of code. Who decides which ones exist
  is [[lt.ui.bands]], from `lt.state`. This namespace is only how that answer
  reaches an editor.

  The set is state. You hand over the whole thing and the view reconciles: what
  left is gone because it is absent, not because anyone removed it. So this is
  one dispatch — where CodeMirror 5 needed a table of drawn widgets, an orphan
  sweep and a close-the-editor cleanup, all of which lived here so that they
  could be deleted in one piece rather than untangled from the code that decides
  what to draw. That is what happened.

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

(defn set-bands!
  "Show exactly `bands` in editor `e`, and nothing else.

  Idempotent, and cheap when nothing changed: a band whose `:content` is equal
  to what is drawn costs one comparison."
  [e bands]
  (.setBands (editor/->cm-ed e) (into-array (map ->js-band bands))))

(defn drawn
  "The keys of what is on screen in editor `e`. For tests and for asking."
  [e]
  (set (.bandKeys (editor/->cm-ed e))))
