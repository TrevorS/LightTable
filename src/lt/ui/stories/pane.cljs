(ns lt.ui.stories.pane
  "What `pane` is, and why it has no stories.

  This is module 4, and the finding is that module 4 should not be built.

  `lt.ui.pane/pane` mounts a real CodeMirror against a real path through
  `lt.objs.editor.pool`, and everything worth seeing about it — the gutter, the
  theme, the bands sitting under lines, the fact that Replicant leaves the
  subtree alone — is that editor. Rendering it in a browser with no editor
  means writing something else that looks like one, and **a stub is a second
  implementation of the thing under test**: it is how a component passes in a
  gallery and fails in the product. The pane is already drawn, with a live
  editor in it, by [[lt.ui.catalogue]] inside Light Table, which is the surface
  that can.

  So it is registered here with everything except states. That is not a gap
  being ignored — it is the difference between *nobody wrote stories for this*
  and *this one is drawn somewhere better*, and the completeness check in
  `script/check-stories.mts` reads `:excluded` to tell them apart.

  Registered under the literal keyword rather than through a require, because
  requiring `lt.ui.pane` means requiring `lt.util.dom`, which extends browser
  types at load — the same reason `lt.ui.view` asks for a pane by keyword. The
  cost is that renaming the alias would not break this file, which is a real
  loss and an acceptable one for the single component that has no states to
  check against."
  (:require [lt.ui.story :as story]))

(story/of :lt.ui.pane/pane
  {:doc "An editor, hosted. Empty, keyed, and with hooks either side —
         everything between them is CodeMirror's and Replicant is told nothing
         about it."
   :props [[":path" "string" "the file to show, and the key it is tracked by"]]
   :usage "view/multibuffer · every editor tabset"
   :badge "hosts an editor"
   :width :wide
   :states {}
   :excluded "Drawn with a live editor by the in-editor catalogue. A version of
              it in a browser with no editor would be a picture of a text area."})
