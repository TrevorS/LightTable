# The editor engine: would we choose CodeMirror 6?

Not a migration plan. This answers a narrower and more useful question — *if
nothing were already chosen, what would Light Table pick?* — because that is
the only honest way to tell a real improvement from an itch to rewrite.

The short answer: **yes, CodeMirror 6, and it is not close.** But the reason is
not the one usually given, and the cost is smaller than
[the earlier assessment](../CHANGELOG-MODERNIZATION.md) claimed. Both of those
are worth the detail.

## What Light Table needs from an editor

Most editor comparisons are about features Light Table does not care about. Its
requirements are unusual and they are short:

1. **Widgets between lines.** Light Table's whole idea is showing you the result
   of an expression next to the expression. That is not a nice-to-have; the
   editor is named for it.
2. **Many independent instances.** Every tab is an editor, and they do not share
   state.
3. **An extension model something else can drive.** Behaviors and Objects attach
   to editors from outside. An editor that insists on owning its own
   configuration fights the whole architecture.
4. **Not owning the DOM around it.** Light Table puts editors inside its own
   tab and pane structure.

Highlighting quality is nowhere on that list, which is why
[syntax-highlighting.md](syntax-highlighting.md) concludes that tree-sitter is
not a highlighting project either.

## How CodeMirror 6 scores

Different software with the same name. State is immutable and changes are
transactions; the view is a separate package; everything user-visible is a
*decoration* over ranges; configuration is composed from *facets* rather than
an options object; and parsing is Lezer, an incremental parser with essentially
tree-sitter's shape.

Against the four requirements:

| | |
|---|---|
| Widgets between lines | **First class.** `Decoration.widget({block: true})` is documented as "drawn between lines", checked in `@codemirror/view` 6.43.7. In CodeMirror 5 the equivalent is `addLineWidget`, a bolt-on to a line-oriented renderer. |
| Many instances | Each `EditorView` owns its state; nothing is global. Better than CodeMirror 5, where modes and commands are registered on the constructor. |
| Externally driven extension | Facets and `StateField` are designed for exactly this — composing configuration from parties that do not know about each other. |
| DOM ownership | Mounts into a supplied parent, same as CodeMirror 5. |

The first row is the one that matters. Light Table's identity feature is a
first-class primitive in CodeMirror 6 and an add-on in CodeMirror 5.

Packaging is fine: `@codemirror/state` 6.7.1, `view` 6.43.7, `language` 6.12.4
and `@lezer/highlight` 1.2.3 all ship dual ESM/CJS with `exports` maps, so
shadow-cljs bundles them the way it bundles everything else. About 1.5 MB
unminified across the four, against a CodeMirror 5 bundle that carries 130 modes.

## What it would actually cost, counted

The earlier assessment said the plugin API break would be "at least as large as
the editor work itself". That was an assumption, and counting says otherwise.

| | |
|---|---|
| `js/CodeMirror` references in `src/` | **59**, across 7 namespaces |
| …of which are `js/CodeMirror.commands.*` in `editor/pool.cljs` | **34** — a mechanical mapping onto `@codemirror/commands` |
| CodeMirror-object leaks in the **published** API | **1** — `lt.objs.editor/->cm-ed` |
| `->cm-ed` callers outside `lt.objs.editor` | 46, of which 33 are that same command list |
| Addon loads | 14 |
| **CodeMirror references in the compiled flagship plugins** | **0** — Clojure, Javascript and Paredit, checked against the shipped artifacts rather than their source |

That last row is the correction. Paredit is the case that should have been worst
— a structural editor, the most editor-coupled thing in the repository — and it
goes through `lt.objs.editor` wrappers (`editor/line`, `editor/last-line`,
`editor/line-length`) without touching CodeMirror once. The abstraction that was
supposed to be leaky mostly is not.

So the shape of the work is: **`lt.objs.editor` is the seam, and it is a real
one.** Rewriting it behind its existing signatures, plus a mechanical command
table, plus 14 addon replacements, is most of the job. That is a large piece of
work — not a small one — but it is bounded and it is in one place, which is a
different proposition from "a break as large as the editor work itself".

The honest caveats: `->cm-ed` is published, so *some* community plugin among the
hundred-plus certainly uses it, and there is no way to scan them. And 130 modes
would have to become Lezer grammars or run through
`@codemirror/legacy-modes`, which exists and carries the CodeMirror 5 modes
forward — worth confirming covers what is bundled before committing.

## The alternatives, briefly

- **Stay on CodeMirror 5.** Still receiving releases — 5.65.21 shipped in
  February 2026, more recently than the `codemirror` 6 meta-package. No security
  cliff, no forced migration. This is a legitimate answer and remains the
  default.
- **Monaco.** VS Code's editor, and the wrong shape here. It wants to own its
  DOM, its workers and its model layer; between-line content is "view zones",
  which is workable and awkward; and it is far heavier. Choosing it would mean
  bending Light Table toward VS Code's architecture, which is the opposite of
  the point.
- **Ace.** The same generation as CodeMirror 5 with a smaller ecosystem. No.
- **Build on Lezer or tree-sitter directly, with a custom view.** This is what
  "if we could use anything" tempts people into, and it is how a two-year
  rewrite starts. The parser was never the hard part; the view is.

## The answer

**If nothing were chosen, Light Table would choose CodeMirror 6** — because
inline results between lines are its reason to exist and CodeMirror 6 treats
that as a primitive, and because Lezer would bring the structural editing and
navigation that [syntax-highlighting.md](syntax-highlighting.md) argues is the
real prize.

**Something is chosen, and CodeMirror 5 works.** So the recommendation is
unchanged and deliberately boring:

1. **Not now.** Nothing is broken, nothing is unmaintained, and no user is
   asking for it. A rewrite of the editor layer is the largest single piece of
   work left in this project and it produces, on day one, exactly what exists
   today.
2. **LSP first**, which needs none of this and answers what users actually ask
   for.
3. **When there is a reason** — structural editing across languages, better
   navigation, or CodeMirror 5 finally stopping — do it through
   `lt.objs.editor` and expect that seam to hold, because Paredit already proves
   it does.

The thing worth keeping from this exercise is not the conclusion but the
number: **zero** CodeMirror references in the compiled flagship plugins. The
editor abstraction is in better shape than anyone assumed, and that is what
makes the migration a project rather than a rewrite.

## Asked again, after the bands (July 2026)

Two of the three conditions above have since been met. LSP is done. And the
inline-result work the design calls the product now exists — `lt.ui.bands`
draws hiccup between two lines of code, which is exactly the thing CodeMirror 6
treats as a primitive and CodeMirror 5 treats as a line widget.

So it is worth asking again, and the answer is still **not yet** — for a
different reason than last time.

**What changed in favour.** The new code's coupling to CodeMirror is two
functions: `ensure-widget!` and `retire!`, both going through
`lt.objs.editor/line-widget`. The band mechanism, which should have been the
hardest thing to port, is the easiest. And question 1 of the design asks which
editor is being hosted, which deserves an answer rather than a default.

**What changed against.** There is a migration in flight. The window renders
from state beside the real chrome rather than as it, and the piece that closes
that gap — making the state the source of truth instead of a projection of the
objects — is the same `lt.objs.editor` surface a CodeMirror 6 port would be
rewriting. Two half-finished migrations through one seam is how both stall.

**The count, re-measured:**

| | |
|---|---|
| `js/CodeMirror` references in `src/` | 61, across 9 namespaces |
| …of which are the command table in `editor/pool.cljs` | 34 |
| `->cm-ed` callers outside `lt.objs.editor` | 50 |
| CodeMirror references in the new Replicant layer | **0** |

That last row is the one that matters, and it is the argument for waiting
rather than against: the new work is already behind the seam, so it costs
nothing to keep building it and porting later.

**The trigger, stated so it can be checked.** Do it when the window is the
chrome — when `lt.state.objects` is deleted rather than merely shrinking. At
that point the editor is mounted by a view through one `on-mount` hook, the
command table has one caller, and the port is a rewrite of one namespace behind
signatures that Paredit has already proved hold. Before that point it is the
same work plus a moving target.
