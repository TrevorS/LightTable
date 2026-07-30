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

shadow-cljs builds everything Light Table ships. `lein-cljsbuild` is gone;
`project.clj` survives but builds nothing, existing only to generate the plugin
API docs through codox, which has no shadow-cljs equivalent.

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

The build reports around 200 `:infer-warning`s, concentrated in interop-heavy
namespaces (72 in `lt.objs.editor` alone). They are shadow noting it cannot infer
a type at a JavaScript call site. Harmless under `:simple`, which does not
rename, but they would need `^js` hints throughout before `:advanced` could be
considered. CI gates on any warning that is *not* an infer-warning, so a real
problem still fails the build — verified by introducing an undeclared var and
watching it trip.

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

## Content isolation (not started)

`nodeIntegration: true` and `contextIsolation: false` are still set. Turning them
around is the last step and the one with a product decision in it: plugins are
distributed as JavaScript `eval`'d into the renderer with full node access, so a
preload/contextBridge split and a content security policy break every existing
plugin by construction. The eval surface is now narrow enough that the question
is a plugin-API one rather than an app-wide rewrite — but it is a decision, not
a refactor.
