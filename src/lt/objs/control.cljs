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
            [lt.objs.popup :as popup]
            [lt.objs.tabs :as tabs]
            [lt.objs.workspace :as workspace]
            [lt.state :as state]
            [lt.state.objects :as from-objects]))

;; `lt.util.dom` came off this namespace's requires with the last DOM read in
;; it. The control surface answers questions about the editor from the objects
;; and the state now, and touches the document nowhere — which is the property
;; worth having: what it reports and what a test can assert are the same thing.

;;*********************************************************
;; What it is waiting for
;;*********************************************************

(defn- prompt-choices
  "The clickable choices in a popup, as text.

  Read from the object. This used to be a `querySelectorAll` for `li.button`,
  and the reason was real rather than lazy: a popup's choices are not all
  buttons, and the two callers that needed a list of them — which client should
  evaluate this, which code action to run — built `li.button` hiccup by hand in
  `:body`, so the choices existed only in the rendered document. A reader that
  trusted `:buttons` alone would have offered \"cancel\" and nothing else for
  exactly the prompts where the choice matters.

  `lt.objs.popup` has `:options` now, which is what those callers pass instead,
  so both kinds of choice are on the object. The order is
  [[lt.objs.popup/choices]]'s, because buttons are laid out floated-right and
  therefore backwards — which is the popup's business rather than something a
  reader should have to know."
  [p]
  (->> (popup/choices p)
       (map #(string/trim (str (:label %))))
       (remove string/blank?)
       vec))

(defn- prompt-header
  "What the popup is asking."
  [p]
  (string/trim (str (:header @p))))

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
    (cond
      (nil? choice) (do (object/raise (:object p) :close!)
                        {:answered prompt-id :choice nil})
      ;; Runs the choice rather than clicking the element that would have run it
      ;; — see `lt.objs.popup/choose-by-label!`.
      (popup/choose-by-label! (:object p) choice)
      {:answered prompt-id :choice choice}

      :else {:error (str "No choice called " (pr-str choice))
             :choices (:choices p)})
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
  ;; `lt.window.modules` is here for the same reason as the rest and answers
  ;; the other direction: it is the one bridge to Light Table's own TypeScript,
  ;; a `def ^js` per module with its exports in the docstring. So
  ;; `(.knownModes modules/cm6-modes)` reaches src-window/cm6-modes.ts from a
  ;; caller that is writing ClojureScript, without a second mechanism and
  ;; without anybody spelling `window.ltCm6Modes` by hand.
  "(ns lt.control-user
     (:require [lt.object :as object]
               [lt.objs.command :as cmd]
               [lt.objs.editor :as editor]
               [lt.objs.editor.pool :as pool]
               [lt.objs.files :as files]
               [lt.objs.tabs :as tabs]
               [lt.objs.workspace :as workspace]
               [lt.objs.clients :as clients]
               [lt.window.modules :as modules]
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

(defn- ->data
  "A value a JSON caller can have, or a failure that says why it cannot.

  Not everything survives the trip. An editor, a CodeMirror instance and an
  object are cyclic or full of functions, and `clj->js` on one of those either
  throws or produces something worse than useless. So a caller that asks for
  data and names something that is not data is told, and is told what it
  named — the alternative is a JSON `{}` that reads as an empty result."
  [value]
  (try
    ;; `clj->js` walks as far as the value goes, and a Light Table object is an
    ;; atom whose state holds other objects — so this is the same cycle
    ;; [[lt.objs.eval/cljs-result-format]] bounds with `*print-level*`, with no
    ;; equivalent knob. Refusing the kinds that cannot be data is the knob.
    ;; `object?` is `(identical? (type x) js/Object)` and an HTMLBodyElement is
    ;; not that, so the node check has to be the property. A DOM node is the
    ;; one of the three that does not throw — it stringifies to `{}`, because
    ;; everything on it is on the prototype — which is the worst of the three
    ;; answers and the reason this list is not just a try/catch.
    (when-let [why (cond (fn? value) "a function"
                         (satisfies? IDeref value) "an object or an atom"
                         (and (some? value) (number? (.-nodeType ^js value))) "a DOM node"
                         :else nil)]
      (throw (js/Error. (str "it is " why))))
    (let [json (js/JSON.stringify (clj->js value))]
      ;; `JSON.stringify` answers `undefined` for `undefined` and for a bare
      ;; function, and `JSON.parse` of that throws a SyntaxError naming
      ;; neither. Both mean "no value", which is what nil is.
      {:ok (if (undefined? json) nil (js/JSON.parse json))})
    (catch :default e
      {:error (str "The value is not data, so it cannot come back as data: "
                   (.-message e))})))

(defn eval-clj
  "Evaluate ClojureScript in this window. Returns a job.

  The point of the whole namespace, and the reason it can exist now: the
  window has a ClojureScript compiler in it — see [[lt.objs.cljs-compiler]] —
  so a caller can send `(count (object/by-tag :editor))` rather than
  `cljs.core.count(lt.object.by_tag(...))` with the munged names spelled by
  hand. Sending source and getting a value back is the difference between
  driving an editor and operating a keyboard.

  `data?` decides which of those two you get. Without it the result is what a
  REPL would print — `\"[0 2 0]\"` — which is right for a person reading it and
  wrong for a caller that wanted the vector: every assertion becomes a string
  comparison against pretty-printed EDN, and a mismatch is a diff of text
  rather than of values. With it the value comes back through `clj->js`, so a
  vector is an array and a map with keyword keys is an object.

  Printed stays the default because the callers that came first — the REPL
  script, MCP — are showing a person a value."
  ([source] (eval-clj source false))
  ([source data?]
   (let [id (start-job! :eval)]
     (with-eval-ns
       (fn []
         (cljs-compiler/eval-forms
          {:forms [{:code source :meta {:line 1}}]
           :buffer-ns eval-ns
           :path "control"}
          (fn [results]
            (let [r (first results)]
              (cond
                (or (:ex r) (:stack r))
                (finish! id "failed" {:error (or (:ex r) (:stack r))})

                (not data?)
                (finish! id "completed" {:result (:result r)})

                :else
                (let [{:keys [ok error]} (->data (:value r))]
                  (if error
                    (finish! id "failed" {:error (str error " — " (:result r))})
                    (finish! id "completed" {:result ok})))))))))
     (job id))))

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

(defn screen
  "What the window is showing, as data.

  A screenshot answers this and cannot be asserted on; `snapshot` answers what
  is *open*, which is a different question. This is what is in front of you: a
  modal covering the editor is the difference between a feature that does
  nothing and one whose answer you cannot see, and an hour went into querying
  the DOM for an expected widget while exactly that sat on top of it.

  Read from the document rather than from state on purpose. State says what
  should be drawn; this says what is."
  []
  (let [$$ (fn [sel] (array-seq (.querySelectorAll js/document sel)))
        text (fn [^js el] (some-> el .-innerText string/trim))]
    {:modals (mapv (fn [^js el] (some-> (text el) (subs 0 (min 200 (count (text el))))))
                   ($$ ".popup"))
     :tabs (mapv text ($$ ".tab__label"))
     :active-tab (some-> ^js (.querySelector js/document ".tab--active .tab__label") text)
     :statusbar (some-> ^js (.querySelector js/document ".statusbar") text)
     :focused (let [^js el (.-activeElement js/document)]
                (when el (str (string/lower-case (or (.-tagName el) "?"))
                              (when (seq (str (.-className el)))
                                (str "." (.-className el))))))
     ;; What is drawn over the editor, which is the class of thing that hides
     ;; an answer rather than replacing it.
     :overlays (vec (concat (mapv (constantly "popup") ($$ ".popup"))
                            (mapv (constantly "commandbar") ($$ ".commandbar"))))
     :inline (count ($$ ".inline-doc, .inline-exception, .result-mark, .underline-result"))
     ;; Highlighting, as a number rather than an impression. "Sometimes we
     ;; start to lose syntax highlighting" is a real report and was not
     ;; reproducible from outside — opening many files, typing into one and
     ;; replacing a whole buffer all kept it. So this is what to read when it
     ;; happens next.
     ;;
     ;; Spans per *rendered* line, because CodeMirror 6 draws only the
     ;; viewport: a raw span count halves when you scroll to a shorter region
     ;; and says nothing. A file with highlighting runs several spans a line; a
     ;; file that has lost it runs at or near zero, whatever its length.
     :highlighting (vec (for [ed (object/by-tag :editor)
                              :let [^js el (try (editor/->elem ed) (catch :default _ nil))]
                              :when el
                              :let [lines (.-length (.querySelectorAll el ".cm-line"))
                                    spans (.-length (.querySelectorAll el ".cm-line span"))]]
                          {:file (some-> (tabs/->path ed) files/basename)
                           :mode (str (try (editor/option ed "mode") (catch :default _ nil)))
                           :lines lines
                           :per-line (when (pos? lines)
                                       (/ (js/Math.round (* 10 (/ spans lines))) 10))}))}))

(defn drift
  "Where the state atom disagrees with the objects it is projected from.

  `lt.state.objects/snapshot` is a pure function of the object world, so the
  projection can be recomputed at any instant and compared with what the views
  are actually drawing from. Nothing did, and the two are kept in step by a
  list of triggers in `lt.ui.window` — so a fact that changes on a trigger not
  in that list is stale until you click something.

  Which is not hypothetical: the statusbar reported a language server as
  `:connecting`, pulsing, for as long as the window was open, because
  `:lsp.ready` was not on the list. Empty is the answer you want."
  []
  (let [fresh (from-objects/snapshot)
        live @state/app
        ;; The command bar is not excluded. Only *part* of it is the state's
        ;; own — the open flag, the query, the selection — and the list of
        ;; commands is projected like everything else. Dropping the key whole
        ;; was the easy thing and it made this check blind to the one half it
        ;; could judge, which is worse than not checking: a tool that reports
        ;; nothing reads as agreement.
        ;;
        ;; `:cursor` because it has a different clock and that is deliberate:
        ;; the statusbar renders from `lt.state/cursor`, its own atom, and a
        ;; cursor going through the main one would re-render the window on
        ;; every keypress. `(:cursor @state/app)` is therefore always behind,
        ;; by design, and reporting it would make this tool cry wolf — which
        ;; is how a check stops being read.
        interesting (disj (set (keys fresh)) :cursor)
        ;; The observer is part of what it observes. This is reached through
        ;; the control surface, which is itself an agent client, and asking
        ;; puts it in `:executing` with a `:via` — so a fresh projection taken
        ;; during the question always differs from the last one taken before
        ;; it. Reported, that is noise on every single call, which is how a
        ;; check stops being read. So an agent's own liveness is normalised
        ;; out and everything else about every client is still compared.
        settle (fn [clients]
                 (into {} (for [[id c] clients]
                            [id (if (= :agent (:kind c)) (dissoc c :status :via) c)])))
        normalise (fn [k v]
                    (case k
                      :clients (settle v)
                      ;; The projected half only. `:open?`, `:query` and
                      ;; `:selected` belong to the state and are supposed to
                      ;; differ.
                      :command-bar (select-keys v [:commands])
                      ;; The same split, for the same reason: `:entries` is
                      ;; projected from the behavior registry, while which half
                      ;; of the screen you are looking at, what you typed into
                      ;; the filter, and the binding being captured are the
                      ;; state's own.
                      :settings (select-keys v [:entries])
                      v))]
    {:drifted (vec (for [k (sort interesting)
                         :let [a (normalise k (get fresh k))
                               b (normalise k (get live k))]
                         :when (not= a b)]
                     {:key (str k)
                      :projected (pr-str a)
                      :drawn (pr-str b)}))}))

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
    "eval" (eval-clj (:source arg) (boolean (:data arg)))
    "open" (open (:path arg))
    "value" (editor-value (:editor arg))
    "job" (or (job (:job arg)) {:error (str "No job " (:job arg))})
    "cancel" (cancel! (:job arg))
    "screen" (screen)
    "drift" (drift)
    {:error (str "No operation " (pr-str op))
     :operations ["snapshot" "editors" "clients" "errors" "clear-errors"
                  "prompts" "answer" "eval" "open" "value" "job" "cancel"
                  "screen" "drift"]}))

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
