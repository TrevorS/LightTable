# The JavaScript left, and what to do with it

Light Table's own JavaScript is nearly gone: the main process, the preload, the
browser injection, the fuzzy matcher, drag-and-drop and the two CodeMirror
additions are all TypeScript. What remains, with what it is and whether it
should move.

## Port these three

They are the whole of `npm run lint:js`'s 18 remaining warnings, and every one
is the same thing: `var` declared at the top of a function and assigned later,
which reads as redeclaration and as assignments nothing consumes. A TypeScript
port fixes all of them by construction rather than by patching the idiom, so
they are worth doing together and not before.

| file | lines | what it is |
|---|---|---|
| `background/behaviorsParser.js` | 121 | Parses `.behaviors` files in the worker. Pure string handling, no I/O — the easiest of the three, and the one most worth types. |
| `background/walkdir2.js` | 74 | Directory walking for the worker. Node-only, so it types against `@types/node` rather than the DOM. |
| `ws.js` | 175 | The socket.io client shim, served to *connecting browsers* rather than run in Light Table. It types against a foreign page's globals, so `io` and `cljs` need declaring. |

The first two run in the worker, which is a node process — so unlike everything
ported so far they want `"types": ["node"]` rather than `"types": []`, and a
tsconfig of their own.

## Leave these

| file | lines | why |
|---|---|---|
| `util/keyevents.js` | 1,097 | Mousetrap 1.6.5 with four marked deviations. Typing it would destroy the only thing keeping it maintainable — a mechanical diff against upstream. See `util/VENDORED.md`. |
| `util/fuzzy.js` | — | Gone: ported to `src-window/fuzzy.ts`. |
| `ui/dragdrop.js` | — | Gone: ported to `src-window/dragdrop.ts`. |
| `codemirror_addons/*` | — | Gone: `overlay.js` was a stale CodeMirror 4 copy and is now required from npm; the other two are `src-window/cm-search.ts` and `cm-hint.ts`. |

## Tooling, not shipped code

`script/*.js` — the smoke test, the REPL, the plugin placer, the CodeMirror
require generator. Types would have caught a couple of the probe mistakes made
while writing them, but these run under node with no build step, and adding one
to the tooling that verifies the build is a trade worth thinking about rather
than assuming.
