(ns lt.ui.storybook
  "The bridge, and nothing else.

  Storybook's html renderer takes a DOM node back from a story, and Replicant
  renders hiccup into a DOM node, so this is three functions and no adapter.
  What to draw comes from [[lt.ui.story]], which the catalogue reads too —
  there is no story data here, and that is the point of module 2.

  **Nothing here reaches the editor.** Thirty-two of the thirty-three aliases
  require nothing but Replicant; the one that does not is `lt.ui.pane/pane`,
  which mounts a real CodeMirror — see [[lt.ui.stories.pane]] for why it has no
  stories rather than a stubbed one. Keeping it out is what lets this be a
  browser bundle with no Electron and no bridge.

  (An earlier version of this docstring said thirty-five aliases and five that
  reach the editor. That came from counting `defalias` with grep, which also
  counts the `:refer-macros [defalias]` line in every namespace that uses it.)

  **The require list below is the third of three that have to agree**, beside
  [[lt.ui.stories.manifest]]'s and [[lt.ui.catalogue]]'s. Adding
  `lt.ui.stories.field` to the first two and not to this one is exactly what
  happened, and the symptom is worth knowing: the generator writes the story
  files from the manifest, so Storybook lists all fourteen states and every one
  of them draws nothing, because this bundle has never heard of the namespace
  that registered them. `script/check-stories.mts` is what turns that into a
  failure rather than a page of blank cards."
  (:require [lt.ui.band]
            [lt.ui.chrome]
            [lt.ui.field]
            [lt.ui.host]
            [lt.ui.row]
            [lt.ui.stories.band]
            [lt.ui.stories.chrome]
            [lt.ui.stories.field]
            [lt.ui.stories.host]
            [lt.ui.stories.pane]
            [lt.ui.stories.row]
            [lt.ui.story :as story]
            [replicant.dom :as rdom]))

(defn- install-dispatch!
  "Teach Replicant that a handler may be data.

  Without this, `:on {:click [[:tab/close 0 4]]}` is a vector where a function
  was expected: Replicant throws inside its own render, catches it itself, logs
  *\"you may have misbehaving aliases\"* with the exception as `[object Object]`,
  and skips that render. `chrome/tab`'s closable state found exactly that here,
  three commits after `lt.actions/install!` was documented as the fix for it in
  the editor — which is the argument for a gallery that renders the real
  components rather than pictures of them.

  Logged rather than dispatched. `lt.actions/dispatch!` writes to
  [[lt.state/app]] and performs effects, and neither exists in a browser with no
  editor in it; what a story can honestly show is *which action this gesture
  emits*, which is the thing worth checking about a handler anyway."
  []
  (rdom/set-dispatch!
   (fn [_ handler-data]
     (js/console.log "action" (pr-str handler-data)))))

;; At load, like `lt.actions/install!` does for the editor: every namespace
;; that draws requires this one, so it has to be true before the first paint.
(install-dispatch!)

(defn ^:export render!
  "Draw the story `id` into a fresh element and hand it back.

  A new element every call rather than one reused. Replicant reconciles against
  what it rendered last into a given node, and Storybook remounts a story
  whenever a control changes — handing back a node it has already discarded
  gives you the previous story's DOM with the new story's diff applied to it.

  An unknown id draws nothing rather than throwing, and `script/check-stories.mts`
  is what turns that into a failure: it opens every story in the built index and
  fails on one that drew nothing, which is the case this would otherwise hide."
  [id]
  (let [el (js/document.createElement "div")]
    (set! (.-className el) "lt-story")
    (when-let [hiccup (some (fn [[alias spec]]
                              (some (fn [[state s]]
                                      (when (= id (story/story-id alias state))
                                        (story/hiccup-for alias (if (map? s) s {}))))
                                    (:states spec)))
                            @story/registry)]
      (rdom/render el hiccup))
    el))

(defn ^:export ids
  "Every story id, sorted."
  []
  (clj->js (mapv :id (story/manifest))))
