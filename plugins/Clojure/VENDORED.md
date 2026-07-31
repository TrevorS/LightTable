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
`script/gen-codemirror-requires.mts` says why — and Light Table now bundles all
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
REPL through **the user's own Leiningen** — the same jack-in every other
Clojure editor performs, which is why a project does not have to know anything
about Light Table to be opened in it. `lt.plugins.clojure/lein-args` builds the
command line, and **The middleware** below says what goes on it.

Using the Leiningen the user already has means the REPL tracks their JDK and
their project rather than a 2015 snapshot of both. The cost is that Leiningen
has to be installed; the plugin says so, with a link, rather than failing
silently the way the jar did.

## The middleware: bought, not built

`lein-light-nrepl/` is gone. It was 1,192 lines of Clojure that reimplemented
completion on clojure-complete from 2013, documentation lookup, stacktrace
formatting and a ClojureScript compiler driver, and served them over nREPL
operations only Light Table understood.

All of that is somebody's maintained library:

| was ours | lines | is now |
|---|---|---|
| `auto_complete.clj` | 72 | **compliment**, through cider-nrepl's `complete` |
| `doc.clj` | 142 | **orchard**, through `info` |
| `exception.clj` | 22 | **orchard**, through `analyze-last-stacktrace` |
| `cljs.clj` | 487 | nothing yet — see below |
| `eval.clj` | 226 | nREPL's own `eval`, which takes `:file`, `:line` and `:column` |
| `core.clj`, `handler.clj`, `fs.clj` | 241 | nREPL |

The REPL is a plain cider-nrepl jack-in now:

```
lein update-in :dependencies conj '[nrepl/nrepl "1.7.0"]' -- \
     update-in :plugins conj '[cider/cider-nrepl "0.62.2"]' -- \
     repl :headless
```

Two versions in `lt.plugins.clojure` and nothing else. cider-nrepl injects its
own middleware list as a Leiningen plugin, so Light Table does not have to know
what that list is or keep up with it.

**This is strictly better than what it replaced**, not merely cheaper to own.
The stacktrace is orchard's structured frame list rather than a formatted
string, and because `:file`, `:line` and `:column` go out with every form, the
frames name the file in the editor at the line in the editor. Completion is
compliment, which understands locals and context. `info` returns the defining
file and line, which is jump-to-definition for free the moment there is
somewhere to put it.

## What stayed Light Table's

Buying the intelligence is not the same as buying the editor. Two things were
built here, and they are the reason this feels like Light Table rather than
like a REPL in a pane.

**A result beside each form.** `lt.plugins.clojure/forms-in` splits the buffer
into top-level forms and evaluates each on its own, so every form gets its own
inline result at its own line. That used to be the middleware's job — which
meant inline results needed a bespoke server per language, only worked with a
REPL attached, and only after a round trip. It is `lt.objs.editor.treesitter/
top-level-forms` now: the parse tree is already there, already current on every
keystroke, and knows where the forms are whether or not anything is connected.
`form-at` does the same for evaluating the form under the cursor.

**Watches.** `lighttable.nrepl.eval/watch` sent values back over a bespoke
operation. A watch now wraps the expression so it prints one tagged line, and
`lt.plugins.clojure.nrepl/split-watches` takes the tag off the `:out` the
client is already receiving. No server-side code at all, so watches work
against any nREPL — cider-nrepl or otherwise.

## What is not wired up yet

| | |
|---|---|
| ClojureScript evaluation | `cljs.clj` drove the compiler by hand. The bought answer is **piggieback** or shadow-cljs's own nREPL, and neither is wired. `:editor.eval.cljs` against a browser client is unaffected — that path never went through this middleware. |
| the instarepl | `lein-light-nrepl-instarepl`, one file, fetched from Clojars at runtime. Not vendored and not replaced. |
| jump to definition | `info` already returns `:file` and `:line`; nothing consumes them yet. |

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
