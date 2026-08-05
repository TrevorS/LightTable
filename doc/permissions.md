# The bridge as a permission system

**Built.** This was the scouting note for point 3 of the security section in
[the modernization changelog](../CHANGELOG-MODERNIZATION.md) — the last
substantial named-and-unbuilt item in that plan — and all four modules exist
now. What is below the line is the scout, kept because the counting in it is
what made the work tractable and because two of its numbers turned out to be
wrong in a way worth being able to read.

## What it is, in four namespaces

| | |
|---|---|
| `lt.objs.plugins.attribution` | who is asking, from the stack. Was inside `require-shim` |
| `lt.objs.plugins.scopes` | where a capability reaches. Dependency-free, so the containment rule is testable against the cases that break it |
| `lt.util.bridge.guard` | the wrapper over eight of the fourteen groups, and the table of which arguments are paths |
| `lt.objs.plugins/install-bridge-policy!` | the editor's half: which plugins exist, what each declared, and where `:self` and `:workspace` actually are |

The policy is *installed* rather than reached for, so nothing in
`lt.util.bridge` knows what a plugin is, and until `install-node-compatibility!`
runs the guard is a passthrough — which is what the window needs for the several
hundred bridge calls it makes before a single plugin exists.

## Three corrections to the scout below

1. **The filesystem group is fifteen functions, not twelve.** The scout counted
   from `lt.util.bridge`'s docstring for `files`, which named three of them and
   had gone stale. The docstring is fixed, and `guard_test` now parses
   `preload.ts` and fails when a function taking a path is missing from the
   table. It caught `files/watch` — whose signature wraps onto a second line —
   on its first run.
2. **Twenty-one functions are path-checked, not fifteen.** Fifteen `files/*`,
   three `shell/*`, `net/download`'s destination, and `processes/fork`'s script.
   `spawn` and `exec` are deliberately not scoped: their command is resolved
   against `PATH`, and pretending to scope that would be a guarantee this cannot
   keep.
3. **Seven `path/*` functions are string arithmetic, not four.** `join`,
   `relative` and `resolve` take paths too and touch no disk.

## What the scout did not price

**The cost of attribution on a hot path.** The scout measured the stack read at
1.3-2.7µs and noted that `require` happens once per plugin load. A bridge call
does not — `files/existsSync` is about 0.4µs and is called per file open, per
save, per plugin load. Paying for a stack read on every one of those in order to
discover there was nothing to check would make the editor slower for everyone
not using the feature.

So the guard short-circuits on two derefs: no policy, or no loaded plugin that
has narrowed anything, and the second is cached against the identity of the
plugin map. The cost lands on installations that actually scope something.

**One decision the scout left open, decided.** A capability a plugin never
declared follows the existing `:warn`/`:report`/`:refuse` mode. A path or host
outside a root it *did* declare is refused whatever the mode says. The asymmetry
is the point: the first is the same claim inference already checks and inference
can be wrong, while a root exists only where an author wrote one, and a root
that is not enforced is a comment. Module 4's question — whether the shipped
default moves off `:warn` — is therefore narrower than it was, and is still
open.

---

The scout, as written:

> **The bridge is the permission system**, so its surface should keep being
> designed as one. `readFile` scoped to the workspace is a different thing from
> `fs.readFile`, and the difference is worth having before a hundred plugins are
> written against the wrong one.

The argument for doing it now is the last clause. Every plugin written against
the current surface is written against `fs.readFile` with a different name, and
the number of those only goes up.

## What already exists

Three layers, and only one of them is missing — which is the finding this note
is for.

**The surface.** `src-electron/preload.ts` exposes 67 functions in 14 groups.
It is a list of capabilities rather than a re-export of Node, which is the
thing that makes any of the rest possible: `lt.util.bridge` names that object
and nothing else in the window may.

**A load-time audit.** `lt.objs.plugins.capabilities` maps eight capability
names onto the evidence that implies them, and `allowed-to-load?` holds a
plugin to what its `plugin.edn` declared. Three modes — allow, warn, refuse —
defaulting to warn. It reads compiled JavaScript with regular expressions, so
it catches drift and mistakes rather than concealment, and its own docstring
says so.

**Call-time attribution, which is the part people assume is missing.**
`lt.objs.plugins.require-shim` already works out *which plugin is calling* from
the stack, because Light Table evaluates plugin code with a `sourceURL` and a
frame inside a plugin names its file. Measured at **1.3–2.7µs per stack** on
Electron 43.

That mechanism is applied to `require` and to nothing else. Its own docstring
is explicit about the consequence:

> It is not containment: a plugin runs in the window, so it can reach the
> bridge directly whatever `require` says. What contains a plugin is the
> bridge's surface.

So the hard part — knowing who is asking, cheaply — is built and paid for. What
is missing is applying it one layer down.

## The two gaps, which are different problems

**1. The bridge does not know who is calling.** `js/lightTable` is a global.
Any code in the window reaches all 67 functions, and a plugin held to `:files`
by the load-time audit is held to nothing at call time.

**2. The bridge's arguments are unscoped.** `files/readFileSync(path)` takes
any path. This is the one the changelog names, and it is the deeper of the two:
gating *who* may call `readFile` is worth much less if the answer is still the
whole disk.

The second is much smaller than it sounds. Of the 67 functions:

| | count | needs a policy |
|---|---|---|
| take a path | 19 | **15** — the other four are `path/dirname`, `basename`, `extname`, `isAbsolute`, which are string manipulation and touch no disk |
| take a url, host or port | 4 | 4 |
| take neither | 44 | none — clipboard, zoom, the fifteen window methods, menus, dialogs |

So the scoping problem is **19 functions**, not 67, and twelve of those are one
group: `files`. That is a tractable thing to design rather than a rewrite.

The 44 are not automatically safe — `window/destroy` is not nothing — but they
are *unparameterised*, so the only question about them is whether a caller may
use them at all, which gap 1 answers.

## What a scope has to be

Three decisions, and the middle one is where the design lives.

**Who is asking.** The stack, as `require-shim` already does it. A frame under
a plugin directory names that plugin; a frame under none is Light Table itself
and gets everything. That last part is not a loophole — the editor is the thing
granting permission, and a permission system its own code has to satisfy is one
that gets turned off.

**What a grant says.** The current vocabulary is eight capability *names*, and
a name is not a scope: `:files` means "reads and writes the filesystem". The
smallest useful addition is a *root*, because that is the unit everything in
this editor already deals in — a plugin scoped to the workspace, to its own
directory, or to a named path. `{:files ["~/src/thing"]}` is a sentence a
person can check; `{:files :all}` is the honest name for what every plugin has
today.

Worth being precise about what a root does not cover: a path is not a file's
identity. `realpathSync` exists on the bridge, and a scope enforced on the
string rather than on the resolved path is a scope a symlink walks out of.

**What a denial looks like.** `require-shim` already answers this: a refusal
the plugin can see and a console line saying which capability and which plugin,
rather than a crash. Whatever this does should match it, because a plugin
author debugging a denial is the common case and a stack trace from inside
`fs` is the worst possible version of it.

## Three shapes, and which one to build

**A. Gate at the bridge, in the window.** Wrap `js/lightTable` so every call
attributes its caller and checks a policy. Cheapest by a distance, and it
composes with everything: one wrapper, the existing attribution, the existing
manifest.

Its limit has to be stated plainly, because it is the same limit `require-shim`
has: a plugin runs in the window and can reach `js/lightTable` before the
wrapper is installed, or keep a reference to an unwrapped function. This is
**level 2** — a plugin held to its own word — not containment. It catches drift,
mistakes and honest plugins doing more than they said. It does not stop a
plugin that is trying.

**B. Gate in the preload, where the privilege actually is.** The preload is a
separate world; the window cannot reach past what it exposes. A policy enforced
there cannot be unwrapped from the window at all.

The problem is attribution: the preload cannot see the window's stack. It would
have to be *told* who is calling, by the window, which is the thing being
constrained — so it is worth exactly as much as option A, at more cost, unless
the plugin is in its own context.

**C. A context per plugin.** Real containment: level 3, the process boundary
that `plugins/README.md` already describes. It is the only one of the three
that stops a determined plugin, and it is a much larger project — a second
runtime, a serialisable API, and every synchronous bridge call becoming
asynchronous.

**Recommendation: A, and say what it is.** It is the one that pays for itself
against the actual threat here: a single-user editor with a handful of plugins,
where the realistic failure is a plugin quietly reading more than it said rather
than one written to attack. B buys nothing without C. C is worth
doing when there is a plugin nobody in this repository wrote and everybody
installs — and A is what makes C tractable later, because the policy vocabulary
and the denial path would already exist and only the enforcement point moves.

## Scope

*All four were built. The estimates are left as written — the first two were
close, module 3 was undercounted for the reason above, and module 4 turned out
to be a decision rather than a change.*

**Module 1 — attribution at the bridge.**
- Files: `src/lt/util/bridge.cljs` (+80/-10), `src/lt/objs/plugins/require_shim.cljs` (+20/-20, the frame-walking moves somewhere both can use it)
- Named units: one wrapper over 14 groups; `caller-plugin` extracted from the shim; the existing `allow`/`warn`/`refuse` modes reused
- Verification: existing `require-shim` tests still pass; new units for the wrapper against a faked stack; one e2e proving a plugin's call is attributed to it
- Risk: public API no (the bridge keeps its shape) · cross-module yes (bridge ↔ plugins) · reversible yes

**Module 2 — roots in the manifest.**
- Files: `src/lt/objs/plugins/capabilities.cljs` (+60/-10), `plugins/README.md` (+40)
- Named units: `:capabilities` accepts `{cap roots}` as well as `#{cap}`; a `permits?` predicate; `realpathSync` on both sides of a comparison
- Verification: units for the predicate, including the symlink case, `..`, and a scope that is a prefix-of-a-sibling (`/src/app` vs `/src/application`) — the same bug `pool/containing-path` had
- Risk: data migration yes but backward-compatible (a set means `:all`) · reversible yes

**Module 3 — the fifteen file functions.**
- Files: `src-electron/preload.ts` (+40/-20) or the window wrapper, depending on module 1's outcome
- Named units: 12 `files/*`, 3 `shell/*`
- Verification: a denial per function; the smoke test's capability report gains a scope column
- Risk: this is where a wrong scope breaks a working plugin · reversible yes

**Module 4 — network, and the default.**
- Named units: 4 url/host/port functions; deciding whether the shipped default moves off `:warn`
- Risk: changing the default is the only user-visible break in the whole plan

## What this deliberately is not

It is not a CSP, and it is not a sandbox. The changelog is right that the two
things people bundle together are separable, and that only one of them costs
the feature Light Table is named for. Nothing here touches `eval`: a window
that can evaluate an expression but cannot read outside its workspace is a much
smaller target than one that can do both, and that is the whole of the value on
offer.
