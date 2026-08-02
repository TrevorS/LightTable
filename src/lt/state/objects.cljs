(ns lt.state.objects
  "Projecting the running editor into the state atom.

  The views are functions of one value, and the editor's facts are spread
  across a hundred objects — so something has to carry them across. This is
  that, in one namespace, so it is countable and so it can be deleted.

  It reads and does not write. Nothing here changes an object, which is what
  keeps it a projection rather than a second source of truth: if a view is
  wrong the answer is either here or in the view, never in a third place where
  two copies drifted.

  It goes away as each surface moves. When the tabs are a view, the tab
  projection below is dead code, and the day the last one is dead this
  namespace is the diff that removes it."
  (:require [lt.object :as object]
            [lt.objs.clients :as clients]
            [lt.objs.clients.agent :as agent]
            [lt.objs.command :as cmd]
            [lt.objs.editor :as editor]
            [lt.objs.editor.lsp :as lsp]
            [lt.objs.editor.pool :as pool]
            [lt.state :as state]))

(defn- path-of [obj]
  (-> @obj :info :path))

(defn- tab-id
  "What a tab is called in the state. The path when there is one, because that
  is what a result is keyed by and the two have to agree."
  [obj]
  (or (path-of obj) (str "obj-" (object/->id obj))))

(defn tabsets
  "The open tabs, per tabset, and which is active."
  []
  (vec (for [ts (object/by-tag :tabset)
             :let [objs (:objs @ts)]]
         {:id (object/->id ts)
          :tabs (mapv tab-id objs)
          :active (or (some (fn [[i o]] (when (= o (:active-obj @ts)) i))
                            (map-indexed vector objs))
                      0)})))

(defn editors
  "Every open editor, by path."
  []
  (into {} (for [ed (object/by-tag :editor)
                 :let [path (path-of ed)]
                 :when path]
             [path {:dirty? (boolean (:dirty @ed))
                    :lang (-> @ed :info :type)}])))

(defn clients*
  "Every connected client. The window itself is one of them, which is the
  point of the panel — an evaluation reaching Light Table is not a special
  case, it is a client with a name."
  []
  ;; What the buffer you are in already evaluates through. `:bound?` is read
  ;; from that rather than stored anywhere, which is what makes the panel's
  ;; claim — \"this is where an eval goes\" — true instead of asserted.
  (let [bound (set (some-> (pool/last-active) deref :client vals))]
    (into {} (for [[id c] @clients/cs
                   :when @c]
               [id (merge
                    {:name (:name @c)
                     :kind (cond
                             (object/has-tag? c :client.agent) :agent
                             (object/has-tag? c :nrepl.client) :nrepl
                             (object/has-tag? c :client.local) :self
                             (object/has-tag? c :clients.devtools) :browser
                             :else :client)
                     :status (if (clients/available? c) :finished :lost)
                     :bound? (contains? bound c)}
                    ;; An agent is a client like the others and has one thing
                    ;; they do not: where its work actually runs.
                    (when (object/has-tag? c :client.agent)
                      (select-keys (agent/state) [:status :via])))]))))

(defn results
  "Diagnostics, as results keyed the way the design keys them.

  A diagnostic is the one thing the editor already puts between two lines, so
  it is the one thing there is real data for — and keying it `[path line]` here
  is what says the address in [[lt.state]] is not hypothetical."
  []
  (into {} (for [ed (object/by-tag :editor)
                 :let [path (path-of ed)]
                 :when path
                 d (lsp/diagnostics ed)
                 :let [line (get-in d [:range :start :line])]
                 :when line]
             [[path line] {:status :finished
                           :value (:message d)
                           :mime "text/plain"
                           :from (:source d)}])))

(defn commands
  "Every command, for the command bar, as `{:label :action}` pairs.

  Commands are Light Table's own registry rather than [[lt.actions]]'s, and
  both are tables of things a key can be bound to. Presenting them as one list
  is the first place the two registries meet."
  []
  (vec (for [[k c] (:commands @cmd/manager)]
         {:label (or (:desc c) (name k)) :action [:cmd/exec k]})))

(defn cursor
  "Where the cursor is, in the editor that has it."
  []
  (when-let [ed (pool/last-active)]
    (try
      (editor/->cursor ed)
      (catch :default _ nil))))

(defn snapshot
  "The whole projection, as one value."
  []
  {:tabsets (tabsets)
   :editors (editors)
   :clients (clients*)
   :results (results)
   :cursor (or (cursor) {:line 0 :ch 0})
   :command-bar {:commands (commands)}})

(defn sync!
  "Put the projection into the state atom, keeping everything it does not own.

  Merged rather than reset, because runs, watches, review and the keymap are
  the state's own — nothing in the object world has them, and a projection that
  reset would delete them every time a tab opened."
  []
  (swap! state/app
         (fn [s]
           (let [snap (snapshot)]
             (-> (merge s (dissoc snap :command-bar))
                 ;; The command bar's own state — open, query, selection — is
                 ;; the state's, and only its list of commands is projected.
                 (assoc :command-bar (merge (:command-bar s) (:command-bar snap))))))))
