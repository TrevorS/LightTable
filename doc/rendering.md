# Rendering, and adding a renderer beside the one that is here

Light Table's UI is built with **singultus**, a vendored fork of crate — hiccup
in, DOM out, plus a fine-grained binding layer (`bound`, `bound-coll`,
`map-bound`, `subatom`) that writes into a node when an atom changes. 560 lines,
in `src/singultus/`, unmaintained upstream. 116 `defui`, 67 `bound` and 27
`subatom` across 37 namespaces in core, and a further 11 and 3 in the bundled
plugins.

It was also the plugin API — `lt.macros/defui` and `defpartial` compile to
singultus calls — and for most of this migration that meant singultus stays in
the bundle whatever else happens, because deleting it breaks every published
plugin that draws.

**That is no longer a constraint.** This fork is one person's editor. The only
plugins that matter are the ones in this repository, and those are ported as
first-class citizens rather than supported as guests. So singultus is not a
permanent tenant; it is the thing 22 remaining files still use, and the last
one to stop using it is the commit that deletes it.

What that changes in practice: a file being touched for another reason should
go to a view rather than being tidied in place, and `defui` is not a signature
to preserve. What it does not change: the order. The chrome is worth converting
because the design is worth having; the plugin manager's twelve `defui` are
worth converting because they are the last twelve.

## What is where, today

| | |
|---|---|
| singultus | everything, minus the list below. 63 `defui` across 22 files, from 116 across 37 |
| Replicant | the tab strip, the statusbar, the workspace tree, the connect panel, the welcome screen, the component kit, and the window as a view |

The swap is one object at a time and the two render side by side in the same
document, which is what makes it safe to do gradually rather than as one
change nobody can review.

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
three times over, returns the object count, the editor count, the number of
tabs drawn and the watch count on the tabset's atom to exactly where they
started.
`bound` adds a watch and destroying the object is what ends it, so the binding
layer does not leak — which means the object model is a foundation to render
onto rather than a second thing to replace at the same time.

## Rendering an object with Replicant

[[lt.ui/node]] takes the object, the root element as static hiccup, and a view:

```clojure
(defn- probe-ui [this]
  [:div.inner {:class (when (:busy @this) "working")}
   (count (:items @this))])

(object/object* ::probe
                :init (fn [this] (ui/node this [:div.probe] probe-ui)))
```

The root is separate from the view because the rest of the editor holds a
reference to it — `object/->content` hands it out, the statusbar appends it, a
tabset moves it, `dom/css` writes to it. Replicant is given that node as its
container and owns everything inside, so nothing is wrapped and the document
keeps the shape every stylesheet is written against.

The obvious alternative fails, and fails quietly: render into a detached holder
and hand out its first child, and the moment anything moves that node it is no
longer the holder's child, so the next render patches nothing and the panel
stops updating. That is what `test-e2e/renderer.spec.ts` asserts against by
setting a status message twice.

[[lt.ui/state-node]] is the same thing for a view: the node belongs to an
object, but what it draws from is state the object does not own, and it may be
more than one atom. The statusbar is both — the strip is an object, because the
tabs above it size themselves against it, and the bar inside it is a function
of `lt.state/app` and `lt.state/cursor`.

The view re-runs on every change to the object. `bound` was finer than that —
it wrote one attribute when one path changed — so a view over an object that
changes on every keystroke is worth measuring before converting.

## What cannot be swapped one-for-one

**Anything composing another object's content.** `map-bound` over a collection
of objects, splicing `(object/->content %)` — the right bar's remaining panels,
and the *content* area of a tabset. Replicant renders hiccup, and a DOM node
another object owns is not hiccup. Those stay on singultus until the thing they
are composing is a view rather than an object with a node.

The way out is not to swap them, and four surfaces have now gone the other way
instead. The statusbar's three items — a cursor, a loader, a console toggle —
were deleted rather than converted, and `lt.ui.view/statusbar` draws the whole
bar from the state. The workspace tree was an object per file and an object per
folder, 677 lines and 28 behaviors, and is now `[:workspace :nodes]` in the
state: a map from path to what is known about that path, drawn by
`lt.ui.view/workspace`.

The connect panel is the third and the cheapest, because the view was already
written: `lt.ui.view/connections` is one of the design's eight and had been
tested from a map since the kit landed, drawn only in the window-as-a-view tab.
The panel is that function over `lt.state.objects/clients*`, and `:bound?` —
whether the buffer you are in evaluates through a client — is read from the
editor rather than stored, so the claim it makes cannot go stale the way a flag
can.

The tab strip is the fourth, and it is the clearest case of the two halves
coming apart. A tabset draws a strip and a content area: the strip was a `<ul>`
of `::tab-label` objects, one object with one node per tab per tabset, and the
content area hosts each tab object's own DOM. Only the strip moved.
`lt.ui.view/titlebar` draws it per tabset from the projection; the content area
is untouched, because hosting foreign DOM is what it is for.

Two things went with it. `objs-list` destroyed every label and rebuilt the whole
`<ul>` — reattaching the drag-and-drop wiring each time — whenever anything
about the tabset changed, including a dirty flag; a view patches. And
`src-window/dragdrop.ts`, 146 lines of sortable that moved list items and then
read the new tab order back out of the document, is deleted: picking a tab up
is `[:tab/drag-start ts i]` and putting it down is `[:tab/drop ts j]`, so
reordering is a change to `:objs` and the strip follows.

All four deleted more than they moved, and all of them left the container
behind. The statusbar strip is still an object because the find bar is in it
too and the tabs above give back its height; the workspace and connect panels
are still objects because `lt.objs.sidebar` holds their nodes; a tabset is
still an object because it owns a column of the window and the editors in it.
What used to be `object/merge!` into an item is an action in each case, so what
those surfaces show is asserted by folding actions over a map.

The tree is the one worth reading twice, because it is where the shape paid.
Every folder was a `ul` whose closed state was `display:none`, so opening a
directory of four hundred files created four hundred objects with four hundred
nodes and four hundred watches, and closing it kept all of them. Flat, a closed
folder is one that is not descended into — it is not on screen because it is
not drawn, and what was read stays in the map, so reopening it asks the disk
nothing.

That is most of the composition in the editor, and it is the reason this is a
migration rather than a swap.

## The kit

[`lt.ui.row`](../src/lt/ui/row.cljs), [`lt.ui.chrome`](../src/lt/ui/chrome.cljs)
and [`lt.ui.band`](../src/lt/ui/band.cljs) are the twenty-five components the
design draws, as Replicant aliases. They sort into three kinds, and the sort is
the architecture:

| | | |
|---|---|---|
| alias | 17, in `row` and `chrome` | markup, no state, no data access |
| band | 6, in `band` | the same, but rendered into a node the editor owns |
| view | 9, in `view` | a function of the whole state, composing aliases |

Only views read state, so every question about correctness is a question about
however many views there are. Nine, and four of them — the tab strip, the
statusbar, the workspace tree and the connect panel — are on screen in the
editor you are using. The rest of the
chrome is still objects, which is the honest state of the migration.

**Light Table: Component kit** opens
[the catalogue](../src/lt/ui/catalogue.cljs): every component, in every state
it has, rendered from the same aliases the editor uses. That is the point of
it. A picture of a component that is not the component goes stale, and this
one cannot.

No component names a colour. It names a role, and
[`deploy/core/css/kit.css`](../css/kit.css) resolves it as a CSS custom
property — which is the design's fifth open question answered the way it
suggested, so a flavour swap is a variable list rather than a sweep through
the markup. The palette is Catppuccin Mocha.

## The views

[`lt.ui.view`](../src/lt/ui/view.cljs) is the eight the design names — titlebar,
review queue, connections, sidebar, statusbar, command bar, multibuffer,
settings — plus `workspace`, which it does not: the document draws the chrome
around a run and takes the file tree as given. `window` composes them. Each is `(defn view [state] hiccup)`
over the *whole* state, so the slicing happens in the open rather than in a
subscription nobody can see.

The reason to do it this way is not tidiness, and it shows up as a test file.
A view has nowhere to keep a secret, so `test/lt/ui/view_test.cljs` asks a map
and reads hiccup back: which tab is active, whether a conflicted edit is toned
`:warning`, that the statusbar counts unapplied edits and not applied ones,
that an empty queue says what would fill it. Milliseconds, no editor, no DOM.

**Light Table: Window as a view** opens it, rendering from the live state
beside the real chrome. Moving a surface there is moving its root, which is the
point of having exactly one — and [`lt.objs.statusbar`](../src/lt/objs/statusbar.cljs)
and [`lt.objs.sidebar.workspace`](../src/lt/objs/sidebar/workspace.cljs) are
that done twice, [`lt.objs.sidebar.clients`](../src/lt/objs/sidebar/clients.cljs)
a third time and [`lt.objs.tabs`](../src/lt/objs/tabs.cljs) a fourth. The strip
across the top of the real editor, the bar along its bottom and the tree down
its left side are `view/titlebar`, `view/statusbar` and `view/workspace` — the
same functions this tab draws and the same ones `test/lt/ui/view_test.cljs`
asks with a map.

## Where the state comes from

[`lt.state.objects`](../src/lt/state/objects.cljs) projects the running editor
into the atom: open tabs, editors and their dirty flags, clients, the cursor,
LSP diagnostics as results keyed `[path line]`. It reads and never writes, so
a wrong view is either the view's fault or the projection's and never a third
copy that drifted.

It is scaffolding and says so. Each surface that becomes a view deletes part of
it, and when the last one goes the namespace is the diff that removes it.

The projection is why `titlebar` appends runs to the tab list. In the design's
state a run is simply one of `:tabs`, because that list is authoritative; here
it comes from objects that know nothing about runs. One line, and it goes when
tabs are state.

## Handlers are data

```clojure
[::row/list-row {:on-select [[:review/goto i]]} …]
```

A vector, not a closure. [`lt.actions`](../src/lt/actions.cljs) is the table it
dispatches through, and `lt.actions/install!` is what teaches Replicant that a
handler may be a value — without it the vector is silently not a function and
nothing happens.

The claim worth stating plainly: this is the same design
`deploy/settings/default/default.behaviors` has had since 2013, arriving from
the other direction. That file is `[tag behavior-keyword]` pairs merged from
every plugin on load. An action table is `[kind & args]` vectors resolved
through a registry. Both are a UI whose wiring is data you can read, and the
two registries can eventually be one.

What it buys immediately: an action is a pure function of state and arguments,
so what a click does is asserted by calling it. `test/lt/actions_test.cljs`
tests them without a window, a DOM, or a click.

An action that changes nothing about the state still has to be registered —
`lt.actions/register-passthrough!` is for those, and it exists because
forgetting it is invisible. `dispatch!` resolves actions, so an effect that no
action asks for is an `:error/unknown-action` reported into a console nobody is
reading, and the button appears to work. That is what the tree's whole
right-click menu did for an afternoon. `test-e2e/renderer.spec.ts` now walks
what the views emit and checks the table covers it, rather than listing what it
covers.

## The boundary: bands live in DOM Replicant does not own

An inline result is not beside the code, it is *between two lines of it* — so
its parent is a node CodeMirror created and reflows. Put the editor inside the
chrome tree and one of two things breaks: Replicant diffs away the editor's own
DOM, or the bands sit outside the state model and stop being declarative.

[`lt.ui.bands`](../src/lt/ui/bands.cljs) is the answer the design gives:
**N+1 render roots.** One for the chrome, one per visible band, into the widget
node the editor hands us. Both are ordinary Replicant renders of ordinary
hiccup; the second just has a foreign parent.

`:lt.ui.band/result` is therefore the same alias in the catalogue, in this
document, and inside a live buffer. The impurity is `ensure-widget!` and
`retire-orphans!` — two functions, one file — and the second exists because a
band is never *un*-rendered: nothing renders it any more, which is a different
thing, so what left the state has to be taken off the screen explicitly.

Editor instances are not in the state atom. They are not data.

## Three clocks, not one

Section 06 of the design names four places top-down rendering from one atom
strains. All four are handled, and three of them the same way — by admitting
that a surface has its own clock:

| | |
|---|---|
| the cursor | `lt.state/cursor`, observed by the statusbar's own root. Mirroring keystrokes into the main atom is a window render per keypress |
| a watch at 60fps | `lt.state/watch-values`, observed by the watch bands' roots. `app` holds only that the watch exists |
| the multibuffer | a window of 12 excerpts either side of the review cursor, keyed `[path start-line]`, with what is hidden shown as a count |
| a value that is not data | the band dispatches on `:mime` and hands off — `text/html` is a sandboxed frame, an image is an element, and neither is described as hiccup |

The design already draws watches in a distinct colour; here that distinction is
also a rendering boundary.

## The kit is data

The design's fourth question was whether the seventeen aliases should have been
plain functions. Aliases, and [`lt.ui.kit`](../src/lt/ui/kit.cljs) is the
argument: a plain function is reachable only by code that calls it, while an
alias is a keyword in a registry — so the set of components is a *value*, and
one can be replaced without touching a view.

```clojure
(kit/redefine! :lt.ui.row/list-row (fn [attrs body] [:div.row.row--fancy body]))
```

Every row is that on the next draw, and nothing that draws a row was
recompiled. It is the same claim as `[tag behavior-keyword]` in
`default.behaviors`: what the editor is made of is a table, and a table can be
edited from inside the thing it describes.

One thing to know: replacing an alias produces **identical hiccup** — the
keyword did not change, only what it expands to — so a diffing renderer
correctly does nothing. `lt.ui/redraw-all!` unmounts each root and rebuilds it.
That is why redefining is explicit rather than watched.

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
