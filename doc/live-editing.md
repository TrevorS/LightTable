# Changing the editor while it runs

The editor compiles ClojureScript inside itself. Put the cursor in a form,
evaluate it, and the editor you are typing in changes — not a copy of it, not
one that restarts afterwards.

This is the feature Light Table is named for, and for most of this fork's life
it did not work. What follows is how it works now, what was in the way, and the
two lines of build configuration the whole thing rests on.

## The two lines

```clojure
;; shadow-cljs.edn, the :app build
:optimizations  :simple
:output-wrapper false
```

Closure's `:simple` renames local variables and leaves properties alone, and no
output wrapper means there is no closure to hide them inside. So in the shipped
bundle `lt.objs.editor.__GT_val` is a live global, and any JavaScript naming it
reaches the running editor's own function. The editor's whole API is
addressable at runtime.

That is not an accident of the build to be optimized away later. It is the
build carrying the feature, and anything that changes those lines — `:advanced`,
a wrapper, a bundler that scopes the output — turns the editor back into
something you can only restart.

## Three languages, one client

[`lt.objs.clients.local`](../src/lt/objs/clients/local.cljs) is a client like
any other in Light Table, except that the thing at the far end is this window.
Connect it from the Connect bar as `Light Table UI`.

| buffer | what happens |
|---|---|
| `.js` | `js/eval`, straight into the window |
| `.css` | a `<style>` element, replaced by name, so re-evaluating restyles rather than accumulates |
| `.cljs` | compiled by [`lt.objs.cljs-compiler`](../src/lt/objs/cljs_compiler.cljs), then evaluated |

There is a fourth message, `:editor.eval.cljs.exec`, which is the same door
from the other side: JavaScript that something *else* compiled, which is how
the browser clients deliver ClojureScript. Both routes end in the same
`js/eval`.

## The compiler

ClojureScript is compiled in the window, by the ClojureScript compiler
compiled into the window. No JVM, no project, no jack-in, nothing to install —
which matters, because the alternative makes the editor's own primary feature
depend on a toolchain that someone editing their `user.behaviors` has no other
reason to own.

A compiler resolving `lt.objs.editor/->val` needs to know that var exists, what
arities it takes and how its name munges. That knowledge is the *analysis*, and
shadow-cljs's `:target :bootstrap` writes it out per namespace:

```
deploy/core/lighttable/cljs-cache/ana/<ns>.transit.json
```

What the compiler must **not** do is load those namespaces' JavaScript. It is
already loaded. Running it again would re-run every top-level `def` and replace
the atoms the editor is holding — the object graph, the open editors, the
client list — with empty ones, while the old ones stayed on screen. The editor
would still be drawn and nothing would work.

Shadow already draws that line, which is the reason to use its loader rather
than write one. A build requiring `shadow.cljs.bootstrap.browser` is a
*bootstrap host*, and shadow appends to every module:

```js
shadow.cljs.bootstrap.env.set_loaded(["lt.core", "lt.objs.editor", …]);
```

The loader reads that, fetches analysis for those namespaces, and skips their
code. The split is shadow's design, not ours.

## Which namespace a form evaluates in

The `ns` form at the top of the file, when the compiler knows it, and
`cljs.user` when it does not. A namespace it does not know is usually
someone's own plugin, and evaluating its `ns` form is what teaches the compiler
about it — which is why [workflow.md](workflow.md) has always said to evaluate
the `ns` form first. That is now the literal mechanism rather than folklore.

## Which client a `.cljs` file gets

Evaluating ClojureScript in your own project should reach your project's REPL,
not Light Table's window. `lt.plugins.clojure/connect-cljs` decides, and it
decides from the file rather than from a setting:

- a `plugin.edn` or `plugin.json` above it — a Light Table plugin
- a `project.clj` naming `lighttable` — the old layout, still read
- a namespace starting with `lt.` — Light Table's own source, which no longer
  has a `project.clj` to be recognised by

Any of those evaluates into the window. Everything else goes to the project's
REPL, by the same route Clojure takes. `:lt.plugins.clojure/set-default-cljs-client`
overrides it.

## Building it

`npm run build:cljs` builds the analysis cache after the app, because the
prune step below reads what the app says it provides:

```
shadow-cljs release app worker bootstrap && node script/prune-cljs-cache.mts
```

`:target :bootstrap` emits three directories and the window reads one and a
half of them — `ana/` entirely, `js/` only for namespaces the window does not
already have, and `src/` never. Left alone that is 16MB shipped to read 4MB of,
so [`script/prune-cljs-cache.mts`](../script/prune-cljs-cache.mts) deletes the
rest. It works out which by reading the same `set_loaded` call the loader
reads, so the two cannot disagree about what is dead.

## What was verified, in a packaged release

Against `builds/LightTable-*/LightTable.app` rather than the tree, because
`:simple`, the wrapper and the asset paths are all things a release could get
wrong on its own:

| | |
|---|---|
| `lt.*` still global after `:simple` | yes |
| `.js` buffer → eval → statusbar changed | yes |
| `.js` buffer → eval → new command defined, then ran | yes |
| `.css` buffer → eval → editor restyled live | yes |
| `.cljs` buffer → eval → four forms, four inline results | yes |
| `.cljs` buffer → eval → `def`, then a command using it | yes |

The command is the one that matters. It did not exist; a buffer was evaluated;
it existed and ran. The editor gained a capability it shipped without, while
running, from a file open in it.

## Two things that bit, and what they looked like

**An `ns` form is not an expression.** `cljs.js` with `:context :expr` wraps
what it compiles in a `return`, and an `ns` form emits `goog.provide` and
`goog.require` statements — so the result was `SyntaxError: Unexpected
identifier 'lt'` beside the `ns` form while every form after it worked, because
the requires had been established anyway. `ns` forms compile as statements.

**Closure rewrites long array literals.** shadow appends `set_loaded([...])`
as JSON, and Closure turns a long array literal into `"a b c".split(" ")`. The
prune script matched only the array form, pruned 7 files instead of 296, and
reported success. Both forms are read now.

## Not for a packaged release?

It is, deliberately. The earlier note here said a REPL against the editor
needed a `shadow-cljs watch app` build and should not exist in a shipped app.
That is true of shadow's own `nrepl-select` route — a release carries no
devtools client — and false of this one, which never attaches a REPL to the
window at all. It compiles in the window and evaluates in the window, and needs
nothing outside it.

See also [Evaluating ClojureScript](clojurescript-eval.md), which measured the
nREPL routes against real servers and is how the shape of this was decided.
