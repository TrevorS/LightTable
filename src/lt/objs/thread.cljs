(ns lt.objs.thread
  "Provide worker thread for background processes"
  (:require [lt.object :as object]
            [lt.objs.files :as files]
            [lt.objs.platform :as platform]
            [lt.objs.console :as console]
            [cljs.reader :as reader])
  (:require-macros [lt.macros :refer [behavior]]))

(def cp (js/require "child_process"))

(declare worker)

(behavior ::try-send
          :triggers #{:try-send!}
          :reaction (fn [this msg]
                      (if-not (:connected @this)
                        (object/raise this :queue! msg)
                        (object/raise this :send! msg))))

(behavior ::queue!
          :triggers #{:queue!}
          :reaction (fn [this msg]
                      (object/update! this [:queue] conj msg)))

(behavior ::send!
          :triggers #{:send!}
          :reaction (fn [this msg]
                      (.send (:worker @this) (clj->js msg))))

(behavior ::connect
          :triggers #{:connect}
          :reaction (fn [this]
                      (doseq [q (:queue @this)]
                        (object/raise this :send! q))
                      (object/merge! this {:connected true
                                           :queue []})))

(behavior ::message
          :triggers #{:message}
          :reaction (fn [this ^js m]
                      (when-let [obj (object/by-id (.-obj m))]
                        (object/raise obj
                                      (if-not (keyword? (.-msg m))
                                        (keyword (.-msg m))
                                        (.-msg m))
                                      (if (= "clj" (.-format m))
                                        (reader/read-string (.-res m))
                                        (.-res m))))))

(behavior ::kill!
          :triggers #{:kill!}
          :reaction (fn [this]
                      (.kill ^js (:worker @this))))

(behavior ::shutdown-worker-on-close
          :triggers #{:closed}
          :reaction (fn [app]
                      (object/raise worker :kill!)))

;; A forked node process running the compiled lt.background.worker bundle. The
;; renderer sends it the name of a job to run; results come back as messages.
(object/object* ::worker-thread
                :tags #{:worker-thread}
                :queue []
                :init (fn [this]
                        (let [worker (.fork cp (files/lt-home "/core/lighttable/background/worker.js")
                                            (clj->js ["--harmony"])
                                            (clj->js {:execPath js/process.execPath
                                                      :silent true
                                                      ;; ATOM_SHELL_INTERNAL_RUN_AS_NODE became
                                                      ;; ELECTRON_RUN_AS_NODE in Electron 1.x. Without it the
                                                      ;; child boots as a full app and never gets an IPC
                                                      ;; channel, so process.send is undefined in the worker.
                                                      ;; Extend the current env rather than replacing it,
                                                      ;; otherwise the worker loses PATH and friends.
                                                      :env (js/Object.assign #js {} js/process.env
                                                                             #js {"ELECTRON_RUN_AS_NODE" "1"})
                                                      :cwd files/cwd}))]
                          (.on (.-stdout worker) "data" (fn [data]
                                                          (console/loc-log {:file "thread"
                                                                            :line "stdout"
                                                                            :content (str data)})))
                          (.on (.-stderr worker) "data" (fn [data]
                                                          (console/loc-log {:file "thread"
                                                                            :line "stderr"
                                                                            :content (str data)
                                                                            :class "error"})))
                          (.on worker "message" (fn [m] (object/raise this :message m)))
                          (.send worker (clj->js {:msg "init"
                                                  :obj (object/->id this)
                                                  :ltpath (files/lt-home)}))
                          (object/merge! this {:worker worker})
                        nil)))

(defn send [msg]
  (object/raise worker :try-send! msg))

(defn job
  "Return a fn that runs the worker job registered under `job-key` in
  lt.background.worker, for the object it is given.

  This used to take the function itself, stringify it, and send the source over
  for the worker to eval. Naming the job instead means the worker is compiled
  like the rest of the codebase, and the two sides can be checked against each
  other rather than only failing at runtime."
  [job-key]
  (fn [obj & args]
    (send {:msg "call"
           :job (name job-key)
           :obj (object/->id obj)
           :params (map pr-str args)})))

;;NOTE: Because functions are defined at load time, we need to pre-add the worker behaviors so that
;;      the defined functions are sent correctly
(object/tag-behaviors :worker-thread [::kill! ::connect ::send! ::queue! ::try-send ::message])

(def worker (object/create ::worker-thread))
