(ns lt.objs.workspace-edit
  "Changing text across files, as one action that can be taken back.

  **The one way Light Table edits code it is not currently showing.** There
  were three before this, and they disagreed: an editor's own `replace`, a
  whole-file `files/save`, and `lt.background.file-search` writing files from
  the worker thread — which is how workspace search-and-replace came to rewrite
  files that were open in a tab without telling the tab, leaving the buffer and
  the disk saying different things. Anything that changes text somewhere the
  user is not looking belongs here instead: a language server's rename, a code
  action, a formatter, search-and-replace.

  ## An edit

  ```clojure
  {:path \"/src/probe.clj\"
   :from {:line 4 :ch 5}
   :to   {:line 4 :ch 8}
   :text \"plus\"}
  ```

  Positions are the editor's — zero-based line, character offset — which is
  also what LSP uses, so a `TextEdit` is this with its names changed.

  ## Three rules, and they are what make it safe

  **Last edit first.** Every range is stated against the document as it was, so
  applying one moves every range after it. Sorting descending and applying in
  that order means no offset ever has to be adjusted, which is the kind of
  arithmetic that is right in testing and wrong on the file with the tab
  character in it.

  **Saved files only.** A workspace edit refuses to start if any file it would
  touch has unsaved changes, and saves everything it writes. That is one rule
  instead of an asymmetry — otherwise an open file ends up as a dirty buffer
  and a closed one as a changed file on disk, from the same operation, and
  neither undo nor \"what did that just do\" has a good answer. Save first is
  something a person can act on; a half-written workspace is not.

  **One undo.** Every applied edit records what each file held before it, on a
  stack. `undo!` puts them all back. This is [[lt.objs.jump-stack]]'s idea
  applied to mutation rather than navigation: an operation that spans files
  needs a way back that also spans files, and CodeMirror's history cannot be
  it — that history is per document, and a file that was never open has none.

  We own this stack, so rather than apologise for what the editor underneath
  does not offer, the way back is a thing we keep."
  (:require [clojure.string :as string]
            [lt.object :as object]
            [lt.objs.editor :as editor]
            [lt.objs.editor.pool :as pool]
            [lt.objs.files :as files]
            [lt.objs.workspace-edit.text :as text]))

;;*********************************************************
;; The way back
;;*********************************************************

(defonce ^:private history
  ;; A stack of {:label … :files {path before-text}}. Bounded, because the
  ;; alternative is holding every version of every file a session ever
  ;; rewrote.
  (atom []))

(def ^:private history-limit 20)

(defn- remember! [label befores]
  (swap! history (fn [h]
                   (vec (take-last history-limit
                                   (conj h {:label label :files befores}))))))

;;*********************************************************
;; Applying
;;*********************************************************

(defn- write!
  "Put `text` in `path`, through its editor when it has one.

  Through the editor rather than only to disk, because an open tab showing
  what the file no longer says is the bug this namespace exists to stop. The
  save is what keeps the two agreeing."
  [path text]
  (if-let [ed (first (pool/by-path path))]
    (editor/operation ed (fn []
                           (editor/set-val-and-keep-cursor ed text)
                           (object/raise ed :save)))
    (files/save path text)))

(defn- read-text [path]
  (if-let [ed (first (pool/by-path path))]
    (editor/->val ed)
    (:content (files/open-sync path))))

(defn unsaved
  "Every path in `edits` whose editor has changes not yet on disk."
  [edits]
  (->> (map :path edits)
       distinct
       (filter (fn [path]
                 (when-let [ed (first (pool/by-path path))]
                   (:dirty @ed))))
       vec))

(defn missing
  "Every path in `edits` that is not a file on disk."
  [edits]
  (->> (map :path edits)
       distinct
       (remove files/exists?)
       vec))

(defn apply!
  "Apply `edits` across files, as one action.

  Returns `{:files n :edits n}` on success, or `{:error …}` — and on an error
  nothing at all has been written, which is the point of checking first. A
  workspace edit that stopped halfway would leave code that does not compile
  and no account of which half it did."
  [label edits]
  (let [edits (remove #(nil? (:path %)) edits)]
    (cond
      (empty? edits)
      {:error "Nothing to change."}

      (seq (missing edits))
      {:error (str "Cannot find " (string/join ", " (missing edits)))}

      (seq (unsaved edits))
      {:error (str "Save first: " (string/join ", " (map files/basename (unsaved edits))))}

      :else
      (let [by-file (group-by :path edits)
            befores (into {} (for [[path _] by-file] [path (read-text path)]))]
        (doseq [[path file-edits] by-file]
          (write! path (text/apply-to-text (get befores path) file-edits)))
        (remember! label befores)
        {:files (count by-file) :edits (count edits)}))))

(defn apply-texts!
  "Replace whole files, as one action.

  The ranged [[apply!]] above is the right shape when a server hands back
  ranges. Workspace search-and-replace does not have ranges — it matches by
  line and rewrites with a regex — so asking it to invent them would be
  arithmetic in the one place nobody would check it.

  Everything else is shared: the same refusal to start with a file missing or
  unsaved, the same write through an open editor, the same single undo. That
  is the point of it being here rather than a `writeFileSync` in a worker,
  which is what this replaced — search-and-replace rewrote files that were
  open in a tab and told no one, so the buffer and the disk disagreed until
  something else saved over it."
  [label texts]
  (let [texts (into {} (remove (fn [[path _]] (nil? path)) texts))
        edits (mapv (fn [[path _]] {:path path}) texts)]
    (cond
      (empty? texts)
      {:error "Nothing to change."}

      (seq (missing edits))
      {:error (str "Cannot find " (string/join ", " (missing edits)))}

      (seq (unsaved edits))
      {:error (str "Save first: " (string/join ", " (map files/basename (unsaved edits))))}

      :else
      (let [befores (into {} (for [[path _] texts] [path (read-text path)]))]
        (doseq [[path text] texts]
          (write! path text))
        (remember! label befores)
        {:files (count texts)}))))

(defn undo!
  "Put back what the last workspace edit changed."
  []
  (if-let [{:keys [label files]} (peek @history)]
    (do
      (swap! history pop)
      (doseq [[path text] files]
        (write! path text))
      {:label label :files (count files)})
    {:error "Nothing to undo."}))

(defn pending
  "What the stack holds, most recent last."
  []
  (mapv (fn [{:keys [label files]}] {:label label :files (count files)}) @history))
