# The JavaScript left, and what to do with it

**Light Table ships no hand-written JavaScript any more.** The main process, the
preload, the browser injection, the fuzzy matcher, drag-and-drop, the two
CodeMirror addons, the behavior parser, the directory walker and the browser
client are all TypeScript. What is left is vendored code and build tooling.

## Where the TypeScript lives

Four source roots, one per place code runs, because each has a different set of
globals and typing them together would mean typing none of them.

| root | runs in | emitted to | tsconfig |
|---|---|---|---|
| `src-electron/` | the main process and the preload | `deploy/core/` | `tsconfig.json` |
| `src-window/` | the editor window | `src-gen/lt/window/`, bundled | `tsconfig.window.json` |
| `src-worker/` | the worker thread, a forked node process | `src-gen/lt/background/`, bundled | `tsconfig.worker.json` |
| `src-browser/` | a page Light Table has connected to | `deploy/core/lighttable/`, served | `tsconfig.browser.json` |

The differences are not incidental:

- `src-window` has DOM and `"types": []`. A window namespace that wants
  something from Node goes through the bridge, so Node types being absent is
  the point rather than an oversight.
- `src-worker` is the reverse: `"types": ["node"]` and no DOM.
- `src-browser` is the only one that downlevels, to ES2017, because the page
  loading it is someone else's and may be old. It also compiles with
  `"module": "none"` — the output is served to a script tag, so adding an
  `import` has to be a compile error rather than a file that silently stops
  working in a browser.

`src-window` and `src-worker` emit onto the ClojureScript source path, which is
what lets shadow-cljs bundle them: a namespace at `lt/background/` can require
`"./walkdir.js"` and shadow resolves it against the same classpath directory.
The worker build therefore runs `:js-provider :shadow` with `fs` and `path` kept
as real requires, since it is a node process and those are node's.

## Leave these

| file | lines | why |
|---|---|---|
| `deploy/core/lighttable/util/keyevents.js` | 1,097 | Mousetrap 1.6.5 with four marked deviations. Typing it would destroy the only thing keeping it maintainable — a mechanical diff against upstream. See `util/VENDORED.md`. |

## Tooling, not shipped code

`script/*.js` — the smoke test, the REPL, the screenshotter, the plugin placer,
the CodeMirror require generator. Types would have caught a couple of the probe
mistakes made while writing them, but these run under node with no build step,
and adding one to the tooling that verifies the build is a trade worth thinking
about rather than assuming. They are linted.

## What the ports turned up

Porting is supposed to be behaviour-preserving, so a difference is worth
recording rather than quietly keeping.

**`walkdir2.js` consumed its argument.** `walk(dirs, opts)` assigned the
caller's array straight to its work queue and then drained it, so `dirs` came
back empty and a second call with the same array found nothing. The port copies.
Nothing hit this — the only caller passes a single path — but it was real, and
it is the kind of thing a differential test finds and a reading does not.

**`behaviorsParser.js` labels a nested atom as a keyword.** Preserved. It is
almost certainly a copy-paste slip in the original, but behavior files are read
back by position and value rather than by that label, so changing it would be a
silent change to how they render for no gain.

Both were checked by running the old implementation and the new one side by
side: every `.behaviors` and `.keymap` file in the tree plus deliberately broken
input, and five directory walks. 18/18 and 5/5 identical once the mutation
above is accounted for.
