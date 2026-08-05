(ns lt.objs.console
  "Provide console object for logging to file and displaying log messages
  in bottom bar"
  (:require [lt.object :as object]
            [lt.objs.app :as app]
            [lt.objs.files :as files]
            [lt.objs.bottombar :as bottombar]
            [lt.objs.command :as cmd]
            [lt.objs.statusbar :as statusbar]
            [lt.objs.tabs :as tabs]
            [clojure.string :as string]
            ;; `append` came off the refer with `write`: the console does not put
            ;; nodes anywhere any more. `dom` stays for the scroll and for the
            ;; menu's copy, which reads what was rendered.
            [lt.util.dom :as dom]
            [lt.objs.platform :as platform]
            [lt.ui :as ui]
            [lt.util.bridge :as bridge])
  (:require-macros [lt.macros :refer [behavior]]))

(def console-limit 50)
(def logs-dir (files/lt-user-dir "logs"))
(def core-log (try
                (when-not (files/exists? logs-dir)
                  (when-not (files/exists? (files/lt-user-dir))
                    (files/mkdir (files/lt-user-dir)))
                  (files/mkdir logs-dir))
;; A path rather than a write stream. Log lines are rare and appending is
                ;; one call, so a stream is a handle to keep alive for no gain.
                (files/join logs-dir (str "window" (app/window-number) ".log"))
                (catch :default e
                  (.error js/console (str "Failed to initialize the log writer: " e)))))

(defn ->ui [c]
  (object/->content c))

(defn dom-like? [thing]
  (or (vector? thing)
      (.-nodeType thing)
      (string? thing)))

(declare console)

(defn write-to-log [thing]
  (when core-log
    (.appendFileSync bridge/files core-log thing)))

;;*********************************************************
;; The lines, as a value
;;*********************************************************

;; The console was the last surface still building its own nodes, and it had the
;; best reason: it is genuinely append-only. `write` appended an `<li>`, dropped
;; the first child when there were too many, and `try-update` appended a text
;; node into a `<pre>` that was already on screen so that a process talking in
;; chunks accumulated in one row rather than producing a row per chunk.
;;
;; doc/hygiene.md's judgement was that making this a view means holding the last
;; fifty lines *as* a value, and that the streaming append is the thing that would
;; have to change. Both were right, and the second turned out to be the argument
;; *for* doing it rather than the obstacle: appending to a line you are holding is
;; `update :text str`, where appending to a line you have already drawn means
;; finding it by id in the document. The imperative version existed because there
;; was no value to update, not because streaming needs a DOM.

(defonce ^:private next-key
  ;; A line's identity, and it has to be stable. `:replicant/key` is what stops a
  ;; re-render from re-creating every row when the oldest is dropped: with the
  ;; index as the key, dropping the first line shifts all fifty and Replicant
  ;; rebuilds the list every time anything is logged.
  (atom 0))

(defn- push-line!
  "Add `line` to the console, dropping the oldest past [[console-limit]]."
  [line]
  (object/update! console [:lines]
                  (fn [ls]
                    (let [ls (conj (or ls []) (assoc line :key (swap! next-key inc)))
                          n (count ls)]
                      ;; `subvec` rather than `take`: the result stays a vector,
                      ;; which is what `update` on the last line below needs.
                      (if (> n console-limit)
                        (subvec ls (- n console-limit))
                        ls))))
  (when-not (bottombar/active? console)
    (statusbar/dirty))
  ;; After the value, because the redraw is what creates the node to scroll. The
  ;; watch `ui/node` installs renders synchronously, so by here the line is in
  ;; the document.
  (when-let [el (->ui console)]
    (dom/scroll-top el 10000000000)))

(defn log
  ([l] (log l nil))
  ([l class] (log l class nil))
  ([l class str-content]
   (when-not (= "" l)
     (when (or (string? l) str-content)
       (write-to-log (if (string? l) l str-content))
       (push-line! {:kind :log
                    :class class
                    :content (if-not (dom-like? l) (pr-str l) l)})
       nil))))

(defn error
  "Log errors, strings or any objects as console error(s). If an error,
  its stack is logged"
  [& errors]
  (statusbar/console-class "error")
  (doseq [e errors]
    (log (str (cond
               (.-stack e) (.-stack e)
               (string? e) e
               (not= (pr-str e) "[object Object]") (pr-str e)
               :else (str e)))
         "error")))

;; Was process.on("uncaughtException"), which the window no longer has — and
;; which only ever caught what reached node's handler anyway. These two are the
;; window's own, and between them they cover both ways a failure escapes.
(.addEventListener js/window "error" #(error (or (.-error %) (.-message %))))
(.addEventListener js/window "unhandledrejection" #(error (.-reason %)))

(defn- line-ui
  "One console line, as hiccup.

  Keyed, and the key is the line's own rather than its position — see
  [[next-key]].

  Two kinds, which is the distinction the old `->item` made by what its caller
  wrapped things in rather than by saying so: `log` put its content in a `<pre>`
  and `verbatim` did not, because a caller of `verbatim` is handing over the whole
  line. `:loc` is the third and is the only one with structure of its own."
  [{:keys [key kind class content file line text]}]
  [:li {:replicant/key key :class class}
   (case kind
     :loc [:table
           [:tr
            [:td.loc
             [:em.file file
              (when line [:em.line "[" line "]"])
              ": "]]
            [:td [:pre text]]]]
     :log [:pre content]
     ;; `verbatim`, whose caller owns the whole line.
     content)])

(defn- console-ui
  "The console, as a function of its lines.

  `ui/node` rather than `ui/state-node`: the lines belong to this object, so the
  object is the thing to watch. The `<ul>` is the root and stays put, because
  `object/->content` hands it out and the bottombar, a tabset and the scroll
  below all keep the reference — see [[lt.ui/node]]."
  [this]
  (ui/node this
           [:ul.console {:on {:contextmenu (fn [e] (object/raise this :menu! e))}}]
           (fn [obj] (map line-ui (:lines @obj)))))

(behavior ::on-close
          :triggers #{:close}
          :reaction (fn [this]
                      (object/merge! this {:current-ui :bottom})
                      (tabs/rem! this)))

(object/object* ::console
                :tags #{:console}
                :name "console"
                :dirty false
                ;; The last `console-limit` lines, newest last. What the console
                ;; *is*, rather than what it has drawn.
                :lines []
                :init (fn [this]
                        (object/merge! this {:current-ui :bottom})
                        (console-ui this)
                        ))

(behavior ::set-console-limit
          :triggers #{:object.instant}
          :desc "Console: Set buffer size"
          :type :user
          :params [{:label "size"}]
          :reaction (fn [this size]
                      (set! console-limit size)))

(defn inspect [thing]
  (.inspect ^js (.-host bridge/bridge) thing 2))

(defn verbatim
  ([thing] (verbatim thing nil))
  ([thing class]
   (verbatim thing class nil))
  ([thing class str-content]
   (when str-content
     (write-to-log str-content))
   (when class
     (statusbar/console-class class))
   (push-line! {:kind :verbatim :class class :content thing})
   nil))

(defn try-update
  "Append `content` to the line already streaming under `id`, if there is one.

  This is the one genuinely imperative-for-a-reason piece doc/hygiene.md named,
  and holding the lines as a value is what stopped it being imperative. nREPL
  stdout arrives in chunks under one id, and every chunk has to land in the row
  the first one made rather than making a row of its own.

  As a DOM operation that meant `document.querySelector('#console<id>')` and
  appending a text node — which is why the line carried a generated id at all. As
  a value it is `update :text str`, and the id is a field nobody has to put in the
  markup.

  **Any** line with that id, not just the last, which is what `querySelector`
  did and is the behaviour to keep: two processes talking at once each accumulate
  into their own row, and a chunk from the older one belongs in the row its
  stream started rather than in a new row at the bottom. Matched from the end, so
  that if an id were ever reused the most recent row wins."
  [{:keys [content id]}]
  (when (and id content)
    (let [ls (:lines @console)
          at (->> (map-indexed vector ls)
                  (filter (fn [[_ l]] (= id (:stream l))))
                  last
                  first)]
      (when at
        (object/update! console [:lines] update at update :text str content)
        (when-let [el (->ui console)]
          (dom/scroll-top el 10000000000))
        true))))

(defn loc-log [{:keys [file line content class str-content id] :as msg}]
  (when content
    (when (or (string? content) str-content)
      (write-to-log (str file "[" line "]: "
                         (if (string? content) content str-content) "\n")))
    (when-not (try-update msg)
      (when class
        (statusbar/console-class class))
      (push-line! {:kind :loc
                   :class class
                   :file file
                   :line line
                   ;; `:stream` is what a later chunk matches on. Present only
                   ;; when the caller gave an id, which is how it says the line
                   ;; may be continued.
                   :stream id
                   :text (if (string? content)
                           (string/replace content #"^\s+" "")
                           content)}))))

(defn clear []
  (object/merge! console {:lines []}))

(def console (object/create ::console))

(behavior ::menu+
          :triggers #{:menu+}
          :reaction (fn [this items event]
                      (conj items
                            {:label "Clear"
                             :order 1
                             :click (fn []
                                      (cmd/exec! :clear-console))}
                            {:label "Copy"
                             :order 2
                             :click (fn []
                                      (let [target (.-target event)
                                            item (if (= (.toLowerCase (.-tagName target)) "li")
                                                   target
                                                   (dom/parents target "ul.console li"))]
                                        (platform/copy (.-textContent target))))}
                            (when (not= :tab (:current-ui @console))
                              {:label "Hide console"
                               :order 3
                               :click (fn []
                                        (cmd/exec! :toggle-console))})
                            (when (not= :tab (:current-ui @console))
                              {:label "Open console tab"
                               :order 4
                               :click (fn []
                                        (cmd/exec! :toggle-console)
                                        (cmd/exec! :console-tab))}))))

;; These three were on the statusbar's console-toggle object, which is what the
;; `@FIXME: rename to statusbar?` beside each of them was asking about. The
;; answer turned out to be neither: the toggle is not an object any more — the
;; statusbar is a view and its unread count is state — so showing and hiding the
;; console belongs to the console.

(behavior ::toggle-console
          :triggers #{:toggle}
          :reaction (fn [this]
                      (object/raise bottombar/bottombar :toggle console)
                      (when (bottombar/active? console)
                        (dom/scroll-top (object/->content console) 10000000000)
                        (statusbar/clean))))

(behavior ::show-console
          :triggers #{:show!}
          :reaction (fn [this]
                      (object/raise bottombar/bottombar :show! console)
                      (when (bottombar/active? console)
                        (dom/scroll-top (object/->content console) 10000000000)
                        (statusbar/clean))))

(behavior ::hide-console
          :triggers #{:hide!}
          :reaction (fn [this]
                      (object/raise bottombar/bottombar :hide! console)))


;; `(bottombar/add-item console)` was here. It wrote the console into a
;; `:items` map on the bar that nothing read — the bar draws `(:active @this)`
;; and always has — so registering was a no-op that made the bar look general.
;; The console reaches it by `:show!`, `:hide!` and `:toggle` above, which is
;; what actually happens.

(cmd/command {:command :console-tab
              :desc "Console: Open the console in a tab"
              :exec (fn []
                      (when (not= :tab (:current-ui @console)) ; Running the command when tab is already opened in a tab was creating another new tab each time.
                        (object/raise console :hide!)
                        (object/merge! console {:current-ui :tab})
                        (tabs/add! console)
                      ))})


(cmd/command {:command :console.show
              :desc "Console: Show console"
              :hidden true
              :exec (fn []
                      (if (= (:current-ui @console) :tab)
                        (do (tabs/active! console) (statusbar/clean))
                        (object/raise console :show!)))})

(cmd/command {:command :console.hide
              :desc "Console: Hide console"
              :hidden true
              :exec (fn []
                      (if (= (:current-ui @console) :tab)
                        (object/raise console :close)
                        (object/raise console :hide!)))})

(cmd/command {:command :toggle-console
              :desc "Console: Toggle console"
              :exec (fn []
                      (if (= (:current-ui @console) :tab)
                        (do (tabs/active! console) (statusbar/clean))
                        (object/raise console :toggle)))})

(cmd/command {:command :clear-console
              :desc "Console: Clear console"
              :exec (fn [this]
                      (doseq [o (object/by-tag :clients.devtools)]
                        (object/raise o :clear!))
                      (clear))})
