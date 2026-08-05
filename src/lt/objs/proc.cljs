(ns lt.objs.proc
  "Provide fns to spawn processes and manage them. Used by language plugins"
  (:require [lt.object :as object]
            [lt.objs.files :as files]
            [lt.objs.platform :as platform]
            [lt.objs.app :as app]
            [lt.objs.notifos :as notifos]
            [lt.objs.proc.shell-env :as shell-env]
            [clojure.string :as string]
            [lt.util.bridge :as bridge])
  (:require-macros [lt.macros :refer [behavior]]))

(def custom-env (atom {}))

(def procs (atom #{}))

(defn add! [p]
  (swap! procs conj p))

(defn rem! [p]
  (swap! procs disj p))

(defn kill [^js p]
  (.kill p))

(defn kill-all [& [ps]]
  (doseq [p (or ps @procs)]
    (kill p)))

(defn parse-commands [com]
  (let [pipes (string/split com "|")]
    (for [p pipes
          :let [args (filter (complement empty?) (re-seq #"(?:(?:\\\s)|[^\s\"'])+|\"[^\"]*\"|'[^']*'" p))]]
      {:command (first args)
       :args (rest args)})))

(defn merge-env [env]
  (let [base (.env bridge/host)]
    (if-not env
      base
      (clj->js (merge (into {} (for [k (js/Object.keys base)]
                                 [k (aget base k)]))
                      env
                      @custom-env)))))

(defn simple-spawn* [obj {:keys [command args]} cwd? env]
  (let [^js proc (.spawn bridge/processes command
                         (if (seq args) (clj->js args) #js [])
                         (js-obj "cwd" cwd?
                                 "env" (merge-env env)))]
    (add! proc)
    (.onExit proc (partial rem! proc))
    (.onError proc #(when @obj
                         (println (str %) (> (.indexOf (str %) "ENOENT") -1))
                         (if (> (.indexOf (str %) "ENOENT") -1)
                           (do
                             (object/raise obj :proc.error (str "Could not find command: " command))
                             (object/raise obj :proc.exit)
                             (.kill proc))
                           (object/raise obj :proc.error %))))
    (.onStderr proc #(if-not @obj
                       (println "ERROR running: " command)
                       (object/raise obj :proc.error %)))
    (.onStdout proc #(when @obj (object/raise obj :proc.out %)))
    (.onExit proc #(when @obj (object/raise obj :proc.exit %)))
    proc))

(defn exec [com]
  (let [{:keys [command obj cwd env args] :as this} com
        commands (if-not args
                   (parse-commands command)
                   [this])
        ;;spawn and store them
        procs (doall(for [c commands]
                      (simple-spawn* obj c cwd env)))]
    (object/merge! obj {:procs procs})
    nil))

(behavior ::kill-procs-on-close
          :triggers #{:closed}
          :reaction (fn [this]
                      (kill-all)))

(object/add-behavior! app/app ::kill-procs-on-close)

;;*******************************************************
;; Testing
;;*******************************************************

(behavior ::print-all
          :triggers #{:proc.error :proc.out :proc.exit}
          :reaction (fn [this data]
                      (println "PROC: " data)))

(object/object* ::test-printer
                :triggers []
                :behaviors [::print-all]
                :init (fn []))

(def printer (object/create ::test-printer))

;;*********************************************************
;; Path setting
;;********************************************************

;; Getting the environment a terminal would have.
;;
;; VS Code's `shellEnv.ts` arrangement, read rather than remembered, and the
;; differences from it are noted where they are. Everything that *decides*
;; anything is in [[lt.objs.proc.shell-env]], because it is all a pure function
;; of its arguments and the bug that made this exist was in one of them. What is
;; left here is the spawning.

(defn- host-env
  "The host's environment as a map.

  Read rather than merged through [[merge-env]], which would also fold in
  `@custom-env`. Those are the user's `:global-env` settings, and letting them
  shape the shell whose environment is being discovered would make the answer
  depend on the question."
  []
  (let [base (.env bridge/host)]
    (into {} (for [k (js-keys base)] [k (aget base k)]))))

(defonce ^:private env-gate
  ;; `:waiting` are thunks registered by [[on-env-ready]] before the environment
  ;; arrived. Emptied when the gate opens, so nothing is held after that.
  (atom {:done? false :waiting []}))

(defn- open-gate!
  "Let everything waiting on the environment go, once."
  []
  (when-not (:done? @env-gate)
    (let [{:keys [waiting]} @env-gate]
      (reset! env-gate {:done? true :waiting []})
      (doseq [f waiting] (f)))))

(defn on-env-ready
  "Run `f` once the shell environment has been imported — or now, if it already
  has or was never going to be.

  Resolving the environment means starting a shell, so it is not instant, and
  everything that looks something up on `PATH` at startup is in a race with it.
  Session restore reopens the files that were open, an editor asks for a language
  server as soon as it knows its language, and on this machine the shell takes
  about 170ms — but a `.zshrc` with nvm, conda and direnv in it takes seconds.
  Losing that race is not a delay, it is the whole session: the server is
  recorded as not installed and nothing asks again. VS Code has the same ordering
  problem and answers it the same way, by awaiting the resolved environment
  before it launches an extension host.

  **The deadline is the point, not the waiting.** Every waiter is released by
  whichever comes first: the environment arriving, the resolution failing, or a
  bound slightly past the one the shell itself gets. A gate that can only be
  opened by something going right would be a worse bug than the one this fixes —
  no language servers at all, silently, and this file has twice had the behavior
  that opens it left unattached by a rename."
  [f]
  (if (:done? @env-gate)
    (f)
    (let [ran (atom false)
          once #(when-not @ran (reset! ran true) (f))]
      (swap! env-gate update :waiting conj once)
      (js/setTimeout once (+ shell-env/timeout 1000)))))

(behavior ::resolve-shell-env
          :triggers #{:init}
          :desc "App: take the environment from the shell a terminal would give you"
          :reaction (fn [_app]
                      ;; Not mac-only. A Linux desktop entry does not read
                      ;; `.zshrc` either, and the same version manager is in it.
                      ;; Windows is skipped, as VS Code skips it: there is no
                      ;; login shell to ask.
                      ;;
                      ;; `LTCLI` means the editor was started from a terminal, so
                      ;; the environment it wants is the one it already has.
                      (if-not (and (or (platform/mac?) (platform/linux?))
                                   (not (aget (.env bridge/host) "LTCLI")))
                        (open-gate!)
                        (let [env (host-env)
                              marker (shell-env/marker)
                              shell (shell-env/shell env)
                              args (shell-env/args shell)
                              command (shell-env/command (.execPath bridge/host) marker)
                              pieces (array)
                              errs (array)
                              done (atom false)
                              ^js proc (.spawn bridge/processes shell
                                               (clj->js (conj args command))
                                               (js-obj "env" (clj->js (shell-env/spawn-env env))))
                              ;; The bound is on the shell, not on `exec`. An
                              ;; interactive shell reads somebody's rc files,
                              ;; and those can prompt, wait on a network mount,
                              ;; or block on a keychain — with no terminal to
                              ;; show it in. VS Code cancels the same way.
                              timer (js/setTimeout #(when-not @done (.kill proc)) shell-env/timeout)
                              fail (fn [why]
                                     ;; Opened on the way out. Whatever is
                                     ;; waiting is better off trying with the
                                     ;; environment there is than not running.
                                     (open-gate!)
                                     (notifos/set-msg!
                                      (str "Could not read your shell environment, so language "
                                           "servers and REPLs may not be found. See the console.")
                                      {:class "error"})
                                     (.error js/console
                                             (str "Shell environment resolution failed: " why
                                                  "\n  shell: " shell " " (string/join " " args)
                                                  "\n  command: " command
                                                  "\n  stderr: " (.join errs "")
                                                  "\n  stdout: " (.join pieces ""))))
                              ;; The arrival of the payload is what finishes
                              ;; this, rather than the shell exiting, and that is
                              ;; deliberate two ways over.
                              ;;
                              ;; It is right: the exit *status* means nothing
                              ;; here, because an interactive shell with no tty
                              ;; warns about job control and can exit non-zero
                              ;; having printed a perfectly good environment. VS
                              ;; Code rejects on a non-zero code; this does not,
                              ;; because the environment is either in the output
                              ;; or it is not, and that is checkable.
                              ;;
                              ;; It also avoids a race the bridge cannot express.
                              ;; VS Code reads its output on `close`, which is
                              ;; "gone *and* drained"; `ProcessHandle` offers
                              ;; `exit`, which is only "gone" and can land before
                              ;; the last chunk of stdout. Waiting for the match
                              ;; instead of for the exit makes the ordering
                              ;; irrelevant, and means a shell whose rc file
                              ;; hangs after printing still resolves.
                              try-finish!
                              (fn []
                                (when-not @done
                                  (when-let [resolved (shell-env/->resolved (.join pieces "") marker)]
                                    (reset! done true)
                                    (js/clearTimeout timer)
                                    (doseq [[k v] resolved]
                                      (.setEnv bridge/host k v))
                                    (open-gate!)
                                    ;; Nothing more is wanted from it, and a
                                    ;; login shell that is still busy would
                                    ;; otherwise outlive the answer.
                                    (.kill proc)
                                    true)))]
                          (.onStdout proc (fn [chunk] (.push pieces chunk) (try-finish!)))
                          (.onStderr proc #(.push errs %))
                          (.onError proc #(when-not @done
                                            (reset! done true)
                                            (js/clearTimeout timer)
                                            (fail (str %))))
                          (.onExit proc
                                   (fn [code]
                                     (when-not (try-finish!)
                                       (when-not @done
                                         (reset! done true)
                                         (js/clearTimeout timer)
                                         (fail (str "no environment in its output (exit " code ")"))))))))))

(behavior ::global-path
          :triggers #{:object.instant}
          :desc "App: set global PATH for processes"
          :type :user
          :params [{:label "path"}]
          :exclusive true
          :reaction (fn [app path]
                      (.setEnv bridge/host "PATH" path)))

(behavior ::global-env
          :triggers #{:object.instant}
          :desc "App: add to the global ENV for processes"
          :params [{:label "env map"}]
          :type :user
          :exclusive true
          :reaction (fn [app kvs]
                      (reset! custom-env kvs)))


(defn var-caps [vs]
  (if (platform/win?)
    (str "echo " (apply str (map #(str "%" % "%;") vs)))
    (str "echo \"" (apply str (map #(str "$" % ";") vs)) "\"")))


(defn capture [cmd vars cb]
  (.exec bridge/processes (str cmd " && " (var-caps vars))
         (fn [err out serr]
           (let [vs (zipmap vars (string/split out ";"))]
             (cb vs)))))
