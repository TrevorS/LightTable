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

**Largely done, and by a different route than this section proposed.** The
argument was that CodeMirror 5 emitted 7 token types on a realistic TypeScript
file and the default theme rendered them in 6 colours, so a better palette over
the classes the modes already emit was the cheapest possible win — a CSS file.

What happened instead is that the engine and the highlighter both changed
underneath it. Tree-sitter highlighting gives **14 capture classes in 10
colours** on the same file, and the difference is not mainly quantity: a parse
tree can tell a type from a value and a parameter from a local, which a
per-line state machine cannot do at all, ever. The capture names are a
vocabulary Helix, Neovim and Zed already share, so a theme is a stylesheet
rather than a port. See [syntax-highlighting.md](syntax-highlighting.md) for the
numbers.

The chrome came with it: `kit.css` is roles rather than colours, every tint
derives from one with `color-mix`, and the component kit is 31 components with
105 states drawn from one registry in two places — in the editor and in a
browser. See [storybook.md](storybook.md).

What is left of this item is genuinely a design question rather than an
engineering one, which is where it should have ended up. Three of the tints in
`kit.css` sit at 2%, 3% and 3.5% of the same colour, which is almost certainly
drift rather than three decisions, and nobody has chosen. This is also where
"close to its roots" gets tested honestly: Light Table's look is part of its
identity, and the answer is a modern palette in the same voice — dark, quiet,
high-contrast where it carries meaning — rather than adopting somebody else's.

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
interesting and all of which matter more than they sound.

| | |
|---|---|
| multiple cursors | done, with the CodeMirror 6 move |
| a file tree that behaves | done — indent from the tree, folder removal sticks, rename in the row it is in |
| undo that never surprises | done, and it surprised three ways: the file itself was undoable, `clearHistory` did not clear, and the dirty dot could not come back |
| find-in-project | done, and measured: `make bench-search` says 161ms → 20ms on this repository. Runs the bundled ripgrep, as VS Code does |
| a settings experience | done. `:type :user` and `:params` have been on every behavior since 2013, so the screen is a projection and a view rather than a subsystem |
| persistent sessions | still open |

That settings one deserved care and got it. "Configuration is data" is a root
worth keeping, but it is not in tension with a settings screen that writes the
data for you — and what it writes is one line of `user.behaviors`, in the format
the file already had.

Persistent sessions is the one left.

The search one is worth reading for the method rather than the outcome. Nothing
had measured it, so `make bench-search` was written before anything was changed,
and it contradicted the reason for making the change twice over.

Reading the whole tree either way, ripgrep is **4.6x** on the wall clock and 5x
per file, and finds precisely what the walk finds — 760 files and 2,490 matches,
both. The shipped configuration is faster again for a different reason: it reads
455 files instead of 13,967, because it honours `.gitignore`. That is a
**behaviour change** rather than an optimisation, and is now a setting people can
see and turn off.

What is worth carrying to the next one of these is how the measurement was wrong,
three times, in three different ways:

- **Comparing rows that had done different amounts of work.** The wall clock said
  `1.5x SLOWER` about an engine that was five times faster, because the two had
  read 3,432 and 1,342 files. Report the per-unit number.
- **Inferring a component instead of measuring it.** The parsing cost was worked
  out by subtracting a run that wrote to `/dev/null` from one read through a
  pipe. Chasing the real number found a correctness bug: matches from inside
  compiled binaries were being reported.
- **Measuring through a personal config.** `RIPGREP_CONFIG_PATH` was in effect
  and was silently overriding `--no-follow` and excluding `node_modules`, so
  every early number was one developer's dotfile. VS Code passes `--no-config`
  for exactly this, and adopting it is what exposed the other two.

An unmeasured optimisation is indistinguishable from a regression. A
*badly*-measured one is worse, because it comes with a number that ends the
argument.

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
