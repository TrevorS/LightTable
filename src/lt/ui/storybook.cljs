(ns lt.ui.storybook
  "The bridge, and nothing else.

  Storybook's html renderer takes a DOM node back from a story, and Replicant
  renders hiccup into a DOM node, so this is three functions and no adapter.
  What to draw comes from [[lt.ui.story]], which the catalogue reads too —
  there is no story data here, and that is the point of module 2.

  **Nothing here reaches the editor.** Thirty of the thirty-five aliases
  require nothing but Replicant; the five that do not are `lt.ui.pane`'s two
  and `lt.ui.kit`'s two, which reach the object world and the state atom.
  Keeping them out is what lets this be a browser bundle with no Electron, no
  bridge and no stubs — and a stub is a second implementation of the thing
  under test, which is how a component passes in a gallery and fails in the
  product."
  (:require [lt.ui.chrome]
            [lt.ui.stories.chrome]
            [lt.ui.story :as story]
            [replicant.dom :as rdom]))

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
