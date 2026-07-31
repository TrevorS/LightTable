(ns lt.objs.session
  "What was open, so that reopening Light Table reopens your work.

  The workspace already survived a restart — its folders come back, the tree is
  populated — and every tab did not. You were returned to a project with
  nothing open in it, which is a strange place to be put by an editor that
  remembered the project.

  This stores the open files, where the cursor was in each, and which one you
  were looking at, and puts them back. It is deliberately a small amount of
  state: a list of paths, a line and a character. Anything richer — split
  layout, scroll offset, folded regions — is state that can be wrong, and a
  session that restores something subtly different from what you left is worse
  than one that restores less.

  ## Where it is kept

  Beside the workspace it belongs to, in `session.clj` under the cache
  directory, and guarded by the workspace's folders: reopening files from a
  project you are no longer in would be the one genuinely bad failure here, so
  a session whose folders do not match the workspace that was restored is
  discarded rather than applied.

  ## When it is written

  On `:closed` and `:refresh`, which is the pair [[lt.objs.app]] already uses
  to persist window geometry — and, unlike geometry, also whenever a tab opens
  or closes, so that a session survives a crash rather than only a clean quit."
  (:require [cljs.reader :as reader]
            [lt.object :as object]
            [lt.objs.cache :as cache]
            [lt.objs.command :as cmd]
            [lt.objs.console :as console]
            [lt.objs.editor :as editor]
            [lt.objs.editor.pool :as pool]
            [lt.objs.files :as files]
            [lt.objs.tabs :as tabs]
            [lt.objs.workspace :as workspace])
  (:require-macros [lt.macros :refer [behavior]]))

(def session-path (files/join cache/cache-path "session.clj"))

;;*********************************************************
;; Taking a snapshot
;;*********************************************************

(defn- tab-order
  "Every open tab, in the order the tabs are drawn.

  Across tabsets, flattened. Splits are not restored — see the namespace
  docstring — so what matters here is that the order is stable and matches
  what you were looking at."
  []
  ;; (:tabsets @multi) is the tabset objects. tabs/->tabsets maps them to
  ;; their DOM content, which is what the renderer wants and not this.
  (mapcat #(:objs @%) (:tabsets @tabs/multi)))

(defn- ->entry
  "One tab, as the little that is worth remembering about it."
  [obj]
  (when-let [path (not-empty (tabs/->path obj))]
    (when (files/exists? path)
      (let [cursor (when (object/has-tag? obj :editor)
                     (try (editor/->cursor obj) (catch :default _ nil)))]
        (cond-> {:path path}
          cursor (assoc :line (:line cursor) :ch (:ch cursor)))))))

(defn snapshot
  "What is open right now, in the shape [[restore!]] reads."
  []
  (let [tabs (tab-order)
        open (vec (keep ->entry tabs))
        active (first (keep-indexed (fn [i obj]
                                      (when (some #(= obj (:active-obj @%))
                                                  (:tabsets @tabs/multi))
                                        i))
                                    tabs))]
    {:open open
     :active (or active 0)
     ;; What this session belongs to. Not decoration: it is what stops a
     ;; session being applied to a workspace it has nothing to do with.
     :folders (vec (:folders @workspace/current-ws))}))

;;*********************************************************
;; Storing it
;;*********************************************************

(defn store!
  "Write the current session out. Quiet on failure — this is a convenience,
  and an editor that cannot start because it could not remember your tabs
  would be a poor trade."
  []
  (try
    (let [session (snapshot)]
      ;; An empty session would overwrite a real one with nothing, which is
      ;; how you lose your tabs by opening a second window and closing it.
      (when (seq (:open session))
        (files/save session-path (pr-str session))))
    (catch :default e
      (console/log (str "Could not store the session: " e)))))

(defn stored
  "The session on disk, or nil when there is none to read."
  []
  (when (files/exists? session-path)
    (try
      (-> (files/open-sync session-path) :content reader/read-string)
      (catch :default e
        (console/log (str "Could not read the session: " e))
        nil))))

;;*********************************************************
;; Putting it back
;;*********************************************************

(defn- belongs-to-current-workspace?
  "Was this session taken in the workspace that has just been restored?

  Compared as sets, because the order folders were added in is not something
  the user is telling us about."
  [session]
  (= (set (:folders session))
     (set (:folders @workspace/current-ws))))

(defn restore!
  "Reopen what was open. Returns the number of files reopened."
  []
  (if-let [session (stored)]
    (if-not (belongs-to-current-workspace? session)
      0
      (let [open (filter #(files/exists? (:path %)) (:open session))]
        (doseq [{:keys [path]} open]
          (cmd/exec! :open-path path))
        ;; After the opens, because opening a file focuses it and the cursor
        ;; belongs to the editor that now exists.
        (doseq [{:keys [path line ch]} open
                :when line]
          (when-let [ed (first (pool/by-path path))]
            (editor/move-cursor ed {:line line :ch (or ch 0)})))
        (when-let [{:keys [path]} (nth open (:active session) nil)]
          (when-let [ed (first (pool/by-path path))]
            (tabs/active! ed)))
        (count open)))
    0))

;;*********************************************************
;; Behaviors
;;*********************************************************

(behavior ::store-session
          :triggers #{:closed :refresh}
          :desc "App: Remember which files are open"
          :type :user
          :reaction (fn [this]
                      (store!)))

(behavior ::store-session-on-tab-change
          ;; Raised on the tabset rather than the app, so this is a second
          ;; behavior rather than another trigger on the one above. It is what
          ;; makes a session survive a crash instead of only a clean quit.
          :triggers #{:tab.updated :tab.close :tab}
          :desc "Tabs: Remember which files are open as they change"
          :type :user
          :reaction (fn [this & _]
                      (store!)))

(behavior ::restore-session
          :triggers #{:post-init}
          :desc "App: Reopen the files that were open last time"
          :type :user
          :reaction (fn [this]
                      ;; After ::reconstitute-last-workspace, which is on the
                      ;; same trigger and declared before this one — the
                      ;; session is checked against the workspace's folders, so
                      ;; the workspace has to have been restored first.
                      (restore!)))

(cmd/command {:command :session.restore
              :desc "App: Reopen the files that were open last time"
              :exec (fn []
                      (let [n (restore!)]
                        (console/log (str "Reopened " n " file" (when (not= 1 n) "s")))))})
