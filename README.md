# Light Table

Light Table is a code editor that connects you to your creation with instant
feedback. It is highly customizable and can display anything a
[Chromium browser](https://www.chromium.org/) can.

> **This is a development branch.** It is not as stable as `master`, and it is
> undergoing modernization: the runtime, the dependencies and the build have all
> moved. What changed and why is recorded in
> [CHANGELOG-MODERNIZATION.md](CHANGELOG-MODERNIZATION.md).
>
> The previous README, which describes the project as it stood in 2021, is kept
> at [doc/README-2021.md](doc/README-2021.md). Several of the services it linked
> to no longer exist.

## Current state

| | |
|---|---|
| Runtime | Electron 43 — Chromium 150, Node 24 |
| Language | ClojureScript 1.12, Clojure 1.12 |
| Build | [shadow-cljs](https://github.com/thheller/shadow-cljs), plus `tsc` for the main process |
| Toolchain | JDK 25 (JDK 21 also works), Node 24 |

## Building

You need a JDK, Node, and [Leiningen](https://leiningen.org) only if you want to
regenerate the API docs.

```sh
script/build.sh            # fetches dependencies and plugins, builds, packages
script/build.sh --release  # the above, plus a release archive
```

To rebuild just the ClojureScript after a source change:

```sh
npm run build:cljs
```

`app` is the window bundle; `worker` is the background thread that runs
searching, file walking and behavior parsing off the main thread. Both are
defined in [shadow-cljs.edn](shadow-cljs.edn). The default user plugin is a
module of `app`, so it compiles against the editor rather than shipping as a
checked-in artifact; the script puts it where the plugin loader looks.

The main process and the preload script are TypeScript, in `src-electron/`:

```sh
npm run build:main
```

Plugins that live in this repository are built separately:

```sh
npm run build:plugins
```

The window does not use Electron's modules directly. Everything it can ask the
desktop for is a named capability exposed by the preload script, and
`lt.util.bridge` is the only namespace that reaches it — see
[doc/electron-guide.md](doc/electron-guide.md), and
[doc/context-isolation.md](doc/context-isolation.md) for what remains before the
window can be isolated outright.

## Testing

```sh
npx shadow-cljs compile test && node target/test.js   # unit tests
script/smoke-test.sh                                  # boots the app and checks it
```

The smoke test matters more than its size suggests. Almost nothing in Light
Table can be loaded in isolation — `lt.object`, `lt.objs.files` and
`lt.objs.menu` all reach for Electron or the DOM while loading — and the
failures this codebase actually produces are load-time and cross-process ones: a
namespace throwing as it loads, an Electron API that no longer exists, an ipc
channel with nobody listening, a worker that cannot reply. None of those are
reachable from unit tests, so the smoke test drives the assembled application
instead.

Both run in [CI](.github/workflows/build.yml) on every push.

To ask the running editor a question rather than boot one per question:

```sh
script/lt-repl.sh start
script/lt-repl.sh cljs "files/cwd"
script/lt-repl.sh eval "cljs.core.count(cljs.core.deref(lt.object.behaviors))"
script/lt-repl.sh stop
```

It attaches over the DevTools protocol to the real application, evaluates with a
timeout so a hang reports as one, and munges ClojureScript names — `files/cwd`
reaches `lt.objs.files.cwd` — outside string literals.

## Documentation

* [docs.lighttable.com](http://docs.lighttable.com/) — user documentation and tutorials
* [BOT architecture](doc/BOT.md) — behaviors, objects and tags, the model everything here is built on
* [Light Table's API docs](http://lighttable.github.io/LightTable/api/index.html) — what plugin authors can reach
* [Workflow](doc/workflow.md) — a typical session
* The [community wiki](https://github.com/LightTable/LightTable/wiki), including the
  [FAQ](https://github.com/LightTable/LightTable/wiki/FAQ) and guides for
  [vim](https://github.com/LightTable/LightTable/wiki/For-Vim-Users) and
  [emacs](https://github.com/LightTable/LightTable/wiki/For-Emacs-Users) users

To regenerate the API docs:

```sh
lein codox
```

`project.clj` exists only for that. It builds nothing.

## Plugins

Light Table's plugin system can extend or replace almost any part of the editor,
and the community has published [over a hundred](https://github.com/LightTable/plugin-metadata)
— language eval support, domain-specific IDEs, and more. To write one, see
[Write a Plugin](http://docs.lighttable.com/#write-a-plugin), and for an example
in ClojureScript see
[LightTable-Declassifier](https://github.com/LightTable/LightTable-Declassifier).

Published plugins ship as precompiled JavaScript and are not rebuilt when Light
Table changes, so a renamed namespace in the editor surfaces as an error in a
user's session rather than as a failing build. `lt.compat` keeps published
plugins working across such renames; see the modernization changelog for what is
currently shimmed and what has broken.

Plugins in [`plugins/`](plugins/README.md) do not have that problem: they are
built from source against the editor, in TypeScript or ClojureScript, so a
rename is a compile error. That directory also documents the capability
manifest — what a plugin declares it needs — and the three levels of
enforcement it is a path towards.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) and
[For Developers](https://github.com/LightTable/LightTable/wiki/For-Developers).
Light Table is written in [ClojureScript](https://clojurescript.org); if it is
new to you, [David Nolen's tutorial](https://github.com/swannodette/lt-cljs-tutorial)
is a good start.

## License

All files in this project are under the [MIT license](LICENSE.md) unless
otherwise stated in the file or by a dependency's license file. One directory is
not: `src/singultus` is vendored third-party code under the EPL, and
[its README](src/singultus/README.md) explains why.

## Credits

Thanks to all our [contributors](https://github.com/LightTable/LightTable/graphs/contributors),
to Kodowa for everything they did for Light Table, and to Cognitect for
supporting one of the core team members.
