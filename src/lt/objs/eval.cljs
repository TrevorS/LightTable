(ns lt.objs.eval
  "Provide objects for doing evals through clients and displaying inline
  results from evals"
  (:require [lt.object :as object]
            [lt.objs.canvas :as canvas]
            [lt.objs.editor :as ed]
            [lt.objs.menu :as menu]
            [lt.objs.files :as files]
            [lt.objs.editor.pool :as pool]
            [lt.objs.clients :as clients]
            [lt.util.cljs]
            [lt.objs.sidebar.command :as cmd]
            [lt.objs.notifos :as notifos]
            [lt.objs.popup :as popup]
            [lt.objs.console :as console]
            [lt.util.dom :as dom]
            [clojure.string :as string]
            [cljs.reader :as reader]
            [lt.objs.platform :as platform]
            [lt.ui :as ui]
            [lt.ui.host :as host])
  (:require-macros [lt.macros :refer [behavior]]))

(defn unescape-unicode [s]
  (string/replace s
                  #"\\x(..)"
                  (fn [res r]
                    (js/String.fromCharCode (js/parseInt r 16)))))

(let [ev-id (atom 0)]
  (defn append-source-file [code file]
    (str code "\n\n//# sourceURL=" (or file "evalresult") "[eval" (swap! ev-id inc) "]")))

(defn pad [code lines]
  (str (reduce str (repeat lines "\n"))
       code))

(def ^:private print-level
  "How deep a printed result goes before it says `#`.

  Deep enough that no ordinary value is truncated, and finite because some
  values are not ordinary. A Light Table object is an atom whose state holds
  other objects, so printing one walks the object graph — and the graph has
  cycles, so it does not finish. `(first (pool/by-path f))` in a buffer, or
  through `lt.objs.control`, took the window's stack with it and the
  RangeError came out of whatever had called in."
  12)

(defn cljs-result-format [n]
  (binding [*print-level* print-level]
    (try
      (cond
        (coll? n) (pr-str n)
        (fn? n) (str "(fn " (.-name n) " ..)")
        (nil? n) "nil"
        (= (pr-str n) "#<[object Object]>") (console/inspect n)
        :else (pr-str n))
      (catch :default e
        ;; A backstop for the values `*print-level*` does not bound: a native
        ;; object holding a cycle is printed by JavaScript, not by us. A
        ;; result nobody can read is still better than an exception thrown at
        ;; whoever asked for it.
        (str "#<unprintable: " (.-message e) ">")))))

(behavior ::on-selected-cb
          :triggers #{:selected}
          :reaction (fn [obj client]
                      (let [cb (@obj :cb)]
                        (cb client))))

(behavior ::on-selected-destroy
          :triggers #{:selected}
          :reaction (fn [obj client]
                      (object/raise obj :destroy)
                      ))

(def eval-queue (atom []))

(behavior ::queue-on-no-client
          :triggers #{:no-client}
          :reaction (fn [this queue-item]
                      (swap! eval-queue conj queue-item)
                      ))

(behavior ::alert-on-no-client
          :triggers #{:no-client}
          :reaction (fn [this]
                      (popup/popup! {:header "No client available."
                                     :body "We don't know what kind of client you want for this one. Try starting a client by choosing one of the connection types in the connect panel."
                                     :buttons [{:label "Connect a client"
                                                :action (fn []
                                                          (cmd/exec! :show-add-connection))}]})
                      ))

(behavior ::queue!
          :triggers #{:queue!}
          :reaction (fn [this queue-item]
                      (swap! eval-queue conj queue-item)
                      ))


(defn drain [queue]
  (vec (remove
        (fn [cur]
          (let [[_ _ cb] cur
                [result client] (apply clients/discover cur)]
            (when (= :found result)
              (cb client)
              true)))
        queue)))

(behavior ::on-connect-check-queue
          :triggers #{:connect}
          :reaction (fn [this]
                      (swap! eval-queue drain)
                      ))

(object/object* ::evaler
                :tags #{:evaler}
                :init (fn []))

(def evaler (object/create ::evaler))

(object/add-behavior! clients/clients ::on-connect-check-queue)

(defn try-read [r]
  (try
    (reader/read-string r)
    (catch :default e
      (console/error e))))

(defn find-client [{:keys [origin command info key create] :as opts}]
  (let [[result client] (clients/discover command info)
        key (or key :default)]
    (condp = result
      :none (if create
              (create opts)
              (do
                (notifos/done-working)
                (object/raise evaler :no-client opts)
                (clients/placeholder)))
      :found client
      :select (do
                (object/raise evaler :select-client client (fn [client]
                                                             (clients/swap-client! (-> @origin :client key) client)
                                                             (object/update! origin [:client] assoc key client)))
                (clients/placeholder))
                )))

(defn get-client! [{:keys [origin command key create] :as opts}]
  (let [key (or key :default)
        cur (-> @origin :client key)]
    (if (and cur (clients/available? cur))
      cur
      (let [neue (find-client opts)]
        (object/update! origin [:client] assoc key neue)
        (object/raise origin :set-client neue)
        neue))))

;;****************************************************
;; inline result
;;****************************************************

(defn ->result-class [this trunc]
  (str (:class this)
       "-result result-mark"
       (when (or (:open this)
                 (not trunc))
         " open"
         )))

(defn truncate-result
  ([r] (truncate-result r nil))
  ([r opts]
   (when (string? r)
     (let [nl (.indexOf r "\n")
           len (if (> nl -1)
                 nl
                 (:trunc-length opts 50))]
       (if (> (count r) len)
         (str (subs r 0 len)  " …")
         r)))))

(defn- ->body
  "A result as something that can be drawn.

  `:result` is whatever the language handed over. Usually a string, sometimes
  hiccup — and sometimes a DOM node another object owns, which is what a
  JavaScript evaluation against a connected browser produces: the devtools
  client answers an object with an expandable inspector, and
  `lt.objs.browser/eval-js-form` passes that node straight through.

  Replicant does not render a node, and it does not complain about one either.
  It drew the result mark with nothing inside and reported no error, which is
  the failure this whole migration keeps turning up. Hosted, it is placed."
  [result]
  (if (and (some? result) (number? (.-nodeType ^js result)))
    [::host/host {:tag :span :content result}]
    result))

(defn- inline-res-ui
  "What is inside an inline result.

  Both halves are always drawn and the class on the root decides which one you
  see — that is what `open` means here, and it is why the class is the root's
  rather than something inside it. See [[lt.ui/node]]'s `attrs`."
  [this]
  (let [{:keys [result] :as info} @this
        truncated (truncate-result result info)]
    ;; A seq rather than a vector: Replicant renders either one node or a list
    ;; of them, and a vector of two would be read as a tag and its children.
    (remove nil? (list (when truncated [:span.truncated truncated])
                       [:span.full (->body result)]))))

(defn- ->inline-res
  "The node for an inline result.

  Handlers go on with `dom/on` rather than in the root's hiccup, because the
  root is built by `lt.ui.hiccup` — it is the element the object hands out, and
  Replicant owns only what is inside it. So it does not read `:on`."
  [this]
  (doto (ui/node this [:span] inline-res-ui
                 (fn [obj]
                   {:class (->result-class @obj (truncate-result (:result @obj) @obj))}))
    (dom/on :mousewheel (fn [e] (dom/stop-propagation e)))
    (dom/on :click (fn [e] (dom/prevent e) (object/raise this :click)))
    (dom/on :contextmenu (fn [e] (dom/prevent e) (object/raise this :menu! e)))
    (dom/on :dblclick (fn [e] (dom/prevent e) (object/raise this :double-click)))))

(behavior ::result-menu+
          :triggers #{:menu+}
          :reaction (fn [this items]
                      (conj items
                            {:label "Remove result"
                             :order 1
                             :click (fn [] (object/raise this :clear!))}
                            {:label "Copy result"
                             :order 2
                             :click (fn [] (object/raise this :copy))})))

(behavior ::expand-on-click
          :triggers #{:click :expand!}
          :reaction (fn [this]
                      (object/merge! this {:open true})
                      (object/raise this :changed)
                      ))

(behavior ::shrink-on-double-click
          :triggers #{:double-click :shrink!}
          :reaction (fn [this]
                      (object/merge! this {:open false})
                      (object/raise this :changed)
                      (ed/focus (:ed @this))))

(behavior ::destroy-on-cleared
          :triggers #{:cleared}
          :reaction (fn [this]
                      (object/destroy! this)))

(behavior ::clear-mark
          :triggers #{:clear!}
          :reaction (fn [this]
                      (when (deref (:ed @this))
                        (when-let [listener (:listener @this)]
                          (ed/off (:ed @this) :change listener))
                        (.clear (:mark @this))
                        (object/raise this :clear)
                        (object/raise this :cleared))))

(behavior ::copy-result
          :triggers #{:copy}
          :reaction (fn [this]
                      (platform/copy (:result @this))))

(behavior ::changed
          :triggers #{:changed}
          :reaction (fn [this]
                      (.changed ^js (:mark @this))
                      ))

(behavior ::update!
          :triggers #{:update!}
          :reaction (fn [this res]
                      (let [content (object/->content this)
                            full (dom/$ :.full content)
                            scroll (dom/scroll-top full)]
                        (when-let [t (truncate-result res)]
                          (when-let [trunc (dom/$ :.truncated content)]
                            (dom/html trunc t)))
                        (dom/html full res)
                        (dom/scroll-top full scroll))))

(behavior ::move-mark
          :triggers #{:move!}
          :desc "Editor: Take an inline result away when its line goes"
          :doc "A result follows its text on its own — it is a mark in the
                document, and a mark is mapped through every edit. What it
                cannot decide for itself is when it has outlived the thing it
                was about, which is what this is: the mark is gone, or the line
                it sits on has been emptied.

                CodeMirror 5 needed rather more. A bookmark there did not move
                the way this one does, so the reaction this replaces re-made it
                after any change that might have shifted it, and read the line's
                text off a handle to decide."
          :reaction (fn [this _change]
                      (let [editor (:ed @this)
                            ^js mark (:mark @this)
                            ^js loc (when mark (.find mark))]
                        (when (or (nil? loc)
                                  (string/blank? (ed/line editor (.-line (.-from loc)))))
                          (object/raise this :clear!)))))


(object/object* ::inline-result
                :triggers #{:click :double-click :clear!}
                :tags #{:inline :inline.result}
                :init (fn [this info]
                        (when-let [ed (ed/->cm-ed (:ed info))]
                          ;; Before the node, and that is the change: the view
                          ;; is a function of the object, so `:result` and
                          ;; `:class` have to be on it before the first draw.
                          ;; They used to arrive in the same `merge!` as the
                          ;; mark, which is after.
                          (object/merge! this info)
                          (let [content (->inline-res this)
                                ;; The editor, not a handle to one line.
                                ;; CodeMirror 5 could tell you when a particular
                                ;; line changed or went away; a line is not an
                                ;; object here, so the question is asked of the
                                ;; document and answered in ::move-mark.
                                listener (fn [_ change]
                                           (object/raise this :move! change))]
                            (ed/on (:ed info) :change listener)
                            (object/merge! this
                                           {:listener listener
                                            :mark (ed/bookmark ed
                                                               {:line (-> info :loc :line)}
                                                               {:widget content
                                                                :insertLeft true})})
                            content))))






(behavior ::inline-results
          :triggers #{:editor.result}
          :reaction (fn [this res loc opts]
                      (let [ed (:ed @this)
                            type (or (:type opts) :inline)
                            line (ed/line-handle ed (:line loc))
                            res-obj (object/create ::inline-result {:ed this
                                                                    :class (name type)
                                                                    :opts opts
                                                                    :result res
                                                                    :loc loc
                                                                    :line line})]
                        (when-let [prev (get (@this :widgets) [line type])]
                          (when (:open @prev)
                            (object/merge! res-obj {:open true}))
                          (object/raise prev :clear!))
                        (when (:start-line loc)
                          (doseq [widget (map #(get (@this :widgets) [(ed/line-handle ed %) type]) (range (:start-line loc) (:line loc)))
                                  :when widget]
                            (object/raise widget :clear!)))
                        (object/update! this [:widgets] assoc [line type] res-obj))))

;;****************************************************
;; underline result
;;****************************************************

(defn ->spacing [text]
  (when text
    (-> (re-seq #"^\s+" text)
        (first))))

(defn- ->underline-result
  "A result drawn on its own line under the code, as a node.

  `lt.ui/element` rather than [[lt.ui/node]] because nothing redraws it: unlike
  an inline result there is no `open` to toggle, and the class comes from the
  `:class` it was created with.

  `:result` is hiccup, and that is what took the longest to be able to say. Its
  two producers — a plot from IPython, a language's answer to \"toggle docs\" —
  each used to hand over a DOM node built by `defui`, which Replicant cannot
  render, so this widget could not become one until both of them moved."
  [this info]
  (ui/element
   [:div {:class (str "underline-result " (when (-> info :class) (:class info)))
          :on {:click (fn [e] (dom/prevent e) (object/raise this :click))
               :contextmenu (fn [e] (dom/prevent e) (object/raise this :menu! e))
               :dblclick (fn [e] (dom/prevent e) (object/raise this :double-click))}}
    [:span.spacer (->spacing (ed/line (:ed info) (-> info :loc :line)))]
    [:pre (->body (:result info))]]))

(object/object* ::underline-result
                :tags #{:inline :inline.underline-result}
                :init (fn [this info]
                        (let [content (->underline-result this info)
                              ;; What the line handle's "delete" event was for:
                              ;; a doc under a line that no longer exists is a
                              ;; doc about nothing.
                              listener (fn [_ _]
                                         (when (>= (-> @this :loc :line)
                                                   (ed/line-count (:ed @this)))
                                           (object/raise this :clear!)))]
                          (ed/on (:ed info) :change listener)
                          (object/merge! this (assoc info
                                                :listener listener
                                                :widget (ed/line-widget (ed/->cm-ed (:ed info))
                                                                        (-> info :loc :line)
                                                                        content
                                                                        {:coverGutter false
                                                                         :above (-> info :above)})))
                          content)))

(behavior ::underline-results
          :triggers #{:editor.result.underline}
          :reaction (fn [this res loc opts]
                      (let [ed (:ed @this)
                            line (ed/line-handle ed (:line loc))
                            res-obj (object/create ::underline-result {:ed this
                                                                       :opts opts
                                                                       :result res
                                                                       :loc loc
                                                                       :line line})]
                        (when-let [prev (get (@this :widgets) [line :underline])]
                          (when (:open @prev)
                            (object/merge! res-obj {:open true}))
                          (object/raise prev :clear!))
                        (when (:start-line loc)
                          (doseq [widget (map #(get (@this :widgets) [(ed/line-handle ed %) :underline]) (range (:start-line loc) (:line loc)))
                                  :when widget]
                            (object/raise widget :clear!)))
                        (object/update! this [:widgets] assoc [line :underline] res-obj))))

(behavior ::copy-underline-result
          :triggers #{:copy}
          :reaction (fn [this]
                      ;; Read off what was drawn rather than off `:result`.
                      ;; That used to be a DOM node and the copy was its element
                      ;; children joined with newlines — which threw outright
                      ;; for the results whose `:result` is a plain string,
                      ;; because a string has no `.children`. `innerText` of the
                      ;; `pre` is what "copy the result" means for both.
                      (platform/copy
                       (or (some-> ^js (dom/$ :pre (object/->content this)) .-innerText) ""))))

;;****************************************************
;; inline exception
;;****************************************************

(defn ->exception-class [this]
  (str "inline-exception " (when (:open this)
                             "open"
                             )))

(defn- inline-exception-ui [this]
  (let [{:keys [ed ex loc]} @this]
    (list [:span.spacer (->spacing (ed/line ed (:line loc)))]
          [:pre (str ex)])))

(defn- ->inline-exception [this]
  (doto (ui/node this [:div] inline-exception-ui
                 (fn [obj] {:class (->exception-class @obj)}))
    (dom/on :click (fn [] (object/raise this :click)))
    (dom/on :contextmenu (fn [e] (object/raise this :menu! e)))
    (dom/on :dblclick (fn [] (object/raise this :double-click)))))

(behavior ::ex-shrink-on-double-click
          :triggers #{:double-click :shrink!}
          :reaction (fn [this]
                      (ed/focus (:ed @this))
                      (object/raise this :clear!)))


(behavior ::ex-clear
          :triggers #{:clear!}
          :reaction (fn [this]
                      (when (ed/->cm-ed (:ed @this))
                        (when-let [listener (:listener @this)]
                          (ed/off (:ed @this) :change listener))
                        (ed/remove-line-widget (ed/->cm-ed (:ed @this)) (:widget @this)))
                      (object/raise this :clear)
                      (object/raise this :cleared)))

(behavior ::ex-menu+
          :triggers #{:menu+}
          :reaction (fn [this items]
                      (conj items
                            {:label "Remove exception"
                             :click (fn [] (object/raise this :clear!))}
                            {:label "Copy exception"
                             :click (fn [] (object/raise this :copy))})))

(behavior ::copy-exception
          :triggers #{:copy}
          :reaction (fn [this]
                      (platform/copy (:ex @this))))

(object/object* ::inline-exception
                :triggers #{:click :double-click :clear!}
                :tags #{:inline :inline.exception}
                :init (fn [this info]
                        (if-not (-> info :loc :line)
                          (notifos/set-msg! (str (:ex info)) {:class "error"})
                          (do
                            (object/merge! this info)
                            (let [content (->inline-exception this)]
                              (object/merge! this
                                             {:widget (ed/line-widget (ed/->cm-ed (:ed info))
                                                                      (-> info :loc :line)
                                                                      content
                                                                      {:coverGutter false})})
                              content)))))

(behavior ::inline-exceptions
          :triggers #{:editor.exception}
          :reaction (fn [this ex loc]
                      (when (and ex loc (>= (:line loc) 0))
                        (let [ed (:ed @this)
                              line (ed/line-handle ed (:line loc))
                              ex-obj (object/create ::inline-exception {:ed this
                                                                        :ex ex
                                                                        :loc loc
                                                                        :line line})]
                          (doseq [prev [(get (@this :widgets) [line :inline])
                                        (get (@this :widgets) [line :underline])]
                                  :when prev]
                            (when (:open @prev)
                              (object/merge! ex-obj {:open true}))
                            (object/raise prev :clear!))
                          (when (:start-line loc)
                            (doseq [type [:inline :underline]
                                    widget (map #(get (@this :widgets) [(ed/line-handle ed %) type]) (range (:start-line loc) (:line loc)))
                                    :when widget]
                              (object/raise widget :clear!)))
                          (object/update! this [:widgets] assoc [line :inline] ex-obj)))))

(behavior ::eval-on-change
          :triggers #{:change}
          :desc "Editor: Eval when the editor changes"
          :type :user
          :debounce 300
          :reaction (fn [this]
                      (object/raise this :eval)))

(cmd/command {:command :clear-inline-results
              :desc "Eval: Clear inline results"
              :exec (fn []
                      (when-let [ed (pool/last-active)]
                        (doseq [[_ w] (:widgets @ed)]
                          (object/raise w :clear!))))})

(cmd/command {:command :eval-editor
              :desc "Eval: Eval editor contents"
              :exec (fn []
                      (when-let [ed (pool/last-active)]
                        (object/raise ed :eval)))})

(cmd/command {:command :eval-editor-form
              :desc "Eval: Eval a form in editor"
              :exec (fn []
                      (when-let [ed (pool/last-active)]
                        (object/raise ed :eval.one)))})

(cmd/command {:command :eval.custom
              :desc "Eval: Eval custom expression in editor"
              :hidden true
              :exec (fn [exp opts]
                      (when-let [ed (pool/last-active)]
                        (object/raise ed :eval.custom exp opts)))})


(cmd/command {:command :eval.cancel-all!
              :desc "Eval: Cancel evaluation for the current client"
              :exec (fn []
                      (when-let [ed (pool/last-active)]
                        (when (:client @ed)
                          (doseq [[_ client] (:client @ed)]
                            (clients/cancel-all! client)))))})

(cmd/command {:command :editor.disconnect-clients
              :desc "Editor: Disconnect clients attached to editor"
              :exec (fn []
                      (when-let [ed (pool/last-active)]
                        (doseq [client (-> @ed :client vals)]
                          (clients/close! client))))})

