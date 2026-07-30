(ns lt.objs.app
  "Provide app object which manages app startup, app shutdown and
  window related features"
  (:require [lt.object :as object]
            [lt.objs.platform :as platform]
            [lt.objs.command :as cmd]
            [lt.objs.context :as ctx]
            [clojure.string :as string]
            [lt.util.js :refer [now]]
            [lt.util.dom :refer [$] :as dom]
            [lt.util.ipc :as ipc])
  (:require-macros [lt.macros :refer [behavior]]))

(def frame (.-webFrame (js/require "electron")))

;; BrowserWindow lives in the browser process. It used to be reached through
;; `remote`, which Electron removed in v14. Mutations are fire-and-forget sends;
;; the few reads are synchronous because they happen while the window is closing.

(defn- window-call!
  "Invoke `method` on this renderer's BrowserWindow, in the browser process."
  [method & args]
  (ipc/send "lt:window-call" (name method) (vec args)))

(defn- window-state
  "Current geometry of this renderer's BrowserWindow: :id, :size, :position
  and :fullScreen."
  []
  (js->clj (ipc/send-sync "lt:window-state") :keywordize-keys true))
(def closing true)
(def default-zoom 1)

(defn app-url []
  (.-location.href js/window))

(defn window-number []
  (:id (window-state)))

(defn first-window? []
  (= 1 (window-number)))

(defn prevent-close []
  (set! closing false))

(declare app)

(defn close
  ([] (close false))
  ([force?]
   (if force?
     (do
       (object/raise app :closing)
       (object/raise app :closed)
       (window-call! :destroy))
     (window-call! :close))))

(defn refresh []
  (js/window.location.reload true))

(defn init []
  (object/raise app :deploy)
  (object/raise app :pre-init)
  (object/raise app :init)
  (object/raise app :post-init)
  (object/raise app :show))

(defn fetch [k]
  (when-let [v (aget js/localStorage (name k))]
    (when (not= "null" v)
      (js/JSON.parse v))))

(defn store!
  "Store key and value in localStorage. If value is a string, fetch must be used
  to get back the original value from localStorage."
  [k v]
  (aset js/localStorage (name k) (if (string? v)
                                   (pr-str v)
                                   v)))

(defn store-swap! [k f]
  (let [neue (f (fetch k))]
    (store! k neue)
    neue))


(defn ensure-greater [x cap]
  (let [x (if (string? x)
            (js/parseInt x)
            x)]
    (max x cap)))

(defn zoom-level []
  (when (not= (.getZoomFactor frame) 0)
    (.getZoomFactor frame)))

(defn open-window []
  (ipc/send "createWindow"))

;;*********************************************************
;; Behaviors
;;*********************************************************

(behavior ::refresh
          :triggers #{:refresh}
          :reaction (fn [obj]
                      (set! closing true)
                      (object/raise app :reload)
                      (when closing
                        (refresh))))

(behavior ::close!
          :triggers #{:close!}
          :reaction (fn [this]
                      (set! closing true)
                      (object/raise this :close)
                      (when closing
                        (close true))))

(behavior ::notify-init-window
          :triggers #{:init}
          :reaction (fn [this]
                      (ipc/send "initWindow" (window-number))))

(behavior ::store-position-on-close
          :triggers #{:closed :refresh}
          :reaction (fn [this]
                      (let [{:keys [size position fullScreen]} (window-state)]
                        (when-not fullScreen
                          (let [[width height] size]
                            (store! :width width)
                            (store! :height height))
                          (let [[x y] position]
                            (store! :x x)
                            (store! :y y)))
                        (set! js/localStorage.fullscreen fullScreen))))

(behavior ::restore-fullscreen
          :triggers #{:show}
          :reaction (fn [this]
                      (when (= js/localStorage.fullscreen "true")
                        (window-call! :setFullScreen true))))

(behavior ::restore-position-on-init
          :triggers #{:show}
          :reaction (fn [this]
                      (when js/localStorage.width
                        (window-call! :setSize (ensure-greater js/localStorage.width 400) (ensure-greater js/localStorage.height 400))
                        (window-call! :setPosition (ensure-greater js/localStorage.x 0) (ensure-greater js/localStorage.y 0)))))

(behavior ::on-show-bind-navigate
          :triggers #{:show}
          :reaction (fn [this]
                      (dom/on ($ :#canvas) :click (fn [^js e]
                                                    (when (and (= (.-target.nodeName e) "A")
                                                               (not (.-defaultPrevented e)))
                                                      (dom/prevent e)
                                                      (when-let [href (.-target.href e)]
                                                        (platform/open-url href)
                                                        (window-call! :focus)))))))

(behavior ::track-focus
          :triggers #{:focus :show}
          :reaction (fn [this]
                      (store! :focusedWindow (window-number))))

(behavior ::focus-class
          :triggers #{:focus :show}
          :reaction (fn [this]
                      (dom/add-class (dom/$ :body) :active)
                      (dom/remove-class (dom/$ :body) :inactive)))

(behavior ::blur-class
          :triggers #{:blur}
          :reaction (fn [this]
                      (dom/remove-class (dom/$ :body) :active)
                      (dom/add-class (dom/$ :body) :inactive)))

(defn run-commands [this & commands]
  (when (seq commands)
    (let [commands (if (-> commands first vector?)
                     (first commands)
                     commands)]
      (doseq [c commands]
        (if (coll? c)
          (apply cmd/exec! c)
          (cmd/exec! c))))))

(behavior ::run-pre-init
          :triggers #{:pre-init}
          :desc "App: Run commands before init"
          :params [{:label "commands"
                    :type :list
                    :items cmd/completions}]
          :type :user
          :reaction run-commands)

(behavior ::run-on-init
          :triggers #{:init}
          :desc "App: Run commands on init"
          :params [{:label "commands"
                    :type :list
                    :items cmd/completions}]
          :type :user
          :reaction run-commands)

(behavior ::run-post-init
          :triggers #{:post-init}
          :desc "App: Run commands after init"
          :params [{:label "commands"
                    :type :list
                    :items cmd/completions}]
          :type :user
          :reaction run-commands)

(behavior ::set-default-zoom-level
          :triggers #{:init}
          :desc "App: Set the default zoom level"
          :params [{:label "default-zoom-level"
                    :type :number}]
          :type :user
          :reaction (fn [this default]
                      (set! default-zoom default)
                      (cmd/exec! :window.zoom-reset)))

(behavior ::add-platform-class
          :triggers #{:init}
          :reaction (fn [this]
                      (dom/add-class (dom/$ :body) (name platform/platform))))

;;*********************************************************
;; Object
;;*********************************************************

(object/object* ::app
                :tags #{:app :window}
                :delays 0
                :init (fn [this]
                        (ctx/in! :app this)))

(def app (object/create ::app))

;; Handles events e.g. focus, blur and close
(ipc/on "app" #(object/raise app (keyword %2)))


;;*********************************************************
;; Commands
;;*********************************************************

(cmd/command {:command :window.new
              :desc "Window: Open new window"
              :exec (fn []
                      (let [w (open-window)]))})

(cmd/command {:command :window.close
              :desc "Window: Close window"
              :exec (fn []
                      (object/raise app :close!))})


(cmd/command {:command :window.zoom-in
              :desc "Window: Zoom in"
              :exec (fn []
                      (.setZoomFactor frame (+ (.getZoomFactor frame) 0.2)))})

(cmd/command {:command :window.zoom-out
              :desc "Window: Zoom out"
              :exec (fn []
                      (when (> (.getZoomFactor frame) 0)
                        (.setZoomFactor frame (- (.getZoomFactor frame) 0.2))))})

(cmd/command {:command :window.zoom-reset
              :desc "Window: Zoom reset"
              :exec (fn []
                      (.setZoomFactor frame default-zoom))})

(cmd/command {:command :window.fullscreen
              :desc "Window: Toggle fullscreen"
              :exec (fn []
                      (window-call! :setFullScreen (not (:fullScreen (window-state)))))})

(cmd/command {:command :window.minimize
              :desc "Window: Minimize"
              :exec (fn []
                      (window-call! :minimize))})

(cmd/command {:command :window.maximize
              :desc "Window: Maximize"
              :exec (fn []
                      (window-call! :maximize))})
