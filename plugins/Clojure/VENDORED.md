# Clojure, in this repository

Copied from [LightTable/Clojure](https://github.com/LightTable/Clojure) at
version **0.3.3**, under its own MIT licence (`LICENSE.md`), which is not this
project's.

Upstream shipped `clojure_compiled.js` and its source map as checked-in build
artifacts that nothing rebuilt. Here the ClojureScript is a module of the `:app`
build, so it compiles against the editor it extends and a rename in Light Table
fails the build rather than a user's session.

## What was left behind

| | |
|---|---|
| `clojure_compiled.js`, `.js.map` | build output; rebuilt from `src/` |
| `project.clj`, `build.sh`, `.travis.yml` | upstream's own build, replaced by `shadow-cljs.edn` |
| `codemirror/clojure.js` | see below |
| `runner/` | the uberjar project; see **The nREPL server** below |
| `CHANGELOG.md`, `CONTRIBUTING.md` | upstream process, not this plugin |

`lein-light-nrepl/` is kept and is the plugin's other half: the nREPL
middleware, in Clojure, that a REPL loads so Light Table can talk to it.
`local-project/project.clj` — upstream's `runner/resources/project.clj` — is
read when a REPL starts with no project around the file being evaluated.

## The CodeMirror mode

Dropped. Upstream's `clojure.behaviors` loaded `codemirror/clojure.js` ahead of
the plugin, from the days when `lt.objs.editor/mode-blacklist` stopped the
editor bundling a mode any plugin shipped. That list is gone —
`script/gen-codemirror-requires.js` says why — and Light Table now bundles all
131 of CodeMirror's modes, including a current `clojure`. Keeping the copy meant
a decade-old mode overwriting a fresh one at load, which is worse than nothing.
Light Table also has tree-sitter highlighting for Clojure on top of that.

## The nREPL server

**Upstream's did not run, and had not for years.** `java -jar
lein-light-standalone.jar` dies before it prints anything: `dynapath`, pulled in
underneath Leiningen 2.5.2, reads `sun.misc.Launcher$ExtClassLoader` when its
class initialises, and that has not existed since JDK 9. Light Table requires
JDK 21. So Clojure evaluation — the plugin's entire reason to exist — was
broken on arrival, quietly, because the failure happened in a child process
whose output nothing read.

Two more things that jar turned out to be, neither of them what its name
suggests:

- **It is Leiningen, packaged.** 11,590 files, of which 87 lines are Light
  Table's. Everything else is Leiningen 2.5.2 and its Maven, Aether, plexus and
  jackson dependencies. 15MB to ship one namespace.
- **It contains none of the middleware.** `lighttable/nrepl/*` is not in it.
  The launcher injected `[lein-light-nrepl "0.3.3"]` and
  `[lein-light-nrepl-instarepl "0.3.1"]` as dependencies, so starting a REPL
  fetched somebody else's artifacts from Clojars *at runtime* — a worse
  dependency than the build-time one, and one that fails on an aeroplane.

So there is no jar any more, and nothing is downloaded. Light Table starts a
REPL through **the user's own Leiningen**, pointing it at the middleware
sources vendored here:

```
lein update-in :source-paths conj "<plugin>/lein-light-nrepl/src" -- \
     update-in :dependencies conj '[org.clojure/data.json "2.5.1"]' -- \
     ... -- \
     update-in :repl-options:nrepl-middleware conj \
         '"lighttable.nrepl.handler/lighttable-ops"' -- \
     repl :headless
```

`lt.plugins.clojure/lein-args` builds that. Using the Leiningen the user
already has means it tracks their JDK and their project rather than a 2015
snapshot of both, and it is what CIDER, Calva and Conjure all do. The cost is
that Leiningen has to be installed; the plugin says so, with a link, rather
than failing silently the way the jar did.

The Clojars copy of `lein-light-nrepl` 0.3.3 is byte-identical to what is
vendored here, so nothing was lost by no longer fetching it.
`lein-light-nrepl-instarepl` is dropped — it is one file backing the instarepl,
which is a separate feature and was never vendored.

## The middleware, ported

`lein-light-nrepl/` is a decade newer than it was. It had to be: the nREPL it
was written against no longer exists under that name.

| | was | now |
|---|---|---|
| nREPL | `org.clojure/tools.nrepl` 0.2.10 | `nrepl/nrepl` 1.x |
| Clojure | 1.7.0 | 1.12.3 |
| ClojureScript | 0.0-3308 | 1.12.42 |
| reader, json, complete, commons-io | 2013-2015 | current |

The only part that was more than a rename is `lighttable.nrepl.core/queued`.
It reached into two **private** vars — `queue-eval` and `configure-executor` —
to put Light Table's operations on the same serialised queue as `eval`. Neither
exists in nrepl 1.x: a session owns a thread and publishes `:exec` in its own
metadata, which is public and does more, so `queued` came out shorter than it
went in. It calls the three-argument form, because Leiningen still ships nREPL
1.0 and the fourth argument arrived in 1.1.

The 487 lines driving the ClojureScript compiler needed nothing at all, which
was the outcome I would have bet against.

**Verified** on JDK 26 against a real project: evaluation returning a value,
`println` streaming to the inline result, an exception with a stacktrace,
`editor.clj.doc`, and `editor.clj.hints` returning 59kB of completions — first
by driving the wire protocol directly, then through Light Table itself.

**Still to answer:** whether this middleware should exist at all.
`cider-nrepl` and `orchard` are what every other editor uses, are maintained,
and do all of this better — `compliment` for completion rather than
clojure-complete from 2013, orchard for docs, piggieback or shadow-cljs for
ClojureScript rather than 487 lines driving the compiler by hand. Adopting them
would delete most of `lein-light-nrepl/` and rewrite the plugin's client half,
which is a bigger job than this one and a separate decision.

## Changes to the source

Compiling against the current editor and the current ClojureScript compiler
found these. Everything else is unmodified.

| where | was | why |
|---|---|---|
| `clojure/collapsible_exception.cljs` | `crate.binding` | Light Table's hiccup library was forked and renamed `singultus`. `lt.compat` shims this at runtime for published plugins; a plugin compiled from source needs the real namespace. |
| `clojure/nrepl.cljs` `decode` | `recur` inside `try` | rejected by the compiler. The attempt now yields the bytes still to decode and the loop recurs outside the `try`. |
| `clojure/nrepl.cljs` `decode` | `(catch js/global.Error …)` | there is no `global` in the window under `contextIsolation`, so the catch matched nothing it was written for. |
| `clojure/nrepl.cljs` `non-blocking-loop` | `js/global.setImmediate` | same `global`, and Chromium has no `setImmediate` either — the message pump threw on its second message, from inside a socket callback where nothing reported it. `setTimeout(…, 0)` is the same yield. |
| `clojure.cljs` `run-jar` | `(object/create ::connecting-notifier n …)` | `n` is bound nowhere. The notifier argument has always arrived undefined and nothing reads it back; `nil` says so. |
| `clojure.cljs` ×3 | `(.write console/core-log …)` | `core-log` is a **path** in this fork, not a write stream — `console/write-to-log` is the function. Two of the three calls are in the behaviors that read the REPL process's output, so the connecting notifier threw on the first line the server printed. Nothing could ever have connected. |
| `clojure.cljs` `plugin-compile-results` | `string/lower_case` | not a function. The name is `lower-case`, so building a ClojureScript plugin from the editor threw. |
| `clojure.cljs` `clj-watch-result` | `console/util-inspect` | no longer exists; `console/inspect` is core's, and takes only the value. |
| `clojure.cljs` `notify` | `(deploy/deploy)` when the jar is missing | `lt.objs.deploy/deploy` is gone, and re-downloading the editor was never the right answer. There is no jar now, so what it checks for is Leiningen. |
| `clojure.cljs` `run-jar`, `check-java`, `check-ltjar`, `jar-command`, `windows-escape` | started `java -jar` | replaced by `run-lein`, `check-lein`, `check-middleware` and `lein-args` — see **The nREPL server** above. Nothing assembles a command line as a string any more, so the Windows quoting went with it. |
| `clojure.cljs` `::java-exe` behavior | named the JVM to start the server with | `::lein-exe`, naming the Leiningen. Which JVM that uses is Leiningen's business. |
| `clojure.cljs`, `clojure/nrepl.cljs` | forward references | both namespaces are written in call order. Added `declare`, as Paredit needed. |
| `clojure.cljs`, `clojure/nrepl.cljs` | `shell`, `bencode` | `^js` on the def, so externs inference does not warn. The build's bar is zero warnings. |

Five of those are bugs upstream ships and an unrebuilt artifact hides, which
is the argument for compiling plugins from source in one paragraph. The
`core-log` one is the argument for *running* it: it compiles perfectly and
guarantees that nothing connects.

**Treat this plugin as provisional.** Its evaluation path predates the
modernization by a decade and overlaps with clojure-lsp; it may be rewritten or
dropped rather than maintained.
