# Light Table

Light Table is a code editor that connects you to your creation with instant
feedback. It is highly customizable and can display anything a
[Chromium browser](https://www.chromium.org/) can.

> **This is a development branch.** It is not as stable as `master`, and it is
> undergoing modernization: the runtime, the dependencies and the build have all
> moved. What changed and why is recorded in
> [CHANGELOG-MODERNIZATION.md](CHANGELOG-MODERNIZATION.md).
>
> The previous README, which describes the project as it stood in 2021, is kept
> at [doc/README-2021.md](doc/README-2021.md). Several of the services it linked
> to no longer exist.

## Current state

| | |
|---|---|
| Runtime | Electron 43 — Chromium 150, Node 24 |
| Language | ClojureScript 1.12, Clojure 1.12 |
| Build | [shadow-cljs](https://github.com/thheller/shadow-cljs), plus `tsc` for everything that is not ClojureScript |
| Toolchain | JDK 25 (JDK 21 also works), Node 24 |
| Window | `contextIsolation: true`, `nodeIntegration: false` — the window reaches the desktop only through the capability list in `src-electron/preload.ts` |

## Building

You need a JDK and Node. The JDK is for shadow-cljs, which is the ClojureScript
compiler and runs on the JVM; nothing in Light Table itself does. Leiningen is
needed only to regenerate the API docs.

```sh
brew install --cask temurin   # Eclipse Adoptium's OpenJDK build; any JDK 21+ works
brew install node             # or fnm / nvm / volta — .node-version pins it
```

Nothing in the build is platform-specific; macOS, Linux and Windows-under-Cygwin
all go through the same scripts.

```sh
make deps                  # dependencies, the Electron binary and ripgrep
make build                 # builds everything, packages
make run                   # run what you just built, from the tree
```

Two binaries are fetched rather than committed, both pinned to a version and
checked against a published `sha256`: Electron, and the **ripgrep** that
project-wide search runs. Neither is in the repository — source in the
repository, binaries fetched at build time. If ripgrep is missing, search still
works through the tree walk it used before and says so in its summary line;
`make bench-search` is what says how much that costs.

`make help` lists the rest. The Makefile is a wrapper: the npm scripts and
`script/*.sh` stay the source of truth, and CI runs those.

`make dist` also produces a release archive — a `.app` on macOS, a directory on
Linux. Packaging is the least exercised part of this repository and CI does not
cover it, so `make run` is the path to prefer while developing: it launches the
same editor from `deploy/core` without the bundling, renaming and code-signing
step in between.

The first build downloads Electron, which is about 300MB and takes a while.
Subsequent builds reuse it.

To rebuild just the ClojureScript after a source change:

```sh
make build-cljs
```

`app` is the window bundle; `worker` is the background thread that runs
searching, file walking and behavior parsing off the main thread. Both are
defined in [shadow-cljs.edn](shadow-cljs.edn). The default user plugin is a
module of `app`, so it compiles against the editor rather than shipping as a
checked-in artifact; the script puts it where the plugin loader looks.

Light Table's own JavaScript is all TypeScript, in four roots — one per place
code runs, because each has a different set of globals:

| root | runs in | built by |
|---|---|---|
| `src-electron/` | the main process and the preload | `npm run build:main` |
| `src-window/` | the editor window | `npm run build:window` |
| `src-worker/` | the worker thread | `npm run build:worker` |
| `src-browser/` | a page Light Table has connected to | `npm run build:browser` |

`npm run build:ts` builds all four at once — they are siblings, none imports
another — plus the TypeScript plugin, and `build:cljs` runs it first because
shadow-cljs consumes what `src-window/` emits. See
[doc/javascript-remaining.md](doc/javascript-remaining.md) for why the roots
are separate.

Plugins are built separately:

```sh
make build-plugins
```

**Every plugin Light Table ships with lives in this repository**, in
[`plugins/`](plugins/README.md), and is built from source against the editor it
extends. Nothing is cloned at build time, there is no binary in the tree, and
the only thing downloaded is each plugin's own npm dependencies.

Clojure evaluation needs [Leiningen](https://leiningen.org/#install) on your
`PATH`. Light Table starts a `cider-nrepl` REPL in your project through it, the
same way every other Clojure editor does. It used to carry its own copy of both
Leiningen and the middleware; the first was from 2015 and does not start on a
current JDK, and the second is a maintained library now — see
[plugins/Clojure/VENDORED.md](plugins/Clojure/VENDORED.md).

`plugins/TypeScript` is the worked example, and does something real: it finds
the tsconfig governing the open file, runs that project's own compiler, and
reports the diagnostics to Light Table's console. See
[doc/language-support.md](doc/language-support.md) for what language support
looks like beyond that, and
[doc/lsp-architecture.md](doc/lsp-architecture.md) for the language server
client that grew out of it.

The window does not use Electron's modules directly. Everything it can ask the
desktop for is a named capability exposed by the preload script, and
`lt.util.bridge` is the only namespace that reaches it — see
[doc/electron-guide.md](doc/electron-guide.md), and
[doc/context-isolation.md](doc/context-isolation.md) for how the window came to
be isolated and what it cost.

## Testing

```sh
make test    # unit tests, under node
make smoke   # boots the real application and checks it
make check   # lint and type-check
```

The smoke test matters more than its size suggests. Almost nothing in Light
Table can be loaded in isolation — `lt.object`, `lt.objs.files` and
`lt.objs.menu` all reach for Electron or the DOM while loading — and the
failures this codebase actually produces are load-time and cross-process ones: a
namespace throwing as it loads, an Electron API that no longer exists, an ipc
channel with nobody listening, a worker that cannot reply. None of those are
reachable from unit tests, so the smoke test drives the assembled application
instead.

Both run in [CI](.github/workflows/build.yml) on every push.

To ask the running editor a question rather than boot one per question:

```sh
script/lt-repl.sh start
script/lt-repl.sh cljs "files/cwd"
script/lt-repl.sh eval "cljs.core.count(cljs.core.deref(lt.object.behaviors))"
script/lt-repl.sh eval -t 60000 "..."       # longer than the 15s default
script/lt-repl.sh shot builds/shot.png 2    # the window as it stands
script/lt-repl.sh stop
```

It attaches over the DevTools protocol to the real application, evaluates with a
timeout so a hang reports as one, and munges ClojureScript names — `files/cwd`
reaches `lt.objs.files.cwd` — outside string literals.

Every evaluation gets an `LT` object holding the things that go wrong by hand:

| | |
|---|---|
| `LT.wrap(obj, name, f)` / `LT.unwrap()` | replace a function *without* breaking it. A ClojureScript function of several arities compiles to a dispatcher with the real bodies hanging off it as properties, and internal callers go straight to those — so a plain wrapper breaks every caller, and the damage outlives the probe |
| `LT.watchErrors()` → `LT.errors` | what behaviors threw. `lt.object` catches exceptions inside reactions, so a behavior that throws is indistinguishable from one that decided not to act |
| `LT.until(test, ms)`, `LT.sleep(ms)` | wait for a state rather than for a duration |
| `LT.editor(path)`, `LT.get(obj, "ns/key")`, `LT.open(path)`, `LT.kw(name)` | the lookups every probe starts with |
| `LT.openIn(path, n)`, `LT.tabsets()` | arrange a split. Opening a file puts it in the *active* tabset, and opening one that is already open focuses it where it already is — so a second pane is filled by moving a tab, not by opening one |

`script/screenshot.sh <file>...` is the other one: it boots its own instance and
writes a PNG per file, which is right for a fixed list and wrong for a window
you have spent a dozen evaluations arranging. That is what `shot` is for.

## Documentation

**[doc/](doc/README.md)** is the index, and it says which pages are current and
which predate this work. The ones to start with:

* [Behaviors, Objects and Tags](doc/BOT.md) — the model everything here is built on
* [Workflow](doc/workflow.md) — a typical session
* [The Electron layer](doc/electron-guide.md) and
  [the road to context isolation](doc/context-isolation.md) — how the window
  reaches the desktop, and why it reaches it that way

`docs.lighttable.com` is no longer up, and the community
[wiki](https://github.com/LightTable/LightTable/wiki) and
[plugin list](https://github.com/LightTable/plugin-metadata) still live on the
upstream repository.

The [API reference](doc/api/README.md) for the namespaces plugins are written
against is generated from the source docstrings and committed, so it reads on
GitHub and is published with the rest of `doc/`. After changing a docstring in
one of those namespaces:

```sh
make docs
```

`make check` fails if it is stale, which is what stops it drifting.

## Plugins

Light Table's plugin system can extend or replace almost any part of the editor,
and the community has published [over a hundred](https://github.com/LightTable/plugin-metadata)
— language eval support, domain-specific IDEs, and more. To write one, see
[Write a Plugin](http://docs.lighttable.com/#write-a-plugin), and for an example
in ClojureScript see
[LightTable-Declassifier](https://github.com/LightTable/LightTable-Declassifier).

Published plugins ship as precompiled JavaScript and are not rebuilt when Light
Table changes, so a renamed namespace in the editor surfaces as an error in a
user's session rather than as a failing build. `lt.compat` keeps published
plugins working across such renames; see the modernization changelog for what is
currently shimmed and what has broken.

Plugins in [`plugins/`](plugins/README.md) do not have that problem: they are
built from source against the editor, in TypeScript or ClojureScript, so a
rename is a compile error. Compiling the five that used to be fetched as
published artifacts found eleven bugs in them, several of which had made part
of the plugin silently useless — each one's `VENDORED.md` has the list.

That directory also documents the capability manifest — what a plugin declares
it needs — and the three levels of enforcement it is a path towards.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) and
[For Developers](https://github.com/LightTable/LightTable/wiki/For-Developers).
Light Table is written in [ClojureScript](https://clojurescript.org); if it is
new to you, [David Nolen's tutorial](https://github.com/swannodette/lt-cljs-tutorial)
is a good start.

## License

All files in this project are under the [MIT license](LICENSE.md) unless
otherwise stated in the file or by a dependency's license file. There used to be
an exception — `src/singultus`, a vendored EPL fork of `crate` — and there is
not any more: Replicant replaced it and the directory is gone. Every plugin in
`plugins/` carries its own `LICENSE.md`, and all of them are MIT.

## Credits

Thanks to all our [contributors](https://github.com/LightTable/LightTable/graphs/contributors),
to Kodowa for everything they did for Light Table, and to Cognitect for
supporting one of the core team members.
