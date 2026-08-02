# Where this is going

Light Table was an argument, not a text editor. The argument — that you should
see what your code does while you write it, not after you run it — was right,
and it is now everywhere: notebooks, playgrounds, inline test results, the
little grey annotations every modern editor puts at the end of a line. Light
Table got there first and then stopped, because being right about one thing is
not the same as being good to work in every day.

So the goal is: **a text editor good enough to live in, that still makes that
argument.** Those pull against each other less than they look, but the order
matters, and this document is about the order.

## The two failure modes

**Becoming a worse VS Code.** Every feature that makes an editor "real" —
completion, diagnostics, go-to-definition, a good file tree — exists elsewhere,
done better, by more people. Chasing them one at a time produces something with
no reason to exist. Light Table already lost this race once.

**Staying a demo.** The counter-failure is treating the inline-result feature as
the whole product and letting everything around it stay 2014. That is the
current state, and it is why the honest answer to "should I use this daily" has
been no.

The way between them: **be excellent at the thing nothing else is excellent at,
and adequate at everything else — where adequate means it never makes you
notice it.** Most of the work below is about removing reasons to notice.

## What "close to its roots" actually means

Worth being specific, because "roots" is otherwise a licence to change nothing.

**Keep:**

- **Inline results.** Evaluate an expression, see the value where the expression
  is. This is the whole idea and every technical decision should protect it —
  it is why a strict CSP is off the table, and why the editor engine question
  is decided by which one treats between-line widgets as a primitive.
- **Behaviors, Objects and Tags.** Configuration is data, the editor is
  introspectable, and a user can change how it works without forking it. This
  is genuinely unusual and worth more now than it was in 2014, when every editor
  was extensible and none were inspectable.
- **The connection model.** Attaching to a running process rather than shelling
  out is what makes the results live rather than a batch report.
- **Plugins that can replace almost anything.** The require shim is why a
  precompiled plugin still loads at all.

  The *second* half of this was dropped on 2026-08-01: `lt.compat` published
  `crate.core` and `crate.binding` so a plugin built before the hiccup library
  was renamed kept drawing, and it is deleted. This fork is one person's
  editor, the only plugins that matter are the ones in this repository, and
  those are ported rather than supported — see doc/hygiene.md. A ten-year-old
  plugin still working stopped being a feature worth carrying a renderer for.

**Do not keep:**

- The 2013 visual design, which is the subject of the next section.
- The assumption that a plugin author will write completion, diagnostics and
  navigation per language by hand. That was reasonable when LSP did not exist.
- Anything that only made sense when the window had Node and the editor
  compiled itself at startup. Most of that is already gone.

## The order

Ordered by how much a daily user notices per unit of work, which is not the
same as ordering by how interesting the work is.

### 1. Stop the editor looking like a prototype

The measured state, and the reason this is first: on a realistic TypeScript
file, CodeMirror 5 emits 7 token types and the default theme renders them in 6
colours, because six token classes share one hue and numbers were painted the
same colour as body text. Rust threw instead of highlighting at all. Both bugs
are fixed; the palette is not.

A theme that used every class the modes already emit would change how the
editor looks more than any parser would, and it is a CSS file. See
[syntax-highlighting.md](syntax-highlighting.md) for the numbers.

This is also where "close to its roots" gets tested honestly. Light Table's look
is part of its identity, and the answer is a modern palette in the same voice —
dark, quiet, high-contrast where it carries meaning — rather than adopting
somebody else's.

### 2. Language support that does not need a plugin author per language

LSP. One client in the editor, a server list per plugin, mapped onto surfaces
Light Table already has: inline results for diagnostics, the doc bar for hover,
the jump stack for definitions. This is the single largest jump in "can I work
in this" per unit of work, and the seams already exist —
[language-support.md](language-support.md), with the preload gaps now closed.

It is also the most in-keeping thing on this list. A language server is a
process you connect to that tells you about your code while you write it. That
is the Light Table thesis with a protocol attached.

### 3. The unglamorous list

The things that make people quietly stop using an editor, none of which are
interesting and all of which matter more than they sound: multiple cursors,
a file tree that behaves, persistent sessions, find-in-project that is fast on a
large repository, undo that never surprises, and a settings experience that does
not require knowing what a behavior is before changing the font.

That last one deserves care. "Configuration is data" is a root worth keeping,
but it is not in tension with a settings screen that writes the data for you.

### 4. The editor engine, if and when there is a reason

CodeMirror 6 is what Light Table would choose if nothing were chosen, and the
migration is smaller than assumed — one published function leaks the CodeMirror
object, and the compiled flagship plugins reference it zero times. It is still
the largest single piece of work here and it produces, on day one, exactly what
exists today. [editor-engine.md](editor-engine.md).

The reason to do it eventually is not highlighting. It is that Lezer and
tree-sitter make **structural** editing possible for every language — what
Paredit does for Lisps — and structural editing is the natural extension of
Light Table's argument from *values* to *code*: the editor understanding what
you are writing, not just what it looks like. That is a root, not a departure.

## What this is not

Not a roadmap with dates, and not a promise. It is the order to work in, so that
each piece is worth doing on its own and none of it is a prerequisite for
something that never happens.

The test for any addition: **does it make Light Table more itself, or more like
everything else?** Both answers are sometimes acceptable — multiple cursors make
it more like everything else and should still be done — but the question should
be asked out loud, because the failure mode is answering it by accident.
