# Testing

Four layers, each answering a question the one below it cannot.

| | what it runs | what it needs | how long |
|---|---|---|---|
| `make test-cljs` | ClojureScript, under node | nothing | seconds |
| `make test-electron` | the main process, under plain node | nothing | under a second |
| `make test-e2e` | the real application, one per worker | a build | ~45s |
| `make smoke` | the whole assembled application, once | a build | ~40s |

`make test` runs both unit layers. CI runs all four.

## Choosing a layer

**Put it in a unit test if it is a function.** Both unit layers are plain node
with no window, no Electron and no DOM, which is what makes them fast enough to
run on every save. `test/` holds the ClojureScript, `test-electron/` the
TypeScript.

This is where the pressure to *make* things testable comes from, and it is the
useful pressure. The main process shipped a bug that blanked every window after
the first, and it was untestable because the decision — how one window's
options are built — was three lines inside a function that also creates windows,
attaches listeners and loads a URL. Moving the decision into
[`src-electron/config.ts`](../src-electron/config.ts) made it eight assertions
that run in a millisecond. The rule that follows: **if it is a decision rather
than an effect, it does not belong in `main.ts`.**

**Put it in an integration test if it needs a running editor.** `test-e2e/`
launches the real `deploy/core` through the real `main.js` and gets both halves
of it — `page` for the window, `electronApp.evaluate` for the main process, in
the same test.

Each test gets its own application *and its own home directory*, via
`LT_USER_DIR`. Settings, the workspace, logs and caches all live under it, so
without that a test inherits whatever the last one — or the developer — left
behind: a tab pointing at a file that has since been deleted, a workspace full
of scratch directories. That is not hypothetical; it is why a suite that took
twelve seconds took fifteen minutes the next time and then failed.

**Leave it in the smoke test if it is about the assembly.** `script/smoke-test.mts`
boots once and asserts eighty-odd things in sequence: plugins loaded, modes
registered, the worker connected, the bridge intact. That shape is right for
"is this application wired together" and wrong for everything else. New
integration tests go in `test-e2e/`; smoke shrinks as checks move.

## Why Playwright, and the one thing it needed

Electron testing has two live options — Playwright's `_electron` launcher and
`@wdio/electron-service`. Playwright's is documented as experimental and there
are reports of it failing on Electron 36 and later, which is why this was
measured rather than assumed.

It works on Electron 43. What did not work was our own
`--remote-debugging-port=8315`, appended unconditionally at module scope:
Playwright appends its own with port 0 and reads back what Chromium chose, the
two switches disagree, and the launch hangs until the harness times out three
minutes later naming nothing. So the port is
[configurable](../src-electron/config.ts) now, the renderer learns it from
`appInfo` instead of holding a second copy of the number, and the fixture
launches with `LT_REMOTE_DEBUGGING_PORT=off`.

That is worth knowing beyond the tests. Light Table opened a debugging port on
every run whether or not anything used it, and anything able to reach that port
could drive the application.

## Writing an integration test

[`test-e2e/fixtures.ts`](../test-e2e/fixtures.ts) supplies `app`, `window` and
`ltErrors`, and `ready()` waits for the object graph rather than the page —
`did-finish-load` fires when the bundle has been fetched, not when it has run,
and every probe that raced that failed in a way that read as a missing feature.

`ltErrors()` exists because `lt.object` catches exceptions thrown inside
behavior reactions and reports them, so a behavior that throws looks exactly
like one that decided not to act. Assert on it. Two things were found by
asserting the console is empty at startup: an update check that reported
`TypeError: Failed to fetch` with a stack trace whenever the machine was
offline, and a devtools client that polled a port that was not there.

## What the layers have caught

- **A blank second window.** `test-electron/config.test.ts` pins the defect —
  a shared options object mutated per window — and `test-e2e/windows.spec.ts`
  pins the consequence. Neither could have been written against the old
  `main.ts`, and `script/smoke-test.mts` had passed 80 checks while it shipped,
  because every one of them ran in a first window.
- **Stale plugin artifacts.** shadow-cljs numbers its shared constant table per
  build, so a plugin module built against one `bootstrap.js` fails to load
  against another with `ReferenceError: cljs$cst$2661$eval_BANG_ is not
  defined`. Rebuilding the app without re-running `script/place-plugins.mts`
  produces exactly that, and the startup-console assertion is what says so.
  Three times, which is why it is now checked rather than caught: see
  [`lt.util.load.compiled`](../src/lt/util/load/compiled.cljs).
- **A view that shadowed itself.** `(defn settings [{:keys [settings]}] …)` is
  the obvious way to write it, and the binding shadows the function — so
  `settings-screen`'s `(settings state)` was a *map lookup*, because calling a
  map with one argument is a lookup rather than an error in ClojureScript. It
  returned nil and half the screen drew nothing, with nothing logged. A view
  test written before anyone opened the screen is what found it, which is the
  case for writing them first even when the view is "obviously" right.

### A fallback hides its own use

Worth its own note, because it defeated every layer above.

Project-wide search runs the bundled ripgrep and falls back to the tree walk when
the binary is missing. The two produce **identical results** — that is what makes
the fallback safe — so when the worker looked for the binary in the wrong place,
search silently ran several times slower and *every existing check passed*: the
right matches, in the right files, with the right count.

Two things now make that visible, and the split is worth copying for any fallback:

- The summary line names the engine **only when it is the slow one**. A status
  line that reports what is fine is one nobody reads.
- A smoke check asserts which engine answered. It failed on its first run, which
  is how the path bug was found rather than shipped.

The general rule: **if two implementations are interchangeable by design, no test
of their output can tell you which one ran.** Something has to report it.

`make bench-search` is the other half — the numbers that say whether the fast one
is worth having, and on the first run they said the engine was not where the win
was coming from.

### A function and a projection are two different questions

The settings screen is the clearest example of a split worth copying, because it
is the same feature tested twice for genuinely different reasons.

`test/lt/ui/view_test.cljs` hands the two views a **literal map** and asks what
they draw. That covers every branch cheaply — filters, empty states, which
control a declared type produces — and it cannot tell you whether the map
resembles reality.

`test-e2e/settings.spec.ts` never writes a settings entry. It reads the real
behavior registry, counts the behaviors marked `:type :user`, and asserts the
projection produced exactly that many — then asserts the screen drew one row per
entry. So it fails if `lt.state.objects` and the object world stop agreeing,
which is the failure the view tests are structurally unable to see.

The rule: **test a view against a map, and test the projection against the
editor.** A test that does both at once is usually asserting a number somebody
typed.

## Changing a stylesheet without changing the rendering

Moving a declaration from one stylesheet to another is supposed to change
nothing, and there is no way to be sure by reading it: the cascade decides, and
the cascade depends on file order, specificity and what else matched. So ask
the browser.

```sh
node script/style-snapshot.mts before.json src/lt/objs/style.cljs
# …edit stylesheets…
node script/style-snapshot.mts after.json src/lt/objs/style.cljs
node script/style-snapshot.mts --compare before.json after.json
```

It boots Light Table, records the computed style of every element — around
1,175 of them — and names the element and property behind any difference. An
empty comparison is the proof that a refactor was one. It is deterministic
across runs, which is what makes it usable; a screenshot is not, because of
antialiasing, font loading and a cursor that blinks.

It is as useful in the other direction: what a new skin is *actually* doing,
element by element, rather than what it was meant to do.

## Looking at the UI, which is a layer of its own

```sh
make uiscan                              # every state, both window sizes
make uiscan ARGS="--only settings,keys"   # just those
make uiscan ARGS="--size narrow"          # one size
make uiscan ARGS=--strict                 # exit 1 if anything is found
```

`script/uiscan.sh` boots the editor headlessly, drives it into eighteen states at
two window sizes, writes a PNG of each to `builds/uiscan/`, and audits every one.
It exists because all four layers above were green while:

- the settings screen drew each row 60px into the one below it, so the whole
  screen was two layers of text on top of each other
- the keymap put a key at x=220 and its command at x=1350
- the docs panel's language chip floated over the tab strip in every window
- every button in the application turned light grey under the pointer
- opening the plugin manager rewrote the user's `plugin.edn` as garbage

None of that is invisible. It is just not what an assertion about an element
looks at. A test asks whether the element is there, holds the right text and
answers a click, and every one of those was true throughout.

The screenshots are for a person. The audits are for the times nobody looks, and
each is a measurement rather than a matter of taste:

| check | what it means |
|---|---|
| `overlap` | two siblings in normal flow whose boxes intersect. Flow does not overlap, so an intersection is a height that content did not fit |
| `spill` | a box holding more than it has room for, with nothing clipping it — the surplus is being painted over whatever comes next |
| `escape` | an absolutely positioned element painted outside the box it is positioned against, which is a panel that forgot `position: relative` |
| `error` | anything in the console. `lt.object` catches what a behavior throws, so a behavior that failed looks exactly like one that decided not to act |
| `clipped` | text wider than its box, and by how much. Often correct — a URL has to end somewhere — so it is reported rather than failed |

Two things make it trustworthy rather than noisy. It models **clipping**, and it
models it the way CSS does: an absolutely positioned element is clipped from its
containing block upward, not from its parent, which is the exact distinction the
docs-chip bug turned on. Without that it reported two elements that have a box and
are not painted. And every check was written against a bug that had shipped, then
checked against it — restoring the old stylesheets produces 33 findings, and the
current ones produce none.

**Two sizes**, because a layout that works only when there is room to spare is not
a layout. The keymap's thousand-pixel gap was invisible at 1024 and a connection
row's truncation was invisible at 1440, so neither size alone finds both.

**Every state starts from a reset** — the same recipe `fixtures.ts` uses, through
the same control channel. Without it each screenshot showed the residue of the
ones before: the narrow pass opened on seven tabs and a console bar left from the
wide one, and a settings screen with 130px of dead space under it that looked
exactly like a window which had failed to reflow. It had not. `Escape` closes a
filter list; it does not close a tab, empty a workspace or hide a bottombar.

**A state that wedges the editor cannot wedge the run.** `app.close()` goes
through the application's own close path, which asks the *renderer* — so a modal
left on screen, or an editor left dirty, makes it wait for ever. States clean up
after themselves, and the close is bounded and then killed, because a scanner that
does not exit is worse than one that misses a state.

It is a scanner, not a gate: `--strict` makes it one, but the default reports,
because a `clipped` finding is frequently the right answer and a tool that fails
on those gets turned off.

## The linter is a pinned binary

`npm run lint:cljs` fetches clj-kondo from its GitHub release, checks it
against a digest pinned in [`script/fetch-clj-kondo.mts`](../script/fetch-clj-kondo.mts),
and caches it in `.tools/`. That replaced the `clj-kondo` npm package, which
wrapped `binwrap`, which depends on `request`, which has been deprecated since
2020 — eight advisories reached this repository through it, three critical, all
of them marked "no fix available". It had also stopped working: npm blocks
dependency install scripts by default now, so a fresh clone got the package
without the binary it exists to install.

Fetching the binary ourselves is what the repository's own policy already
allowed — source in the repository, binaries fetched at build time, pinned and
checksummed.

## Headless, and how to watch

Both windowed layers run headless: `LT_HEADLESS` makes `createWindow` build
its windows with `show: false`. They still lay out, still run scripts, and
`getComputedStyle` still answers — they just never appear or take focus. A
suite that opens sixteen windows across your desktop and steals focus from
whatever you were typing into is a suite people stop running.

Hidden windows are not the whole of it on a Mac, because macOS puts an
*application* in the Dock when it launches rather than when it opens a window.
So under `LT_HEADLESS` the main process also asks for the `accessory`
activation policy, before `ready` — after it is half a second too late, and
what that looks like is the Dock growing and shrinking once per launch. The
policy is set from the Info.plist at process start, so a launch still
registers for an instant before the JS runs; the reason that is no longer
worth chasing is the section below.

Hidden is not the same as displayless. Electron has no headless mode —
Chromium's is not exposed — so Linux without a display still goes through
`xvfb-run`, which both wrappers arrange.

To watch a run, which is the only way to see what a failing check was looking
at:

```
script/smoke-test.sh --headed
script/e2e.sh --headed --grep "second window"
make smoke ARGS=--headed
```

`LT_HEADED` beats `LT_HEADLESS`, so exporting it turns any run visible without
editing the script that set the other one.

## One application per worker

`test-e2e/fixtures.ts` launches Light Table once per Playwright worker, not
once per test, and `reset` puts the editor back between tests — tabs closed,
workspace emptied, console cleared. Files run in parallel across four workers;
tests inside a file run in order, which is what sharing one editor requires.

It was one launch per test, which is the arrangement that needs no thought and
cost four minutes: booting Light Table is a second and a half, there are a
hundred tests, and almost the whole run was Electron starting. Four workers
each booting once is forty-five seconds for the same hundred tests, and
fourteen launches rather than a hundred and nine.

Two things make the sharing safe. Each `launch()` gets its own `LT_USER_DIR`
from `mkdtemp`, so parallel instances have no settings, workspace, log or cache
in common — `workers: 1` was justified by that having once been untrue. And a
test that genuinely needs a fresh process still calls `launch()` itself:
`session.spec.ts` does, because what it tests is what a second boot remembers.

The failure mode to know about is a test that leaves state `reset` does not
clear, which shows up as a test that passes alone and fails after another one.
Add it to `reset` rather than working around it in the test. Two already
happened and are worth knowing as shapes: `windows.spec.ts` opens second
`BrowserWindow`s and never closes them, so `reset` destroys every window but
the first; and `bands.spec.ts` puts an editor straight into `document.body`
rather than into a tabset, where `reset` cannot see it, so that file destroys
its own probe in an `afterEach` rather than on the last line of each test —
where a failing assertion above would skip it.

`npx playwright test --workers=1` puts every file in one application, which is
the worst case for leaks and the fastest way to find one.

### What `fixtures.ts` gives you

Reach for these rather than writing them again — each was a helper copied into
half the specs before it moved here.

| | |
|---|---|
| `evalClj(window, src, opts?)` | ClojureScript through the control surface. `tries`/`every` for something slow, `raw` to inspect a failed job rather than throw |
| `control(window, op, arg?)` | the control surface itself, for `snapshot`/`job`/`errors` |
| `openFile(window, path)` | open it and wait until the pool has an editor for it |
| `ltErrors()` | what the editor reported into its own console |
| `scratchDir(name)` | a directory of this test's own |
| `connectLocalClient(window)` | the "Light Table UI" client, as the connect panel makes it |
| `launch()` / `teardown()` / `ready()` | a whole application of your own, for the tests that need a second boot |

Write ClojureScript through `evalClj` rather than munged names in
`window.evaluate` — `(pool/by-path "x")` is what a person would type, where
`lt.objs.editor.pool.by_path` is a name a reviewer has to demangle, and a form
that throws comes back as the ClojureScript exception instead of a generic JS
error. The exceptions are real but few: driving a second window before its
control surface exists, and reading the DOM, which has no ClojureScript
equivalent.

## A locator that matches two views

Six consecutive Linux CI runs reported `a file is renamed in the row it is in`
as flaky. Retries hid it: the first attempt timed out after thirty seconds,
the retry passed in two, every time. macOS never failed.

The message was the whole answer, once it was read rather than summarised:

```
waiting for locator('#side .wstree .row').filter({ hasText: '…' })
element was detached from the DOM, retrying
```

`lt.ui.view/workspace` draws **either** the tree **or** the list of workspaces
you can switch to, and both draw `row/list-row`. So `.wstree .row` matched rows
belonging to whichever view was not going to be on screen: Playwright resolved
one, began its actionability checks, and the view swapped underneath it.
Scoping every one of them to `.wstree__tree` makes that impossible to hit, and
`the tree and the recents list are never both drawn` asserts the invariant the
scoping relies on — including that the recents list really does draw `.row`,
which is what made the ambiguity reachable.

Worth recording what this was **not**, because each was checked and none of it
needs checking again: the rows are keyed and an identical re-render keeps every
node; a click survives the tree being re-rendered every 60ms; `:tree/roots`
prunes the nodes of a removed root rather than leaving them behind; and
`:tree/changed` on an unloaded directory is a no-op.

The lesson is narrower than "avoid flaky selectors". **A selector scoped to a
container that holds one of two things is ambiguous by construction**, and the
symptom is a detachment rather than a wrong match — so it reads as timing and
gets retried instead of fixed.

**That was not the whole cause.** Scoping the locators did not stop it: the next
run reported the same test flaky, with a call log that now says the row *does*
resolve and is then detached while Playwright waits for it to be stable. The
mechanism is still unexplained, and what has been ruled out is above. Two things
changed for it anyway, both worth doing on their own terms: the test three
places earlier no longer leaves an editor open on a file it then deletes, and
the rename test opens its folder by dispatching `[:tree/toggle …]` rather than
by clicking — the click was setup, the subject is renaming, and clicking a row
is covered by the test written for it.

So the flake is removed rather than explained. That is a worse outcome than
understanding it, and it is recorded as such: if a tree row starts detaching
under Linux somewhere else, this is the thread to pull, not a new investigation.

The same run reported `and redrawing the chrome does not replace the webview`
flaky, for a different reason with the same shape: it read `querySelector
("#browser webview")` once, and between two renders that can return nothing,
which comes back as nil and is indistinguishable from a webview that was
replaced. It polls now. Polling cannot hide the failure it is there to catch —
a replacement webview has no probe on it and never grows one.

## The scripts are TypeScript

Everything in `script/` is `.mts`, which node runs directly by stripping the
types — so there is no build step between the source and what runs, and
`npm run typecheck` says whether the types were worth writing. `.mts` rather
than `.ts` because these are ES modules: a `.ts` file in this package would be
CommonJS, where `require` returns `any` and the types buy nothing.

Two things to know before editing `script/smoke-test.mts`. Its harness is one
long template literal that gets appended to a copy of the compiled `main.js`,
so a backtick anywhere in it — including in a comment — ends the string, and
the parse error is reported hundreds of lines later. And `__dirname` inside
that harness is the *window's*, which one check exists to assert is undefined;
the script's own is `SCRIPT_DIR` from `script/lib/paths.mts`.

## Probing a running editor

`script/lt-repl.sh` drives a real instance, and by default that instance uses
the same home directory as the one you develop in — so a probe that calls
`add.folder!` adds the folder to *your* workspace, and sessions then restore
it. Give it a scratch home:

```
LT_USER_DIR=$(mktemp -d) script/lt-repl.sh start
```

The workspace and session live under `deploy/core/ltcache` in a tree run,
which is gitignored; deleting it resets both.

## Running one thing

```
npm run test:electron -- --test-name-pattern windowOptions
script/e2e.sh --grep "second window"
script/e2e.sh --debug
npx playwright show-trace test-results/<name>/trace.zip
```

Traces and screenshots are kept only for failures, and CI uploads them as an
artifact.

**The ClojureScript suite has no way to run one namespace.** `:ns-regexp` in
`shadow-cljs.edn` selects every `-test$` namespace and the compiled
`target/test.js` reads no arguments, so `npm run test:cljs` is all of it or
none. That is worth knowing rather than looking for; the whole suite is a few
hundred assertions and finishes in about a second, so the loop is tight anyway.

## Why a namespace will not load under `:test`

The ClojureScript suite runs under plain node. Anything that touches a browser
or the Electron bridge **at load time** takes the whole run down with it, and
the error names the missing global rather than the namespace that wanted it:

```
SHADOW import error .../cljs-runtime/lt.util.dom.js
ReferenceError: HTMLCollection is not defined
```

Three namespaces do this, and everything that requires them inherits it:

| | |
|---|---|
| `lt.util.dom` | `extend-type js/HTMLCollection` / `js/NodeList` at the top level |
| `lt.util.bridge` | `(def bridge js/lightTable)` — the preload injects that, node has no such thing |
| `singultus.*` | a bare `js/document` reference while loading |

ClojureScript loads namespaces eagerly, so this is transitive and the closure is
large: `lt.object` requires `lt.util.dom`, and so does most of `lt.objs.*`. The
practical rule is that a unit test can reach pure functions and the state layer
(`lt.state`, `lt.actions`, `lt.ui.view`, `lt.ui.chrome`/`row`/`band`) and cannot
reach anything holding an object or a node.

`lt.state.objects` is the case worth naming, because it looks like it should be
testable and is not — it is the projection every view reads, and reaching the
object world is the whole of its job. It is covered at the e2e layer instead.

## How a ClojureScript test is written here

Consistent across every file in `test/`, and load-bearing enough to write down:

- **A sentence for a name.** `a-run-is-a-tab-like-any-other`,
  `later-declarations-win`, `a-plugin-that-needs-nothing-gets-nothing`. The name
  is the claim; if it does not read as one, the test is probably checking an
  implementation rather than a behaviour.
- **A prose docstring on the namespace** saying why this is tested the way it
  is, and naming the bug when there was one. `wire_test.cljs` on framing
  desync, `require_shim_test.cljs` on prefix collisions.
- **A reason on a `testing` block**, not a restatement of the assertion.
- **Assert something positive nearby every negative.** `(is (empty? …))` passes
  when a selector matches nothing, including when it matches nothing because
  the tag name has a typo in it. Every negative assertion in `test/` has a
  positive one in the same file proving the mechanism finds what it should.

`test/lt/support/hiccup.cljs` holds the walkers a view test needs — `nodes`,
`find-all`, `attrs-of`, `text-of`, `classes-in`. Require it rather than copying
the walker, which is how there came to be two of it.
