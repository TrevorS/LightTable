# Hygiene

What is known to be wrong, or known to be owed, in one place.

This is not a backlog of features. It is the list of things a reader would be
right to be surprised by — a claim the code makes and does not keep, a
mechanism that fails silently, a place two answers disagree. Each entry says
what it is, how it was found, and what it would cost to close.

Entries move to **Closed** with the commit that closed them rather than being
deleted, because the useful part of most of these is the reason they existed.

Found by a survey of the whole repository in seven parts — the chrome
migration's remaining surfaces, the two test layers, dead code, and the build —
and added to since, mostly by conversions turning up what the code they touched
was actually doing. Where a finding says "not worth doing", that is a decision,
not an omission.

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

### A view that throws says `[object Object]`

Replicant catches a render exception, logs `you may have misbehaving aliases`
with the exception as a second console argument, and skips that render. What
reaches the console is five identical lines naming neither the view nor the
object, and the window looks fine because the next render is one state change
away.

`lt.ui/render-safely!` now forces the hiccup tree before handing it over, so a
lazy sequence that throws does it in a frame that names the object. That covers
the common case and not the one that caused this: the throw happened inside
Replicant's own reconcile, which no wrapper here can catch.

Worth knowing when the next one appears: it was only visible in
`script/smoke-test.mts`, because that is the only harness that leaves the
remote debugging port on, so a devtools client forwards the window's own
console into Light Table's. `page.addInitScript` with a `console.error` wrapper
is what found it.

### "Language server ready" is about whichever one finished first

`::on-ready` says it on every `:lsp.ready`, and nothing ties the message to the
editor you are looking at. On this repository a probe found the statusbar
already reporting it while the connection attached to the open editor had
`initialized? false` and no capabilities at all.

Found chasing "toggle docs isn't working", which `063aa943` fixed at the other
end: the commands that a person runs on purpose now say when the server is
still starting. That makes the truth visible where it matters and leaves this
message still capable of lying. Closing it means naming the server, or saying
it per editor rather than per connection.

### A doc over an existing underline result orphans the old node

`lt.plugins.doc/inline-doc` does `object/update! this [:widgets] assoc [line
:underline]` without raising `:clear!` on whatever was already at that line —
where `lt.objs.eval/::underline-results`, the other writer of that key, does.
So a Python plot followed by a doc on the same line leaves the plot on screen
with nothing holding it.

Found writing the copy test in `test-e2e/inline-results.spec.ts`, which is why
that test opens a file per case. Narrow: it needs two different producers on
one line.

### Splits are half-projected

Each tabset draws its own strip and the actions carry the tabset id, so the
strip itself handles splits. Two things downstream still assume the first
tabset: `lt.ui.view/editor-pane` reads `(first tabsets)`, and the
window-as-a-view tab draws one strip. Neither is wrong today — a window with no
splits has exactly one — but both are silent about it.

---

## Open — claims the code does not keep

### The hinter's rendering cost is unmeasured

`lt.ui.filter` replaced the `<li>` pool with ordinary diffing, which is right
for the command bar and the navigator — lists that change when you type, a few
hundred rows at most. The auto-complete hinter is the one that was actually
being optimised for: its candidate list is unbounded and it refreshes on every
character. Nothing has measured the new one under a large buffer with a
language server attached, and `cm6-hint.spec.ts` asserts state rather than
render time.

### The popup's choices are still read out of its DOM

Half closed by `560d49bc`. The popup keeps its options now, so
`lt.objs.control` reads the header from the object — the comment saying
`(:header @p)` is empty for every popup there is has gone with it.

The *choices* are still a DOM read, and now for a reason rather than by
default: three callers put their options in the body rather than in `:buttons`
— which client should evaluate this, which code action to run — because there
can be many and they are the question rather than a confirmation. A reader that
trusted `:buttons` would offer "cancel" and nothing else for exactly the
prompts where the choice matters.

What that leaves as an interface is narrower but real: `li.button` elements in
order, with matching `textContent`, that respond to a real `.click()`. Asserted
in `test-e2e/renderer.spec.ts`.

### The bottombar is general for one consumer

`lt.objs.bottombar` keeps a sorted map of `:items` and splices the active one's
`object/->content` into its own DOM — the "composing another object's content"
shape that does not convert. Exactly one thing has ever registered: the console.
Deleting the generality is probably cheaper than solving it.

### The console's streaming path is real, the rest is incidental

`console/try-update` finds an existing `<pre id="console<id>">` and appends a
text node to it, so nREPL stdout arriving in chunks under one id accumulates in
one row instead of producing a row per chunk. That is the one genuinely
imperative-for-a-reason piece.

`baae8ffe` took the rest as far as it goes without answering this: every line is
built by `lt.ui/element` and the list is still appended to, dropped from and
scrolled imperatively, because that is what append-only means. Making the
console a view of a value means holding the last fifty lines *as* a value, and
the streaming append is the thing that would have to change.

### Windows packaging has no coverage

`script/build-app.sh` has real Windows-specific logic — `rcedit`, 7-Zip, the
`.exe` rename — and no CI runner produces a Windows build, so that path is
uncovered. A regression there ships to whoever builds one by hand.

### `lt.objs.editor` still documents an engine that is gone

79 vars, and ten of them have no caller anywhere in the repository: `->mode`,
`add-gutter`, `remove-gutter`, `char-coords`, `get-history`, `set-history`,
`lh->line`, `set-doc!`, `set-line` and `on-click`. Each works — the CM6 shim in
`src-window/cm6-editor.ts` implements what they call — and each has a docstring
linking to `codemirror.net/doc/manual.html`, which is CodeMirror 5's manual.

Left alone deliberately. This is the plugin-facing API, `doc/api` is generated
from it, and a function that works and is documented costs nothing to keep;
what it costs is a reader believing the links. The four that were *also* broken
are gone — see Closed.

### Runs, reviews and agent edits are designed and not produced

`lt.state/initial` has `:runs` and `:review`. `lt.actions` registers
`:review/goto`, `:edit/apply` and `:run/grant` against them; `lt.ui.bands`
draws bands from `:runs`; `lt.ui.view` draws a review list and an excerpt
header with three origins; `lt.ui.chrome` and `lt.ui.row` draw `:origin :run`;
`kit.css` has `.band--agent`, `.chip--agent`, `.pill--agent`, `.row--agent` and
`.excerpt--agent`. Nothing writes a run. `:behavior/rebind` writes a `:keymap`
key that `initial` does not have either.

This is forward design rather than rot — `lt.ui.catalogue` draws all of it from
literals, which is what a component kit is for, and `doc/direction.md` is about
where the editor is going. Recorded because a reader who greps for what creates
a run will not find it, and should not have to conclude the projection is
broken.

---

## Open — coverage

Ranked by what a failure would cost.

| gap | where it is covered today |
|---|---|
| The browser tab and its webview | `test-e2e/browser-tab.spec.ts` covers the controls, the absent `preload`, and the webview surviving a redraw; smoke covers it end to end with a real page. What is still uncovered is the devtools connection, which needs the remote debugging port the e2e fixture turns off |
| Plugin capability enforcement | smoke only for the general case; `windows.spec.ts` covers two specific historical bugs |
| The application menu | smoke only, one assertion |
| The settings and keymap UI | nothing, at any layer |
| The docs sidebar, the plugin manager and the object inspector | `doc-sidebar.spec.ts`, `plugin-manager.spec.ts` and `inspector.spec.ts` cover what each draws and what changes it. Each was written with the conversion off `defui`; none existed before |
| `contextIsolation` / no Node in the renderer | smoke only — a security-relevant invariant that would be cheap to pin as an e2e one-liner |
| The background search worker round-trip | smoke proves the worker answers; the e2e search tests go through the UI and might not exercise it |
| Dragging a grip to resize a panel | `renderer.spec.ts` asserts each grip exists, is in the right parent and is `draggable`, and drives `:width!` directly — Playwright does not synthesise HTML5 drag, so the browser's half is unproven |

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

**Keeping `defui` as a stable plugin API.** Decided the other way, 2026-08-01:
this fork is one person's editor, the only plugins that matter are the ones in
this repository, and those get ported rather than supported. So singultus is
not permanent. It is gone: `src/singultus/`, `lt.macros/defui` and `lt.compat`
were all deleted once the last call site was, from 116 `defui` across 37 files
when doc/rendering.md was written. A `defui` reimplemented on Replicant to keep
the signature would have been work to preserve a signature nobody outside this
repository uses.

**A `host` alias for splicing another object's DOM into hiccup.** Decided
against on 2026-08-02 for having no caller — the three files it was proposed
for keep their bound roots either way — and built later the same day when the
command bar needed it, with three callers waiting. The decision was right when
it was made and the record of it is more useful than a tidy one: what those
files actually shared was the opposite gap, the root's own class and style,
which is `lt.ui/node`'s `attrs`. `lt.ui.host` exists now because a panel whose
content is two other objects' DOM cannot be a view without it.

**A `make`-based build system.** The `Makefile` is explicitly a wrapper, one
line deep, deferring to `package.json` and `script/`. Reimplementing logic in it
would create the second source of truth it exists to avoid.

---

## Closed

**Toggle docs did nothing in ClojureScript files** — the fourth report, and
the same two dead ends as the second, in the twin nobody looked at. `838f8446`
fixed `::clj-doc` and `::print-clj-doc`; `::cljs-doc` and `::print-cljs-doc`
are line-for-line the same behaviors for the other language and kept both:
a `when token` that skipped silently, and `(if-not result …)` on a value whose
key had just been read, so the branch could not fire.

The test is why it survived. `lsp-doc.spec.ts` drives those behaviors *by name*
— the plugin's behaviors hang off `:editor.clj.*` and the fixture file is
TypeScript — and it named the two that had been fixed. It runs over both
languages now, which is the fix that generalises.

Reproduced before it was believed, and the reproduction found a third failure
underneath: with a REPL claiming `:doc`, `widgets` was empty, nothing was
drawn, and the error ring held `Cannot read properties of null (reading
'substring')` from `lt.plugins.doc/retrieve-behavior`. An answer with every
field nil reached `:editor.doc.show!`, which looks a doc with no `:file` and no
`:doc` up as one of Light Table's own behaviors — `(subs nil 2)`. So the
silence was a throw, inside a behavior, into a console nobody reads. `retrieve`
is guarded, and a nameless doc has its own assertion.

Meanwhile clojure-lsp was connected, ready and indexed, and stood down because
a REPL had taken the surface. The statusbar said "Language server ready", which
is the open entry above about that message being true of whichever connection
finished first.

**The collapsible exception was a whole feature nothing could reach** — wired
on 2026-08-02. An object, a view and eight lines of `:collapsible.exception`
tag config, all downstream of `::expandable-exceptions`, which listens for
`:editor.exception.collapsible` — and the only two behaviors that raise it were
absent from `clojure.behaviors`. Both of their triggers were taken by
`lt.plugins.clojure/clj-exception` and `::cljs-exception`, which raise the
generic `:editor.exception` instead, so the older implementation answered and
the newer one was unreachable. Found by cross-checking every `(behavior ::…)`
in the repository against every name in a `.behaviors` file.

Wiring it was not the two-line swap the entry predicted, because a path nothing
runs drifts from the one beside it. Two divergences from
`lt.objs.eval/::inline-exceptions`, both real:

*No guard on the line.* `::inline-exceptions` checks `(>= (:line loc) 0)`
because the line is `(dec (:end-line meta))` and nREPL reports no end line for
an exception it cannot place — a reader error, a form sent without position.
`(dec nil)` is -1 in ClojureScript rather than an error. The guard here is
`integer?` as well, because `(>= nil 0)` compiles to `null >= 0`, which is
*true* — so a bounds check alone is not one.

*Cleared one of the two widget kinds.* It cleared `[line :inline]` and not
`[line :underline]`, which is the open entry above about a doc orphaning an
underline result, in a second place. An exception landing on a line that
already had a result would have left it on screen with nothing holding it.

Also `::cljs-expandable-exception` passed the whole stack as the *summary*
whenever there was one, and `.truncated` is `nowrap` with `overflow:hidden` —
so it drew as one very long clipped line. The view truncates now, which is what
the class is called and what the "..." promises.

Three tests in `test-e2e/inline-results.spec.ts`, and the negative control was
run: with the wiring reverted, two of the three fail. The third is the guard,
which only the collapsible path needed. One of them asserts the widget's height
grows when it expands rather than only that the class flipped — CodeMirror 5
wanted a `:changed` raise for that and CodeMirror 6 does not, and this is what
says so.

**`make clean` removed none of the nine modules on Linux.** It had been fixed
once already — the list named five of nine — and the fix was written
`rm -f deploy/core/lighttable/{bootstrap,user,…}.js`. make runs recipes with
`/bin/sh`, which is bash on macOS and dash on Linux, and **dash does not expand
braces**. So on every Linux machine, including the one CI runs on, that was
`rm -f` against a single file named `{bootstrap,user,…}.js`, which does not
exist, and `-f` made it silent. The same bug as the missing four, in the form
that only appears on the machines nobody develops on. make expands the list
now, via `$(addprefix)`, so no shell is involved.

**`make clean` also deleted the compiler cache, and `clean` is not `clean-all`.**
`.shadow-cljs` is content-keyed — shadow invalidates on source hash and
compiler version — so it is a cache, not an artifact, and removing it forced a
cold compile of 305 and then 426 files on every single `make clean build`.
`clean` keeps it and `clean-all` drops it, which is the conventional split and
the honest one. `make clean build` went from 37s to 22s; `make clean-all build`
is still 35s, which is what it is for.

**The ClojureScript warning gate was a grep.** `grep -c 'WARNING #' build.log`
inline in the workflow, with the failure mode every grep-as-a-gate has: if
shadow ever stopped printing that banner the grep would find nothing and the
step would pass green. `script/check-build-warnings.mts` reads the banner *and*
shadow's own per-build warning count, and requires all three builds to have
reported at all — so a changed format fails as "expected 3 builds, read 0"
rather than passing. Tested against a clean log, a warning log, and a log whose
format moved.

**Five `tsc` runs and five `npm run` chains, all sequential.** The five
TypeScript trees are siblings rather than a stack — `doc/hygiene.md` already
said so under *Decided against: project references* — so `build:ts` runs them
at once, the same shape as `script/typecheck.mts`. `npm run check` and
`npm test` likewise: independent checks over disjoint inputs, chained with
`&&`, so a type error hid a stale `doc/api` and fixing one showed you the next.
Both report every failure now. `check` is 3s.

**Four editor event wrappers listened for names the engine never emits.**
`on-change`, `on-move`, `on-update` and `on-scroll` subscribed to `onChange`,
`onCursorActivity`, `onUpdate` and `onScroll`. `src-window/cm6-editor.ts` emits
`change`, `inputRead`, `cursorActivity`, `focus`, `blur` and `scroll` — so all
four registered a listener that could not fire, and had been wrong since
CodeMirror 5, whose names are also unprefixed. Nothing called them, which is
the only reason nobody noticed. Deleted, with a comment where they were.

**Two dead helpers wrote DOM the state had taken over.**
`lt.objs.sidebar.command/input->value` read the `<input>`'s value and
`pre-fill` wrote it; `lt.ui.filter` draws that input from `:search`, so a
`pre-fill` with a caller would have been overwritten by the next render. Both
gone, along with `show-filled`, `set-and-select` and `current-selected`.

**`lt.state` had three readers of its own keying and no caller for any.**
`results-for`, `watches-for` and `edits-for` demonstrated the two keying
decisions the namespace argues for; `lt.ui.bands` reads `:results`, `:watches`
and `:runs` directly and always did. `stop-render!` and `reset-for-test!` went
with them — no test ever touched the atom, because the actions are pure and get
called with a map.

**The ordering fix from `838f8446` was written twice and pinned nowhere.**
`lt.objs.editor.lsp/status-line` and `lt.state.objects/language-server` each
had their own `cond` over the same status map, and each had had the same bug:
the singular keys describe the last-declared server and the plural ones
describe all of them, so a second declared-and-missing server reported over a
live connection to the first. Both read `lt.objs.editor.lsp.status/phase` now —
one `cond`, six phases, in one order — and `test/lt/objs/editor/lsp/
status_test.cljs` asserts that order, including the sentence it used to print.
The extraction is what made it testable: `status` reads an editor, `phase` and
`line` read a map.

**The ClojureScript lint gate named six plugin directories by hand.**
`plugins/Clojure/src plugins/CSS/src …` in `lint:cljs`, exactly right today and
silently wrong the first time a plugin adds ClojureScript. `plugins` lints the
same nine files, finds any new ones, and takes the same second.

**Four stale claims in files that are read before the code is.** `README.md`
carved `src/singultus` out of the MIT license and linked to its README, which
is a deleted path in a deleted directory; `shadow-cljs.edn` said singultus was
"on its way out"; `plugins/Clojure/VENDORED.md` said `lt.compat` shims
`crate.binding` at runtime, and `lt.compat` is deleted too. The fourth is code:
`::build-cljs-plugin`'s `:ignore` list — what a plugin built from source should
not bundle because the editor already has it — still named `crate.core`,
`crate.util`, `fetch.core` and `fetch.util`, none of which have been in the
bundle for two renames, and did not name `replicant.dom`, which draws.

**A control-surface assertion that could not fail.** `control.spec.ts` checked
`Number(count.result)` was `>= 0` to prove the prepared eval namespace aliases
`object` to `lt.object`. A count is a non-negative number whatever it counted.
It now evaluates the expression both ways and compares, which is the claim.

**A DOM node left in hiccup was dropped without a word** — `70666c35`'s
successor. Replicant renders hiccup; hand it a node among the children and it
draws the element around it empty, reports nothing and throws nothing. The
single most expensive thing found in the renderer migration: four separate
files — an inline result carrying a devtools inspector, a console line carrying
the same, a sidebar grip, and the file navigator's filter list — and every one
found by a test written for something else.

`lt.ui.host` is what to reach for, and `lt.ui/render-safely!` now says so at the
moment it happens, naming the object and the tag. It already walked the hiccup
tree to force lazy sequences, so the check is free. It would have caught all
four.

**Every chrome panel silently lost its first paint** — `0a3fe468`'s successor.
A view with a data handler renders when its namespace loads, which was before
`lt.core` reached the bottom of its own file and installed `r/set-dispatch!`.
Replicant threw, caught it itself, logged `[object Object]` and skipped that
render. Nothing looked wrong because the second render is one state change
away. `lt.actions` installs the dispatch when it loads now.

**Toggle docs did nothing, silently** — `063aa943`. `request-at-cursor!`
declined before the language server's handshake and said nothing at all, and a
server takes seconds to minutes to index a project. It returns `:sent`,
`:not-ready` or `:no-server` now, and the two commands a person runs on purpose
report which.

**Printing a Light Table object never finished** — `e7466806`. An object is an
atom whose state holds other objects and the graph has cycles, so
`(first (pool/by-path f))` — the most ordinary thing there is to evaluate in
this editor — printed until the stack ran out, and the RangeError came out of
whoever had called in rather than out of the job. `cljs-result-format` binds
`*print-level*` now.

**The e2e tests spelled ClojureScript names by hand** — `9f5c146f`. 81 places
went round the control surface into `window.evaluate` because `evalClj` was not
available there and answered printed text anyway. One left, the readiness
probe, which cannot use the surface it is waiting for.

**`defpartial` had no callers and a file to itself** — `baae8ffe`. The last
reference was an unused require in `lt.objs.opener`, beside an unused `defui`.
`src/singultus/def_macros.clj` is gone.

**Three copies of a dead button** — `98a4b0a3` and its parent. `eval/button`,
`document/button` and `deploy/button` were the same eight lines with no caller
anywhere, left behind when the connect panel became a view. `python/canvas` and
`canvas/canvas-elem` went with them.

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

**Five behavior entries named things that do not exist** — the HTML plugin
asked for `lt.objs.editor/load-addon` twice for CodeMirror 5's closetag and
matchtags, which has had no such function for some time and was superseded by
the `set-codemirror-flags` line below it; `default.behaviors` asked for
`check-metadata-sha`, renamed to `get-latest-metadata-sha` without the config
following; and the Clojure plugin bound `nrepl/client.settings` twice, which is
a private function rather than a behavior. All silent.

**Twenty-nine unused images, ~270KB** — the CodeMirror 5 show-invisibles
sprites and three loaders, of which two were reachable only through CSS that
was itself dead.

**Dead CSS for four features that no longer exist** — `#filer`,
`#markdown-preview`, `.behavior-helper-result`, `#multi-container`, plus the
CodeMirror 5 addon classes and `.load-wrapper`, whose last markup went with the
connect panel.

**Two CSS blocks for the tab strip had drifted apart** — `5977a8b8`, along with
a `.dirty.ui-sortable-placeholder:after` rule naming a jQuery-UI class nothing
had produced since `dragdrop.ts` replaced it.

**Every chrome panel lost its first paint.** A view with a data handler renders
when its namespace loads, which is before `lt.core` reaches the bottom of its
own file and calls `actions/install!` — so `r/set-dispatch!` was not installed
yet and Replicant threw, caught, logged and skipped. `lt.actions` installs the
dispatch when it loads now, which is before any namespace that draws.

**The connect panel drew whatever a client put in `:name`.** A projection's job
is to hand a view data it can draw, and `(:name @c)` is set by whoever made the
connection. `lt.state.objects` stringifies it, and the tab label with it.

**`filter-list` was a pool of `<li>` repainted with `innerHTML`** — the command
bar, the navigator, the syntax selector and the hinter are `lt.ui.filter` now,
which closed the unescaped-HTML entry above with it.
