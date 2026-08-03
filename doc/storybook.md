# The kit in a browser

`make storybook` opens the component kit on <http://localhost:6106>, rendered
by the same ClojureScript the editor renders, outside the editor.

All five modules are done. **91 stories over 26 components**, written in
ClojureScript, drawn by the same registry the in-editor catalogue draws from.
`make storybook-check` proves every one of them renders.

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

**The stories.** ClojureScript, in `lt.ui.stories.*` — one namespace per
component namespace, beside the components rather than inside them — `lt.ui.chrome` is eighteen aliases and nothing else
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

Twenty-six of the twenty-seven aliases require nothing but Replicant:

| namespace | aliases | requires |
|---|---|---|
| `lt.ui.chrome` | 17 | Replicant |
| `lt.ui.band` | 6 | Replicant, chrome |
| `lt.ui.row` | 2 | Replicant |
| `lt.ui.host` | 1 | Replicant |
| `lt.ui.pane` | 1 | the object world — pool, opener, files |
| `lt.ui.kit` | 0 | it manipulates the registry rather than adding to it |

*(Earlier versions of this page said thirty-five aliases, thirty of them pure.
That came from `grep -c defalias`, which also counts the `:refer-macros
[defalias]` line in every namespace that uses it.)*

So this build has no Electron in it, no bridge, and no stubs. That is a
property worth keeping rather than an accident of where we started: **a stub is
a second implementation of the thing under test**, and it is how a component
passes in a gallery and fails in the product.

`lt.ui.pane/pane` is the one that would need one, and module 4's finding was
that it should not have it. Everything worth seeing about a pane is the
CodeMirror inside it, so a version here would be a picture of a text area; the
catalogue already draws it with a live editor. It is registered with a
description and an `:excluded` reason instead, which is the difference between
*nobody wrote stories for this* and *this one is drawn somewhere better* — and
the completeness check reads that field to tell them apart.

## A build is not a render

`script/check-stories.mts` serves the static build, opens every story through
the same `iframe.html` the sidebar loads, and asserts the root drew something
and the console stayed quiet. `make storybook-check` does both halves.

It exists because a Storybook build succeeds whether or not a single component
drew anything: a story whose render throws shows an empty frame and a green
build, which is the same shape of silence this project has paid for elsewhere
more than once.

The check earned itself immediately, twice. The first hand-written story passed
`{:tone :ok}` to `chrome/status-dot`, which takes `:status` — an alias silently
ignores an attribute it does not destructure, so it rendered the *default*
state under a story named for a different one. It drew, so the check passed,
and it was still wrong. That is the argument for module 2 in one sentence: a
story's props have to come from the same place the component's props do.

It needs Chromium, which Playwright does not install for this repository —
the end-to-end suite drives Electron, which brings its own. `npx playwright
install chromium` is a one-time ~95MB download. Whether CI pays for it is a
module 5 question.

## What the check catches

Three things, and each has caught something real.

**A story that draws nothing.** A Storybook build succeeds whether or not a
component rendered; a story whose render throws shows an empty frame and a
green build.

**A console error.** This is how `chrome/tab`'s closable state was found: it
carries `:on-close [[:tab/close 0 4]]`, and nothing in this bundle had taught
Replicant that a handler may be data — so it threw inside Replicant's own
render, which catches it, logs *"you may have misbehaving aliases"*, and skips
the render. That is the exact trap `lt.actions/install!` exists for in the
editor, met again three commits after it was written down. `lt.ui.storybook`
now installs a dispatch that **logs** the action rather than running it: there
is no state atom here and no effects, and what a story can honestly show is
which action a gesture emits.

**A component nobody described.** The alias list comes from Replicant's own
registry rather than from the story registry, so a component with no `story/of`
shows up as zero states rather than not at all — the same completeness argument
`test-e2e/catalogue.spec.ts` makes for the catalogue.

It is not in `make check`, deliberately: that script exists so a lint error
does not cost a ClojureScript build to discover, and this needs a cljs compile,
a Vite build and a browser. `make storybook-check` is its own thing.

## What the light skin found

The point of a skin toolbar is not a light theme — Light Table does not ship
one and this is not it. It is a question you can only ask by changing the
ground: **does this component name a role, or a colour?**

It found ten literal colours outside `:root` in `kit.css`, eight of them role
colours written out by hand. `.chip--selected` spelled `rgba(137, 220, 235,
0.15)`, which is the exact value of `--lt-element-selected`, three lines away.
The tints in `:root` were literals too — `--lt-agent-tint` wrote out mauve's
channels rather than naming `--lt-agent`.

They derive now:

```css
--lt-agent-tint: color-mix(in srgb, var(--lt-agent) 10%, transparent);
```

Which is what makes a skin work at all: swap `--lt-text` and every tint built
on it follows, where a hand-written one stays the colour it was compiled at.
Measured on the two that do follow — `--lt-element-hover` goes from a light
tint on dark to a dark tint on light, and `--lt-element-selected` correctly
does not move, because sky is an accent rather than a ground role.

Two literals are left and both are deliberate: a drop shadow, which is black
everywhere, and `.band__frame`, which is white because what is inside it is
somebody else's HTML and HTML with no stylesheet expects a white page.

`script/kit-colours.mts` is what made that safe to do. It records what all 91
stories paint and compares a later run against it, so "this refactor changes no
colours" is checked rather than claimed. It is a tool for a change like this
one rather than a standing check, which is why it takes a baseline file instead
of living in `make storybook-check`.

**It found its own bug first, which is the useful part of the story.** The
first comparison reported 44 of 91 stories changed, and every one was
`rgba(243, 139, 168, 0.1)` becoming `color(srgb 0.952941 0.545098 0.658824 /
0.1)` — the same colour, because 243/255 is 0.952941, spelled the way
`color-mix` computes. Comparing strings was the bug. It compares painted pixels
now.

The same trap was sitting in `test-e2e/renderer.spec.ts`, which asserted that
literal string. It compares the element against the token now, both resolved
through one probe, which is what the test was always trying to say.

## The modules, and what they turned into

1. **The harness** — the build, the bridge, one component, the check.
2. **ClojureScript as a first-class story format** — and, unplanned, the
   unification: the catalogue reads the same registry, so a component is
   described once.
3. **The pure kit** — 91 stories over 26 components, and 24 of the catalogue's
   hand-written cards replaced by `told-card`. The catalogue lost 255 lines and
   gained nothing it did not have.
4. **The one that reaches the editor** — a finding rather than a build. See
   above.
5. **Themes and the gate** — a skin toolbar, and the completeness check. The
   light skin overrides only the role variables, so a component that looks
   wrong on it has hard-coded a colour somewhere it should have named a role.
   Light Table ships no light theme; this one is a test instrument and lives
   beside the stories rather than in `deploy/core/css`.
