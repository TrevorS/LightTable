(ns lt.objs.clients.lsp
  "A Language Server Protocol client: one server process, its lifecycle, and
  the correlation of requests to responses.

  The framing is [[lt.objs.clients.lsp.wire]], which is pure and tested. This
  is the part that owns a process, and it is deliberately the only part that
  does — so the protocol logic above it never has to know that a server is a
  child process rather than a socket.

  See doc/lsp-architecture.md for why the protocol lives in the editor rather
  than in each language plugin, and what the remaining layers are.

  Every event this raises carries the connection it came from, because one
  Light Table window talks to several servers at once and they all report to
  the same object. `:lsp.exit` without a connection is a message that cannot
  be acted on."
  (:require [clojure.string :as string]
            [lt.object :as object]
            [lt.objs.clients.lsp.sync :as sync]
            [lt.objs.clients.lsp.wire :as wire]
            [lt.util.bridge :as bridge]))

(def ^:private request-timeout-ms
  "How long a request waits before it is failed.

  A language server that has stopped answering is a normal thing to survive —
  a spinner that never stops is not, and neither is a callback that is never
  called and never released."
  30000)

(defn- next-id
  "Request ids are per connection and monotonic, which is all JSON-RPC asks."
  [conn]
  (:next-id (swap! conn update :next-id inc)))

;;*********************************************************
;; Sending
;;*********************************************************

(defn- write! [conn message]
  (when-let [^js handle (:handle @conn)]
    (.write handle (wire/encode-string message))))

(defn- send!
  "Write a message, or hold it until the handshake finishes.

  Everything shares one queue so that order is preserved: a `didOpen` that
  overtakes the `initialize` it was queued behind describes a document to a
  server that does not yet know the project exists."
  [conn msg]
  (if (:initialized? @conn)
    (write! conn msg)
    (swap! conn update :queued conj msg)))

(defn notify!
  "Send a notification, which expects no reply."
  [conn method params]
  (send! conn (wire/notification method params)))

(defn request!
  "Send a request and call `callback` with `{:result}` or `{:error}`.

  Before the server has answered `initialize` the request is queued rather than
  sent. Servers are entitled to reject anything that arrives first and several
  do, with an error that reads like a bug in the request rather than in its
  timing.

  `:immediate?` bypasses that queue, which only `initialize` itself needs —
  it is the request that ends the waiting, so queueing it would deadlock."
  ([conn method params callback] (request! conn method params callback nil))
  ([conn method params callback {:keys [immediate?]}]
   (let [id (next-id conn)
         msg (wire/request id method params)
         timeout (js/setTimeout
                  (fn []
                    (when-let [cb (get-in @conn [:pending id])]
                      (swap! conn update :pending dissoc id)
                      (cb {:error {:code :timeout
                                   :message (str method " did not answer in "
                                                 request-timeout-ms "ms")}})))
                  request-timeout-ms)]
     (swap! conn update :pending assoc id
            (fn [outcome] (js/clearTimeout timeout) (callback outcome)))
     (if immediate?
       (write! conn msg)
       (send! conn msg))
     id)))

(defn- flush-queue! [conn]
  (let [queued (:queued @conn)]
    (swap! conn assoc :queued [])
    (doseq [msg queued] (write! conn msg))))

;;*********************************************************
;; Receiving
;;*********************************************************

(def ^:private method-not-found -32601)

(defn- handle-response! [conn {:keys [id result error]}]
  (if-let [cb (get-in @conn [:pending id])]
    (do (swap! conn update :pending dissoc id)
        (cb (if error {:error error} {:result result})))
    ;; A response to a request that already timed out. Dropping it is correct;
    ;; saying so is how a too-short timeout gets noticed.
    (object/raise (:object @conn) :lsp.stray-response id conn)))

(defn- handle-request!
  "Answer a request the *server* sent us.

  Answering matters more than it looks: a server that asks
  `window/workDoneProgress/create` and never hears back can stop sending
  diagnostics entirely, and the symptom is a feature that silently does
  nothing.

  Anything not recognised gets a method-not-found error rather than silence,
  which is what the protocol asks for and what lets a server fall back."
  [conn {:keys [id method] :as msg}]
  (case method
    ;; Progress reporting: yes, go ahead. Nothing renders it yet.
    "window/workDoneProgress/create" (write! conn (wire/response id nil))
    ;; Dynamic capability registration: accepted, and ignored until there is
    ;; something registering capabilities for.
    "client/registerCapability" (write! conn (wire/response id nil))
    "client/unregisterCapability" (write! conn (wire/response id nil))
    (do
      (object/raise (:object @conn) :lsp.unhandled-request msg conn)
      (write! conn (wire/error-response id method-not-found
                                        (str "Light Table does not implement " method))))))

(defn- handle-message! [conn msg]
  (case (wire/message-kind msg)
    :response (handle-response! conn msg)
    :request (handle-request! conn msg)
    :notification (object/raise (:object @conn) :lsp.notification msg conn)
    (object/raise (:object @conn) :lsp.unknown-message msg conn)))

(defn- handle-bytes! [conn chunk]
  (let [{:keys [buffer messages errors]} (wire/feed (:buffer @conn) chunk)]
    (swap! conn assoc :buffer buffer)
    (doseq [e errors] (object/raise (:object @conn) :lsp.wire-error e conn))
    (doseq [m messages] (handle-message! conn m))))

;;*********************************************************
;; Lifecycle
;;*********************************************************

(defn- initialize-params [root-path]
  {:processId nil
   :rootUri (sync/->uri root-path)
   :workspaceFolders [{:uri (sync/->uri root-path)
                       :name (last (string/split root-path #"/"))}]
   :capabilities
   {:textDocument
    {:synchronization {:didSave true :dynamicRegistration false}
     :publishDiagnostics {:relatedInformation false}}
    :window {:workDoneProgress true}}
   ;; Only what is actually implemented is advertised. A client that claims a
   ;; capability it does not have gets sent things it will drop, and the server
   ;; has no way to know.
   :clientInfo {:name "Light Table"}})

(defn connect!
  "Start `command` as a language server rooted at `root-path`.

  Returns the connection atom. `on-ready` is called with the server's
  `initialize` result once the handshake finishes, which is where a caller
  learns what the server can actually do — sync kind, completion triggers, and
  the rest."
  [{:keys [command args root-path object on-ready]}]
  (let [conn (atom {:next-id 0
                    :pending {}
                    :queued []
                    :buffer wire/empty-buffer
                    :initialized? false
                    :object object
                    :root-path root-path})
        ^js handle (.spawn bridge/processes command (clj->js (or args []))
                           #js {:cwd root-path})]
    (swap! conn assoc :handle handle)
    (.onStdoutBytes handle (fn [chunk] (handle-bytes! conn chunk)))
    ;; stderr is where a server explains itself when it will not start, so it
    ;; goes somewhere a plugin author will look rather than nowhere.
    (.onStderr handle (fn [text] (object/raise object :lsp.stderr text conn)))
    (.onExit handle (fn [code]
                      (swap! conn assoc :initialized? false :handle nil)
                      (doseq [[_ cb] (:pending @conn)]
                        (cb {:error {:code :exited :message "the language server exited"}}))
                      (swap! conn assoc :pending {})
                      (object/raise object :lsp.exit code conn)))
    (.onError handle (fn [message] (object/raise object :lsp.error message conn)))

    ;; The handshake. `initialize` is a request; `initialized` is the
    ;; notification that tells the server the client is ready for real work,
    ;; and skipping it leaves some servers waiting forever.
    (request! conn "initialize" (initialize-params root-path)
              (fn [{:keys [result error]}]
                (if error
                  (object/raise object :lsp.error
                                (str "initialize failed: " (:message error)) conn)
                  (do
                    (swap! conn assoc :initialized? true :capabilities (:capabilities result))
                    ;; `initialized` first, and outside the queue it just
                    ;; opened, because it is what makes the rest legal.
                    (write! conn (wire/notification "initialized" {}))
                    (flush-queue! conn)
                    (object/raise object :lsp.ready result conn)
                    (when on-ready (on-ready result)))))
              {:immediate? true})
    conn))

(defn disconnect!
  "Shut a server down the way the protocol asks: `shutdown`, then `exit`.

  Killing the process instead works, and leaves a server that was writing to
  disk no chance to finish."
  [conn]
  (when (:handle @conn)
    (request! conn "shutdown" nil
              (fn [_]
                (notify! conn "exit" nil)
                (when-let [^js handle (:handle @conn)]
                  (.endStdin handle))))))

(defn server-capabilities
  "What the server said it can do, once the handshake has finished."
  [conn]
  (:capabilities @conn))

(defn ready?
  "Whether the handshake has finished. Requests before this are queued, not
  lost, so this is for reporting rather than for gating."
  [conn]
  (boolean (:initialized? @conn)))

(defn alive?
  "Whether the server process is still running."
  [conn]
  (some? (:handle @conn)))
