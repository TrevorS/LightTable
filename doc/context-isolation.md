# The road to context isolation

**Done.** Light Table runs with `nodeIntegration: false` and
`contextIsolation: true`. The window has no `require`, no `process`, no
`__dirname` and no `module` of Node's. Every filesystem call, process, socket,
download and archive goes through the named capability list in
`src-electron/preload.ts`, and plugins reach Node through a `require` that is
Light Table's rather than Node's.

This document is the record of how, kept because the reasoning is worth more
than the outcome — particularly the measurement in the next section, which is
what made the cheap route possible.

## The measurement that decides the design

The obvious reading is that isolation means routing every filesystem call
through ipc to the main process, and that `lt.objs.files` — 573 lines, mostly
synchronous — would have to be redesigned around that. Measured on Electron 43,
2,000 iterations per number:

| | direct | via contextBridge | via sync ipc |
|---|---|---|---|
| `existsSync` | 1.7µs | **2.9µs** (1.8x) | 194µs (118x) |
| `readFileSync` | 6.4µs | **9.1µs** (1.4x) | 242µs (38x) |

**These are two different hops, and only one of them is expensive.**
`contextIsolation` separates the window's JavaScript world from the preload's.
It does not require leaving the process. A preload with `sandbox: false` keeps
Node, so a capability can be served from the isolated world directly — the
window pays about a microsecond to cross, not two hundred.

So `lt.objs.files` can be **moved rather than redesigned**, and its synchronous
API can stay synchronous. That is the difference between a week and a quarter.

### The stateful cases work too

Sockets and child processes cannot simply be handed across: a `net.Socket` sent
through `contextBridge` arrives as a plain `Object`, cloned, with its prototype
and methods gone. The pattern that does work is a handle — the real object stays
in the preload, and what crosses is a set of functions over it. Verified:

- A callback passed from the window and invoked from the preload costs
  **0.68µs** per call, which is cheap enough for streaming process output.
- Returning `{pid, kill}` from the preload works; both cross intact.
- A real child process was spawned, its stdout streamed into the window through
  a callback, and its exit code delivered.

## The configuration, as shipped

In `deploy/core/package.json`:

```
nodeIntegration:  false     the window has no require
contextIsolation: true      window and preload are separate worlds
sandbox:          false     the preload keeps Node, so capabilities are cheap
```

`sandbox: false` is not a leftover. Electron sandboxes renderers by default,
and a sandboxed preload has no Node either — which would push every filesystem
call into the 118x column below and force a redesign this configuration avoids.

This is not the strongest possible configuration — `sandbox: true` would remove
Node from the preload as well, forcing everything through ipc and back into the
118x column. That is a later step and a different design (see *Afterwards*).
What this configuration buys is the property that matters most: **code in the
window cannot reach anything that is not on the list**, which is where plugins
run.

## The surface — migrated

77 touch points across 9 namespaces. All 77 are done.

| namespace | touches | what it became |
|---|---|---|
| `lt.util.load` | 9 | First, because everything loads through it. |
| `lt.objs.files` | 32 | One for one, and still synchronous. |
| `lt.objs.workspace` | 8 | Watching became a handle. |
| `lt.objs.console` | 2 | The log stream became a path and an append. |
| `lt.objs.deploy` | 12 | Two capabilities: download a file, extract an archive. |
| `lt.objs.proc` | 3 | Handles carrying stdout, stderr, exit and error. |
| `lt.objs.thread` | 2 | A fork handle with send and onMessage. |
| `lt.objs.clients.tcp` | 2 | The server moved whole; connections are numbers. |
| `lt.objs.clients.ws` | 8 | The socket.io server moved whole. |

**There is no `js/require`, `js/process` or `js/__dirname` left in the window**
— and since the flip, no Node behind those names to reach even if there were.
`process.nextTick` became `queueMicrotask`, which is the web equivalent; `env`,
`execPath` and `versions` are host capabilities.

Three things the migration settled:

**Name capabilities after the task, not the machinery.** `lt.objs.deploy` held
url parsing, redirect following, a CONNECT tunnel and a write stream, all to
download a file. Naming the capability `download` took seventy lines out of the
window. The same call applied to both client servers: what the window does with
a tcp server is wait for connections and send lines, so that is the surface.

**Identity does not survive the crossing.** `fs.watchFile` pairs with
`unwatchFile` keyed on the callback passed in; a socket is compared against a
stored one. Neither works through a proxy. Handles fix the first, and numbering
connections fixes the second — and the numbering was an improvement anyway,
since the window only ever stored and compared them.

**Match the underlying API's names exactly, or do not resemble it at all.**
Three bridge file methods were shortened while the rest were identical to
Node's. That inconsistency broke the migration silently and cost a debugging
round. They all match now.

### Two things not in that table

**Local JavaScript requires.** ~~`lt.objs.editor` loads CodeMirror's fold
addons by path, and several namespaces require files under `deploy/core`.~~
Done. The answer was not a capability but the build: shadow-cljs ran with
`:js-provider :require`, which left npm imports as runtime requires. It runs
with `:shadow` now, which bundles them, and the 122 CodeMirror modes a runtime
walk used to discover are generated into a require list at build time.

**The bootstrap problem.** `lt.util.load` reads files during startup, before
anything else exists. Whatever serves it has to be available at that point,
which the preload is — it runs before the page. No obstacle, but it constrains
ordering: `lt.util.load` migrates first or the rest cannot load.

## Plugins — the gate, now built and running

Turning on `contextIsolation` removes `require` from the window, which is where
plugins run. The Clojure plugin calls `require("net")` at namespace load for
nREPL; the Javascript plugin does the same. Both ship precompiled, so nobody
can rebuild them for users who already have them.

**The shim is what carried them across.** It was built and installed a step
before the flip, while `contextIsolation` was still off — deliberately, so that
a plugin needing something it does not serve failed where it could be fixed
rather than on the day itself. That is how the two gaps below were found. It
lives in three pieces:

| | |
|---|---|
| `lt.objs.plugins.require-shim` | Deciding. Attribution and capabilities. Pure, so it is unit-tested. |
| `lt.objs.plugins.node-modules` | The modules, bridge-backed or bundled. |
| `lt.objs.plugins.local-modules` | CommonJS, for the JavaScript a plugin vendored. |

**What the manifest is for.** A plugin's manifest already declares what it
needs, and Light Table already infers that for plugins with no manifest. That
is exactly the shim's input: a plugin declaring `#{:files}` gets `fs` and
nothing else; one declaring nothing gets what it was inferred to use, so
nothing breaks on the day this turns on. The manifest work was not preparation
for this step — it *is* this step's design.

Worth being exact about what the shim is for: **compatibility, and telling an
honest plugin what it may use.** It is not containment. A plugin runs in the
window, so it can reach the bridge directly whatever `require` says. What
contains a plugin is the bridge's surface, and that is true either way.

### Attribution works, and is cheap — measured

**A plugin's frames name its directory.** Light Table loads plugin code with a
`sourceURL`, and a stack captured from inside a running plugin looks like this:

```
at Object.notify (/home/user/LightTable/deploy/plugins/HelloTS/hello_compiled.js:42:25)
at exec         (/home/user/LightTable/deploy/plugins/HelloTS/hello_compiled.js:73:16)
```

`deploy/plugins/HelloTS` is exactly the key `lt.objs.plugins` already stores a
plugin under, so the path maps onto a manifest with no new bookkeeping. The
local module loader uses the same `sourceURL`, so a frame from inside vendored
code is attributed to the plugin that shipped it.

**A stack costs 1.3–2.7µs** on Electron 43. `require` happens at plugin load
rather than per operation, so it is not close to mattering.

### What it serves

Two kinds, and the difference is the thing that made the hard case work.

**Bridge-backed**, gated on a capability: `net` (`connect`, `createConnection`,
`Server`, `createServer`), `fs`, `path`, `os`, `shelljs`.

**Bundled**, reaching nothing and needing no capability: `buffer`, `events`,
`bencode`, `util`. Plus the globals a plugin written for Node expects to find —
`Buffer`, `global`, `setImmediate`, and a `process` with the three fields
plugin code was actually found to read.

Anything else is refused with a message naming what is missing, which is a
denial a plugin author can act on rather than a crash.

### The chain that decided the shape — tested against the Clojure plugin

The open question was whether a per-module shim could serve a real client, and
the answer turned on one thing: the nREPL client takes a socket's data, hands
it to `Buffer.concat`, and hands that to `bencode.decode`. Three modules, one
value passing between them.

It works, and the reason it works is that **only the socket crosses the
bridge.** Bytes arrive as a `Uint8Array` — copied, not viewed, because node
pools small Buffers into a shared ArrayBuffer — and everything downstream is in
the window: `Buffer` is the bundled one, `bencode` is bundled too. One world
throughout. Had `Buffer` lived in the preload the concatenated value would have
crossed twice and arrived as something else both times.

Driven against a bencode-speaking server that splits replies mid-message and
mid-character, the plugin's own captured modules complete the exchange:
connect, encode, write, two chunks, concatenate, decode. `script/smoke-test.js`
checks the pieces of this on every run.

Trying it also turned up two things that had nothing to do with isolation:

- **`bencode@4.0.1` cannot do what the plugin asks.** `decode(data, 'utf-8')`
  throws on any dictionary — `decode.dictionary` decodes the key and then
  decodes it again. Every nREPL message is a dictionary, so the plugin's client
  could not have worked. Pinned to `^2.0.3`, which is the API it was written
  against.
- **Plugins vendor their own node_modules.** The Javascript plugin requires
  `harbor` out of its own directory to find a free port. That is not a builtin
  to serve a facade for, it is a file to read and run — hence the CommonJS
  loader, which resolves the way Node resolves and evaluates with the same
  `sourceURL` everything else uses.

## Order, as it went

1. **Bundle npm imports.** `:js-provider :shadow`, and a generated require list
   for the 122 CodeMirror modes a runtime `node_modules` walk used to find.
2. **`lt.util.load` onto the bridge**, first, because everything loads through
   it.
3. **The mechanical ones**: `files`, `workspace`, `console`, `deploy`.
4. **The handle cases**: `proc`, `thread`, `tcp`, `ws`.
5. **The plugin `require` shim**, scoped by manifest, installed a step early so
   the gaps showed up while isolation was still off.
6. **`tar`** — the last node module in Light Table's own code, and the last
   thing before the flip. It became `files.extract`, a capability in the same
   shape as `download`: what the window wants is a release unpacked, not a
   stream to pipe.
7. **Flip.** Three lines in `deploy/core/package.json`, and three window-side
   Node references that had gone unnoticed until Node was actually gone:
   `process.on("uncaughtException")` in the console (now `window`'s `error` and
   `unhandledrejection` events, which cover more), `process.execPath` in the
   cli (a host capability), and `js/global.String` / `js/global.Array` in
   `lt.util.cljs` (the window's own `String` and `Array`, which is what they
   always were).

### What it cost, and what it did not

Nothing was redesigned. `lt.objs.files` is still synchronous, self-evaluation
still works, and 44 smoke checks pass — including the isolation itself, which is
established by observation rather than by reading the config back: no
`__dirname`, no `module`, a bridge that is a contextBridge proxy rather than
the preload's own object, and a `require` that refuses a builtin Node would
have served.

One predicted degradation is real and worth knowing about: `util.inspect` runs
in the preload, so what reaches it is a clone. Plain data is unaffected — which
is the only thing `lt.objs.console/inspect` is ever called on, since
`cljs-result-format` handles functions before it gets there — but a function
prints as `[Function (anonymous)]` and a DOM node as `HTMLBodyElement {}`.
## Afterwards

Two further steps get proposed whenever this configuration is read. Neither is
being taken, and the reasons are different in kind — one is a cost question,
the other is not available at all.

**`sandbox: true`** removes Node from the preload as well. Everything then has
to cross to the main process, at the 118x cost measured above, so it needs the
coarse-capability redesign that the cheap path avoids — `scan this workspace`
rather than `stat this path`, with the walking done on the privileged side.

That is a genuine redesign of the filesystem layer and should be judged on its
own merits, not adopted because the flag exists. It also buys less than the
step before it: once the window has no `require` and reaches the system only
through a named list, moving where that list is implemented is defence in depth
rather than a new boundary. So it stays off, deliberately, and this paragraph
is the answer rather than a to-do.

**A content security policy** without `unsafe-eval` cannot be adopted at all
while the editor evaluates the code you are writing, which is the feature Light
Table is named for. A CSP governs what may be executed; self-evaluation is
execution the user asked for by name. The honest version is `unsafe-eval` plus
a much narrower `connect-src` and `img-src`, which is worth doing on its own
one day and is not what people mean when they ask for a CSP.
