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

## Content isolation (not started)

`nodeIntegration: true` and `contextIsolation: false` are still set.

`contextIsolation` and a content security policy are separable, and they have
different prices:

- **`contextIsolation: true`** means a preload script and a `contextBridge`.
  The renderer's Node use is enumerable — 37 `js/require` sites across 19 files,
  around 30 distinct operations — so this is bounded work. It breaks plugins that
  reach for Node directly.
- **A CSP without `unsafe-eval`** cannot be adopted without removing
  self-evaluation, and self-evaluation is the feature Light Table is named for.

So the honest position is that the eval surface has narrowed as far as it
usefully can. What remains is not cleanup deferred; it is the product.

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

- `contextIsolation` is about *who can reach Node*. That is bounded work: 37
  `js/require` sites over 19 files, roughly 30 distinct operations, behind a
  preload and a `contextBridge`. Nothing about self-evaluation requires the
  renderer to hold `child_process`.
- A CSP is about *what can be executed*. This is where the feature lives.

Split that way, most of the value is available without touching the feature. A
renderer that cannot spawn processes or read arbitrary files is a much smaller
target even while it can still evaluate expressions, because the interesting
attacks are not "run some JavaScript in a sandboxed page" — they are "run some
JavaScript that then shells out".

Which points at where the real exposure is today, and it is not `eval`:

1. **Plugins are arbitrary code with no boundary.** They are fetched over the
   network, `eval`'d into the renderer, and inherit full Node. There is no
   manifest of what a plugin may touch, no signature, and no review gate. In an
   era of dependency-confusion and typosquat attacks on package ecosystems, that
   is the supply chain, and `contextIsolation` alone does not fix it — it just
   means a plugin has to ask the bridge instead of calling `fs` directly.
2. **The bridge is the security model.** Once plugins go through a
   `contextBridge`, its surface *is* the permission system, so it should be
   designed as one: capability-scoped rather than a flat re-export of Node.
   `readFile` scoped to the workspace is a different thing from `fs.readFile`,
   and the difference is worth having before a hundred plugins are written
   against the wrong one.
3. **Self-evaluation can be scoped too.** The connector model already
   distinguishes evaluating *in Light Table* from evaluating *in a client*. That
   distinction is the natural place for a trust boundary: an editor-scoped eval
   with the bridge available is defensible in a way that "everything can do
   everything" is not.

So: `contextIsolation` and a capability-shaped bridge are worth doing, in that
order, and neither costs the feature. A CSP is the last question, and the honest
answer may be `unsafe-eval` with a much smaller blast radius behind it.

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

## Hand-written JavaScript to TypeScript

About 2,529 lines across thirteen files, and they fall into three groups that
deserve different answers.

| | lines | notes |
|---|---|---|
| `main.js` | 269 | The whole main process. Ideal TypeScript candidate: Electron ships its own types, and this is the security boundary. |
| `script/smoke-test.js` | 194 | Test harness; types would catch the probe mistakes made while writing it. |
| `ws.js`, `browserInjection.js`, `dragdrop.js`, `fuzzy.js`, `throttle.js`, `behaviorsParser.js`, `walkdir2.js` | 1,675 | Light Table's own runtime scripts. `keyevents.js` alone is 1,086 of that and is a vendored keyboard library. |
| forked CodeMirror addons | 281 | Vendored forks; typing them means diverging further from upstream. |

The argument for starting with `main.js` is that it is where the privilege is.
Every ipc handler added for `contextIsolation` lands there, and a typed
`contextBridge` surface is worth considerably more than a typed drag-and-drop
helper. shadow-cljs does not compile TypeScript, so this needs a small `tsc`
step feeding `deploy/core` — worth scoping before committing.

`keyevents.js` should probably be replaced rather than ported.

## CodeMirror

Worth knowing before anyone treats this as urgent: **CodeMirror 5 is still
receiving releases.** 5.65.21 shipped in February 2026 — *more recently* than the
`codemirror` 6.0.2 meta-package, which sits still because CodeMirror 6 is
distributed as `@codemirror/state`, `@codemirror/view`, `@codemirror/language`
and friends, each independently versioned and actively developed.

So there is no security cliff and no forced migration. CodeMirror 6 is a genuine
rewrite — immutable state, transactions, a different extension model — and Light
Table has 59 `js/CodeMirror` references, 16 addon loads, and a plugin API that
exposes the CodeMirror object directly. That last point makes it a plugin API
break at least as large as the editor work itself.

Reasonable position: stay on 5, revisit if upstream signals an end.

## Dependencies

Everything is at its latest release: `codemirror` 5.65.21 (see above),
`socket.io` 4.8.3, `tar` 7.5.22, `bencode` 4.0.1, `shelljs` 0.10.0,
`replace` 1.2.2, Electron 43.2.0, Clojure 1.12.5, ClojureScript 1.12.145.

The one that is not really finished is `replace`, which pulls an old `minimatch`
and is the sole source of the three remaining npm advisories, all
`brace-expansion` denial-of-service. Forcing a newer `minimatch` breaks it, since
its export shape changed. Clearing them means either an upstream fix or replacing
`replace` with a small in-tree file walker — worth doing, but it is the
project-wide search implementation, so not worth rushing.

## Smaller things

- **The `lt.macros/background` removal is still an unshimmed API break.** No
  bundled plugin uses it. A shim would mean restoring the register-and-eval path
  in the worker, which is exactly what made shadow-cljs impossible; the
  alternative — running the work in the renderer with a deprecation warning —
  keeps plugins working but silently loses the off-thread property. It is a
  judgement call, not a technical obstacle.
- **Three `load/js` eval sites remain** for Light Table's own global-scope
  scripts and the forked CodeMirror addons. Mechanical, and a prerequisite for
  any CSP conversation.
- **`project.clj` builds nothing** and exists only for codox. It stays as long as
  the published plugin API docs do.
