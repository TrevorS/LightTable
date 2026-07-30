(ns lt.objs.platform
  "Provide platform-agnostic and platform related fns"
  (:require [lt.util.bridge :as bridge]))

(def electron true)

(defn get-data-path []
  (:appPath bridge/app-info))

(defn normalize [plat]
  (condp = plat
    "win32" :windows
    "linux" :linux
    "darwin" :mac))

(defn open-url [path]
  (.openExternal bridge/shell path))

(defn open
  "If the given path exists, open it with the desktop's default manner.
  Otherwise, open it as an external protocol e.g. a url."
  [path]
  ;; Which of the two it is gets decided on the other side of the bridge, where
  ;; there is a filesystem to decide it with.
  (-> (.open bridge/shell path)
      (.then #(when (seq %)
                (js/lt.objs.console.error (str "Could not open " path ": " %))))))

(defn show-item [path]
  (.showItemInFolder bridge/shell path))

(defn copy
  "Copies given text to platform's clipboard"
  [text]
  (.writeText bridge/clipboard text))

(defn paste
  "Returns text of last copy to platform's clipboard"
  []
  (.readText bridge/clipboard))

;; Was (.-platform js/process), which a sandboxed window does not have.
(def platform (normalize (:platform bridge/app-info)))

(defn mac? []
  (= platform :mac))

(defn win? []
  (= platform :windows))

(defn linux? []
  (= platform :linux))
