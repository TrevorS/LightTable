# Plugins, in this repository

Plugins that live here are built from source against the editor they extend. A
rename in Light Table becomes a compile error rather than something a user finds
at startup — which is not hypothetical: `crate` → `singultus` broke both
flagship plugins and the default user plugin silently, and stayed broken until
someone ran them.

**Every plugin Light Table ships with is here.** Nothing is cloned at build
time and no compiled output, jar or `node_modules` is committed.

```
plugins/
├── types/lighttable.d.ts   the Light Table API, for TypeScript plugins
├── lib/lt.ts               helpers for talking to ClojureScript
├── TypeScript/             a plugin, in TypeScript
│   ├── plugin.edn          metadata, and the capability manifest
│   ├── typescript.behaviors   what the plugin contributes
│   ├── tsconfig.json
│   └── src/typescript.ts
├── Paredit/                a plugin, in ClojureScript
│   ├── plugin.edn
│   ├── paredit.behaviors
│   ├── VENDORED.md         where it came from, and what changed
│   └── src/lt/plugins/paredit.cljs
├── Clojure/                and five more, all vendored the same way
├── CSS/
├── HTML/
├── Javascript/
└── Python/
```

`npm run build:plugins` places everything in `deploy/plugins/`, where the loader
looks. The two languages get there differently: TypeScript compiles straight
into the plugin's directory, while ClojureScript is a module of the `:app` build
— so shadow writes it beside the bundle and `script/place-plugins.js` moves it
next to its `plugin.edn`. Run `npm run build:cljs` first, or it will say so.

A directory is a plugin when it has a `plugin.edn`. A ClojureScript one also
needs its `src` on `:source-paths` and a `:modules` entry in
`shadow-cljs.edn` — every namespace its `.behaviors` file names has to be an
entry, because behaviors are resolved by name at load and a namespace nothing
requires is one the module would not contain — plus a line in `CLJS_PLUGINS` in
`script/place-plugins.js` saying which module file its `plugin.edn` expects.

### What a plugin may bring with it

| | |
|---|---|
| its own source | committed, and compiled here |
| a third-party npm package | a dependency in the plugin's `package.json`, installed at build time by `script/install-plugin-deps.js` into a gitignored `node_modules`. The lockfile is committed |
| compiled output | never. `.gitignore` covers `/plugins/*/*_compiled.js` and its map |
| a binary | **none.** There is no binary in `plugins/`, and nothing is downloaded at build time but npm packages |

The one binary there used to be was the Clojure plugin's nREPL server, a 15MB
uberjar fetched from a pinned tag. It is gone, and the reason is worth reading:
it did not run. `plugins/Clojure/VENDORED.md` has the whole account — the jar
was Leiningen 2.5.2 packaged, it died on any JDK newer than 8, and it did not
contain the Light Table middleware at all, fetching that from Clojars when a
REPL started.

That plugin is also the worked example of the other rule, which is **buy the
intelligence and build the editor**. Its 1,192 lines of bespoke nREPL
middleware — completion, documentation, stacktrace formatting — are
`cider-nrepl` and `orchard` now, the same libraries every other Clojure editor
uses. What was kept and rewritten rather than bought is the part nobody sells:
a result beside each top-level form, and watches. Those come from the
tree-sitter parse tree the editor already keeps, so they work whether or not a
REPL is attached.

### Bringing a published plugin in

Paredit was the worked example: 683 lines of ClojureScript from
[LightTable/Paredit](https://github.com/LightTable/Paredit), under its own MIT
licence. Upstream ships `paredit_compiled.js` as a checked-in artifact that
nothing rebuilds.

Compiling it against the editor found a warning on the first attempt —
`batched-edits` calls `do-edit` above the `defmulti` that defines it, so
ClojureScript reports an undeclared var. Upstream ships with it, which is
exactly the sort of thing an unrebuilt artifact hides.

The five that followed made that argument rather better than one warning did.
Between them, compiling turned up eleven things, most of which had made part of
the plugin silently useless:

| plugin | what the artifact was hiding |
|---|---|
| Clojure | `recur` inside a `try`; `setImmediate` on a `global` the window does not have, which killed the nREPL message pump on its second message from inside a socket callback; `string/lower_case`, which is not a function; a notifier argument bound nowhere; `console/util-inspect` and `lt.objs.deploy/deploy`, which core no longer has; two namespaces reached fully qualified without a require |
| Javascript | `ws/port` and `tcp/port`, both now `->port` functions — the script tag it printed for connecting a browser named port `undefined`, and so did the callback address it handed the node client; `send` called with two of its three arguments; a `catch` on `js/global.Error` |
| Python | the same `tcp/port`, so its client was told to call back on `undefined` |
| CSS, HTML | forward references, and `lt.objs.editor` required twice under two aliases with both in use |

Each plugin's `VENDORED.md` has the full list and what was dropped. All five
are marked **provisional**: they predate the modernization by a decade, and the
point of vendoring them was to be able to see this at all — not to commit to
maintaining them as they stand.

Not every plugin is worth bringing in. Emmet needs no capabilities and would
otherwise be a good candidate, but its 13,931 lines are vendored third-party
JavaScript that bundles Underscore 1.3.3 from 2012 — moving it here means owning
that. The test is whether the plugin's own source is what you would be
maintaining.

Rainbow was evaluated and deliberately left out. It colours nested brackets by
re-tokenizing through `CodeMirror.overlayMode`, on exactly the tags tree-sitter
highlighting already owns, and `deploy/core/css/treesitter.css` argues against
the result by name. Colouring by depth from the parse tree is the replacement.

## What a plugin is

A plugin is a directory with a `plugin.edn` and a behaviors file that names what
it contributes. If it contributes code, it does so with
`:lt.objs.plugins/load-js`, which evaluates the named JavaScript — and does not
care what produced it.

So **plugins were never required to be ClojureScript.** Of the 20 surveyed,
Emmet and Claire are plain hand-written JavaScript with no ClojureScript
anywhere; Emmet is by Light Table's own author. Most of the rest are
ClojureScript, and many of those ship hand-written JavaScript alongside their
compiled output.

That is what makes TypeScript a natural third option rather than a new
mechanism: it compiles to the JavaScript the loader already takes. Nothing in
Light Table had to change to accept the plugin below.

## Writing one in TypeScript

Plugins are evaluated as global-scope scripts, so a plugin compiles to one
concatenated file: `module: none` plus `outFile`, with `plugins/lib/lt.ts`
listed ahead of the plugin's own sources. That is also why `lt.ts` is a
`namespace` rather than a module — namespaces compile to idempotent globals.

The output is strict-mode, and `lt.util.load/js` runs it through `window.eval`,
so a plugin's top-level bindings stay inside that eval instead of becoming
globals. Two plugins can each carry their own copy of the helpers without
colliding. It does mean anything a plugin wants to publish has to be assigned
somewhere deliberately; `lt.plugins` is where ClojureScript plugins land, so it
is where TypeScript plugins should go too.

`tsconfig.json` sets `"types": []`. A plugin runs in the window, and one that
declares no filesystem capability has no business seeing `fs`'s signatures —
the types a plugin gets should match what it is allowed to do.

Light Table's API has no machine-readable schema, so `types/lighttable.d.ts` is
hand-written and describes rather than guarantees. What it buys is the mistakes
that actually happen: a misspelled namespace, a command missing `exec`, a
keyword built with the wrong arity. Everything declared in it is exercised
against the running editor by `script/smoke-test.sh`.

## The capability manifest

`plugin.edn` may declare `:capabilities`:

```clojure
{:name "TypeScript"
 ;; ...
 :capabilities #{:processes :files}}
```

### Why these capabilities, and not `require`

A survey of 20 published plugins — the most-released ones across languages,
tools and themes — says the obvious manifest would have been the wrong one.

Counting direct `require()` calls, **10 of 20 plugins need nothing from Node**.
That reads as "most of the ecosystem could be sandboxed today", and it is wrong.
The Terminal plugin is one of the ten, and it spawns processes — through
`lt.objs.proc`. A manifest covering only `require` would have called it safe.

Running the inference in `lt.objs.plugins.capabilities` over the same 20, which
counts both routes to a thing as one capability, only **4 of 20 need nothing**
(Emmet, Markdown, Paredit, and a theme):

| capability | plugins |
|---|---|
| `:files` | 15/20 |
| `:processes` | 10/20 |
| `:plugins` | 8/20 |
| `:network` | 5/20 |
| `:desktop` | 5/20 |
| `:worker` | 3/20 |
| `:clipboard` | 1/20 |

So capabilities are named after what a plugin *does*, and every route to a
thing maps onto the same name. This is the better boundary anyway: Light Table's
API is a chokepoint the editor controls, and ambient `require` is not.

There are three routes, not two, and the third was found the hard way. A plugin
written before `contextIsolation` reaches a capability through `require` or
through `lt.objs`; one written now goes straight to `lt.util.bridge`, and the
scanner did not look there until a plugin that did was written. So the bridge's
surface is enumerated in `lt.objs.plugins.capabilities/bridge-surface` and
checked against `lt.util.bridge` by a test — a capability added to the bridge
without being classified fails it. An omission is the failure mode here, and an
omission cannot be caught by a pattern nobody thought to write.

| capability | covers |
|---|---|
| `:files` | reading and writing the filesystem — `lt.objs.files`, `bridge.files`, node `fs`, `path` |
| `:processes` | spawning — `lt.objs.proc`, `bridge.processes`, node `child_process` |
| `:network` | sockets and http — `lt.objs.clients.tcp`/`.ws`, `bridge.sockets`/`.servers`/`.net`, node `net`, `http`, `https` |
| `:desktop` | opening things outside the window — `shell.open`, reveal, trash |
| `:clipboard` | reading and writing the clipboard |
| `:environment` | reading and changing environment variables |
| `:worker` | running work on the background thread |
| `:plugins` | installing, updating or reading other plugins |

### Three levels, not one switch

`contextIsolation` could not simply be turned on: the Clojure plugin `require`s
`net` at namespace load time for nREPL, and it ships precompiled, so nobody can
rebuild it for the users who already have it. Breaking every installed plugin to
gain a security property is not a trade worth making, and it was not necessary —
the property arrives in stages.

It is on now. What carries a level 1 plugin across is the `require` shim in
`lt.objs.plugins.require-shim`: `require` in the window is Light Table's rather
than Node's, it serves a fixed list, and what a plugin gets from that list is
scoped by the same manifest described below. A plugin needing something not on
the list is told so on the console rather than crashing.

**Level 1 — legacy, unmanifested.** What every published plugin is today: full
Node, full API, no declaration. It keeps working. Light Table can infer what
such a plugin *would* have declared by scanning it, which is what the survey
above does, and show that — a plugin that turns out to want `:processes` and
`:network` is worth knowing about before installing it, even when nothing is
enforced.

**Level 2 — manifested.** The plugin declares `:capabilities`, and Light Table
holds it to them. This does not need `contextIsolation`: the capabilities that
matter are reached through Light Table's own namespaces, and those are ours to
gate. A plugin that declared `#{:clipboard}` and called `lt.objs.proc` is a
plugin doing something it said it would not.

**Level 3 — isolated.** `contextIsolation: true`, no Node `require` in the
window at all, everything through the preload bridge. This is what Light Table
ships now. Be exact about what it buys, though: the boundary it establishes is
between the *window* and the desktop, not between one plugin and another. A
plugin runs in the window, so it can reach the bridge directly whatever
`require` says — level 2 is still what holds a plugin to its own word, and a
per-plugin boundary would need a process each.

The levels are a migration, not alternatives: a plugin at level 1 today is at
level 2 when it adds seven characters to its `plugin.edn`, and the default flips
when enough have.

### Inference, so level 1 is not a dead end

Almost no published plugin declares anything, because almost all of them predate
the idea. Waiting for authors to update would leave the whole ecosystem outside
the system indefinitely, so Light Table infers the same evidence by reading the
JavaScript a plugin actually loads.

**Plugins: Report what each plugin can do** prints, for every installed plugin,
what it declared and what it uses. On a stock install:

```
Clojure: declares :desktop :files :network :plugins :processes, uses the same
CSS: declares :files, uses :files
HTML: declares nothing, uses nothing
Javascript: declares :desktop :files :network :plugins :processes, uses the same
Paredit: declares nothing, uses nothing
Python: declares :desktop :files :network :plugins :processes, uses the same
TypeScript: declares :files :processes, uses :files :processes
```

Every plugin that ships with Light Table now declares one, so what the report
is for has changed: it no longer answers "what would this plugin have to
declare" but "does what it declared cover what it does". The answer was no four
times over on the first attempt — Javascript opens a page outside the window,
Python resolves its own directory, and CSS and HTML both claimed a `:network`
they reach only through `lt.objs.clients`. The smoke test checks all seven
agree, which is why those were caught in a build rather than by a user.

An installed third-party plugin still has no manifest, and that is what the
rest of this section is about.

Inference reads only JavaScript, because a plugin's ClojureScript sources are
not what runs, and it matches member access rather than a bare mention — every
compiled plugin contains `goog.require('lt.objs.plugins')`, and that is not use
of it. `lt.objs.plugins.capabilities/evidence` reports the matched text, so a
finding can be checked rather than believed.

This is what makes the levels a migration instead of a cliff. An author can see
what they would have to declare before declaring it, a user can see what they
are installing before installing it, and nothing breaks in the meantime.

### Current state

Inference and reporting work. **Nothing is denied yet** — enforcement before
inference existed would have meant every published plugin breaking on the day it
shipped. What is missing is the enforcement gate itself, which is the point at
which `:undeclared` stops being a report and starts being a refusal.

`TypeScript` was the first plugin to carry a manifest; every plugin in this
repository carries one now, and the smoke test checks that what each declares
and what each uses agree. It is also the case that made
inference cover the third route to a capability: it reaches `lt.util.bridge`
directly rather than through `require` or `lt.objs`, and until it existed the
scanner did not look there.
