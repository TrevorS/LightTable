# Workflow

I'll assume you already know how to eval code (Cmd/Ctrl-enter), how to open the command bar (Ctrl-space) and how to open files with the navigator (Cmd/Ctrl-o). If you don't, `Ctrl-space` and typing part of a command name is the way to find everything else — the command bar lists what each one does.

Add this repository's `src` to your Light Table workspace and open `src/lt/objs/jump_stack.cljs`. Hit eval
(Cmd/Ctrl-enter) somewhere in the file. The first evaluation starts a ClojureScript compiler *inside
the editor* — no project, no JVM, nothing to install — and after a second or two you get a result
beside the form. Now you're evaluating ClojureScript inside your current Light Table instance: try
`(js/alert "foo")` to be sure. Evaluate the `ns` form at the top of a file first, which is what tells
the compiler which namespace the rest of the file belongs to. Generally, we eval code as we write it
and only rebuild with `make build-cljs` if we need to restart Light Table.

See [Changing the editor while it runs](live-editing.md) for how that works and which files get it.

## Rebuilding, and knowing that you did

Three targets, and the difference between them is what you changed:

| you changed | run | what it does |
| --- | --- | --- |
| any `.cljs` | `make build-cljs` | the window bundle, the worker, the analysis caches, the plugins |
| `src-electron/` | `make build-main` | the main process and the preload — needs a full restart |
| a plugin only | `make build-plugins` | places `plugins/` into `deploy/plugins/` |

Then `make run`, which starts Electron against `deploy/core` — the tree, not
`builds/`. **`make build` is not the iteration loop**: it re-runs three `npm
install`s and packages an app into `builds/`, which `make run` never looks at.
Reach for it when you want the packaged thing.

A window keeps the bundle it started with, so a rebuild does nothing until you
restart. Run **`App: What build is this?`** from the command bar to see what the
window in front of you was compiled from:

> Light Table 0.10.0 — 3423429d with uncommitted changes on develop, built 2026-08-02T18:24:26.601Z

`with uncommitted changes` is the part that matters while you are working: it
means the build included whatever was in the tree, which is what you want to
confirm. The same line is in the version pane (`App: Light Table version`).

This exists because "is my change in the window I am looking at?" had no answer
— the bundle carries no identity and `version.json` is the same string across
every build between two releases — so twice the answer to "it still doesn't
work" turned out to be a window running code from before the fix.

The new Light Table release supports auto-complete (Tab), inline docs (Ctrl-d) and jump-to-definition (Ctrl-. to jump and Ctrl-, to jump back) for ClojureScript and Clojure vars, all of which are very useful for exploring the codebase. In ClojureScript these features are only aware of vars that have been eval'd in the current compiler process, so be sure to eval the ns form at the top of the file to get the full effect.

For hunting down behaviors, objects and other things that don't live in vars use the searcher (Cmd/Ctrl-Shift-f). If it isn't clear how to use a given function then using the searcher to find examples will also help.

Finally, use the documentation searcher (Ctrl-Shift-d) for full-text search over the names and docstrings of all known vars. Most of Light Table doesn't have docstrings, but this is still useful for library code.

