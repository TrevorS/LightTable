# Language support, and where a language server would fit

Not built. This is the thinking, written down before it is needed, because the
shape of the first language plugin decides how expensive the second one is.

## What a language plugin does today

Three separable things, and it is worth keeping them separate because they fail
independently and users conflate them:

| | who provides it | how to check |
|---|---|---|
| **Colouring** | Light Table. `deploy/settings/default/default.behaviors` maps an extension to a CodeMirror mime, and the mode is in the bundle. | Open a file; if it is grey, the mime resolves to no mode. |
| **Identity** | The same table's `:tags`, e.g. `:editor.typescript`. Behaviors attach to tags. | `lt.objs.files/path->type` |
| **Behaviour** | A plugin. Eval, diagnostics, completion, jump-to-definition. | The Connect bar, and the plugin's commands in the command bar. |

The Clojure plugin is the reference for the third: it registers connectors, so
"Clojure" appearing in the Connect bar is the fastest proof it is not merely
colouring `.clj` files.

That colouring is Light Table's and behaviour is the plugin's is a deliberate
split. A mime is the same question for all 102 file types the editor ships, and
answering it per plugin is how `.ts` came to be registered as
`text/x-typescript` — a mime no mode has ever defined — and stayed that way.

## Why a language server is the obvious next step, and the trap in it

The per-language work above is unbounded. Every language wants completion,
diagnostics, hover, definition, references, rename; every one of them is a
different implementation; and Light Table has one plugin author per language at
best. LSP is exactly the answer to that, and Light Table's model takes it
unusually well.

The trap is doing it inside a language plugin. Whoever writes the first one will
write a JSON-RPC framer, a lifecycle, a document-sync loop and a mapping onto
Light Table's UI — and the second language plugin will copy it, badly. That is
how editors end up with six half-working integrations.

**So the protocol belongs in the editor and the server list belongs in the
plugins.** One `lt.objs.clients.lsp`, many one-page plugins.

## The seams that already exist

The reason this is worth doing once is that Light Table already has everywhere
the answers would go. LSP is a *source* for surfaces that exist, not a new UI:

| LSP | already in Light Table |
|---|---|
| `textDocument/publishDiagnostics` | inline results — `lt.objs.eval/::inline-result`, which already renders a widget at a line |
| `textDocument/completion` | `lt.plugins.auto-complete` |
| `textDocument/hover` | the doc bar — `lt.objs.docs` |
| `textDocument/definition` | `lt.objs.jump-stack`, which already models jump-and-return |
| `textDocument/references` | the search sidebar |
| server lifecycle | `lt.objs.clients` — connectors, connect bar, per-client state |

That last row is the important one. Light Table's client abstraction is already
"a thing you connect to, send messages to, and get results back from, which may
die and be reconnected". An LSP server is that. It differs from the existing
clients only in transport (stdio rather than tcp) and framing (JSON-RPC with
`Content-Length` headers rather than line-delimited JSON).

`lt.util.bridge.processes.spawn` gives a handle with `onStdout`/`onStderr`/
`onExit`, which is the streaming shape a JSON-RPC transport needs.

### Two things the preload does not do yet

This section originally said nothing new was required on the privileged side.
That was wrong, and reading `src-electron/preload.ts` rather than trusting the
summary is what turned it up. Both gaps are small, both are in that one file,
and both should be closed before the first line of protocol code is written —
they are the kind of thing that gets worked around badly if discovered halfway.

**`ProcessHandle` cannot be written to.** It has `kill`, `onStdout`,
`onStderr`, `onExit` and `onError`, and no `write`. A language server over
stdio is a conversation, so stdin is not optional. `SocketHandle` already has
`write`; the process handle needs the same.

**`onStdout` decodes each chunk on its own** — `callback(String(d))` — and that
breaks LSP framing twice over. `Content-Length` counts *bytes*, so a framer
handed decoded text has to re-encode to know where a message ends. And a
multi-byte character split across two chunks is corrupted before the window
ever sees it. `SocketHandle.onData` already gets `Uint8Array` for exactly this
reason, and its comment says so: *"what arrives on an nREPL connection is
bencode, where a message boundary can fall inside a multi-byte character, so
decoding per chunk would corrupt it."* The same sentence is true of LSP.

The fix is to give `ProcessHandle` a byte-oriented `onStdoutBytes` alongside
the existing text one, rather than change `onStdout` under the plugins that
already use it — `plugins/lib/lt.ts` streams from it, and text is the right
shape for a compiler printing diagnostics.

## The shape

**In the editor**, one namespace that knows the protocol and nothing about any
language:

- Frame and parse JSON-RPC over a process handle's stdout.
- Drive the lifecycle: `initialize` → `initialized` → … → `shutdown`/`exit`.
- Keep documents in sync from editor change events.
- Turn server notifications into Light Table events, so the mapping table above
  is a set of behaviors a user can turn off individually.

**In a plugin**, a description rather than an implementation:

```clojure
{:name "TypeScript"
 :capabilities #{:processes :files}
 :language-servers
 [{:tags     #{:editor.typescript :editor.tsx}
   :root     ["tsconfig.json" "jsconfig.json" "package.json"]
   :command  ["node_modules/.bin/typescript-language-server" "--stdio"]
   :fallback :none}]}
```

`:root` is the marker list `LT.projectRoot` already takes, and `:command` is
resolved relative to the project root — the same rule the type-check command
uses, and for the same reason: checking against a different compiler than the
project builds with reports differences that are not the code's.

**Capabilities fall out of it.** A language server is a spawned process reading
the project, so a plugin declaring one needs `:processes` and `:files` — which
the manifest already expresses and `lt.objs.plugins.capabilities` already
infers, including through the bridge.

## What is deliberately not decided

- **Which server per language.** That is a plugin's business, and getting it
  wrong should not require an editor release.
- **Whether servers are bundled.** Downloading a server on demand is a
  `:network` capability and a trust question; requiring the project to provide
  one is what the TypeScript plugin does today, and is the honest default.
- **Diagnostics as inline widgets or as a gutter.** Light Table renders eval
  results inline, so diagnostics inline is the consistent answer, but a file
  with two hundred errors makes that a real design question rather than an
  obvious one.

## Before any of it

The current TypeScript plugin runs `tsc --noEmit` and parses its output. That is
worth keeping even after a server exists: a whole-project check is a different
question from as-you-type diagnostics, it is what CI runs, and it does not need
a server at all. It also produced the first evidence that the seams above are
the right ones — finding the project root, spawning against the project's own
tooling, streaming output back — which is why those live in `plugins/lib/lt.ts`
rather than inside the plugin.
