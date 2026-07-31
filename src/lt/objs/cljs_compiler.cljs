(ns lt.objs.cljs-compiler
  "A ClojureScript compiler, inside the editor it compiles for.

  **This is what makes Light Table Light Table.** Evaluating a form changes
  the running editor — not a copy of it, not one that restarts afterwards, the
  one you are typing in. [[lt.objs.clients.local]] has always been able to
  evaluate JavaScript in the window; what was missing was something to turn
  ClojureScript into JavaScript, and until now that lived on a JVM at the far
  end of an nREPL connection.

  It does not need to. Two lines in `shadow-cljs.edn` decide it:

  ```clojure
  :optimizations  :simple
  :output-wrapper false
  ```

  `:simple` renames locals and leaves properties alone, and no wrapper means
  there is no closure to hide them in — so `lt.objs.editor.__GT_val` is a live
  global in the shipped bundle, and JavaScript naming it reaches the running
  editor's own function. The editor's whole API is addressable at runtime. All
  a compiler has to do is emit the name.

  ## What it needs, and what it must not do

  A compiler resolving `lt.objs.editor/->val` needs to know that var exists,
  what arities it takes and how its name munges. That knowledge is the
  *analysis*, and shadow-cljs's `:target :bootstrap` writes it out per
  namespace — `deploy/core/lighttable/cljs-cache/ana/<ns>.transit.json`.

  What it must **not** do is load those namespaces' JavaScript. It is already
  loaded; running it again would re-run every top-level `def` and replace the
  atoms the editor is holding — the object graph, the open editors, the client
  list — with empty ones, while the old ones stayed on screen. The editor would
  still be drawn and nothing would work.

  Shadow already draws that line, which is the reason to use its loader rather
  than write one. Requiring `shadow.cljs.bootstrap.browser` makes this build a
  *bootstrap host*, and shadow appends a `set_loaded` call listing every
  namespace the bundle provides. The loader reads it, fetches analysis for
  those, and skips their code.

  ## Which namespace a form evaluates in

  `:buffer-ns` — the `ns` form at the top of the file — when the compiler knows
  it, and `cljs.user` when it does not. A namespace it does not know is
  usually someone's own plugin, and evaluating its `ns` form is what teaches
  the compiler about it. That is why `doc/workflow.md` has always said to
  evaluate the `ns` form first; it is now the literal mechanism rather than
  folklore."
  (:require [cljs.js :as cljs]
            [lt.objs.eval :as eval]
            [lt.objs.notifos :as notifos]
            [shadow.cljs.bootstrap.browser :as bootstrap]
            [shadow.cljs.bootstrap.env :as bootstrap-env]))

;; Relative to LightTable.html, which is what the loader's XHR resolves
;; against. Electron serves the window from file://, and reading a sibling
;; file over file:// works here — measured, because it does not everywhere.
(def ^:private cache-path "lighttable/cljs-cache")

(defonce ^:private compile-state (cljs/empty-state))

;; :idle -> :loading -> :ready, or :failed. The first evaluation pays for
;; loading cljs.core's analysis, which is the bulk of it; the ones after it
;; pay nothing.
(defonce ^:private status (atom :idle))
(defonce ^:private waiting (atom []))

(defn ready?
  "Has the compiler finished loading? Used to decide whether to say so."
  []
  (= :ready @status))

(defn- resolve-waiting! [err]
  (let [pending @waiting]
    (reset! waiting [])
    (doseq [cb pending] (cb err))))

(defn init!
  "Load the compiler, then call `cb` — with an error string, or nil.

  Calls that arrive while it is loading wait for the same load rather than
  starting a second one."
  [cb]
  (case @status
    :ready (cb nil)
    :loading (swap! waiting conj cb)
    (do
      (reset! status :loading)
      (swap! waiting conj cb)
      (notifos/working "Starting the ClojureScript compiler")
      (try
        (bootstrap/init compile-state
                        {:path cache-path}
                        (fn []
                          (reset! status :ready)
                          (notifos/done-working)
                          (resolve-waiting! nil)))
        (catch :default e
          (reset! status :failed)
          (notifos/done-working)
          (resolve-waiting! (str "Could not start the ClojureScript compiler: " e)))))))

(defn- known-ns?
  "Does the compiler already have analysis for `ns`?"
  [ns]
  (boolean (get-in @compile-state [:cljs.analyzer/namespaces ns :name])))

(defn- in-index?
  "Is `ns` one of the namespaces the cache was built for?"
  [ns]
  (try
    (boolean (bootstrap-env/get-ns-info ns))
    (catch :default _ false)))

(defn- ensure-ns!
  "Make `ns` available to evaluate in, and call `cb` with the one to use.

  Falls back to `cljs.user` rather than failing: a namespace the compiler has
  never heard of is the normal case for a file whose `ns` form has not been
  evaluated yet, and refusing to evaluate anything until it is would be a
  worse answer than evaluating it somewhere."
  [ns cb]
  (cond
    (nil? ns) (cb 'cljs.user)
    (known-ns? ns) (cb ns)
    (in-index? ns) (bootstrap/load-namespaces compile-state #{ns} (fn [] (cb ns)))
    :else (cb 'cljs.user)))

(defn- ->display
  "Print a value the way a REPL would.

  `:def-emits-var` makes `(def x 1)` answer with the Var rather than 1, which
  is what Clojure does and what makes `#'ns/x` the useful result. A Var is
  also callable, though, so the shared formatter reported every definition as
  `(fn ..)` — the same answer for `(def x 1)` and `(defn f [])`."
  [v]
  (if (instance? Var v)
    (str "#'" (.-sym ^js v))
    (eval/cljs-result-format v)))

(defn- error-result
  "An exception, in the shape the editor's result behaviors read."
  [^js e meta]
  (let [cause (or (.-cause e) e)]
    {:ex (str (.-message cause))
     :stack (or (.-stack cause) (str cause))
     :meta meta}))

(def ^:private ns-form?
  ;; An `ns` form emits statements — goog.provide, goog.require — and
  ;; `:context :expr` wraps what it is given in a `return`, which makes
  ;; `return goog.provide(…); goog.require(…)` and a SyntaxError naming the
  ;; namespace. The requires are established either way, so the failure was
  ;; the confusing kind: the error appeared beside the `ns` form and every
  ;; form after it worked.
  #(re-find #"^\s*\(ns\s" %))

(defn eval-form
  "Compile `code` and run it in this window. Calls `cb` with one result map.

  The result is `{:result <printed> :meta …}`, or the `:ex`/`:stack` pair the
  result behaviors render as an exception — the same shape the nREPL client
  produces, so everything downstream is shared."
  [{:keys [code meta ns path]} cb]
  (ensure-ns!
    ns
    (fn [use-ns]
      (cljs/eval-str compile-state
                     code
                     (or path "lt-eval")
                     {:eval cljs/js-eval
                      :load (partial bootstrap/load compile-state)
                      :ns use-ns
                      :context (if (ns-form? code) :statement :expr)
                      :def-emits-var true
                      ;; The window has the whole editor in it; a compiler
                      ;; warning about a var this file has not defined yet is
                      ;; noise in the console, not a result.
                      :warnings false
                      :source-map false}
                     (fn [{:keys [value error]}]
                       (if error
                         (cb (error-result error meta))
                         (cb {:result (->display value)
                              :meta meta})))))))

(defn eval-forms
  "Evaluate `forms` in order, calling `cb` once with every result.

  In order and one at a time, because they are top-level forms from one file:
  a `def` on line 3 is routinely what line 7 refers to, and evaluating them
  concurrently would make that a race. The same reason
  [[lt.plugins.clojure.nrepl]] sends its forms one at a time."
  [{:keys [forms buffer-ns path]} cb]
  (let [ns (when (and buffer-ns (seq (str buffer-ns))) (symbol (str buffer-ns)))]
    (init!
      (fn [err]
        (if err
          (cb [{:ex err :stack err :meta (:meta (first forms))}])
          (let [results (atom [])
                remaining (atom (vec forms))
                step (fn step []
                       (if-let [form (first @remaining)]
                         (do
                           (swap! remaining rest)
                           (eval-form (assoc form :ns ns :path path)
                                      (fn [res]
                                        (swap! results conj res)
                                        (step))))
                         (cb @results)))]
            (step)))))))

(defn compile-str
  "Compile `code` to JavaScript without running it. For plugin builds."
  [code ns cb]
  (init!
    (fn [err]
      (if err
        (cb {:error err})
        (ensure-ns!
          ns
          (fn [use-ns]
            (cljs/compile-str compile-state code "lt-compile"
                              {:load (partial bootstrap/load compile-state)
                               :ns use-ns
                               :warnings false}
                              (fn [{:keys [value error]}]
                                (if error
                                  (cb {:error (str error)})
                                  (cb {:js value}))))))))))

(defn describe
  "What the compiler knows, for a status line or a probe."
  []
  {:status @status
   :namespaces (count (get @compile-state :cljs.analyzer/namespaces))})
