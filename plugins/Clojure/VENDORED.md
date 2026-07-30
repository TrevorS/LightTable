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
| `project.clj`, `build.sh`, `.travis.yml` | upstream's own build, replaced by `shadow-cljs.edn` and `script/fetch-clojure-jar.js` |
| `codemirror/clojure.js` | see below |
| `runner/target/lein-light-standalone.jar` | a binary; fetched at build time, see below |
| `CHANGELOG.md`, `CONTRIBUTING.md` | upstream process, not this plugin |

`lein-light-nrepl/` and `runner/` are kept. They are the Clojure sources of the
nREPL server, and `runner/resources/project.clj` is read at runtime when Light
Table starts a REPL with no project around it.

## The CodeMirror mode

Dropped. Upstream's `clojure.behaviors` loaded `codemirror/clojure.js` ahead of
the plugin, from the days when `lt.objs.editor/mode-blacklist` stopped the
editor bundling a mode any plugin shipped. That list is gone —
`script/gen-codemirror-requires.js` says why — and Light Table now bundles all
131 of CodeMirror's modes, including a current `clojure`. Keeping the copy meant
a decade-old mode overwriting a fresh one at load, which is worse than nothing.
Light Table also has tree-sitter highlighting for Clojure on top of that.

## The nREPL jar

`runner/target/lein-light-standalone.jar` is 15MB of compiled Java and Clojure.
It is a runtime artifact — `lt.plugins.clojure/jar-path` resolves it beside the
plugin and starts it with `java -jar` — so it is neither source to keep nor
build output to produce, and the repository commits neither.

`script/fetch-clojure-jar.js` downloads it from the pinned 0.3.3 tag and checks
its SHA-256, which is the escape hatch the policy allows for binaries. It runs
as part of `npm run build:cljs` and `npm run build:plugins`, and is a checksum
verification rather than a download once the file is there.

**Future work:** build it from `runner/` with Leiningen. Both halves' sources
are vendored here, so what is missing is a JVM build step and a decision about
where Leiningen comes from. That would retire the exception.

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
| `clojure.cljs` `plugin-compile-results` | `string/lower_case` | not a function. The name is `lower-case`, so building a ClojureScript plugin from the editor threw. |
| `clojure.cljs` `clj-watch-result` | `console/util-inspect` | no longer exists; `console/inspect` is core's, and takes only the value. |
| `clojure.cljs` `notify` | `(deploy/deploy)` when the jar is missing | `lt.objs.deploy/deploy` is gone, and re-downloading the editor was never the right answer. The jar is a build-time fetch now, so a missing one is reported as one. |
| `clojure.cljs`, `clojure/nrepl.cljs` | forward references | both namespaces are written in call order. Added `declare`, as Paredit needed. |
| `clojure.cljs`, `clojure/nrepl.cljs` | `shell`, `bencode` | `^js` on the def, so externs inference does not warn. The build's bar is zero warnings. |

Four of those are bugs upstream ships and an unrebuilt artifact hides, which is
the argument for compiling plugins from source in one paragraph.

**Treat this plugin as provisional.** Its evaluation path predates the
modernization by a decade and overlaps with clojure-lsp; it may be rewritten or
dropped rather than maintained.
