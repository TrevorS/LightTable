(ns lt.objs.sidebar.workspace
  "The file tree, which is a view.

  It was an object per file and an object per folder — 677 lines, three object
  types, twenty-eight behaviors, and a `ul` per directory with the closed ones
  hidden in CSS. Opening a folder of four hundred files created four hundred
  objects, each with a node, each with a `bound` watch, and closing it again
  kept all of them.

  Now the tree is data: `[:workspace :nodes]` in [[lt.state]] is a map from
  path to what is known about that path, and `lt.ui.view/workspace` draws the
  rows that are visible. Opening a folder is one `assoc-in`, and a folder that
  is closed is one that is not descended into rather than one whose DOM is
  hidden.

  What is left in this namespace is what talks to the disk: reading a
  directory, renaming, deleting, the menus, and keeping the state in step with
  [[lt.objs.workspace]], which still owns which folders are in the workspace.
  Those are effects, registered here rather than in [[lt.actions.effects]]
  because this is where the file system already is.

  The panel itself is still an object, for the same reason the statusbar strip
  is: [[lt.objs.sidebar]] holds its node, moves it, and sizes itself against
  it."
  (:require [clojure.string :as string]
            [lt.actions :as actions]
            [lt.object :as object]
            [lt.objs.command :as cmd]
            [lt.objs.dialogs :as dialogs]
            [lt.objs.document :as document]
            [lt.objs.files :as files]
            [lt.objs.menu :as menu]
            [lt.objs.popup :as popup]
            [lt.objs.sidebar :as sidebar]
            [lt.objs.workspace :as workspace]
            [lt.state :as state]
            [lt.ui :as ui]
            [lt.ui.view :as view]
            [lt.util.dom :as dom])
  (:require-macros [lt.macros :refer [behavior]]))

(declare sidebar-workspace)

(defn- dispatch! [& actions] (actions/dispatch! (vec actions)))

;;*********************************************************
;; Reading the disk
;;*********************************************************

(defn children
  "What is in `path`, as `[path dir?]` pairs in the order they are drawn.

  Folders before files and each set by name, ignoring case. Sorted here rather
  than in the view because which order to show them in is a decision, and the
  view is not where decisions go — it is also the only place that has to know
  the answer, so nothing sorts twice."
  [path]
  (let [{:keys [folders files]} (workspace/files-and-folders path)
        by-name #(string/lower-case (files/basename %))]
    (vec (concat (for [f (sort-by by-name folders)] [f true])
                 (for [f (sort-by by-name files)] [f false])))))

(defn open-dirs
  "Every folder the tree has open. What [[lt.objs.session]] remembers, and what
  the workspace watches."
  []
  (for [[path node] (get-in @state/app [:workspace :nodes])
        :when (and (:dir? node) (:open? node))]
    path))

(defn expand!
  "Open `path` in the tree, if it is not already.

  For restoring a session. Shallowest first, because a folder is only known to
  be a folder once the one above it has been read."
  [path]
  (when-not (get-in @state/app [:workspace :nodes path :open?])
    (dispatch! [:tree/toggle path])))

(actions/register-effect! :tree/read
                          (fn [path]
                            ;; Watched so that what happens to the folder while
                            ;; it is open reaches the tree. Only open folders
                            ;; are watched, which is what `:watch-paths+` below
                            ;; reports.
                            (workspace/watch! path)
                            (dispatch! [:tree/loaded path (children path)])))

;;*********************************************************
;; Changing what is on disk
;;*********************************************************

(defn- rename!
  "Move `path` to `name-of` beside it, and tell everything that held the old one.

  The awkward part is real and was here before: an open document is keyed by
  its path, so renaming a folder has to move every document underneath it."
  [path name-of]
  (let [neue (files/join (files/parent path) name-of)
        root? (contains? (set (concat (:files @workspace/current-ws)
                                      (:folders @workspace/current-ws)))
                         path)]
    (when-not (= path neue)
      (if (and (not= (string/lower-case path) (string/lower-case neue))
               (files/exists? neue))
        ;; Case matters to `move!` and not to `exists?` on macOS, which is why
        ;; the comparison is lowered before it is asked.
        (popup/popup! {:header "That name is taken."
                       :body (str neue " already exists, so this one needs a different name.")
                       :buttons [{:label "ok"
                                  :post-action (fn [] (dispatch! [:tree/rename-start path]))}]})
        (do
          (files/move! path neue)
          (when (files/dir? neue)
            (let [under (str path files/separator)]
              (doseq [doc (filter #(string/starts-with? % under)
                                  (keys (get @document/manager :files)))]
                (document/move-doc doc (string/replace-first doc path neue)))))
          (if root?
            (object/raise workspace/current-ws :rename! path neue)
            (object/raise workspace/current-ws :watched.rename path neue))
          (dispatch! [:tree/changed (files/parent path)]))))))

(actions/register-effect! :file/rename rename!)

(actions/register-effect! :tree/new-file
                          (fn [dir]
                            (let [path (files/next-available-name
                                        (files/join dir "untitled.txt"))]
                              (files/save path "")
                              (dispatch! [:tree/toggle dir]
                                         [:tree/changed dir]
                                         [:file/open path]
                                         [:tree/rename-start path]))))

(actions/register-effect! :tree/new-folder
                          (fn [dir]
                            (let [path (files/next-available-name
                                        (files/join dir "NewFolder"))]
                              (files/mkdir path)
                              (dispatch! [:tree/changed dir]
                                         [:tree/rename-start path]))))

(actions/register-effect! :tree/duplicate
                          (fn [path]
                            (let [base (files/without-ext (files/basename path))
                                  neue (files/join (files/parent path)
                                                   (str base " copy." (files/ext path)))]
                              (files/copy path neue)
                              (dispatch! [:tree/changed (files/parent path)]))))

(actions/register-effect! :tree/delete
                          (fn [path]
                            (let [dir? (files/dir? path)]
                              (popup/popup!
                               {:header (if dir? "Delete this folder?" "Delete this file?")
                                :body (str "This will delete " path
                                           " from disk and cannot be undone.")
                                :buttons [{:label (if dir? "Delete folder" "Delete file")
                                           :action (fn []
                                                     (files/delete! path)
                                                     (object/raise workspace/current-ws :watched.delete path)
                                                     (dispatch! [:tree/changed (files/parent path)]))}
                                          popup/cancel-button]}))))

(actions/register-effect! :tree/refresh
                          (fn [path]
                            (dispatch! [:tree/loaded path (children path)])))

(actions/register-effect! :tree/remove-root
                          (fn [path]
                            (object/raise workspace/current-ws
                                          (if (files/dir? path) :remove.folder! :remove.file!)
                                          path)))

;;*********************************************************
;; The workspace the tree is of
;;*********************************************************

(defn- roots! []
  (dispatch! [:tree/roots
              (vec (:folders @workspace/current-ws))
              (vec (:files @workspace/current-ws))]))

(actions/register-effect! :workspace/add-folder
                          (fn [] (dialogs/dir workspace/current-ws :add.folder!)))

(actions/register-effect! :workspace/add-file
                          (fn [] (dialogs/file workspace/current-ws :add.file!)))

(actions/register-effect! :workspace/read-recents
                          (fn []
                            (dispatch! [:workspace/recents-loaded
                                        (for [w (workspace/all)]
                                          (select-keys w [:path :folders :files]))])))

(actions/register-effect! :workspace/open
                          (fn [path]
                            (workspace/open workspace/current-ws path)
                            (dispatch! [:workspace/show-tree])))

(actions/register-effect! :workspace/clear
                          (fn [] (object/raise workspace/current-ws :clear!)))

;;*********************************************************
;; Menus
;;*********************************************************

;; Still `raise-reduce`, so a plugin can still add an item — with a path rather
;; than an object as the thing the item is about, which is the whole change.
;; There is no object per row any more to hang a tag on, and a path is what a
;; menu item wanted from one anyway.

(behavior ::tree-menu-items
          :triggers #{:tree-menu-items}
          :desc "Workspace: The right-click menu for a row in the tree"
          :reaction (fn [this items path]
                      (let [dir? (files/dir? path)
                            root? (contains? (set (concat (:files @workspace/current-ws)
                                                          (:folders @workspace/current-ws)))
                                             path)]
                        (concat items
                                (when dir?
                                  [{:label "New file" :order 0
                                    :click #(dispatch! [:tree/new-file path])}
                                   {:label "New folder" :order 1
                                    :click #(dispatch! [:tree/new-folder path])}])
                                (when-not dir?
                                  [{:label "Duplicate" :order 2
                                    :click #(dispatch! [:tree/duplicate path])}])
                                [{:label "Rename" :order 3
                                  :click #(dispatch! [:tree/rename-start path])}
                                 {:label (if dir? "Delete folder" "Delete") :order 4
                                  :click #(dispatch! [:tree/delete path])}]
                                (when dir?
                                  [{:label "Refresh folder" :order 5
                                    :click #(dispatch! [:tree/refresh path])}])
                                (when root?
                                  [{:type "separator" :order 9}
                                   {:label "Remove from workspace" :order 10
                                    :click #(dispatch! [:tree/remove-root path])}])))))

(behavior ::sidebar-menu
          :triggers #{:menu-items}
          :reaction (fn [this items]
                      (conj items
                            {:label "Add folder" :click #(cmd/exec! :workspace.add-folder)}
                            {:label "Add file" :click #(cmd/exec! :workspace.add-file)}
                            {:label "Open recent workspace" :click #(cmd/exec! :workspace.show-recents)}
                            {:type "separator"}
                            {:label "Clear workspace" :click #(dispatch! [:workspace/clear])})))

(defn- show-menu! [items]
  (-> (menu/menu (sort-by :order items)) (menu/show-menu)))

(actions/register-effect! :tree/menu
                          (fn [path]
                            (show-menu! (object/raise-reduce sidebar-workspace
                                                             :tree-menu-items [] path))))

(actions/register-effect! :workspace/menu
                          (fn []
                            (show-menu! (object/raise-reduce sidebar-workspace :menu-items []))))

;;*********************************************************
;; Keeping the state in step with the workspace object
;;*********************************************************

;; [[lt.objs.workspace]] still owns which folders and files are in the
;; workspace — it serializes them, it watches them, it is what a session
;; restores. So this is a projection like [[lt.state.objects]] is, and it goes
;; the same way: one direction, no second copy.

(behavior ::on-ws-set
          :triggers #{:set}
          :reaction (fn [_ & _] (roots!)))

(behavior ::on-ws-add
          :triggers #{:add}
          :reaction (fn [_ & _] (roots!)))

(behavior ::on-ws-remove
          :triggers #{:remove}
          :reaction (fn [_ & _] (roots!)))

(behavior ::on-ws-rename
          :triggers #{:rename}
          :reaction (fn [_ & _] (roots!)))

(behavior ::watched.create
          :triggers #{:watched.create}
          :reaction (fn [_ path]
                      (dispatch! [:tree/changed (files/parent path)])))

(behavior ::watched.delete
          :triggers #{:watched.delete}
          :reaction (fn [_ path]
                      (dispatch! [:tree/changed (files/parent path)])))

(behavior ::watch-open-dirs-paths
          :triggers #{:watch-paths+}
          :reaction (fn [_ cur]
                      ;; Exactly the folders you can see into, which is the set
                      ;; worth being told about.
                      (concat cur (open-dirs))))

;;*********************************************************
;; The panel
;;*********************************************************

(defn- panel-ui []
  (view/workspace @state/app))

(defn- dropped-paths
  "The paths in a drop, which is the one thing a view cannot be handed.

  A `DataTransfer` is not data — it is live, it is only readable during the
  event, and there is no argument to an action that could carry it. So the
  listener is on the panel's own node, outside what Replicant draws."
  [^js e]
  (let [fs (.. e -dataTransfer -files)]
    (for [i (range (.-length fs))]
      (.-path (aget fs i)))))

(object/object* ::sidebar.workspace
                :tags #{:sidebar.workspace}
                :label "workspace"
                :order -7
                :init (fn [this]
                        (let [el (ui/state-node this [:div.workspace] panel-ui [state/app])]
                          (dom/on el :contextmenu (fn [_] (dispatch! [:workspace/menu])))
                          (dom/on el :dragover (fn [e]
                                                 (set! (.. ^js e -dataTransfer -dropEffect) "move")
                                                 (dom/prevent e)
                                                 false))
                          (dom/on el :drop (fn [e]
                                             (doseq [path (dropped-paths e)]
                                               (object/raise workspace/current-ws
                                                             (if (files/dir? path)
                                                               :add.folder!
                                                               :add.file!)
                                                             path))
                                             (dom/stop-propagation e)
                                             (dom/prevent e)))
                          el)))

(def sidebar-workspace (object/create ::sidebar.workspace))

(sidebar/add-item sidebar/sidebar sidebar-workspace)

(behavior ::workspace.open-on-start
          :triggers #{:init}
          :type :user
          :desc "Workspace: Show workspace on start"
          :reaction (fn [this]
                      (cmd/exec! :workspace.show)))

;;*********************************************************
;; Commands
;;*********************************************************

(cmd/command {:command :workspace.add-folder
              :desc "Workspace: add folder"
              :exec (fn [] (dispatch! [:workspace/add-folder]))})

(cmd/command {:command :workspace.add-file
              :desc "Workspace: add file"
              :exec (fn [] (dispatch! [:workspace/add-file]))})

(cmd/command {:command :workspace.show
              :desc "Workspace: Toggle workspace tree"
              :exec (fn [force?]
                      (object/raise sidebar/sidebar :toggle sidebar-workspace
                                    {:transient? false :force? force?}))})

(cmd/command {:command :workspace.show-recents
              :desc "Workspace: Open recent workspace"
              :exec (fn []
                      (cmd/exec! :workspace.show :force)
                      (dispatch! [:workspace/show-recents]))})

(cmd/command {:command :workspace.rename.cancel!
              :desc "Workspace: Cancel rename"
              :hidden true
              :exec (fn [] (dispatch! [:tree/rename-cancel]))})

(cmd/command {:command :workspace.rename.submit!
              :desc "Workspace: Submit rename"
              :hidden true
              :exec (fn []
                      ;; Blurring is what submits, so `enter` does that rather
                      ;; than reading the input a second way. One path out.
                      (when-let [input (dom/$ :input.tree__rename
                                              (object/->content sidebar-workspace))]
                        (.blur input)))})
