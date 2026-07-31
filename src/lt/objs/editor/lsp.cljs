(ns lt.objs.editor.lsp
  "Language servers, wired to editors by tag, with diagnostics shown inline.

  This is the editor-facing half of the LSP client, the same shape as
  `lt.objs.editor.treesitter`: the protocol lives elsewhere and knows nothing
  about Light Table, and this decides which server goes with which editor and
  where its answers are drawn.

  **Inline, not a gutter.** Light Table's whole idea is showing you what your
  code does next to the code, and a diagnostic is that — the compiler's opinion
  of the line you are looking at. It uses the same `line-widget` mechanism as
  `lt.objs.eval`'s inline results and exceptions, so a type error sits where a
  printed value would.

  One connection per project root and server, shared by every editor under it,
  because that is what a language server is for: it indexes a project once and
  answers about all of it.

  **Which server goes with which language is data**, not code here: the
  `::language-servers` behavior, shaped like `:lt.objs.files/file-types` and
  contributed to by the same three places — Light Table's own behaviors, a
  plugin's, and the user's, in that order. [[lt.objs.editor.lsp.registry]] is
  the table and the precedence rule.

  **Diagnostics, completion, documentation and jump-to-definition** are wired.
  Which of them answers when a language also has a REPL is decided under
  *Surfaces other than diagnostics* below, and the short version is: completion
  merges, the other two defer to a connected REPL, and diagnostics are the
  server's alone because no REPL has any.

  See doc/lsp-architecture.md for the layering and what is deliberately still
  not here — references, document symbols and rename."
  (:require [clojure.string :as string]
            [lt.object :as object]
            [lt.objs.clients.lsp :as lsp]
            [lt.objs.clients.lsp.sync :as sync]
            [lt.objs.clients :as clients]
            [lt.objs.command :as cmd]
            [lt.objs.editor :as editor]
            [lt.objs.editor.lsp.registry :as registry]
            [lt.objs.editor.pool :as pool]
            [lt.objs.jump-stack :as jump-stack]
            [lt.objs.notifos :as notifos]
            [lt.plugins.auto-complete :as auto-complete]
            [lt.util.bridge :as bridge])
  (:require-macros [lt.macros :refer [behavior defui]]))

(declare lsp-client capability-tags)

(defn servers
  "Every language server that has been declared, in declaration order."
  []
  (::servers @lsp-client []))

(defn server-for
  "The server for an editor carrying `tags`, or nil."
  [tags]
  (registry/for-tags (servers) tags))

;;*********************************************************
;; Finding a project
;;*********************************************************

(defn- parent [path]
  (let [idx (.lastIndexOf path "/")]
    (when (pos? idx) (subs path 0 idx))))

(defn project-root
  "The nearest directory at or above `path` containing one of `markers`.

  Nil when there is none, which is a real answer rather than a failure: a
  loose file with no project around it has no sensible root, and starting a
  server rooted at the filesystem is worse than not starting one."
  [path markers]
  (loop [dir (parent path)]
    (when dir
      (if (some #(.existsSync bridge/files (str dir "/" %)) markers)
        dir
        (recur (parent dir))))))

;;*********************************************************
;; Finding the server itself
;;*********************************************************

(defn- on-path
  "The first directory on `PATH` holding `command`, or nil.

  Resolved here rather than left to `spawn` so that a server which is not
  installed anywhere is a plain answer instead of a process that fails to
  start, and so [[status]] can say exactly where it looked.

  `:` and no `.cmd`, because nothing here builds for Windows."
  [command]
  (let [path (aget (.env bridge/host) "PATH")]
    (when-not (string/blank? path)
      (some (fn [dir]
              (let [full (str dir "/" command)]
                (when (.existsSync bridge/files full) full)))
            (string/split path #":")))))

(defn server-command
  "Where to find the server for `root`, or nil if it is not installed.

  The project's own copy first: a language server is a compiler, and checking
  against a different one than the project builds with reports differences
  that are not the code's. Then `PATH`, because refusing to start when a
  perfectly good server is installed globally is worse than a version skew
  nobody has hit yet.

  On macOS `PATH` is worth having only because `lt.objs.proc/set-path-OSX`
  sources the login shell's at startup; an application launched from Finder
  inherits almost nothing, so a version manager's shims are invisible without
  it."
  [root command]
  (let [local (str root "/node_modules/.bin/" command)]
    (if (.existsSync bridge/files local)
      local
      (on-path command))))

;;*********************************************************
;; Connections
;;*********************************************************

(defonce ^:private connections
  ;; Keyed by [root command], so every editor in a project shares one server.
  (atom {}))

(defn- ensure-connection!
  "The connection for this root and server, started if it is not running."
  [root {:keys [command args]}]
  (let [key [root command]]
    (or (get @connections key)
        (when-let [full (server-command root command)]
          (let [conn (lsp/connect! {:command full
                                    :args args
                                    :root-path root
                                    :object lsp-client})]
            (swap! connections assoc key conn)
            (notifos/set-msg! (str "Language server starting for " root))
            conn)))))

;;*********************************************************
;; Diagnostics, drawn inline
;;*********************************************************

(def ^:private severity-class
  {1 "error" 2 "warning" 3 "info" 4 "hint"})

(defui ->diagnostic [{:keys [message severity source]}]
  [:div {:class (str "inline-diagnostic " (get severity-class severity "error"))}
   [:span.source (or source "lsp")]
   [:span.message message]])

(defui ->diagnostics
  "Everything a line has to say about itself, as one element.

  One element and not a document fragment, which is the obvious way to hand
  CodeMirror several nodes and does not work: measuring a widget's height
  reads `node.parentNode.offsetHeight`, and appending a fragment moves its
  children out and leaves the fragment itself parentless. The throw comes from
  inside `addLineWidget`, after the nodes are already in the measuring
  container — so they are visible on screen while the widget that was supposed
  to own them does not exist."
  [ds]
  [:div.inline-diagnostics
   (for [d ds] (->diagnostic d))])

(defn- clear-diagnostics! [ed]
  (doseq [widget (::widgets @ed)]
    (editor/remove-line-widget ed widget))
  (object/merge! ed {::widgets []}))

(defn- draw-diagnostics!
  "Put one widget under each line that has a diagnostic.

  Grouped by line rather than one widget per diagnostic: three errors on one
  line are three sentences, not three boxes pushing the code apart."
  [ed diagnostics]
  (clear-diagnostics! ed)
  (let [last-line (editor/last-line ed)
        by-line (group-by #(:line (sync/range->loc (:range %))) diagnostics)
        widgets (doall
                 (for [[line ds] by-line
                       ;; A diagnostic past the end of the document is one the
                       ;; server computed against text we have since changed.
                       :when (and line (<= 0 line last-line))]
                   (editor/line-widget ed line (->diagnostics ds)
                                       {:coverGutter false})))]
    (object/merge! ed {::widgets (vec widgets)})))

;;*********************************************************
;; Telling the server about the document
;;*********************************************************

(defn- ->change
  "One CodeMirror change object as the map [[lt.objs.clients.lsp.sync]] works in.

  CodeMirror's `change` event fires once per change — `changes` is the batched
  one — so there is no `next` chain to walk here."
  [^js change]
  {:from {:line (.. change -from -line) :ch (.. change -from -ch)}
   :to {:line (.. change -to -line) :ch (.. change -to -ch)}
   :text (vec (.-text change))})

(defn- open-close?
  "Whether to send `didOpen`/`didClose` to this server.

  Before the handshake finishes there are no capabilities to consult, and the
  answer then is yes: `sync/open-close?` defaults to it, every server that has
  not said otherwise expects it, and the notification is queued behind
  `initialize` anyway."
  [conn]
  (sync/open-close? (lsp/server-capabilities conn)))

;;*********************************************************
;; Behaviors
;;*********************************************************

(behavior ::use-language-server
          ;; Both triggers, for the reason lt.objs.editor.treesitter gives: an
          ;; editor does not know its language when it is created.
          :triggers #{:object.instant :lt.object/tags-added}
          :desc "Editor: Connect to a language server"
          :doc "Starts the language server configured for this editor's
                language, if the project provides one, and keeps it told about
                the document. Diagnostics appear inline, under the line they
                are about.

                A project with no server installed is not an error: nothing
                starts, and everything else about the editor is unaffected."
          :type :user
          :reaction (fn [this & _]
                      (when-let [server (server-for (:tags @this))]
                        (when-let [path (-> @this :info :path)]
                          (when-not (::doc @this)
                            (when-let [root (project-root path (:root server))]
                              (when-let [conn (ensure-connection! root server)]
                                (let [doc (sync/document path (:language-id server))]
                                  (object/merge! this {::doc doc ::conn conn})
                                  ;; A server that was already up when this
                                  ;; editor opened has answered `initialize`
                                  ;; long ago, so ::tag-from-capabilities will
                                  ;; not fire again for it.
                                  (doseq [tag (capability-tags (lsp/server-capabilities conn))]
                                    (object/add-tags this [tag]))
                                  (when (open-close? conn)
                                    (lsp/notify! conn "textDocument/didOpen"
                                                 {:textDocument
                                                  {:uri (:uri doc)
                                                   :languageId (:language-id doc)
                                                   :version (:version doc)
                                                   :text (editor/->val this)}}))))))))))

(behavior ::sync-on-change
          :triggers #{:change}
          :desc "Editor: Keep the language server's copy of this document current"
          ;; `:change` is raised with CodeMirror's own two arguments, the
          ;; instance and then the change. Taking the first one for the change
          ;; is silent: the version still increments, `didChange` is never
          ;; sent, and the server answers confidently about the file on disk.
          :reaction (fn [this _cm ^js change]
                      (when-let [conn (::conn @this)]
                        (let [doc (sync/bump (::doc @this))
                              kind (sync/sync-kind (lsp/server-capabilities conn))]
                          (object/merge! this {::doc doc})
                          (lsp/notify! conn "textDocument/didChange"
                                       {:textDocument {:uri (:uri doc) :version (:version doc)}
                                        :contentChanges
                                        (sync/content-changes kind
                                                              [(->change change)]
                                                              (editor/->val this))})))))

(behavior ::close-document
          :triggers #{:destroy :close}
          :desc "Editor: Tell the language server this document is gone"
          :reaction (fn [this]
                      (when-let [conn (::conn @this)]
                        (when (open-close? conn)
                          (lsp/notify! conn "textDocument/didClose"
                                       {:textDocument {:uri (:uri (::doc @this))}}))
                        ;; The widgets go with the editor, but the editor may
                        ;; outlive this — `:close` on a tab that is being
                        ;; reused leaves the object behind.
                        (clear-diagnostics! this)
                        (object/merge! this {::conn nil ::doc nil}))))

;;*********************************************************
;; Surfaces other than diagnostics
;;*********************************************************

;; **Which of these answers, when a language also has a REPL.**
;;
;; Light Table's own design already decides two of the three. Completions come
;; from `:hints+`, which is a `raise-reduce` — every source contributes and the
;; list is the union — so a language server and a REPL both add and neither has
;; to win. Documentation and jump-to-definition are singular: there is one doc
;; bar and one cursor, so exactly one answer is wanted.
;;
;; For those two the REPL goes first when one is connected. That is not a
;; hedge: a language server reads what is written and a REPL knows what is
;; *loaded*, including vars that only exist because something defined them at
;; runtime, and Light Table is an editor about the running program. When no
;; REPL can answer — no client, no project, another language entirely — the
;; server does, which is most of the time and every language that has no REPL
;; at all.
;;
;; Diagnostics are not in this argument. cider-nrepl publishes 183 operations
;; and none of them is a linter; a REPL can tell you a form threw when you ran
;; it and nothing about the line you have not run yet. So diagnostics are the
;; server's, unconditionally.

(defn- repl-answers?
  "Whether a client this editor is already using handles `suffix`.

  Asked of the clients the editor has, not of `lt.objs.eval/get-client!`,
  which would *start* a REPL to answer — the opposite of what a fallback
  should do.

  Matched by suffix because the command is the language's, not Light Table's:
  the Clojure plugin advertises `:editor.clj.doc` and a Python one would
  advertise `:editor.python.doc`. What is common is the ending, so that is
  what is compared, and core needs no table of languages to do it."
  [ed suffix]
  (boolean
   (some (fn [client]
           (and client
                (clients/available? client)
                (some #(string/ends-with? (str %) suffix)
                      (:commands @client))))
         (vals (:client @ed)))))

(defn- request-at-cursor!
  "Send `method` about the cursor's position in `ed`, if a server is connected."
  [ed method callback]
  (when-let [conn (::conn @ed)]
    (when (lsp/ready? conn)
      (lsp/request! conn method
                    {:textDocument {:uri (:uri (::doc @ed))}
                     :position (sync/->position (editor/->cursor ed))}
                    callback))))

;;*********************************************************
;; Completion
;;*********************************************************

(defn- ->hint
  "One LSP completion item, as the hinter's list wants it.

  `:completion` is what gets inserted and `:text` is what is shown, which is
  how a hint can carry a signature or a namespace without typing it."
  [{:keys [label detail insertText]}]
  #js {:completion (or insertText label)
       :text (if detail (str label " — " detail) label)})

(behavior ::completion-hints
          :triggers #{:hints+}
          :type :user
          :desc "Editor: Offer the language server's completions"
          :doc "Adds what the language server suggests to the completion list.
                It adds rather than replaces: `:hints+` is a reduction over
                every source, so a REPL's completions and a server's appear
                together, which is more than either knows on its own."
          :reaction (fn [ed hints token]
                      ;; A new token means the list is about to be wrong, so
                      ;; ask again — and return what is cached meanwhile,
                      ;; because the hinter is synchronous and will not wait.
                      (when (not= token (::hint-token @ed))
                        (object/merge! ed {::hint-token token})
                        (object/raise ed ::update-completions))
                      (concat (::hints @ed) hints)))

(behavior ::update-completions
          :triggers #{::update-completions}
          :debounce 100
          :reaction (fn [ed]
                      (request-at-cursor!
                       ed "textDocument/completion"
                       (fn [{:keys [result]}]
                         ;; A server may answer with a list or with
                         ;; {:items …, :isIncomplete}; both are in the spec.
                         (let [items (if (map? result) (:items result) result)]
                           (object/merge! ed {::hints (map ->hint items)})
                           (object/raise auto-complete/hinter :refresh!))))))

;;*********************************************************
;; Documentation
;;*********************************************************

(defn- strip-fences
  "Markdown code fences, removed.

  A hover is markdown by specification and is nearly always one fenced block
  holding a signature. Light Table draws it in a code widget already, so the
  fence is three backticks and a language name the reader did not ask for."
  [text]
  (-> text
      (string/replace #"(?m)^```[a-zA-Z0-9-]*\s*$" "")
      string/trim))

(defn- hover->text
  "An LSP hover's contents, flattened to text.

  Three shapes are legal — a string, a `{:kind :value}` map, or a list of
  either — because the protocol accreted two deprecated forms and kept them."
  [contents]
  (strip-fences
   (cond
     (string? contents) contents
     (map? contents) (or (:value contents) "")
     (sequential? contents) (string/join "\n\n" (map #(hover->text %) contents))
     :else "")))

(defn- capability-tags
  "The tags an editor earns from what its server says it can do.

  Light Table gates surfaces on tags — the doc bar only draws for `:docable` —
  which is how a language gets a feature without the feature knowing about the
  language. A server's `initialize` result is exactly that list, so the tags
  come off the wire rather than out of a table someone has to maintain."
  [capabilities]
  (cond-> #{}
    (:hoverProvider capabilities) (conj :docable)
    (:documentSymbolProvider capabilities) (conj :navigable)))

(behavior ::tag-from-capabilities
          :triggers #{:lsp.ready}
          :desc "Language server: Tag editors with what their server can do"
          :reaction (fn [_ result conn]
                      (doseq [ed (object/by-tag :editor)
                              :when (= conn (::conn @ed))
                              tag (capability-tags (:capabilities result))]
                        (object/add-tags ed [tag]))))

(behavior ::doc-at-cursor
          :triggers #{:editor.doc}
          :type :user
          :desc "Editor: Show the language server's documentation"
          :doc "Answers **Editor: Toggle documentation at cursor** from the
                language server. Stands down when a REPL is connected that can
                answer instead: a REPL knows what is actually loaded, and
                there is only one doc bar."
          :reaction (fn [ed]
                      (when-not (repl-answers? ed ".doc")
                        (request-at-cursor!
                         ed "textDocument/hover"
                         (fn [{:keys [result]}]
                           (let [text (hover->text (:contents result))]
                             (if (string/blank? text)
                               (notifos/set-msg! "No documentation found.")
                               (object/raise ed :editor.doc.show!
                                             {:name (:string (editor/->token ed (editor/->cursor ed)))
                                              :doc text
                                              :loc (editor/->cursor ed)}))))))))

;;*********************************************************
;; Jump to definition
;;*********************************************************

(defn- ->location
  "The first location an LSP definition response names, whatever its shape.

  `Location`, `Location[]` and `LocationLink[]` are all legal answers, and a
  server picks whichever it likes."
  [result]
  (let [one (if (sequential? result) (first result) result)]
    (when one
      {:uri (or (:uri one) (:targetUri one))
       :range (or (:range one) (:targetSelectionRange one) (:targetRange one))})))

(behavior ::jump-to-definition
          :triggers #{:editor.jump-to-definition-at-cursor!}
          :type :user
          :desc "Editor: Jump to a definition the language server found"
          :doc "Stands down when a REPL is connected that can answer: there is
                one cursor, and a REPL knows where a var was actually defined
                rather than where it appears to have been."
          :reaction (fn [ed]
                      (when-not (repl-answers? ed ".jump-to-definition")
                        (request-at-cursor!
                         ed "textDocument/definition"
                         (fn [{:keys [result]}]
                           (if-let [{:keys [uri range]} (->location result)]
                             (object/raise jump-stack/jump-stack :jump-stack.push!
                                           ed (sync/uri->path uri) (sync/range->loc range))
                             (notifos/set-msg! "No definition found.")))))))

;;*********************************************************
;; The client object
;;*********************************************************

(defn- editors-for-uri
  "Every open editor showing the document a notification is about."
  [uri]
  (let [path (sync/uri->path uri)]
    (filter #(= path (-> (deref %) :info :path))
            (object/by-tag :editor))))

(object/object* ::lsp-client
                :tags #{:lsp.client}
                :name "Language servers"
                :init (fn [this] nil))

(def lsp-client (object/create ::lsp-client))

(behavior ::language-servers
          :triggers #{:object.instant}
          :type :user
          :desc "Language server: Associate language servers"
          :doc "Which language server answers for which editor, declared the
                same way file types are — see `:lt.objs.files/file-types`.
                Entries from every `.behaviors` file accumulate, and a later
                declaration beats an earlier one, so `user.behaviors` beats a
                plugin's and a plugin's beats Light Table's own.

                `:tags` are the editor tags this server answers for. `:root`
                is the marker list that decides where the project starts,
                nearest first from the file. `:command` is the bare executable
                name; the project's own `node_modules/.bin` is looked in
                before `PATH`, because a language server is a compiler and
                checking against a different one than the project builds with
                reports differences that are not the code's.

                A server that is not installed is not an error — nothing
                starts. **Language server: Status for this editor** says which
                of the ways this can be quiet is the one in play."
          :params [{:label "servers"
                    :example "[{:tags [:editor.clj :editor.cljs],\n  :language-id \"clojure\",\n  :root [\"deps.edn\" \"project.clj\"],\n  :command \"clojure-lsp\",\n  :args []}]"}]
          :reaction (fn [this servers]
                      (object/update! this [::servers] registry/add servers)))

(behavior ::on-notification
          :triggers #{:lsp.notification}
          :desc "Language server: Handle a notification"
          :reaction (fn [_ {:keys [method params]} _conn]
                      (case method
                        "textDocument/publishDiagnostics"
                        (doseq [ed (editors-for-uri (:uri params))]
                          (draw-diagnostics! ed (:diagnostics params)))

                        ;; A server telling the user something. The status bar
                        ;; is where Light Table says things of this size.
                        "window/showMessage"
                        (notifos/set-msg! (str "Language server: " (:message params)))

                        ;; Everything else is noise until something renders it.
                        nil)))

(behavior ::on-stderr
          :triggers #{:lsp.stderr}
          :desc "Language server: Log what the server printed"
          :reaction (fn [_ text _conn]
                      ;; Where a plugin author will look when a server will not
                      ;; start, which is the only time this matters.
                      (when-not (string/blank? text)
                        (js/lt.objs.console.log (str "language server: " (string/trim text))))))

(behavior ::on-exit
          :triggers #{:lsp.exit}
          :desc "Language server: Report that a server stopped"
          :reaction (fn [_ code conn]
                      ;; Only the one that exited. Forgetting all of them would
                      ;; leave every other project's server running with
                      ;; nothing holding its handle, and a fresh one would be
                      ;; started alongside it on the next keystroke.
                      (swap! connections #(into {} (remove (comp #{conn} val)) %))
                      (notifos/set-msg! (str "Language server exited (" code ")")
                                        {:class "error"})))

(behavior ::on-error
          :triggers #{:lsp.error}
          :desc "Language server: Report a failure"
          :reaction (fn [_ message _conn]
                      (js/lt.objs.console.error (str "language server: " message))))

(behavior ::on-ready
          :triggers #{:lsp.ready}
          :desc "Language server: Report that a server is ready"
          :reaction (fn [_ _result _conn]
                      (notifos/done-working "Language server ready")))

(behavior ::shutdown-servers-on-close
          :triggers #{:closed}
          :desc "App: Shut language servers down when Light Table closes"
          :doc "A language server is a child process, and one that is never told
                to stop can outlive the window that started it. This asks each
                one to shut down the way the protocol says."
          :reaction (fn [_]
                      (doseq [[_ conn] @connections]
                        (lsp/disconnect! conn))
                      (reset! connections {})))

;;*********************************************************
;; Saying what happened
;;*********************************************************

(defn status
  "The language server situation for `ed`, as data.

  Silence is this feature's hard part. A project with no server installed is
  deliberately not an error — nothing starts, nothing is said — and that is
  indistinguishable from a bug you have just introduced. This is the answer to
  \"why are there no diagnostics\", and it names every place that was looked."
  [ed]
  (let [path (-> @ed :info :path)
        server (server-for (:tags @ed))
        root (when (and path server) (project-root path (:root server)))
        conn (::conn @ed)]
    {:path path
     :language-id (:language-id server)
     :command (:command server)
     :markers (:root server)
     :root root
     :found (when root (server-command root (:command server)))
     :connected? (boolean conn)
     :ready? (boolean (and conn (lsp/ready? conn)))
     :diagnostics (count (::widgets @ed))}))

(defn status-line
  "One sentence saying which of the ways this can be quiet is the one in play."
  [{:keys [path language-id command markers root found connected? ready? diagnostics]}]
  (cond
    (nil? path) "This editor is not backed by a file."
    (nil? language-id) "No language server is configured for this file type."
    (nil? root) (str "No project root above " path " — looked for "
                     (string/join ", " markers))
    (nil? found) (str "No " command " in " root "/node_modules/.bin, or on PATH. "
                      "Install it in the project, or globally.")
    (not connected?) (str "Found " found ", but this editor is not connected to it.")
    (not ready?) (str "Starting " found " …")
    :else (str "Connected to " found " — " diagnostics
               (if (= 1 diagnostics) " diagnostic" " diagnostics") " on screen")))

(cmd/command {:command :lsp.status
              :desc "Language server: Status for this editor"
              :exec (fn []
                      (when-let [ed (pool/last-active)]
                        (let [s (status ed)]
                          (notifos/set-msg! (status-line s))
                          (js/lt.objs.console.log
                           (str "language server status: " (pr-str s))))))})

;; These behaviors are attached in deploy/settings/default/default.behaviors
;; rather than here. `object/tag-behaviors` works, and then does not: the
;; settings loader builds the tag map from that file after this namespace has
;; loaded, so anything added programmatically at load time is replaced by the
;; time an object is created. Behaviors being data is the whole idea, and this
;; is what enforces it.
;;
;; The same argument is why the server table is `::language-servers` and not an
;; atom. An atom cannot be overridden from user.behaviors, has no defined
;; precedence when two entries match, and hides a spawned process from the
;; capability manifest — which is a fact about the plugin that spawns it. Which
;; server answers for which language is declared by whoever owns the language:
;; plugins/TypeScript/typescript.behaviors and plugins/Clojure/clojure.behaviors
;; today, and default.behaviors for a language with no plugin.
