(ns lt.background.worker
  "Entry point for the worker thread.

  The renderer names the work it wants; nothing is sent across as code. Adding a
  background job means adding a function to this table and calling it by the
  same key from lt.objs.thread."
  (:require [cljs.reader :as reader]
            [lt.background.runtime :as bg]
            [lt.background.search :as search]
            [lt.background.behaviors :as behaviors]
            [lt.background.navigate :as navigate]
            [lt.background.auto-complete :as auto-complete]))

(def ^:private jobs
  {:search           search/search
   :parse-behaviors  behaviors/parse-flat
   :workspace-files  navigate/workspace-files
   :hint-tokens      auto-complete/hint-tokens})

(defn- handle-message [^js message]
  (case (.-msg message)
    "init" (do
             (bg/set-lt-path! (.-ltpath message))
             (.send js/process #js {:obj (.-obj message) :msg "connect" :format "json"}))
    "call" (if-let [job (get jobs (keyword (.-job message)))]
             (apply job (.-obj message) (map reader/read-string (.-params message)))
             (.error js/console (str "No such background job: " (.-job message))))
    (.error js/console (str "Unknown worker message: " (.-msg message)))))

(defn main
  "Wire up the message loop. Errors are reported rather than thrown, so that one
  bad job does not take the worker down with it."
  []
  (.on js/process "message"
       (fn [message]
         (try
           (handle-message message)
           (catch :default e
             (.error js/console (.-stack e)))))))
