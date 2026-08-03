# The kit in a browser

`make storybook` opens the component kit on <http://localhost:6106>, rendered
by the same ClojureScript the editor renders, outside the editor.

Modules 1 and 2 of five are done: the path end to end, and stories as
ClojureScript that the in-editor catalogue reads too. One component is
described so far. The rest are listed at the bottom.

## One description, two surfaces

[`lt.ui.catalogue`](../src/lt/ui/catalogue.cljs) draws components from the live
alias registry, inside Light Table, and `test-e2e/catalogue.spec.ts` fails if a
registered alias is missing from it. That is worth more than a picture of a
component, and it is not what this replaces.

It did, briefly, mean two answers to *what states does this component have?* —
a hand-written card here and a story registry over there. Two is the number
that goes stale. So [`lt.ui.story`](../src/lt/ui/story.cljs) is the one both
read: `story/of` carries the sentence, the prop table, the usage line and every
state, the catalogue's `told-card` builds a card from it, and the Storybook
generator builds files from it. Neither surface owns the description.

What is left is genuinely different, and the split is clean:

| | catalogue | Storybook |
|---|---|---|
| where it draws | in the editor, in the window it belongs to | any browser, no Electron |
| what it shows | the states someone wrote down | those, plus whatever a control produces |
| swap a component live | `kit/redefine!`, everywhere at once | no |
| show it to someone | they run Light Table | a URL |
| viewports, themes, a11y | no | addons do it |

Both read from the same alias registry, which is what stops them disagreeing.
A component is a keyword in `replicant.alias`, and neither of these has its own
copy of what that keyword draws.

## The shape

Four pieces, and only one of them is JavaScript.

**The build.** `shadow-cljs.edn` gains a `:storybook` build, `:target :esm`.
ESM rather than `:browser` because the consumer is Vite, which wants real
modules with real exports; one `import` in a story file is the whole
integration. It emits `.storybook/generated/kit.js`, which is a build output
and is not committed.

**The bridge.** [`lt.ui.storybook`](../src/lt/ui/storybook.cljs) holds the story
registry and exports three functions: `render!`, `ids` and `describe`. That is
the entire surface. Storybook's html renderer takes a DOM node back from a
story, and Replicant renders hiccup into a DOM node, so there is no adapter and
no wrapper component — `render!` makes an element, renders into it, and hands
it over.

A fresh element every call, deliberately. Replicant reconciles against what it
last rendered into a given node, and Storybook remounts a story whenever a
control changes; handing back a node it has already discarded gives you the
previous story's DOM with the new story's diff applied to it.

**The stories.** ClojureScript, in `lt.ui.stories.*`, beside the components
rather than inside them — `lt.ui.chrome` is eighteen aliases and nothing else
and it stays that way.

A state is **props, not markup**:

```clojure
(story/of ::chrome/status-dot
  {:doc    "One indicator for all seven execution states plus connection health."
   :props  [[":status" "one of the eight" "what it is doing now"]]
   :usage  "band/result · view/statusbar · every titlebar"
   :states (array-map :queued {:status :queued}
                      :hollow {:status :finished :hollow true})})
```

Props because that is what a control varies, and because an alias silently
ignores an attribute it does not destructure — a state written as hiccup can
pass a prop the component does not take, draw the default, and look fine. That
is not hypothetical; see below. `:hiccup` is still available for a state that
is a small composition rather than one call.

Naming the alias is the other half: `::chrome/status-dot` is a keyword the
compiler resolves, so renaming a component breaks its stories at compile time.
That is the argument for writing these in ClojureScript rather than in the
JavaScript that has to exist anyway.

**The generated files.** Storybook's indexer needs one file per component, so
`script/gen-stories.mts` writes them from the registry — via a `stories-manifest`
node build that prints it as JSON, because asking a registry a question should
not need a browser.

They are **not committed**, which is a change from what this page said in module
1. The comparison then was `doc/api`: generated, committed, guarded by `make
check`. But `doc/api` is committed because people read it on GitHub and its diff
says what changed about the promised API. Nobody reads these. A generated file
nobody reads, committed, is something that can go stale plus a check that has to
exist to notice; generated into a gitignored directory by the same command that
runs Storybook, it cannot.

**The ground.** `.storybook/preview.js` imports `deploy/core/css/reset.css` and
`kit.css` — the real stylesheets, not a copy. A component drawn against a copy
of the token sheet agrees with the copy; the reason to have a gallery is that
it disagrees with you when the product would.

## Nothing here reaches the editor

Thirty of the thirty-five aliases require nothing but Replicant:

| namespace | aliases | requires |
|---|---|---|
| `lt.ui.chrome` | 18 | Replicant |
| `lt.ui.band` | 7 | Replicant, chrome |
| `lt.ui.row` | 3 | Replicant |
| `lt.ui.host` | 2 | Replicant |
| `lt.ui.kit` | 2 | `lt.state`, `lt.objs.command` |
| `lt.ui.pane` | 2 | the object world — pool, opener, files |

So this build has no Electron in it, no bridge, and no stubs. That is a
property worth keeping rather than an accident of where we started: **a stub is
a second implementation of the thing under test**, and it is how a component
passes in a gallery and fails in the product. The last five are module 4, and
the question there is what to do about that rather than how to write the stub.

## A build is not a render

`script/check-stories.mts` serves the static build, opens every story through
the same `iframe.html` the sidebar loads, and asserts the root drew something
and the console stayed quiet. `make storybook-check` does both halves.

It exists because a Storybook build succeeds whether or not a single component
drew anything: a story whose render throws shows an empty frame and a green
build, which is the same shape of silence this project has paid for elsewhere
more than once.

The check earned itself immediately. The first hand-written story passed
`{:tone :ok}` to `chrome/status-dot`, which takes `:status` — an alias silently
ignores an attribute it does not destructure, so it rendered the *default*
state under a story named for a different one. It drew, so the check passed,
and it was still wrong. That is the argument for module 2 in one sentence: a
story's props have to come from the same place the component's props do.

It needs Chromium, which Playwright does not install for this repository —
the end-to-end suite drives Electron, which brings its own. `npx playwright
install chromium` is a one-time ~95MB download. Whether CI pays for it is a
module 5 question.

## The modules

1. **The harness** — done. The build, the bridge, one component, the check.
2. **ClojureScript as a first-class story format** — done, and it grew a second
   half on the way: the catalogue reads the same registry, so a component is
   described once. `chrome/status-dot` is the first card told that way.
3. **The pure kit.** Every state of the thirty aliases in chrome, row, band and
   host, and the rest of the catalogue's cards converted to `told-card`.
4. **The five that reach the editor.** `lt.ui.pane` and `lt.ui.kit`, and the
   composed views in `lt.ui.view`. This is a design question, not a typing
   exercise — see above on stubs.
5. **Controls, themes, and the gate.** Args from the same prop tables the
   catalogue lists, light and dark, and a check that every registered alias has
   at least one story — which is what `test-e2e/catalogue.spec.ts` already does
   for the catalogue.
