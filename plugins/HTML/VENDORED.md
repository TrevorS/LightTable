# HTML, in this repository

Copied from [LightTable/HTML](https://github.com/LightTable/HTML) at version
**0.1.0**, under its own MIT licence (`LICENSE.md`), which is not this project's.

Upstream shipped `html_compiled.js` and its source map as checked-in build
artifacts that nothing rebuilt. Here the ClojureScript is a module of the `:app`
build, so it compiles against the editor it extends.

## What was left behind

| | |
|---|---|
| `html_compiled.js`, `.js.map` | build output; rebuilt from `src/` |
| `project.clj`, `plugin.json` | upstream's build and manifest; `plugin.edn` says the same and can carry `:capabilities`, which JSON cannot express as a set |
| `codemirror/htmlmixed.js and codemirror/htmlembedded.js` | core bundles current `htmlmixed` and `htmlembedded` modes now — see `script/gen-codemirror-requires.mts` — so the copy was an old mode overwriting a new one at load |
| `CHANGELOG.md`, `CONTRIBUTING.md` | upstream process, not this plugin |

## Changes to the source

| `html.cljs` | forward reference to `html-lang` | added `declare`. |

**Treat this plugin as provisional.** It predates the modernization by a decade
and may be rewritten or dropped rather than maintained.
