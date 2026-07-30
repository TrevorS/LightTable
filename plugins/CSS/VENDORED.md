# CSS, in this repository

Copied from [LightTable/CSS](https://github.com/LightTable/CSS) at version
**0.0.6**, under its own MIT licence (`LICENSE.md`), which is not this project's.

Upstream shipped `css_compiled.js` and its source map as checked-in build
artifacts that nothing rebuilt. Here the ClojureScript is a module of the `:app`
build, so it compiles against the editor it extends.

## What was left behind

| | |
|---|---|
| `css_compiled.js`, `.js.map` | build output; rebuilt from `src/` |
| `project.clj`, `plugin.json` | upstream's build and manifest; `plugin.edn` says the same and can carry `:capabilities`, which JSON cannot express as a set |
| `codemirror/css.js` | core bundles a `css` mode now — see `script/gen-codemirror-requires.js` — so the copy was an old mode overwriting a new one at load |
| `CHANGELOG.md`, `CONTRIBUTING.md` | upstream process, not this plugin |

## Changes to the source

| `css.cljs` | forward reference to `css-lang` | added `declare`. |

**Treat this plugin as provisional.** It predates the modernization by a decade
and may be rewritten or dropped rather than maintained.
