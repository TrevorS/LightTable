(ns lt.objs.providers
  "Which connected client already answers a surface.

  Light Table has two kinds of thing that can tell you about your code, and
  they overlap. A **language server** knows what the file says. A **REPL**
  knows what is actually loaded — it can tell you the docstring of the
  function you just redefined, which the server on disk cannot. Where both
  could answer, the REPL should, and there is only one doc bar.

  So a surface needs to ask: is anything already answering this? That used to
  be a string test — `(string/ends-with? command \".doc\")` — which worked and
  said nothing. It matched by suffix because the command belongs to the
  language rather than to Light Table: the Clojure plugin advertises
  `:editor.clj.doc`, and a Python one would advertise `:editor.python.doc`.
  Clever, and unreadable at the point of use, where `\".jump-to-definition\"`
  is a string with no type and no way to find every place it matters.

  ## Two ways to answer, and the older one still works

  A client says what it provides:

  ```clojure
  {:name \"LightTable-REPL\" :provides #{:doc :completion}}
  ```

  and a client that says nothing is read the old way, by matching its
  `:commands` against the suffix each surface is known by. That fallback is
  not politeness — Light Table's plugins outlive its releases, and a plugin
  compiled in 2014 that still supplies documentation should keep supplying it.
  Declaring is how a new plugin says so clearly; inference is how an old one
  keeps working.

  Everything here is a function of plain maps, so it is testable without a
  window — see `test/lt/objs/providers_test.cljs`. Deciding who answers is
  exactly the kind of thing that is easy to get subtly wrong and impossible to
  notice, because the symptom is a feature quietly not appearing."
  (:require [clojure.string :as string]))

(def surfaces
  "The surfaces more than one thing can answer.

  `:suffix` is how a client that has not declared `:provides` is recognised —
  the ending its command has whatever language it is for."
  {:doc         {:suffix ".doc"                :desc "documentation at the cursor"}
   :completion  {:suffix ".hints"              :desc "completions"}
   :jump        {:suffix ".jump-to-definition" :desc "jump to definition"}
   :code-action {:suffix ".code-actions"       :desc "code actions"}})

(defn declares?
  "Does `client` say it provides `surface`?"
  [client surface]
  (boolean (contains? (set (:provides client)) surface)))

(defn infers?
  "Does `client` look like it provides `surface`, from its commands?"
  [client surface]
  (when-let [suffix (get-in surfaces [surface :suffix])]
    (boolean (some #(string/ends-with? (str %) suffix) (:commands client)))))

(defn provides?
  "Does `client` answer `surface`?

  A declaration is taken as complete: a client that lists `:provides` is
  saying what it does *and* what it does not, so its commands are not then
  read behind its back. Otherwise the suffix rule applies."
  [client surface]
  (if (contains? client :provides)
    (declares? client surface)
    (infers? client surface)))

(defn provider
  "The first of `clients` that answers `surface`, or nil.

  `clients` are the deref'd client maps, already filtered to the ones that are
  connected — availability belongs to `lt.objs.clients` and this namespace
  stays a function of values."
  [clients surface]
  (first (filter #(provides? % surface) clients)))

(defn provided?
  "Is anything in `clients` answering `surface`?"
  [clients surface]
  (boolean (provider clients surface)))

(def ^:private eval-prefix
  "What an evaluation command is called, whatever the language.

  A prefix rather than a suffix, which is why this is not one of the `surfaces`
  above: the Clojure plugin advertises `:editor.eval.cljs`, Python
  `:editor.eval.python`, and the browser `:editor.eval.cljs.exec` — they agree
  at the front and diverge at the back, where a doc command agrees at the back
  and diverges at the front."
  "editor.eval")

(defn eval-commands
  "The evaluation commands `client` advertises.

  Read off `:commands`, which is what the client sent at handshake and what
  `lt.objs.clients/discover*` already filters on. Nothing is inferred beyond
  the name."
  [client]
  (into (sorted-set)
        (filter #(string/starts-with? (str (symbol %)) eval-prefix))
        (:commands client)))

(defn evaluates?
  "Can `client` be the thing a buffer evaluates through?

  Asked before binding one, and it is the only guard that matters there:
  `lt.objs.eval/get-client!` reuses whatever is bound if it is *available*, and
  never asks whether it can serve the command being sent. So a client bound
  under `:default` that advertises no evaluation command at all is a buffer whose
  next evaluation goes nowhere, with nothing to say why.

  Deliberately not a check that it can serve *this* language. A client that
  advertises `:editor.eval.python` is a legitimate choice for a buffer Light
  Table thinks is something else — the file type may be wrong, or the user may
  know better — and refusing that would be the editor overruling a deliberate
  act. Jupyter lets you select a Python kernel for any notebook too."
  [client]
  (boolean (seq (eval-commands client))))
