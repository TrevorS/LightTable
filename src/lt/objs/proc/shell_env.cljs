(ns lt.objs.proc.shell-env
  "Working out the environment a terminal would have, as pure functions.

  An application launched from Finder or a desktop entry inherits almost nothing
  — not the `PATH` a shell would build, so not a version manager's shims, so no
  `node`. Language servers then fail two different ways, which is why it does not
  look like one bug: one installed globally is not found, while one in the
  project's own `node_modules/.bin` *is* found and still cannot run, because it is
  a script whose first line is `#!/usr/bin/env node`.

  The fix is VS Code's, read from `shellEnv.ts` rather than remembered: start the
  user's own shell, login *and* interactive, and ask it. What is left over is
  three decisions — which shell, how to ask it, and what came back — and they are
  here rather than in [[lt.objs.proc]] because every one of them is a pure
  function of its arguments, and because the bug that made this file exist was in
  one of them. `lt.background.rg` is split the same way and for the same reason.

  [[lt.objs.proc/resolve-shell-env]] is what spawns, and the only impure part.

  `clojure.string` and nothing else, deliberately. `lt.objs.files/basename` would
  be the obvious way to name a shell, and requiring it pulls in `lt.object`, and
  through it `lt.util.dom` and a bridge call at load time — so the tests for
  these functions would need a DOM and a preload to run at all. Being testable
  under plain node is the reason this namespace exists, so it keeps its own
  four-line [[basename]] instead."
  (:require [clojure.string :as string]))

(def timeout
  "How long the shell gets. VS Code's default, and for the same reason: an
  interactive shell reads rc files that can prompt, wait on a network mount, or
  block on a keychain, and none of that may become the editor waiting for ever."
  10000)

(defn marker
  "A fresh delimiter for one resolution.

  Random rather than a constant, which is VS Code's choice and worth copying: the
  payload is JSON holding every environment variable, so a fixed marker is a
  string somebody's `$PROMPT` could contain."
  []
  (str "__LT_ENV_" (.toString (js/Math.floor (* (js/Math.random) 1e12)) 36) "__"))

(defn shell
  "The shell a terminal would start, given an environment.

  `$SHELL`, not `/bin/sh` — which is what this used to source `~/.profile` with,
  a bash-era guess that has been wrong on macOS since Catalina made zsh the
  default, and wrong in a way that is hard to see. macOS puts `$SHELL` into a GUI
  application's environment from the account record, so it is there even when the
  app was launched from the dock and almost nothing else is.

  Both fallbacks are VS Code's, and both are for real systems rather than
  imagined ones: `$SHELL` can be unset in a container, and a locked account has
  it set to `/bin/false`, which would make every resolution fail. VS Code reads
  `/etc/passwd` via `os.userInfo()` between those two; that is a main process
  call the window has no capability for, so this goes straight to the last
  resort."
  [env]
  (let [s (get env "SHELL")]
    (cond
      (string/blank? s) "/bin/sh"
      (= "/bin/false" s) "/bin/bash"
      :else s)))

(defn- basename
  "The last segment of a path. `/bin/zsh` is `zsh`, and `zsh` is `zsh`."
  [path]
  (last (string/split path #"/")))

(defn args
  "How to ask `sh` to run one command, login *and* interactive.

  Interactive is not optional, and it is the half that was missing. A version
  manager — fnm, nvm, asdf, mise — hooks itself into `~/.zshrc`, which a login
  shell does not read: `-l` alone gets `/etc/zprofile` and `~/.zprofile` and
  stops. So `-i` as well, even with no terminal here, because that is what
  decides whether `node` exists.

  csh and tcsh get `-i` without `-l`, which is VS Code's special case: they
  accept `-l` only as the very first argument and reject it in this position. The
  shells VS Code also handles — PowerShell, nushell, xonsh — are deliberately not
  handled. Each needs its own quoting, none is a login shell on macOS or Linux,
  and guessing wrong for them would break a shell that would otherwise have
  worked as a POSIX one."
  [sh]
  (if (#{"csh" "tcsh"} (basename sh))
    ["-ic"]
    ["-i" "-l" "-c"]))

(defn command
  "A command for the user's shell that prints its environment as JSON.

  **The whole environment, not just `PATH`**, which is the other thing VS Code
  does and this did not. `JAVA_HOME` is how clojure-lsp finds a JVM, `FNM_DIR`
  and `ASDF_DIR` are how a version manager finds its own installs, and a proxy
  lives there too. `PATH` was the reported symptom rather than the boundary of
  the problem.

  **Electron's own binary does the printing**, under `ELECTRON_RUN_AS_NODE` —
  `exec-path` is that binary. Not elegance: an environment serialised by `env`
  cannot be parsed reliably when a value contains a newline, and every other way
  of producing JSON needs a `node` on `PATH`, which is the thing that is missing.
  The editor is already a node.

  **The quoting has exactly two levels, and that is load-bearing.** It is why
  [[lt.objs.proc/resolve-shell-env]] spawns the shell rather than calling
  `processes/exec`, which runs its argument through `/bin/sh -c` and so adds a
  third. With three, the marker cannot be quoted at all: single quotes for the JS
  string end the shell's own single-quoted argument, and double quotes end the
  level above that. Written that way it did not fail loudly — the shell stripped
  the quotes and node evaluated the marker as a bare identifier, so what came
  back was a `ReferenceError`, and an editor that silently had no `PATH`."
  [exec-path mark]
  (str "'" exec-path "' -p '\"" mark "\" + JSON.stringify(process.env) + \"" mark "\"'"))

(defn spawn-env
  "The environment to run the resolving shell in: `base`, plus the two flags that
  make Electron behave as node and keep it from attaching to a console.

  Both are VS Code's. Neither may survive into what comes back — see
  [[->resolved]]."
  [base]
  (assoc base
         "ELECTRON_RUN_AS_NODE" "1"
         "ELECTRON_NO_ATTACH_CONSOLE" "1"))

(defn ->resolved
  "The environment in `out`, as a map, or nil when it is not in there.

  Between the markers rather than all of stdout, because a login shell runs
  somebody's rc files and those print things: a version manager's notice,
  `direnv`, a banner, `Using Node v24.19.0`. Taking all of stdout would be
  reading a shell greeting as configuration. It has to look like an object as
  well as sit between the markers, so that a shell which printed the marker and
  then died is a failure rather than a parse of whatever followed it.

  `ELECTRON_RUN_AS_NODE` is dropped, and this is the footgun rather than a
  detail: it is set in order to make the child print the environment, so the
  child reports it as part of that environment. Importing it back would mean
  every process the editor spawns afterwards runs as a bare node instead of as
  Electron — which would break the background worker, whose whole arrangement is
  that flag being set deliberately and only for it. VS Code puts back whatever
  value it had beforehand; here there is never one, because the window is not
  running as node."
  [out mark]
  (when-not (string/blank? out)
    (when-let [[_ json] (re-find (re-pattern (str mark "(\\{.*\\})" mark)) out)]
      (try
        (let [parsed (js->clj (.parse js/JSON json))]
          (when (seq parsed)
            (dissoc parsed "ELECTRON_RUN_AS_NODE" "ELECTRON_NO_ATTACH_CONSOLE")))
        (catch :default _ nil)))))
