# Hygiene

What is known to be wrong, or known to be owed, in one place.

This is not a backlog of features. It is the list of things a reader would be
right to be surprised by — a claim the code makes and does not keep, a
mechanism that fails silently, a place two answers disagree. Each entry says
what it is, how it was found, and what it would cost to close.

Entries move to **Closed** with the commit that closed them rather than being
deleted, because the useful part of most of these is the reason they existed.

Found by a survey of the whole repository in seven parts — the chrome
migration's remaining surfaces, the two test layers, dead code, and the build.
Where a finding says "not worth doing", that is a decision, not an omission.

---

## Open — correctness

### `:client/bind` is a stub

`lt.actions.effects` registers `:client/bind`; its effect writes a `::client`
key on the editor that nothing reads. So clicking a connection row in the
connect panel does nothing at all, while the row's whole purpose is to say
where an evaluation goes.

Making it real means deciding what "bind" means: `(:client @ed)` is a map from
*command kind* to client, not one client per buffer. Three defensible answers —
bind every kind the target can serve, bind only the kinds already present, or
drop the affordance and leave the panel read-only with disconnect and unset in
the menu. That is a call about evaluation semantics rather than about chrome.

`:bound?` is already read truthfully from the editor, so the panel *shows* the
right thing; it is only the click that lies.

### Match highlighting is injected as unescaped HTML

`lt.objs.sidebar.command/fill-lis` sets a row's `innerHTML` from whatever
`:transform` returned, and both transforms build markup by string
concatenation: `command->display` wraps a command's description in `<p>`, and
the file navigator's builds `<h2>`/`<p>` around a path. `wrapMatch` inserts
`<em>` runs into the same string.

So any HTML-special character in a file path or a command description reaches
the DOM as markup. Nothing here is attacker-controlled today in the usual
sense, but a file named `<img onerror=…>` is a file a person can create.

Closing this falls out of converting `filter-list` to a view — `wrapMatch`
already returns match *positions*, so the highlight can be hiccup instead of a
string.

### Splits are half-projected

Each tabset draws its own strip and the actions carry the tabset id, so the
strip itself handles splits. Two things downstream still assume the first
tabset: `lt.ui.view/editor-pane` reads `(first tabsets)`, and the
window-as-a-view tab draws one strip. Neither is wrong today — a window with no
splits has exactly one — but both are silent about it.

---

## Open — claims the code does not keep

### `filter-list` is three widgets' worth of machinery with no test

`lt.objs.sidebar.command` is 526 lines, 26 behaviors, and a hand-virtualized
list: a fixed pool of `<li>` nodes repainted in place on every keystroke,
because singultus renders once and has no diffing step. It backs the command
bar, the file navigator, the syntax selector and — the load-bearing one — the
auto-complete hinter, whose candidate list is unbounded and refreshed on every
character typed.

None of the command bar, the navigator, or the pool mechanism has any test at
all. `cm6-hint.spec.ts` reaches the hinter's state machine and never touches
the rendered rows.

Two things the conversion has to preserve or deliberately redesign: `:selected`
is an index modulo the pool size rather than an item's identity, and the keymap
depends on that; and whatever the pool is doing for the hinter's performance is
exercised there and nowhere else, so that is where a replacement gets measured.

Two things scoped as risks that turned out not to be: `lt.plugins.doc` does
**not** use the widget — it borrows the `.filter-list` CSS class and has its own
hand-rolled everything — and nothing under `plugins/` constructs one, so this
is not plugin API. Only the `:filter-list.input` keymap context is documented.

### The popup's automation surface is its DOM

`lt.objs.control` — what MCP and `script/lt-repl.sh` talk to — reads pending
popups by scraping them: `dom/$$ :h2` for the header, `li.button` for the
choices, matched by `textContent`, answered with a synthetic `.click()`. It
uses none of the popup object's state.

So the popup's rendered shape is an interface, not a presentation choice. Any
conversion has to keep yielding an `h2` with the header, `li.button` elements
in order with matching text, and elements that respond to a real click by doing
what the button does.

Two call sites also compose *other objects'* DOM into a popup body
(`lt.objs.connector`, `lt.objs.editor.lsp/offer-actions!`) with a
closure-over-atom shape adopted deliberately after an object leaked. One more
embeds a live `<input>` and reaches into it after creation
(`lt.plugins.clojure`'s remote-connect).

### The bottombar is general for one consumer

`lt.objs.bottombar` keeps a sorted map of `:items` and splices the active one's
`object/->content` into its own DOM — the "composing another object's content"
shape that does not convert. Exactly one thing has ever registered: the console.
Deleting the generality is probably cheaper than solving it.

### The console's streaming path is real, the rest is incidental

`console/try-update` finds an existing `<pre id="console<id>">` and appends a
text node to it, so nREPL stdout arriving in chunks under one id accumulates in
one row instead of producing a row per chunk. That is the one genuinely
imperative-for-a-reason piece; `log`, `error` and `verbatim` are a list of
lines with a 50-item cap and convert like any other list.

### Windows packaging has no coverage

`script/build-app.sh` has real Windows-specific logic — `rcedit`, 7-Zip, the
`.exe` rename — and no CI runner produces a Windows build, so that path is
uncovered. A regression there ships to whoever builds one by hand.

### The ClojureScript warning gate is a grep

`.github/workflows/app.yml` greps `build.log` for `WARNING #`. If shadow-cljs
ever changes that line's format — a plausible thing for a minor version to do —
the grep finds nothing and the step passes green with warnings present.

---

## Open — coverage

Ranked by what a failure would cost.

| gap | where it is covered today |
|---|---|
| The browser tab and its webview | `script/smoke-test.mts` only — one script, once per run, on the surface most likely to break on an Electron bump |
| Plugin capability enforcement | smoke only for the general case; `windows.spec.ts` covers two specific historical bugs |
| The application menu | smoke only, one assertion |
| The command bar and the settings/keymap UI | nothing, at any layer |
| `contextIsolation` / no Node in the renderer | smoke only — a security-relevant invariant that would be cheap to pin as an e2e one-liner |
| The background search worker round-trip | smoke proves the worker answers; the e2e search tests go through the UI and might not exercise it |

`doc/testing.md` says checks move down from smoke to e2e as they are written.
For the webview, the menu and the worker that has not happened, and those are
whole features rather than assembly checks.

---

## Decided against

Each of these looks like an obvious improvement and is not, for a reason
specific to this repository.

**pnpm, and workspaces.** `script/build-app.sh` copies `deploy/core` with
`cp -R` and `place-plugins.mts` copies each plugin with `cpSync`, so
`node_modules` ships **verbatim** in the distributable. pnpm's default layout is
symlinks into a store outside the copied tree, so a packaged app would ship
dangling links. `node-linker=hoisted` avoids that by giving back npm's layout,
which forfeits the reason to switch. Workspace hoisting also fights the
plugin-isolation design in `install-plugin-deps.mts`, where a plugin's
dependencies are private and lockfile-pinned on purpose. Bun and Yarn Berry
share the packaging problem, and Yarn PnP also uses a resolution model
`lt.objs.plugins.local-modules` does not implement.

**tsconfig project references.** References exist to skip work when an upstream
project has not changed. None of the seven trees imports another — they are
siblings, not a stack — so there is no graph to skip over. What was actually
slow was running seven checks in sequence, now fixed by running them at once.

**esbuild or vite for the TypeScript halves.** Five of the seven configs emit
genuinely transformed output — bundler-consumable layout, `outFile`
concatenation, a downlevelled script served to a connected page — so a faster
transpiler removes none of the type-checking, which is where the cost is.

**Node's native type stripping for those five.** Same reason: they emit rather
than erase. The two check-only configs already rely on it.

**A `make`-based build system.** The `Makefile` is explicitly a wrapper, one
line deep, deferring to `package.json` and `script/`. Reimplementing logic in it
would create the second source of truth it exists to avoid.

---

## Closed

**`allowScripts` claimed a protection that was not happening** — `abe6ea3b`'s
parent. Both `package.json` files carried an `allowScripts` map; npm has no such
key, so sixteen tree-sitter grammars ran `node-gyp-build` on every install.
`deploy/core/.npmrc` with `ignore-scripts=true` now, which is safe because those
sixteen are the only packages there with an install script and Light Table reads
grammars as `.wasm` rather than through the native binding.

**`make clean` cleaned five of nine modules** — same commit. `clojure.js`,
`javascript.js`, `css.js`, `html.js` and `python.js` survived a clean.

**The plugin build ran twice** — same commit. `build:plugins` is a subsequence
of `build:cljs` and `script/build.sh` ran both; `build:window` likewise.

**Electron was pinned in two files with nothing linking them** — same commit.
`script/check-pins.mts` runs in `npm run check`.

**Seven type-checks in sequence** — same commit. `script/typecheck.mts` runs
them at once and reports every project rather than stopping at the first.

**Two leaks the shared test application made possible** — `5a4d3c6b`.
`windows.spec.ts` left second windows open and `bands.spec.ts` destroyed its
probe on a line a failing assertion would skip. Both were free when every test
had its own application.

**Eleven copies of `evalClj`** — same commit, plus two of `consoleErrors` and
six of "open a file and wait for its editor".

**Two copies of the hiccup walker** — `abe6ea3b`, now `test/lt/support/hiccup.cljs`.

**The tree's right-click menu did nothing** — `0a3fe468`. Every item dispatched
an action registered only as an effect, so each click reported
`:error/unknown-action` into a console nobody reads and appeared to work.
`register-passthrough!` is the missing half; `renderer.spec.ts` now walks what
the views emit rather than holding a list of it.

**`lt.ui.row/list-row` dropped every attribute it did not name** — `64979e12`.
Replicant merges none of an alias's call-site attrs onto what it returns, so
the workspace tree had been drawing flat.

**Two CSS blocks for the tab strip had drifted apart** — `5977a8b8`, along with
a `.dirty.ui-sortable-placeholder:after` rule naming a jQuery-UI class nothing
had produced since `dragdrop.ts` replaced it.
