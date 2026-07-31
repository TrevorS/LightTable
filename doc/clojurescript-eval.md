# Evaluating ClojureScript

**Built.** This began as a scouting note and the measurements below are what it
found; what it recommended is now what happens. Clojure evaluation works — see
[plugins/Clojure/VENDORED.md](https://github.com/TrevorS/LightTable/blob/develop/plugins/Clojure/VENDORED.md)
— and ClojureScript does not. This is what it would take, measured against a
real shadow-cljs project and a real Leiningen one rather than read out of a
README.

## The good news, first

**Neither route needs anything new in the client.** A ClojureScript REPL over
nREPL is the *same session* as the Clojure one, switched by evaluating a single
form. After the switch, `eval` is ClojureScript and every reply has the shape
Light Table already renders.

Measured against this repository's own shadow-cljs server:

```
(+ 1 1)                                    → {:ns shadow.user  :value "2"}
(shadow.cljs.devtools.api/node-repl)       → {:ns cljs.user    :value "[:selected :node-repl]"}
(js/Math.max 3 7)                          → {:ns cljs.user    :value "7"}
```

That third line is ClojureScript, compiled and run in a node process shadow
started, answering over the same socket the second line went out on. Nothing in
`lt.plugins.clojure.nrepl` had to change to read it.

The same is true through piggieback in a plain Leiningen project:

```
(cider.piggieback/cljs-repl (cljs.repl.node/repl-env))
                                           → {:ns cljs.user    :value "nil"}
```

## Which route

They are not alternatives so much as *the project decides*.

| | shadow-cljs project | anything else |
|---|---|---|
| server | shadow's own nREPL, already running | `lein` jack-in |
| switch | `(shadow.cljs.devtools.api/nrepl-select :build)` or `(…/node-repl)` | `(cider.piggieback/cljs-repl (env))` |
| needs | nothing — shadow ships it | `[cider/piggieback "0.7.0"]` and its middleware |

A shadow-cljs project should use shadow's, and not only to save a dependency:
shadow already knows the project's builds, and `nrepl-select` attaches to the
one that is running with the user's actual runtime — a browser with their app
open in it. Piggieback would start a second, unrelated compiler.

Detecting which is one file check: `shadow-cljs.edn` beside `project.clj` or
`deps.edn`. Light Table already reads `shadow-cljs.edn` as a project root
marker for clojure-lsp.

## The signal to watch

cider-nrepl and shadow both emit a message Light Table currently throws away:

```clojure
{:repl-type "clj"  :status ["state"] :changed-namespaces {…}}
{:repl-type "cljs" :status ["state"] :changed-namespaces {…}}
```

That is the session telling the client which language it is now evaluating,
without the client having to remember what it asked for or parse `:ns`. It
arrives after every evaluation, so a session that is switched from somewhere
else — a user typing the form themselves — is noticed too.

`lt.objs.clients.lsp` has no equivalent problem, but the Clojure client does:
`::nrepl-message` ignores `state` messages today. Reading `:repl-type` off them
and keeping it on the client is what lets `:editor.eval.cljs` be routed to the
same connection instead of hunting for a browser client.

## What is already in place

The plugin's ClojureScript half is not missing — it is disconnected. These all
exist and render:

`::cljs-result`, `::cljs-result.inline`, `::cljs-result.statusbar`,
`::cljs-result.replace`, `::cljs-result.inline-at-cursor`,
`::cljs-result.return`, `::cljs-exception`, `::cljs-watch-result`.

What is missing is the send path: `::on-eval.cljs` builds a command,
`lt.objs.eval/get-client!` looks for a client advertising `:editor.eval.cljs`,
and the nREPL client does not advertise it. Advertising it once the session is
in ClojureScript, and translating the reply the way `:editor.eval.clj` already
is, is most of the work.

## The two decisions, decided

**Nobody is asked which environment.** The project already says: a
`shadow-cljs.edn` beside the code means shadow, anything else means piggieback
over node. That is `::language-servers`' rule again — look at the project, act,
and let a behavior override — rather than a question in front of every
evaluation. Connecting a browser is still something you can *choose*, in the
Connect bar, which is what the Connect bar is for; doing it automatically meant
evaluating any `.cljs` file went looking for a page to attach to and threw
before it ever reached the REPL.

**Two sessions on one connection.** Not a decision in the end: a project holds
`.clj` and `.cljs` files and both have to keep evaluating, so switching the one
session would mean the Clojure half stops until someone switches back. nREPL
sessions are cheap and independent, so `lt.plugins.clojure.nrepl` clones a
second one on the first ClojureScript evaluation and keeps both.

Readiness is decided by the `:repl-type` on the `state` message, not by the
switch form's `done` — the state message arrives *after* it, so deciding on
done asks whether the session is ClojureScript one message before it says so.

Light Table also brings what its own features need, the way it brings
cider-nrepl: `[cider/piggieback]` and `[org.clojure/clojurescript]` are
injected into the jack-in. A project with its own ClojureScript wins on
Leiningen's normal resolution; a project with none gets a working REPL instead
of a missing class.

## One trap, already paid for

`lein update-in :repl-options:nrepl-middleware conj '"cider.piggieback/wrap-cljs-repl"'`
fails with `class java.lang.String cannot be cast to class clojure.lang.IFn`.
The value is read by the Clojure reader and has to be a **symbol**, so the
quotes have to come off. The error names neither the middleware nor the
quoting.

## What was verified

A `.cljs` file in a Leiningen project, evaluated from the editor:

```
(ns cljsprobe)              nil
(defn add [a b] (+ a b))    #'cljsprobe/add
(add 20 22)                 42
(js/Math.max 3 7)           7
```

Real ClojureScript, compiled and run in node, with a result beside each form —
the same path Clojure takes, differing only in which session evaluates.

The shadow route was driven from the editor too, in a project holding only a
`shadow-cljs.edn`: the server started, the second session was cloned and
switched, and `:repl-type` came back `:cljs`. Evaluation then reported *No
available JS runtime*, which is shadow telling the truth — `node-repl` needs
something to run in and that scratch project had nothing built. The machinery
either side of it is verified.

Not covered by `make smoke`: the fixture server there answers LSP, and a
ClojureScript runtime is a great deal more than that.

## A REPL against the editor itself

Not a side case — the reason the editor is called Light Table, and what
[workflow.md](workflow.md) still tells you to do: open a file, evaluate, choose
`Light Table UI`, and change the editor you are typing in.

An earlier draft of this page said this needed a `shadow-cljs watch app` build,
because a release carries no devtools client. That is true of shadow's
`nrepl-select` route and **false of Light Table's own**, which is the one that
matters. Light Table never attached a REPL to its window. It compiled
ClojureScript somewhere else and evaluated the resulting *JavaScript* in the
window — `lt.objs.clients.local`, the client named `LightTable-UI`, which is
`(.call js/eval js/window code)` and about seventy lines around it.

That works in a packaged release, and it works because of two lines in
`shadow-cljs.edn`:

```clojure
:optimizations  :simple
:output-wrapper false
```

`:simple` renames locals and leaves properties alone, and no wrapper means no
closure to hide them in — so `lt.objs.notifos.set_msg_BANG_` is a live global
in the shipped bundle, and JavaScript that names it reaches the running
editor's own function. The whole plugin API is addressable at runtime. That is
not an accident of the build; it is the build carrying the feature.

### What was measured, in `builds/LightTable-0.10.0-mac/LightTable.app`

A packaged release, launched from `builds/`, driven through the same commands a
person would use:

| | |
|---|---|
| `lt.*` still global after `:simple` | yes |
| `Light Table UI` connector present, connects | yes |
| `.js` buffer → eval → statusbar changed | yes |
| `.js` buffer → eval → **new command defined, then ran** | yes |
| `.css` buffer → eval → editor restyled live | yes |
| `.cljs` buffer → eval | **no client** |

The command is the one that matters. `:live-hello` did not exist; a buffer was
evaluated; it existed and ran. The editor gained a capability it shipped
without, while running, from a file open in it.

### The one missing link

`lt.objs.clients.discover` for `:editor.eval.cljs` answers `:none`, and for
`:editor.eval.cljs.exec` answers `:found`. That pair is the whole diagnosis.

Nothing compiles ClojureScript. **Everything downstream of the compiler is
present and working** — `::on-code` takes `:editor.eval.cljs.code`, raises
`:exec.cljs!`, which sends `:editor.eval.cljs.exec` to the `LightTable-UI`
client, which evaluates the JavaScript and renders the result inline. All of
that is live in the release; it is a chain with its first link missing.

The link used to be `lein-light-nrepl`, which ran the ClojureScript compiler on
the JVM against a prebuilt analysis cache of Light Table's namespaces and sent
JavaScript back. It was deleted with the rest of that middleware, for reasons
[VENDORED.md](../plugins/Clojure/VENDORED.md) records and which still hold —
but this went with it, and nothing replaced it.

### It was put back in the window

Two ways were open. **On the JVM, over nREPL** — what the original did, and what
would make the editor's own primary feature depend on a project, a jack-in and a
toolchain that a person editing their `user.behaviors` has no other reason to
own. Or **in the window, self-hosted**, using shadow-cljs's `:target :bootstrap`
to emit per-namespace analysis that `cljs.js` compiles against.

The second, and it works. The risk this page flagged — that the bootstrap loader
would load Light Table's *JavaScript* along with its analysis, re-running every
top-level `def` and replacing the atoms the running editor holds — turned out to
be already solved by shadow: a build requiring `shadow.cljs.bootstrap.browser`
is a bootstrap host, and shadow appends a `set_loaded` call naming everything
the bundle provides, so the loader fetches analysis for those and skips their
code. The split is its design, not something to be worked around.

[Changing the editor while it runs](live-editing.md) is the built thing.

## Still to do

- The shadow route selects `node-repl` rather than a named build. `nrepl-select
  :build` is what attaches to a runtime the user already has open, and choosing
  *which* build is a question the project can answer — `shadow-cljs.edn` lists
  them.
