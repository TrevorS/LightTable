# Modernization changelog

Light Table's development branch had stopped running. This file records the work
to get it going again and bring its dependencies, runtime and build forward, in
the order it happened. It is separate from [the release
changelog](deploy/core/changelog.md), which tracks user-facing releases.

Everything below was verified by building and launching the app, not by
inspection alone. "Zero errors" means a fresh window opened a file and rendered
it with nothing logged to Light Table's own console.

---

## Starting state

The branch did not launch. The window came up blank, because the renderer threw
before `lt.objs.app` was ever defined:

```
Uncaught ReferenceError: global is not defined     bootstrap.js
TypeError: Cannot read property 'app' of undefined  LightTable.html
```

Alongside that: 527 compiler warnings, 19 npm advisories (6 critical), four CI
configurations of which none still ran, and an Electron 30 major versions
behind.

---

## Getting it to run again

**Two causes, both fallout from jumping Electron 2 → 12 → 13 without adjusting
anything around it.**

- `webPreferences` was never updated. `nodeIntegration` defaulted to true in
  Electron 2 but has been false since 5, and `contextIsolation` true since 12,
  so `js/global.String` in `lt.util.cljs` threw immediately and took the whole
  bundle with it.
- ClojureScript's `:process-shim` emits `var process = {env:{}}` into the
  bundle, which **overwrites Electron's real `process`** in the renderer.
  `js/process.platform` then read `undefined`, and `lt.objs.platform/normalize`
  threw `No matching clause` while the namespace was loading.

Confirmed by probing a live renderer: `process.platform` was `"linux"` in a
clean Electron window with identical `webPreferences`, and `undefined` inside
Light Table's.

**With the app booting, the next layer of breakage became reachable:**

- `lt.objs.clients.ws` used the socket.io 1.x API (`io.listen`, `.set`,
  `static.add`) against a pinned socket.io 4. The client shim is now served from
  an explicit http server.
- Dialogs called `showOpenDialog`/`showSaveDialog` synchronously; both have
  returned promises since Electron 6, so opening and saving silently did
  nothing.
- `platform/open` called `shell.openItem`, removed in Electron 9.
- Project search built result rows with `crate/raw`, which needs
  `goog.dom/htmlToDocumentFragment` — deleted from the Closure Library. Rows are
  built as nodes now, which also stops matched file content being parsed as
  HTML.
- The forked CodeMirror addons (`overlay`, `search`, `show-hint`) had been
  deleted in a bulk `node_modules` commit in January 2020. Their absence made
  `::init-codemirror` fail, so **no editor ever initialized**. Restored under
  `core/lighttable/`, where they are tracked rather than gitignored.

**The background worker turned out to be dead behind four failures, each hidden
by the one before it:**

- `script/build.sh` had the `cljsdeps` build commented out, so `cljsDeps.js` was
  never produced. It compiles fine; the interactive `rm -i` above it, which
  hangs a non-interactive build, is the likely reason it was disabled.
- That bundle needs `:process-shim false` too — it is `global.eval`'d into the
  worker, where the shim replaced the worker's `process` and with it
  `process.send`.
- `thread.cljs` set `ATOM_SHELL_INTERNAL_RUN_AS_NODE`, renamed to
  `ELECTRON_RUN_AS_NODE` in Electron 1.x, so the child booted as a full app
  rather than as Node. It also passed `:env` as the entire environment,
  dropping `PATH`.
- `lt.macros/background` handed `read-string` straight to
  `Array.prototype.map`, which calls it with `(element, index, array)`. Every
  background call died with `Invalid arity: 3`.

**Also fixed:** `files/trash!` called `.moveItemTotrash` — a typo, so it never
resolved to a real method even before Electron replaced `moveItemToTrash` with
`trashItem`. And `User/user_compiled.js`, a checked-in artifact, still called
`crate.core.html` from before that dependency was renamed, so the default user
plugin threw on every startup.

**Warnings: 527 → 0.** 464 shared one cause — `lt.object/behavior*` was private
while the public `behavior` macro expanded to it. The CI gate is now scoped to
`src/lt` so dependency warnings cannot mask ours.

---

## Dependencies

**Two Clojars forks and one deprecated package, all retired.**

- **singultus** (a fork of `crate`) is vendored under `src/singultus` — 573
  lines, only the namespaces actually used. It keeps its namespace names so
  plugins using `defui`/`defpartial` are unaffected. See
  [its README](src/singultus/README.md) for provenance: the code descends from
  crate, which is EPL, so it is not under Light Table's MIT license. Vendoring
  is what made `core/raw` fixable — it now parses through an inert `<template>`,
  verified by confirming an `img`/`onerror` payload builds a node without the
  handler firing.
- **fetch** (also a Clojars fork) backed three GETs, now going through
  `lt.util.js/fetch-text`. Its other user was the metrics namespace, which
  posted usage data to `http://app.kodowa.com` — a defunct company's domain,
  over plaintext, keyed by a persistent per-install id. Deleted outright.
- **request** was deprecated and the sole source of 13 of the 19 advisories,
  including five of the six criticals. `deploy/download-file` is now built on
  node `https`, keeping what request provided: redirects followed (the GitHub
  download endpoints rely on it), the strict-SSL toggle mapped onto
  `rejectUnauthorized`, and `http_proxy`/`https_proxy` honoured through a
  CONNECT tunnel. Tested against a real redirect chain, a redirect loop, a 404,
  and a live https download through a proxy.

**Two more breakages surfaced while doing that:** `untar` piped into
`tar.Extract`, dropped in tar 2.x, so extraction had been broken for plugin
installs and self-update alike; and once `fetch-text` let the devtools client
connect, it exposed crashes on protocol messages that carry no `url`.

**Versions.** Clojure 1.10.3 → 1.12.5 and ClojureScript 1.10.844 → 1.12.145,
following [apollonicolson](https://github.com/apollonicolson)'s commits
`b881894` and `b202e13` on the same project, reimplemented rather than merged
since this branch had diverged. Their observation that dropping `:externs` is a
prerequisite rather than tidying is the useful one: ClojureScript 1.12 aborts on
a missing externs file where 1.10 ignored it, and those files never existed
here.

Two of their npm removals were deliberately **not** taken:

- **`replace` is not unused.** `lt.objs.search` requires it as
  `(js/require (str js/ltpath "/core/node_modules/replace"))`, inside the
  `background` macro, so it loads in the worker thread. The path is assembled at
  runtime and no grep for a require will find it. Removing it breaks
  find-and-replace across a project. Five more requires share that shape, in
  `langs/behaviors`, `sidebar/navigate` and `auto-complete`.

  That reasoning was right about the require and wrong about the consequence:
  find-and-replace was *already* broken against the pinned version. The package
  is gone now — see *Project-wide search, which had stopped working*.
- `bencode` and `jsonify` were left in place at the time, since
  `deploy/core/node_modules` is also the pool plugins draw from, so absence from
  core does not establish absence of use. Both were removed later, once the
  runtime made the case clearer.

---

## Electron 13 → 43, Node 14 → 24

Runtime moves from Chromium 91 and Node 14.16 to **Chromium 150 and Node
24.18** — roughly five years of browser security fixes.

Diffing Electron 13's and 43's type definitions against every Electron API
Light Table actually calls turns up only three casualties: the `remote` module,
dropped in v14, and the `enableRemoteModule` and `worldSafeExecuteJavaScript`
webPreferences. Everything else survives, including the dialog functions, whose
only change was widening their first parameter from `BrowserWindow` to
`BaseWindow`.

`remote` was replaced with explicit ipc rather than `@electron/remote`. The shim
needs its own init wiring, adds a dependency to delete again later, and keeps
the arrangement that made `remote` a liability. The surface did not justify it:

- `app.getAppPath` and the three cli values are read once while the renderer
  boots, so they arrive together over one synchronous channel.
- The dialogs already returned promises, so they map onto ipc `invoke` with no
  change to callers.
- `BrowserWindow` mutations are fire-and-forget sends; geometry reads stay
  synchronous because they happen as the window is closing.
- Menus are described as data and built in the browser process. **Click handlers
  do not cross over**: each clickable item carries a token, the browser process
  reports which token was clicked, and the handler runs in the renderer as
  before. Plugins keep passing ordinary `:click` closures, and the
  behaviour-reduction chain that lets them contribute context-menu items is
  untouched. Verified end to end, including submenus, separators, and a click on
  a real `MenuItem` running the renderer's closure.

`nodeIntegration` and `contextIsolation` still exist in Electron 43 and keep
their current values. Tightening them is separate work that this does not depend
on.

**With Node 24 available, the dependencies pinned back by Node 14 could move —
and several stopped being dependencies at all:**

- `tar` → 7.x, which had been blocked because it requires builtins by their
  `node:` prefix. Clears the last critical advisory.
- `shelljs` **removed** — used for exactly `rm -r` and `cp -R`, both native in
  `fs` for years, and about a third of everything installed. `lt.objs.proc`
  required it without ever calling it.
- `yargs` **removed** — its whole job was two boolean flags and a usage string,
  which `node:util` `parseArgs` covers. Unrecognised arguments are still
  tolerated, keeping the case the old code called out: macOS appends an
  apple-event argument like `-psn_0_12381134` when opening a file from Finder.
- `jsonify` **removed** — a JSON polyfill, in an app that has run on Chromium
  its entire life.
- `bencode` **removed** — nothing in the tree references it. This is the one
  removal that cannot be proven safe from this repository alone; restore it if a
  plugin turns out to want it.

One behavioural difference to watch: `cp -R` copies *into* a destination
directory, whereas `fs.cpSync` treats the destination as the copy itself. Every
`files/copy` caller already passed a full destination path, so only
`deploy/move-tmp` needed adjusting.

**Installed packages: 100 → 62. Advisories: 19 (6 critical) → 3 (0 critical).**

The three remaining all come from `replace` → an old `minimatch` →
`brace-expansion`. Forcing a newer `minimatch` breaks `replace`, whose call
signature changed. Clearing them means either an upstream fix or replacing
`replace` with a small in-tree file walker — worth doing, but not worth rushing,
since it is the project-wide search implementation.

---

## Toolchain and CI

- Builds verified on **JDK 25** (and JDK 21). `javax.xml.bind/jaxb-api`, added
  when JAXB left the JDK in 11, is no longer needed and has been dropped.
- Four CI configurations were removed: `.travis.yml` (travis-ci.org has been
  gone for years), `appveyor.yml`, `.circleci/` (pinned to `clojure:lein-2.8.0`,
  a Leiningen 2.8 and JDK 8 era image), and `.whitesource`. The README carried
  badges for those plus Semaphore and codecov, none of which reported anything.
- Replaced by a single [GitHub Actions
  workflow](.github/workflows/build.yml) on JDK 25 and Node 24.

The warning gate is scoped to `src/lt` deliberately. `cljsbuild` reports
problems as warnings while still exiting zero, so the job has to read the
output — but JDK 25 emits its own `sun.misc.Unsafe` deprecation warnings from
Closure's bundled protobuf, and the original `grep WARNING:` would have failed
on those.

---

## Build system

shadow-cljs builds everything Light Table ships. `lein-cljsbuild` is gone, and
so is `project.clj` — see *API documentation* below for the last thing that was
keeping it.

Three things had to change before the switch was possible.

**`src/lt/core.cljs` is new.** Behaviors, commands and objects register as a side
effect of namespaces loading, and eighteen of them were required by nothing —
they were only ever included because `:optimizations :simple` compiles the whole
source path into one file. A bundler keeps only what an entry point reaches, so
those are now listed explicitly. `lt.core-test` guards it, failing on the commit
that introduces an orphan rather than whenever somebody notices a missing
feature.

**The output cannot be wrapped in a closure.** Light Table's plugin API *is* the
global `lt.*` namespaces, `LightTable.html` calls `lt.objs.app.init()` directly,
and `js/lt.objs.console.error` is used throughout. `:output-wrapper false` is
load-bearing, not tidiable. The same constraint means `lt.objs.editor` has to
publish `window.CodeMirror` now that requiring the module no longer creates it.

**The worker thread had to stop being shipped as source.** Background work used
to be sent across as text: the renderer stringified a compiled function and the
worker `eval`'d it against a second ClojureScript bundle built solely to give
that eval a `cljs.core`. That broke under any optimization hoisting values out
of a function body, since the hoisted names only existed back in the renderer —
which is exactly what shadow's release builds do. Dev builds do not hoist and
`:optimize-constants false` is not honoured, so there was no setting to reach
for.

The four background jobs are now ordinary compiled namespaces under
`lt.background`, built by their own target. Extraction was possible because each
was already self-contained. The renderer names the work it wants —
`(thread/job :search)` — so both sides are compiled and checkable. `src-cljsdeps`
and `threadworker.js` are gone with it.

> **Plugin API break:** `lt.macros/background` is removed. It is published API,
> but the mechanism it depended on cannot be kept — the macro existed to produce
> a function for stringifying. Background work now means adding a job to
> `lt.background.worker`.

### Eval

Of the 18 `load/js`/`load/css` sites, the CodeMirror ones are now requires,
including the loop that eagerly eval'd **122 mode files, about 1.1 MB, at every
startup**. Modes and fold addons stay discovered rather than listed, since the
set is whatever the installed CodeMirror ships, but they go through
`js/require`; the point was the eval, not the enumeration.

Five sites remain, none of them oversights:

| site | why |
|---|---|
| `plugins.cljs` | loads plugin code by a runtime path — the genuinely dynamic case |
| `dragdrop.js`, `fuzzy.js` | Light Table's own scripts, written against the global scope rather than as modules |
| `overlay.js`, `search.js`, `show-hint.js` | the forked CodeMirror addons; `overlay.js` carries a UMD wrapper resolving relative to the stock addon layout |

### Warnings

**Zero, on both targets**, down from 527 at the start. CI gates on exactly that:
no allowance, no exempt classes.

Getting there was two different jobs. The first 527 were one root cause —
`lt.object/behavior*` was private while the public macro expanded to it. The
remaining ~200 were externs inference: shadow could not tell that the target of
an interop call was a JavaScript object, because nothing said so.

The fix is a `^js` hint at the binding, and since a hint is an assertion about
the value, each was checked against what actually flows through rather than
applied by pattern. Most fell in groups once the right binding was found —
hinting `lt.objs.editor/->cm-ed` alone removed 69, because nearly every
CodeMirror call in that namespace goes through it.

Five sites would not take a hint at all: `^js` on an inline expression like
`(.-score (aget items 4))` is ignored, because it has to attach to a binding.
Those are let-bound now, which reads better regardless.

One warning was hiding behind a hole in the gate, which matched
`WARNING #n - :type` and so missed Closure's own warnings, which carry no type.
`browser.cljs` assigned `js/lttools` as a bare global; shadow inferred an extern
and Closure reported the write as assigning to a constant twice.

## Tests

There were none; `lein-cloverage` and `lein-codox` were configured against
nothing. Three things now exist:

- **`lt.core-test`** — the entry-point reachability guard described above.
- **`lt.util.kahn-test`** — the topological sort behind plugin load order,
  including the cycle detection reported to users. It is the one piece of
  non-trivial logic that loads cleanly outside Electron; `lt.object`,
  `lt.objs.files` and `lt.objs.menu` all reach for Electron or the DOM while
  loading.
- **`script/smoke-test.sh`** — boots the real application using the real
  `main.js`, opens a file, and checks fourteen things: ipc-resolved app path,
  platform and window number, CodeMirror and its forked addons, registered
  language modes and fold helpers, a rendered editor, the behavior count, worker
  connection, a background job round trip, and an empty console.

The smoke test matters more here than unit tests do. Every failure this project
has hit while modernizing has been a load-time or cross-process one — a
namespace throwing on load, an Electron API that no longer exists, an ipc
channel with nobody listening, a worker unable to talk back. None are reachable
from unit tests. Both the test and the CI gate were verified by deliberately
breaking things and confirming they go red.

## Plugins

The Clojure and Javascript plugins were cloned at the versions `script/build.sh`
pins and run against this branch. Two things stopped them loading.

**`bencode` and `shelljs` had to be restored.** They were removed as
unreferenced, with the caveat that `deploy/core/node_modules` is also the pool
plugins draw from. That caveat was the whole story: Clojure needs `bencode` for
the nREPL wire protocol, Javascript needs `shelljs`, and without them neither
plugin loads at all. Light Table's own code still uses neither.

**Both call `crate.core.html`, which no longer exists.** Light Table's hiccup
library was crate and became a fork named singultus *before* this work; plugins
ship precompiled and are not rebuilt when the host changes, so both have been
broken since that rename. `lt.compat` republishes `crate.core` and
`crate.binding` as aliases onto the vendored singultus functions.

With both fixed the plugins load and register their behaviors — 467 to 561 — and
the smoke test asserts it.

The general point is worth stating: **a renamed namespace here does not fail a
downstream build, because there is no downstream build.** It fails in a user's
editor, in whichever feature happened to touch it.

## Where eval still lives

Worth separating, because the three kinds have very different answers.

| kind | sites | can it go? |
|---|---|---|
| Asset loading | 5 | Yes. Three are Light Table's own global-scope scripts; two are forked CodeMirror addons, one with a UMD wrapper resolving to the stock layout. All mechanical. |
| Plugin loading | 1 | Only by changing how plugins are distributed. `plugins.cljs` loads plugin JavaScript by a path known at runtime. |
| Self-evaluation | 2 | **No.** This is the product. |

That last row is the one that matters. `lt.objs.clients.local` is the
"Light Table UI" connector — evaluating ClojureScript and JavaScript against the
running editor, which is how Light Table is customized from inside itself. It
calls `js/eval` deliberately.

## A capability bridge between the window and the desktop

`src-electron/preload.ts` is new, and the window now talks to its privileged
half through it rather than through Electron directly. `js/require("electron")`
does not appear in `src/` any more.

The shape was not a style choice. Electron has sandboxed renderers by default
since v20, and a sandboxed preload cannot require `fs`, `child_process`, `net`,
`path` or `os` — only `electron`, `events`, `timers` and `url`. A preload
therefore cannot be a thin wrapper over Node even if one wanted it to be: every
privileged operation has to run in the main process, with the preload carrying
nothing but the request. That constraint forces a named list, which is exactly
what a permission model needs.

What the window can ask for:

| | |
|---|---|
| `shell` | open a path or url, reveal in file manager, move to trash |
| `clipboard` | read and write text |
| `zoom` | this window's zoom factor |
| `window` | close, focus, minimize, geometry, devtools, app events |
| `dialog` | open and save dialogs |
| `menu` | context menus and the menubar |
| `host` | app path, platform, argv, files to open — read once at startup |

`lt.util.bridge` is the only namespace that names that object. It replaced
`lt.util.ipc`, which handed out the raw `ipcRenderer`: with that, the set of
things the window could do was however many `ipc/send` calls existed,
discoverable only by grep.

Three things got narrower on the way:

- `lt:window-call` took a method name and invoked it on `BrowserWindow` — "call
  whatever you like on the window" as a channel. The eight methods Light Table
  actually uses are named in main; anything else is refused.
- `initWindow` and `toggleDevTools` took a window id from the renderer, so a
  window could name one it did not own. They use the sender's window now, which
  is the only one they were ever called with.
- Deciding whether a string is a path or a url took `fs.existsSync` in the
  window. That decision moved to the privileged side rather than handing the
  window a filesystem to make it with. Same for the platform name, which was
  read off the renderer's own `process`.

Not everything forwards. `webFrame` is renderer-side and the preload shares the
window's frame, so zoom is answered locally.

Verified by five smoke checks: the bridge is exposed, the window is really going
through it, zoom resolves, the clipboard round-trips in both directions, and the
menubar — built by a behavior in the window — actually arrives in the main
process.

## The browser tab, which had not worked in years

Checking whether `<webview>` still worked turned up that it did not, and had not
for a long time. Four faults, stacked, each hiding the next:

1. **`webviewTag` defaults to false since Electron 5** and
   `deploy/core/package.json` never set it. The `<webview>` element was parsed
   as a plain `HTMLElement` with no Electron API on it — inert, and silent about
   it. Light Table was on Electron 13 before this branch, so this predates the
   upgrade rather than being caused by it.
2. **`browserInjection.js` called `ipcMain.on`**, in a renderer. `ipcMain` is
   undefined there, so the guest preload threw on its third line and none of the
   browser tab's messaging existed.
3. **Its listeners were missing Electron's event argument**, so they would have
   read the event as their payload had they ever run.
4. **A guest's `contextIsolation` defaults to true**, which puts a preload in an
   isolated world. `window.lttools` and `eval.call(window, ...)` would not have
   reached the page even once the preload loaded — and reaching the page is the
   entire feature.

Fixed, and the injection is now `src-electron/browserInjection.ts` — it is a
preload, so it belongs with the other privileged code. Two smoke checks cover
it: the element is a real `WebViewElement`, and the injection is visible from
inside the guest page.

While there: `getUrl` became `getURL` at some point, and the exception path
called `cljs.core.pr_str` unconditionally, which throws its own error on any
page that is not a ClojureScript app.

The guest's preferences are settled in `secureWebContents()` rather than by the
`<webview>` attributes, which the window used to set. A guest is an arbitrary
website; what runs inside it is not the window's decision. It gets no Node
integration and Light Table's own injection, and the `:preload` attribute is
gone from `lt.objs.browser` entirely.

That function is also where `window.open` is now handled. Nothing in Light
Table calls it, but with no handler set Electron's default creates a
`BrowserWindow` inheriting this application's preferences — node integration
included — pointed at whatever url it was given. It is denied; an `http(s)`
url goes to the desktop's browser instead.

## Every JavaScript file in the tree, and where it came from

Thirteen files, 2,400 lines. Auditing them for the monorepo question — what can
this repository actually build from source? — the answer sorted into four
groups, and only one file was a compiler artifact.

**Compiler output that nothing rebuilt.** `deploy/core/User/user_compiled.js`,
the default user plugin: 68 lines emitted by a ClojureScript old enough to still
produce `goog.provide` and `.call(null, ...)`, from a source file sitting in the
same directory. It had drifted — still calling `crate.core/html` years after
that rename, so it threw on every startup until it was hand-patched earlier in
this branch. It is a module of the `:app` build now, compiled against the editor
it extends, and gitignored. A rename in the host is a build error.

That is the shape the monorepo wants: **a module, not a second build.** It
shares the bundle's `cljs.core`, references its hoisted constants, and comes out
as a plain script the existing plugin loader loads with no format of its own.
Nothing about it is special to the User plugin — the same applies to the seven
bundled plugins `script/build.sh` currently clones as prebuilt artifacts.

**Vendored third-party code.** Lifted, so per the standing rule it gets ported
rather than carried:

| | lines | origin | status |
|---|---|---|---|
| `util/keyevents.js` | 1,086 | Mousetrap 1.6.0, Apache 2.0 | **A real fork.** Diffed against upstream 1.6.0: four Light Table deviations, all comment-marked — a `handleKeyUp` hook, a `keyDownOnly` helper, a rewrite of the keydown/keypress/keyup dispatch so a keypress character can be paired with its keydown keycode, and a backported numpad fix (upstream PR #258). Not replaceable with the npm package: deviation three is inside a private function. Port. |
| `util/throttle.js` | 9 | jQuery throttle/debounce 1.1, Ben Alman, 2010 | **Gone.** Minified, reached through a global called `Cowboy`, and twenty lines of standard behaviour. Now `lt.util.js/throttle` and `/debounce`, with tests. |
| `util/fuzzy.js` | 110 | `string_score` (minified) plus Light Table's own `wrapMatch` | Patches `String.prototype.score`. Port; the monkey patch should not survive it. |
| forked CodeMirror addons | 281 | CodeMirror | Vendored forks. Judged not worth typing here; later reversed — see below. Typing them against real CodeMirror 5 declarations cost less than expected and found two dead locals. |

**Light Table's own hand-written JavaScript**: `ws.js` (175, the socket.io shim
served to connected browsers), `ui/dragdrop.js` (100), `background/behaviorsParser.js`
(121), `background/walkdir2.js` (74). All portable, none large — and all four are
TypeScript now.

**This project's own tooling**: `script/smoke-test.js` and `script/screenshot.js`.

Two of these are loaded by `load/js`, which evaluates them into global scope —
`fuzzy.js` and `dragdrop.js`. Porting them to modules that declare their exports
retires those eval sites as a side effect, which is the prerequisite for any CSP
conversation.

## What plugins actually need, measured

Before designing a manifest it was worth knowing what plugins do, so 20
published ones were cloned and read — the most-released across languages, tools
and themes, which is the closest thing to a popularity signal the metadata
repository offers.

The naive measurement says the ecosystem is nearly ready to be sandboxed.
Counting direct `require()` calls, **10 of 20 plugins need nothing from Node**:

| module | plugins |
|---|---|
| `child_process` | 7/20 |
| `path` | 4/20 |
| `fs` | 3/20 |
| `net`, `os` | 2/20 each |
| `buffer`, `http` | 1/20 each |

That measurement is wrong, and the way it is wrong is the finding. The Terminal
plugin `require`s nothing at all — and spawns processes, through
`lt.objs.proc`. Counting Light Table's *own* privileged namespaces, only **3 of
20** need nothing (Emmet, Paredit, and a theme):

| namespace | plugins |
|---|---|
| `lt.objs.files` — the filesystem | 15/20 |
| `lt.objs.plugins` — plugin management | 9/20 |
| `lt.objs.proc` — spawning processes | 8/20 |
| `lt.objs.platform` — shell and clipboard | 6/20 |
| `lt.objs.thread` — the background worker | 4/20 |
| `lt.objs.clients.tcp` / `.ws` — sockets | 4/20 |

**A manifest covering only `require` would have been security theatre.** It
would have called Terminal safe.

Two things follow. Capabilities have to be named after what a plugin *does*, so
that both routes to a thing — Node directly, or Light Table's API — map onto one
name. And since the dominant route is Light Table's own API, which is a
chokepoint the editor controls, **a manifest can be enforced without
`contextIsolation` at all**. That is what turns the plugin blocker from a wall
into a migration.

The vocabulary that came out of it — `:files`, `:processes`, `:network`,
`:desktop`, `:clipboard`, `:worker`, `:plugins` — and the three levels of
enforcement are documented in [plugins/README.md](plugins/README.md).

`lt.objs.plugins.capabilities` implements the inference, and **Plugins: Report
what each plugin can do** runs it over everything installed. Running the shipped
version over the same 20 plugins refines the numbers above: counting both routes
to a thing as one capability, **4 of 20 need nothing** — `:files` 15,
`:processes` 10, `:plugins` 8, `:network` and `:desktop` 5 each, `:worker` 3,
`:clipboard` 1.

Inference matters as much as the vocabulary. Almost no published plugin declares
anything, because they all predate the idea, so enforcement built on
declarations alone would have applied to nothing. Inferring the same evidence
means an author can see what they would have to declare before declaring it and
a user can see what they are installing — while every existing plugin keeps
working. Nothing is denied yet; that gate is the next step, and it is the point
where `:undeclared` stops being a report and becomes a refusal.

## The first plugin built in this repository

Worth settling first, because it decides whether TypeScript is a new mechanism
or an existing one: **Light Table never required plugins to be ClojureScript.**
`:lt.objs.plugins/load-js` evaluates JavaScript and does not care what produced
it. Of the 20 surveyed, Emmet and Claire are plain hand-written JavaScript with
no ClojureScript at all — Emmet by Light Table's own author. So a TypeScript
plugin is a third source language for an unchanged loader, and nothing in the
editor had to change to accept one.

`plugins/TypeScript` is a Light Table plugin written in TypeScript, built from
source against the editor it extends, carrying a capability manifest. It is
small on purpose: what it demonstrates is the shape.

- Plugins load as global-scope scripts, so the build is `module: none` plus
  `outFile`, with `plugins/lib/lt.ts` concatenated ahead of the plugin's
  sources. That helper is a `namespace` rather than a module because namespaces
  compile to idempotent globals.
- `plugins/types/lighttable.d.ts` describes the editor's API. It is
  hand-written, because that API has no machine-readable schema, so it describes
  rather than guarantees — but it catches the mistakes that actually happen, and
  everything in it is exercised against the running editor by the smoke test.
- `tsconfig.json` sets `"types": []`. A plugin that declares no filesystem
  capability should not be able to see `fs`'s signatures either.
- The output is strict-mode and `load/js` runs it through `window.eval`, so a
  plugin's top-level bindings stay inside that eval rather than becoming
  globals. Two plugins can carry their own copy of the helpers without
  colliding; the cost is that anything published has to be assigned somewhere
  deliberately.

Verified by the smoke test with and without the cloned flagship plugins, since
`deploy/plugins` is no longer empty in CI.

## Every eval of Light Table's own code is gone

`fuzzy.js` and `dragdrop.js` were loaded with `load/js`, which evaluates into
global scope. They are TypeScript modules in `src-window/` now, required rather
than evaluated. That leaves **one** `load/js` call in the whole codebase — the
plugin loader — and that one is inherent, since a plugin is arbitrary
JavaScript by definition.

`fuzzy.js` opened by patching `String.prototype` with a `score` method, a
minified copy of `string_score`. Extending `String.prototype` from an editor
that hosts plugins is a poor trade at the best of times; here it was also
invisible, because the call site read `(.score scorer search)` on an ordinary
string with nothing to say where that came from. De-minified with real names,
behaviour unchanged, and the smoke test checks the scoring rather than the
module's presence — a wrong port would rank results badly rather than throw.

**The three "forked CodeMirror addons" turned out to be two.** `overlay.js` was
a copy of CodeMirror **4.1.1**'s addon with no Light Table changes at all, and
the packaged 5.65 version carries an upstream fix it was missing. It is
required from `node_modules` now and the copy is deleted. `search.js` (153 lines
against upstream's 295) and `show-hint.js` (43 against 523) really are Light
Table's own trimmed code, and stay — required rather than evaluated.

Requiring `overlay` is what exposed the difference between the two mechanisms:
it has a UMD wrapper, so under `eval` it took the browser branch and used the
global, while under `require` its CommonJS branch tried to resolve
`../../lib/codemirror` and failed. That failure is what sent the diff hunting.

One fragility found and closed on the way: a ClojureScript plugin module
references the bundle's hoisted constants, which are numbered per compilation,
so Paredit failed with an undefined `cljs$cst$900` after the bundle was rebuilt
without re-placing it. Placement is part of `build:cljs` now rather than a step
that can be run separately — the stale combination is impossible rather than
merely documented. It also means these artifacts are **not** independently
distributable: an in-tree plugin module only runs against the bundle it was
compiled with.

## What crossing costs, measured

Moving Light Table's remaining Node use behind the bridge means each call
crosses a boundary. Which boundary turns out to be the whole question. Measured
on Electron 43, 2,000 iterations per number:

| | direct | via contextBridge | via sync ipc |
|---|---|---|---|
| `existsSync` | 1.7µs | **2.9µs** (1.8x) | 194µs (118x) |
| `readFileSync` | 6.4µs | **9.1µs** (1.4x) | 242µs (38x) |

The first measurement taken here was the ipc one alone, and the conclusion drawn
from it — that `lt.objs.files` could not be ported and would need redesigning
around coarse asynchronous calls — was wrong. `contextIsolation` separates the
window's JavaScript world from the preload's; it does not require leaving the
process. A preload with `sandbox: false` keeps Node, so a capability can be
served from the isolated world directly, and the window pays about a microsecond
to cross rather than two hundred.

So `lt.objs.files` can be moved rather than redesigned, and its synchronous API
stays synchronous.

The stateful cases work too, which was the other open question. A `net.Socket`
sent across `contextBridge` arrives as a plain `Object` with its prototype gone,
so sockets and child processes need a handle — the real object stays in the
preload and a set of functions crosses. Verified by spawning a real child
process, streaming its stdout into the window through a callback, and reading
its exit code. A callback crossing costs 0.68µs, which is cheap enough for
streaming.

The full scope — 77 touch points across 9 namespaces, what each needs, the
ordering, and where the ecosystem risk sits — is in
[doc/context-isolation.md](doc/context-isolation.md).

## The window no longer touches Node

All 77 touch points are migrated. **There is no `js/require`, `js/process` or
`js/__dirname` left in `src/`.** Every filesystem call, process, socket and
download goes through the capability list in `src-electron/preload.ts`.

The mechanical ones were mechanical: `lt.objs.files`'s 32 calls kept their
synchronous shape and their names, because the bridge names match Node's
exactly. The rest were more interesting.

**`lt.objs.deploy` stopped being an http client.** It held url parsing, redirect
following, a CONNECT tunnel for proxied https and a write stream, all so that a
file could be downloaded. Naming the capability `download` took about seventy
lines out of the window. The coarse-capability argument was made for security
reasons; it paid in code size first, and the same call applies to both client
servers — what the window does with a tcp server is wait for connections and
send lines, so that is the surface, and the server itself moved across whole.

**Identity does not survive the crossing**, twice. `fs.watchFile` pairs with
`unwatchFile` keyed on the callback passed in, which cannot work through a
proxy — so watching returns a handle and the listener never leaves the preload.
Sockets were compared against stored ones, so connections are numbered instead,
which the window only ever needed since it did nothing with a socket but store
and compare it.

**Names must match exactly or not resemble at all.** Three bridge file methods
were shortened (`readSync`) while the rest were identical (`existsSync`). That
inconsistency broke the migration silently, and the failure surfaced as a
27-key smoke-test expression reporting only that it threw.

Verified beyond the suite: a spawned process streaming stdout and reporting
ENOENT, `exec` collecting output, a 25KB download through a real proxy
tunnel, a 404 rejecting rather than writing a truncated file, and a real
tcp client connecting, announcing itself, appearing with its connection id and
being removed on disconnect.

## A REPL, which should have come first

Every question about the assembled application was costing a throwaway Electron
harness: a bespoke probe, fifteen seconds of boot, one answer, no follow-up.
`script/lt-repl.sh` boots the real application once and attaches over the
DevTools protocol `main.js` already opens a port for.

```sh
script/lt-repl.sh start
script/lt-repl.sh cljs "files/cwd"
script/lt-repl.sh stop
```

It earned itself on the first use, naming the line a probe cycle had failed to
find. Two of its own bugs are worth recording because both produced confident
wrong answers rather than errors: `replMode` stopped `awaitPromise` taking
effect, so every async probe returned `{}`; and the ClojureScript name munger
rewrote `/` inside string literals, so a path argument arrived mangled and the
call answered about nothing. The second sent a real debugging session after a
bug in `lt.objs.files` that did not exist.

## A `require` for plugins, so isolation can be turned on

`contextIsolation: true` takes `require()` away from the window entirely. Light
Table's own code stopped needing it some time ago; plugins had not, and plugins
ship precompiled, so nobody can rebuild them for users who already have them.
That was the last thing between here and the flip.

There is now a `require` in the window that is Light Table's rather than Node's.
It is installed *today*, while isolation is still off, deliberately: a plugin
needing something it does not serve fails now, where it can be fixed, rather
than on the day of the flip. Three pieces, split so the one with judgement in
it can be unit-tested:

- **`lt.objs.plugins.require-shim`** decides. It reads the call stack, finds the
  innermost frame under a plugin's directory — plugin code is evaluated with a
  `sourceURL`, so a frame names its file — and looks that plugin's capabilities
  up. A plugin with a manifest is held to it; one without gets what Light Table
  infers it uses, so nothing breaks the day this turns on. A stack costs
  1.3–2.7µs and `require` happens at load, not per call.
- **`lt.objs.plugins.node-modules`** serves. `net`, `fs`, `path`, `os` and
  `shelljs` are bridge-backed and gated on a capability; `buffer`, `events`,
  `bencode` and `util` are bundled and gated on nothing, because they reach
  nothing. Plus the globals Node code expects to find: `Buffer`, `global`,
  `setImmediate`, and a `process` with the three fields plugin code was found
  to read.
- **`lt.objs.plugins.local-modules`** loads what a plugin vendored. Plugins ship
  their own `node_modules`, and those are not builtins to serve a facade for —
  they are files to resolve and run. Node's resolution algorithm minus the parts
  nothing uses, evaluated through the same CommonJS wrapper and `sourceURL` as
  everything else, so attribution still works from inside vendored code.

What the shim is *for* is worth stating, because it is easy to overclaim: it is
compatibility, and it is telling an honest plugin what it may use. It is not
containment. A plugin runs in the window and can reach the bridge directly
whatever `require` says. What contains a plugin is the bridge's surface.

### The chain that decided the shape

The open question was never attribution, it was whether a per-module shim could
carry a value *between* modules. The Clojure plugin's nREPL client is the case:
socket data → `Buffer.concat` → `bencode.decode`, three modules and one value
passing through all of them.

It works, and the reason is that only the socket crosses. Bytes arrive as a
`Uint8Array` — copied rather than viewed, since node pools small Buffers into a
shared ArrayBuffer — and everything after that is in the window, because
`Buffer` and `bencode` are bundled rather than served from the preload. One
world throughout. Had `Buffer` been a bridge capability the concatenated value
would have crossed twice and arrived as something else both times.

Verified end to end against a bencode server that splits replies mid-message
and mid-character, driving the modules the plugin itself captured at load.

Trying it turned up two things unrelated to isolation:

- **`bencode@4.0.1` cannot do what the plugin asks.** `decode(data, 'utf-8')`
  throws on any dictionary: `decode.dictionary` decodes the key, then decodes
  the result again. Every nREPL message is a dictionary, so the Clojure plugin's
  client could not have worked at all on this checkout. Pinned to `^2.0.3`,
  the API it was written against.
- **The Javascript plugin was not fully loading.** It requires a vendored
  `harbor` to find a free port, which is what prompted the local module loader.
  With it, the plugin's behaviors go from 535 to 560 registered.

`script/smoke-test.js` grew four checks covering this, and is at 41.

## Context isolation, on

```
nodeIntegration:  false     the window has no require
contextIsolation: true      window and preload are separate worlds
sandbox:          false     the preload keeps Node, so capabilities are cheap
```

The window has no `require`, no `process`, no `__dirname` and no `module` of
Node's. Everything privileged goes through the capability list in
`src-electron/preload.ts`; plugins reach Node through a `require` Light Table
serves rather than Node's own.

`sandbox: false` is deliberate rather than left over. Electron sandboxes
renderers by default, and a sandboxed preload has no Node either — which would
push every filesystem call from 2.9µs to 194µs and force exactly the coarse
asynchronous redesign the measurements above showed was unnecessary.

The last thing before the flip was **`tar`**: `lt.objs.deploy` unpacked a
downloaded release with it, the only call in Light Table's own code still
falling through to a real `require`. It became `files.extract`, a capability in
the same shape as `download` — what the window wants is a release unpacked, not
a stream to pipe, and the whole of tar stays privileged.

The flip itself was three lines in `deploy/core/package.json` plus three
window-side Node references nothing had noticed until Node was actually gone:

- `process.on("uncaughtException")` in `lt.objs.console`, now `window`'s
  `error` and `unhandledrejection` events — which catch more than the original
  did, since the original only ever saw what reached node's handler.
- `process.execPath` in `lt.objs.cli`, a host capability.
- `js/global.String` and `js/global.Array` in `lt.util.cljs`, which are the
  window's own `String` and `Array` and always were. Naming them directly made
  the compiler able to see what it had not been able to before, so
  `:extending-base-js-type` is now switched off for this build with a note:
  extending them is what makes `("key" some-map)` work, and that is published
  API rather than an accident.

Nothing was redesigned. `lt.objs.files` is still synchronous, self-evaluation
still works, and the smoke test is at 42 — including isolation itself,
established by observation rather than by reading the config back: no
`__dirname`, no `module`, a bridge that is a contextBridge proxy rather than the
preload's own object, and a `require` that refuses a builtin Node would serve.

One predicted degradation is real. `util.inspect` runs in the preload, so what
reaches it is a clone: a function prints as `[Function (anonymous)]` and a DOM
node as `HTMLBodyElement {}`. It does not bite in practice —
`cljs-result-format` handles functions before it reaches `console/inspect`, and
plain data crosses intact — but it is a genuine loss of fidelity.

- **A CSP without `unsafe-eval`** remains separately unavailable: it cannot be
  adopted without removing self-evaluation, and self-evaluation is the feature
  Light Table is named for. The eval surface has narrowed as far as it usefully
  can. What remains is not cleanup deferred; it is the product.

---

# What comes next

Scouted but not done. Ordered roughly by how much they unblock.

## Security, without giving up what Light Table is

This deserves stating carefully, because the obvious move is wrong.

The instinct is `contextIsolation: true` plus a strict CSP, and be done. But
Light Table's whole proposition is evaluating code against the running editor —
`lt.objs.clients.local`, the "Light Table UI" connector, calls `js/eval`
deliberately. A CSP without `unsafe-eval` does not harden that feature; it
deletes it. Any plan that starts by banning `eval` has already lost the argument.

The useful reframing is that **the two things people bundle together are
separable, and only one of them is a real cost.**

- `contextIsolation` is about *who can reach Node*. Nothing about
  self-evaluation requires the window to hold `child_process`.
- A CSP is about *what can be executed*. This is where the feature lives.

Split that way, most of the value is available without touching the feature. A
window that cannot spawn processes or read arbitrary files is a much smaller
target even while it can still evaluate expressions, because the interesting
attacks are not "run some JavaScript in a sandboxed page" — they are "run some
JavaScript that then shells out".

The bridge is the first half of that, and it is done. What is left is the part
that was always going to be harder, and it is not `eval`:

1. ~~**Light Table's own Node use.**~~ Done — 77 touch points across 9
   namespaces, all as capabilities rather than as an `fs` passthrough. The hard
   case was `lt.objs.proc`, where spawning a process and streaming its output is
   stateful, and it settled the pattern: the object stays privileged and a set
   of functions crosses.
2. ~~**Plugins are the actual blocker**~~ — built, and the compatibility route
   held. They are fetched over the network, loaded into the window, and ship
   precompiled, so the host cannot rebuild them: the Clojure plugin calls
   `require("net")` at namespace load for nREPL and the Javascript plugin does
   the same.

   The three ways out turned out to be **three levels rather than three
   alternatives**, which is the useful reframing — see
   [plugins/README.md](plugins/README.md) for the full design. Briefly: legacy
   plugins keep working unmanifested and can have a manifest inferred by
   scanning them; a manifested plugin is held to what it declared; an isolated
   plugin is held by the process boundary. A plugin moves from the first level
   to the second by adding a line to its `plugin.edn`.

   The load-bearing detail is that **level two does not need
   `contextIsolation`.** That fell out of surveying the ecosystem rather than
   reasoning about it, below. The `require` shim is what carries a level-one
   plugin across the flip, and the manifest is what scopes it.
3. **The bridge is the permission system**, so its surface should keep being
   designed as one. `readFile` scoped to the workspace is a different thing from
   `fs.readFile`, and the difference is worth having before a hundred plugins
   are written against the wrong one. This is the argument against the
   compatibility-`require` route as anything but a bridge to somewhere else.
4. **Self-evaluation can be scoped too.** The connector model already
   distinguishes evaluating *in Light Table* from evaluating *in a client*. That
   distinction is the natural place for a trust boundary: an editor-scoped eval
   with the bridge available is defensible in a way that "everything can do
   everything" is not.

That order — Light Table's own Node use behind capabilities, then the plugin
story, then flip isolation — is what happened, and points 1 and 2 are done. A
CSP is the last question, and the honest answer may be `unsafe-eval` with a much
smaller blast radius behind it, which is now what it has.

## Monorepo for the bundled plugins

Worth doing, for a reason that is not code organisation.

Plugins ship precompiled and nothing rebuilds them, so a rename in the editor
does not fail a build — it fails in a user's session. That already happened
twice here: `crate` → `singultus` broke both flagship plugins and the default
user plugin, silently, until they were actually run. In-tree plugins compile
against the host, which turns that class of break into a build error.

Sizes are small — Clojure 1,176 lines of ClojureScript, Javascript 573. The
complications are the payload and the coupling:

- Clojure carries about 15 MB: `lein-light-nrepl`, a runner, vendored CodeMirror
  modes. Javascript carries a 2.8 MB `node_modules`.
- Both have vestigial `project.clj` files pinning Clojure 1.5.1.
- Both ship compiled artifacts that would become build outputs.

The natural shape is another shadow-cljs target per plugin, output where the
plugin loader already looks, with `plugin.edn`/`plugin.json` kept so nothing
about distribution changes for anyone else. Candidates beyond the two flagships:
CSS, HTML, Paredit, Python, Rainbow — the set `script/build.sh` already pins.

The second reason has partly resolved itself: `contextIsolation` is on, and the
flagship plugins still work, because the `require` shim serves them. That was
the compatibility route rather than the fix — an in-tree plugin would call the
bridge directly and need no shim at all, and would fail its build rather than a
user's session when the host renames something.

The directory exists — `plugins/`, with the first TypeScript plugin in it and
the build wired into `script/build.sh` and CI. What remains is moving the seven
published ones in, which is the payload-and-coupling problem above rather than a
question of mechanism.

## Hand-written JavaScript to TypeScript

The privileged half is done — `src-electron/`, compiled to `deploy/core` by a
`tsc` step, strict, with `noUncheckedIndexedAccess` and
`exactOptionalPropertyTypes`:

| | lines |
|---|---|
| `main.ts` | 421 |
| `preload.ts` | 196 |
| `browserInjection.ts` | 145 |

That was the part worth doing first because it is where the privilege is. Every
capability added to the bridge lands there, and it is where the bugs were.

**The rest is done too. Light Table ships no hand-written JavaScript.** Four
source roots, one per place code runs, because each has a different set of
globals and typing them together would mean typing none of them:

| root | runs in | emitted to |
|---|---|---|
| `src-electron/` | the main process and the preload | `deploy/core/` |
| `src-window/` | the editor window — DOM, and `"types": []` | `src-gen/lt/window/`, bundled |
| `src-worker/` | the worker thread — `"types": ["node"]`, no DOM | `src-gen/lt/background/`, bundled |
| `src-browser/` | a page Light Table has connected to | `deploy/core/lighttable/`, served |

`src-window`'s empty `types` is the point rather than an oversight: a window
namespace that wants something from Node goes through the bridge, so it should
not be able to name `fs` at all. `src-browser` is the only root that downlevels,
to ES2017, because the page loading it is someone else's; it also compiles with
`"module": "none"`, so an `import` added to a file served to a script tag is a
compile error rather than something a browser discovers.

The last three ports were `behaviorsParser.js` and `walkdir2.js` — the worker's
behavior parser and directory walker, now bundled into `worker.js` rather than
read off the install directory at runtime, which is the last of what
`lt.background.runtime`'s docstring set out to do — and `ws.js`, the client
served to connecting browsers.

Two differences worth recording, since a port is supposed to preserve
behaviour:

- **`walkdir2.js` consumed its argument.** It assigned the caller's array
  straight to its work queue and drained it, so a second call with the same
  array found nothing. The port copies. Nothing hit it — the only caller passes
  a single path — but it was real, and it is what a differential test finds and
  a reading does not.
- **`behaviorsParser.js` labels a nested atom as a keyword.** Almost certainly a
  slip in the original, and preserved: behavior files are read back by position
  and value rather than by that label, so changing it would alter how they
  render for no gain.

Both were checked by running the old implementation against the new one on every
`.behaviors` and `.keymap` file in the tree plus deliberately broken input, and
on five directory walks. Identical throughout, once the mutation above is
accounted for. `ws.js` was driven in a stubbed page through all four eval
commands, the watch path, and a cyclic value.

`throttle.js` is not on this list because it was nine lines of minified 2010
jQuery plugin, and it is ClojureScript with tests now.

**`npm run lint:js` now covers the TypeScript too**, which it had to once there
was no JavaScript left for it to lint — `typescript-eslint` was a dependency
doing nothing. Three of its recommended rules are off, each for a stated reason:
`no-explicit-any` (every one is at a boundary with an untyped foreign API, and
`strict` is what guards Light Table's own types), `no-require-imports` (these
compile to CommonJS because that is what loads them), and `no-var` for the two
CodeMirror forks alone. Everything else passes with zero findings.

**Mousetrap came off it for the opposite reason.** The rule elsewhere is that
lifted code gets ported, but this file is not edited, and typing it would
destroy the only thing that keeps it maintainable: a mechanical diff against
upstream. So it was upgraded instead — 1.6.0 to 1.6.5, by re-applying its four
marked deviation blocks onto the newer upstream.

That turned out to be the cheaper and better trade by some distance. Upstream
1.6.0 → 1.6.5 is 28 lines, and one of its two substantive changes is a numpad
fix Light Table had already backported as an unmarked inline edit — so the
upgrade *removed* a deviation. The fork is now exactly its four marked blocks,
46 lines, with no unmarked drift at all, which means the next upgrade is the
same mechanical operation. `deploy/core/lighttable/util/VENDORED.md` records the
procedure and the diff command.

Before touching it, `script/smoke-test.sh` gained a check that dispatches a real
`KeyboardEvent` and asserts it reaches Light Table's handler. Nothing tested the
keyboard, and the fork exists specifically to route `keydown`/`keypress`/`keyup`
differently — if that routing broke, every other check would still have passed.

The same reasoning applies to the forked CodeMirror addons — but only to their
`var` declarations, which stay. They were ported: `src-window/cm-search.ts` and
`cm-hint.ts`, against real CodeMirror 5 types in `src-window/codemirror.d.ts`
rather than `any`. Typing them found two dead locals that a reading had not.

## Documentation, and the end of `project.clj`

`docs.lighttable.com` returns 503, so `doc/` in this repository is now the
documentation. `doc/README.md` indexes it and marks each page **current** or
**predates the modernization**, because a page that was accurate in 2015 and a
page written this year are different things to read. A `docs` workflow publishes
`doc/` to GitHub Pages, separately from the build: documentation should not wait
on a ClojureScript compile, and a red build should not block a typo fix.

**The API reference is generated from clj-kondo's static analysis** by
`script/gen-api-docs.js`, into markdown, committed. That replaced codox, which
was the last reason `project.clj` existed. Codox is a Leiningen plugin, so
keeping it meant keeping a project file that built nothing else, a JVM
invocation, an `--add-opens` to get past the module system since JDK 16, and
`script/build-api-docs.sh` — a script that switched branches and force-pushed
to `gh-pages` from whatever state the working tree was in. It also emitted a
wall of `WARNING: Use of undeclared Var` noise the docs recommended ignoring.

clj-kondo is already a dependency, for linting, and its analysis output carries
docstrings, arglists, privacy and source positions — everything codox rendered.
The output is markdown rather than HTML so it reads on GitHub as well as on the
published site, and so a diff shows what changed about the API rather than what
changed about a generator's templates. `make check` regenerates and fails if
the tree differs, which is what keeps a committed artifact honest.

The seven namespaces it covers are the ones codox published, unchanged, so the
promised surface did not silently move with the tooling. That list lives at the
top of the script, where adding to it is visibly a decision about what the
editor promises to keep.

Two smaller things fell out. `script/build-app.sh` derived the release name by
`cut`-ing the first line of `project.clj`; that line had become a comment, so
builds were coming out as `Light-Table-mac` with `Table` for a version and
nothing saying so. It now reads `deploy/core/package.json`, the manifest
Electron itself reads, and fails loudly if either field is missing.
`deploy/core/version.json` still claimed Electron 13.1.2.

## CodeMirror

Worth knowing before anyone treats this as urgent: **CodeMirror 5 is still
receiving releases.** 5.65.21 shipped in February 2026 — *more recently* than the
`codemirror` 6.0.2 meta-package, which sits still because CodeMirror 6 is
distributed as `@codemirror/state`, `@codemirror/view`, `@codemirror/language`
and friends, each independently versioned and actively developed.

So there is no security cliff and no forced migration. CodeMirror 6 is a genuine
rewrite — immutable state, transactions, a different extension model — and Light
Table has 59 `js/CodeMirror` references and 14 addon loads.

The claim that used to close this section — that exposing the CodeMirror object
made it "a plugin API break at least as large as the editor work itself" — was
an assumption, and counting contradicted it. Exactly one published function
leaks the object (`lt.objs.editor/->cm-ed`), 34 of the 59 references are
`js/CodeMirror.commands.*` in one command table, and the compiled Clojure,
Javascript and Paredit plugins reference CodeMirror **zero** times. Paredit is
a structural editor and should have been the worst case; it goes through
`lt.objs.editor` wrappers instead. The abstraction holds better than anyone
assumed.

Reasonable position: stay on 5, revisit if upstream signals an end — but the
migration is a bounded project rather than a rewrite, and if nothing were
already chosen, CodeMirror 6 is what Light Table would pick. See
[doc/editor-engine.md](doc/editor-engine.md).

## Dependencies

Everything is at its latest release: `codemirror` 5.65.21 (see above),
`socket.io` 4.8.3, `tar` 7.5.22, `bencode` 4.0.1, `shelljs` 0.10.0,
Electron 43.2.0, Clojure 1.12.5, ClojureScript 1.12.145.

**What Light Table ships has no npm advisories.** The last three were
`brace-expansion` denial-of-service reports reached through `minimatch`, pulled
in by `replace`, and they are gone with the package — see *Project-wide search,
which had stopped working* below.

The build tooling still reports some, and they are a different problem: the
`clj-kondo` npm wrapper installs its binary through `binwrap`, which depends on
the deprecated `request`, which depends on `form-data`, `qs`, `tough-cookie` and
`uuid`. There is no fix available upstream, none of it is shipped to a user, and
the way out is fetching the clj-kondo binary directly rather than through npm.
Worth doing; not the same urgency as something in the application.

## Project-wide search, which had stopped working

Removing `replace` was supposed to be a dependency chore: it was the last source
of the project's npm advisories, three `brace-expansion` denial-of-service
reports reached through `minimatch`, and forcing a newer `minimatch` breaks it
because its export shape changed. So the package had to go rather than be
upgraded.

Reading the call site first turned up something else. `lt.background.search`
passed `replace` a `result` callback and read `totalFiles` and `time` off what
it returned. The 1.x package has no `result` option and returns a plain array.
Measured against the shipped copy: the callback fired **zero** times and both
properties were `undefined`.

So workspace search had been returning nothing at all — no results in the list,
and a summary reading "Found 0 results searching undefined files in NaN
seconds". Whatever version that API belonged to was several majors ago, and
nothing in the repository noticed, because nothing tested search.

`lt.background.file-search` replaces it, in ClojureScript rather than
TypeScript so the existing test runner can exercise it directly, with
`lt.background.search` reduced to the wiring. Four things are better than a
like-for-like port:

- **A plain search is a literal.** `replace` compiled every search as a regex,
  so looking for `a.b` quietly matched `axb` and looking for `(` was an error.
  `->pattern` existed specifically to mark a regex as `/like this/`; now that
  distinction means something.
- **Exclusions work.** The searcher passed `files/ignore-pattern`'s regex source
  as `replace`'s `exclude`, which fed it to minimatch as a glob, where it
  matched approximately nothing. It is now a regex matched against each entry's
  name, with a trailing separator for directories — the same rule
  `src-worker/walkdir.ts` uses, so the navigate bar and the searcher finally
  agree on what is not worth looking at.
- **Binaries are skipped**, on a NUL byte in the first 8KB, the way grep decides.
  Searching one as UTF-8 produces matches nobody asked for, and replacing in one
  would corrupt it.
- **The file count is honest.** A binary or an unreadable file is not counted as
  searched, because it was not.

Long lines are truncated at 400 characters for display, an unreadable file is
reported and skipped rather than ending the walk, and symlinks are still not
followed — which is also what stops a cyclic link walking forever.

Twenty-seven unit tests build a real directory tree in a temp directory and
search it: literal against regex, case sensitivity holding across the walk,
exclusions that skip `target/` but not `target.txt`, symlink loops, binaries,
unreadable files, CRLF, two matches on one line, and replacements read back off
disk. Six smoke checks drive the whole path through the running application —
searcher, worker, engine, message back — and assert exact numbers rather than
"more than zero", since zero is precisely what the bug produced.

## Smaller things

- **The `lt.macros/background` removal is still an unshimmed API break.** No
  bundled plugin uses it. A shim would mean restoring the register-and-eval path
  in the worker, which is exactly what made shadow-cljs impossible; the
  alternative — running the work in the renderer with a deprecation warning —
  keeps plugins working but silently loses the off-thread property. It is a
  judgement call, not a technical obstacle.
- **Three `load/js` eval sites remain** for Light Table's own global-scope
  scripts and the forked CodeMirror addons. Mechanical, and a prerequisite for
  any CSP conversation. Porting those scripts to TypeScript resolves it as a
  side effect, since a module that declares its exports can be required.
- **`lt.util.ipc` is gone**, which is a plugin API break in principle. Nothing in
  the flagship plugins referenced it — checked against the compiled artifacts,
  not just the source — and `lt.util.bridge` is where its one general-purpose
  member, `app-info`, now lives.
- **`lt.util.load/node-module` has no callers in this repository.** It is kept
  because published plugins have them: the Clojure plugin reaches for `bencode`
  and `shelljs` through it and the Javascript plugin for `shelljs`, both from
  precompiled JavaScript that no build here rebuilds. Its docstring says so, so
  the next person to find it unused does not delete it.
- **clj-kondo reports zero warnings**, not zero errors with warnings tolerated.
  Nine were outstanding and each was fixed rather than suppressed. Most were
  cosmetic — a redundant `do`, a nested `or`, a one-argument `str`. Two were
  discarded transient returns, in `lt.object/tags->behaviors` and
  `lt.objs.document/->snapshot`; both are on vectors, where ClojureScript's
  `conj!` happens to mutate in place, so they were contract violations rather
  than live bugs. The snapshot one now uses a JavaScript array, which is the
  honest tool when the accumulation happens inside a callback that cannot
  rebind what it closed over.

- **Following that thread found a live one.** `lt.objs.workspace/watch!` did the
  same thing to a transient *map*, where it is not benign: `assoc!` returns a
  different object once the map outgrows its array-map representation at eight
  entries. Watching a folder with more than eight paths kept the first eight
  watches and silently dropped the rest, so most of a real project stopped
  reporting changes. The same function also called `persistent!` inside its
  loop, so `watch-workspace` on a workspace with two or more root folders threw
  `persistent! called twice`, and it built an `fs` watcher for every folder
  before checking whether that folder was already watched, leaking the ones it
  discarded. It is now a `reduce` that threads the transient, makes it
  persistent once, and only creates a watcher it is going to keep.
