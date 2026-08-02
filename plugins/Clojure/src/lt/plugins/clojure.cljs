(ns lt.plugins.clojure
  (:require [lt.object :as object]
            [lt.objs.clients :as clients]
            [lt.objs.clients.local :as local]
            [lt.objs.files :as files]
            [lt.objs.context :as ctx]
            [lt.objs.sidebar.clients :as scl]
            [lt.objs.dialogs :as dialogs]
            [lt.objs.console :as console]
            [lt.objs.editor :as ed]
            [lt.objs.editor.pool :as pool]
            [lt.objs.editor.treesitter :as treesitter]
            [lt.objs.jump-stack :as jump-stack]
            [lt.objs.popup :as popup]
            [lt.objs.platform :as platform]
            [lt.objs.tabs :as tabs]
            [lt.plugins.auto-complete :as auto-complete]
            [lt.objs.proc :as proc]
            [lt.objs.eval :as eval]
            [lt.objs.notifos :as notifos]
            [lt.plugins.watches :as watches]
            [lt.util.dom :as dom]
            [lt.util.js :as util]
            [lt.util.load :as load]
            [lt.util.cljs :refer [->dottedkw]]
            [clojure.string :as string]
            [cljs.reader :as reader]
            [lt.objs.command :as cmd]
            [lt.objs.plugins :as plugins])
  (:require-macros [lt.macros :refer [behavior]]))

(def ^js shell (load/node-module "shelljs"))
(def cur-path (.pwd shell))

;; The project a REPL runs in when there is no project around the file being
;; evaluated. It is the plugin's own, and exists only to give Leiningen
;; something to read.
(def local-project-clj (files/join plugins/*plugin-dir* "local-project/project.clj"))

;; What the REPL loads so Light Table can ask it things. Bought, not built:
;; cider-nrepl is what CIDER, Calva, Conjure and vim-iced all sit on, it is
;; maintained, and it does more than the 1,192 lines of bespoke middleware this
;; plugin used to vendor ever did. plugins/Clojure/VENDORED.md has that story.
;;
;; As a Leiningen plugin rather than a dependency, which is how cider-nrepl
;; asks to be used: it injects its own middleware list, so nothing here has to
;; know what that list is or keep up with it.
(def cider-nrepl-version "0.62.2")
(def nrepl-version "1.7.0")

;; Injected whether or not the project asked for it, the way cider-nrepl is:
;; ClojureScript evaluation is a feature Light Table offers, so Light Table
;; brings what it needs rather than asking the user to add a dependency to
;; their project for the editor's benefit. shadow-cljs projects never reach
;; this — they have their own.
(def piggieback-version "0.7.0")

;; And the compiler it drives. A project with its own ClojureScript wins on
;; Leiningen's normal resolution, so this only matters for a project that has
;; none — where without it the ClojureScript REPL fails with a missing class
;; rather than a missing feature.
(def clojurescript-version "1.12.42")

;; Forward references. This namespace is written in call order rather than
;; definition order throughout, which the ClojureScript compiler reports as an
;; undeclared var — see plugins/Clojure/VENDORED.md.
(declare clj-lang check-all find-project find-symbol-at-cursor run-local-server buffer-ns)

;;****************************************************
;; highlighting
;;****************************************************

;;****************************************************
;; Lang object
;;****************************************************

(def local-name "LightTable-REPL")

(defn unescape-unicode [s]
  (when (string? s)
    (string/replace s
                    #"\\x(..)"
                    (fn [res r]
                      (js/String.fromCharCode (js/parseInt r 16))))))

(defn try-connect [{:keys [info]}]
  (let [path (:path info)
        {:keys [project-path]} (when path (find-project {:path path}))]
    (if project-path
      (check-all {:path path
                  :client (clients/client! :nrepl.client)})
      (or (clients/by-name local-name)
          (run-local-server (clients/client! :nrepl.client))))))

(defn form-code
  "The text of one form, with any watches inside it wrapped.

  `watches/watched-range` and not `ed/range`, which is what this used and which
  quietly dropped every watch: the wrapping is what turns a watched expression
  into one that reports its value, and it happens here or not at all.

  It takes an *inclusive* end and tree-sitter gives an exclusive one, hence the
  `dec` — it adds one back."
  [editor start end]
  (let [inclusive (if (pos? (:ch end))
                    (update end :ch dec)
                    ;; A form ending at column 0 ends on the line before, and
                    ;; decrementing into -1 is not a position.
                    end)]
    (watches/watched-range editor start inclusive nil)))

(defn ->form
  "One top-level form, as the evaluator and the renderers both want it.

  `:meta` is 1-based, because that is what `::clj-result.inline` and the rest
  subtract from to find the line to draw beside."
  [editor {:keys [start end]}]
  {:code (form-code editor start end)
   :pos start
   :meta {:line (inc (:line start))
          :end-line (inc (:line end))
          :end-column (:ch end)}})

(defn forms-in
  "The top-level forms of `editor`, from its parse tree.

  This is the whole reason a result can sit beside the form that produced it
  rather than one result arriving for a file. It used to be answered by Light
  Table's own nREPL middleware, which meant inline results needed a bespoke
  server per language and a round trip before the editor knew where anything
  was. tree-sitter already parses this buffer on every keystroke and knows.

  Nil when there is no parse tree — a grammar that has not loaded, or a
  language without one — and the caller falls back to the whole region rather
  than guessing."
  [editor]
  (when-let [forms (seq (treesitter/top-level-forms editor))]
    (mapv #(->form editor %) forms)))

(defn whole-region
  "The editor's text as a single form, for when there is no parse tree."
  [editor]
  [{:code (ed/->val editor)
    :pos {:line 0 :ch 0}
    :meta {:line 1
           :end-line (inc (ed/last-line editor))
           :end-column 0}}])

(behavior ::on-eval.clj
          :triggers #{:eval}
          :reaction (fn [editor]
                      (object/raise clj-lang :eval! {:origin editor
                                                     :info (assoc (@editor :info)
                                                             :print-length (object/raise-reduce editor :clojure.print-length+ nil)
                                                             :buffer-ns (buffer-ns editor)
                                                             :forms (or (forms-in editor)
                                                                        (whole-region editor)))})))
(behavior ::on-eval.cljs
          :triggers #{:eval}
          ;; The same shape as ::on-eval.clj, because it is now the same path:
          ;; forms from the parse tree, a result beside each one, watches. The
          ;; only difference is which nREPL session evaluates them, and that is
          ;; the client's business.
          ;;
          ;; The `(set! js/COMPILED true)` wrapper that used to be here belonged
          ;; to the browser-client route, where evaluating an `ns` form in a
          ;; page raised goog.provide errors. A real ClojureScript REPL compiles
          ;; the form properly and does not need to be lied to.
          :reaction (fn [editor]
                      (object/raise clj-lang :eval! {:origin editor
                                                     :info (assoc (@editor :info)
                                                             :print-length (object/raise-reduce editor :clojure.print-length+ nil)
                                                             :buffer-ns (buffer-ns editor)
                                                             :forms (or (forms-in editor)
                                                                        (whole-region editor)))})))

(behavior ::on-eval.one
          :triggers #{:eval.one}
          :reaction (fn [editor]
                      (let [pos (ed/->cursor editor)
                            info (:info @editor)
                            forms (if (ed/selection? editor)
                                    (let [start (ed/->cursor editor "start")
                                          end (ed/->cursor editor "end")]
                                      [{:code (watches/watched-range editor start end nil)
                                        :pos start
                                        :meta {:line (inc (:line start))
                                               :end-line (inc (:line end))
                                               :end-column (:ch end)}}])
                                    ;; The form the cursor is in, from the parse
                                    ;; tree. Nothing is evaluated when the cursor
                                    ;; is between forms, which is the honest
                                    ;; answer — the old middleware guessed at the
                                    ;; nearest one.
                                    (when-let [form (treesitter/form-at editor pos)]
                                      [(->form editor form)]))
                            info (assoc info
                                        :forms forms
                                        :buffer-ns (buffer-ns editor)
                                        :print-length (object/raise-reduce editor :clojure.print-length+ nil))]
                        (if (seq forms)
                          (object/raise clj-lang :eval! {:origin editor :info info})
                          (notifos/set-msg! "No form under the cursor")))))


(defn fill-placeholders [editor exp]
  (-> exp
      (string/replace "__SELECTION*__" (pr-str (ed/selection editor)))
      (string/replace "__SELECTION__" (ed/selection editor))))

(behavior ::on-eval.custom
          :triggers #{:eval.custom}
          :reaction (fn [editor exp opts]
                      (let [code (fill-placeholders editor exp)
                            pos (ed/->cursor editor)
                            info (:info @editor)
                            info  (assoc info
                                    :code code
                                    :ns (or (:ns opts) (:ns info))
                                    :meta (merge {:start (-> (ed/->cursor editor "start") :line)
                                                  :end (-> (ed/->cursor editor "end") :line)
                                                  :result-type :inline
                                                  :trigger :return}
                                                 (update-in opts [:handler] object/->id)))
                            info (assoc info :print-length (object/raise-reduce editor :clojure.print-length+ nil))]
                        (object/raise clj-lang :eval! {:origin editor
                                                       :info info}))))

(behavior ::on-code
          :triggers #{:editor.eval.cljs.code}
          :reaction (fn [this result]
                      (object/raise this :exec.cljs! result)))

(behavior ::exec.cljs!
          :triggers #{:exec.cljs!}
          :reaction (fn [this res]
                      (let [client (-> @this :client :exec)
                            path (-> @this :info :path)
                            res (update-in res [:results] #(for [r %]
                                                             (assoc r :code (-> (:code r)
                                                                                (eval/pad (-> r :meta :line dec))
                                                                                (eval/append-source-file path))
                                                               :meta (merge (:meta res) (:meta r)))))]
                        (clients/send (eval/get-client! {:command :editor.eval.cljs.exec
                                                         :info {:type "cljs"}
                                                         :key :exec
                                                         :origin this})
                                      :editor.eval.cljs.exec res :only this))))

(def mime->type {"text/x-clojure" "clj"
                 "text/x-clojurescript" "cljs"})

(def default-cljs-client
  "Default cljs client to invoke when a cljs file is first evaled. Takes any valid client or
  :auto which automatically sets 'Light Table UI' or 'ClojureScript Browser' based on whether
  project is LightTable related or not."
  nil)

(behavior ::set-default-cljs-client
          :triggers #{:object.instant}
          :desc "Clojure: Set default ClojureScript client to use when first evaled. Disable with nil"
          :type :user
          :params [{:label "client-name"
                    :type :list
                    :items ["ClojureScript Browser" "Light Table UI" "Browser" "Browser (External)"]}]
          :reaction (fn [this client-name]
                      (set! default-cljs-client client-name)))

(defn lighttable-ui-project?
  "Does this file evaluate into Light Table's own process?

  Three ways to be one, and each is a thing about the file rather than a
  setting someone has to find:

  - a `plugin.edn` or `plugin.json` above it — it is a Light Table plugin
  - a `project.clj` naming `lighttable` — the old layout, still read
  - a namespace starting with `lt.` — Light Table's own source, which no
    longer has a `project.clj` to be recognised by. The editor is built by
    shadow-cljs now, and `shadow-cljs.edn` is too common a file to treat as
    the same claim."
  ([path] (lighttable-ui-project? path nil))
  ([path buffer-ns]
   (or (files/walk-up-find path "plugin.edn")
       (files/walk-up-find path "plugin.json")
       (when-let [project-file (files/walk-up-find path "project.clj")]
         (= 'lighttable (second (reader/read-string (:content (files/open-sync project-file))))))
       (when buffer-ns
         (or (= "lt" (str buffer-ns))
             (string/starts-with? (str buffer-ns) "lt."))))))

(defn connect-cljs
  "Which client evaluates this ClojureScript.

  Light Table's own code and its plugins evaluate into the window, through
  [[lt.objs.clients.local]] — that is the editor changing itself while
  running, and it needs nothing started. Everything else is somebody's
  project, and belongs in that project's REPL, so it takes the same route
  Clojure does."
  [{:keys [info] :as opts}]
  (if (lighttable-ui-project? (:path info) (:buffer-ns info))
    (local/connect!)
    (try-connect opts)))

(behavior ::eval!
          :triggers #{:eval!}
          :reaction (fn [this event]
                      (let [{:keys [info origin]} event
                            command (->dottedkw :editor.eval (-> info :mime mime->type))
                            client (-> @origin :client :default)]
                        (notifos/working)
                        (clients/send (eval/get-client! {:command command
                                                         :info info
                                                         :origin origin
                                                         ;; A .cljs file evaluates in the project's REPL,
                                                         ;; the same as a .clj one — a second nREPL session
                                                         ;; on the same connection, which
                                                         ;; lt.plugins.clojure.nrepl starts on demand —
                                                         ;; unless the file is Light Table's own, in which
                                                         ;; case it evaluates in the window. See
                                                         ;; connect-cljs.
                                                         ;;
                                                         ;; Connecting a browser is still a thing you can
                                                         ;; do; it is a thing you *choose*, in the Connect
                                                         ;; bar, which is what the Connect bar is for. Doing
                                                         ;; it automatically meant evaluating any .cljs file
                                                         ;; went looking for a page to attach to, and threw
                                                         ;; before it ever reached the REPL.
                                                         :create (if (= command :editor.eval.cljs)
                                                                   connect-cljs
                                                                   try-connect)})
                                      command info :only origin))))

(behavior ::build!
          :triggers #{:build!}
          :reaction (fn [this event]
                      (let [{:keys [info origin]} event
                            command (->dottedkw (-> info :mime mime->type) "compile")]
                        (notifos/working "Starting build")
                        (clients/send (eval/get-client! {:command command
                                                         :info info
                                                         :origin origin
                                                         :create try-connect})
                                      command info :only origin))))

(behavior ::build-cljs-plugin
          :triggers #{:build}
          :desc "Plugin: build ClojureScript plugin"
          :reaction (fn [this opts]
                      (let [to-compile (files/filter-walk #(= (files/ext %) "cljs") (files/join (:lt.objs.plugins/plugin-path @this) "src"))]
                        (object/raise clj-lang :build! {:info {:files to-compile
                                                               :mime (-> @this :info :mime)
                                                               :dir (:lt.objs.plugins/plugin-path @this)
                                                               :path (files/join (:lt.objs.plugins/plugin-path @this) "plugin.edn")
                                                               :ignore ['goog
                                                                        'goog.array
                                                                        'lt.object
                                                                        'crate.core
                                                                        'crate.util
                                                                        'lt.util.load
                                                                        'lt.util.cljs
                                                                        'lt.util.dom
                                                                        'lt.util.js
                                                                        'fetch.core
                                                                        'fetch.util
                                                                        'cljs.core
                                                                        'cljs.reader
                                                                        'clojure.string
                                                                        'clojure.set
                                                                        'goog.string]
                                                               :merge? true}
                                                        :origin this}))))

(behavior ::plugin-compile-results
          :triggers #{:cljs.compile.results}
          :desc "Plugin: output compile results"
          :reaction (fn [this res]
                      (let [plugin-name (-> (:lt.objs.plugins/plugin-path @this) plugins/plugin-info :name string/lower-case)
                            final-path (files/join (:lt.objs.plugins/plugin-path @this) (str plugin-name "_compiled.js"))
                            plugin-map-name (str plugin-name "_compiled.js.map")
                            sm-path (files/join (:lt.objs.plugins/plugin-path @this) plugin-map-name)]
                        (notifos/done-working (str "Compiled plugin to " final-path))
                        (files/save final-path (str (:js res) "\n//# sourceMappingURL=" plugin-map-name))
                        (files/save sm-path (:source-map res)))))

(behavior ::on-result-set-ns
          :triggers #{:editor.eval.cljs.code
                      :editor.eval.clj.result}
          :reaction (fn [obj res]
                      (when (and (:ns res)
                                 (not= (-> @obj :info :ns) (:ns res)))
                        (object/update! obj [:info] assoc :ns (:ns res)))))

(behavior ::no-op
          :triggers #{:editor.eval.cljs.no-op
                      :editor.eval.clj.no-op}
          :reaction (fn [this]
                      (notifos/done-working)))

(behavior ::cljs-result
          :triggers #{:editor.eval.cljs.result}
          :reaction (fn [obj res]
                      (notifos/done-working)
                      (let [type (or (-> res :meta :result-type) :inline)
                            ev (->dottedkw :editor.eval.cljs.result type)]
                        (object/raise obj ev res))))

(defn results-in
  "The results a response carries, whichever shape it came in.

  There were two. Clojure evaluation sends `:results`, a vector with one entry
  per top-level form and the `:meta` saying which lines each belongs beside —
  which is what makes a result appear next to the form that produced it.
  ClojureScript sent a single `:result` at the top level, because the only
  thing that answered it was a browser evaluating one expression.

  Both go through here now, so there is one shape downstream and the browser
  route keeps working: a response that carries no `:results` is a response with
  one, itself."
  [res]
  (or (seq (:results res)) [res]))

(behavior ::cljs-result.replace
          :triggers #{:editor.eval.cljs.result.replace}
          :reaction (fn [obj res]
                      (doseq [result (results-in res)]
                        (if-let [err (or (:stack result) (:ex result))]
                          (notifos/set-msg! err {:class "error"})
                          (ed/replace-selection obj (unescape-unicode (or (:result result) "")))))))

(behavior ::cljs-result.statusbar
          :triggers #{:editor.eval.cljs.result.statusbar}
          :reaction (fn [obj res]
                      (doseq [result (results-in res)]
                        (if-let [err (or (:stack result) (:ex result))]
                          (notifos/set-msg! err {:class "error"})
                          (notifos/set-msg! (unescape-unicode (or (:result result) "")) {:class "result"})))))

(behavior ::cljs-result.inline
          :triggers #{:editor.eval.cljs.result.inline}
          :reaction (fn [obj res]
                      (doseq [result (results-in res)
                              :let [meta (or (:meta result) (:meta res))
                                    loc {:line (dec (:end-line meta)) :ch (:end-column meta)
                                         :start-line (dec (:line meta))}]]
                        (if-let [err (or (:stack result) (:ex result))]
                          (object/raise obj :editor.eval.cljs.exception result :passed)
                          (object/raise obj :editor.result (unescape-unicode (or (:result result) "")) loc)))))

(behavior ::cljs-result.inline-at-cursor
          :triggers #{:editor.eval.cljs.result.inline-at-cursor}
          :reaction (fn [obj res]
                      (doseq [result (results-in res)
                              :let [meta (or (:meta result) (:meta res))
                                    loc {:line (or (:start meta) (dec (:end-line meta)))
                                         :start-line (or (:start meta) (dec (:line meta)))}]]
                        (if-let [err (or (:stack result) (:ex result))]
                          (object/raise obj :editor.eval.cljs.exception result :passed)
                          (object/raise obj :editor.result (unescape-unicode (or (:result result) "")) loc)))))

(behavior ::cljs-result.return
          :triggers #{:editor.eval.cljs.result.return}
          :reaction (fn [obj res]
                      (doseq [result (results-in res)
                              :let [meta (:meta res)
                                    handler (-> meta :handler object/by-id)
                                    ev (:trigger meta)]]
                        (if-let [err (or (:stack result) (:ex result))]
                          (object/raise obj :editor.eval.cljs.exception result :passed)
                          (object/raise handler ev {:result (unescape-unicode (or (:result result) ""))
                                                    :meta meta})))))

(behavior ::clj-result
          :triggers #{:editor.eval.clj.result}
          :reaction (fn [obj res]
                      (notifos/done-working)
                      (let [type (or (-> res :meta :result-type) :inline)
                            ev (->dottedkw :editor.eval.clj.result type)]
                        (object/raise obj ev res))))

(behavior ::clj-result.replace
          :triggers #{:editor.eval.clj.result.replace}
          :reaction (fn [obj res]
                      (doseq [result (-> res :results)
                              :let [meta (:meta result)
                                    loc {:line (dec (:end-line meta)) :ch (:end-column meta)
                                         :start-line (dec (:line meta))}]]
                        (if (:stack result)
                          (notifos/set-msg! (:result res) {:class "error"})
                          (ed/replace-selection obj (:result result))))))

(behavior ::clj-result.statusbar
          :triggers #{:editor.eval.clj.result.statusbar}
          :reaction (fn [obj res]
                      (doseq [result (-> res :results)
                              :let [meta (:meta result)
                                    loc {:line (dec (:end-line meta)) :ch (:end-column meta)
                                         :start-line (dec (:line meta))}]]
                        (if (:stack result)
                          (notifos/set-msg! (:result res) {:class "error"})
                          (notifos/set-msg! (:result result) {:class "result"})))))

(behavior ::clj-result.inline
          :triggers #{:editor.eval.clj.result.inline}
          :reaction (fn [obj res]
                      (doseq [result (-> res :results)
                              :let [meta (:meta result)
                                    loc {:line (dec (:end-line meta)) :ch (:end-column meta)
                                         :start-line (dec (:line meta))}]]
                        (if (:stack result)
                          (object/raise obj :editor.eval.clj.exception result :passed)
                          (object/raise obj :editor.result (:result result) loc)))))

(behavior ::clj-result.inline-at-cursor
          :triggers #{:editor.eval.clj.result.inline-at-cursor}
          :reaction (fn [obj res]
                      (doseq [result (-> res :results)
                              :let [meta (:meta result)
                                    loc {:line (-> res :meta :start)
                                         :start-line (-> res :meta :start)}]]
                        (if (:stack result)
                          (object/raise obj :editor.eval.clj.exception result :passed)
                          (object/raise obj :editor.result (:result result) loc)))))

(behavior ::clj-result.return
          :triggers #{:editor.eval.clj.result.return}
          :reaction (fn [obj res]
                      (doseq [result (-> res :results)
                              :let [meta (:meta res)
                                    handler (-> meta :handler object/by-id)
                                    ev (:trigger meta)]]
                        (if (:stack result)
                          (object/raise obj :editor.eval.clj.exception result :passed)
                          (object/raise handler ev {:result (:result result)
                                                    :meta meta})))))

(behavior ::clj-exception
          :triggers #{:editor.eval.clj.exception}
          :reaction (fn [obj res passed?]
                      (when-not passed?
                        (notifos/done-working ""))
                      (let [meta (:meta res)
                            loc {:line (dec (:end-line meta)) :ch (:end-column meta 0)
                                 :start-line (dec (:line meta 1))}]
                        (notifos/set-msg! (:result res) {:class "error"})
                        (object/raise obj :editor.exception (:stack res) loc))
                      ))

(behavior ::cljs-exception
          :triggers #{:editor.eval.cljs.exception}
          :reaction (fn [obj res passed?]
                      (when-not passed?
                        (notifos/done-working ""))
                      (let [meta (:meta res)
                            loc {:line (dec (:end-line meta)) :ch (:end-column meta)
                                 :start-line (dec (:line meta))}
                            msg (or (:stack res) (:ex res))
                            stack (or (:stack res)
                                      (if (and (:ex res)
                                               (.-stack (:ex res)))
                                        (.-stack (:ex res))
                                        (if (:ex res)
                                          (if (:verbatim meta)
                                            (:ex res)
                                            (pr-str (:ex res)))))
                                      msg
                                      "Unknown error")]
                        (notifos/set-msg! msg {:class "error"})
                        (object/raise obj :editor.exception stack loc))
                      ))

(behavior ::eval-print
          :triggers #{:editor.eval.clj.print}
          :reaction (fn [this str]
                      (when (not= "\n" (:out str))
                        (console/loc-log {:file (files/basename (or (-> @this :name) (-> @this :info :path) "unknown"))
                                          :line (when (object/has-tag? this :nrepl.client)
                                                  "stdout")
                                          :id (:id str)
                                          :content (:out str)}
                                         ))))

(behavior ::eval-print-err
          :triggers #{:editor.eval.clj.print.err}
          :reaction (fn [this str]
                      (when (not= "\n" (:out str))
                        (console/error (:out str)))))

(behavior ::handle-cancellation
          :triggers #{:editor.eval.clj.cancel}
          :reaction (fn [this]
                      (notifos/done-working)
                      (notifos/set-msg! "Canceled clj eval." {:class "error"})))

(behavior ::print-length
          :triggers #{:clojure.print-length+}
          :desc "Clojure: Set the print length for eval (doesn't affect CLJS)"
          :params [{:label "length"
                    :type :number}]
          :type :user
          :exclusive true
          :reaction (fn [this res len]
                      len))

(behavior ::lein-exe
          :triggers #{:object.instant}
          :desc "Clojure: set the path to the build tool executable for clients"
          :doc "Was `::java-exe`, which named the JVM Light Table started its
                own nREPL server with. It no longer starts one: the REPL is
                your Leiningen, and which JVM that uses is Leiningen's business
                — `JAVA_CMD` if you want to say."
          :type :user
          :params [{:label "path"}]
          :exclusive true
          :reaction (fn [this path]
                      (object/merge! clj-lang {:lein-exe path})))

;;****************************************************
;; Connectors
;;****************************************************

(behavior ::connect-local
          :triggers #{:connect}
          :reaction (fn [this path]
                      (try-connect {:info {:path path}})))


(scl/add-connector {:name "Clojure"
                    :desc "Select a project.clj to connect to."
                    :connect (fn []
                               (dialogs/file clj-lang :connect))})

(defn- server-input
  "The one popup that asks for text rather than a choice.

  No `:value` in the hiccup, deliberately. The popup redraws when its active
  button moves, and an attribute Replicant is told about is one it will write
  back — so a controlled value here would put \"localhost:\" back in the box
  under whoever was typing. The initial text is set once, on the node, below."
  []
  [:input.nrepl-server {:type "text" :placeholder "host:port"
                        :on {:focus (fn [] (ctx/in! :popup.input))
                             :blur (fn [] (ctx/out! :popup.input))}}])

(defn connect-to-remote [server]
  (let [[host port] (string/split server ":")]
    (when (and host port)
      (let [client (clients/client! :nrepl.client.remote)]
        (object/merge! client {:port port
                               :host host
                               :name server})
        (object/raise client :connect!)))))

(defn remote-connect []
  (let [input (atom nil)
        p (popup/popup! {:header "Connect to a remote nREPL server."
                         :body [:div
                                [:p "In order to connect to an nrepl server, make sure the server is started (e.g. lein repl :headless)
                                 and that you have included the lighttable.nrepl.handler/lighttable-ops middleware."]
                                [:label "Server: "]
                                (server-input)]
                         :buttons [{:label "cancel"}
                                   {:label "connect"
                                    :action (fn []
                                              (connect-to-remote (dom/val @input)))}]})
        ^js el (dom/$ :input.nrepl-server (object/->content p))]
    (reset! input el)
    ;; After the popup is in the document, because focusing a detached node
    ;; does nothing.
    (set! (.-value el) "localhost:")
    (dom/focus el)
    (.setSelectionRange el 1000 1000)))

(scl/add-connector {:name "Clojure (remote nREPL)"
                    :desc "Enter in the host:port address of an nREPL server to connect to"
                    :connect (fn []
                               (remote-connect)
                               )})


;;****************************************************
;; watches
;;****************************************************

(behavior ::cljs-watch-src
          :triggers #{:watch.src+}
          :reaction (fn [editor cur meta src]
                      (let [meta (assoc meta :ev :editor.eval.cljs.watch)]
                        (str "(js/lttools.watch " src " (clj->js " (pr-str meta) "))"))))

(def watch-sentinel
  "The marker a watch prints its value behind.

  Watches are Light Table's, and they survived losing the middleware that used
  to carry them. `lighttable.nrepl.eval/watch` sent the value back over a
  bespoke nREPL operation; there is no such operation on a standard server and
  none is needed — the watch prints one tagged line and the client picks it out
  of the `:out` it is already receiving. No server-side code at all, so this
  works against any nREPL, cider-nrepl or otherwise.

  A control character rather than a word, because the marker has to be
  something a program would not print by accident."
  "\u0001LT-WATCH ")

(defn watch-src
  "`src`, wrapped so that evaluating it also reports its value to the editor."
  [src meta]
  (str "(let [v# " src "]"
       " (println (str " (pr-str watch-sentinel)
       " (pr-str {:meta " (pr-str meta) " :result (pr-str v#)})))"
       " v#)"))

(behavior ::clj-watch-src
          :triggers #{:watch.src+}
          :reaction (fn [editor cur meta src]
                      (watch-src src meta)))

(defn fill-watch-placeholders [exp src meta watch]
  (-> exp
      (string/replace "\n" " ")
      (string/replace "__SELECTION*__" (pr-str src))
      (string/replace "__SELECTION__" src)
      (string/replace "__ID__" (pr-str (:id meta)))
      (string/replace #"__\|(.*)\|__" watch)))

(behavior ::cljs-watch-custom-src
          :triggers #{:watch.custom.src+}
          :reaction (fn [editor cur meta opts src]
                      (let [watch (str "(js/lttools.raise " (:obj meta) " :editor.eval.cljs.watch {:meta " (pr-str (merge (dissoc opts :exp) meta)) " :result $1})")]
                        (fill-watch-placeholders (:exp opts) src meta watch))))

(behavior ::clj-watch-custom-src
          :triggers #{:watch.custom.src+}
          :reaction (fn [editor cur meta opts src]
                      (let [wrapped (if (:verbatim opts)
                                      "$1"
                                      "(pr-str $1)")
                            watch (str "(println (str " (pr-str watch-sentinel)
                                       " (pr-str {:meta " (pr-str (merge (dissoc opts :exp) meta))
                                       " :result " wrapped "})))")]
                        (fill-watch-placeholders (:exp opts) src meta watch))))

(behavior ::cljs-watch-result
          :triggers #{:editor.eval.cljs.watch}
          :reaction (fn [editor res]
                      (when-let [watch (get (:watches @editor) (-> res :meta :id))]
                        (let [str-result (if-not (-> res :meta :verbatim)
                                           (pr-str (:result res))
                                           (:result res))
                              str-result (if (= str-result "#<[object Object]>")
                                           ;; console/util-inspect upstream, which no
                                           ;; longer exists; console/inspect is what
                                           ;; core offers now and takes only the value.
                                           (console/inspect (:result res))
                                           str-result)
                              str-result (util/escape str-result)]
                          (object/raise (:inline-result watch) :update! str-result)))))

(behavior ::clj-watch-result
          :triggers #{:editor.eval.clj.watch}
          :reaction (fn [editor res]
                      (when-let [watch (get (:watches @editor) (-> res :meta :id))]
                        (let [str-result (:result res)
                              str-result (util/escape str-result)]
                          (object/raise (:inline-result watch) :update! str-result)))))


;;****************************************************
;; doc
;;****************************************************

(defn buffer-ns
  "The namespace this buffer declares, or nil.

  `info` and `complete` resolve a symbol relative to a namespace, and the
  buffer says which one in its first form. Read here rather than asked of the
  server, because the file on disk and the buffer disagree constantly and it is
  the buffer the cursor is in."
  [editor]
  (second (re-find #"\(ns\s+([\w\.\-\*\+\!\?<>=]+)" (ed/->val editor))))

(behavior ::clj-doc
          :triggers #{:editor.doc}
          :reaction (fn [editor]
                      (let [token (find-symbol-at-cursor editor)
                            command :editor.clj.doc
                            info (assoc (@editor :info)
                                   :result-type :doc
                                   :loc (:loc token)
                                   :sym (:string token)
                                   :ns (buffer-ns editor)
                                   :print-length (object/raise-reduce editor :clojure.print-length+ nil))]
                        (if token
                          (clients/send (eval/get-client! {:command command
                                                           :info info
                                                           :origin editor
                                                           :create try-connect})
                                        command info :only editor)
                          ;; Said rather than skipped. A REPL that is connected
                          ;; takes this surface from the language server — see
                          ;; lt.objs.providers — so a `when` here is the whole
                          ;; feature going quiet, and the cursor being on a
                          ;; paren or on whitespace is the ordinary case rather
                          ;; than a rare one.
                          (notifos/set-msg! "No symbol at the cursor to document.")))))

(behavior ::print-clj-doc
          :triggers #{:editor.clj.doc}
          :reaction (fn [editor result]
                      (when (= :doc (:result-type result))
                        ;; `(if-not result …)` was here and could not fire:
                        ;; `result` has just been read for `:result-type`, so it
                        ;; is a map and truthy every time. What actually happens
                        ;; is that cider-nrepl answers `info` with no-info — for
                        ;; a local, a keyword, anything it cannot resolve — and
                        ;; every field comes back nil. That drew an empty box or
                        ;; nothing at all, and said nothing either way.
                        (if (or (seq (str (:doc result))) (seq (str (:args result))))
                          (object/raise editor :editor.doc.show! result)
                          (notifos/set-msg! (str "No documentation for "
                                                 (or (:name result) "that")
                                                 "."))))))

(defn symbol-token? [s]
  ;; `(:string token)` is nil for a position CodeMirror has no token at, and
  ;; re-seq throws on nil rather than returning it — so asking for
  ;; documentation with the cursor just past the end of a line took the whole
  ;; behavior down, and lt.object swallowed it.
  (when (string? s)
    (re-seq #"[\w\$_\-\.\*\+\/\?\><!]" s)))

(defn find-symbol-at-cursor [editor]
  (let [loc (ed/->cursor editor)
        token-left (ed/->token editor loc)
        token-right (ed/->token editor (update-in loc [:ch] inc))]
    (or (when (symbol-token? (:string token-right))
          (assoc token-right :loc loc))
        (when (symbol-token? (:string token-left))
          (assoc token-left :loc loc)))))

(behavior ::cljs-doc
          :triggers #{:editor.doc}
          :reaction (fn [editor]
                      (let [token (find-symbol-at-cursor editor)
                            command :editor.cljs.doc
                            info (assoc (@editor :info)
                                   :result-type :doc
                                   :loc (:loc token)
                                   :sym (:string token)
                                   :ns (buffer-ns editor)
                                   :print-length (object/raise-reduce editor :clojure.print-length+ nil))]
                        (when token
                          (clients/send (eval/get-client! {:command command
                                                           :info info
                                                           :origin editor
                                                           :create try-connect})
                                        command info :only editor)))))

(behavior ::print-cljs-doc
          :triggers #{:editor.cljs.doc}
          :reaction (fn [editor result]
                      (when (= :doc (:result-type result))
                        (if-not result
                          (notifos/set-msg! "No docs found." {:class "error"})
                          (object/raise editor :editor.doc.show! result)))))

(behavior ::clj-doc-search
          :triggers #{:types+}
          :reaction (fn [this cur]
                      (conj cur {:label "clj" :trigger :docs.clj.search :file-types #{"Clojure"}})
                      ))

(behavior ::cljs-doc-search
          :triggers #{:types+}
          :reaction (fn [this cur]
                      (conj cur {:label "cljs" :trigger :docs.cljs.search :file-types #{"ClojureScript"}})
                      ))

;;****************************************************
;; autocomplete
;;****************************************************

(behavior ::trigger-update-hints
          :triggers #{:editor.clj.hints.update!}
          :debounce 100
          :reaction (fn [editor res]
                      (when-let [default-client (-> @editor :client :default)] ;; dont eval unless we're already connected
                        (when @default-client
                          (let [info (:info @editor)
                                command (->dottedkw :editor (-> info :mime mime->type) :hints)]
                            (clients/send (eval/get-client! {:command command
                                                             :info info
                                                             :origin editor
                                                             :create try-connect})
                                          command info :only editor))))))

(behavior ::finish-update-hints
          :triggers #{:editor.clj.hints.result
                      :editor.cljs.hints.result}
          :reaction (fn [editor res]
                      (object/merge! editor {::hints res})
                      (object/raise auto-complete/hinter :refresh!)))

(behavior ::use-local-hints
          :triggers #{:hints+}
          :reaction (fn [editor hints token]
                      (when (not= token (::token @editor))
                        (object/merge! editor {::token token})
                        (object/raise editor :editor.clj.hints.update!))
                      (if-let [clj-hints (::hints @editor)]
                        (concat clj-hints hints)
                        hints)))

;;****************************************************
;; Jump to definition
;;****************************************************

(behavior ::jump-to-definition-at-cursor
          :triggers #{:editor.jump-to-definition-at-cursor!}
          :reaction (fn [editor]
                      (let [token (find-symbol-at-cursor editor)]
                        (when token
                          (object/raise editor :editor.jump-to-definition! (:string token))))))

(behavior ::start-jump-to-definition
          :triggers #{:editor.jump-to-definition!}
          :reaction (fn [editor string]
                      (let [info (:info @editor)
                            command (->dottedkw :editor (-> info :mime mime->type) :doc)
                            info (assoc info
                                   :result-type :jump
                                   :sym string)]
                        (clients/send (eval/get-client! {:command command
                                                         :info info
                                                         :origin editor
                                                         :create try-connect})
                                      command info :only editor))))

(behavior ::finish-jump-to-definition
          :triggers #{:editor.clj.doc
                      :editor.cljs.doc}
          :reaction (fn [editor {:keys [file line] :as res}]
                      (when (= :jump (:result-type res))
                        (if (and res file line)
                          (object/raise jump-stack/jump-stack :jump-stack.push! editor file {:line (dec line) :ch 0})
                          (notifos/set-msg! "Definition not found" {:class "error"})))))

;;****************************************************
;; Proc
;;****************************************************

(behavior ::on-out
          :triggers #{:proc.out}
          :reaction (fn [this data]
                      (let [out (.toString data)]
                        (console/write-to-log (str (:name @this) "[stdout]: " data))
                        (object/update! this [:buffer] str out)
                        (if (> (.indexOf out "nREPL server started") -1)
                          (do
                            (notifos/done-working)
                            (object/merge! this {:connected true})
                            (let [client (clients/by-id (:cid @this))]
                              (object/merge! client {:port (-> (re-seq #"port ([\d]+)" out) first second)})
                              (object/raise client :connect!))
                            ;(object/destroy! this)
                            )
                          (when-not (:connected @this)
                            (notifos/set-msg! "Retrieving deps.. " {}))))
                      ))

(behavior ::on-error
          :triggers #{:proc.error}
          :reaction (fn [this data]
                      (let [out (.toString data)]
                        (console/write-to-log (str (:name @this) "[stderr]: " data))
                        (when-not (> (.indexOf (:buffer @this) "nREPL server started") -1)
                          (object/update! this [:buffer] str data)
                          ))
                      ))


(behavior ::on-exit
          :triggers #{:proc.exit}
          :reaction (fn [this data]
                      ;(object/update! this [:buffer] str data)
                      (when-not (:connected @this)
                        (notifos/done-working)
                        (notifos/done-working)
                        (popup/popup! {:header "We couldn't connect."
                                       :body [:span "Looks like there was an issue trying to connect
                                              to the project. Here's what we got:" [:pre (:buffer @this)]]
                                       :buttons [{:label "close"}]})
                        (notifos/set-msg! "Failed to connect" {:class "error"})
                        (clients/rem! (clients/by-id (:cid @this))))
                      (proc/kill-all (:procs @this))
                      (object/destroy! this)
                      ))

(object/object* ::connecting-notifier
                :triggers []
                :behaviors [::on-exit ::on-error ::on-out]
                :init (fn [this notifier cid]
                        (object/merge! this {:notifier notifier :buffer "" :cid cid})
                        nil))

;; wrap-quotes and windows-escape were here, to quote the jar's path for a
;; command line assembled as a string. Nothing assembles one now — proc/exec
;; takes an argument vector and no shell sees it.

(declare lein-args)

(def build-tools
  "How to start an nREPL, by the file that says what kind of project this is.

  In order, and the order is the answer to \"a project with more than one\": a
  `shadow-cljs.edn` means shadow whatever else is beside it, because shadow is
  the one that knows that project's builds and can attach a ClojureScript REPL
  to the runtime the user already has open. A `deps.edn` without one is the
  Clojure CLI. A `project.clj` without either is Leiningen.

  Each brings cider-nrepl, because Light Table's documentation, completion and
  stacktraces are cider-nrepl's — that is a feature of the editor rather than a
  dependency a project should have to declare to be opened in it."
  [{:tool :shadow
    :marker "shadow-cljs.edn"
    :exe "shadow-cljs"
    :name "shadow-cljs"
    :install "https://github.com/thheller/shadow-cljs#installation"
    :args (fn [] ["-d" (str "cider/cider-nrepl:" cider-nrepl-version)
                  "server"])}

   {:tool :deps
    :marker "deps.edn"
    :exe "clojure"
    :name "the Clojure CLI"
    :install "https://clojure.org/guides/install_clojure"
    :args (fn []
            ["-Sdeps" (str "{:deps {nrepl/nrepl {:mvn/version \"" nrepl-version "\"}"
                           " cider/cider-nrepl {:mvn/version \"" cider-nrepl-version "\"}"
                           " cider/piggieback {:mvn/version \"" piggieback-version "\"}"
                           " org.clojure/clojurescript {:mvn/version \"" clojurescript-version "\"}}}")
             "-M" "-m" "nrepl.cmdline"
             "--middleware" "[cider.nrepl/cider-middleware,cider.piggieback/wrap-cljs-repl]"])}

   {:tool :lein
    :marker "project.clj"
    :exe "lein"
    :name "Leiningen"
    :install "https://leiningen.org/#install"
    :args (fn [] (lein-args))}])

(defn tool-for
  "The entry in [[build-tools]] whose marker `dir` holds, or nil."
  [dir]
  (first (filter #(files/exists? (files/join dir (:marker %))) build-tools)))

(defn lein-args
  "The `lein` command line that starts a headless nREPL Light Table can talk to.

  Leiningen's `update-in` task edits the project map before the next task
  runs, and `--` separates one from the next. This is the same jack-in every
  other Clojure editor performs, and deliberately so — a project does not have
  to know anything about Light Table to be opened in it.

  Each value is read by the Clojure reader, which is why the coordinates
  arrive with their brackets. Nothing goes through a shell, so the quoting has
  to be in the argument itself."
  []
  ["update-in" ":dependencies" "conj" (str "[nrepl/nrepl \"" nrepl-version "\"]") "--"
   "update-in" ":dependencies" "conj" (str "[cider/piggieback \"" piggieback-version "\"]") "--"
   "update-in" ":dependencies" "conj" (str "[org.clojure/clojurescript \"" clojurescript-version "\"]") "--"
   "update-in" ":plugins" "conj" (str "[cider/cider-nrepl \"" cider-nrepl-version "\"]") "--"
   ;; A bare symbol, not a string: the value is read by the Clojure reader, and
   ;; a string here fails with "String cannot be cast to IFn" naming neither
   ;; the middleware nor the quoting.
   "update-in" ":repl-options:nrepl-middleware" "conj" "cider.piggieback/wrap-cljs-repl" "--"
   "repl" ":headless"])

(defn run-repl
  "Start the REPL for this project, through the tool the project uses.

  This replaced a 15MB uberjar that was Leiningen 2.5.2 packaged, fetched at
  build time, and dead on every JDK this editor supports — see
  plugins/Clojure/VENDORED.md. Using what the user already has means the REPL
  tracks their JDK and their project rather than a 2015 snapshot of both, and
  it is what every other editor's Clojure integration does."
  ;; `tool-exe` destructured rather than read off the map below, because the
  ;; `let` rebinds `obj` to the notifier object — reading it there asked the
  ;; wrong thing and silently fell back to the bare executable name.
  [{:keys [project-path name client build-tool tool-exe]}]
  ;; `n` upstream, which is not bound anywhere: the notifier argument has
  ;; always arrived undefined, and nothing reads `:notifier` back out. nil says
  ;; so rather than relying on an undefined property lookup.
  (let [obj (object/create ::connecting-notifier nil (clients/->id client))
        args (vec ((:args build-tool)))
        exe (or tool-exe (:exe build-tool))]
    (notifos/working "Connecting..")
    ;; console/core-log is a path in this fork, not a write stream — see
    ;; lt.objs.console. Calling .write on it threw, and two of the three call
    ;; sites were in the behaviors that read the REPL process's output, so the
    ;; connecting notifier died on the first line the server printed.
    (console/write-to-log (str "STARTING CLIENT: " exe " " (string/join " " args) "\n"))
    (proc/exec {:command exe
                :args args
                :cwd project-path
                :obj obj})
    ;; A project client had no name, so the Connect bar showed "null". The
    ;; directory it is rooted at is what a user would call it.
    (object/merge! client {:dir project-path
                           :build-tool (:tool build-tool)
                           :name (or name (last (string/split project-path #"/")))})
    (object/raise client :try-connect!)))

(defn run-local-server [client]
  (check-all {:path local-project-clj
              :client client
              :name local-name}))

(defn tool-exe
  "Where to find `exe` for a project at `dir`, or nil.

  The project's own `node_modules/.bin` before `PATH`, which is the rule
  `lt.objs.editor.lsp/server-command` already applies to language servers and
  is right here for the same reason — a shadow-cljs project keeps its
  shadow-cljs there, pinned to the version that project builds with, and it is
  usually nowhere else at all."
  [dir exe]
  (let [local (files/join dir "node_modules" ".bin" exe)]
    (if (files/exists? local)
      local
      (let [found (.which shell exe)]
        (when-not (or (nil? found) (empty? (str found)))
          (str found))))))

(defn check-tool
  "Whether the tool this project needs is installed.

  After [[find-project]], because which tool it is depends on what the project
  turned out to be."
  [{:keys [build-tool project-path] :as obj}]
  (assoc obj :tool-exe (when (and build-tool project-path)
                         (or (:lein-exe @clj-lang)
                             (tool-exe project-path (:exe build-tool))))))

(defn find-project
  "The nearest directory at or above `:path` that some build tool claims.

  Walked once looking for every marker rather than once looking for
  `project.clj`, which is what this did — so a shadow-cljs or deps.edn project
  had no project at all and could not start a REPL."
  [obj]
  (loop [dir (files/parent (:path obj))]
    (cond
      (or (empty? dir) (= dir (files/parent dir)))
      (assoc obj :project-path nil :build-tool nil)

      (tool-for dir)
      (assoc obj :project-path dir :build-tool (tool-for dir))

      :else (recur (files/parent dir)))))

(defn notify [obj]
  (let [{:keys [tool-exe project-path path build-tool]} obj]
    (cond
     (not project-path)
     (console/error
      (str "No Clojure project above " path ". Looked for "
           (string/join ", " (map :marker build-tools))))

     (or (not tool-exe) (empty? tool-exe))
     (popup/popup! {:header (str "We couldn't find " (:name build-tool) ".")
                    :body (str "This project has a " (:marker build-tool)
                               ", so Light Table starts its REPL with "
                               (:name build-tool) " — which reads that file, works out "
                               "the classpath and knows the project's builds. "
                               "Light Table used to ship its own copy of a build tool; "
                               "that copy was from 2015 and does not run on a current JDK.")
                    :buttons [{:label (str "Install " (:name build-tool))
                               :action (fn [] (platform/open (:install build-tool)))}
                              {:label "ok"}]})

     :else (run-repl obj))
    obj))

(defn check-all [obj]
  (-> obj
      ;; The project first: which tool has to be installed is a question about
      ;; what kind of project this turned out to be.
      (find-project)
      (check-tool)
      (notify))
  (:client obj))

(object/object* ::langs.clj
                :tags #{:clojure.lang})

(def clj-lang (object/create ::langs.clj))

(def cljs-browser-paths
  "Relative paths to search for when connecting to a Clojurescript Browser."
  [])

(behavior ::set-cljs-browser-paths
          :triggers #{:object.instant}
          :desc "Clojure: Set relative paths or urls to check for and use in ClojureScript Browser"
          :type :user
          :params [{:label "paths"}]
          :reaction (fn [this paths]
                      (set! cljs-browser-paths paths)))

(defn find-cljs-browser-url
  "Searches cljs-browser-paths for first url or relative path to exist and returns it."
  [ed]
  (let [project-dir (files/parent (files/walk-up-find (get-in @ed [:info :path]) "project.clj"))]
    (some #(if (re-find #"^https?://" %)
             %
             (when (files/exists? (files/join project-dir %))
               (str "file://" (files/join project-dir %))))
          cljs-browser-paths)))

(scl/add-connector {:name "ClojureScript Browser"
                    :desc "Open a browser tab to eval ClojureScript"
                    :connect (fn []
                               (let [ed (pool/last-active)
                                     default-url (find-cljs-browser-url ed)]
                                 (cmd/exec! :add-browser-tab default-url)
                                 (if default-url
                                   (tabs/active! ed)
                                   ;; Need timeout for message to show up after connection message
                                   (js/setTimeout (fn [] (notifos/set-msg! "No file or url found for cljs connection. Enter a valid one in the browser"
                                                                           {:class "error"}))
                                                  10000))))})

(cmd/command {:command :client.refresh-connection
              :desc "Client: Refresh client connection"
              :exec (fn []
                      (when-let [client (-> (pool/last-active) deref :client :exec)]
                        (object/raise client :client.refresh!)))})
