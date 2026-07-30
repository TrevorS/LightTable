(ns lt.util.ipc
  "Util functions for the ipc renderer - https://github.com/atom/electron/blob/master/docs/api/ipc-renderer.md")

(def ipc "Provides access to the ipc renderer." (.-ipcRenderer (js/require "electron")))

;; `send` and `on` are declared here with their bodies defined later as otherwise Codox will use the
;; redefined `send` and `on` in the below when block instead.
(declare transport)

(declare start)

;; Set $IPC_DEBUG to debug incoming and outgoing ipc messages for the renderer process
(when (aget js/process.env "IPC_DEBUG")
  (let [old-send transport
        old-on start]
    (def transport (fn [& args]
                     (prn "RENDERER->" args)
                     (apply old-send args)))
    (def start (fn [channel cb]
                 (old-on channel (fn [_ & args]
                                   (prn "->RENDERER" channel args)
                                   (apply cb args)))))))

(defn send
  "Delegates to ipc.send, which asynchronously sends args to the browser process's channel."
  [channel & args]
  (apply (.-send ipc) channel (clj->js args)))

(defn on
  "Delegates to ipc.on, which defines a callback to fire for the given channel."
  [channel cb]
  (.on ipc channel cb))

(defn send-sync
  "Delegates to ipc.sendSync, blocking until the browser process replies.

  Only for values the renderer needs before it can carry on booting; prefer
  [[send]] or [[invoke]] everywhere else."
  [channel & args]
  (apply (.-sendSync ipc) channel (clj->js args)))

(defn invoke
  "Delegates to ipc.invoke, returning a promise of the browser process's reply."
  [channel & args]
  (apply (.-invoke ipc) channel (clj->js args)))

(def app-info
  "Values owned by the browser process that the renderer reads once at startup.
  Keys are :appPath, :parsedArgs, :openFiles and :argv."
  (js->clj (send-sync "lt:app-info") :keywordize-keys true))
