# Light Table documentation

Everything here is version-controlled beside the code it describes, which is
the point: `docs.lighttable.com` served this role for a decade and now returns
503, and a document that can drift from the editor without anyone noticing is
how that becomes a problem rather than an inconvenience.

Two kinds of page live here, and it is worth knowing which you are reading.
**Current** pages were written or revised during the 2026 modernization and
describe the editor as it is. **Predates the modernization** means the page was
accurate when written, is still broadly right about concepts, and may name
build steps, versions or services that have moved. Where one is misleading
rather than merely old, it says so at the top.

## Using Light Table

| | |
|---|---|
| [Workflow](workflow.md) | A typical session: evaluating as you write, watches, the jump stack. *Predates the modernization.* |
| [Changing the editor while it runs](live-editing.md) | Evaluating ClojureScript, JavaScript or CSS into the running editor. The feature Light Table is named for, and the build configuration it rests on. *Current.* |
| [Commands](commands.md) | What a command is and how to add one. *Predates the modernization.* |
| [Behaviors and keymaps](behavior-and-keymap-configuration.md) | Configuring the editor by editing data rather than settings screens. *Predates the modernization.* |

## Understanding the editor

| | |
|---|---|
| [Behaviors, Objects and Tags](BOT.md) | The model the whole editor is built on. Read this one first — nothing else here makes sense without it. *Predates the modernization, and is still accurate.* |
| [The Electron layer](electron-guide.md) | Processes, the preload, and what the window may reach. *Current.* |
| [Rendering](rendering.md) | What draws the UI, the one seam another renderer plugs into, and what it must not own. *Current.* |

## Working on Light Table

| | |
|---|---|
| [Building and running](https://github.com/TrevorS/LightTable/blob/develop/README.md#building) | In the root README, because it changes with the build. |
| [Developer install](developer-install.md) | *Predates the modernization; the root README is the current answer.* |
| [For committers](for-committers.md) | Dependencies, releases, plugin metadata. *Mixed: the dependency, release and API documentation sections are current; the rest predates the modernization.* |
| [Driving it from outside](control-surface.md) | The control surface an agent or an MCP server uses: what is open, did that work, what is it waiting for. *Current.* |
| [Testing](testing.md) | The four layers, which one a given test belongs in, and what each has caught. *Current.* |
| [Style guide](style-guide.md) | ClojureScript conventions. *Predates the modernization, and still applies.* |
| [Plugins in this repository](https://github.com/TrevorS/LightTable/blob/develop/plugins/README.md) | Writing one in TypeScript or ClojureScript, and the capability manifest. *Current.* |

## Where this is going

| | |
|---|---|
| [Direction](direction.md) | What Light Table is for, what to keep, and the order to work in. Read this before proposing anything large. |
| [Hygiene](hygiene.md) | What is known to be wrong or owed, what was decided against and why, and what has been closed. Read this before proposing anything small. |

## What changed, and why

The modernization is recorded rather than summarised, because the reasoning is
worth more than the outcome — several of these exist to stop a decision being
re-litigated from scratch.

| | |
|---|---|
| [Modernization changelog](https://github.com/TrevorS/LightTable/blob/develop/CHANGELOG-MODERNIZATION.md) | The whole of it, in the order it happened. |
| [The road to context isolation](context-isolation.md) | How the window came to have no Node, what it cost, and the measurement that decided the design. |
| [The JavaScript left](javascript-remaining.md) | There is none of Light Table's own. What the four TypeScript roots are and why they are separate. |
| [LSP architecture](lsp-architecture.md) | The language server client: the four layers, how a server is declared and who declares it, and what building it settled. *Current.* |
| [Workspace edits](workspace-edits.md) | The one way Light Table changes code it is not showing, and the rename built on it. *Scouted, then built.* |
| [Evaluating ClojureScript](clojurescript-eval.md) | The two routes to a ClojureScript REPL, measured, and the two decisions to make before writing any. *Scouted, then built.* |
| [Language support](language-support.md) | Where a language server would fit, why it belongs in the editor rather than in each plugin, and the two preload gaps it turned up. |
| [Syntax highlighting](syntax-highlighting.md) | Tree-sitter highlighting: what it does, and the scouting note that first argued against it. *Current.* |
| [The editor engine](editor-engine.md) | Whether CodeMirror 6 is what we would choose if nothing were chosen, and what the migration actually costs. |

## Archived

| | |
|---|---|
| [README as of 2021](README-2021.md) | The project as it stood before this work. Kept because older discussions link to it. |

## API reference

| | |
|---|---|
| [Plugin API](api/README.md) | Every public var in the seven namespaces a plugin is written against, with arglists, docstrings and links to the source. *Generated.* |

Generated by `script/gen-api-docs.mts` from clj-kondo's analysis of the source,
and committed, so it reads on GitHub as well as here. `make docs` regenerates
it and `make check` fails when it is stale. This replaced codox, which needed a
`project.clj` that built nothing else.
