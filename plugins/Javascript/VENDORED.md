# Javascript, in this repository

Copied from [LightTable/Javascript](https://github.com/LightTable/Javascript)
at version **0.2.0**, under its own MIT licence (`LICENSE.md`), which is not
this project's.

Upstream shipped `javascript_compiled.js` and its source map as checked-in
build artifacts that nothing rebuilt. Here the ClojureScript is a module of the
`:app` build, so it compiles against the editor it extends.

## What was left behind

| | |
|---|---|
| `javascript_compiled.js`, `.js.map` | build output; rebuilt from `src/` |
| `project.clj`, `plugin.json` | upstream's build and manifest; `plugin.edn` says the same and can carry `:capabilities`, which JSON cannot express as a set |
| `codemirror/javascript.js` | CodeMirror 4.8.0's mode. Core bundles a current one — see `script/gen-codemirror-requires.mts` — so this was an old mode overwriting a new one at load. |
| `node_modules/` | see below |
| `CHANGELOG.md`, `CONTRIBUTING.md` | upstream process, not this plugin |

`node/ltnodeclient.js` is kept. It is the plugin's own hand-written JavaScript,
run by `node` in the child process rather than in the window, so it is source.

## The vendored node_modules

Upstream committed `node_modules/` with `acorn@0.9` and `harbor@0.2` in it.
Here they are ordinary dependencies in `package.json`, installed at build time
by `script/install-plugin-deps.mts` into a gitignored `node_modules/`, at
`acorn@8` and `harbor@0.3`. Nothing in the source had to change: both are
reached through `lt.objs.plugins/local-module`, which resolves relative to the
plugin's own directory, and `lt.objs.plugins.local-modules` implements Node's
resolution over the bridge.

Installing a third-party package at build time is not the "code fetched at
build time" the repository rules out. What is ruled out is cloning a *plugin's*
repository; a dependency from npm with a committed lockfile is a dependency.

acorn 8 is a decade newer than 0.9 and the plugin's one call —
`parse(code, {locations, ecmaVersion, allowReturnOutsideFunction})` — is
unchanged in it. `harbor` still requires `events`, `util` and `net`, all three
of which `lt.objs.plugins.node-modules` serves.

## Changes to the source

| where | was | why |
|---|---|---|
| `js.cljs` `on-eval.one` | `(catch js/global.Error e …)` | there is no `global` in the window under `contextIsolation`, so the catch matched nothing. |
| `js.cljs` connect-to-browser | `ws/port` | `lt.objs.clients.ws` reads the port from the running server now, so it is `(ws/->port)`. The old var compiled to `undefined`, and the script tag it built named port `undefined`. |
| `js/node.cljs` `connect!`, `load-tools` | `tcp/port` | same, `(tcp/->port)`. The node client was told to call back on `undefined`. |
| `js/node.cljs` `changelive!` | `(send this msg)` | `send` takes three arguments. Editing a live script threw. |
| `js.cljs` | forward reference to `js-lang` | added `declare`. |
| `js.cljs` | `(.-a.b.c x)` property chains | rewritten as `(.. x -a -b -c)` with `^js` hints, so externs inference does not warn. The build's bar is zero warnings. |

Three of those are bugs upstream ships, and two of them — the two `port` vars —
are the kind that only appear once the editor around the plugin moves, which is
the argument for compiling plugins from source rather than shipping artifacts.

**Treat this plugin as provisional.** `lt.plugins.js.node` drives the V8 debug
protocol that Node removed in version 12, so that half cannot work against any
supported Node and has not been exercised. It is vendored as it stands rather
than rewritten; the eval-in-browser half is the part that still does something.
