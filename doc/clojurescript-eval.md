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

## Scope

- Files: `plugins/Clojure/src/lt/plugins/clojure/nrepl.cljs` (+~80),
  `plugins/Clojure/src/lt/plugins/clojure.cljs`, `plugins/Clojure/clojure.behaviors`
- Named units: 1 connector entry, `repl-type` tracking, an `:editor.eval.cljs`
  branch in `::nrepl-send!`, and the environment choice
- Verification: live against this repository's own shadow-cljs build and
  against a Leiningen project with piggieback; a smoke check would need a
  ClojureScript runtime, which is more than the fixture server currently is
- Risk: public API no · data migration no · cross-module no · reversible yes ·
  external blocker no
