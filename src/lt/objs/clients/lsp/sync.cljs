(ns lt.objs.clients.lsp.sync
  "Keeping a language server's idea of a document the same as the editor's.

  This is the layer that decides whether the feature works. Protocol bugs
  announce themselves; a synchronisation bug does not — the server answers
  confidently about a document that is one keystroke out of date, and
  diagnostics land on the wrong lines. So the position arithmetic lives here,
  as pure functions, and is tested.

  Three things are easy to get wrong and are handled deliberately:

  **Versions.** Every change carries an incrementing version, and every request
  computed against a document records the version it saw. A response for an
  older version is stale and is dropped rather than drawn at offsets that have
  since moved.

  **Incremental against full.** A server declares which it wants in its
  `initialize` result, and sending the other kind is a protocol violation that
  most servers accept quietly and then drift on. `content-changes` produces
  whichever the server asked for.

  **Positions.** LSP counts lines from zero and characters in UTF-16 code
  units. CodeMirror counts lines from zero and characters in UTF-16 code units
  too, so there is nothing to convert — but that is a fact worth asserting
  rather than assuming, because the day a server negotiates UTF-8 it stops
  being true."
  (:require [clojure.string :as string]))

;;*********************************************************
;; Paths and URIs
;;*********************************************************

(defn ->uri
  "A filesystem path as a `file://` URI.

  Each segment is encoded separately so that separators survive: a path with a
  space or a `#` in it is common and breaks a server's parsing if sent raw,
  and encoding the whole thing would turn the slashes into `%2F`."
  [path]
  (let [normalized (string/replace path "\\" "/")
        ;; A Windows path starts with a drive letter rather than a slash, and
        ;; the URI needs one either way.
        absolute (if (string/starts-with? normalized "/") normalized (str "/" normalized))]
    (str "file://"
         (->> (string/split absolute #"/")
              (map js/encodeURIComponent)
              (string/join "/")))))

(defn uri->path
  "The path inside a `file://` URI. Diagnostics arrive addressed by URI and
  have to be matched back to an open editor."
  [uri]
  (when uri
    (-> uri
        (string/replace #"^file://" "")
        js/decodeURIComponent)))

;;*********************************************************
;; Positions
;;*********************************************************

(defn ->position
  "A CodeMirror `{:line :ch}` as an LSP position.

  Both count lines from zero and characters in UTF-16 code units, so this is a
  rename rather than a conversion. It exists so there is one place to change
  if a server ever negotiates a different encoding."
  [{:keys [line ch]}]
  {:line line :character ch})

(defn ->loc
  "An LSP position as a CodeMirror `{:line :ch}`."
  [{:keys [line character]}]
  {:line line :ch character})

(defn range->loc
  "The start of an LSP range, as somewhere to put a widget."
  [{:keys [start]}]
  (->loc start))

;;*********************************************************
;; Changes
;;*********************************************************

(defn change->content-change
  "One CodeMirror change as an LSP incremental content change.

  CodeMirror reports `from` and `to` as positions in the document *before* the
  change, and `text` as the inserted lines — which is exactly the shape LSP
  wants, so this is a translation rather than a computation. Joining the lines
  with `\\n` is right regardless of the file's line endings: CodeMirror
  normalises them internally and reports what it holds."
  [{:keys [from to text]}]
  {:range {:start (->position from) :end (->position to)}
   :text (string/join "\n" text)})

(defn content-changes
  "The `contentChanges` array for a `didChange`, in the kind the server asked
  for.

  `kind` is the server's declared `textDocumentSync.change`: 1 is full, 2 is
  incremental, 0 is none. Sending incremental changes to a server that asked
  for full text is the kind of mistake that works for a while — many servers
  accept both — and then desynchronises on an edit that happens to straddle a
  boundary they handle differently."
  [kind changes whole-text]
  (case kind
    2 (mapv change->content-change changes)
    ;; Full text, which is also the safe default for a server that declared
    ;; nothing: every server understands it.
    [{:text whole-text}]))

(defn sync-kind
  "What the server said it wants, from its `initialize` result.

  The field has two shapes in the wild: a bare number, from before the protocol
  grew options, and a map. Both are still sent."
  [capabilities]
  (let [sync (:textDocumentSync capabilities)]
    (cond
      (number? sync) sync
      (map? sync) (or (:change sync) 1)
      :else 1)))

(defn open-close?
  "Whether the server wants `didOpen`/`didClose` at all. A server that says no
  is telling you it works off the filesystem."
  [capabilities]
  (let [sync (:textDocumentSync capabilities)]
    (if (map? sync) (not (false? (:openClose sync))) true)))

;;*********************************************************
;; Documents
;;*********************************************************

(defn document
  "The record kept per open document. `version` starts at 1 and only ever
  increases, which is what lets a stale response be recognised."
  [path language-id]
  {:path path
   :uri (->uri path)
   :language-id language-id
   :version 1})

(defn bump
  "The next version of a document."
  [doc]
  (update doc :version inc))

(defn stale?
  "Whether a response computed against `version` is worth drawing.

  Anything older than what the document is now has been overtaken by an edit,
  and drawing it puts marks at offsets that have moved."
  [doc version]
  (not= (:version doc) version))
