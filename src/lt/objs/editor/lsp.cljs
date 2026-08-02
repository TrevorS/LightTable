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
            [lt.objs.popup :as popup]
            [lt.objs.providers :as providers]
            [lt.objs.search :as search]
            [lt.objs.sidebar.command :as scmd]
            [lt.objs.tabs :as tabs]
            [lt.objs.workspace-edit :as we]
            [lt.objs.workspace-edit.text :as we-text]
            [lt.plugins.auto-complete :as auto-complete]
            [lt.ui :as ui]
            [lt.util.bridge :as bridge])
  (:require-macros [lt.macros :refer [behavior]]))

(declare lsp-client capability-tags)

(defn servers
  "Every language server that has been declared, in declaration order."
  []
  (::servers @lsp-client []))

(defn server-for
  "The server for an editor carrying `tags`, or nil."
  [tags]
  (registry/for-tags (servers) tags))

(defn servers-for
  "Every server declared for an editor carrying `tags`."
  [tags]
  (registry/all-for-tags (servers) tags))

(defn conns
  "Every language server this editor is connected to, in declaration order."
  [ed]
  (::conns @ed []))

(def ^:private capability-for-method
  "What a server must advertise before it is worth sending a method to.

  This is how two servers for one language divide the work without anyone
  configuring it: biome answers formatting and code actions and advertises
  nothing else, so hover goes to vtsls without either of them being told about
  the other."
  {"textDocument/hover" :hoverProvider
   "textDocument/definition" :definitionProvider
   "textDocument/references" :referencesProvider
   "textDocument/documentSymbol" :documentSymbolProvider
   "textDocument/rename" :renameProvider
   "textDocument/formatting" :documentFormattingProvider
   "textDocument/rangeFormatting" :documentRangeFormattingProvider
   "textDocument/codeAction" :codeActionProvider})

(defn- conn-for
  "The connection to send `method` to, or nil.

  The last connection that says it can answer, so a server declared later wins
  a surface both of them offer — the same rule as the table itself.

  Falling back to the last connection when none advertises it is not a
  formality: before the handshake there are no capabilities to filter on, and
  a server that never advertised a method may still answer it. This is what a
  single-server editor has always done."
  [ed method]
  (let [cs (conns ed)]
    (or (last (filter #(get (lsp/server-capabilities %) (capability-for-method method)) cs))
        (last cs))))

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

;; Projects already told about a missing server, so it is said once.
(defonce ^:private announced (atom #{}))

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
  ;; Keyed by [root command args], so every editor in a project shares one
  ;; server. The arguments are part of it because two declarations can name the
  ;; same executable and be different servers — `node one.mjs` and
  ;; `node two.mjs`, or one interpreter running two tools.
  (atom {}))

(defn- ensure-connection!
  "The connection for this root and server, started if it is not running."
  [root {:keys [command args init-options install] :as server}]
  (let [key [root command (vec args)]]
    (or (get @connections key)
        (if-let [full (server-command root command)]
          (let [conn (lsp/connect! {:command full
                                    :args args
                                    :init-options init-options
                                    :root-path root
                                    :object lsp-client})]
            (swap! connections assoc key conn)
            (notifos/set-msg! (str "Language server starting for " root))
            conn)
          ;; Said once per project, and with the install line the declaration
          ;; carries. A missing server used to be silent, which is the same
          ;; experience as a language server that does not work.
          (do (when-not (contains? @announced key)
                (swap! announced conj key)
                (js/lt.objs.console.log (str command " is not installed, so " (:language-id server)
                                  " has no language server here."
                                  (when install (str " To install it: " install)))))
              nil)))))

;;*********************************************************
;; Diagnostics, drawn inline
;;*********************************************************

(def ^:private severity-class
  {1 "error" 2 "warning" 3 "info" 4 "hint"})

(defn- ->diagnostic [{:keys [message severity source]}]
  [:div {:class (str "inline-diagnostic " (get severity-class severity "error"))}
   [:span.source (or source "lsp")]
   [:span.message message]])

(defn- ->diagnostics
  "Everything a line has to say about itself, as one element.

  One element and not a document fragment, which is the obvious way to hand
  CodeMirror several nodes and does not work: measuring a widget's height
  reads `node.parentNode.offsetHeight`, and appending a fragment moves its
  children out and leaves the fragment itself parentless. The throw comes from
  inside `addLineWidget`, after the nodes are already in the measuring
  container — so they are visible on screen while the widget that was supposed
  to own them does not exist.

  A node rather than hiccup, because CodeMirror takes it and owns it from
  there: nothing changes a widget, it is replaced."
  [ds]
  (ui/element [:div.inline-diagnostics
               (for [d ds] (->diagnostic d))]))

(defn- erase-widgets! [ed]
  (doseq [widget (::widgets @ed)]
    (editor/remove-line-widget ed widget))
  (object/merge! ed {::widgets []}))

(defn- clear-diagnostics! [ed]
  (object/merge! ed {::diagnostics {}})
  (erase-widgets! ed))

(defn diagnostics
  "Everything every server has said about this editor."
  [ed]
  (vec (mapcat val (::diagnostics @ed {}))))

(defn- draw-diagnostics!
  "Put one widget under each line that has a diagnostic.

  Grouped by line rather than one widget per diagnostic: three errors on one
  line are three sentences, not three boxes pushing the code apart.

  Kept per connection, because `publishDiagnostics` is a replacement rather
  than an addition: it is the whole truth *from that server* about that file,
  and holding one list would mean the linter's publish erased the type
  checker's a moment after it arrived."
  [ed conn published]
  (let [by-conn (assoc (::diagnostics @ed {}) conn (vec published))
        all (vec (mapcat val by-conn))]
    (erase-widgets! ed)
    (let [last-line (editor/last-line ed)
          by-line (group-by #(:line (sync/range->loc (:range %))) all)
          widgets (doall
                   (for [[line ds] by-line
                         ;; A diagnostic past the end of the document is one the
                         ;; server computed against text we have since changed.
                         :when (and line (<= 0 line last-line))]
                     (editor/line-widget ed line (->diagnostics ds)
                                         {:coverGutter false})))]
      ;; The diagnostics themselves are kept, not only the widgets drawn from
      ;; them: a code action is a fix *for* a diagnostic, and the server expects
      ;; to be handed back the ones it sent for the range being asked about.
      (object/merge! ed {::widgets (vec widgets)
                         ::diagnostics by-conn}))))

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
          :doc "Starts the language servers configured for this editor's
                language, if the project provides them, and keeps them told
                about the document. Diagnostics appear inline, under the line
                they are about.

                More than one server for a language is normal — a type checker
                and a linter — and every one declared is started. Each surface
                asks the server that says it can answer.

                A project with no server installed is not an error: nothing
                starts, and everything else about the editor is unaffected."
          :type :user
          :reaction (fn [this & _]
                      (when-let [path (-> @this :info :path)]
                        (doseq [server (servers-for (:tags @this))
                                :let [root (project-root path (:root server))]
                                :when root
                                :let [conn (ensure-connection! root server)]
                                ;; Already connected, which this is raised
                                ;; often enough to reach: :lt.object/tags-added
                                ;; fires for every tag an editor earns,
                                ;; including the ones earned here.
                                :when (and conn (not (some #{conn} (conns this))))]
                          ;; One document, shared. The version counter belongs
                          ;; to the file rather than to a server, and every
                          ;; server is told about every change, so one sequence
                          ;; is what each of them sees.
                          (let [doc (or (::doc @this) (sync/document path (:language-id server)))]
                            (object/merge! this {::doc doc
                                                 ::conns (conj (conns this) conn)})
                            ;; A server that was already up when this editor
                            ;; opened has answered `initialize` long ago, so
                            ;; ::tag-from-capabilities will not fire again for
                            ;; it.
                            (doseq [tag (capability-tags (lsp/server-capabilities conn))]
                              (object/add-tags this [tag]))
                            (when (open-close? conn)
                              (lsp/notify! conn "textDocument/didOpen"
                                           {:textDocument
                                            ;; Its own languageId, not the
                                            ;; document's: two servers for one
                                            ;; file can name its language
                                            ;; differently, and each was
                                            ;; declared with the name it knows.
                                            {:uri (:uri doc)
                                             :languageId (:language-id server)
                                             :version (:version doc)
                                             :text (editor/->val this)}})))))))

(behavior ::sync-on-change
          :triggers #{:change}
          :desc "Editor: Keep the language server's copy of this document current"
          ;; `:change` is raised with CodeMirror's own two arguments, the
          ;; instance and then the change. Taking the first one for the change
          ;; is silent: the version still increments, `didChange` is never
          ;; sent, and the server answers confidently about the file on disk.
          :reaction (fn [this _cm ^js change]
                      (when (seq (conns this))
                        (let [doc (sync/bump (::doc @this))
                              text (editor/->val this)]
                          (object/merge! this {::doc doc})
                          ;; Every server, and each in the shape it asked for:
                          ;; one may want the whole document where another
                          ;; takes the range that changed.
                          (doseq [conn (conns this)]
                            (lsp/notify! conn "textDocument/didChange"
                                         {:textDocument {:uri (:uri doc) :version (:version doc)}
                                          :contentChanges
                                          (sync/content-changes
                                           (sync/sync-kind (lsp/server-capabilities conn))
                                           [(->change change)]
                                           text)}))))))

(behavior ::close-document
          :triggers #{:destroy :close}
          :desc "Editor: Tell the language server this document is gone"
          :reaction (fn [this]
                      (when (seq (conns this))
                        (doseq [conn (conns this)
                                :when (open-close? conn)]
                          (lsp/notify! conn "textDocument/didClose"
                                       {:textDocument {:uri (:uri (::doc @this))}}))
                        ;; The widgets go with the editor, but the editor may
                        ;; outlive this — `:close` on a tab that is being
                        ;; reused leaves the object behind.
                        (clear-diagnostics! this)
                        (object/merge! this {::conns [] ::doc nil}))))

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

(defn- answered-elsewhere?
  "Whether a client this editor is already using answers `surface`.

  Asked of the clients the editor has, not of `lt.objs.eval/get-client!`,
  which would *start* a REPL to answer — the opposite of what a fallback
  should do.

  Which client answers what is [[lt.objs.providers]]; all this does is find
  the connected ones and hand over their values."
  [ed surface]
  (providers/provided?
   (->> (vals (:client @ed))
        (filter #(and % (clients/available? %)))
        (map deref))
   surface))

(defn- request-at-cursor!
  "Send `method` about the cursor's position in `ed`, and say what happened.

  Returns `:sent`, `:not-ready` or `:no-server`, and the return value is the
  point. This declines before the handshake — a cursor request answered thirty
  seconds later is about a cursor that has moved, so [[lt.objs.clients.lsp]]'s
  queue is the wrong behaviour here even though it is the right one for the
  requests that are about a document.

  Declining is fine. Declining *silently* is what made \"toggle docs isn't
  working\" a real report: a language server takes seconds to minutes to
  index a project, every keypress in that window did nothing at all, and the
  statusbar was meanwhile saying \"Language server ready\" about some other
  connection. So a caller that a person triggered on purpose says which of
  these it was; the ones that fire while you type stay quiet."
  [ed method callback]
  (if-let [conn (conn-for ed method)]
    (if (lsp/ready? conn)
      (do (lsp/request! conn method
                        {:textDocument {:uri (:uri (::doc @ed))}
                         :position (sync/->position (editor/->cursor ed))}
                        callback)
          :sent)
      :not-ready)
    :no-server))

(defn- report-decline!
  "Say why nothing is going to happen, for a command a person ran on purpose."
  [outcome what]
  (case outcome
    :not-ready (notifos/set-msg! (str "The language server is still starting — "
                                      what " once it is ready."))
    :no-server (notifos/set-msg! (str "No language server for this file, so "
                                      what " has nothing to ask."))
    nil))

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
    (:documentSymbolProvider capabilities) (conj :navigable)
    (:documentFormattingProvider capabilities) (conj :formattable)
    (:documentRangeFormattingProvider capabilities) (conj :range-formattable)
    ;; codeActionProvider is `true` or an options map, and both mean yes.
    (:codeActionProvider capabilities) (conj :actionable)))

(behavior ::tag-from-capabilities
          :triggers #{:lsp.ready}
          :desc "Language server: Tag editors with what their server can do"
          :reaction (fn [_ result conn]
                      (doseq [ed (object/by-tag :editor)
                              :when (some #{conn} (conns ed))
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
                      (when-not (answered-elsewhere? ed :doc)
                        (-> (request-at-cursor!
                             ed "textDocument/hover"
                             (fn [{:keys [result]}]
                               (let [text (hover->text (:contents result))]
                                 (if (string/blank? text)
                                   (notifos/set-msg! "No documentation found.")
                                   (object/raise ed :editor.doc.show!
                                                 {:name (:string (editor/->token ed (editor/->cursor ed)))
                                                  :doc text
                                                  :loc (editor/->cursor ed)})))))
                            (report-decline! "documentation")))))

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
                      (when-not (answered-elsewhere? ed :jump)
                        (-> (request-at-cursor!
                             ed "textDocument/definition"
                             (fn [{:keys [result]}]
                               (if-let [{:keys [uri range]} (->location result)]
                                 (object/raise jump-stack/jump-stack :jump-stack.push!
                                               ed (sync/uri->path uri) (sync/range->loc range))
                                 (notifos/set-msg! "No definition found."))))
                            (report-decline! "jump to definition")))))

;;*********************************************************
;; References
;;*********************************************************

(defn- ->reference
  "One LSP location, as a line the search sidebar can list.

  The excerpt comes from the buffer when the file is open and from disk when
  it is not — a reference list that says only \"line 40\" is a list of numbers."
  [{:keys [uri range]}]
  (let [path (sync/uri->path uri)
        line (:line (sync/range->loc range))
        text (if-let [ed (first (pool/by-path path))]
               (editor/line ed line)
               (some-> (.readFileSync bridge/files path "utf-8")
                       (string/split #"\n")
                       (nth line nil)))]
    {:path path
     :line line
     :text (or (some-> text string/trim) "")}))

(defn- show-references!
  "Put `locations` in the search sidebar, grouped by file.

  The sidebar is Light Table's list of places in the project, and a reference
  list is exactly that — so this is the existing surface with a different
  question behind it, which is the point of the whole LSP layer."
  [locations]
  (object/raise search/searcher :clear!)
  (tabs/add! search/searcher)
  (tabs/active! search/searcher)
  (let [refs (map ->reference locations)]
    (doseq [[path rs] (group-by :path refs)]
      (object/raise search/searcher :result
                    (js-obj "file" path
                            "results" (into-array
                                       (for [r rs]
                                         (js-obj "line" (inc (:line r)) "text" (:text r)))))))
    (notifos/done-working (str (count refs) " reference"
                              (when-not (= 1 (count refs)) "s")))))

(behavior ::find-references
          :triggers #{:editor.find-references!}
          :type :user
          :desc "Editor: Find references with the language server"
          :doc "Lists every use of the symbol under the cursor in the search
                sidebar. There is no REPL equivalent worth deferring to — a
                running program knows what calls what only for code it has
                loaded — so this is the server's answer whenever there is one."
          :reaction (fn [ed]
                      (notifos/working "Finding references…")
                      (if-not (seq (conns ed))
                        (notifos/set-msg! "No language server for this editor.")
                        (when-let [conn (conn-for ed "textDocument/references")]
                          (lsp/request! conn "textDocument/references"
                                        {:textDocument {:uri (:uri (::doc @ed))}
                                         :position (sync/->position (editor/->cursor ed))
                                         :context {:includeDeclaration true}}
                                        (fn [{:keys [result]}]
                                          (if (seq result)
                                            (show-references! result)
                                            (notifos/done-working "No references found."))))))))

(cmd/command {:command :editor.find-references
              :desc "Editor: Find references"
              :exec (fn []
                      (when-let [ed (pool/last-active)]
                        (object/raise ed :editor.find-references!)))})

;;*********************************************************
;; Document symbols
;;*********************************************************

(def ^:private symbol-kinds
  "LSP's SymbolKind, numbered as the specification numbers them."
  {1 "file" 2 "module" 3 "namespace" 4 "package" 5 "class" 6 "method"
   7 "property" 8 "field" 9 "constructor" 10 "enum" 11 "interface"
   12 "function" 13 "variable" 14 "constant" 15 "string" 16 "number"
   17 "boolean" 18 "array" 19 "object" 20 "key" 21 "null" 22 "enum-member"
   23 "struct" 24 "event" 25 "operator" 26 "type-parameter"})

(defn- flatten-symbols
  "`DocumentSymbol[]` or `SymbolInformation[]`, as one flat list.

  Both shapes are legal and servers disagree about which to send. The nested
  one carries children; they are flattened with their names qualified, because
  a method is worth finding by its own name and worth reading with its class's."
  ([symbols] (flatten-symbols symbols nil))
  ([symbols prefix]
   (mapcat (fn [{:keys [name kind location range selectionRange children]}]
             (let [qualified (if prefix (str prefix "/" name) name)
                   loc (sync/range->loc (or selectionRange range (:range location)))]
               (cons {:name qualified
                      :kind (get symbol-kinds kind "symbol")
                      :path (some-> location :uri sync/uri->path)
                      :line (:line loc)
                      :ch (:ch loc)}
                     (flatten-symbols children qualified))))
           symbols)))

(defn- show-symbols!
  "List a file's definitions in the search sidebar.

  The same surface as references, and for the same reason: both are lists of
  places in the project, and Light Table already has one of those. A picker
  would have been a second thing to build, learn and keep working."
  [ed syms]
  (object/raise search/searcher :clear!)
  (tabs/add! search/searcher)
  (tabs/active! search/searcher)
  (object/raise search/searcher :result
                (js-obj "file" (-> @ed :info :path)
                        "results" (into-array
                                   (for [sym syms]
                                     (js-obj "line" (inc (:line sym))
                                             "text" (str (:kind sym) "  " (:name sym)))))))
  (notifos/done-working (str (count syms) " symbol"
                            (when-not (= 1 (count syms)) "s"))))

(behavior ::document-symbols
          :triggers #{:editor.document-symbols!}
          :type :user
          :desc "Editor: List this file's symbols from the language server"
          :doc "Every definition in the file, as somewhere to jump to. The
                `:navigable` tag is granted by the server advertising
                `documentSymbolProvider`, so a language gets this by its server
                saying it can answer."
          :reaction (fn [ed]
                      (when-let [conn (conn-for ed "textDocument/documentSymbol")]
                        (lsp/request! conn "textDocument/documentSymbol"
                                      {:textDocument {:uri (:uri (::doc @ed))}}
                                      (fn [{:keys [result]}]
                                        (let [syms (flatten-symbols result)]
                                          (if (seq syms)
                                            (show-symbols! ed syms)
                                            (notifos/set-msg! "No symbols found."))))))))

(cmd/command {:command :editor.document-symbols
              :desc "Editor: Jump to a symbol in this file"
              :exec (fn []
                      (when-let [ed (pool/last-active)]
                        (object/raise ed :editor.document-symbols!)))})

;;*********************************************************
;; Rename
;;*********************************************************

(defn- ->edits
  "A `WorkspaceEdit`, as the flat list [[lt.objs.workspace-edit]] applies.

  Both shapes are handled. `changes` maps a document URI to text edits and is
  what both servers Light Table ships with actually send; `documentChanges`
  wraps the same thing with a document version and is what the specification
  prefers. The version is ignored — `workspace-edit` refuses to touch a file
  with unsaved changes, which is a stronger check made against the thing that
  would actually be overwritten."
  [{:keys [changes documentChanges]}]
  (vec
   (concat
    (for [[uri edits] changes
          {:keys [range newText]} edits]
      {:path (sync/uri->path (name uri))
       :from (sync/->loc (:start range))
       :to (sync/->loc (:end range))
       :text newText})
    (for [{:keys [textDocument edits]} documentChanges
          {:keys [range newText]} edits]
      {:path (sync/uri->path (:uri textDocument))
       :from (sync/->loc (:start range))
       :to (sync/->loc (:end range))
       :text newText}))))

(defn- rename! [ed new-name]
  (when-let [conn (conn-for ed "textDocument/rename")]
    (lsp/request!
     conn "textDocument/rename"
     {:textDocument {:uri (:uri (::doc @ed))}
      :position (sync/->position (editor/->cursor ed))
      :newName new-name}
     (fn [{:keys [result error]}]
       (cond
         error (notifos/set-msg! (str "Rename failed: " (:message error)) {:class "error"})
         (nil? result) (notifos/set-msg! "The server had nothing to rename." {:class "error"})
         :else
         (let [edits (->edits result)
               outcome (we/apply! (str "rename to " new-name) edits)]
           (if (:error outcome)
             (notifos/set-msg! (:error outcome) {:class "error"})
             (notifos/set-msg! (str "Renamed " (:edits outcome) " occurrence"
                                    (when-not (= 1 (:edits outcome)) "s")
                                    " in " (:files outcome) " file"
                                    (when-not (= 1 (:files outcome)) "s")
                                    " — Editor: Undo workspace edit to take it back")))))))))

(defn- apply-to-editor!
  "Apply LSP text edits to the buffer `ed` is showing, as one undoable change.

  Not through [[lt.objs.workspace-edit]], and the difference is the point.
  That namespace is for changing files the user is not looking at: it refuses
  a buffer with unsaved changes and replaces whole files, because what it is
  protecting against is a tab and a disk disagreeing. Formatting is the
  opposite situation — the buffer is in front of you, it is usually dirty
  because you have just been typing in it, and the edits are small and
  positional.

  So these go straight into the editor, last-first, inside one
  `editor/operation`: CodeMirror's own undo takes the whole format back in one
  keystroke, and applying in reverse means no edit moves the range of the next
  one. `we-text/ordered` is the same sort the file path uses — the arithmetic
  that gets edits wrong is worth having in exactly one place."
  [ed edits]
  (when (seq edits)
    (editor/operation
     ed
     (fn []
       (doseq [{:keys [from to text]} (we-text/ordered edits)]
         (editor/replace ed from to text))))
    (count edits)))

(defn- ->text-edits
  "`TextEdit[]` from a server, in the shape [[apply-to-editor!]] takes."
  [result]
  (vec (for [{:keys [range newText]} result]
         {:from (sync/->loc (:start range))
          :to (sync/->loc (:end range))
          :text newText})))

(defn- formatting-options
  "What the server should format to, taken from the editor rather than guessed.

  A server that indents with four spaces in a project that uses two is a
  formatter people turn off, so this reports what this editor is actually
  configured with — which is what `:lt.objs.editor/tab-settings` sets."
  [ed]
  {:tabSize (or (editor/option ed "tabSize") 4)
   :insertSpaces (not (editor/option ed "indentWithTabs"))})

(defn- format!
  "Format the whole buffer, or the selection when there is one.

  Range formatting when something is selected, because that is what the
  selection means, and only when the server offers it — a server with just
  `documentFormattingProvider` formats the file, which is a surprise worth
  avoiding when the user asked about four lines."
  [ed]
  (let [selection? (and (editor/selection? ed)
                        (object/has-tag? ed :range-formattable))
        method (if selection?
                 "textDocument/rangeFormatting"
                 "textDocument/formatting")]
    ;; One formatter, never two. Two servers both willing to format a file will
    ;; not agree about it, and applying both means the second undoes the first.
    ;; The later declaration wins, which is how a project puts biome in front of
    ;; the type checker that would otherwise answer.
    (when-let [conn (conn-for ed method)]
      (let [params (cond-> {:textDocument {:uri (:uri (::doc @ed))}
                            :options (formatting-options ed)}
                     selection?
                     (assoc :range {:start (sync/->position (editor/->cursor ed "start"))
                                    :end (sync/->position (editor/->cursor ed "end"))}))]
        (notifos/working "Formatting")
        (lsp/request!
         conn method params
         (fn [{:keys [result error]}]
           (notifos/done-working)
           (cond
             error
             (notifos/set-msg! (str "Formatting failed: " (:message error)) {:class "error"})

             ;; A server with nothing to change answers with an empty list, and
             ;; one that declined answers null. Both mean the same thing here.
             (empty? result)
             (notifos/set-msg! "Nothing to format.")

             :else
             (let [n (apply-to-editor! ed (->text-edits result))]
               (notifos/set-msg! (str "Formatted — " n " change"
                                      (when-not (= 1 n) "s")))))))))))

(defn- diagnostics-in-range
  "The server's own diagnostics overlapping `range`, sent back with the request.

  Required by the protocol rather than optional: a quick fix is a fix *for a
  diagnostic*, and a server given no diagnostics has nothing to offer a fix
  for. This is why asking on a line with a red squiggle produces actions and
  asking two lines above it produces none.

  Only what `conn` itself published. Handing a server another server's
  diagnostics asks it for a fix for a problem it does not know it has."
  [ed conn]
  (let [{:keys [line]} (editor/->cursor ed)]
    (->> (get (::diagnostics @ed {}) conn)
         (filter (fn [d]
                   (let [start (get-in d [:range :start :line])
                         end (get-in d [:range :end :line])]
                     (and start end (<= start line end)))))
         vec)))

(defn- action-title [action]
  (or (:title action) "(untitled action)"))

(defn- action-button [popup action cb]
  ;; Its own class, because the popup's cancel is an `li.button` too.
  [:li.button.lsp-action {:on {:click (fn []
                                        (cb action)
                                        (when-let [p @popup] (object/raise p :close!)))}}
   (action-title action)])

(defn- offer-actions!
  "Ask which action, then run it.

  No object of its own. An earlier version created one to hold the callback,
  the way lt.objs.connector's client selector does, and destroyed it when a
  button was clicked — so dismissing the popup with Esc instead left the
  object behind, tagged and reachable, holding a closure over the editor.
  Closing over the callback here means there is nothing to clean up."
  [actions cb]
  (let [popup (atom nil)]
    (reset! popup
            (popup/popup! {:header "What would you like to do?"
                           :body [:ul.lsp-actions
                                  (map #(action-button popup % cb) actions)]
                           :buttons [popup/cancel-button]}))))

(defn- run-action!
  "Do what a chosen action says: an edit, a command, or both.

  A `CodeAction` may carry `:edit`, `:command`, or both, and the order is the
  specification's — the edit first, then the command. A `Command` on its own
  is the older shape and is still what several servers send.

  The edit goes through [[lt.objs.workspace-edit]] rather than into the
  editor: unlike formatting, a code action routinely rewrites files you are
  not looking at — an import added at the top of another module, a symbol
  renamed where it is used — and that is exactly what that namespace is for."
  [ed action]
  (let [conn (conn-for ed "textDocument/codeAction")]
    (when-let [edit (:edit action)]
      (let [outcome (we/apply! (action-title action) (->edits edit))]
        (if (:error outcome)
          (notifos/set-msg! (:error outcome) {:class "error"})
          (notifos/set-msg! (str (action-title action) " — "
                                 (:files outcome) " file"
                                 (when-not (= 1 (:files outcome)) "s")
                                 " changed, Editor: Undo workspace edit to take it back")))))
    (when-let [command (:command action)]
      ;; A Command nested inside a CodeAction, or the action itself when the
      ;; server sent the older shape.
      (let [cmd (if (string? (:command command)) command action)]
        (lsp/request! conn "workspace/executeCommand"
                      {:command (:command cmd) :arguments (or (:arguments cmd) [])}
                      (fn [{:keys [error]}]
                        (when error
                          (notifos/set-msg! (str "Action failed: " (:message error))
                                            {:class "error"}))))))))

(defn- code-actions! [ed]
  (when-let [conn (conn-for ed "textDocument/codeAction")]
    (let [from (if (editor/selection? ed) (editor/->cursor ed "start") (editor/->cursor ed))
          to (if (editor/selection? ed) (editor/->cursor ed "end") (editor/->cursor ed))]
      (notifos/working "Asking for code actions")
      (lsp/request!
       conn "textDocument/codeAction"
       {:textDocument {:uri (:uri (::doc @ed))}
        :range {:start (sync/->position from) :end (sync/->position to)}
        :context {:diagnostics (diagnostics-in-range ed conn)}}
       (fn [{:keys [result error]}]
         (notifos/done-working)
         (cond
           error (notifos/set-msg! (str "Code actions failed: " (:message error))
                                   {:class "error"})
           (empty? result) (notifos/set-msg! "No code actions here.")
           ;; One action is not a choice. Offering a popup with a single
           ;; button in it is a dialog that exists to be dismissed.
           (= 1 (count result)) (run-action! ed (first result))
           :else (offer-actions! result #(run-action! ed %))))))))

(behavior ::code-actions
          :triggers #{:editor.code-actions!}
          :type :user
          :desc "Editor: Offer the language server's code actions"
          :doc "Asks what can be done at the cursor, or over the selection —
                quick fixes for a diagnostic, adding a missing import,
                extracting a function. An action that edits files applies as
                one workspace edit, so `Editor: Undo workspace edit` takes the
                whole thing back."
          :reaction (fn [ed]
                      (cond
                        (empty? (conns ed))
                        (notifos/set-msg! "No language server for this editor.")

                        (not (object/has-tag? ed :actionable))
                        (notifos/set-msg! "This language server offers no code actions.")

                        (answered-elsewhere? ed :code-action)
                        (notifos/set-msg! "Something else is answering code actions here.")

                        :else (code-actions! ed))))

(cmd/command {:command :editor.code-actions
              :desc "Editor: Code actions"
              :exec (fn []
                      (when-let [ed (pool/last-active)]
                        (object/raise ed :editor.code-actions!)))})

(behavior ::format
          :triggers #{:editor.format!}
          :type :user
          :desc "Editor: Format with the language server"
          :doc "Formats the buffer, or the selection when there is one and the
                server offers range formatting. One undo takes it back."
          :reaction (fn [ed]
                      (cond
                        (empty? (conns ed))
                        (notifos/set-msg! "No language server for this editor.")

                        (not (object/has-tag? ed :formattable))
                        (notifos/set-msg! "This language server does not format.")

                        :else (format! ed))))

(behavior ::format-on-save
          :triggers #{:save}
          :type :user
          :desc "Editor: Format with the language server on save"
          :doc "Off by default. Formatting on save is a strong opinion about
                somebody else's project, so it is a line in user.behaviors
                rather than a default — which is what behaviors are for."
          :reaction (fn [ed]
                      (when (and (seq (conns ed)) (object/has-tag? ed :formattable))
                        (format! ed))))

(cmd/command {:command :editor.format
              :desc "Editor: Format"
              :exec (fn []
                      (when-let [ed (pool/last-active)]
                        (object/raise ed :editor.format!)))})

(behavior ::rename
          :triggers #{:editor.rename!}
          :type :user
          :desc "Editor: Rename a symbol with the language server"
          :doc "Renames every use of the symbol under the cursor, across the
                project, as one action. `Editor: Undo workspace edit` puts it
                back — the whole thing, not one file at a time.

                Files with unsaved changes are refused rather than overwritten:
                a workspace edit works on what is on disk, so what it took away
                is what it can put back."
          :reaction (fn [ed new-name]
                      (if-not (seq (conns ed))
                        (notifos/set-msg! "No language server for this editor.")
                        (rename! ed new-name))))

(cmd/command {:command :editor.rename
              :desc "Editor: Rename symbol"
              :options (scmd/options-input {:placeholder "new name"})
              :exec (fn [new-name]
                      (when-let [ed (pool/last-active)]
                        (when-not (string/blank? new-name)
                          (object/raise ed :editor.rename! new-name))))})

(cmd/command {:command :editor.undo-workspace-edit
              :desc "Editor: Undo workspace edit"
              :exec (fn []
                      (let [outcome (we/undo!)]
                        (notifos/set-msg!
                         (if (:error outcome)
                           (:error outcome)
                           (str "Undid " (:label outcome) " across "
                                (:files outcome) " file"
                                (when-not (= 1 (:files outcome)) "s"))))))})

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
          :reaction (fn [_ {:keys [method params]} conn]
                      (case method
                        "textDocument/publishDiagnostics"
                        (doseq [ed (editors-for-uri (:uri params))]
                          (draw-diagnostics! ed conn (:diagnostics params)))

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
        declared (servers-for (:tags @ed))
        ;; The singular keys describe the last-declared server, which is the
        ;; one a single-server language has and the one every surface both
        ;; offer falls to. `:servers` is the whole list.
        server (last declared)
        root (when (and path server) (project-root path (:root server)))
        connected (conns ed)]
    {:path path
     :language-id (:language-id server)
     :command (:command server)
     :markers (:root server)
     :root root
     :found (when root (server-command root (:command server)))
     :connected? (boolean (seq connected))
     :ready? (boolean (some #(lsp/ready? %) connected))
     :diagnostics (count (::widgets @ed))
     :servers (vec (for [s declared]
                     {:command (:command s)
                      :language-id (:language-id s)
                      :root (when path (project-root path (:root s)))}))
     :connections (count connected)}))

(defn status-line
  "One sentence saying which of the ways this can be quiet is the one in play."
  [{:keys [path language-id command markers root found connected? ready?
           diagnostics servers connections]}]
  (cond
    (nil? path) "This editor is not backed by a file."
    (nil? language-id) "No language server is configured for this file type."
    (nil? root) (str "No project root above " path " — looked for "
                     (string/join ", " markers))
    (nil? found) (str "No " command " in " root "/node_modules/.bin, or on PATH. "
                      "Install it in the project, or globally.")
    (not connected?) (str "Found " found ", but this editor is not connected to it.")
    (not ready?) (str "Starting " found " …")
    ;; Which of them, by name. Two servers for one language is the case where
    ;; "connected" on its own answers the wrong question — the one that is
    ;; missing is the one you are asking about.
    (> (count servers) 1)
    (str "Connected to " connections " of " (count servers) " servers ("
         (string/join ", " (map :command servers)) ") — " diagnostics
         (if (= 1 diagnostics) " diagnostic" " diagnostics") " on screen")
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
