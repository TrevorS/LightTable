# LSP: the architecture

Not built. This is the design, at the level of detail where the decisions are
visible and the work is estimable. [language-support.md](language-support.md)
argues *why* the protocol belongs in the editor rather than in each plugin;
this says *how*.

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

```
(defn feed
  "Append `bytes` to `buffer`, returning [remaining-buffer messages]."
  [buffer bytes] ...)

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
- Registers as a Light Table client so it appears in the Connect bar, dies
  visibly, and reconnects the way every other client does.

### 3. Document synchronisation — `lt.objs.clients.lsp.sync`

The part everyone underestimates, and the usual source of "it worked and then
stopped".

- `textDocument/didOpen` when an editor with a matching tag opens; `didClose`
  when it closes.
- `didChange` on edit, debounced. **Incremental** where the server advertises
  it, full-text where it does not — its `initialize` result says which, and
  sending the wrong kind desynchronises quietly.
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

## The first slice

**TypeScript diagnostics, and nothing else.** One server, one surface, one
plugin. It exercises the whole spine — spawn, frame, initialize, sync, a
notification, a rendered result — and it is the smallest thing that is useful
on its own. Completion and navigation are then additive rather than
prerequisite.

Deliberately *not* in the first slice: multi-root workspaces, workspace edits,
server-side file watching, and any server other than one. Each is a real
feature and none is needed to prove the design.

## What could make this wrong

Worth writing down now, so it is checked rather than discovered:

- **Debounce and document version are the whole ballgame.** If sync is wrong,
  diagnostics land on the wrong lines and the feature reads as broken however
  correct the protocol code is.
- **Inline diagnostics may not survive contact with a file that has two hundred
  errors.** Light Table renders eval results inline, so inline is the
  consistent answer, but a gutter marker with the count may be the honest one.
  This is a design question the first slice should answer with a real file, not
  a preference to settle in advance.
- **`lt.objs.clients` may not fit as neatly as it looks.** It was built for
  connections Light Table accepts or dials, not for a child process it owns. If
  the fit is bad, the answer is a sibling abstraction, not bending the existing
  one — and finding that out is part of the first slice.
