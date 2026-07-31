(ns lt.objs.workspace
  "Provide workspace object and associated behaviors"
  (:require [lt.object :as object]
            [lt.objs.app :as app]
            [lt.objs.files :as files]
            [lt.util.bridge :as bridge]
            [lt.objs.command :as cmd]
            [lt.objs.cache :as cache]
            [lt.objs.notifos :as notifos]
            [lt.objs.console :as console]
            [clojure.set]
            [cljs.reader :as reader]
            [lt.util.load :as load]
            [lt.util.js :refer [now]]
            [lt.util.cljs])
  (:require-macros [lt.macros :refer [behavior]]))

;;*********************************************************
;; Watching
;; TODO: The way I did this is awful. Should get cleaned up
;;*********************************************************

(def max-depth 10)
(def watch-interval 1000)

(object/object* ::workspace
                :tags #{:workspace}
                :files []
                :folders []
                :watches {}
                :ws-behaviors ""
                :init (fn [this]
                        nil))

(def current-ws (object/create ::workspace))

(defn unwatch [watches path recursive?]
  (when watches
    (let [removes (cond
                    (coll? path) path
                    (not recursive?) [path]
                    :else (filter #(> (.indexOf % path) -1) (keys watches)))]
      (doseq [r (map watches removes)
              :when (and r (:close r))]
        ((:close r)))
      (apply dissoc watches removes))))

(defn unwatch!
  ([path] (unwatch! path false))
  ([path recursive?]
   (object/merge! current-ws {:watches (unwatch (:watches @current-ws) path recursive?)})))

(defn alert-file [path]
  (fn [stat]
    (if (.existsSync bridge/files path)
      (object/raise current-ws :watched.update path stat)
      (do
        (unwatch! path)
        (object/raise current-ws :watched.delete path)))))

;; A handle rather than a callback pair: fs keys unwatchFile on the identity of
;; the function passed to watchFile, and a function's identity does not survive
;; being proxied across a boundary. The bridge holds the real listener.
(defn file->watch [path]
  (let [handle (.watch bridge/files path watch-interval (alert-file path))]
    {:path path
     :close (fn [] (.close handle))}))

(declare folder->watch)

;; `results` is threaded through rather than mutated in place, and that is not
;; a style preference. `assoc!` on a transient map returns a *different* object
;; once it outgrows the array-map representation at eight entries. This walked
;; the tree with `doseq` and dropped the return, so a folder with more than
;; eight watchable paths — which is most projects — kept the first eight
;; watches and silently lost the rest, along with every change notification
;; they would have produced.
;;
;; The recursive call returns `results` for the same reason: the child level is
;; where the map is most likely to cross the threshold.
(defn watch!
  ([path] (watch! (transient {}) path nil))
  ([path recursive?] (watch! (transient {}) path recursive?))
  ([results path recursive?]
   (let [results
         (reduce
          (fn [results path]
            (cond
              (re-seq files/ignore-pattern path)
              results

              (files/dir? path)
              (let [depth (cond
                            (not recursive?) 0
                            (number? recursive?) (dec recursive?)
                            :else max-depth)
                    ;; Only create the watcher if it is going to be kept: this
                    ;; used to build one unconditionally and discard it when
                    ;; the path was already watched, leaving it running.
                    results (if (get (:watches @current-ws) path)
                              results
                              (assoc! results path (folder->watch path)))]
                (if (> depth -1)
                  (watch! results (files/full-path-ls path) depth)
                  results))

              (or (get (:watches @current-ws) path)
                  (get results path))
              results

              :else
              (assoc! results path (file->watch path))))
          results
          (if (coll? path) path [path]))]
     ;; A numeric `recursive?` means this is a level of someone else's walk, so
     ;; the transient is still being filled and must not be made persistent.
     (when-not (number? recursive?)
       (object/update! current-ws [:watches] merge (persistent! results)))
     results)))

(defn- children-of
  "The entries of `path` worth telling anyone about."
  [path]
  (set (remove #(re-seq files/ignore-pattern %) (files/full-path-ls path))))

(defn alert-folder
  "What to do when a watched directory changes.

  Against a remembered listing rather than against the watch table, which is
  what this compared before: it took the first child that was not being
  watched and announced it as new. Files inside an open folder are mostly not
  watched, so that was almost never the file that had just appeared — it was
  whichever ordinary file happened to sort first, which already existed, so
  the tree never learned about anything created in it. Creating a file in a
  folder you had open did nothing until you closed and reopened it.

  Diffing the listing also gets deletions of unwatched files for free, which
  the old shape could not see at all."
  [path known]
  (fn [_stat]
    (if (.existsSync bridge/files path)
      (let [now (children-of path)
            before @known]
        (reset! known now)
        (doseq [gone (clojure.set/difference before now)]
          (unwatch! gone :recursive)
          (object/raise current-ws :watched.delete gone))
        (doseq [neue (clojure.set/difference now before)]
          (watch! neue)
          (object/raise current-ws :watched.create neue (.statSync bridge/files neue))))
      (do
        (unwatch! path :recursive)
        (object/raise current-ws :watched.delete path)))))

(defn folder->watch [path]
  ;; The listing as it was when watching started, so the first change has
  ;; something to be a change from.
  (let [known (atom (children-of path))
        handle (.watch bridge/files path watch-interval (alert-folder path known))]
    {:path path
     :close (fn [] (.close handle))}))

(defn stop-watching [ws]
  (unwatch! (keys (:watches @ws))))

(defn watch-workspace [ws]
  (stop-watching ws)
  (watch! (object/raise-reduce ws :watch-paths+ [])))

;;*********************************************************
;; Files and folders
;;*********************************************************

(def workspace-cache-path (files/join cache/cache-path "workspace"))

(defn files-and-folders [path]
  (reduce (fn [res cur]
            (let [dir? (files/dir? cur)]
              (if (re-seq files/ignore-pattern (str (files/basename cur) (when dir? files/separator)))
                res
                (if dir?
                  (update-in res [:folders] conj cur)
                  (update-in res [:files] conj cur)))))
          {:folders []
           :files []}
          (files/full-path-ls path)))

(defn serialize [ws]
  (select-keys ws [:files :folders :ws-behaviors]))

(defn reconstitute [ws v]
  (object/raise ws :set! {:files (:files v)
                          :folders (filter files/exists? (:folders v))
                          :ws-behaviors (:ws-behaviors v)}))

(defn add! [ws k v]
  (object/update! ws [k] conj v))

(defn remove! [ws k v]
  (object/update! ws [k] #(vec (remove #{v} %))))

(defn new-cached-file []
  (str (now) ".clj"))

(defn file->ws [file]
  (-> (files/open-sync file)
      (:content)
      (reader/read-string)
      (assoc :path file)))

(defn save [ws file]
  (files/save (files/join workspace-cache-path file) (pr-str (serialize @ws)))
  (object/raise ws :save))

(defn open [ws file]
  (let [loc (if-not (> (.indexOf file files/separator) -1)
              (files/join workspace-cache-path file)
              file)]
    (object/merge! ws {:file (new-cached-file)})
    (try
      (reconstitute ws (file->ws loc))
      (save ws (:file @ws))
      (files/delete! loc)
      (catch :default e
        (console/error e)))))

(defn cached []
  (filter #(> (.indexOf % ".clj") -1) (files/full-path-ls workspace-cache-path)))

(defn all []
  (let [fs (sort > (cached))]
    ;;if there are more than 20, delete the extras
    (doseq [file (drop 20 fs)]
      (files/delete! file))
    (map file->ws (take 20 fs))))

(defn ws-empty? [ws]
  (not (or (seq (:files @ws))
           (seq (:folders @ws)))))

(behavior ::serialize-workspace
          :triggers #{:updated :serialize!}
          :reaction (fn [this]
                      (when-not (@this :file)
                        (object/merge! this {:file (new-cached-file)}))
                      (when (and (@this :initialized?)
                                 (not (ws-empty? this)))
                        (save this (:file @this)))))

(behavior ::reconstitute-last-workspace
          :triggers #{:post-init}
          :reaction (fn [app]
                      (when (and (app/first-window?)
                                 (not (:initialized @current-ws)))
                        (when-let [ws (first (all))]
                          (open current-ws (-> ws :path (files/basename))))) ;;for backwards compat
                      (object/merge! current-ws {:initialized? true})))

(behavior ::new!
          :triggers #{:new!}
          :reaction (fn [this]
                      (object/merge! this {:file (new-cached-file)})
                      (object/raise this :clear!)))

(behavior ::add-file!
          :triggers #{:add.file!}
          :reaction (fn [this f]
                      (if-not (contains? (set (:files @this)) f)
                        (do
                          (add! this :files f)
                          (object/raise this :add f)
                          (object/raise this :updated))
                        (notifos/set-msg! "This file is already in your workspace." {:class "error"}))))

(behavior ::add-folder!
          :triggers #{:add.folder!}
          :reaction (fn [this f]
                      (if-not (contains? (set (:folders @this)) f)
                        (do
                          (add! this :folders f)
                          (object/raise this :add f)
                          (object/raise this :updated))
                        (notifos/set-msg! "This folder is already in your workspace." {:class "error"}))))

(behavior ::remove-file!
          :triggers #{:remove.file!}
          :reaction (fn [this f]
                      (remove! this :files f)
                      (object/raise this :remove f)
                      (object/raise this :updated)))

(behavior ::remove-folder!
          :triggers #{:remove.folder!}
          :reaction (fn [this f]
                      (remove! this :folders f)
                      (object/raise this :remove f)
                      (object/raise this :updated)))

(behavior ::rename!
          :triggers #{:rename!}
          :reaction (fn [this f neue]
                      (let [key (if (files/file? f)
                                  :files
                                  :folders)]
                        (remove! this key f)
                        (add! this key neue)
                        (object/raise this :rename f neue)
                        (object/raise this :updated))))

(behavior ::clear!
          :triggers #{:clear!}
          :reaction (fn [this]
                      (let [old @this]
                        (object/merge! this {:files []
                                             :folders []
                                             :ws-behaviors ""})
                        (object/raise this :set old)
                        (object/raise this :updated))))

(behavior ::set!
          :triggers #{:set!}
          :reaction (fn [this fs]
                      (let [old @this]
                        (object/merge! this fs)
                        (object/raise this :set old)
                        (object/raise this :updated))))

(behavior ::watch-on-set
          :triggers #{:set}
          :reaction (fn [this]
                      (watch-workspace this)))

(behavior ::stop-watch-on-close
          :triggers #{:close :refresh}
          :reaction (fn [app]
                      (stop-watching current-ws)))

(behavior ::init-workspace-cache-dir
          :triggers #{:init}
          :reaction (fn [app]
                      (when-not (files/exists? workspace-cache-path)
                        (files/mkdir workspace-cache-path))))

(cmd/command {:command :workspace.new
              :desc "Workspace: Create new workspace"
              :exec (fn []
                      (object/raise current-ws :new!)
                      )})
