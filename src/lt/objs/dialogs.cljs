(ns lt.objs.dialogs
  "Provide Electron-based dialogs"
  (:require [lt.object :as object]
            [lt.util.dom :as dom]
            [lt.util.bridge :as bridge])
  (:require-macros [lt.macros :refer [behavior defui]]))

;; The dialog module lives in the browser process. It used to be reached through
;; `remote`, which Electron removed in v14, so these go across the bridge
;; instead. Both dialog fns have returned promises since Electron 6, so callers
;; were already async and are unaffected by the move.

(defn- open-dialog
  "Show an open dialog and raise `event` on `obj` once per selected path."
  [obj event properties]
  (-> (.open bridge/dialog #js {:properties (clj->js properties)})
      (.then (fn [^js result]
               (when-not (.-canceled result)
                 (doseq [file (.-filePaths result)]
                   (object/raise obj event file)))))
      (.catch #(js/lt.objs.console.error %))))

(defn dir [obj event]
  (open-dialog obj event ["openDirectory" "multiSelections"]))

(defn file [obj event]
  (open-dialog obj event ["openFile" "multiSelections"]))

(defn save-as [obj event path]
  (-> (.save bridge/dialog #js {:defaultPath path})
      (.then (fn [^js result]
               (when-not (.-canceled result)
                 (when-let [file (.-filePath result)]
                   (object/raise obj event file)))))
      (.catch #(js/lt.objs.console.error %))))
