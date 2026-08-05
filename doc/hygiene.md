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

---

## Open — claims the code does not keep

### Syntax highlighting is reported to go, and has not been caught

"Sometimes we start to lose syntax highlighting", and three attempts to force
it failed: opening five files of three languages, forty inserts into one, and
replacing a whole buffer all kept it. The apparent loss in the first attempt
was an artifact of the measurement — CodeMirror 6 renders only the viewport, so
a raw span count halves when you scroll to a shorter region and says nothing.

So the measurement exists now rather than the fix: `lt.objs.control/screen`
reports spans per *rendered* line per editor, which is stable across scrolling.
A file with highlighting runs several a line. `script/lt-repl.sh screen` when
it next happens is what turns this into something with a cause.

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
`.excerpt--agent`. Nothing writes a run.

This is forward design rather than rot — `lt.ui.catalogue` draws all of it from
literals, which is what a component kit is for, and `doc/direction.md` is about
where the editor is going. Recorded because a reader who greps for what creates
a run will not find it, and should not have to conclude the projection is
broken.

**The `:keymap` half of this is closed.** `:behavior/rebind` wrote to a key
`initial` did not have, and now `lt.state.objects/keymap` projects it and two
surfaces read it — see *the settings screen* in the Closed section.

### ripgrep's `--json` output is unbounded, and one line can be megabytes

`--json` includes the whole matched line. A binary has almost no newlines, so a
match inside one arrives as a single enormous line — measured at **21MB** for the
191MB Electron Framework under `builds/`, and 107MB of output for one search of
this repository with `use-ignore-files` off. The same search with ignore files on
produces 374KB and a longest line of 723 characters.

Three things are known about it, and none is a fix:

- **`--max-columns` does not help.** It has no effect in `--json` mode, confirmed
  by measuring the longest line with and without it.
- **Matches from binary files are dropped**, which removes the *results* problem
  but not the transport cost — ripgrep has already written the bytes by the time
  it says the file was binary.
- **`maxBuffer` is no longer the limit.** stdout is read incrementally, so there
  is no ceiling to exceed — and peak memory is the largest single line rather
  than the whole output. A throw while parsing still falls back to the tree walk
  and says why.

`--max-filesize` would fix the rest, and is deliberately not set: **VS Code
passes it only when it is configured**, so there is no default there either, and
a cap would silently stop searching large *text* files, which the walk never did.
That closes this as a decision rather than an omission.

### Workspace search results are in a different order than they were

Two changes, both from searching through ripgrep, and both are visible rather
than internal.

**Sorted by path.** ripgrep searches in parallel and reports files in no stable
order at all, so something had to decide. Lexicographic is what `rg --sort path`
and VS Code both do and the one a person can predict — but it is not what the
tree walk did, which reached a directory's own files before descending into its
subdirectories. So `nested/three.txt` now comes before `one.txt` where it used to
come after. Sorted in `lt.background.rg` rather than by `--sort path`, which
ripgrep's own documentation says abandons parallelism.

**All at once rather than file by file.** The walk sent a message per matching
file as it found them, so results appeared while it was still going. ripgrep's
output is collected before any of it is emitted — which is what makes it
sortable, and is the trade. Worth having when a search took 161ms; not worth
much at 20ms.

Neither is wrong and neither is a fix. They are recorded because "my results are
in a different order" is a real thing to notice and there would otherwise be
nowhere to look.

### A component handed to a row as `:leading` cannot be found by a test

`lt.support.hiccup/nodes` walks a vector's children, and an attribute map is not
one of them — so `find-all` cannot see a component passed as `:leading`,
`:trailing` or `:what`. Two real components live there: the keyboard hint in
`view/command-bar` and the one in `view/keys-screen`.

The consequence is narrow but worth knowing before writing an assertion that
passes for the wrong reason: a test asking "does this screen draw a `kbd`" gets
`false` whether or not it does. Asserting on `text-of` works, because that walks
everything that is not a map.

Not obviously worth fixing. Descending into attributes would make `find-all`
report components that are arguments rather than children, and the two are
genuinely different questions — but nothing says so anywhere except here.

---

## Open — coverage

Ranked by what a failure would cost.

| gap | where it is covered today |
|---|---|
| The browser tab and its webview | `test-e2e/browser-tab.spec.ts` covers the controls, the absent `preload`, and the webview surviving a redraw; smoke covers it end to end with a real page. What is still uncovered is the devtools connection, which needs the remote debugging port the e2e fixture turns off |
| Plugin capability enforcement | smoke only for the general case; `windows.spec.ts` covers two specific historical bugs |
| The application menu | smoke only, one assertion |
| ~~The settings and keymap UI~~ | **closed.** 20 view tests over `settings`, `keys-screen` and `settings-screen`, and 13 action tests over the eight actions behind them. It was the worst gap on this list and the cheapest to close, because a view is a function: a settings screen is hard to drive and trivial to *ask* |
| The docs sidebar, the plugin manager and the object inspector | `doc-sidebar.spec.ts`, `plugin-manager.spec.ts` and `inspector.spec.ts` cover what each draws and what changes it. Each was written with the conversion off `defui`; none existed before |
| ~~`contextIsolation` / no Node in the renderer~~ | **closed.** `test-e2e/isolation.spec.ts` pins all four: no `__dirname` or `module`, a `require` that refuses `vm`, a `process` with no `binding`, and a bridge whose prototype proves it came through `contextBridge` rather than a shared global. Deliberately duplicating smoke — this is the one invariant where two independent statements are a feature, because the failure mode is somebody turning isolation off to debug and not turning it back on |
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

**The popup's choices are on the object, and the control surface reads no DOM at
all now.** This was half closed once already — the popup kept its header — and the
remaining half had a real reason rather than a lazy one: a popup's choices are not
all buttons. Two callers needed a *list* of them, which client should evaluate
this and which code action to run, and `:options` did not exist. So they built
`li.button` hiccup in `:body` with a click closure inside it, the choices existed
only in the rendered document, and `lt.objs.control` read them back with
`querySelectorAll` because a reader that trusted `:buttons` would have offered
"cancel" and nothing else for exactly the prompts where the choice matters.

`lt.objs.popup` has `:options` now. Same shape as `:buttons` — `{:label :action}`
through `choose!` — and positioned as what it is: an option **is the question**
where a button confirms or cancels it.

Four things followed, and three of them are the interesting part:

- **The `atom` holding the popup is gone from both callers.** Each had
  `(let [popup (atom nil)] (reset! popup (popup! …)))`, which existed only so a
  hand-built click handler could close the thing that contained it. The popup
  closes itself for an option, as it always has for a button.
- **`.lsp-action` is gone and nothing replaced it.** Its own comment said what it
  was for — *the popup's cancel is an `li.button` too* — so it was a class
  invented to make a distinction the markup could not. `ul.options` against
  `ul.buttons` is that distinction. No stylesheet ever referenced it; the smoke
  test did, and selects on the structure instead.
- **`answer!` runs the choice rather than clicking it.** It used to find the
  matching `li.button` and call `.click()`, which reaches the same place through
  the handler the view installed. `popup/choose-by-label!` is what is meant, and
  it does not stop working when the view changes what it hangs a handler on.
- **`lt.util.dom` came off `lt.objs.control`'s requires**, which is the property
  worth having: what the control surface reports and what a test can assert are
  now the same thing.

**And the keyboard gap that was left with it is closed too.** `:button` indexed
`:buttons` alone, so a popup whose whole purpose was to ask which of nine things
you wanted let you arrow between *cancel* and nothing — and Enter on a chooser
cancelled it. `:active` indexes every choice in the order it is on screen now, so
the arrows walk the options and then the buttons and Enter runs what is
highlighted.

That changes what Enter does on a chooser, deliberately: it takes the highlighted
option, which is what every other chooser does and what the highlight was already
promising. A popup with no options is unaffected — the indices are just its
buttons.

The rename from `:button` to `:active` is the part worth remembering, because it
cost a bug: **renaming a behavior silently unattaches it.** `::change-active-button`
became `::change-active-choice` and `default.behaviors` still named the old one, so
the arrow keys stopped working entirely while every unit test passed. `make audit`
reports unattached behaviors and would have said so; the e2e test is what actually
caught it.


**The console is a view of a value, and the streaming append is why it works
rather than why it could not.** It was the last surface still building its own
nodes and it had the best reason: it is genuinely append-only. `write` appended an
`<li>` and dropped the first child past the limit; `try-update` found
`#console<id>` in the document and appended a text node, so a process talking in
chunks accumulated in one row rather than producing a row per chunk.

This entry said making it a view means holding the last fifty lines *as* a value
and that the streaming append is the thing that would have to change. Both were
right — and the second turned out to be the argument *for* the conversion.
Appending to a line you are holding is `update :text str`; appending to a line you
have already drawn means finding it by a generated id in the document. **The
imperative version existed because there was no value to update, not because
streaming needs a DOM.** The `id` a line carried in its markup is now a field
nobody has to render.

Three things fell out:

- **`:replicant/key` is load-bearing.** With the index as the key, dropping the
  oldest line shifts all fifty and Replicant rebuilds the list on every log. Each
  line carries its own monotonic key instead.
- **`::sidebar.console` was dead** — a second console object, defined and never
  created. Gone.
- **`lt.util.dom/text-node` was dead as a consequence**, its only caller being the
  old append. Deleted; making a text node by hand is something only a renderer
  needs to do.

One behaviour deliberately preserved rather than tidied: `try-update` matches an
id **anywhere** in the list, not just the last line. That is what `querySelector`
did, and it is right — two processes talking at once each accumulate into their own
row, and a chunk from the older stream belongs where its stream started. An earlier
version of the conversion matched only the last line, which reads as a
simplification and is a regression.

`test-e2e/console.spec.ts` is ten tests over a surface that had none, and every
assertion is about what the console *shows* — so they would pass against the
imperative version too, which is the point. A conversion's tests should not be
able to tell which implementation they are running.


**The bottombar's generality was half dead and is deleted.** It kept `:items` as
a `(sorted-map-by >)`, `add-item` was the only writer, and **nothing ever read
it** — the bar draws `(:active @this)` and always has. So it was a registry of
things that could be shown, consulted by nobody, kept in step by one caller: the
console, the only thing that ever registered.

The remaining half is deliberately left, and the distinction is the useful part.
The bar still splices another object's `object/->content` into its own DOM through
`lt.ui.host/host`, which is the shape that does not convert to a view — but the
reason for that is the console, which renders itself imperatively because its
streaming append genuinely is. Making the bar draw the console directly before the
console is a value would move the problem rather than close it, so this closes the
part that was dead and leaves the part that is load-bearing to the entry below.


**The hinter's rendering cost is measured now, and the concern does not survive
it.** `lt.ui.filter` replaced a fixed pool of `<li>` nodes with ordinary diffing,
which was obviously right for the command bar and the navigator and was an open
question for the auto-complete hinter — unbounded candidates, refreshed on every
character.

Measured in the running window by `test-e2e/hinter-cost.spec.ts`:

| candidates | scoring | row building | rows built |
|---|---|---|---|
| 100 | 0.4ms | 0.0ms | 50 |
| 1,000 | 0.7ms | 0.1ms | 50 |
| 10,000 | 2.4ms | 0.1ms | 50 |
| 50,000 | 11.2ms | 0.0ms | 50 |

**Row building is flat**, because `indexed-results` slices to 50 *before* the
expensive scoring pass — so the renderer's input is fifty items whether there
were a hundred candidates or fifty thousand. The unbounded part is scoring, and it
is not quadratic either: a hundredfold increase in candidates costs 38x the time,
because the cheap `fastScore` filter removes most of the list before anything
sorts it.

What is asserted is the **property** rather than the duration: rows built never
exceeds the cap, and does not change with the candidate count. A wall-clock
threshold in CI fails on a loaded machine and passes on a fast one whatever the
code does — the same mistake a raw span count was for syntax highlighting above,
and `lt.objs.control/screen` exists because of it.


**`:client/bind` was a stub.** Its effect wrote a `::client` key on the editor
that nothing read, so clicking a connection row did nothing while the row's whole
purpose is to say where an evaluation goes. `:bound?` was already read truthfully,
which made it the hardest kind of broken to notice: the panel reported correctly
and only the click lied.

What it needed was a decision about evaluation semantics, and the decision turned
out to be smaller than the question. `(:client @ed)` maps *key* to client rather
than one client per buffer — but of the eleven `get-client!` call sites across the
five code plugins, **ten pass no key at all** and get `:default`. The eleventh is
the Clojure plugin's `:exec`, a private second channel for
`:editor.eval.cljs.exec`. So `:default` is what "where does an evaluation go"
means, and binding writes that and nothing else. Redirecting one plugin's private
channel from a list of connections would be answering a question nobody asked.

Three things made it cheap rather than new:

- **The operation already existed** in a place nobody could reach —
  `find-client`'s `:select` branch, which runs when a language finds more than one
  candidate and asks. Binding from the panel is the same act with the choice made
  earlier, so it is the same `clients/swap-client!` then `assoc`. `swap-client!`
  is why it is not a plain `assoc`: a client can have queued messages that have
  not been sent, and replacing the entry without replaying them loses an
  evaluation somebody asked for.
- **`:client/unset` is already the inverse** and removes the client from *every*
  key it occupies, so the two compose without a second policy.
- **The guard was the only thing genuinely missing.** `get-client!` reuses
  whatever is bound if it is merely *available* and never checks it can serve the
  command — so a client advertising no evaluation command would be a buffer whose
  next evaluation goes nowhere silently. `lt.objs.providers/evaluates?` is that
  check, in the namespace that already answers "what can this client do" from
  published `:commands`.

Deliberately *not* checked: whether the client suits the buffer's language. A
client advertising `:editor.eval.python` is a legitimate choice for a file Light
Table thinks is something else — the type may be wrong, or the user may know
better — and refusing it would be the editor overruling a deliberate act. Jupyter
lets you select a Python kernel for any notebook too.

`test-e2e/connect.spec.ts` covers it in eight tests, including that evaluation
reads what the click wrote — `get-client!` returns the bound client with a
`:create` that throws, so a discovery instead of a binding fails the test. Six of
the eight were checked against the old stub to confirm they fail there.


**A doc over an inline result orphaned it.** `lt.objs.eval/::underline-results`
cleared whatever was at `[line :underline]` before writing there;
`lt.plugins.doc/inline-doc` overwrote the entry without raising `:clear!`. A
widget owns a DOM node, so the replaced one stayed on screen with nothing holding
it — a Python plot followed by a doc on the same line was the case.

Two writers of one key and only one of them knowing the rule was the actual
defect, so the fix is `lt.objs.eval/put-underline!` and both call it. `keep-open?`
is the one thing they disagree about, and it is now an argument rather than an
omission: a re-evaluated result replacing its own earlier value should stay
expanded, and a doc replacing a plot should not.

`test-e2e/inline-results.spec.ts` proves it, and was checked against the old code
to confirm it fails there — a test for a leak is worth nothing if it passes
either way.

**Splits were half-projected.** `lt.ui.view/window` drew one tab strip and one
editor pane, both the first tabset's, so a split window showed half of itself.
Silently, because a window with no splits has exactly one tabset and looked
right. The real chrome never had this — `lt.objs.tabs` gives each tabset its own
strip and the actions carry the tabset id — so it was this view, drawn beside the
real thing in the component kit and in `Window as a view`, that disagreed with
it.

`editor-pane` has two arities now, the same shape as `titlebar`'s and for the
same reason, and `window` draws a column per tabset. Five view tests cover it,
including that a window with no splits is unchanged.

### A user's ripgrep config could change what the editor searches

Closed, and recorded because of how it was found rather than what it was.

ripgrep reads `RIPGREP_CONFIG_PATH` before its own arguments, so any flag in a
developer's config file governed workspace search. The one on the machine this
was found on turned off `--no-follow` — a guarantee `lt.background.rg/argv`
states in so many words, *what stops a cyclic link running forever* — and hid
results with `--glob=!node_modules/`.

`--no-config` fixes it, and VS Code has always passed it. What makes this worth an
entry is that **it was found by the numbers moving**: adopting the flag changed
the benchmark's file count from 3,432 to 13,967, which is how anybody learned the
earlier measurements had been taken through one person's dotfile. A setting that
silently changes results and cannot be seen from inside the editor is the exact
shape of problem this document is for.


**The settings screen existed and was never populated.** `lt.ui.view/settings`
was eight lines over a `:keymap` state key, and the docstring's claim was right —
the keymap really is a view over the dispatch table, and there was nothing to
build. What it was missing was that nothing filled `:keymap` in: it was written
to by `:behavior/rebind` and by nothing else, against a key `lt.state/initial`
did not have. Two lines in `lt.state.objects` made it real, and the eight-line
view is `keys-screen` now, unchanged in what it claims.

The settings half is new and is a projection for the same reason: `:type :user`
and `:params` have been on every behavior since 2013, so what a control should be
is a lookup on a declared type rather than a form anybody writes.

**A view destructured `:settings` and shadowed itself.** The obvious way to
write these is `(defn settings [{:keys [settings]}] …)`, and then
`settings-screen`'s call to `(settings state)` is a *map lookup* — in
ClojureScript calling a map with one argument is a lookup, not an error — so it
returned nil and half the screen drew nothing, silently. Found by a test written
before anyone looked at the screen. Both views take the whole state and slice it
by name now, with a comment saying why.

**An alias returning nil is a render Replicant throws inside.**
`lt.ui.field/source` was written `(when from …)`, and Replicant takes the return
value as the node — so a nil became `createElement(":lt.ui.field/source")`, an
`InvalidCharacterError` raised inside Replicant's own render, caught by
Replicant, logged as *you may have misbehaving aliases*, and the whole render
skipped. `setting-row` passes `:from` for every setting and most are at their
default, so the first unchanged one would have blanked the screen.

Found by `make storybook-check`, from a story state written to show that the
common case draws nothing. Now also a unit test — `no-component-can-draw-nothing`
calls every registered alias with no attributes — so the class is caught without
a browser.

**A function in the state atom makes `drift` cry wolf for ever.** The state is
data by contract — `lt.state`'s docstring says editor instances and DOM nodes are
not in it because they are not data — and a function is the same category. It
also breaks the one tool that can tell you whether the projection is stale:
`snapshot` is called twice and compared, and two calls that build two closures
are never `=`, so every key carrying one is reported as drifted always.

The settings projection did it twice, once with a function and once with the
autocomplete hinter's JS objects behind it. Both are resolved to plain data now.
Worth knowing before projecting anything new: **if `(= (snapshot) (snapshot))` is
false, `drift` is useless from then on**, and it will not be obvious which key
did it.

**`object/by-tag` returns objects that have been closed.** A tab that reuses its
object rather than making a new one has to ask two more questions than
`by-tag` answers: is the object still alive — `object/destroy!` raises
`:destroy` *before* removing the instance, the same ordering
`lt.objs.control/drift` was built to find — and is it still in a tabset, which a
closed tab clears. Focusing either kind of corpse puts nothing on screen and
throws nothing.

The symptom is what makes this worth writing down: the settings screen's e2e
specs passed and failed in **strict alternation**. One test opened a real
screen; the next focused what the first left behind and timed out waiting for an
element that was never going to exist; that failure left no tab, so the one after
it opened a real screen again. Nothing about the pattern looks like a stale
reference until you notice it is every other test.

`lt.ui.settings/open!` filters on both, and `tabs/add-or-focus!` is the built-in
that does the right thing with the answer. The catalogue and the window-as-a-view
never hit it because they create a new object every time.

**A language server was rooted at the nearest manifest, not at the project.**
`project-root` walked up to the first directory carrying a marker, which is
wrong for every monorepo. A Cargo workspace member says
`edition.workspace = true` and cannot be read without the workspace above it —
so rust-analyzer rooted at `mope/core` was rooted at a manifest it could not
parse. It answered hover, which needs only the open file, and published no
diagnostics at all, which needs the crate graph. Reported as "docs work,
errors don't", with a screenshot of an unflagged typo.

The outermost marker *inside the repository* now, bounded by the nearest
`.git`. The bound is what makes "outermost" safe: without it one stray
`Cargo.toml` in a home directory would capture every project beneath it, and
with no repository around the file there is nothing to say how far out the
project goes, so it falls back to nearest. Verified against the real project —
the root moved from `mope/core` to `mope` — and against this repository, whose
root is unchanged.

**Routine server stderr was logged as if it mattered.** stderr is where a
server explains itself when it will not start, and that is the only time it is
worth reading — but several servers use it as an ordinary log. rust-analyzer
emits `WARN notify error: No path was found` for each optional config file it
looks for and does not find, three of them, on every start. A console that says
that every boot is a console nobody reads on the day something is wrong.
Filtered by what the line is rather than by which server sent it: INFO, DEBUG
and TRACE levels, and that one WARN about a path that does not exist. ERROR is
never dropped.

**A right-click built two menus, and the second wiped the first's handlers.**
An editor answers `:menu!` twice — `lt.objs.editor/menu!` from `:editor` and
`lt.objs.menu/menu!` from `:tabset.tab` — so one right-click builds two menus.
`lt.objs.menu/menu` did `(reset! popup-handlers {})` on every call, on the
reasoning that only one context menu is on screen at a time, so the second
build dropped the first's tokens before either was clicked.

The symptom is exact, and it is the sixth reported cause of "toggle docs does
nothing": the menu opens and looks right, `Copy`, `Cut`, `Paste` and `Select
all` work because they are Electron *roles* and need no token at all, and every
item backed by a handler — `Toggle docs`, `Close tab`, `Move tab to new
tabset` — does nothing whatsoever, because `(when-let [handler …])` finds none
and returns.

Tokens are monotonic, so an old one can never be confused with a new one and
keeping them costs the closures. Bounded at 400 rather than reset, which fixes
any double-menu rather than this one.

Found by driving the packaged application and noticing that every probe so far
had called the `:click` closure directly — the token path, which is the only
one a person uses, had never been exercised. Two menus is still one too many
and `lt.objs.editor/menu!` and `lt.objs.menu/menu!` are near-duplicates; that
is a separate tidy.

**`status` described the last-declared server rather than the one answering.**
On a TypeScript file in a project with no `biome.json`, the singular keys —
`:command`, `:root`, `:found` — were biome's: `"biome"`, `nil`, `nil`, reported
about an editor vtsls had indexed and was answering. Two true facts about a
server that is not involved, in the three fields anything short reads first.
`situation/phase` already asked what was connected before what was declared;
this is the same correction one level down, in the map that feeds it. Matched
on the command's leaf, because a declaration carries the bare name and a
connection carries the resolved path.

**Line numbers were off by default.** `[:editor :lt.objs.editor/hide-line-numbers]`
in `default.behaviors` — a choice rather than an absence, and the wrong one for
an editor whose diagnostics are reported by line. The pair is `:exclusive` and
`:type :user`, so naming either in `user.behaviors` replaces the default
without removing it; the user template offers the opposite one now.

**Toggle docs did nothing when no editor was active** — the fifth distinct
cause, and the one that survived four fixes aimed at the silence.
`:editor.doc.toggle` was `(when-let [ed (pool/last-active)] …)`, and
`last-active` is set by the `:active` trigger — so an editor that has not been
made active since it opened leaves it nil and the command returns having done
and said nothing. It happened *before* the guard that exists to notice exactly
that, which is why the guard never fired.

Reported with a screenshot of the right-click menu, which is where it is most
obviously wrong: a menu is opened *on* an editor, so it is holding the answer
the command went looking for. `::doc-menu+` passes it now, and the command says
"No active editor to document" when it has none rather than returning.

Found with the tracer, on its second real outing: `:editor.doc` was not in the
trace at all, which is what "the command returned before raising anything"
looks like from outside.

**One diagnostic was drawn twice.** `lt.state.objects/results` projected LSP
diagnostics to show that the `[path line]` address in `lt.state` was not
hypothetical. `lt.ui.bands` is installed on real editors by `lt.core` and draws
a band per `:results` entry, while `lt.objs.editor.lsp/draw-diagnostics!` draws
its own line widget for the same diagnostic — so one diagnostic put two things
under the line, saying the same sentence in two styles.

Latent until `::sync-from-language-servers` made the projection keep up, and
then visible on every diagnostic, which is a demonstration turning into a
feature. `results` returns `{}` now: a diagnostic is what the file says rather
than what running it produced, and the two want different addresses.

**A command registered after startup never reached the projection.**
`lt.state.objects/commands` projects `lt.objs.command/manager` so the command
bar can be a view over it, and nothing listened for `:added` — so every
plugin's commands, which arrive after the window does, were missing from that
list until something else happened to sync. Debounced, because 217 commands
register at load and each raises it.

Narrow in effect and worth knowing why: the command bar you actually open was
never wrong. `lt.objs.sidebar.command` passes `:items` as a *function* and
calls it when it needs the list, so it reads the table live. This is the
window-as-a-view surface that replaces it.

Found by scouting the two bugs that building `drift` turned up, and it needed a
fix to `drift` first: the check excluded `:command-bar` wholesale because part
of that key is the state's own, which made it blind to the half it could judge.
A tool that reports nothing reads as agreement. It compares `:commands` and
ignores `:open?`, `:query` and `:selected` now.

**A closed editor stayed in the projection.** `lt.object/destroy!` raises
`:destroy` and *then* removes the instance from the registry, so
`lt.ui.window/sync-from-objects` — which covers `:close` — took its snapshot
while `object/by-tag :editor` still found the editor being closed. It stayed in
`:editors` until something else happened to sync.

Found by `lt.objs.control/drift` on the first run of the check that compares
the projection against the objects it is projected from, which is the entire
reason that check exists. Synced a tick later rather than reordering
`destroy!`: behaviors reacting to `:destroy` are entitled to find the object
still registered, and taking that away to fix a projection would be the
projection dictating terms to the object model.

**Nothing could ask what fired.** Every step of a behavior chain may decline
quietly, so a break looks like nothing at all — and "toggle docs isn't working"
was reported five times with four causes and one symptom. `lt.object/raise*`
already raised `:object.behavior.time` for every invocation, in the hot path,
and nothing recorded it. `lt.objs.trace` does, off by default, and `raise`
reports the listener count as well — so "nothing listens for this" and
"something listened and declined" are one line apart instead of an afternoon.

**The cross-checks lived in a transcript.** Unreferenced public vars, behaviors
declared but never wired, behaviors wired but never declared, duplicate command
keys, unused TypeScript exports: every one was written as throwaway Python,
found something real — four broken event wrappers, an unreachable feature, a
silently-shadowed command — and was thrown away. `script/audit.mts` is those
five checks, `make audit`. Advisory by default, because "no caller in this
repository" is a fact rather than a verdict; `--strict` fails on the two that
are unambiguous.

**Asking for a docstring started a REPL, and put a modal over the editor.**
`::clj-doc` and `::cljs-doc` reached for `lt.objs.eval/get-client!` with
`:create try-connect`, which answers a question nobody asked — is there a REPL
we *could* start — and starts one. On this repository that runs `shadow-cljs`,
which reports "server already running" and exits, which Light Table reads as a
failed connection: **"We couldn't connect."** over the whole window, on every
press of Ctrl-d. That is the fifth report of "toggle docs isn't working" and
the only one where the answer was drawn correctly and covered up.

Without `:create` it is no better: `find-client` raises `:no-client` and
returns a placeholder, which then sits on `(:client @ed)` under `:default` with
no name, no commands and `available?` nil — a client for the purposes of every
later read, answering nothing.

They ask a client that is *already* connected and provides `:doc` now, and
stand aside otherwise so `lt.objs.editor.lsp/doc-at-cursor` answers. That is
the same question `answered-elsewhere?` asks from the other side, so the two
cannot disagree about who is answering.

Found by driving the packaged application rather than the tree — see below.

**Nothing could drive the release build.** `test-e2e` and `smoke-test` launch
`deploy/core` with the development Electron and a scratch `LT_USER_DIR`, which
is right for a test and wrong for "does it work in the real thing": the package
has its own Electron, its own `node_modules`, its own copy of every plugin, and
a resources layout the tree does not have. `script/lt-repl.sh start --release`
boots what a person double-clicks, against the real user directory, and
everything else about the REPL is unchanged. The modal above was found within
minutes of it existing, and confirmed fixed in the same window.

**A window could not say what it was built from.** The bundle is an artifact
with no identity and `version.json` carries the release number, which is the
same string across every build between two releases — so telling a stale window
from a fresh one meant grepping the compiled JavaScript for a string you had
just typed. That is what actually happened, twice, and each cost a round of
"it still doesn't work" about code that was fixed and not loaded.
`script/stamp-build.mts` writes a stamp beside the bundle at the end of
`build:cljs`; `App: What build is this?` and the version pane read it, and
`dirty` is the part that matters while you are working. `doc/workflow.md` now
says which of the three build targets to reach for, and that `make build` is
not the iteration loop.

**Two commands could claim the same key in silence.** `lt.objs.command/command`
has always documented `:command` as unique and never checked, so a second
namespace registering a taken key replaced the first without a word — a command
that runs something else, which is worse than one that does not exist, because
the command bar still lists it. Found by walking into it: `:build.info` was
first written as `:version`, which `lt.objs.version` already owns, and it
silently did nothing.

Reported by `:desc` rather than by identity, so live editing stays quiet —
evaluating a namespace re-runs every `command` form in it, which is the feature.
That misses two copies of one command, so the repository was scanned as well:
one pair in 217, `:editor.select-all` registered twice in
`lt.objs.editor.pool` with different implementations, the second winning. The
dead one is gone.

**A doc press that nothing answers now says so.** The guard that does not need
to know what went wrong. Five reports of "toggle docs isn't working" with four
different causes — a server declining pre-handshake, a REPL taking the surface
and having nothing, the ClojureScript twins of the behaviors that fixed, and a
client whose `on-message` `:default` method swallows the request and never
replies — and every one of them looked identical from outside: no widget, no
message, no console line. Each fix closed the path it was about, and the next
silence was indistinguishable from the last.

`:editor.doc.toggle` checks now. Every way this can fail ends in no widget and
nothing said, whatever the reason and whether or not anybody has found it yet,
so the check needs no theory of the failure. It points at `:lsp.status` rather
than guessing, because that already works the whole situation out and a second,
worse version of that sentence is how two answers come to disagree. It stays
quiet when something *did* report — a feature saying no is a feature working.

**"Language server ready" was about whichever one finished first.** `::on-ready`
said it on every `:lsp.ready` with nothing tying it to what you were looking at.
A connection is keyed `[root command args]`, so on a project with two servers,
or with two projects open, it was a true sentence about something else — and it
is what sent three separate investigations to the wrong place while the
connection the open editor used had `initialized? false` and no capabilities at
all. The connection keeps its `:command` now and `ready-message` names both
halves of what it is keyed by: `clojure-lsp ready in LightTable`.

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
parent. Both `package.json` files carried an `allowScripts` map; npm had no such
key, so sixteen tree-sitter grammars ran `node-gyp-build` on every install.
`deploy/core/.npmrc` with `ignore-scripts=true` now, which is safe because those
sixteen are the only packages there with an install script and Light Table reads
grammars as `.wasm` rather than through the native binding.

*And npm has the key now.* npm 12 blocks install scripts by default and reads
`allowScripts` for the exceptions — the mechanism this repository invented for
itself, arriving upstream. It warns once per unreviewed package, which is a
prompt to decide rather than something to silence: `fsevents`, reached through
`@playwright/test`, is denied in the root `package.json`. Denying costs nothing
and the check was worth doing — fsevents has no `install`, `preinstall` or
`postinstall` script at all (its `build` is for its own publish), ships
`fsevents.node` prebuilt, loads, and is dev tooling that never reaches
`deploy/core`. `npm install-scripts ls` is how to ask what is still unreviewed.

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
