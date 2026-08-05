(ns lt.plugins.doc
  "Provide documentation sidebar for searching docs. Used by language plugins"
  (:require [clojure.string :as string]
            [lt.object :as object]
            [lt.objs.context :as ctx]
            [lt.objs.clients :as clients]
            [lt.objs.notifos :as notifos]
            [lt.objs.editor :as editor]
            [lt.objs.editor.pool :as pool]
            ;; For `put-underline!`, which is the one rule about `:widgets` that
            ;; both writers of `[line :underline]` have to follow. The object type
            ;; is still named by keyword rather than required — see `inline-doc`.
            [lt.objs.eval :as eval]
            [lt.objs.sidebar :as sidebar]
            [lt.state :as state]
            [lt.util.dom :as dom]
            [lt.util.js :as util]
            [lt.util.cljs :refer [str-contains?]]
            [lt.objs.command :as cmd]
            [lt.ui :as ui])
  (:require-macros [lt.macros :refer [behavior]]))

(defn doc-on-line? [editor line]
  (let [line (editor/line-handle editor line)]
    (get-in @editor [:widgets [line :underline]])))

(defn remove! [editor cur]
  (object/update! editor [:widgets] dissoc [(:line @cur) :underline])
  (object/raise cur :clear!))

(behavior ::clear
          :triggers #{:clear}
          :reaction (fn [this]
                      (object/update! (:ed @this) [:widgets] dissoc [(:line @this) :underline])))

(defn inline-doc
  "Show `res` as a doc under the line `loc` names.

  Through [[lt.objs.eval/put-underline!]] rather than writing `:widgets`
  directly, which is what this used to do and is why a doc opened over an
  existing inline result orphaned it: a widget owns a DOM node, and replacing the
  entry without raising `:clear!` leaves the node on screen with nothing holding
  it. A Python plot followed by a doc on the same line was the case.

  `keep-open?` is false. A re-evaluated result replacing its own earlier value
  should stay expanded; a doc replacing a plot is not that."
  [this res opts loc]
  (let [ed (:ed @this)
        type :underline
        line (editor/line-handle ed (:line loc))
        res-obj (object/create :lt.objs.eval/underline-result {:ed this
                                                               :class (name type)
                                                               :opts opts
                                                               :result res
                                                               :loc loc
                                                               :line line})]
    (object/add-tags res-obj [:inline.doc])
    (eval/put-underline! this ed line res-obj loc false)))

(behavior ::doc-menu+
          :triggers #{:menu+}
          :reaction (fn [this items]
                      (conj items
                            {:label "Toggle docs"
                             :order 0.1
                             :enabled (not (editor/selection? this))
                             ;; `this`, not whatever the pool thinks was last
                             ;; active. A menu is opened *on* an editor, so it
                             ;; is holding the answer the command would
                             ;; otherwise go looking for — and `last-active` is
                             ;; set by the `:active` trigger, so an editor that
                             ;; has not been made active since it opened leaves
                             ;; it nil and the command a silent no-op.
                             :click (fn []
                                      (cmd/exec! :editor.doc.toggle this))}
                            {:type "separator"
                             :order 0.2}
                            )))


(def ^:private answer-window
  "How long an answer has to arrive before the press is called unanswered.

  A language server round-trip is milliseconds once it has indexed and a REPL's
  is not much worse, so this is generous rather than tight — the cost of being
  early is telling someone nothing happened when it is about to."
  2500)

(defn- unanswered!
  "Say that the press did nothing, if by now it has.

  This exists because \"toggle docs isn't working\" has been reported five
  times with four different causes, and every one of them presented the same
  way: nothing on screen, nothing in the bar, nothing in the console. Each fix
  closed the path it was about and the next silence looked identical.

  So the command checks. Every way this can fail ends in no widget and no
  message, whatever the reason and whether or not anyone thought to report it —
  which makes this the one guard that does not need to know what went wrong.

  It says where to look rather than guessing: `:lsp.status` already works the
  whole situation out, and duplicating a worse version of that sentence here is
  how two answers come to disagree."
  [ed line said]
  (util/wait answer-window
    (fn []
      (when (and (nil? (doc-on-line? ed line))
                 ;; Nobody said anything either — a decline that reported is a
                 ;; working feature saying no, and must not be talked over.
                 (= said (:text (:message @state/app))))
        (notifos/set-msg! (str "Nothing answered for documentation here. "
                               "Run 'Language server: Status for this editor' to see why."))))))

(cmd/command {:command :editor.doc.toggle
              :desc "Docs: Toggle documentation at cursor"
              :doc "Takes the editor to act on, and falls back to whichever was
                    last active. The argument is what the right-click menu
                    passes, because it knows.

                    The fallback is the fifth distinct cause of \"toggle docs
                    does nothing\": this was `(when-let [ed (pool/last-active)]
                    …)`, and `last-active` is set by the `:active` trigger —
                    so an editor that has not been made active since it opened
                    leaves it nil, and the whole command returned having done
                    and said nothing. It happened *before* the guard that
                    exists to notice exactly that, which is why four fixes
                    aimed at the silence never reached it."
              :exec (fn [& [given]]
                      (if-let [ed (or given (pool/last-active))]
                        (let [loc (editor/->cursor ed)]
                          (if-let [cur (doc-on-line? ed (:line loc))]
                            (remove! ed cur)
                            (let [said (:text (:message @state/app))]
                              (object/raise ed :editor.doc)
                              (unanswered! ed (:line loc) said))))
                        (notifos/set-msg!
                         "No active editor to document — click into one first.")))})

(defn- doc-ui
  "What a language said about the thing under the cursor, as hiccup.

  Handed to `lt.objs.eval/->underline-result` as its `:result`, which is why
  this is hiccup rather than a node: the widget around it is drawn by
  Replicant, and a DOM node spliced into hiccup is the one thing it cannot
  render."
  [doc]
  [:div.inline-doc
   [:h1 (:name doc)]
   [:h2 (:ns doc)]
   (when (and (:args doc)
              (not= (:args doc) "nil"))
     [:h3 (:args doc)])
   (when (and (:labels doc)
              (not= (:labels doc) ""))
     [:h3 (str "[" (:labels doc) "]")])
   (when (and (:doc doc)
              (not= (:doc doc) "nil"))
     [:pre (:doc doc)])])

(defn- retrieve-behavior
  "Helper method for behavior `editor.doc.show!` to determine if the given `ns` and `name`
  match existing behaviors.

  Returns found behavior or `nil`."
  [ns name]
  (@object/behaviors (keyword (str ns "/" (subs name 2)))))

(defn- retrieve-object-def
  "Helper method for behavior `editor.doc.show!` to determine if the given `ns` and `name`
  match existing object defs. Not recommended to print whole object def... use destructuring.

  Returns found object def or `nil`."
  [ns name]
  (@object/object-defs (keyword (str ns "/" (subs name 2)))))

(defn- retrieve
  "The behavior or object def `ns`/`name` names, or nil.

  Guarded, because both of the above do `(subs name 2)` to take the `::` off
  and `(subs nil 2)` throws. A language answering with no name at all is the
  ordinary case rather than a strange one — cider-nrepl answers `info` with
  every field nil for anything it has not loaded — and this used to throw from
  inside a behavior, which is a line in a console nobody reads and a doc bar
  that never appears."
  [ns name]
  (when (and (seq (str ns)) (string/starts-with? (str name) "::"))
    (or (retrieve-behavior ns name)
        (retrieve-object-def ns name))))

(defn- retrieve-docstring
  "Helper method for behavior `editor.doc.show!` that returns the docstring for a matching
  object or behavior. If `:doc` is not found, then `:desc` is used. Otherwise `nil`."
  [ns name]
  (let [o (retrieve ns name)]
    (or (:doc o) (:desc o))))

(defn- retrieve-labels
  [ns name]
  (let [o (retrieve ns name)]
    (string/join ", " (map #(get %1 :label) (:params o [])))))

(behavior ::editor.doc.show!
          :triggers #{:editor.doc.show!}
          :reaction (fn [editor doc]
                      (when (not= (:name doc) "")
                        ;; If :file and :doc are nil then this is likely a behavior or object.
                        ;; Check if a match exists and splice the resulting :doc into the doc argument.
                        (let [doc (if (and (nil? (:file doc)) (nil? (:doc doc)))
                                    (merge doc {:doc (retrieve-docstring (:ns doc) (:name doc))
                                                :labels (retrieve-labels (:ns doc) (:name doc))})
                                    doc)]
                          (inline-doc editor (doc-ui doc) {} (:loc doc))))))

(defn- search-item [i item]
  [:li {:replicant/key i}
   [:h2 (:name item)]
   [:h3 (:ns item)]
   [:pre (str (:args item))]
   [:pre (:doc item)]])

(defn- type-list [this]
  (let [types (object/raise-reduce this :types+ [])
        cur (or (:cur @this) (first types))]
    [:div.types
     [:span (:label cur)]
     [:ul.types
      (for [i types]
        [:li {:replicant/key (:label i)
              :on {:click (fn [] (object/raise this :set-item! i))}}
         (:label i)])]]))

(defn- no-client-ui []
  [:div.no-client
   [:p "There's no client for us to use to search for these kinds of docs. "]
   [:p [:button {:on {:click (fn [] (cmd/exec! :show-add-connection))}} "Connect"] " to one."]])

(defn try-trigger [this cur v]
  (let [cs (clients/discover* (:trigger cur))]
    (if-not (seq cs)
      (object/raise this :no-client)
      (do
        (object/merge! this {:no-client? false})
        (notifos/set-msg! "Searching for docs...")
        (doseq [c cs]
          (notifos/working)
          (clients/send c (:trigger cur) {:search v} :only this))))))

(defn ->val [this]
  (dom/val (dom/$ :input.search (object/->content this))))

(defn grouped-items
  "`results` with the ones whose name contains `v` first.

  Was two document fragments, one prepended to the list and one appended, so
  the order on screen came from the order the batches arrived in. It is a
  function of the whole list now — every exact match above every other, however
  many replies it took — which is what the two fragments were reaching for."
  [results v]
  (let [exact? #(str-contains? (str (:name %)) (str v))]
    (concat (filter exact? results) (remove exact? results))))

(behavior ::set-item
          :triggers #{:set-item!}
          :reaction (fn [this i]
                      ;; No `dom/replace-with` on the type list: `:cur` is what
                      ;; it drew from, so setting it redraws.
                      (object/merge! this {:cur i})
                      (object/raise this :clear!)
                      (object/raise this :focus!)))

(behavior ::clear!
          :triggers #{:clear!}
          :reaction (fn [this]
                      (object/merge! this {:results []})))

(behavior ::no-client
          :triggers #{:no-client}
          :reaction (fn [this]
                      (object/merge! this {:no-client? true})))

(behavior ::cur-from-last-editor
          :triggers #{:show}
          :reaction (fn [this]
                      (when-let [ed (pool/last-active)]
                        (let [ed-type (-> @ed :info :type-name)]
                          (when-not (-> @this :cur :file-types (get ed-type))
                            (when-let [neue (first (filter #(-> % :file-types (get ed-type))
                                                           (object/raise-reduce this :types+ [])))]
                              (object/raise this :set-item! neue)
                              ))))))

(behavior ::sidebar.doc.search.exec
          :triggers #{:sidebar.doc.search.exec}
          :reaction (fn [this]
                      (let [v (->val this)
                            trigger (-> @this :cur :trigger)]
                        (object/raise this :clear!)
                        ;; Kept, so the view can group by it. It used to be read
                        ;; back out of the input every time results arrived.
                        (object/merge! this {:query v})
                        (when-not (empty? v)
                          (if (fn? trigger)
                            (trigger v)
                            (try-trigger this (:cur @this) v))))))

(behavior ::doc.search.results
          :triggers #{:doc.search.results}
          :reaction (fn [this results]
                      ;; A vector rather than the set this was, because the
                      ;; order on screen is the order replies arrived in and a
                      ;; set has none. The dedup a set was doing is here
                      ;; explicitly — several clients answer one search.
                      (let [seen (set (:results @this))
                            fresh (remove seen results)]
                        (object/merge! this {:results (into (vec (:results @this)) fresh)})
                        (notifos/done-working
                         (str "Found " (count (:results @this)) " doc results.")))))

(behavior ::focus-on-show
          :triggers #{:show}
          :reaction (fn [this]
                      (object/raise this :focus!)))

(behavior ::focus!
          :triggers #{:focus!}
          :reaction (fn [this]
                      (if-not (:active @this)
                        (let [input (dom/$ :input (object/->content this))]
                          (dom/focus input)
                          (.select input))
                        (object/raise (-> @this :active :options) :focus!))))

(defn- doc-search-ui [this]
  (let [{:keys [results query no-client?]} @this]
    (list
     [:input.search {:type "text" :placeholder "search docs"
                     :on {:focus (fn [] (ctx/in! :sidebar.doc.search.input this))
                          :blur (fn [] (ctx/out! :sidebar.doc.search.input))}}]
     (type-list this)
     (when no-client? (no-client-ui))
     [:ul.results
      (map-indexed search-item (grouped-items results query))])))

(object/object* ::sidebar.doc.search
                :tags #{:sidebar.docs.search}
                :label "Doc search"
                :results []
                :init (fn [this]
                        (object/merge! this {:cur (first (object/raise-reduce this :types+ []))})
                        (ui/node this [:div.docs-search.filter-list] doc-search-ui)))

(def doc-search nil)

(cmd/command {:command :docs.search.exec
              :desc "Docs: Execute sidebar search"
              :hidden true
              :exec (fn []
                      (when doc-search
                        (object/raise doc-search :sidebar.doc.search.exec))
                      )})

(cmd/command {:command :docs.search.show
              :desc "Docs: Search language docs"
              :exec (fn [force?]
                      (when doc-search
                        (object/raise sidebar/rightbar :toggle doc-search {:force? force?}))
                      )})

(cmd/command {:command :docs.search.hide
              :desc "Docs: hide language docs"
              :hidden true
              :exec (fn [force?]
                      (when doc-search
                        (object/raise sidebar/rightbar :close!))
                      )})

(behavior ::init-doc-search
          :triggers #{:init}
          :reaction (fn [this]
                      (set! doc-search (object/create ::sidebar.doc.search))
                      (sidebar/add-item sidebar/rightbar doc-search)
                      ))
