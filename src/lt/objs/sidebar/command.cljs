(ns lt.objs.sidebar.command
  "Provide command sidebar for finding and executing a command"
  (:require [lt.object :as object]
            [lt.objs.context :as ctx]
            [lt.objs.sidebar :as sidebar]
            [lt.objs.command :as cmd]
            [lt.objs.app :as app]
            [lt.objs.keyboard :as keyboard]
            [lt.util.load :as load]
            [lt.window.modules :as window]
            [lt.ui :as ui]
            [lt.ui.filter :as filter-view]
            [lt.util.dom :as dom]
            [lt.util.cljs]
            [clojure.string :as string]
            [lt.ui.host :as host])
  (:require-macros [lt.macros :refer [behavior]]))



;**********************************************************
;; options input
;;**********************************************************

(behavior ::op-select!
          :triggers #{:select!}
          :reaction (fn [this idx]
                      (let [input (object/->content this)]
                        (object/raise this :select (dom/val input))
                        (object/raise this :selected))))

(behavior ::op-clear!
          :triggers #{:clear!}
          :reaction (fn [this]
                      (let [input (object/->content this)]
                        (dom/val input "")
                        (object/raise this :change! ""))))

(behavior ::op-focus!
          :triggers #{:focus!}
          :reaction (fn [this]
                      (let [input (object/->content this)]
                        (dom/focus input)
                        (.select input))))


(defn ->value [{:keys [value]}]
  (if-not value
    ""
    value))

(object/object* ::options-input
                :tags #{:options-input}
                :placeholder "search"
                ;; The one object whose content *is* one element: there is
                ;; nothing to render inside it, and the placeholder and value
                ;; that used to be `bound` are the root's own attributes. See
                ;; [[lt.ui/node]]'s `attrs`.
                :init (fn [this opts]
                        (object/merge! this opts)
                        (doto (ui/node this [:input.option {:type "text"}]
                                       (constantly nil)
                                       (fn [obj]
                                         {:placeholder (:placeholder @obj)
                                          :value (->value @obj)}))
                          (dom/on :focus (fn [_]
                                           (ctx/in! :options-input this)
                                           (object/raise this :active)))
                          (dom/on :blur (fn [_]
                                          (ctx/out! :options-input this)
                                          (object/raise this :inactive)))
                          (dom/on :keyup (fn [e]
                                           (object/raise this :change!
                                                         (dom/val (.-target ^js e))))))))

(defn options-input [opts]
  (let [lst (object/create ::options-input opts)]
    (object/raise lst :refresh!)
    lst))

;;**********************************************************
;; filter list
;;**********************************************************

(defn input-val
  "What is typed in the list's input.

  Read out of the object rather than off the `<input>`: the input is drawn from
  `:search` now, so the state is the answer and the element is the picture."
  [this]
  (:search @this))

(defn set-val [this v]
  (object/merge! this {:search v}))

(defn ensure-visible
  "Scroll the selected row into view.

  The one thing here that still reads the document, because scrolling is about
  where something ended up rather than about what it is. Both selectors follow
  what `lt.ui.filter` draws — `.filter-list__results` and `row--selected`, not
  the `ul`/`.selected` the pool used."
  [this]
  (when-let [list (dom/$ ".filter-list__results" (object/->content this))]
    (when-let [elem (dom/$ ".row--selected" list)]
      (cond
        (< (.-offsetTop elem) (.-scrollTop list))
        (set! (.-scrollTop list) (- (.-offsetTop elem) 15))

        (> (+ (.-offsetTop elem) (.-offsetHeight elem))
           (+ (.-scrollTop list) (.-clientHeight list)))
        (set! (.-scrollTop list)
              (- (+ (.-offsetTop elem) (.-offsetHeight elem) 15) (.-clientHeight list)))

        :else nil))))

(declare sidebar-command indexed-results)

(behavior ::move-selection
          :triggers #{:move-selection}
          :reaction (fn [this dir]
                      (object/raise this :set-selection! (+ dir (:selected @this)))
                      (ensure-visible this)
                      ))

(behavior ::set-selection!
          :triggers #{:set-selection!}
          :reaction (fn [this idx]
                      ;; One number. It was two DOM classes moved between two
                      ;; nodes of a pool, which is the same fact written where
                      ;; only a rendered document could hold it.
                      (let [cnt (min (count (:cur @this)) (:size @this))]
                        (when (pos? cnt)
                          (object/merge! this {:selected (mod idx cnt)})))))

(behavior ::change!
          :triggers #{:change!}
          :reaction (fn [this v]
                      (let [v (object/raise-reduce this :change+ v)]
                        (when-not (= (:search @this) v)
                          (object/merge! this {:selected 0
                                               :search v})
                          (object/raise this :refresh!)))))

(behavior ::escape!
          :triggers #{:escape!}
          :reaction (fn [this]
                      (object/raise this :inactive)
                      (cmd/exec! :close-sidebar)))

(behavior ::options-escape!
          :triggers #{:escape!}
          :reaction (fn [this]
                      (object/raise sidebar-command :cancel!)
                      (cmd/exec! :close-sidebar)))

(behavior ::set-on-select
          :triggers #{:select}
          :reaction (fn [this thing]
                      (when (:set-on-select @this)
                        (set-val this ((:key @this) thing)))))

(behavior ::select!
          :triggers #{:select!}
          :reaction (fn [this idx]
                      (let [cur (indexed-results @this)
                            cnt (count cur)
                            idx (or idx (:selected @this))
                            i (mod idx (if (> cnt (:size @this)) (:size @this) cnt))]
                        (if (> cnt 0)
                          (do
                            (object/raise this :select (aget (aget cur i) 0))
                            (object/raise this :selected))
                          (object/raise this :select-unknown (input-val this)))
                        )))

(behavior ::filter-active
          :triggers #{:active}
          :reaction (fn [this]
                      (ctx/in! :filter-list.input this)))

(behavior ::filter-inactive
          :triggers #{:inactive}
          :reaction (fn [this]
                      (ctx/out! :filter-list.input)))

(behavior ::clear!
          :triggers #{:clear!}
          :reaction (fn [this]
                      (object/merge! this {:search ""})
                      (object/raise this :change! "")))

(behavior ::filter-list.focus!
          :triggers #{:focus!}
          :reaction (fn [this]
                      (let [input (dom/$ :.search (object/->content this))]
                        (dom/focus input)
                        (.select input))))

(behavior ::update-lis
          :triggers #{:refresh!}
          :reaction (fn [this]
                      ;; Which is the whole of a refresh now: the results are
                      ;; state and the list is drawn from them.
                      (object/merge! this {:cur (indexed-results @this)})))


(defn ->items [items]
  (cond
   (satisfies? IDeref items) @items
   (fn? items) (items)
   :else items))

(defn score-sort [x y]
  (- (aget y 3) (aget x 3)))

(defn score-sort2 [x y]
  (let [^js a (aget y 4)
        ^js b (aget x 4)]
    (- (.-score a) (.-score b))))

(defn indexed-results [{:keys [search size items key]}]
  (let [items (apply array (->items items))
        map-func3 #(array % (key %) (.fastScore window/fuzzy (key %) search) nil nil)
        map-func (fn [item] (aset item 3 (.stringScore window/fuzzy (aget item 1) search))
                      item)
        map-func2 #(do (aset % 4 (.score window/fuzzy (aget % 1) search)) %)
        has-score (fn [item] (let [^js scored (aget item 4)] (> (.-score scored) 0)))]
    (if-not (empty? search)
      (let [score0 (.. items (map map-func3) (filter #(aget % 2)))
            score1 (.. score0  (map map-func) (sort score-sort))
            score2 (.. score1 (slice 0 50) (map map-func2) (filter has-score) (sort score-sort2))]
        score2)
      (.. items (map #(array % (key %) nil nil))))))

(defn- filter-ui
  "The list, from what the object knows.

  Handlers are functions rather than action vectors: a filter list belongs to
  an object and there are four of them, so what a row does is a question about
  which list you clicked in. See [[lt.ui.filter]]."
  [this]
  (filter-view/filter-list
   (assoc @this :results (:cur @this))
   {:on-input (fn [e] (object/raise this :change! (.. e -target -value)))
    :on-focus (fn [_] (object/raise this :active))
    :on-blur (fn [_] (object/raise this :inactive))
    :on-select (fn [i]
                 (fn [e]
                   (dom/prevent e)
                   (dom/stop-propagation e)
                   (object/raise this :set-selection! i)
                   (object/raise this :select! i)))}))

(object/object* ::filter-list
                :tags #{:filter-list}
                :selected 0
                :placeholder "search"
                :size 100
                :items []
                :search ""
                :cur []
                :init (fn [this opts]
                        (object/merge! this opts)
                        (ui/node this [:div.filter-list-host] filter-ui)))


(defn filter-list [opts]
  (let [lst (object/create ::filter-list opts)]
    (object/raise lst :refresh!)
    lst))

;;**********************************************************
;; Commands
;;**********************************************************

(behavior ::select-command
          :triggers #{:select}
          :reaction (fn [this sel]
                      (when-let [cmd (cmd/by-id sel)]
                        (if (:options cmd)
                          (do
                            (object/merge! sidebar-command {:active cmd})
                            (object/raise (:options cmd) :focus!))
                          (do
                            (object/raise sidebar-command :exec! cmd)
                            (object/raise sidebar-command :selected-exec cmd)
                            (object/merge! sidebar-command {:active nil})))
                        )
                      ))

(behavior ::select-hidden
          :triggers #{:select-unknown}
          :reaction (fn [this v]
                      (when-let [cmd (cmd/by-id (keyword v))]
                        (object/raise this :select cmd))))

(behavior ::post-select-pop
          :triggers #{:selected-exec}
          :reaction (fn [this]
                      (when (= this (:active @sidebar/rightbar))
                        (object/raise sidebar/rightbar :close!
                                      (not (or (ctx/in? :filter-list.input)
                                               (ctx/in? :options-input)))))))

(behavior ::exec-command
          :triggers #{:exec!}
          :reaction (fn [this sel & args]
                      (let [cmd (cmd/by-id sel)]
                        (cond
                         (not (:options cmd)) (apply cmd/exec! cmd args)
                         (and (:options cmd) (seq args)) (apply (:exec cmd) args)
                         :else (do (cmd/exec! :show-commandbar-transient)
                                 (object/raise (:selector @this) :select cmd))))))

(behavior ::exec-active!
          :triggers #{:exec-active!}
          :reaction (fn [this args]
                      (let [cmd (:active @this)]
                        (apply (:exec cmd) args)
                        (object/raise this :selected-exec cmd)
                        (object/merge! this {:active nil}))))

(behavior ::focus-on-show
          :triggers #{:show}
          :reaction (fn [this]
                      (object/raise this :focus!)))

(behavior ::focus!
          :triggers #{:focus!}
          :reaction (fn [this]
                      (if-not (:active @this)
                        (let [input (dom/$ :.search (object/->content this))]
                          (dom/focus input)
                          (.select input))
                        (object/raise (-> @this :active :options) :focus!))))

(behavior ::soft-focus!
          :triggers #{:soft-focus!}
          :reaction (fn [this]
                      (let [input (dom/$ :.search (object/->content this))]
                        (dom/focus input))))

(behavior ::refresh!
          :triggers #{:refresh!}
          :reaction (fn [this]
                      (object/raise (:selector @this) :refresh!)))

(behavior ::cancel!
          :triggers #{:cancel!}
          :reaction (fn [this]
                      (object/merge! this {:active nil})
                      (object/raise this :focus!)))

(defn ->options [this active]
  (when (:options active)
    (object/->content (:options active))))

(defn ->command-class [this]
  (str "command " (if (:active this)
                    "options"
                    "selector")
       (when (dom/has-class? (:content this) :active)
         " active")))

(defn ->binding [[k v]]
  (str v (when (> (.indexOf (str k) "emacs") -1)
           " (Emacs)")
       (when (> (.indexOf (str k) "vim") -1)
         " (Vim)")
       ))

(defn command->display
  "A command's row: what it is called, and what it is bound to.

  Hiccup rather than a string of HTML. It was built by concatenation around the
  command's own description, which meant a description with a `<` in it was
  markup — see doc/hygiene.md."
  [_orig _scored highlighted item]
  (list [:p highlighted]
        (when-let [binding (seq (keyboard/cmd->bindings (item :command)))]
          [:p.binding (string/join " | " (map ->binding (reverse binding)))])))

(defn- command-ui
  "The bar, which is two other objects' DOM and a header between them.

  The selector is a filter-list object made once in `:init`; the options slot
  is whichever object the active command brought with it, and changes. Both are
  hosted rather than described — see [[lt.ui.host]]. This is the shape
  doc/rendering.md called the one that cannot be swapped a component at a time,
  and hosting is what makes it swappable without the things it composes having
  to move first."
  [this]
  (let [{:keys [selector active]} @this]
    (list
     [::host/host {:class "selector" :content (object/->content selector)}]
     [:div.options
      [:h2 {:on {:click (fn [] (object/raise this :cancel!))}} (:desc active)]
      [::host/host {:content (->options this active)}]])))

(object/object* ::sidebar.command
                :tags #{:sidebar.command}
                :label "command"
                :active nil
                :order 3
                :init (fn [this]
                        ;; A function rather than a `computed` over a
                        ;; `subatom`: `->items` calls it when it needs the
                        ;; list, which is what those two were arranging.
                        (let [s2 (filter-list {:items (fn []
                                                        (->> (:commands @cmd/manager)
                                                             vals
                                                             (remove :hidden)))
                                               :transform #(command->display % %2 %3 %4)
                                               :key :desc
                                               :empty-what "No command matches"
                                               :empty-why "Nothing in the table has that name — the table is `lt.objs.command/manager`."})]
                          (object/merge! this {:selector s2})
                          (object/add-tags s2 [:command.selector])
                          (ui/node this [:div] command-ui
                                   (fn [obj] {:class (->command-class @obj)})))))

(behavior ::init-commands
          :triggers #{:post-init}
          :reaction (fn [app]
                      (object/raise sidebar-command :refresh!)))

(def sidebar-command (object/create ::sidebar.command))
(ctx/in! :commandbar sidebar-command)

(sidebar/add-item sidebar/rightbar sidebar-command)

(def command cmd/command)

(defn show-and-focus [opts]
  (object/raise sidebar/rightbar :toggle sidebar-command opts))

;; `pre-fill` and `show-filled` lived here, and `pre-fill` wrote the `<input>`'s
;; value with `dom/val`. `lt.ui.filter` draws that input from `:search` now, so
;; the write would have survived exactly until the next render. Neither had a
;; caller. Set `:search` on the object — see [[set-val]] — if either comes back.

(def exec! cmd/exec!)

(defn exec-active! [& args]
  (object/raise sidebar-command :exec-active! args))

(command {:command :show-commandbar
          :desc "Command: Show command bar"
          :hidden true
          :exec (fn []
                  (show-and-focus {})
                  )})

(command {:command :show-commandbar-transient
          :hidden true
          :desc "Command: Show command bar transiently"
          :exec (fn []
                  (show-and-focus {:transient? true}))})

(command {:command :quit
          :desc "Window: Quit Light Table"
          :exec (fn []
                  (app/close))})


(command {:command :passthrough
          :hidden true
          :desc "No-op key passthrough"
          :exec (fn []
                  (keyboard/passthrough))})

(command {:command :filter-list.input.move-selection
          :hidden true
          :desc "FilterList: move selection"
          :exec (fn [dir]
                  (object/raise (ctx/->obj :filter-list.input) :move-selection dir)
                  )})

(command {:command :filter-list.input.select!
          :hidden true
          :desc "FilterList: select"
          :exec (fn []
                  (object/raise (ctx/->obj :filter-list.input) :select!)
                  )})

(command {:command :filter-list.input.escape!
          :hidden true
          :desc "FilterList: escape"
          :exec (fn [force?]
                  (object/raise (ctx/->obj :filter-list.input) :escape! force?)
                  )})

(command {:command :options-input.select!
          :hidden true
          :desc "OptionsInput: select"
          :exec (fn []
                  (object/raise (ctx/->obj :options-input) :select!)
                  )})

(command {:command :options-input.escape!
          :hidden true
          :desc "OptionsInput: escape"
          :exec (fn []
                  (object/raise (ctx/->obj :options-input) :escape!)
                  )})
