# Rendering, and adding a renderer beside the one that is here

Light Table's UI is built with **singultus**, a vendored fork of crate — hiccup
in, DOM out, plus a fine-grained binding layer (`bound`, `bound-coll`,
`map-bound`, `subatom`) that writes into a node when an atom changes. 560 lines,
in `src/singultus/`, unmaintained upstream. 116 `defui`, 67 `bound` and 27
`subatom` across 37 namespaces in core, and a further 11 and 3 in the bundled
plugins.

It is also part of the plugin API: `lt.macros/defui` and `defpartial` compile to
singultus calls, and published plugins use them. So singultus stays in the
bundle whatever else happens. The question is only what *new* UI is written in.

## The seam

`lt.object/->dom`:

```clojure
(if (vector? content)
  (crate/html content)
  content)
```

An object's `:init` returns its content. Hiccup goes through singultus;
anything else is already a DOM node and is used as it is. So an `:init` that
returns what Replicant, React or `document.createElement` produced works today,
with no change to `lt.object` — in the create path and in the redefinition one
both, since they share this function.

That is the whole integration. It is two lines and it is easy to mistake for
defensive coding, which is why `test-e2e/renderer.spec.ts` exists.

## The lifecycle is already the mount and unmount

A renderer needs somewhere to subscribe and somewhere to let go. Both are the
object:

| | |
|---|---|
| mount | `object/create` calls `:init`, and `:content` is what it returned |
| unmount | `object/destroy!` raises `:destroy`, drops the instance, removes the node |
| replace | redefining the type rebuilds every live instance and swaps the node |

This was measured rather than assumed. Opening six files and closing them,
three times over, returns the object count, the editor count, the tab-label
count and the watch count on the tabset's atom to exactly where they started.
`bound` adds a watch and destroying the object is what ends it, so the binding
layer does not leak — which means the object model is a foundation to render
onto rather than a second thing to replace at the same time.

## Two things a new renderer must not do

**Do not let it own the CodeMirror subtree.** An editor's content is a
CodeMirror instance that manages its own DOM, holds the selection, the undo
history and the mode state. A diffing renderer that thinks it owns those nodes
will throw them away on the next render. The editor object hands back the node
CodeMirror made; a component may put that node somewhere, and must not describe
what is inside it.

**Do not mutate a managed node imperatively.** There are about 40 `dom/css`,
`dom/add-class` and `dom/remove-class` calls in core. Under singultus they are
correct: nothing re-renders behind them. Under a renderer that diffs, a class
set that way is gone the next time the component renders. New components should
put it in the data they render from.

## Where the stylesheets stand

`structure.css` decides where things are, `skins/new-dark.css` decides what
colour they are, and `themes/*.css` are CodeMirror syntax themes. That
separation is the intent and it is not what is in the files: the skin carries
about 141 layout declarations.

It has been left that way on purpose. Splitting it means editing a hundred
rules for markup that a new UI is about to replace, and moving a declaration
from the skin to `structure.css` lowers its precedence, so anywhere the skin
was deliberately overriding would flip. New components are better off with new
stylesheets than with a tidied version of these.

`script/style-snapshot.mts` is the tool for either job: it records the computed
style of every element in the window and compares two runs, so a change that
was supposed to change nothing can be shown to have changed nothing — and a new
skin can be diffed against the old one element by element. See
[testing.md](testing.md).
