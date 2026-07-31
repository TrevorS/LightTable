# Driving Light Table from outside

`lt.objs.control` is how something that is not a person operates the editor:
an agent, a script, an MCP server. Data in, data out.

```
script/lt-repl.sh clj '(count (object/by-tag :editor))'
script/lt-repl.sh state
script/lt-repl.sh open ~/src/thing.rs
script/lt-repl.sh prompts
script/lt-repl.sh answer 62 cancel
```

## Why it exists

The editor could already be driven — that is how most of this repository was
verified. But only by evaluating JavaScript through the DevTools protocol and
reading the answer out of the DOM: the statusbar's text for whether an eval
worked, `li.error` for whether it failed, `.inline-result` for what it
produced. That reads the rendering rather than the fact, and it breaks when
the markup changes. It did, once, because a result carries its text twice —
a truncated span beside the full one.

Three questions an agent has and a person does not:

| | |
|---|---|
| What is open? | `snapshot` — editors with ids, clients, workspace, prompts |
| Did that work? | job statuses, and `errors` |
| What is it waiting for? | `prompts`, and `answer` |

## Shaped for MCP

Meant to be wrapped in an MCP server, so it borrows that protocol's shapes
rather than inventing its own. Three things from the
[2026-07-28 revision](https://modelcontextprotocol.io/specification/2026-07-28/changelog)
decided the design.

**State lives in handles.** MCP removed protocol-level sessions; cross-call
state is carried by server-minted handles passed as ordinary arguments. So
nothing here means "the current editor" — `snapshot` gives every editor an id
and every call takes one back. `pool/last-active` is a fine idea for a
keyboard and a bad one for a caller that cannot see the screen.

**Slow things return a handle.** Evaluation, a language-server request, a
project search: all longer than a call should block for. They return a job,
and `job` is polled until terminal. The statuses are MCP's exactly:

```
working · input_required · completed · failed · cancelled
```

so wrapping this in the [Tasks extension](https://modelcontextprotocol.io/extensions/tasks/overview)
is an adapter rather than a translation.

**A question is a result.** MCP replaced server-initiated requests with
`InputRequiredResult`: a call returns what it needs and the caller retries
with answers. Light Table's modals are that shape already — *which of these
code actions*, *you have unsaved changes* — so a job still working when a
newer prompt appears reports `input_required` and carries the choices.
`answer` is the retry.

Only prompts newer than the job. Otherwise every working job reports itself
blocked by a dialog that was already on screen and has nothing to do with it.

## Operations

| op | argument | returns |
|---|---|---|
| `snapshot` | — | editors, clients, workspace, prompts, error count |
| `editors` / `clients` / `prompts` / `errors` | — | just that part |
| `clear-errors` | — | forget the errors so far |
| `eval` | `{source}` | a job; result is the value, `pr-str`'d |
| `open` | `{path}` | a job; result is the editor |
| `value` | `{editor}` | the buffer's text |
| `job` | `{job}` | current state |
| `cancel` | `{job}` | cooperative, as in MCP |
| `answer` | `{prompt, choice}` | `choice` nil dismisses |

An unknown op is answered with the list rather than thrown — a caller that
guessed wrong should be told what there is.

## Evaluating ClojureScript

The reason this can exist now: the window has a ClojureScript compiler in it
(see [live-editing.md](live-editing.md)), so a caller sends source and gets a
value:

```clojure
(count (object/by-tag :editor))
(files/basename "/a/b/c.txt")
```

rather than `cljs.core.count(lt.object.by_tag(...))` with the munged names
spelled by hand. Expressions run in a prepared namespace with `object`,
`cmd`, `editor`, `pool`, `files`, `tabs`, `workspace`, `clients` and `string`
already aliased — without it every expression lands in `cljs.user`, where
nothing is required.

## Errors

`lt.object` catches what a behavior reaction throws so one bad behavior cannot
take the editor down. The cost is that a behavior which threw and one which
decided not to act look identical. `lt.object/errors` keeps the last 50 as
values, and `errors` reads them.

The pattern that works: `clear-errors`, do the thing, read `errors`. It is the
only way to attribute one.

## What this is not

Not a security boundary. Anything that can call this can already evaluate
ClojureScript in the window, which is the whole of the editor. It exists to
make driving Light Table legible, not to make it safe.
