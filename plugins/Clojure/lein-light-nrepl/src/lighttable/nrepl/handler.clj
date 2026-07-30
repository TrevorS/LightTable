(ns lighttable.nrepl.handler
  (:require [nrepl.server :refer [start-server stop-server default-handler]]
            [nrepl.transport :as transport]
            [nrepl.middleware.session :refer [session]]
            [nrepl.middleware.interruptible-eval :refer [interruptible-eval *msg*]]
            [nrepl.misc :refer [response-for returning]]
            [nrepl.middleware :refer [set-descriptor!]]
            [lighttable.nrepl.core :as core]
            [lighttable.nrepl.eval :as eval]
            [lighttable.nrepl.cljs :as cljs]
            [lighttable.nrepl.doc :as doc]
            lighttable.nrepl.auto-complete
            [clojure.repl :as repl]))

(defn with-lt-data [msg]
  (if (or (not (:data msg))
          (empty? (:data msg)))
    msg
    (merge (dissoc msg :data) (read-string (:data msg)))))

(defn lighttable-ops
  "Evaluation middleware that supports interrupts and tracking of source forms.
   Returns a handler that supports \"eval\" and \"interrupt\" :op-erations that
   delegates to the given handler otherwise."
  ;; The executor keyword argument is gone: nrepl 1.x gives every session its
  ;; own thread and publishes `:exec` in the session's metadata, so there is no
  ;; executor for a caller to supply. See lighttable.nrepl.core/queued.
  [h & _configuration]
  (fn [{:keys [op session interrupt-id id transport] :as msg}]
    (cond
     (= op "client.close") (do
                             (core/remove-client msg)
                             (when (empty? @core/clients)
                               (core/restore-io))
                             (when-not (:remote @core/my-settings)
                                (System/exit 0)))
     (= op "client.init") (do
                            (core/capture-client msg)
                            (core/redirect-io msg)
                            (when-let [setts (-> msg with-lt-data :settings)]
                              (core/settings! setts))
                            (core/respond msg "client.settings" (dissoc @core/my-settings :project))
                            (let [out (core/out-writer)
                                  err (core/out-writer "editor.eval.clj.print.err")]
                              (swap! session assoc
                                     #'*out* out
                                     #'*err* err)))
     (core/no-queue? op) (core/handle msg)
     (core/lt-op? op) (core/queued (-> msg
                                       (assoc :h h)
                                       (with-lt-data)))
     :else (h msg))))

(set-descriptor!
 #'lighttable-ops
 {:requires #{#'session}
  :expects #{}})
