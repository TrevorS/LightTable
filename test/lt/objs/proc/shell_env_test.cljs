(ns lt.objs.proc.shell-env-test
  "Tests for reading the environment out of the user's shell.

  Every decision here is a pure function of its arguments, so this needs no
  shell and no process: which shell to ask is [[shell-env/shell]], how to ask it
  is [[shell-env/args]] and [[shell-env/command]], and what came back is
  [[shell-env/->resolved]]. `test-electron/shell-env.test.ts` runs the real
  thing, because the one failure this had was in the quoting and a test that
  compares strings cannot see quoting go wrong.

  The stdout fixtures are shaped like real output rather than clean: a login
  shell runs somebody's rc files, and those print things."
  (:require [cljs.test :refer [deftest is testing]]
            [lt.objs.proc.shell-env :as shell-env]))

;;*********************************************************
;; Which shell
;;*********************************************************

(deftest the-users-own-shell-is-what-is-asked
  ;; The whole bug in one assertion. This used to run `/bin/sh`, which reads
  ;; `~/.profile` and `~/.bash_profile` — and a version manager is in `.zshrc`.
  (is (= "/opt/homebrew/bin/zsh"
         (shell-env/shell {"SHELL" "/opt/homebrew/bin/zsh"}))))

(deftest an-unset-shell-falls-back-rather-than-failing
  (testing "missing, which happens in a container"
    (is (= "/bin/sh" (shell-env/shell {}))))
  (testing "empty, which is not the same as missing and reads the same way"
    (is (= "/bin/sh" (shell-env/shell {"SHELL" ""})))))

(deftest a-locked-account-does-not-take-the-editor-down-with-it
  ;; `/bin/false` exits 1 and prints nothing, so every resolution would fail.
  (is (= "/bin/bash" (shell-env/shell {"SHELL" "/bin/false"}))))

;;*********************************************************
;; How to ask it
;;*********************************************************

(deftest interactive-and-login-both
  ;; `-i` is the half that was missing, and it is not optional: `-l` alone does
  ;; not read `~/.zshrc`, which is where fnm, nvm, asdf and mise hook in.
  (is (= ["-i" "-l" "-c"] (shell-env/args "/bin/zsh")))
  (is (= ["-i" "-l" "-c"] (shell-env/args "/opt/homebrew/bin/bash"))))

(deftest csh-is-asked-differently-because-it-refuses-otherwise
  (is (= ["-ic"] (shell-env/args "/bin/csh")))
  (is (= ["-ic"] (shell-env/args "/usr/local/bin/tcsh")))
  (testing "by its name, not by what its path contains"
    (is (= ["-i" "-l" "-c"] (shell-env/args "/opt/tcsh-tools/bin/zsh")))))

;;*********************************************************
;; The command
;;*********************************************************

(deftest the-marker-is-quoted-so-that-a-shell-cannot-eat-it
  ;; This is the regression. Written with single quotes around the marker, the
  ;; shell — already inside a single-quoted argument — closed the string, dropped
  ;; the quotes, and node evaluated the marker as a bare identifier. It did not
  ;; fail loudly; it failed as an editor with no PATH.
  ;;
  ;; So: the JS string literals must be double-quoted, and the JS as a whole
  ;; single-quoted, with no single quote anywhere inside it.
  (let [command (shell-env/command "/Applications/LightTable.app/Contents/MacOS/Electron" "__M__")]
    (is (= (str "'/Applications/LightTable.app/Contents/MacOS/Electron' "
                "-p '\"__M__\" + JSON.stringify(process.env) + \"__M__\"'")
           command))
    (testing "the JS payload holds no single quote, which would end the argument"
      (let [js (second (re-find #"-p '(.*)'$" command))]
        (is (some? js))
        (is (not (re-find #"'" js)))))))

(deftest every-resolution-gets-its-own-marker
  ;; A constant would be a string somebody's $PROMPT could print.
  (is (not= (shell-env/marker) (shell-env/marker)))
  (is (re-find #"^__LT_ENV_[a-z0-9]+__$" (shell-env/marker))))

;;*********************************************************
;; What comes back
;;*********************************************************

(defn- payload
  "Stdout from a shell that printed `env` between markers, with `noise` first."
  [noise env]
  (str noise "__M__" (.stringify js/JSON (clj->js env)) "__M__"))

(deftest the-environment-is-read-from-between-the-markers
  (is (= {"PATH" "/usr/bin" "FNM_DIR" "/home/x/.fnm"}
         (shell-env/->resolved (payload "" {"PATH" "/usr/bin" "FNM_DIR" "/home/x/.fnm"})
                               "__M__"))))

(deftest a-shell-greeting-is-not-configuration
  ;; Real output. fnm prints this, and direnv, and a motd, and a banner.
  (is (= {"PATH" "/usr/bin"}
         (shell-env/->resolved (payload "Using Node v24.19.0\nzsh: no job control\n"
                                        {"PATH" "/usr/bin"})
                               "__M__"))))

(deftest the-flags-that-made-it-print-do-not-come-back
  ;; The footgun. `ELECTRON_RUN_AS_NODE` is set so the child prints the
  ;; environment, so the child reports it as part of that environment —
  ;; and importing it back would make every process the editor spawns
  ;; afterwards run as a bare node, which breaks the background worker.
  (let [resolved (shell-env/->resolved
                  (payload "" {"PATH" "/usr/bin"
                               "ELECTRON_RUN_AS_NODE" "1"
                               "ELECTRON_NO_ATTACH_CONSOLE" "1"})
                  "__M__")]
    (is (= {"PATH" "/usr/bin"} resolved))))

(deftest nothing-usable-is-nil-rather-than-a-guess
  (testing "no marker at all: the shell died before it ran anything"
    (is (nil? (shell-env/->resolved "zsh: command not found: Electron\n" "__M__"))))
  (testing "nothing at all"
    (is (nil? (shell-env/->resolved "" "__M__")))
    (is (nil? (shell-env/->resolved nil "__M__"))))
  (testing "a marker but no object, which is a shell that died mid-print"
    (is (nil? (shell-env/->resolved "__M__killed" "__M__"))))
  (testing "between the markers but not JSON"
    (is (nil? (shell-env/->resolved "__M__{not json}__M__" "__M__"))))
  (testing "an empty environment, which no real shell has"
    (is (nil? (shell-env/->resolved (payload "" {}) "__M__")))))

(deftest a-value-with-a-newline-in-it-survives
  ;; Why this asks for JSON rather than parsing `env`: `env` output cannot be
  ;; split into variables when a value contains a newline.
  (is (= {"SCRIPT" "line one\nline two"}
         (shell-env/->resolved (payload "" {"SCRIPT" "line one\nline two"}) "__M__"))))

;;*********************************************************
;; The environment it is asked in
;;*********************************************************

(deftest the-resolving-shell-runs-electron-as-node
  (is (= {"PATH" "/usr/bin"
          "ELECTRON_RUN_AS_NODE" "1"
          "ELECTRON_NO_ATTACH_CONSOLE" "1"}
         (shell-env/spawn-env {"PATH" "/usr/bin"}))))
