(ns lt.ui.pane
  "An editor, hosted inside hiccup.

  The fourth kind in the design's split, and the last mechanism it names:
  *foreign DOM we must never diff*. An editor manages its own DOM, holds the
  selection, the undo history and the mode state, so a renderer that thought it
  owned those nodes would throw them away on the next render.

  So the hiccup describes an empty element and a mount hook, and Replicant
  never looks inside. `:replicant/key` is the path, which means an editor
  survives the list around it being rebuilt — and that is not a nicety: without
  it, scrolling a multibuffer would destroy and recreate a CodeMirror per
  excerpt, losing the cursor every time.

  Instances are **not** in the state atom. They are not data."
  (:require [lt.object :as object]
            [lt.objs.editor :as editor]
            [lt.objs.editor.pool :as pool]
            [lt.objs.files :as files]
            [lt.objs.opener :as opener]
            [lt.util.dom :as dom]))

(defonce ^:private instances (atom {}))

(declare mount-editor!)

(defn mounted
  "Every path that currently has an editor hosted in a view."
  []
  (set (keys @instances)))

(defn- mount!
  "Put an editor for `path` inside `node`.

  The editor object is the ordinary one — the same kind the tabs hold, with the
  same behaviors and the same tags — because a hosted editor that was a
  different sort of editor would be a second implementation of the only thing
  this application is."
  [node path]
  (try
    (mount-editor! node path)
    (catch :default e
      ;; Reported rather than swallowed: a mount hook that throws leaves an
      ;; empty box, which reads as a rendering bug and is not one.
      (object/safe-report-error (str "Could not host an editor for " path ": " e))
      nil)))

(defn- mount-editor! [node path]
  (let [content (:content (files/open-sync path))
        ;; The same info an ordinary open builds: mime and tags decide the
        ;; mode, the tags, and therefore which language server attaches. A
        ;; pane that skipped them would be a plain text box that looked like
        ;; an editor.
        info (merge (opener/path->info path)
                    {:content (or content "")})
        ed (pool/create info)]
    (dom/append node (object/->content ed))
    ;; CodeMirror measures itself when it is in the document, and it was not
    ;; when it was created. Without this the pane is a blank box of the right
    ;; size, which looks like a rendering bug and is not one.
    (editor/refresh ed)
    (swap! instances assoc path ed)
    ed))

(defn- unmount! [path]
  (when-let [ed (get @instances path)]
    (swap! instances dissoc path)
    (object/destroy! ed)))

(defn pane
  "Hiccup for an editor showing `path`.

  Empty, keyed, and with hooks either side. Everything between the hooks is the
  editor's and Replicant is told nothing about it."
  [path]
  [:div.pane
   {:replicant/key path
    :replicant/on-mount (fn [{:replicant/keys [node]}] (mount! node path))
    :replicant/on-unmount (fn [_] (unmount! path))}])

(defn clear!
  "Destroy every hosted editor. For tests, and for a view being torn down."
  []
  (doseq [path (keys @instances)]
    (unmount! path)))
