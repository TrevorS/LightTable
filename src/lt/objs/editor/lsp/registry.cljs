(ns lt.objs.editor.lsp.registry
  "Which language server goes with which editor, as data.

  Separate from [[lt.objs.editor.lsp]] because it is pure, and precedence is
  the kind of rule that should have a test rather than a comment. Everything
  else in that namespace reaches for an editor, a process or the bridge and
  cannot be loaded without an application around it.

  A **table** is a vector of entries, in declaration order:

  ```clojure
  [{:tags [:editor.clj :editor.cljs]
    :language-id \"clojure\"
    :root [\"deps.edn\" \"project.clj\"]
    :command \"clojure-lsp\"
    :args []}]
  ```

  `:tags` is inside the entry rather than being the key. One server usually
  answers for several editor tags — clojure-lsp for four file extensions —
  and keying by tag meant repeating the whole entry per tag.

  ## Precedence

  **Later declarations win.** Behaviors reach an object in the order their
  files were loaded, which is `default.behaviors`, then every plugin's, then
  `user.behaviors` — so the rule reads as *user beats plugin beats core* and
  needs no ranking of its own. Pointing at a particular binary, adding
  arguments, or turning a server off is then something a user writes in
  `user.behaviors`, like every other knob in this editor.

  An entry's place in the table is its **last** declaration, not its first.
  That is what makes the rule hold when the same declaration arrives more than
  once, which is not hypothetical: `deploy/core/User` is both the user
  directory and an installed plugin, so `user.behaviors` is read twice — once
  at the plugin stage and once at the user stage — and keeping the first
  arrival would have parked a user's entry ahead of the plugin it was written
  to override. That bug was real and this is what fixed it.

  It also makes reloading safe. `lt.objs.settings` re-raises `:object.instant`
  on every behavior reload, so every declaration arrives again in the same
  order; a table whose order is decided by last arrival lands in the same place
  it was already in."
  (:require [clojure.set :as set]))

(defn add
  "`entries` appended to `table`, moving any it already holds to the end."
  [table entries]
  (into (vec (remove (set entries) table)) entries))

(defn for-tags
  "The server for an editor carrying `tags`, or nil.

  The last matching entry, so a later declaration beats an earlier one. An
  entry matches when any of its `:tags` is one the editor carries."
  [table tags]
  (last (filter #(seq (set/intersection (set (:tags %)) (set tags))) table)))
