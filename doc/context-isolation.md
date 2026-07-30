# The road to context isolation

Scouted, measured, and mostly built. Light Table's own code is done; what is
left is the build change bundling stays blocked on, and the plugin ecosystem.

Light Table still runs with `nodeIntegration: true` and
`contextIsolation: false`, so `require` is still in the window — but nothing of
Light Table's own uses it any more. Every filesystem call, process, socket and
download goes through the named capability list in `src-electron/preload.ts`.

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

## Target configuration

```
nodeIntegration:  false     the window has no require
contextIsolation: true      window and preload are separate worlds
sandbox:          false     the preload keeps Node, so capabilities are cheap
```

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
| `lt.objs.deploy` | 11 | One capability: download a file. |
| `lt.objs.proc` | 3 | Handles carrying stdout, stderr, exit and error. |
| `lt.objs.thread` | 2 | A fork handle with send and onMessage. |
| `lt.objs.clients.tcp` | 2 | The server moved whole; connections are numbers. |
| `lt.objs.clients.ws` | 8 | The socket.io server moved whole. |

**There is no `js/require`, `js/process` or `js/__dirname` left in the window.**
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
### Two things not in that table

**Local JavaScript requires.** `lt.objs.editor` loads CodeMirror's fold addons
by path, and several namespaces require files under `deploy/core`. Those use the
same `require` the window is about to lose. The answer is not a capability but
the build: shadow-cljs runs with `:js-provider :require`, which leaves npm
imports as runtime requires. Switching to `:shadow` bundles them instead, which
is shadow's normal mode and removes the need entirely. Worth doing early — it is
independent of everything else here and reduces the surface before any of it
starts.

**The bootstrap problem.** `lt.util.load` reads files during startup, before
anything else exists. Whatever serves it has to be available at that point,
which the preload is — it runs before the page. No obstacle, but it constrains
ordering: `lt.util.load` migrates first or the rest cannot load.

## Plugins are still the gate

This is unchanged by any of the above, and it is the part that decides the
schedule rather than the design.

Turning on `contextIsolation` removes `require` from the window, which is where
plugins run. The Clojure plugin calls `require("net")` at namespace load time for
nREPL; the Javascript plugin does the same. Both ship precompiled, so nobody can
rebuild them for users who already have them.

The measurements above make the compatibility route look considerably better
than it did. A `require` shim on the bridge — `require('fs')` returning a
bridge-backed object rather than Node's — costs a microsecond per call and can
return handle objects for `net` and `child_process`. It keeps published plugins
working while still removing ambient Node from the window, because the shim
serves a fixed list and nothing else.

**And this is what the capability manifest is for.** A plugin's manifest already
declares what it needs, and Light Table already infers that for plugins with no
manifest. That is exactly the input a per-plugin `require` shim needs: a plugin
declaring `#{:files}` gets an `fs` and nothing else; one declaring nothing
inferred to use `:network` gets `net` too, with a warning. The work already done
on manifests is not preparation for this step — it *is* this step's design.

The open question is attribution: the shim has to know which plugin is calling.
A plugin's code is evaluated with a `sourceURL`, so its frames are identifiable
in a stack trace, but capturing a stack per `require` call is the sort of thing
that needs measuring before it is relied on. Requires happen at plugin load
rather than per operation, which suggests it is affordable — but that is a guess
until it is measured.

## Order

1. ~~**Bundle npm imports**~~ — still to do, and now the largest remaining item.
   See below.
2. ~~**`lt.util.load` onto the bridge.**~~ Done.
3. ~~**The mechanical ones.**~~ Done: `files`, `workspace`, `console`, `deploy`.
4. ~~**The handle cases.**~~ Done: `proc`, `thread`, `tcp`, `ws`.
5. **The plugin `require` shim**, scoped by manifest. Measure stack attribution
   first.
6. **Flip `contextIsolation`.**

### What is actually left

**Bundling.** The window still requires JavaScript files by path: CodeMirror's
122 language modes are discovered by walking `node_modules` at runtime, and
`lt.objs.editor` requires each one. `:js-provider :shadow` bundles static
imports, but a walk cannot be static — so the modes need a generated require
list, adding roughly 1.2MB to a 3MB bundle. That is the largest single piece of
work left before the flip and it is entirely mechanical.

**Plugins.** Unchanged, and still the gate. Every published plugin has `require`
today. The shim below is what keeps them working.

Everything else in Light Table's own code is done.
## Afterwards

`sandbox: true` removes Node from the preload as well. Everything then has to
cross to the main process, at the 118x cost measured above, so it needs the
coarse-capability redesign that the cheap path avoids — `scan this workspace`
rather than `stat this path`, with the walking done on the privileged side.

That is a genuine redesign of the filesystem layer and should be judged on its
own merits later. It is worth noting that it buys less than the step before it:
once the window has no `require` and reaches the system only through a named
list, moving where that list is implemented is defence in depth rather than a
new boundary.

A content security policy remains separate and is still the one thing that
cannot be adopted without removing self-evaluation, which is the feature Light
Table is named for.
