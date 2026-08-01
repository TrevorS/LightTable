# Testing

Four layers, each answering a question the one below it cannot.

| | what it runs | what it needs | how long |
|---|---|---|---|
| `make test-cljs` | ClojureScript, under node | nothing | seconds |
| `make test-electron` | the main process, under plain node | nothing | under a second |
| `make test-e2e` | the real application, per test | a build | ~12s |
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
