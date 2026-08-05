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
  (:require [clojure.string :as string]
            [lt.object :as object]
            [lt.objs.clients :as clients]
            [lt.objs.clients.agent :as agent]
            [lt.objs.command :as cmd]
            [lt.objs.context :as ctx]
            [lt.objs.editor :as editor]
            [lt.objs.editor.lsp :as lsp]
            [lt.objs.editor.lsp.situation :as situation]
            [lt.objs.editor.pool :as pool]
            [lt.objs.keyboard :as kb]
            [lt.objs.settings :as settings]
            [lt.objs.tabs :as tabs]
            [lt.state :as state]))

(defn- path-of [obj]
  (-> @obj :info :path))

(defn- tab-id
  "What a tab is called in the state. The path when there is one, because that
  is what a result is keyed by and the two have to agree."
  [obj]
  (or (path-of obj) (str "obj-" (object/->id obj))))

(defn- tab
  "One tab, as the strip draws it.

  The label is `tabs/->name` rather than the leaf of the path, because most
  tabs are not files: the console, the plugin manager, the component kit and a
  browser all have a name and no path at all, and a strip that took the leaf of
  `obj-42` would draw exactly that."
  [obj]
  {:id (tab-id obj)
   ;; A string for the same reason `:name` is one below: a browser tab's name
   ;; is the page's title, which is whatever the page said.
   :label (str (tabs/->name obj))
   :path (path-of obj)
   :dirty? (boolean (:dirty @obj))
   ;; Per tab, because it is a `:close-button+` raise-reduce and a user
   ;; behavior — off unless you turn it on. See `lt.objs.tabs`.
   :closable? (boolean (object/raise-reduce obj :close-button+ false))})

(defn tabsets
  "The open tabs, per tabset, and which tabset and tab are active.

  In the order they are on screen — `(:tabsets @tabs/multi)` is left to right,
  where `object/by-tag` is whatever order the registry happens to hold.

  Which tabset is active is read from the context rather than from a tabset,
  because that is where it lives: `activate-tabset` puts it in `ctx` and a CSS
  class, and no atom has ever held it."
  []
  (let [active-ts (ctx/->obj :tabset)]
    (vec (for [ts (:tabsets @tabs/multi)
               :let [objs (:objs @ts)]]
           {:id (object/->id ts)
            :active? (= ts active-ts)
            :width (:width @ts)
            :tabs (mapv tab objs)
            :active (or (some (fn [[i o]] (when (= o (:active-obj @ts)) i))
                              (map-indexed vector objs))
                        0)}))))

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
                    ;; A string, whatever the client put there. `:name` is set
                    ;; by whoever made the connection — a URL, a keyword, or in
                    ;; one case a JavaScript object — and a projection's job is
                    ;; to hand the view data it can draw. Replicant throws on
                    ;; anything else, from inside its own render, which is five
                    ;; identical console lines and no address.
                    {:name (some-> (:name @c) str)
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
  "Evaluation results, keyed `[path line]`. Empty for now.

  This projected LSP diagnostics, to show that the address in [[lt.state]] was
  not hypothetical — a diagnostic being the one thing there was real data for.
  It was a demonstration and it became a duplicate: `lt.ui.bands` is installed
  on real editors and draws a band per `:results` entry, while
  `lt.objs.editor.lsp/draw-diagnostics!` draws its own line widget for the same
  diagnostic. One diagnostic, two things under the line, saying the same
  sentence in two styles.

  Latent until `::sync-from-language-servers` made the projection keep up, and
  then visible on every diagnostic — which is what a demonstration turning into
  a feature looks like. A diagnostic is also not a result: it is what the file
  says, not what running it produced, and the two want different addresses in
  the end.

  Kept as a function returning nothing rather than deleted, because the shape
  is what `:results` will hold when evaluation is projected, and `lt.ui.bands`
  is already the thing that will draw it."
  []
  {})

(defn commands
  "Every command, for the command bar, as `{:label :action}` pairs.

  Commands are Light Table's own registry rather than [[lt.actions]]'s, and
  both are tables of things a key can be bound to. Presenting them as one list
  is the first place the two registries meet."
  []
  (vec (for [[k c] (:commands @cmd/manager)]
         {:label (or (:desc c) (name k)) :action [:cmd/exec k]})))

(defn keymap
  "The keys that are live right now, as `key -> [action …]`.

  `lt.objs.keyboard/key-map` rather than `keys`: `keys` is a map per context and
  `key-map` is the merge of the contexts you are actually in, which is what a
  keystroke will really do. A screen listing every context's binding would be
  listing bindings that cannot fire.

  A command becomes `[:cmd/exec …]` so that the keymap and [[lt.actions]]'s
  table are one list of the same kind of thing. That is the claim
  [[lt.actions]]'s docstring makes — the keymap is a view over the dispatch
  table — and it was not true of anything until something projected it."
  []
  (into {} (for [[k commands] @kb/key-map]
             [k (vec (for [c commands]
                       (if (coll? c)
                         (into [:cmd/exec] c)
                         [:cmd/exec c])))])))

(defn- param-values
  "What a behavior's parameters are currently set to, for `tag`.

  `@object/tags` holds `tag -> [behavior-or-list …]`, where a behavior with
  arguments is a list of the keyword and its arguments. So the values are
  already here; nothing computes them."
  [tag behavior]
  (->> (get @object/tags tag)
       (keep (fn [entry]
               (when (and (coll? entry) (= behavior (first entry)))
                 (vec (rest entry)))))
       first))

(defn- attached?
  [tag behavior]
  (boolean (some (fn [entry]
                   (= behavior (if (coll? entry) (first entry) entry)))
                 (get @object/tags tag))))

(defn- unquoted
  "`\"dark\"` for `\"\\\"dark\\\"\"`.

  The hinter's completions are EDN literals, quotes included, because what they
  are for is being inserted into a behaviors file. A `<select>`'s option has to
  match what `:values` holds, which is the string itself."
  [s]
  (if (and (string? s) (> (count s) 1)
           (string/starts-with? s "\"") (string/ends-with? s "\""))
    (subs s 1 (dec (count s)))
    s))

(defn- ->option
  "One `:list` option, as something a view can draw and compare.

  `get-themes` and `get-skins` answer with the **autocomplete hinter's** objects
  — `#js {:text \"\\\"dark\\\"\" :completion \"\\\"dark\\\"\"}` — because that is
  what they were written for. Two calls build two sets of JS objects that are
  never `=`, so leaving them in place keeps the window permanently disagreeing
  with itself."
  [item]
  (cond
    (string? item) (unquoted item)
    (vector? item) item
    (object? item) (unquoted (or (aget item "completion") (aget item "text") ""))
    (keyword? item) (name item)
    :else (str item)))

(defn- resolved-param
  "One parameter declaration, as data.

  `:items` on a `:list` parameter is a **function** in every declaration in the
  tree — `get-themes` reads what the plugins provided — and neither the function
  nor what it answers with may reach the state atom. Three reasons, and the
  third is the one that bit: the state is data by contract, a function cannot be
  logged or replayed, and two calls to `snapshot` produce two objects that are
  not `=`, so `lt.objs.control/drift` reports the window as disagreeing with
  itself for ever. It did, twice — once for the function and once for the JS
  objects behind it — and that check is how both were found.

  Resolved here rather than at render, which is also the better place for it: a
  projection happens on a trigger and a render happens whenever. Defensively,
  because it reaches into the object world and a throw here would take the whole
  projection with it."
  [param]
  (if-let [items (:items param)]
    (assoc param :items
           (try (mapv ->option (if (fn? items) (items) items))
                (catch :default _ [])))
    param))

(defn settings-entries
  "The behaviors a person can set, with what they are set to.

  `:type :user` is the marker Light Table has used since 2013 for \"this one is
  configuration rather than machinery\", and it is what makes a settings screen
  generatable rather than written: a behavior already carries a description, its
  parameters, and each parameter's type. 176 behaviors exist and 49 of them say
  they are yours.

  What this adds is the two things the registry does not hold — which tag a
  behavior is attached to, and which file attached it. The second is
  [[lt.objs.settings/where-from]], and it is the answer to the question a
  settings screen is otherwise unable to answer: *why is this not the default,
  and where do I go to change it back?*

  Deliberately not sorted here. Order is a decision about what to show and
  [[lt.ui.view]] is where those go."
  []
  (vec (for [[behavior beh] @object/behaviors
             :when (= :user (:type beh))
             tag (keys @object/tags)
             :when (attached? tag behavior)]
         {:behavior behavior
          :tag tag
          :desc (or (:desc beh) (name behavior))
          :params (mapv resolved-param (:params beh))
          :values (or (param-values tag behavior) [])
          :exclusive? (boolean (:exclusive beh))
          :from (settings/where-from tag behavior)})))

(defn cursor
  "Where the cursor is, in the editor that has it."
  []
  (when-let [ed (pool/last-active)]
    (try
      (editor/->cursor ed)
      (catch :default _ nil))))

(defn language-server
  "What the language server is doing for the editor you are in.

  Nil when no server is configured for this file type, which is most files and
  is not a state worth drawing. Everything else is: a server that is starting,
  one that is declared and not installed, and one that is answering are three
  different reasons for the same silence, and telling them apart used to mean
  knowing that `:lsp.status` exists.

  `lt.objs.editor.lsp/status` gathers the facts and
  [[lt.objs.editor.lsp.situation/indicator]] decides what they mean — the same
  decision the `:lsp.status` sentence makes, which is why it is made once. This
  had its own copy of that `cond` and its own copy of the bug in it."
  []
  (when-let [ed (pool/last-active)]
    (situation/indicator (lsp/status ed))))

(defn snapshot
  "The whole projection, as one value."
  []
  {:tabsets (tabsets)
   :editors (editors)
   :clients (clients*)
   :results (results)
   :cursor (or (cursor) {:line 0 :ch 0})
   :lsp (language-server)
   :keymap (keymap)
   :settings {:entries (settings-entries)}
   :command-bar {:commands (commands)}})

(defn sync!
  "Put the projection into the state atom, keeping everything it does not own.

  Merged rather than reset, because runs, watches and review are the state's
  own — nothing in the object world has them, and a projection that reset would
  delete them every time a tab opened.

  Two keys are *partly* projected, which is why they are spliced rather than
  merged wholesale: the command bar's list of commands is the object world's and
  its query and selection are not, and the settings screen's entries are the
  object world's while which half you are looking at is not. Getting that
  backwards means a keystroke in a filter box being erased by an unrelated tab
  opening, which is precisely what happened to the command bar before this
  distinction existed."
  []
  (swap! state/app
         (fn [s]
           (let [snap (snapshot)]
             (-> (merge s (dissoc snap :command-bar :settings))
                 (assoc :command-bar (merge (:command-bar s) (:command-bar snap)))
                 (assoc :settings (merge (:settings s) (:settings snap))))))))
