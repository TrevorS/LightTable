(ns lt.objs.plugins.attribution
  "Which plugin is asking, from the call stack.

  Light Table evaluates plugin code with a `sourceURL`, so a frame inside a
  plugin names its file and the plugin's directory is a prefix of that path.
  That is the whole mechanism, and it is the expensive-sounding half of a
  permission system that turns out to be cheap: **1.3-2.7µs per stack** on
  Electron 43.

  This lived in [[lt.objs.plugins.require-shim]], which is where it was needed
  first and where it is still used. It moved here when the bridge needed the
  same answer — the shim decides what `require` serves, the bridge decides what
  a call is allowed to touch, and both are asking one question that neither
  owns.

  Dependency-free on purpose, like [[lt.objs.plugins.capabilities]] beside it:
  attribution is decidable from a list of strings and a list of plugins, so it
  is testable without an editor, a window or a stack that really came from
  one.

  What this is not: a plugin runs in the window and the stack is a convention,
  not a boundary. Code that wanted to hide could call through a `setTimeout`
  and be attributed to nobody. See doc/permissions.md for why that is the
  level being aimed at rather than a gap in it."
  (:require [clojure.string :as string]))

(defn frames
  "Stack frames of the caller, innermost first.

  A fresh `Error` rather than an argument, because the point is where *this*
  was called from."
  []
  (-> (.-stack (js/Error.))
      (or "")
      (string/split #"\n")))

(defn in-dir?
  "Is `frame` a path inside `dir`?

  The separator matters. A plain substring test says plugins/C is where
  plugins/Clojure's code lives, because one path is a prefix of the other —
  so the Clojure plugin was refused `net` on the grounds that a plugin called
  C had not asked for it, and failed to load. Any plugin whose name is a
  prefix of another's would have done it."
  [frame dir]
  (string/includes? frame (str dir "/")))

(defn plugin-for-frames
  "The plugin whose directory appears in `frames`, given `plugins` by name.

  Takes the innermost matching frame: a plugin calling through another
  plugin's helper is asking on its own behalf, not the helper's.

  `nil` means no plugin directory claims any frame, which is Light Table's own
  code. That is not a loophole — the editor is the thing granting permission,
  and a permission system its own code has to satisfy is one that gets turned
  off."
  [plugins frames]
  (first (for [frame frames
               plugin (vals plugins)
               :let [dir (:dir plugin)]
               :when (and dir (in-dir? frame dir))]
           plugin)))

(defn caller-plugin
  "The plugin on whose behalf the current call is being made, or nil.

  The composition of the two above, which is what every caller actually wants.
  Reading the stack is the cost here, so a caller that can answer its question
  without knowing who is asking should not call this at all — see
  [[lt.util.bridge.guard]], which checks whether any plugin is even scoped
  before it looks."
  [plugins]
  (plugin-for-frames plugins (frames)))
