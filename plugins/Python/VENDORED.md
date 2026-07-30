# Python, in this repository

Copied from [LightTable/Python](https://github.com/LightTable/Python) at version
**0.0.7**, under its own MIT licence (`LICENSE.md`), which is not this project's.

Upstream shipped `python_compiled.js` and its source map as checked-in build
artifacts that nothing rebuilt. Here the ClojureScript is a module of the `:app`
build, so it compiles against the editor it extends.

## What was left behind

| | |
|---|---|
| `python_compiled.js`, `.js.map` | build output; rebuilt from `src/` |
| `project.clj`, `plugin.json` | upstream's build and manifest; `plugin.edn` says the same and can carry `:capabilities`, which JSON cannot express as a set |
| `codemirror/python.js` | core bundles a current `python` mode now — see `script/gen-codemirror-requires.js` — so the copy was an old mode overwriting a new one at load |
| `CHANGELOG.md`, `CONTRIBUTING.md` | upstream process, not this plugin |

`py-src/` is kept. It is the plugin's own Python — the client Light Table starts
and talks to over tcp — so it is source rather than an artifact.

## Changes to the source

| where | was | why |
|---|---|---|
| `python.cljs` `run-py` | `tcp/port` | `lt.objs.clients.tcp` reads the port from the running server now, so it is `(tcp/->port)`. The old var compiled to `undefined`, so the Python client was told to call back on port `undefined` and every connection attempt failed. |
| `python.cljs` | forward references to `python` and `try-connect` | added `declare`. |

The `tcp/port` one is a bug that only appears once the editor around the plugin
moves, which is the argument for compiling plugins from source.

**Treat this plugin as provisional.** Its client targets Python 2-era IPython
and has not been exercised against a current interpreter.
