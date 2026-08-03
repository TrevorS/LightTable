# Syntax highlighting with tree-sitter

**Built.** This page was written as a scouting note arguing tree-sitter was not
worth it for highlighting; the argument was wrong in a specific and interesting
way, and the measurements that overturned it are kept below the line.

## What it does

Editors whose language has a bundled grammar are highlighted from a parse tree
rather than a per-line tokenizer. On a realistic TypeScript file that is **14
capture classes in 10 colours**, against 7 token types in 6 from CodeMirror's
javascript mode — and the difference is not mainly quantity. Tree-sitter can
tell a type from a value, a parameter from a local, a method call from a
variable. A per-line state machine cannot, at all, ever.

Capture names arrive as every prefix of themselves, so `variable.parameter`
becomes `cm-ts-variable cm-ts-variable-parameter`. A theme can be broad or
precise and both work, with specificity deciding. That cascade, and the fact
that the names are a **vocabulary Helix, Neovim and Zed already share**, is the
real payoff: a theme becomes a stylesheet rather than a port.

| | |
|---|---|
| Grammars bundled | JavaScript, TypeScript, TSX, JSX, **Clojure**, Python, Rust, Go, C, C++, Java, Ruby, PHP, Elixir, Zig, JSON, YAML, TOML, CSS, **SCSS**, HTML, Bash/Shell — plus regex and jsdoc, which are injected and never opened |
| Runtime load | once, ~0ms after the first grammar |
| Grammar load | ~46ms, once per language, on first use |
| Parse | 0.8ms for a small file; 0.2ms incremental after an edit |
| Shipped size | 6.5MB of WebAssembly, loaded on demand |

Adding a language is usually two lines in `lt.objs.editor.treesitter/grammars`
plus the npm dependency — and that map is an atom a plugin can `swap!`, so a
language plugin can bring its own grammar without waiting for an editor release.

Most grammars come from npm packages that ship a prebuilt `.wasm` and a
`queries/highlights.scm`. Two do not and are built from source and committed
under `deploy/core/grammars/`: SCSS, whose package publishes native bindings
only, and Clojure, whose package publishes neither those nor a query. Clojure's
highlight query is Light Table's own — worth the effort for the language this editor is written
in. That directory's README has the build, which needs neither docker nor
emscripten: the tree-sitter CLI fetches its own wasi-sdk.

Measured in the running editor, capture classes against what the CodeMirror
mode managed for the same file:

| | CodeMirror | tree-sitter |
|---|---|---|
| Clojure | 7 | **15** |
| TypeScript | 7 | **14** |
| Rust | broken entirely | **12** |
| C++, Java, Ruby | 6-7 | **9** |

The mime table still decides indentation, commenting, bracket matching and
folding. Only the colouring changes, and only where a grammar exists.

## Indentation, where nothing else can do it

Colouring is the first thing a parse tree is used for and not the most useful.
The second is knowing where a line belongs.

The obvious route is `indents.scm`, the query file Neovim's indenter reads —
and of the grammars bundled here, exactly one ships one. So this is structural
instead, from the tree every grammar has:

> **Indentation is the number of distinct lines on which something still open
> here was opened.**

Not the number of enclosing nodes. That is the same trap `bracketDepths`
avoids: grammars disagree wildly about how many wrapper nodes sit between a
construct and its body, so counting ancestors indents the same code differently
per language. A `function_declaration` and the `statement_block` inside it both
begin on the line with the `{`; a reader sees one opening there and so does
this. The one exception is a line that *closes* something — a `}` or an `end`
belongs outside the block it finishes — which is one condition rather than a
table of per-language delimiters.

It is used only where nothing better exists: a language with a real parser has
a real indenter written against a real grammar, and a structural rule that is
right most of the time is a regression against one that is right. `modeHasParser`
in `cm6-modes.ts` is that test, so this applies to Elixir and Zig today and to
any future grammar-only language without anyone deciding again.

One trap worth writing down, because it cost a working feature for the length
of one build. CodeMirror's `getIndentation` walks the `indentService` facet and
takes the **first result that is not `undefined`** — so `null` is an answer, and
the answer it gives is "this line has no indentation". A service installed on
every document that returns `null` to mean "not my business" turns smart indent
off for the whole editor, language indenters included.

## A file is not always one language

The `<script>` in an HTML file is JavaScript and the `<style>` is CSS. A Rust
macro body is Rust. A tagged template literal is whatever its tag says. Every
per-line tokenizer that ever handled this did it by hand — CodeMirror's
`htmlmixed` is a mode written to know about two other modes — and it stops at
whatever its author thought of.

Tree-sitter says it in a query instead, and the grammars ship the query:

```scheme
((script_element (raw_text) @injection.content)
 (#set! injection.language "javascript"))
```

So this is not a feature per language pair. It is one pass — `injectionRegions`
in `src-window/treesitter.ts` — over queries that already exist, in the same
vocabulary Helix and Neovim use. What makes it cheap is `includedRanges`: the
inner parser is handed the *whole document* and told which parts of it to read,
so its captures come back in the host document's coordinates and merge into the
same span table. Nothing is extracted, nothing is offset.

Three details are load-bearing.

- **The inner language wins.** Injected captures are appended after the host's,
  and equal-width ties go to the later one. HTML's `@string` over a `<script>`
  body and JavaScript's `@keyword` over `const` are the same characters; only
  one of them knows what they are.
- **Children are holes.** Without `injection.include-children`, what gets parsed
  as the other language is the text *between* the content node's children. That
  is what makes `` html`<p>${name}</p>` `` work: `${name}` is JavaScript and must
  not reach the HTML parser.
- **A bracket is counted once.** Rust injects Rust into its own macro bodies, so
  the same `(` arrives from two grammars. Counting it twice would leave every
  rainbow colour after it one level out.

Grammars load on first *sighting* rather than up front, so an HTML file with no
`<style>` never pays for CSS — and the first parse of one that has it cannot
highlight it yet. The highlighter reparses and repaints when the grammar
arrives; what you see is the block gaining colour a moment late.

Which languages can appear inside another is the same `grammars` map, read
through `language-aliases` for the names that disagree (`sh` and `shell` are
both the `bash` grammar). A plugin that adds a grammar therefore gets both at
once: its language becomes highlightable *and* injectable.

## What decided it, having first decided the other way

The original conclusion here was "feasible, but not a highlighting project",
resting on two measurements. One was sound and one was not.

**Sound:** `markText` costs 34ms to apply 200 marks and 30ms to clear them,
against a 16ms frame budget. That approach is still wrong and is not what this
does.

**Not sound:** the claim that a CodeMirror mode cannot know which line it is
on. That was tested by keeping a counter in mode state, which indeed does not
survive CodeMirror restarting a mode from a cached checkpoint —
`getStateAfter(350)` reported 1. But CodeMirror passes modes its own `Context`
as `stream.lineOracle`, and that carries `.line`, maintained by CodeMirror.
Verified against a document where tokenizing jumps straight to line 350: it
reports 350. The blocker was an artefact of how it was measured.

With that gone, the design is small: parse on change, build a per-line span
table, and let the mode do a lookup. Tokenizing a line touches no parser.

---

## The original scouting note

Kept because the measurements are still the reason the design looks the way it
does.

## Where highlighting stands

This section used to say highlighting was not broken, on the evidence that
Light Table ships **130 CodeMirror 5 modes** and maps 100 file types to them.
That was the wrong measurement. Counting modes says nothing about what a file
looks like, and looking at what a file actually gets found three problems, two
of them plain bugs:

- **Rust threw instead of highlighting.** CodeMirror's simple-mode addon asks a
  rule's token for an `apply` method to decide whether it is a function to
  call, and extending `js/String` with `IFn` — which is what makes
  `("key" some-map)` work, and is published API — makes ClojureScript put one
  on every string. So the addon called a string and threw. Fixed, and the smoke
  test now instantiates every mapped mime so the next one cannot hide.
- **Numbers were the colour of plain text**, in both Light Table themes.
  `cm-number` was `#ccc` against a `#ccc` body. Fixed.
- **The palette is flat by design, and the design is from 2013.** In
  `default.css`, six token types share `#aec`, three share `#aaa`, three share
  `#acf`. On a realistic TypeScript file that is 7 token types rendered in 6
  colours; after the number fix, still 6.

Measured across six languages after the fixes: **5-7 distinct colours each**,
with `variable` sharing the body colour, which is a normal choice rather than a
bug. That is the floor raised. The ceiling is below.

The reason Clojure "looks reasonable" and TypeScript does not, incidentally, is
rainbow brackets: the theme has thirteen vivid bracket colours and a handful of
muted everything-else, so a Lisp gets colour the palette never gave the tokens.

What CodeMirror 5 modes are bad at is narrower than "highlighting":

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

**A third preload gap, since closed.** `lt.util.bridge.files.readFileSync`
returned `fs.readFileSync(path, 'utf8')` — text only, so a `.wasm` read through
it was corrupt. There is now a `readFileBytesSync` returning a `Uint8Array`.
(The other two, both about process stdio, are in
[language-support.md](language-support.md).)

Worth recording how that check nearly passed for the wrong reason: the first
probe file was the eight-byte WebAssembly header, and every one of those bytes
is below 0x80, so it survives a UTF-8 round trip untouched. The probe now
carries an `0xFF` as well, which is what makes the text reader visibly lose a
byte and the comparison mean anything.

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

## The conclusion at the time — since overturned

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

1. **Theme work first, and it is not a consolation prize.** The measured gap
   between what the modes emit and what the palette shows is real: seven token
   types rendered in six colours, with six of them collapsed onto one hue. A
   modern palette that used every class the modes already emit would change how
   the editor looks more than tree-sitter would, for a fraction of the work, and
   it is reversible. Adding a mime for a missing language remains the cheapest
   possible win after that.
2. **The three preload gaps are closed** — `write`, `endStdin`, `onStdoutBytes`
   and `readFileBytesSync`, with five smoke checks over them. Neither project
   is now blocked on the privileged side.
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
