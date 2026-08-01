(ns lt.objs.control
  "Driving Light Table from outside it.

  Everything here answers one of three questions an agent has and a human does
  not: what is open, did that work, and what is it waiting for. The editor
  could already be driven — `script/lt-repl.sh` has been doing it — but only by
  evaluating JavaScript and reading the answer out of the DOM: the statusbar's
  text for whether an eval succeeded, `li.error` for whether it failed,
  `.inline-result` for what it produced. That reads the rendering instead of
  the fact, and it breaks when the markup changes.

  ## Shaped for MCP

  This is meant to be wrapped in an MCP server, so it borrows that protocol's
  shapes rather than inventing its own and translating later. Three things
  from the 2026-07-28 revision decided the design:

  **State lives in handles, not in the connection.** MCP is stateless now:
  cross-call state is carried by server-minted handles passed as ordinary
  arguments. So nothing here means \"the current editor\" — [[snapshot]] gives
  every editor an id and every call takes one back. `pool/last-active` is a
  fine idea for a keyboard and a bad one for a caller that cannot see the
  screen.

  **Slow things return a handle, not a result.** Evaluation, a language-server
  request, a project-wide search: all of them take longer than a call should
  block for. [[start!]] returns a job id and [[job]] is polled until it
  reaches a terminal status. The statuses are MCP's exactly — `working`,
  `input_required`, `completed`, `failed`, `cancelled` — so a Tasks-extension
  wrapper is an adapter rather than a translation.

  **A question is a result, not an interruption.** MCP replaced
  server-initiated requests with `input_required`: a call comes back saying
  what it needs, and the caller retries with answers. Light Table's modals are
  exactly that shape — \"which of these code actions\", \"you have unsaved
  changes\" — so a job that opens one moves to `input_required` and carries
  what it is asking. [[answer!]] is the retry.

  ## What this is not

  Not a security boundary. Anything that can call this can already evaluate
  ClojureScript in the window, which is the whole of Light Table. It exists to
  make driving the editor legible, not to make it safe."
  (:require [clojure.string :as string]
            [lt.object :as object]
            [lt.objs.clients.agent :as agent]
            [lt.objs.cljs-compiler :as cljs-compiler]
            [lt.objs.clients :as clients]
            [lt.objs.editor :as editor]
            [lt.objs.command :as cmd]
            [lt.objs.editor.pool :as pool]
            [lt.objs.files :as files]
            [lt.objs.tabs :as tabs]
            [lt.objs.workspace :as workspace]
            [lt.util.dom :as dom]))

;;*********************************************************
;; What it is waiting for
;;*********************************************************

(defn- prompt-choices
  "The clickable choices in a popup, as text.

  Read out of the DOM because that is where they are: a popup's body is
  markup, and the buttons a caller has to pick between are `li` elements in
  it. Everything else here is data; this one is honest about not being."
  [p]
  (let [content (object/->content p)]
    (->> (array-seq (dom/$$ :li.button content))
         (map #(string/trim (or (.-textContent ^js %) "")))
         (remove string/blank?)
         vec)))

(defn- prompt-header
  "What the popup is asking.

  Out of the rendered `h2` rather than the object: a popup is created with its
  options and draws from them, and does not keep them, so `(:header @p)` is
  empty for every popup there is."
  [p]
  (let [^js h (dom/$ :h2 (object/->content p))]
    (string/trim (or (and h (.-textContent h)) ""))))

(defn prompts
  "Anything open and waiting for an answer, newest last.

  A modal is the one thing that can stop a caller dead: it takes the focus,
  it blocks what is behind it, and nothing about it is visible to code that
  is looking at editors. In MCP terms these are `inputRequests`."
  []
  (->> (object/by-tag :popup)
       (mapv (fn [p]
               {:id (object/->id p)
                :header (prompt-header p)
                :choices (prompt-choices p)
                :object p}))
       (sort-by :id)
       vec))

(defn answer!
  "Answer an open prompt by choice text, or dismiss it with nil.

  Matched case-insensitively on the visible text, because that is what the
  caller was shown and asking it to count buttons would be worse."
  [prompt-id choice]
  (if-let [p (first (filter #(= prompt-id (:id %)) (prompts)))]
    (let [content (object/->content (:object p))
          buttons (array-seq (dom/$$ :li.button content))
          wanted (when choice (string/lower-case (str choice)))
          hit (first (filter (fn [^js b]
                               (and wanted
                                    (= wanted (string/lower-case
                                               (string/trim (or (.-textContent b) ""))))))
                             buttons))]
      (cond
        (nil? choice) (do (object/raise (:object p) :close!)
                          {:answered prompt-id :choice nil})
        hit (do (.click ^js hit) {:answered prompt-id :choice choice})
        :else {:error (str "No choice called " (pr-str choice))
               :choices (:choices p)}))
    {:error (str "No prompt " prompt-id)
     :prompts (mapv #(dissoc % :object) (prompts))}))

;;*********************************************************
;; What is open
;;*********************************************************

(defn- editor->map
  "One editor, as the little a caller needs to name it and know its state."
  [ed]
  (let [cursor (try (editor/->cursor ed) (catch :default _ nil))]
    (cond-> {:id (object/->id ed)
             :path (not-empty (tabs/->path ed))
             :dirty (boolean (:dirty @ed))
             :tags (vec (sort (map str (:tags @ed))))}
      cursor (assoc :line (:line cursor) :ch (:ch cursor)))))

(defn editors
  "Every open editor, in tab order.

  Sorted the way they are drawn rather than by id, and deterministically —
  MCP asks servers to return lists in a stable order so a caller can cache
  them, and a list that reshuffles is one nothing can diff."
  []
  (->> (mapcat #(:objs @%) (:tabsets @tabs/multi))
       (filter #(object/has-tag? % :editor))
       (mapv editor->map)))

(defn- client->map [client]
  (let [c @client]
    {:name (:name c)
     :type (:type c)
     :connected (boolean (:connected c))
     :commands (vec (sort (map str (:commands c))))
     :provides (vec (sort (map str (:provides c))))}))

(defn clients-open
  "Every connected client — REPLs, browsers, the window itself."
  []
  (->> (vals @clients/cs)
       (map client->map)
       (sort-by :name)
       vec))

(defn snapshot
  "What the editor is, right now, as data.

  The one call a caller makes before anything else. Everything in it is
  JSON-able: no objects, no functions, no DOM."
  []
  {:editors (editors)
   :clients (clients-open)
   :workspace {:folders (vec (sort (:folders @workspace/current-ws)))
               :files (vec (sort (:files @workspace/current-ws)))}
   :prompts (mapv (fn [p] (dissoc p :object)) (prompts))
   :errors (count @object/errors)})

;;*********************************************************
;; Did it work
;;*********************************************************

(defn errors
  "Behavior errors since the last [[clear-errors!]], newest first.

  `lt.object` catches what a reaction throws so one bad behavior cannot take
  the editor down, which leaves a caller unable to tell a failure from a
  decision not to act. This is that difference."
  []
  (vec @object/errors))

(defn clear-errors!
  "Forget the errors so far. Call it before trying something, then read
  [[errors]] after — which is the only way to attribute one."
  []
  (object/clear-errors!)
  {:cleared true})

;;*********************************************************
;; Jobs
;;*********************************************************

;; Every job ever started this session, by id. Bounded by nothing: a session
;; that starts thousands of jobs has a bigger problem than this map.
(defonce ^:private jobs (atom {}))
(defonce ^:private next-job-id (atom 0))

(def statuses
  "The five a job can be in. MCP's exactly, so a wrapper does not translate."
  #{"working" "input_required" "completed" "failed" "cancelled"})

(defn- job-snapshot [j]
  (dissoc j :cancel :prompts-before))

(defn job
  "The state of `id`, or nil.

  Polled until `:status` is terminal — `completed`, `failed` or `cancelled`.
  A job in `input_required` is waiting for [[answer!]] on the prompt it names."
  [id]
  (when-let [j (get @jobs id)]
    (job-snapshot
     (if (= "working" (:status j))
       ;; A job whose work opened a modal is waiting for an answer, whether or
       ;; not it knows that — so a caller polling it finds out rather than
       ;; waiting for a result that cannot arrive.
       ;;
       ;; Only prompts newer than the job, or every working job would be
       ;; reported as blocked by a dialog that was already on screen and has
       ;; nothing to do with it.
       (let [mine (filter #(> (:id %) (:prompts-before j)) (prompts))]
         (if (seq mine)
           (assoc j :status "input_required"
                    :input-requests (mapv #(select-keys % [:id :header :choices]) mine))
           j))
       j))))

(defn- finish! [id status extra]
  (swap! jobs update id merge {:status status :finished-at (.now js/Date)} extra)
  id)

(defn- newest-prompt-id
  "The highest popup id right now, or -1. Objects are numbered in creation
  order, so this is how a job tells a prompt it caused from one that was
  already on screen."
  []
  (reduce max -1 (map object/->id (object/by-tag :popup))))

(defn- start-job! [kind]
  (let [id (str (name kind) "-" (swap! next-job-id inc))]
    (swap! jobs assoc id {:id id
                          :kind (name kind)
                          :status "working"
                          :started-at (.now js/Date)
                          :prompts-before (newest-prompt-id)})
    id))

(defn cancel!
  "Ask a job to stop. Cooperative — MCP says the same — so a job may still
  finish."
  [id]
  (if-let [j (get @jobs id)]
    (do (when-let [c (:cancel j)] (c))
        (when-not (#{"completed" "failed"} (:status j))
          (finish! id "cancelled" {}))
        (job id))
    {:error (str "No job " id)}))

;;*********************************************************
;; The jobs there are
;;*********************************************************

(def ^:private eval-ns
  "The namespace a caller's code is evaluated in.

  Prepared, with the namespaces worth having to hand already aliased. Without
  it every expression is evaluated in `cljs.user`, where nothing is required
  and `(object/by-tag :editor)` is an undefined name — a caller would have to
  write `lt.object/by-tag` every time, which is exactly the spelling this
  namespace exists to stop asking for."
  'lt.control-user)

(def ^:private eval-ns-form
  "(ns lt.control-user
     (:require [lt.object :as object]
               [lt.objs.command :as cmd]
               [lt.objs.editor :as editor]
               [lt.objs.editor.pool :as pool]
               [lt.objs.files :as files]
               [lt.objs.tabs :as tabs]
               [lt.objs.workspace :as workspace]
               [lt.objs.clients :as clients]
               [clojure.string :as string]))")

(defonce ^:private eval-ns-ready (atom false))

(defn- with-eval-ns
  "Call `f` once the prepared namespace exists."
  [f]
  (if @eval-ns-ready
    (f)
    (cljs-compiler/eval-forms
     {:forms [{:code eval-ns-form :meta {:line 1}}] :path "control"}
     (fn [_]
       (reset! eval-ns-ready true)
       (f)))))

(defn eval-clj
  "Evaluate ClojureScript in this window. Returns a job.

  The point of the whole namespace, and the reason it can exist now: the
  window has a ClojureScript compiler in it — see [[lt.objs.cljs-compiler]] —
  so a caller can send `(count (object/by-tag :editor))` rather than
  `cljs.core.count(lt.object.by_tag(...))` with the munged names spelled by
  hand. Sending source and getting a value back is the difference between
  driving an editor and operating a keyboard."
  [source]
  (let [id (start-job! :eval)]
    (with-eval-ns
      (fn []
        (cljs-compiler/eval-forms
         {:forms [{:code source :meta {:line 1}}]
          :buffer-ns eval-ns
          :path "control"}
         (fn [results]
           (let [r (first results)]
             (if (or (:ex r) (:stack r))
               (finish! id "failed" {:error (or (:ex r) (:stack r))})
               (finish! id "completed" {:result (:result r)})))))))
    (job id)))

(defn open
  "Open `path`, and finish when its editor exists."
  [path]
  (let [id (start-job! :open)]
    (if-not (files/exists? path)
      (finish! id "failed" {:error (str "No such file: " path)})
      (do
        (cmd/exec! :open-path path)
        (let [tries (atom 0)
              check (fn check []
                      (if-let [ed (first (pool/by-path path))]
                        (finish! id "completed" {:result (editor->map ed)})
                        (if (> (swap! tries inc) 100)
                          (finish! id "failed" {:error "the editor never appeared"})
                          (js/setTimeout check 100))))]
          (check))))
    (job id)))

(defn editor-value
  "The text of an editor, by id."
  [editor-id]
  (if-let [ed (first (filter #(= editor-id (object/->id %)) (object/by-tag :editor)))]
    {:id editor-id :path (tabs/->path ed) :value (editor/->val ed)}
    {:error (str "No editor " editor-id)}))

;;*********************************************************
;; One way in
;;*********************************************************

(defn handle
  "Dispatch `op` with `arg`, both plain data. Returns plain data.

  One entry point rather than a namespace of them, because that is the shape
  the thing wrapping this has: an MCP tool call is a name and an arguments
  map. A wrapper that has to know which ClojureScript function to reach for is
  a wrapper that breaks when one is renamed.

  An unknown op is answered rather than thrown — a caller that guessed wrong
  should be told what there is."
  [op arg]
  (case (name op)
    "snapshot" (snapshot)
    "editors" {:editors (editors)}
    "clients" {:clients (clients-open)}
    "errors" {:errors (errors)}
    "clear-errors" (clear-errors!)
    "prompts" {:prompts (mapv #(dissoc % :object) (prompts))}
    "answer" (answer! (:prompt arg) (:choice arg))
    "eval" (eval-clj (:source arg))
    "open" (open (:path arg))
    "value" (editor-value (:editor arg))
    "job" (or (job (:job arg)) {:error (str "No job " (:job arg))})
    "cancel" (cancel! (:job arg))
    {:error (str "No operation " (pr-str op))
     :operations ["snapshot" "editors" "clients" "errors" "clear-errors"
                  "prompts" "answer" "eval" "open" "value" "job" "cancel"]}))

(defn ^:export request
  "[[handle]] over JSON, for a caller that speaks JavaScript.

  `script/lt-repl.mts` and anything wrapping this in MCP come through here:
  JSON in, JSON out, no ClojureScript values crossing the boundary."
  [op arg]
  ;; Registering here rather than at startup: an editor nobody is driving
  ;; should not claim an agent is connected. The first call is what connects
  ;; it, and from then on it is in the same list as the REPL — see
  ;; [[lt.objs.clients.agent]] for why that is the shape rather than a
  ;; subsystem of its own.
  (agent/connect!)
  (agent/note-activity! true)
  (let [answer (handle op (js->clj arg :keywordize-keys true))]
    (agent/note-activity! false)
    (clj->js answer)))
