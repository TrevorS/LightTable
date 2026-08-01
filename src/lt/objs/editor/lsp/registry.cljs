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
    :args []
    ;; Optional.
    :id \"clojure-lsp\"
    :install \"brew install clojure-lsp/brew/clojure-lsp-native\"
    :init-options {}}]
  ```

  `:install` is shown when the command is not on PATH, so a missing server
  says how to get it. `:init-options` is sent as `initializationOptions` —
  server-specific settings, like telling rust-analyzer to check with clippy.

  `:tags` is inside the entry rather than being the key. One server usually
  answers for several editor tags — clojure-lsp for four file extensions —
  and keying by tag meant repeating the whole entry per tag.

  ## More than one server for a language

  Normal, and increasingly the point: a language often has a compiler-backed
  server and a linter or formatter beside it — vtsls and biome, pyright and
  ruff. They answer different questions and neither replaces the other, so
  every one that is declared runs, and each surface asks the server that says
  it can answer.

  Two declarations are the *same* server when they share an **`:id`**, which
  defaults to the executable's name — `:command` with any directories dropped.
  That is what separates adding a second server from replacing the first:
  biome names a different executable, so it stacks; a user pointing at
  `~/.bun/bin/vtsls` names the same one, so it replaces, which is the whole
  reason the default is the name rather than the path. Swapping in an
  executable that is called something else needs an explicit `:id`, or it will
  run alongside rather than instead of what it was meant to replace.

  An entry with no `:command` is how a server is turned off — declare its `:id`
  with nothing to run and the entry it replaces stops being offered.

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
  (:require [clojure.set :as set]
            [clojure.string :as string]))

(defn add
  "`entries` appended to `table`, moving any it already holds to the end."
  [table entries]
  (into (vec (remove (set entries) table)) entries))

(defn- id-of
  "What makes two declarations the same server: `:id`, or the executable's name."
  [entry]
  (or (:id entry)
      (last (string/split (str (:command entry)) #"[\\/]"))))

(defn all-for-tags
  "Every server to run for an editor carrying `tags`, in declaration order.

  An entry matches when any of its `:tags` is one the editor carries. Entries
  sharing an `:id` are one server declared more than once, and the last of them
  is the one that counts — in the place the first was declared, so adding
  arguments to a server does not also move it to the end of the queue.

  An entry left with no `:command` is dropped, which is how one is turned off."
  [table tags]
  (let [matching (filter #(seq (set/intersection (set (:tags %)) (set tags))) table)
        winner (reduce #(assoc %1 (id-of %2) %2) {} matching)]
    (into []
          (comp (map #(get winner (id-of %)))
                (distinct)
                (remove #(string/blank? (:command %))))
          matching)))

(defn for-tags
  "The one server to ask when only one can answer, or nil.

  The last of them, so a later declaration beats an earlier one."
  [table tags]
  (last (all-for-tags table tags)))
