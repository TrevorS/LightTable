(ns lt.objs.document
  "What Light Table knows about an open file, apart from the editor showing it.

  A document is a path's identity: the text it was opened with, its line
  ending, its MIME type, and the modification time a save has to check against.
  The [[manager]] keeps one per path, which is how a rename can follow a file
  and a save can tell that something else wrote to it first.

  It used to be a CodeMirror 5 `Doc` — a buffer object that an editor could be
  pointed at, and that *two* editors could be pointed at, which is how opening
  the same file twice gave you two views of one document. Nothing in CodeMirror
  6 is that object; the equivalent is two views over one `EditorState`, which is
  a better arrangement and one nobody has built here yet. So a linked document
  opens an ordinary second editor and says so, rather than appearing to be
  linked and quietly not being."
  (:require [lt.object :as object]
            [lt.objs.files :as files]
            [lt.objs.notifos :as notifos]
            [lt.objs.popup :as popup])
  (:require-macros [lt.macros :refer [behavior defui]])
  (:refer-clojure :exclude [replace]))


;;***************************************************
;; Document
;;***************************************************

(object/object* ::document
                :tags #{:document}
                ;; `:text`, and not `:content`: an object's `:content` is its
                ;; DOM, which `object/->content` hands out. A document holding
                ;; the file's text under that name is a document whose element
                ;; is a string, and what that looks like is an editor that
                ;; opens empty.
                :init (fn [this info]
                        (object/merge! this info)
                        nil))

(defn create [info]
  (object/create ::document info))

(defn ->val
  "The text this document was opened with.

  Not what the editor currently shows: an editor is the live copy, and asking it
  is `lt.objs.editor/->val`. This is what was read from disk."
  [doc]
  (:text @doc))

(defn set-val [doc v]
  (object/merge! doc {:text v}))

(behavior ::close-document-on-editor-close
          :for #{:editor}
          :triggers #{:closed}
          :reaction (fn [editor]
                      (when-let [doc (:doc @editor)]
                        (object/raise doc :close.force))))

(behavior ::close-root-document
          :for #{:document}
          :triggers #{:close.force}
          :reaction (fn [this]
                      (object/destroy! this)))


;;***************************************************
;; Manager
;;***************************************************

(declare manager)

(defn register-doc [doc path]
  (object/update! manager [:files] assoc path doc))

(defn open [path cb]
  (files/open path (fn [data]
                     (let [d (create {:text (:content data)
                                      :line-ending (:line-ending data)
                                      :mtime (files/stats path)
                                      :mime (:type data)})]
                       (register-doc d path)
                       (when cb
                         (cb d))))))

(defn linked-open
  "Open `path` again, in an editor of its own.

  Named `linked` because it used to be: a CodeMirror 5 linked document is a
  second view of one buffer, and typing in either showed up in both. This opens
  a second document instead, and says so once — an editor that looks linked and
  is not would let you lose work in the one you were not looking at."
  [_ed _options path cb]
  (notifos/set-msg! "Opened a second copy: linked documents are not available.")
  (open path cb))

(defn check-mtime
  "Whether two stats describe the same version of a file.

  `mtimeMs`, and not `mtime`. A stat crossing the preload boundary is plain
  data: an `fs.Stats` keeps its timestamps on prototype accessors, a structured
  clone keeps only own properties, and so `.mtime` arrives undefined. Reading
  `.getTime` off that threw — inside a behavior reaction, which `lt.object`
  catches — so a save aborted before writing anything and said so only in the
  console, and an edit made outside the editor was never noticed."
  [prev updated]
  (if (and prev updated)
    (= (.-mtimeMs ^js prev) (.-mtimeMs ^js updated))
    true))

(defui button [label & [cb]]
       [:div.button.right label]
       :click (fn []
                (when cb
                  (cb))))

(defn overwrite-warn [cb]
  (popup/popup! {:header "This file was modified."
                 :body [:p "It looks like this file was modified outside of Light Table and saving
                  would overwrite those changes. Do you want to overwrite or cancel?"]
                 :buttons [{:label "Overwrite file"
                            :action cb}
                           {:label "Cancel"}]}))

(defn path->doc [path]
  (-> @manager :files (get path)))

(defn ->stats [path]
  (-> (path->doc path) deref :mtime))

(defn update-stats [path]
  (object/merge! (get-in @manager [:files path]) {:mtime (files/stats path)}))

(defn move-doc [old neue]
  (when-let [old-d (path->doc old)]
    (object/update! manager [:files] assoc neue old-d)
    (object/update! manager [:files] dissoc old)
    (update-stats neue)))

(defn save* [path content cb]
  (files/save path content (fn [data]
                             (update-stats path)
                             (when cb
                             	(cb data)))))

(defn save [path content cb]
  (let [updated (files/stats path)
        safe? (check-mtime (->stats path) updated)]
    (if-not safe?
      (overwrite-warn #(save* path content cb))
      (save* path content cb))))


(object/object* ::doc-manager
                :triggers []
                :behaviors []
                :files {}
                :init (fn []
                        ))

(def manager (object/create ::doc-manager))
