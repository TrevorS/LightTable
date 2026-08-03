# The kit in a browser

`make storybook` opens the component kit on <http://localhost:6106>, rendered
by the same ClojureScript the editor renders, outside the editor.

This is module 1 of five. What exists now is the path end to end — a
ClojureScript build, a bridge, a story, and a check that the story actually
draws — with one component in it. The modules after this one are listed at the
bottom.

## Why, when there is already a catalogue

[`lt.ui.catalogue`](../src/lt/ui/catalogue.cljs) draws twenty-five components
from the live alias registry, inside Light Table, and
`test-e2e/catalogue.spec.ts` fails if a registered alias is missing from it.
That is worth more than a picture of a component, and it is not what this
replaces.

The two answer different questions, and the split is clean:

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

**The stories.** ClojureScript, in the registry. Storybook's indexer needs one
file per component to build its sidebar, so those files exist — but they are
generated from the registry rather than written, which is module 2. Until then
`.storybook/stories/chrome.stories.js` is the shape the generator will emit.

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
2. **ClojureScript as a first-class story format.** A `defstory` that writes
   into the registry from the namespace the component lives in, and
   `script/gen-stories.mts` generating the `.stories.js` files from it —
   committed and guarded by `make check`, the same arrangement `doc/api`
   already has.
3. **The pure kit.** Every state of the thirty aliases in chrome, row, band and
   host.
4. **The five that reach the editor.** `lt.ui.pane` and `lt.ui.kit`, and the
   composed views in `lt.ui.view`. This is a design question, not a typing
   exercise — see above on stubs.
5. **Controls, themes, and the gate.** Args from the same prop tables the
   catalogue lists, light and dark, and a check that every registered alias has
   at least one story — which is what `test-e2e/catalogue.spec.ts` already does
   for the catalogue.
