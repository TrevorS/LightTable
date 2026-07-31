# Rename, and the workspace edit underneath it

A scouting note. Rename is the last of the seven LSP surfaces
[lsp-architecture.md](lsp-architecture.md) lists, and the only one that is not
a request drawn into a surface Light Table already has. It is a *write*, across
files, some of which are open and some of which are not.

Everything below was measured against clojure-lsp 2026.07.06 and
typescript-language-server 5.3 rather than read out of the specification.

## What the servers actually send

Both answer `textDocument/rename` with a `WorkspaceEdit` in its **`changes`**
form — a map of document URI to a list of `TextEdit` — and neither used
`documentChanges`, which is the other legal shape.

clojure-lsp, renaming `add` to `plus` in a file with two uses:

```json
{"changes": {"file:///…/src/probe.clj": [
   {"range": {"start": {"line": 4, "character": 5},
              "end":   {"line": 4, "character": 8}}, "newText": "plus"},
   {"range": {"start": {"line": 7, "character": 5},
              "end":   {"line": 7, "character": 8}}, "newText": "plus"}]}}
```

typescript-language-server is the same shape with the keys in the other order.

They differ on one thing worth knowing:

| server | `renameProvider` |
|---|---|
| clojure-lsp | `{"prepareProvider": true}` |
| typescript-language-server | `true` |

`prepareProvider` means the server will answer `textDocument/prepareRename`,
which says whether the symbol under the cursor *can* be renamed and what range
the old name occupies. It is what stops a rename being offered on a keyword or
a string literal. It is optional and only one of the two has it.

## The blocker, and it is in our code

**`changes` is keyed by URI, and Light Table destroys those keys before a
caller ever sees them.**

`lt.objs.clients.lsp.wire` parses every message with

```clojure
(js->clj (.parse js/JSON text) :keywordize-keys true)
```

That is right for the whole protocol except here. LSP keys maps by fixed names
everywhere else, so keywordising is free; `WorkspaceEdit.changes` is the one
place a map is keyed by arbitrary text. `file:///private/tmp/…/probe.clj`
becomes a keyword whose namespace is `file:` and whose name is the rest, and
what comes back out is `"file:"`. Observed, not theorised — that is the literal
key the probe returned for both servers.

So rename cannot work at all until the framing layer stops keywordising, or
stops keywordising that one key. Neither is hard; both are decisions:

- **Do not keywordise at all**, and read responses with string keys. Correct,
  and touches every existing surface.
- **Keywordise everything except `changes`.** Surgical, and a special case
  someone will later delete without knowing why.
- **Keep the raw JSON alongside the parsed message.** Costs memory per message
  and lets a caller reach for whichever it wants, which is two shapes to know.

The first is the honest one and the reason to do it now, while there are six
surfaces to update rather than twenty.

## Applying the edit

Three things have to be true and only one of them is free.

**Edits within a file must be applied last-first.** Every range is stated
against the *original* document, so applying the first edit moves every range
after it. Sorting by start position descending and applying in that order
avoids needing to track the drift.

**An open file must be edited through its editor.** Light Table already has a
workspace-wide replace, in `lt.background.file-search`, and it writes files
with `writeFileSync` from the worker thread — including files that are open in
a tab. The buffer and the disk diverge, and the tab does not know. That is an
existing bug, and it is the exact mistake rename must not repeat. A file with
an editor gets `editor/replace`; a file without gets read, edited, written.

**Undo is the unsolved part.** `editor/operation` groups any number of
`replaceRange` calls into a single undo step — verified: three separate
replacements inside one `operation`, one `cm.undo()`, document back to where it
started. So *within* one open file, one undo works today and needs nothing new.

Across files it does not exist. CodeMirror's history is per document, so
undoing a rename that touched four files means four undos in four tabs, in no
particular order, and the files that were never open have no history at all —
their previous contents are gone the moment they are written.

That is the real design question, and it is not an LSP question:

- **Open every file the rename touches** before applying anything. Then every
  edit is in a buffer with history, nothing is written until the user saves,
  and "undo" is the editor's own — per tab, but present. Renaming a common
  function could open fifty tabs.
- **Keep a rename journal** — the previous text of every file — and offer a
  single "undo rename" command that restores it. One undo for the whole
  operation, which is what the user means, at the cost of a mechanism that is
  Light Table's alone and has to be got right.
- **Refuse to touch closed files**, rename only what is open, and say so. Least
  useful and least surprising.

## Before any of it, validate

A rename that half-applies is worse than one that does not start: the code no
longer compiles and the editor cannot say which half it did. So the order is
collect, check, then write — every target file exists and is writable, and
nothing has changed underneath since the server answered. `WorkspaceEdit` can
carry document versions for exactly this, though neither server sent them.

Showing the user what is about to change, before it changes, is the other half
of that. It is also a list of places in the project, which is the surface
[references and document symbols](lsp-architecture.md) already use.

## Scope

- Files: `src/lt/objs/clients/lsp/wire.cljs` (the keywordising decision), a new
  `lt.objs.workspace-edit`, `src/lt/objs/editor/lsp.cljs`, and every existing
  LSP surface if response keys stop being keywords
- Named units: 1 behavior, 1 command, `prepare-rename` and `apply-edit!`, plus
  whatever the undo decision above turns into
- Verification: unit tests for last-first ordering and for the URI round trip;
  live rename against both servers across an open and a closed file; a smoke
  check that the buffer and the disk agree afterwards
- Risk: public API no · data migration no · **destructive yes** — this writes
  files · cross-module yes (wire, editor, files, search) · reversible only as
  well as the undo decision makes it · external blocker no
