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

The interesting case, and close to what Light Table was named for. Light
Table's window *is* a ClojureScript program — the `:app` build in this
repository's `shadow-cljs.edn` — so `shadow.cljs.devtools.api/nrepl-select
:app` would attach a ClojureScript REPL to **the editor you are typing in**.
Evaluating a form would change the running editor.

It does not work today, and what stands in the way is build configuration
rather than anything in the plugin. `deploy/core/lighttable/bootstrap.js` is a
shadow **release** build, and a release carries no devtools client — so there
is no runtime for shadow to attach to and `nrepl-select` has nothing to select.
A `shadow-cljs watch app` build does carry one; it opens a websocket back to
the shadow server, and an editor loaded from that output is a live
ClojureScript runtime.

So the missing piece is a development build mode: a `watch:cljs` script, an
editor launched against its output, and shadow's server running beside it.
Everything after that already works, because it is the same `nrepl-select` this
page measured.

Worth being exact about the scope of it. In a packaged release, no — a shipped
editor should not hold a websocket open to a development server, and the
release build has no client with which to. In development, yes, and that is
where an editor you can change while using it is worth having.

## Still to do

- A development build mode, for the section above.
- The shadow route selects `node-repl` rather than a named build. `nrepl-select
  :build` is what attaches to a runtime the user already has open, and choosing
  *which* build is a question the project can answer — `shadow-cljs.edn` lists
  them.
