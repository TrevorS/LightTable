(ns lt.objs.menu
  "Provide Electron-based menus and associated behaviors"
  (:require [lt.object :as object]
            [lt.objs.command :as cmd]
            [lt.objs.keyboard :as keyboard]
            [lt.objs.platform :as platform]
            [lt.objs.repo :as repo]
            [lt.objs.app :as app]
            [lt.util.dom :as dom]
            [lt.util.bridge :as bridge]
            [clojure.string :as string])
  (:require-macros [lt.macros :refer [behavior]]))

;; Menu and MenuItem live in the browser process, and `remote`, which used to
;; reach them, was removed in Electron v14. Menus are therefore described as
;; plain data and built over there.
;;
;; :click handlers stay here rather than crossing the boundary: each clickable
;; item is given a token, the browser process reports the token that was
;; clicked, and the handler runs in this process as it always did. Callers and
;; plugins keep passing ordinary closures.

(def ^:private app-handlers
  "Tokens for the application menubar, which lives as long as the window."
  (atom {}))

(def ^:private popup-handlers
  "Tokens for the context menu currently on screen, replaced each time one opens."
  (atom {}))

(def ^:private ^:dynamic *handlers* popup-handlers)

(def ^:private token-seq (atom 0))

(defn- register-click!
  "Store `f` against a fresh token and return the token."
  [f]
  (let [token (swap! token-seq inc)]
    (swap! *handlers* assoc token f)
    token))

(def ^:private item-keys
  "MenuItem options worth forwarding. Callers attach their own keys too — :order,
  for one, which is only used for sorting here — and Electron has no use for them."
  [:label :sublabel :toolTip :role :type :accelerator :enabled :visible :checked])

(defn menu-item
  "Describe a single menu item. Returns data for the browser process to build,
  with any :click swapped for a token."
  [opts]
  (when opts
    (cond-> (select-keys opts item-keys)
      (:click opts) (assoc :token (register-click! (:click opts)))
      (:submenu opts) (assoc :submenu (mapv menu-item (remove nil? (:submenu opts)))))))

(defn submenu
  "Describe a list of items nested under a parent item."
  [items]
  (mapv menu-item (remove nil? items)))

(defn menu
  "Describe a context menu. Returns data; pass it to [[show-menu]] to display."
  [items]
  (binding [*handlers* popup-handlers]
    (reset! popup-handlers {})
    (mapv menu-item (remove nil? items))))

(defn show-menu [m]
  (.popup bridge/menu (clj->js m)))

(.onClick bridge/menu
          (fn [token]
            (when-let [handler (or (@popup-handlers token) (@app-handlers token))]
              (try
                (handler)
                (catch :default e
                  (js/lt.objs.console.error e))))))

(dom/on (dom/$ :body) :contextmenu (fn [e]
                                     (dom/prevent e)
                                     (dom/stop-propagation e)
                                     false))

(defn set-menubar [items]
  (binding [*handlers* app-handlers]
    (reset! app-handlers {})
    (.setApplicationMenu bridge/menu (clj->js (mapv menu-item (remove nil? items))))))

(def key-mappings {"cmd" "Command"
                   "shift" "Shift"
                   "ctrl" "Control"
                   "alt" "Alt"})

(defn command->menu-binding [cmd]
  (let [ks (first (keyboard/cmd->current-binding cmd))
        parts (string/split ks " ")
        parts (for [part parts
                      :let [ks (for [key (string/split part "-")]
                                 (or (key-mappings key) key))]]
                (string/join "+" ks))]
    ;;OSX can only take single key accelerators
    (when (and (seq parts)
               (or (not (platform/mac?))
                   (= (count parts) 1)))
      {:accelerator (string/join " " parts)})))

(defn cmd-item
  ([label cmd] (cmd-item label cmd {}))
  ([label cmd opts]
   (merge
    {:label label
     :click (when-not (:role opts)
              (fn [] (cmd/exec! cmd)))}
    opts
    (command->menu-binding cmd))))

(defn unknown-menu []
  (set-menubar [(when (platform/mac?)
                  {:label "" :submenu [(cmd-item "About Light Table" :version)
                                       {:type "separator"}
                                       {:label "Hide Light Table" :accelerator "Command+H" :role "hide"}
                                       {:label "Hide Others" :accelerator "Command+Alt+H" :role "hideothers"}
                                       {:type "separator"}
                                       (cmd-item "Quit Light Table" :quit {:accelerator "Command+Q"})]})

                {:label "Edit" :submenu [(cmd-item "Undo" :editor.undo {:role "undo" :accelerator "CommandOrControl+Z"})
                                         (cmd-item "Redo" :editor.redo {:role "redo" :accelerator "Command+Shift+Z"})
                                         {:type "separator"}
                                         (cmd-item "Cut" :editor.cut {:role "cut" :accelerator "CommandOrControl+X"})
                                         (cmd-item "Copy" :editor.copy {:role "copy" :accelerator "CommandOrControl+C"})
                                         (cmd-item "Paste" :editor.paste {:role "paste" :accelerator "CommandOrControl+V"})
                                         (cmd-item "Select All" :editor.select-all {:role "selectall" :accelerator "CommandOrControl+A"})
                                         ]}

                {:label "Window" :submenu [(cmd-item "Minimize" :window.minimize {:role "minimize" :accelerator "Command+M"})
                                           (cmd-item "Close window" :window.close {:role "close" :accelerator "Command+W"})]}

                {:label "Help" :submenu []}]))

(defn main-menu []
  (set-menubar [(when (platform/mac?)
                  {:label "" :submenu [(cmd-item "About Light Table" :version)
                                       {:type "separator"}
                                       {:label "Hide Light Table" :accelerator "Command+H" :role "hide"}
                                       {:label "Hide Others" :accelerator "Command+Alt+H" :role "hideothers"}
                                       {:type "separator"}
                                       (cmd-item "Quit Light Table" :quit {:accelerator "Command+Q"})]})

                {:label "&File" :submenu (into [(cmd-item "New file" :new-file)
                                                (cmd-item "Open file" :open-file)
                                                {:label "Open folder" :click #(do
                                                                                (cmd/exec! :workspace.show :force)
                                                                                (cmd/exec! :workspace.add-folder))}
                                                (cmd-item "Open recent workspace" :workspace.show-recents {})
                                                (cmd-item "Save file" :save)
                                                (cmd-item "Save file as.." :save-as)
                                                (cmd-item "Close file" :tabs.close)
                                                {:label "Settings" :submenu [(cmd-item "User keymap" :keymap.modify-user)
                                                                             (cmd-item "User behaviors" :behaviors.modify-user)
                                                                             (cmd-item "User script" :user.modify-user)]}
                                                {:type "separator"}
                                                (cmd-item "New window" :window.new)
                                                (cmd-item "Close window" :window.close)]
                                               (when-not (platform/mac?)
                                                 [{:type "separator"}
                                                  (cmd-item "About Light Table" :version)
                                                  (cmd-item "Quit Light Table" :quit {:accelerator "Control+Q"})]))}

                (if (platform/mac?)
                  {:label "Edit" :submenu [(cmd-item "Undo" :editor.undo {:role "undo" :accelerator "CommandOrControl+Z"})
                                           (cmd-item "Redo" :editor.redo {:role "redo" :accelerator "CommandOrControl+Shift+Z"})
                                           {:type "separator"}
                                           (cmd-item "Cut" :editor.cut {:role "cut" :accelerator "CommandOrControl+X"})
                                           (cmd-item "Copy" :editor.copy {:role "copy" :accelerator "CommandOrControl+C"})
                                           (cmd-item "Paste" :editor.paste {:role "paste" :accelerator "CommandOrControl+V"})
                                           (cmd-item "Select All" :editor.select-all {:role "selectall" :accelerator "CommandOrControl+A"})]}
                  {:label "&Edit" :submenu [(cmd-item "Undo" :editor.undo)
                                            (cmd-item "Redo" :editor.redo)
                                            {:type "separator"}
                                            (cmd-item "Cut" :editor.cut)
                                            (cmd-item "Copy" :editor.copy)
                                            (cmd-item "Paste" :editor.paste)
                                            (cmd-item "Select All" :editor.select-all)]})

                {:label "&View" :submenu [(cmd-item "Workspace" :workspace.show)
                                          (cmd-item "Connections" :show-connect)
                                          (cmd-item "Navigator" :navigate-workspace-transient)
                                          (cmd-item "Commands" :show-commandbar-transient)
                                          (cmd-item "Plugin Manager" :plugin-manager.show)
                                          {:type "separator"}
                                          (cmd-item "Language docs" :docs.search.show)
                                          {:type "separator"}
                                          (cmd-item "Console" :toggle-console)
                                          (cmd-item "Developer Tools" :dev-inspector)]}

                {:label "&Window" :submenu [(cmd-item "Minimize" :window.minimize)
                                            (cmd-item "Maximize" :window.maximize)
                                            (cmd-item "Fullscreen" :window.fullscreen)]}

                {:label "&Help" :submenu [(cmd-item "Documentation" :show-docs)
                                          {:label "Report an Issue" :click #(do
                                                                              (cmd/exec! :add-browser-tab (repo/at "issues")))}
                                          (when-not (platform/mac?)
                                            (cmd-item "About Light Table" :version))]}]))

(behavior ::create-menu
          :triggers #{:init}
          :reaction (fn [this]
                      ;; Dropped a `(set! (.-Menu win) nil)` here: BrowserWindow has
                      ;; no Menu property, so it was a leftover from the NW.js days.
                      (main-menu)))

(behavior ::recreate-menu
          :debounce 20
          :triggers #{:app.keys.load}
          :reaction (fn [app]
                      (when (platform/mac?)
                        (main-menu))))

(behavior ::set-menu
          :triggers #{:focus}
          :reaction (fn [this]
                      (when (platform/mac?)
                        (main-menu))))

(behavior ::remove-menu-close
          :triggers #{:closed :blur}
          :reaction (fn [this]
                      (when (platform/mac?)
                        (unknown-menu))))

(behavior ::menu!
          :triggers #{:menu!}
          :reaction (fn [this e]
                      (let [items (sort-by :order (filter identity (object/raise-reduce this :menu+ [] e)))]
                        (-> (menu items)
                            (show-menu)))
                      (dom/prevent e)
                      (dom/stop-propagation e)))
