# LSP: the architecture

The first slice is built: TypeScript diagnostics, drawn inline.
[language-support.md](language-support.md) argues *why* the protocol belongs in
the editor rather than in each plugin; this says *how*, and — at the end —
what building it corrected about this page.

## The one-line version

```
a .behaviors file declares a server  →  lt.objs.clients.lsp spawns and speaks JSON-RPC
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

Where a REPL could answer too, it does — a REPL knows what is actually loaded,
including the function you redefined a minute ago, and there is only one doc
bar. [`lt.objs.providers`](../src/lt/objs/providers.cljs) decides: a client
says what it provides (`:provides #{:doc :completion}`), and one that says
nothing is read by matching its commands against the suffix each surface is
known by, so a plugin compiled in 2014 that supplies documentation keeps
supplying it. That replaced a bare `(string/ends-with? command ".doc")`, which
was correct and unreadable at the point of use — a string with no type, and no
way to find every place it mattered.

| LSP | rendered by | already exists as | |
|---|---|---|---|
| `publishDiagnostics` | inline widget at the line | `lt.objs.eval` inline results | **built** |
| `completion` | the completion list | `lt.plugins.auto-complete` | **built** |
| `hover` | the doc bar | `lt.plugins.doc` | **built** |
| `definition` | jump, with a way back | `lt.objs.jump-stack` | **built** |
| `references` | the search sidebar | `lt.objs.search` results list | **built** |
| `documentSymbol` | the search sidebar | `lt.objs.search` results list | **built** |
| `rename` | a workspace edit | `lt.objs.workspace-edit` | **built** |
| `formatting` | the buffer itself | CodeMirror's own undo | **built** |
| `codeAction` | a popup of choices | `lt.objs.workspace-edit` | **built** |

`codeAction` and `formatting` land on opposite sides of the same line, which
is the clearest way to see what `lt.objs.workspace-edit` is for. A code action
routinely rewrites files you are not looking at — an import added at the top
of another module, a symbol renamed where it is used — so it goes through the
workspace edit, refuses to start when anything it would touch is unsaved, and
is one undo. Formatting only ever touches the buffer in front of you.

Diagnostics are kept now, not only drawn as widgets: a code action is a fix
*for* a diagnostic, and the server expects to be handed back the ones it sent
for the range being asked about. A client that forgets them gets an empty list
from a real server and looks broken.

`formatting` is the one surface that does not go through
`lt.objs.workspace-edit`, and the difference is worth stating. That
namespace is for changing files nobody is looking at: it refuses a buffer
with unsaved changes, and replaces whole files. Formatting is the opposite
— the buffer is in front of you, it is dirty because you have been typing
in it, and the edits are small and positional. So they go into the editor
last-first inside one `editor/operation`, and one undo takes the whole
format back.

`documentSymbol` was going to be the navigate bar and is the search sidebar
instead. Both are lists of places in the project, Light Table already has one,
and a picker would have been a second thing to build, learn and keep working.

One behavior per row, each independently switchable. A user who wants
diagnostics but not completion turns one off, which is what the behavior system
is *for* and what makes this feel like Light Table rather than like a port.

### Which surfaces an editor gets

From the server, not from a table here. A server's `initialize` result lists
what it can do, and `::tag-from-capabilities` turns that into tags —
`hoverProvider` grants `:docable`, `documentSymbolProvider` grants
`:navigable`. Light Table already gates its surfaces on tags, so a language
gets the doc bar because its server said it could answer, and nothing had to
learn the language's name.

### Which answers, when a language also has a REPL

Light Table's own design settles two of the three.

**Completion merges.** `:hints+` is a `raise-reduce`, so every source
contributes and the list is the union. A language server's suggestions and a
REPL's appear together, which is more than either knows alone — the server has
the whole project, the REPL has what is actually loaded.

**Documentation and jump-to-definition defer to a connected REPL.** There is
one doc bar and one cursor, so exactly one answer is wanted, and the REPL's is
the better one when there is a REPL: it knows what is *loaded*, including vars
that exist only because something defined them at runtime, and Light Table is
an editor about the running program. With no REPL — no client, no project, or
a language that has none — the server answers, which is most of the time.

`repl-answers?` decides by asking the clients the editor already has whether
any advertises a command ending in `.doc` or `.jump-to-definition`. By suffix,
because the command belongs to the language — the Clojure plugin advertises
`:editor.clj.doc` — so core needs no table of languages to compare against.

**Diagnostics are not in this argument.** cider-nrepl publishes 183 operations
and not one is a linter: a REPL can say a form threw when you ran it and
nothing about the line you have not run. Diagnostics are the server's,
unconditionally.

## What a declaration looks like

A language server is a `.behaviors` entry, the same shape as a file type:

```clojure
[:lsp.client :lt.objs.editor.lsp/language-servers
 [{:tags [:editor.clj :editor.cljs]
   :language-id "clojure"
   :root ["deps.edn" "project.clj" "shadow-cljs.edn" "bb.edn"]
   :command "clojure-lsp"
   :args []}]]
```

`::language-servers` is non-exclusive and `:type :user`, and entries from every
`.behaviors` file accumulate into one table — exactly as
`:lt.objs.files/file-types` does, where `default.behaviors` contributes dozens
of languages and the Clojure plugin contributes three.

`:tags` is inside the entry rather than being the key. One server usually
answers for several editor tags: clojure-lsp covers `.clj`, `.cljc`, `.edn` and
`.cljs` and there is one entry for all four. TypeScript and TSX stay separate
because their `:language-id` differs — `"typescript"` versus
`"typescriptreact"` — which costs nothing, since connections are keyed by
`[root command args]` and the two share one server.

`:root` is the marker list that decides where the project starts, nearest first
from the file. `:command` is the bare executable name; the project's own
`node_modules/.bin` is looked in before `PATH`, the same rule the TypeScript
plugin's type-check uses and for the same reason: checking against a different
compiler than the project builds with reports differences that are not the
code's.

### Precedence

**Later declarations win**, and behaviors are loaded `default.behaviors`, then
every plugin's, then `user.behaviors` — so the rule reads as *user beats plugin
beats core* without needing a ranking of its own. Pointing at a particular
binary, adding arguments, or turning a server off is then configuration rather
than a source edit, which is how every other knob in this editor works.

More precisely, an entry's place in the table is its **last** declaration
rather than its first. That distinction is not academic: `deploy/core/User` is
both the user directory and an installed plugin, so `user.behaviors` is read
twice — once at the plugin stage and once at the user stage — and keeping the
first arrival parked a user's entry *ahead* of the plugin it was written to
override. `lt.objs.editor.lsp.registry` is the table and the rule, separated
from the rest so that precedence has a test rather than a comment.

### More than one server for a language

A language usually has two now: a compiler-backed server that knows what the
code means, and a linter or formatter that knows what it should look like.
vtsls and biome. pyright and ruff. They answer different questions, so both
run, and each surface is sent to the server whose `initialize` result says it
can answer — the *last* such server, so the same later-wins rule decides who
formats when both offer it.

That is why biome takes formatting from vtsls without either being told the
other exists, and why hover still goes to vtsls: biome advertises no
`hoverProvider`.

Diagnostics are the exception to *one answers*, and the reason they are kept
per connection rather than in one list. `publishDiagnostics` is a replacement —
the whole truth from that server about that file — so a linter publishing would
otherwise erase the type checker's errors a moment after they arrived.

**What makes two declarations the same server** is `:id`, which defaults to the
executable's name. Naming a path — `~/.bun/bin/vtsls` — replaces, because the
name is the same; naming a different executable stacks. Swapping in something
called something else needs an explicit `:id`, and declaring an `:id` with no
`:command` is how a server is turned off.

### Where a declaration lives

Whoever owns the language declares the server:

| case | goes in |
|---|---|
| an in-tree plugin owns the language | that plugin's `.behaviors` file |
| no plugin, or a plugin we do not control | `deploy/settings/default/default.behaviors` |

Both servers Light Table ships with have a plugin, so nothing language-specific
is left in core: TypeScript's is in `plugins/TypeScript/typescript.behaviors`
and clojure-lsp's is in `plugins/Clojure/clojure.behaviors`. The `:lsp.client`
behaviors that remain in `default.behaviors` — `on-notification`, `on-exit`,
`on-stderr`, `on-error`, `on-ready` — are surfaces, and a surface is the same
question for every language.

**Capabilities fall out of it.** A language server is a spawned process reading
the project, so the plugin that declares one declares `:processes` and
`:files` — which the manifest already expresses and
`lt.objs.plugins.capabilities` already infers. That is the point of putting the
declaration in the plugin: a server spawned from core is a process accounted to
nobody.

### Not a `plugin.edn` key

An earlier draft of this page sketched a `:language-servers` key in
`plugin.edn`. It is not built and will not be. It would be a second mechanism
for something behaviors already express, and a manifest is a *fact* about a
plugin rather than a setting — so a user could not override it, which is the
whole reason this is data. One mechanism.

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
| `lt.objs.editor.lsp.registry` | the server table and its precedence rule, 6 tests |

One connection per project root and server, shared by every editor under it.
Declared out of the box, each by the plugin that owns the language:

| tags | server | plugin | install |
|---|---|---|---|
| `:editor.c`, `:editor.cpp` | `clangd` | C | `brew install llvm` |
| `:editor.clj`, `:editor.cljs` | `clojure-lsp` | Clojure | `brew install clojure-lsp/brew/clojure-lsp-native` |
| `:editor.css` | `vscode-css-language-server` | CSS | `npm i -g vscode-langservers-extracted` |
| `:editor.scss` | `vscode-css-language-server` | CSS | as above |
| `:editor.elixir` | `elixir-ls` | Elixir | see github.com/elixir-lsp/elixir-ls |
| `:editor.go` | `gopls` | Go | `go install golang.org/x/tools/gopls@latest` |
| `:editor.html` | `vscode-html-language-server` | HTML | `npm i -g vscode-langservers-extracted` |
| `:editor.java` | `jdtls` | Java | `brew install jdtls` |
| `:editor.json` | `vscode-json-language-server` | JSON | `npm i -g vscode-langservers-extracted` |
| `:editor.lua` | `lua-language-server` | Lua | `brew install lua-language-server` |
| `:editor.php` | `intelephense` | PHP | `npm i -g intelephense` |
| `:editor.python` | `pyright-langserver` + `ruff` | Python | `pip install pyright ruff` |
| `:editor.ruby` | `ruby-lsp` | Ruby | `gem install ruby-lsp` |
| `:editor.rust` | `rust-analyzer` | Rust | `rustup component add rust-analyzer` |
| `:editor.shell` | `bash-language-server` | Shell | `npm i -g bash-language-server` |
| `:editor.toml` | `taplo` | TOML | `brew install taplo` |
| `:editor.typescript`, `:editor.tsx`, `:editor.javascript` | `vtsls` + `biome` | TypeScript | `npm i -g @vtsls/language-server @biomejs/biome` |
| `:editor.yaml` | `yaml-language-server` | YAML | `npm i -g yaml-language-server` |
| `:editor.zig` | `zls` | Zig | `zig fetch --global zls` |

Every language with a bundled tree-sitter grammar has a server here, which is
not a coincidence — a grammar and a server are the two halves of supporting a
language, and a language with one and not the other is a gap worth an issue.
The pairs are listed in `lt.objs.editor.treesitter/grammars` and in the plugin
behaviors above, and nothing enforces the correspondence; it is a thing to
check when adding either half.

`vtsls` rather than `typescript-language-server`, which this table named for a
while: it wraps the same extension VS Code ships, so completion matches what
most people are used to. If you go back to `typescript-language-server`, install
`typescript` alongside it and pin it to 5.x — that server drives `tsserver.js`,
and TypeScript 7, the native rewrite, does not ship one, so a plain
`npm i -D typescript` gets a compiler it refuses to start against. It says so on
stderr and Light Table puts that on the console, which is the only reason that
took minutes rather than an afternoon.

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

The `PATH` half works only because `lt.objs.proc/resolve-shell-env` asks the
user's own shell for an environment at startup, login *and* interactive, the way
VS Code's `shellEnv.ts` does. An application launched from Finder or a desktop
entry inherits almost nothing, so a version manager's shims are otherwise
invisible — and `-i` is the half that matters, because fnm, nvm, asdf and mise
all hook themselves into `~/.zshrc`, which a login shell does not read.

Asking a shell is not instant, so **`lt.objs.proc/on-env-ready` stands in front
of `::use-language-server`**. Without it, an editor restored at startup asks for
a server before the `PATH` that would find one has arrived, and losing that race
costs the whole session rather than a moment: the server is recorded as not
installed and nothing asks again. That waiting is bounded, for the reason the
docstring gives — a gate only something-going-right can open would be a worse
bug than the one it fixes.

And a server found in the project's own `node_modules/.bin` **does not escape
this**, which is what made it look like several bugs: `node_modules/.bin/biome`
is a script beginning `#!/usr/bin/env node`, so finding it and running it are
different questions. Without a `node` on `PATH` the local server fails to exec
while the global one fails to be found, and the two report differently.

A project with no server installed is not an error — nothing starts, and
nothing else about the editor changes. Which makes it indistinguishable from a
bug, so **Language server: Status for this editor** says which of the ways this
can be quiet is the one in play: no server configured for the file type, no
project root above the file, nothing installed under either name, or connected
and working.

Deliberately *not* in it: multi-root workspaces, workspace edits, server-side
file watching, and any surface other than diagnostics.

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

## What making the table data settled

The server table began as an atom in `lt.objs.editor.lsp`, written to from the
source. Three things it could not do — a user could not override it, precedence
between two matching entries was undefined, and a process spawned from core was
accounted to no plugin — are what moved it to a behavior. What that turned up:

- **"Later wins" is not "the last file wins".** Reactions arrive in reverse of
  the order behaviors are stored, and `deploy/core/User` is read twice, because
  it is both the user directory and an installed plugin. Written to keep an
  entry's *first* arrival, a `user.behaviors` override of clojure-lsp was
  parked ahead of the plugin it was written to beat, and lost. Position by
  *last* arrival fixes it and is stable under reload, since a reload replays
  the same sequence.
- **Declaring it in the plugin made the manifest true.** Widening the smoke
  test's capability probe from two plugins to all seven immediately reported
  four manifests that did not match what inference read out of the plugin's own
  JavaScript. Declared and used now agree exactly for all seven, which is the
  first time that has been checkable.
- **Nothing needed a new surface.** The declaration is a `.behaviors` entry
  shaped like a file type, and file types have looked like that since before
  any of this.
