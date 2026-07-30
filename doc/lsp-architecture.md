# LSP: the architecture

The first slice is built: TypeScript diagnostics, drawn inline.
[language-support.md](language-support.md) argues *why* the protocol belongs in
the editor rather than in each plugin; this says *how*, and — at the end —
what building it corrected about this page.

## The one-line version

```
plugin.edn declares a server  →  lt.objs.clients.lsp spawns and speaks JSON-RPC
                              →  notifications become Light Table events
                              →  existing behaviors render them
```

No new UI. Every LSP response has somewhere it already belongs.

## Why this fits Light Table rather than being bolted to it

Light Table's client abstraction — `lt.objs.clients` — is already *a thing you
connect to, send messages to, and get results back from, which may die and be
reconnected*. That is a language server. It differs from the nREPL and browser
clients this editor already drives in exactly two ways:

| | existing clients | a language server |
|---|---|---|
| transport | tcp socket, or socket.io | the process's own stdin/stdout |
| framing | line-delimited JSON, or bencode | `Content-Length:` headers, then JSON-RPC |

Both gaps are now closed on the privileged side: `ProcessHandle` gained `write`
and `endStdin`, and `onStdoutBytes` delivers bytes rather than per-chunk
decoded text — which LSP needs, because `Content-Length` counts bytes and a
multi-byte character can straddle a chunk.

## The layers

Four, and the boundaries are chosen so each can be tested without the one below
it.

### 1. Framing — `lt.objs.clients.lsp.wire`

Bytes in, messages out. Pure, and therefore the only part with real unit tests.

```clojure
(defn feed
  "Append `chunk` to `buffer`, returning {:buffer :messages :errors}."
  [buffer chunk] ...)

(defn encode
  "A message as Content-Length framed bytes."
  [message] ...)
```

This is where the fiddly things live and where they are cheap to get right:
a header split across two reads, a body split across five, `Content-Type`
present or absent, and a message whose byte length differs from its character
length. All of that is a function of a byte vector and can be tested as one.

### 2. The connection — `lt.objs.clients.lsp`

One server process, its lifecycle, and request correlation.

- Spawn through `lt.util.bridge.processes.spawn`; feed `onStdoutBytes` into
  `wire/feed`; write with `wire/encode`.
- **Lifecycle:** `initialize` → `initialized` → *(work)* → `shutdown` → `exit`.
  Requests sent before `initialize` returns are queued, because a server is
  entitled to reject them and several do.
- **Correlation:** an integer id per request, a map of id to callback. Requests
  that outlive their server are failed rather than left pending.
- **Server-initiated requests** get answered, not ignored — at minimum
  `window/workDoneProgress/create` and `client/registerCapability`, which some
  servers block on.
- Every event carries the connection it came from. One window talks to several
  servers and they all report to the same object, so `:lsp.exit` without a
  connection is a message that cannot be acted on.

### 3. Document synchronisation — `lt.objs.clients.lsp.sync`

The part everyone underestimates, and the usual source of "it worked and then
stopped".

- `textDocument/didOpen` when an editor with a matching tag opens; `didClose`
  when it closes.
- `didChange` on edit. **Incremental** where the server advertises it,
  full-text where it does not — its `initialize` result says which, and sending
  the wrong kind desynchronises quietly.
- Positions are **UTF-16 code units** in LSP, which is what CodeMirror uses
  too, so no conversion — but the server may negotiate UTF-8, and then it does.
  Worth handling once here rather than wrongly in three places.
- Every request carries the document version it was computed against, so a
  stale response can be dropped rather than drawn at the wrong offsets.

### 4. Surfaces — one behavior each

The reason this is worth doing once: nothing here is new UI.

| LSP | rendered by | already exists as |
|---|---|---|
| `publishDiagnostics` | inline widget at the line | `lt.objs.eval` inline results |
| `completion` | the completion list | `lt.plugins.auto-complete` |
| `hover` | the doc bar | `lt.objs.docs` |
| `definition` | jump, with a way back | `lt.objs.jump-stack` |
| `references` | the search sidebar | `lt.objs.search` results list |
| `documentSymbol` | the navigate bar | `lt.objs.sidebar.navigate` |
| `rename` | a workspace edit | — the one genuinely new piece |

One behavior per row, each independently switchable. A user who wants
diagnostics but not completion turns one off, which is what the behavior system
is *for* and what makes this feel like Light Table rather than like a port.

## What a plugin declares

```clojure
{:name "TypeScript"
 :capabilities #{:processes :files}
 :language-servers
 [{:tags    #{:editor.typescript :editor.tsx}
   :root    ["tsconfig.json" "jsconfig.json" "package.json"]
   :command ["node_modules/.bin/typescript-language-server" "--stdio"]}]}
```

`:root` is the marker list `LT.projectRoot` already takes. `:command` resolves
relative to the project root, the same rule the TypeScript plugin's type-check
uses and for the same reason: checking against a different compiler than the
project builds with reports differences that are not the code's.

**Capabilities fall out of it.** A language server is a spawned process reading
the project, so `:processes` and `:files` — which the manifest already
expresses and `lt.objs.plugins.capabilities` already infers.

## Failure, which is the normal case

A language server is somebody else's program, started from a project that may
not have installed it. So:

- **No server installed** is not an error. The plugin's other features keep
  working, and the Connect bar says the server is absent rather than the editor
  saying nothing.
- **A crash** is reported once, with the last stderr, and the connection is
  retried with backoff — not on a loop that hides the cause.
- **A hang** is a timeout per request, not a spinner forever.
- Everything else the server prints to stderr goes to the console, because that
  is where a plugin author will look.

## The first slice — built

**TypeScript diagnostics, and nothing else.** One server, one surface. It
exercises the whole spine — spawn, frame, initialize, sync, a notification, a
rendered result — and it is the smallest thing that is useful on its own.
Completion and navigation are additive on top of it rather than prerequisite.

What ships:

| | |
|---|---|
| `lt.objs.clients.lsp.wire` | framing, 20 tests |
| `lt.objs.clients.lsp` | one process, its lifecycle, request correlation |
| `lt.objs.clients.lsp.sync` | URIs, positions, changes, versions, 20 tests |
| `lt.objs.editor.lsp` | which server goes with which editor, and where its answers are drawn |

Server table in `lt.objs.editor.lsp/servers`, an atom keyed by editor tag, so a
language plugin can add one without waiting for an editor release. One
connection per project root and server, shared by every editor under it.
Configured out of the box:

| tags | server | install |
|---|---|---|
| `:editor.typescript`, `:editor.tsx` | `typescript-language-server` | `npm i -D typescript-language-server` |
| `:editor.clj`, `:editor.cljs` | `clojure-lsp` | `brew install clojure-lsp/brew/clojure-lsp-native` |

clojure-lsp is worth its own note. Its diagnostics come from **clj-kondo**,
which is what `make check` already runs on this repository — so what it says in
the editor is what CI will say, rather than a second opinion to reconcile. It
is a native binary and not an npm package, so `PATH` is the only place it can
be found; wiring it up was impossible until `PATH` was looked at. It indexes
the whole project before answering, which takes a while the first time and is
cached in `.lsp/.cache`.

Its overlap with the Clojure plugin is worth knowing about but is not yet a
conflict: the plugin's jump-to-definition, docs and completion come from a live
nREPL connection and know what is actually loaded, while clojure-lsp reads the
source. Only diagnostics are implemented here, and the plugin has none, so
today they do not meet. When navigation and completion arrive, which of the two
answers a keystroke is a real decision and not an implementation detail.

The server is looked for in the project first, then on `PATH`:

```
<project root>/node_modules/.bin/typescript-language-server
$PATH
```

Project first because a language server is a compiler, and checking against a
different one than the project builds with reports differences that are not the
code's. `PATH` second because refusing to start when a perfectly good server is
installed globally is worse than a version skew nobody has hit yet.

```
npm install --save-dev typescript-language-server    # or -g
```

On macOS the `PATH` half works only because `lt.objs.proc/set-path-OSX` sources
the login shell's at startup: an application launched from Finder inherits
almost nothing, so a version manager's shims are otherwise invisible.

A project with no server installed is not an error — nothing starts, and
nothing else about the editor changes. Which makes it indistinguishable from a
bug, so **Language server: Status for this editor** says which of the ways this
can be quiet is the one in play: no server configured for the file type, no
project root above the file, nothing installed under either name, or connected
and working.

Deliberately *not* in it: multi-root workspaces, workspace edits, server-side
file watching, and any surface other than diagnostics. Nor is the plugin-side
`:language-servers` declaration below — the server table is the same shape and
reading it from a manifest is a small addition once there is a second plugin
that wants one.

## What building it settled

The three risks this page listed, answered by the code rather than in advance:

- **Sync is the whole ballgame — confirmed, and worse than stated.** Two
  separate bugs each produced a version counter that incremented correctly
  while `didChange` never reached the server, so the editor looked synchronised
  and the server answered about the file on disk. Nothing about the symptom
  said "synchronisation": diagnostics appeared, they were real, they were
  simply about text nobody was looking at. The one that took longest was an
  argument position — `:change` is raised with CodeMirror's two arguments, the
  instance and *then* the change — and Light Table catches exceptions inside
  behavior reactions, so it cost nothing visible. Verified now against a real
  `typescript-language-server` with the traffic teed to a file: 23
  single-character edits, 23 incremental changes, diagnostics landing on the
  right lines throughout.
- **Inline diagnostics against a file with two hundred errors — still open.**
  Grouping by line helps (three errors on one line are three sentences in one
  widget, not three boxes), and a file where every line is wrong has not been
  tried. The honest answer may still be a gutter marker with a count.
- **`lt.objs.clients` did not fit, and was not bent.** It is built for
  connections Light Table dials or accepts, keyed by a client id and rendered
  in the Connect bar. A language server is a child process this window owns,
  addressed by project root, and there is nothing for a user to connect to. So
  `lt.objs.clients.lsp` is a sibling: a connection is an atom, and the events a
  user can see are raised on one `:lsp.client` object with the connection as an
  argument. Adding a Connect bar entry later is additive; starting there would
  have meant inventing a client id nobody names.

And one thing the design did not anticipate at all: a line widget must be a
single element. Handing CodeMirror a document fragment is the obvious way to
give a line several diagnostics and it throws inside `addLineWidget` —
*after* the nodes are in the measuring container, so they are on screen while
the widget that should own them does not exist.
