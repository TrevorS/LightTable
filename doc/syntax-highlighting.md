# Syntax highlighting, and whether tree-sitter is the answer

Not built. This is what was measured before deciding, because "use tree-sitter"
is the kind of proposal that sounds settled and is not.

## Where highlighting stands

Light Table ships **130 CodeMirror 5 modes**, up from 27 before this work.
`deploy/settings/default/default.behaviors` maps an extension to a mime, and the
mode is in the bundle. Adding a language is a line in that table when a mode
exists, which is the usual case — CodeMirror 5 has modes for essentially
everything, and it is still receiving releases.

So the honest starting position is that highlighting is not broken. What
CodeMirror 5 modes are bad at is narrower than "highlighting":

- **Nesting.** A mode is a per-line state machine, so JavaScript inside a
  `<script>`, SQL inside a template literal, or Rust inside a doc comment
  either gets a purpose-built combined mode or gets nothing.
- **Meaning.** A mode knows `foo` is an identifier. It does not know whether it
  is a parameter, a local, a type or a call, so a theme cannot colour those
  differently however much it wants to.
- **Recovery.** Half-typed code is the normal state of a file being edited, and
  a state machine handles it by guessing.

## What tree-sitter would give, measured

Everything below was run rather than read: `web-tree-sitter` 0.26.11 with
`tree-sitter-javascript` 0.25.0, under node, deliberately using only what a
context-isolated window could also do.

| | result |
|---|---|
| Runtime initialised from **bytes**, not a URL | works, via emscripten's `instantiateWasm` hook |
| Grammar loaded from **bytes** | works — `Language.load(uint8array)` |
| Cold parse of a small file | 4.8 ms |
| **Re-parse after a one-character edit** | **0.2 ms** |
| Highlight query over the tree | 34 captures, named: `keyword`, `variable`, `function`, `property`, `comment`, `string`, `punctuation.bracket` |
| Source with a syntax error | still parses, `hasError = true` |

The bytes result matters more than it looks. The window has no Node and cannot
`fetch` a `file://` URL, so anything WASM has to arrive as bytes through the
bridge. Both blobs load that way, so nothing about context isolation rules this
out.

The incremental number is the real argument. 0.2 ms per keystroke is a budget
that supports re-highlighting on every edit, which a whole-file re-tokenize
would not.

## What it would cost

**A third preload gap.** `lt.util.bridge.files.readFileSync` returns
`fs.readFileSync(path, 'utf8')` — text only. A `.wasm` read as UTF-8 is
corrupted. Byte reads have to exist first. (The other two gaps, both about
process stdio, are in [language-support.md](language-support.md); all three are
in `src-electron/preload.ts` and all three are small.)

**Size.** 200 KB for the runtime, plus roughly 400 KB per grammar, each a
separate `.wasm`. Ten languages is about 4 MB. That is not fatal for a desktop
application, but it is not free either, and it argues for fetching grammars on
demand rather than bundling — which is a `:network` capability and a trust
question, the same one the plugin installer has.

**And the part that actually decides it: CodeMirror 5 was not built for this.**
A CodeMirror 5 mode is a line-based streaming tokenizer. Tree-sitter produces a
whole-document tree with character offsets. Bridging them means a mode that
looks up precomputed tokens for the line it is on — so the mode has to know
which line that is, and CodeMirror 5 does not tell it.

Both approaches were tried in the running editor:

**A mode carrying a line counter in its state** tokenizes correctly — every
style landed at the right position via `getTokenAt`. But CodeMirror restarts
modes from cached checkpoints rather than always parsing from line 0, and
`getStateAfter(350)` returned a counter of **1** where the answer was 351.
`getStateAfter(350, true)` — the *precise* path — returned 351. So the counter
is right whenever CodeMirror parses sequentially, which is what it does to
render, and wrong on the cheap approximate path that things like bracket
matching use. Workable, and resting on a distinction the mode API does not
promise to keep.

**Marking ranges directly with `markText`**, skipping modes entirely, was
measured at **34 ms to apply 200 marks and 30 ms to clear them** — 64 ms per
update against a 16 ms frame budget. That is not a viable per-keystroke path,
and 200 marks is one viewport.

## The conclusion

**Tree-sitter is feasible and is not a highlighting project.**

Every measurement above says the parser side is fine: fast, incremental,
error-tolerant, loadable under context isolation. Every measurement about the
*editor* side says CodeMirror 5 is the wrong shape for it, and no amount of
care changes that — the impedance mismatch is the design of the mode API, which
is why CodeMirror 6 replaced it with Lezer, an incremental parser with exactly
tree-sitter's shape.

So the question "can we use tree-sitter for better highlighting" has an
uncomfortable answer: we can, the highlighting would be somewhat better for a
handful of languages, and the work is mostly fighting an editor API that will
be replaced if this project ever moves to CodeMirror 6. Spending it on colour
is a poor trade.

What would make it a good trade is that **the tree is worth more than the
colours**. The same parse supports structural selection, structural editing
(what Paredit does for Lisps, for every language), symbol outlines, and
smarter navigation — none of which CodeMirror modes can do at all, and none of
which LSP covers well either, since LSP is a network round trip and this is 0.2
ms in-process.

**Recommended order:**

1. **Nothing, for now.** 130 modes work. Adding a mime for a missing language is
   a one-line change and remains the cheapest possible win.
2. **The three preload gaps**, when either of these projects starts. They are
   small, they are all in one file, and two of them are correctness issues
   (byte handling) rather than missing features.
3. **LSP before tree-sitter.** It reuses seams Light Table already has — see
   [language-support.md](language-support.md) — and it answers the questions
   users actually ask about first: errors, completion, jump to definition.
4. **Tree-sitter with CodeMirror 6, or not at all.** If the editor moves, this
   comes nearly free and brings structural editing with it. If it does not, the
   64 ms and the line-counter caveat above are what anyone doing it is signing
   up for, and they should know that going in.

## If someone does it anyway

The shape that survives the caveats: parse in the **worker**, not the window —
it already runs Node, it already has a job protocol, and a 400 KB grammar has no
business in the renderer's bundle. Send back per-line token ranges for the
visible region only. The window then needs a mode that consults a map, and the
line-counter caveat above is the one thing to test first, because everything
else rests on it.
