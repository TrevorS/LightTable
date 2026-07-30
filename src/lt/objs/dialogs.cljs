(ns lt.objs.dialogs
  "Provide Electron-based dialogs"
  (:require [lt.object :as object]
            [lt.util.dom :as dom]
            [lt.objs.app :as app])
  (:require-macros [lt.macros :refer [behavior defui]]))

(def remote (.-remote (js/require "electron")))
(def dialog (.-dialog remote))

(defn- open-dialog
  "Show an open dialog and raise `event` on `obj` once per selected path.
  Electron 6 made the dialog fns promise-based, so results arrive async."
  [obj event properties]
  (-> (.showOpenDialog dialog app/win #js {:properties properties})
      (.then (fn [result]
               (when-not (.-canceled result)
                 (doseq [file (.-filePaths result)]
                   (object/raise obj event file)))))
      (.catch #(js/lt.objs.console.error %))))

(defn dir [obj event]
  (open-dialog obj event #js ["openDirectory" "multiSelections"]))

(defn file [obj event]
  (open-dialog obj event #js ["openFile" "multiSelections"]))

(defn save-as [obj event path]
  (-> (.showSaveDialog dialog app/win #js {:defaultPath path})
      (.then (fn [result]
               (when-not (.-canceled result)
                 (when-let [file (.-filePath result)]
                   (object/raise obj event file)))))
      (.catch #(js/lt.objs.console.error %))))
