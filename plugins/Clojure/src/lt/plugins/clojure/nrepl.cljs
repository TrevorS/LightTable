(ns lt.plugins.clojure.nrepl
  "Light Table's side of a Clojure nREPL connection.

  **What this is now.** A translation layer, and deliberately nothing more:
  standard nREPL and cider-nrepl operations go out, and what comes back is
  turned into the events Light Table already renders — an inline result beside
  a form, a printed line, an exception with a stacktrace.

  It used to be the other half of a bespoke middleware, `lighttable.nrepl`,
  vendored in this repository: 1,192 lines of Clojure that reimplemented
  completion on clojure-complete from 2013, documentation lookup, stacktrace
  formatting and a ClojureScript compiler driver, and served them over
  operations only Light Table understood. All of that is somebody's maintained
  library now — `cider-nrepl` and `orchard`, which CIDER, Calva, Conjure and
  vim-iced all sit on — so none of it is ours to keep working.

  What is worth keeping is the other end: results that appear *beside the form
  they came from*, which is the thing Light Table is for and which no editor
  bought this from. So the intelligence is bought and the presentation is not.

  ## The shape of the translation

  nREPL streams. One request produces `:out` and `:err` as they happen, then a
  `:value` or an `:ex`, then `:status [\"done\"]`. Light Table's renderers want
  one message per evaluation, so replies are accumulated per request id and
  emitted when the request finishes.

  ## Which operations

  | Light Table wants | operation | comes from |
  |---|---|---|
  | evaluate a form | `eval` | nREPL |
  | what it printed | `:out` / `:err` on that eval | nREPL |
  | an exception, structured | `analyze-last-stacktrace` | orchard |
  | documentation | `info` | orchard |
  | completions | `complete` | compliment |
  | stop it | `interrupt` | nREPL |"
  (:require [clojure.string :as string]
            [lt.object :as object]
            [lt.objs.clients :as clients]
            [lt.objs.console :as console]
            [lt.objs.files :as files]
            [lt.objs.notifos :as notifos]
            [lt.util.load :refer [node-module]]
            [cljs.reader :as reader])
  (:require-macros [lt.macros :refer [behavior]]))

(def ^js bencode (node-module "bencode"))
(def Buffer (js/require "buffer"))
(def net (js/require "net"))

(declare send send* interrupt! send-form! eval-message ensure-cljs-session! build-tool cljs-ready!)

(def ^:private cljs-clone-id "lt-clone-cljs")
(def ^:private cljs-switch-id "lt-switch-cljs")

;;*********************************************************
;; The wire
;;*********************************************************

(defn encode [msg]
  (.encode bencode (clj->js msg)))

(defn decode
  "Every complete bencode message in the client's buffer, leaving the rest.

  Returns a vector of messages. A partial message at the end of the buffer is
  normal — a reply can straddle two reads — and is kept for the next one."
  [client]
  (let [buffer (:buffer @client)]
    (loop [msg @buffer
           out []]
      (if (or (nil? msg) (<= (.-length msg) 0))
        (do (reset! buffer nil) out)
        (let [decoded (try
                        (let [m (js->clj (.decode bencode msg "utf-8") :keywordize-keys true)
                              pos (.. bencode -decode -position)]
                          {:msg m :pos pos})
                        ;; Incomplete rather than corrupt, almost always. Keep
                        ;; the bytes and decode them when the rest arrives.
                        (catch js/Error _e nil))]
          (if-not decoded
            (do (reset! buffer msg) out)
            (let [{:keys [msg' pos]} {:msg' (:msg decoded) :pos (:pos decoded)}]
              (if (and pos (< pos (.-length msg)))
                (recur (.slice msg pos) (conj out msg'))
                (do (reset! buffer nil) (conj out msg'))))))))))

(defn- deliver-messages!
  "Hand `msgs` to the client one at a time, yielding between them.

  A file evaluated form by form produces a reply per form, and doing all of
  them in one turn is how the window stops repainting while a REPL is busy."
  [client msgs]
  (when (seq msgs)
    (try
      (object/raise client ::message (first msgs))
      (catch :default e
        (console/error e)))
    ;; setTimeout rather than setImmediate: there is no `global` in the window
    ;; and Chromium has no setImmediate either.
    (js/setTimeout #(deliver-messages! client (rest msgs)) 0)))

(defn maybe-decode [client data]
  (swap! (:buffer @client) #(if % (.Buffer.concat Buffer (array % data)) data))
  (deliver-messages! client (decode client)))

(defn connect-to [host port client]
  (let [socket (.connect net port host)]
    (.on socket "connect" #(when @client (object/raise client ::connect)))
    (.on socket "error" #(when @client (object/raise client ::connect-fail)))
    (.on socket "data" #(when @client (maybe-decode client %)))
    (.on socket "close" #(when @client (object/raise client :close!)))
    socket))

;;*********************************************************
;; Requests in flight
;;*********************************************************

(defn- pending
  "The record kept for a request until nREPL says it is done."
  [client id]
  (get-in @client [::pending id]))

(defn- track! [client id record]
  (object/merge! client {::last-id id})
  (object/update! client [::pending] assoc id record))

(defn- last-record
  "The most recently sent request, for replies that name none."
  [client]
  (or (get-in @client [::pending (::last-id @client)])
      {:cb 0}))

(defn- forget! [client id]
  (object/update! client [::pending] dissoc id))

(defn- accumulate
  "Fold one nREPL reply into what has arrived for its request so far.

  `:class`, `:message` and `:stacktrace` are orchard's, and they arrive in
  their own reply *before* the `done` that ends the request — so they have to
  be kept rather than read off whichever message happens to finish it."
  [record {:keys [value out err ex root-ex ns status] :as msg}]
  (cond-> record
    value (update :values (fnil conj []) value)
    ns (assoc :ns ns)
    out (update :out (fnil str "") out)
    err (update :err (fnil str "") err)
    ex (assoc :ex ex :root-ex root-ex)
    (:stacktrace msg) (assoc :class (:class msg)
                             :message (:message msg)
                             :stacktrace (:stacktrace msg))
    ;; Any error status, not just eval-error. namespace-not-found arrives with
    ;; no :value and no :ex, so treating only eval-error as failure drew a
    ;; result of "nil" for a request that never ran — silence where there
    ;; should have been a message.
    (some #{"error" "eval-error" "namespace-not-found"} status)
    (assoc :errored? true :status (vec status))))

(defn- done? [{:keys [status]}]
  (boolean (some #{"done"} status)))

;;*********************************************************
;; Standard nREPL out, Light Table in
;;*********************************************************

(defn- send-form!
  "Evaluate form `n` of `data`, and remember to send the next one after it.

  **One at a time, in order.** The obvious thing is to send every form at once
  and let the session serialise them, and it does not work: nREPL resolves a
  request's `:ns` when the request arrives rather than when it runs, so every
  form after `(ns foo)` is answered `namespace-not-found` before the form that
  creates `foo` has been dequeued. Verified against a bare cider-nrepl, not
  inferred — the replies come back out of order and the failures arrive first.

  Waiting is not a cost worth avoiding anyway. A file evaluates top to bottom,
  each result appears as its form finishes, and watching that happen is the
  thing Light Table is for."
  [client cb data n]
  (when-let [form (nth (:forms data) n nil)]
    (let [id (str cb "-" n)]
      (track! client id {:command (if (::session data)
                                    :editor.eval.cljs
                                    :editor.eval.clj)
                         :cb cb
                         :meta (:meta form)
                         :data data
                         :next (inc n)})
      (send client (cond-> (assoc (eval-message (assoc form
                                                        :ns (:buffer-ns data)
                                                        :path (:path data)))
                                  :id id)
                     (::session data) (assoc :session (::session data)))))))

(defn- ns-form?
  "Whether `code` is an `(ns …)` form."
  [code]
  (boolean (re-find #"^\s*\(ns\s" (str code))))

(defn- eval-message
  "One `eval` request for one form.

  `:file`, `:line` and `:column` are what make a stacktrace point back at the
  editor rather than at a generated form, and they cost nothing to send."
  [{:keys [code ns path pos]}]
  (cond-> {:op "eval" :code code}
    ;; The buffer's namespace, on every form but the `ns` form itself.
    ;;
    ;; Both halves of that matter. Without `:ns` a form runs in whatever the
    ;; session's `*ns*` is, and evaluating `(ns probe)` does *not* leave it
    ;; there — nREPL pushes a thread-binding frame per request and pops it
    ;; afterwards, so the change is discarded and every later form defines its
    ;; vars in `user`. With `:ns` on the `ns` form, the first evaluation of a
    ;; file fails with `namespace-not-found`, because the namespace is what
    ;; that form is about to create.
    ;;
    ;; So the `ns` form runs bare and creates it — `in-ns` does that whether or
    ;; not the binding survives — and everything after names it explicitly.
    ;; The forms are separate requests but one session, and a session runs them
    ;; in order, so it exists by the time the second is dequeued.
    (and ns (not (ns-form? code))) (assoc :ns ns)
    path (assoc :file path)
    (:line pos) (assoc :line (inc (:line pos)))
    (:ch pos) (assoc :column (inc (:ch pos)))))

(defn- ->result
  "One accumulated eval, as the map `:editor.eval.clj.result` renders.

  `:results` is a vector because Light Table draws one result per form; each
  carries the `:meta` that says which lines it belongs beside."
  [{:keys [values ns meta] :as record}]
  {:ns ns
   :meta meta
   :results [{:result (if (seq values)
                        (string/join "\n" values)
                        ;; A form that produced no value at all — an `ns` form
                        ;; under some setups — still gets a result, because a
                        ;; blank beside a line reads as "nothing happened".
                        "nil")
              :meta meta}]})

(defn- ->exception
  "One failed eval, as `:editor.eval.clj.exception` renders it.

  `:stack` is filled in later by [[request-stacktrace!]] if the server can
  analyse it; `:err` is what nREPL already printed, which is a good summary
  and is always there."
  [{:keys [ex err meta status]}]
  (let [summary (or (some-> err string/trim (string/split #"\n") first)
                    ex
                    (when (seq status) (str "nREPL: " (string/join ", " status))))]
    {:result summary
     :stack (or err ex summary)
     :meta meta}))

(def watch-sentinel
  "The marker a watch prints its value behind.

  The other end of `lt.plugins.clojure/watch-src`. A watch used to be a
  bespoke nREPL operation carried by Light Table's own middleware; it is a
  tagged line on stdout now, so it needs nothing of the server and works
  against any nREPL. This is where the tag is taken back off."
  "LT-WATCH ")

(defn split-watches
  "Separate watch reports from ordinary printed output.

  Returns `{:watches [{:meta :result}] :text \"…\"}`. A watch prints one line
  and the rest of what a form printed is the user's, so both have to come out
  of the same `:out` — and the user's has to be left exactly as it was, minus
  the lines that were never theirs."
  [out]
  (let [lines (string/split out #"\n" -1)
        watch? #(string/starts-with? % watch-sentinel)]
    {:watches (keep (fn [line]
                      (when (watch? line)
                        (try
                          (reader/read-string (subs line (count watch-sentinel)))
                          ;; A watch whose value does not read back is a watch
                          ;; on something unprintable. Dropping it is better
                          ;; than taking the connection down.
                          (catch :default _e nil))))
                    lines)
     :text (string/join "\n" (remove watch? lines))}))

;;*********************************************************
;; Sending
;;*********************************************************

(behavior ::nrepl-send!
          :triggers #{:send!}
          :desc "Clojure: send an operation to the REPL"
          :reaction (fn [this msg]
                      ;; `lt.objs.clients/->message` puts `(name command)` in
                      ;; here, so what arrives is a string and not the keyword
                      ;; the rest of the plugin deals in.
                      (let [command (:command msg)
                            data (:data msg)
                            cb (or (:cb msg) 0)]
                        (case command
                          "editor.eval.clj"
                          (send-form! this cb data 0)

                          ;; The same path, a different session. Everything
                          ;; after this — forms from the parse tree, a result
                          ;; beside each one, watches — is what Clojure
                          ;; already does, because it is the same code.
                          "editor.eval.cljs"
                          (ensure-cljs-session!
                           this (fn [session]
                                  (send-form! this cb (assoc data ::session session) 0)))

                          "editor.clj.doc"
                          (let [id (str cb)]
                            ;; :result-type and :loc are the caller's, and both
                            ;; renderers gate on them — `print-clj-doc` draws
                            ;; only for :doc and `finish-jump-to-definition`
                            ;; only for :jump. The old middleware echoed the
                            ;; whole request back, which is how they arrived.
                            (track! this id {:command :editor.clj.doc :cb cb
                                             :meta (:meta data)
                                             :result-type (:result-type data)
                                             :loc (:loc data)})
                            (send this {:op "info" :id id
                                        :ns (or (:ns data) "user")
                                        :sym (:sym data)}))

                          ("editor.eval.clj.cancel" "client.cancel-all")
                          (interrupt! this)

                          "editor.clj.hints"
                          (let [id (str cb)]
                            (track! this id {:command :editor.clj.hints :cb cb})
                            (send this {:op "complete" :id id
                                        :ns (or (:ns data) "user")
                                        :prefix (or (:prefix data) "")}))

                          ;; Anything the plugin still asks for that has no
                          ;; standard operation behind it. Saying so is better
                          ;; than a request that never answers.
                          (console/error
                           (str "The Clojure REPL client has no nREPL operation for " command
                                ". See plugins/Clojure/VENDORED.md."))))))

(defn interrupt!
  "Ask the server to stop what it is running."
  [client]
  (send client {:op "interrupt" :id (str "interrupt-" (rand-int 100000))}))

;;*********************************************************
;; Receiving
;;*********************************************************

(defn- request-stacktrace!
  "Ask orchard to analyse the exception just thrown, and redraw with it.

  A second round trip, and worth it: `:err` is a one-line summary and this is
  the frame list, already classified into the user's code and everything
  else. Light Table draws the whole thing in a widget that collapses."
  [client id record]
  (let [st-id (str id "-stacktrace")]
    (track! client st-id (assoc record :command ::stacktrace))
    (send client {:op "analyze-last-stacktrace" :id st-id})))

(defn- ->path
  "cider's `:file`, as somewhere the editor can open.

  `info` answers with a URL — `file:/…/src/probe.clj` for a project file and
  `jar:file:/…/clojure-1.11.1.jar!/clojure/core.clj` for anything else on the
  classpath. The jump stack takes a path and checks it exists, so the first
  becomes one and the second becomes nil: there is nothing to open inside a
  jar, and saying so beats jumping somewhere that is not there."
  [file]
  (when (and file (not (string/starts-with? file "jar:")))
    (string/replace file #"^file:" "")))

(defn- frames->text
  "orchard's frame list, as the block the exception widget shows."
  [frames]
  (string/join "\n"
               (for [f frames]
                 (str "  " (:file f) ":" (:line f) " " (:name f)))))

(defn- finish!
  "A request is done: turn what accumulated into what Light Table renders."
  [client id {:keys [command cb meta errored?] :as record} msg]
  (forget! client id)
  (case command
    (:editor.eval.clj :editor.eval.cljs)
    (do
      ;; The next form goes out as soon as this one is finished, whether it
      ;; produced a value or threw — a file does not stop evaluating because
      ;; one form in the middle of it failed, and the failure is drawn beside
      ;; the form it belongs to.
      (send-form! client cb (:data record) (:next record))
      (if errored?
      ;; The exception is drawn now from what nREPL printed, and again with
      ;; orchard's frames when they arrive. Drawing twice is deliberate: the
      ;; first is instant and the second is better.
        (do (object/raise clients/clients :message
                          [cb (if (= :editor.eval.cljs command)
                                :editor.eval.cljs.exception
                                :editor.eval.clj.exception)
                           (->exception record)])
            (when (= :editor.eval.clj command)
              ;; orchard analyses the JVM's last exception; a ClojureScript one
              ;; is not on that stack.
              (request-stacktrace! client id record)))
        (object/raise clients/clients :message
                      [cb (if (= :editor.eval.cljs command)
                            :editor.eval.cljs.result
                            :editor.eval.clj.result)
                       (->result record)])))

    ::stacktrace
    (let [{:keys [class message stacktrace]} record]
      (when (seq stacktrace)
        (object/raise clients/clients :message
                      [cb :editor.eval.clj.exception
                       {:result (str class ": " message)
                        :stack (str class ": " message "\n"
                                    (frames->text stacktrace))
                        :meta meta}])))

    :editor.clj.doc
    (object/raise clients/clients :message
                  [cb :editor.clj.doc
                   {:ns (:ns msg)
                    :name (:name msg)
                    :args (:arglists-str msg)
                    :doc (:doc msg)
                    :file (->path (:file msg))
                    :line (:line msg)
                    :result-type (:result-type record)
                    :loc (:loc record)
                    :meta meta}])

    :editor.clj.hints
    (object/raise clients/clients :message
                  [cb :editor.clj.hints.result
                   (clj->js (for [c (:completions msg)]
                              {:completion (:candidate c)}))])

    nil))

(def cljs-switch
  "The form that turns a session into a ClojureScript one, per build tool.

  Nobody is asked which. The project already says: a `shadow-cljs.edn` beside
  the code means shadow, and shadow's own nREPL knows that project's builds and
  can attach to the runtime the user already has open. Anything else gets
  piggieback over a node environment, which needs nothing installed.

  This is `::language-servers`' rule again — look at the project, act, and let
  a behavior override — rather than a question in front of every evaluation."
  {:shadow "(shadow.cljs.devtools.api/node-repl)"
   :piggieback (str "(do (require 'cljs.repl.node 'cider.piggieback)"
                    " (cider.piggieback/cljs-repl (cljs.repl.node/repl-env)))")})

(behavior ::nrepl-message
          :triggers #{::message}
          :desc "Clojure: handle one nREPL reply"
          :reaction (fn [this msg]
                      (let [id (:id msg)
                            status (set (:status msg))]
                        (when-let [new-session (:new-session msg)]
                          (if (= id cljs-clone-id)
                            ;; The second session. Switch it, and only call it
                            ;; ready when the switch has actually taken — the
                            ;; form can fail, and a session that is still
                            ;; Clojure would evaluate ClojureScript as Clojure
                            ;; and report nonsense.
                            (do (object/merge! this {::cljs-candidate new-session})
                                (send* this {:op "eval" :id cljs-switch-id
                                             :session new-session
                                             :code (get cljs-switch (build-tool this))}))
                            (object/raise this :new-session new-session)))

                        ;; Only failure is decided on `done`; a switch that
                        ;; worked is noticed by the state message above.
                        (when (and (= id cljs-switch-id) (done? msg)
                                   (not (::cljs-session @this))
                                   (::cljs-error @this))
                          (object/merge! this {::cljs-starting false ::cljs-waiting []})
                          (console/error
                                 (str "ClojureScript REPL: " (::cljs-error @this)))
                          (notifos/set-msg!
                                 (str "Could not start a ClojureScript REPL — "
                                      (or (some-> (::cljs-error @this) string/trim
                                                  (string/split #"\n") first)
                                          (if (= :shadow (build-tool this))
                                            "shadow-cljs needs a build running, or a runtime to attach to."
                                            "see the console.")))
                                 {:class "error"}))

                        (when (status "interrupted")
                          (notifos/done-working "Interrupted"))

                        ;; cider-nrepl and shadow both say which language the
                        ;; session is evaluating, on every `state` message. Read
                        ;; rather than remembered, so a session someone switched
                        ;; from elsewhere is noticed too.
                        (when-let [repl-type (:repl-type msg)]
                          (object/update! this [::repl-types]
                                          assoc (:session msg) (keyword repl-type))
                          ;; Readiness is decided here and not on the switch's
                          ;; `done`, because the `state` message that carries
                          ;; :repl-type arrives *after* it — deciding on done
                          ;; asked whether the session was ClojureScript one
                          ;; message before it said so.
                          (when (and (= :cljs (keyword repl-type))
                                     (= (:session msg) (::cljs-candidate @this))
                                     (not (::cljs-session @this)))
                            (cljs-ready! this (:session msg))))

                        ;; Printed output is drawn as it arrives rather than
                        ;; held until the form finishes. Watching a long
                        ;; computation print is the point of an inline REPL.
                        ;;
                        ;; nREPL's out and err are written by the session's
                        ;; writers, and a reply carrying them does not always
                        ;; carry the id of the request that caused them — a
                        ;; future that outlives an eval prints under no request
                        ;; at all. So the last request to be sent stands in,
                        ;; which is right for the common case and never drops
                        ;; the line on the floor.
                        (when-let [out (:out msg)]
                          (let [record (or (pending this id) (last-record this))
                                {:keys [watches text]} (split-watches out)]
                            (doseq [w watches]
                              (object/raise clients/clients :message
                                            [(:cb record) :editor.eval.clj.watch w]))
                            (when-not (string/blank? text)
                              (object/raise clients/clients :message
                                            [(:cb record) :editor.eval.clj.print {:out text}]))))
                        ;; The session switch is ours rather than a user's
                        ;; evaluation, so its output is kept for the message
                        ;; below instead of being drawn beside somebody's code.
                        (when (and (= id cljs-switch-id) (:err msg))
                          (object/update! this [::cljs-error] (fnil str "") (:err msg)))

                        (when-let [err (:err msg)]
                          (let [record (when-not (= id cljs-switch-id)
                                         (or (pending this id) (last-record this)))]
                            (when record
                              (object/raise clients/clients :message
                                            [(:cb record) :editor.eval.clj.print.err {:out err}]))))

                        (when-let [record (or (pending this id)
                                              ;; An err with no id still belongs
                                              ;; to the eval that is running,
                                              ;; and its text is the readable
                                              ;; half of the exception.
                                              (when (or (:err msg) (:ex msg))
                                                (last-record this)))]
                          (let [record (accumulate record msg)
                                id (or (when (pending this id) id) (::last-id @this))]
                            (if (done? msg)
                              (finish! this id record msg)
                              (track! this id record)))))))

;;*********************************************************
;; Connecting
;;*********************************************************

;;*********************************************************
;; A second session, for ClojureScript
;;*********************************************************

(defn- build-tool
  "Which switch form this project wants, from what is beside the code."
  [client]
  (if (files/exists? (files/join (:dir @client) "shadow-cljs.edn"))
    :shadow
    :piggieback))

(defn- cljs-ready! [client session]
  (object/merge! client {::cljs-session session ::cljs-starting false})
  (notifos/done-working "ClojureScript REPL ready")
  (doseq [k (::cljs-waiting @client)] (k session))
  (object/merge! client {::cljs-waiting []}))

(defn ensure-cljs-session!
  "Call `k` with a session that is evaluating ClojureScript, starting one if
  there is not one yet.

  A *second* session on the same connection rather than switching the one
  there is. A project holds .clj and .cljs files and both have to keep
  evaluating; switching would mean the Clojure half stops until someone
  switches back, which is worse than it sounds and very hard to explain.
  nREPL sessions are cheap and independent, so there are two."
  [client k]
  (if-let [s (::cljs-session @client)]
    (k s)
    (do
      (object/update! client [::cljs-waiting] (fnil conj []) k)
      (when-not (::cljs-starting @client)
        (object/merge! client {::cljs-starting true})
        (notifos/working "Starting a ClojureScript REPL…")
        (send* client {:op "clone" :id cljs-clone-id})))))

(behavior ::nrepl-connect
          :triggers #{::connect}
          :reaction (fn [this]
                      (object/merge! this {:buffer (atom nil) ::pending {}})
                      (send* this {:op "clone" :id "clone"})))

(defn- client-settings
  "What Light Table records about a connection.

  `:commands` is the list `lt.objs.clients` matches an editor's request
  against, and it is now what this client can actually do rather than what a
  bespoke server advertised."
  [this]
  {:name (:name @this)
   :type "nrepl"
   :dir (:dir @this)
   :client-id (clients/->id this)
   :commands [:editor.eval.clj
              :editor.eval.clj.cancel
              :editor.eval.cljs
              :editor.clj.doc
              :editor.clj.hints]})

(behavior ::init-session
          :triggers #{:new-session}
          :reaction (fn [this session]
                      (object/merge! this {:session session})
                      ;; There is no handshake to perform: a standard nREPL
                      ;; has told us everything by answering `clone`. What
                      ;; used to be a `client.init` round trip to a bespoke
                      ;; server is now just Light Table recording what it has.
                      (clients/handle-connection! (client-settings this))
                      (notifos/done-working "Connected to the REPL")))

(behavior ::init-remote-session
          :triggers #{:new-session}
          :reaction (fn [this session]
                      (object/merge! this {:session session})
                      (clients/handle-connection! (assoc (client-settings this)
                                                         :remote true
                                                         :dir nil))))

(behavior ::try-connect!
          :triggers #{:try-connect!}
          :reaction (fn [this _info]
                      (when (:port @this)
                        (object/raise this :connect!))))

(behavior ::connect!
          :triggers #{:connect!}
          :reaction (fn [this]
                      (object/merge! this {:socket (connect-to (:host @this "localhost")
                                                               (:port @this)
                                                               this)})))

(behavior ::close
          :triggers #{:close!}
          :reaction (fn [this]
                      (clients/rem! this)))

(defn send* [client msg]
  (.write (:socket @client) (encode msg)))

(defn send [client msg]
  (let [session (:session @client)]
    ;; A message that names its own session keeps it — that is how a
    ;; ClojureScript evaluation reaches the ClojureScript session while
    ;; everything else stays on the Clojure one.
    (send* client (merge (when session {:session session}) msg))))
