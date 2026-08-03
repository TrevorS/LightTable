# Grammars built here

Tree-sitter grammars that npm does not publish a usable `.wasm` for, built from
source and committed. Everything else comes from a package under
`deploy/core/node_modules` and is not here — see
`lt.objs.editor.treesitter/grammars` for which is which.

| | why it is here |
|---|---|
| `tree-sitter-clojure.wasm` | `tree-sitter-clojure` ships no `.wasm`, and its committed `parser.c` is ABI 9 where the current runtime wants 13–15 |
| `queries/clojure-highlights.scm` | no package ships Clojure highlight queries at all |
| `tree-sitter-scss.wasm` | `tree-sitter-scss` ships no `.wasm` — it publishes prebuilt *native* bindings only, which this editor cannot load |
| `queries/scss-highlights.scm` | it comes from that same package, which is not a dependency for want of a `.wasm` |

## Rebuilding

The tree-sitter CLI downloads its own wasi-sdk, so this needs neither docker nor
emscripten — just network access the first time.

```sh
npm pack tree-sitter-clojure && tar xzf tree-sitter-clojure-*.tgz
cd package
npx tree-sitter-cli@0.26.11 generate     # regenerate: the shipped parser.c is ABI 9
npx tree-sitter-cli@0.26.11 build --wasm
cp tree-sitter-clojure.wasm ../../deploy/core/grammars/
```

`generate` is not optional **for Clojure**. Building the committed `parser.c`
produces a grammar the runtime rejects with *"Incompatible language version 9"*,
which is a confusing error a long way from its cause.

SCSS is the other way round: its committed `parser.c` is already ABI 14, and
`generate` **cannot** be run — `grammar.js` extends `tree-sitter-css/grammar`,
and the published versions of the two packages no longer fit together
(`Cannot read properties of undefined (reading 'name')`). So build from what
ships:

```sh
npm pack tree-sitter-scss && tar xzf tree-sitter-scss-*.tgz
cd package
npx tree-sitter-cli@0.26.11 build --wasm     # no generate: parser.c is ABI 14
cp tree-sitter-scss.wasm ../../deploy/core/grammars/
cp queries/highlights.scm ../../deploy/core/grammars/queries/scss-highlights.scm
```

If the tree-sitter CLI's own install script has been blocked — this repo sets
`ignore-scripts`, and npm's `allowScripts` policy blocks it globally — the
`tree-sitter` binary will be missing and `npx` fails with `ENOENT`. Run
`node node_modules/tree-sitter-cli/install.js` to fetch it.

## The query

`queries/clojure-highlights.scm` is Light Table's own, written against the
grammar's node names rather than adapted from another editor. The grammar is
unusually rich for a Lisp — it gives `defn` a node with `function_name`,
`docstring` and `params` as named children — so the query can distinguish
things no per-line mode can see.

Two orderings in it are load-bearing, because later captures win:

- `(list . (symbol) @function.call)` comes *before* the special-form rule, so
  `if` and `let` end up keywords rather than calls. Clojure has no reserved
  words, so spelling and position are the only things that tell them apart.
- The broad `(symbol) @variable` comes first, so everything more specific
  overrides it.

Verified against `src/lt/objs/notifos.cljs`: 286 captures across 14 distinct
names.
