(ns lt.plugins.auto-complete
  "Provide any auto-complete related functionality"
  (:require [lt.object :as object]
            [lt.objs.keyboard :as keyboard]
            [lt.objs.command :as cmd]
            [lt.util.load :as load]
            [lt.objs.thread :as thread]
            [lt.objs.sidebar.command :as scmd]
            [lt.objs.editor.pool :as pool]
            [lt.objs.editor :as editor]
            [lt.objs.context :as ctx]
            [lt.window.modules :as modules]
            [clojure.string :as string]
            [lt.util.js :refer [wait]]
            [lt.util.dom :as dom])
  (:require-macros [lt.macros :refer [behavior]]))

(def default-pattern #"[\w_$]")

(defn tokens
  "The maximal runs of `pattern` characters in `line`, as `[start end string]`.

  What the CodeMirror 5 `StringStream` loop this replaces was doing: eat while
  the pattern matches and whatever came out is a token. A hint pattern is always
  a character class — every one Light Table ships and every one a behavior
  sets — so a single regular expression finds them all, and a scanner borrowed
  from an editor goes away with the editor."
  [line pattern]
  (let [re (js/RegExp. (str "(?:" (.-source pattern) ")+") "g")]
    (loop [found []]
      (if-let [m (.exec re line)]
        (let [s (aget m 0)]
          ;; A pattern that can match nothing would never move `lastIndex` and
          ;; this would not return. None of ours can; the guard is cheaper than
          ;; the hang.
          (if (empty? s)
            (do (set! (.-lastIndex re) (inc (.-lastIndex re))) (recur found))
            (recur (conj found [(.-index m) (+ (.-index m) (count s)) s]))))
        found))))

(def ^:private patterns
  "What counts as a word, per mode.

  CodeMirror 5 kept this on the mode object and it was put there with
  `extendMode` — an editor of one engine holding a setting that has nothing to
  do with drawing text. It is a map now, so both engines read the same answer
  and a plugin registers one with [[hint-pattern!]]."
  (atom {}))

(defn mode-key
  "A mode name or MIME type, reduced to the one word both name.

  `text/x-clojurescript` and `clojurescript` are the same language; so are
  `text/css` and `css`. The same reduction cm6-modes.ts does, for the same
  reason — what arrives here depends on who named the language."
  [mode]
  (-> (str mode)
      (string/replace #"^text/x-|^application/x-|^text/" "")
      (string/lower-case)))

(defn ed-mode
  "What language an editor is showing.

  The file type first, because the mode option is not always a language: a
  tree-sitter editor's mode is the tree-sitter mode object, named `lt-treesitter`
  for every language it handles. `:info` comes from Light Table's own file-type
  table, which knows Clojure from CSS no matter who is drawing them."
  [ed]
  (or (-> @ed :info :mime) (editor/option ed :mode)))

(defn hint-pattern!
  "Register the hint pattern for a mode name or MIME type."
  [mode pattern]
  (swap! patterns assoc (mode-key mode) pattern))

(defn get-pattern [ed]
  (or (:hint-pattern @ed)
      (@patterns (mode-key (ed-mode ed)))
      ;; A CodeMirror 5 mode extended by a plugin that has not been told about
      ;; the map yet. There is no such thing on CodeMirror 6 — no mode object
      ;; to hang it on — which is why the map exists.
      (when-not (editor/cm6? ed)
        (aget (editor/inner-mode ed) "hint-pattern"))
      default-pattern))

(defn get-token [ed pos]
  (let [line (or (editor/line ed (:line pos)) "")
        ch (:ch pos)]
    (or (first (for [[start end s] (tokens line (get-pattern ed))
                     :when (<= start ch end)]
                 {:start start
                  :end end
                  :line (:line pos)
                  :string s}))
        {:line (:line pos) :start ch :end ch})))

(defn non-token-change? [ed ch]
  (let [pattern (get-pattern ed)
        text (map str (.-text ch))]
    (condp = (.-origin ch)
      "+input" (some #(not (re-seq pattern %)) text)
      "paste" true
      false)))

(def w (thread/job :hint-tokens))

(defn async-hints [this]
  (when @this
    (w this {:string (editor/->val this)
             :pattern (.-source (get-pattern this))})))

(defn text|completion [^js x]
  (or (.-text x) (.-completion x)))

(defn text+completion [^js x]
  (str (.-text x) (.-completion x)))

(defn distinct-completions [hints]
  (let [seen #js {}]
    (filter (fn [^js hint]
              (if (true? (aget seen (.-completion hint)))
                false
                (aset seen (.-completion hint) true)))
            hints)))

(declare hinter)

(defn remove-long-completions [hints]
  (filter (fn [^js h] (< (.-length (.-completion h)) (:hint-limit @hinter))) hints))

(def hinter (-> (scmd/filter-list {:items (fn []
                                            (when-let [cur (pool/last-active)]
                                              (let [token (-> @hinter :starting-token :string)]
                                                (->> (if token
                                                       (remove (fn [^js h] (= token (.-completion h)))
                                                               (object/raise-reduce cur :hints+ [] token))
                                                       (object/raise-reduce cur :hints+ []))
                                                     remove-long-completions
                                                     distinct-completions))))
                                   :key text|completion})
                (object/add-tags [:hinter])))

(defn on-editor-change
  "Keep the open hint list in step with the text being typed under it.

  CodeMirror 5 could hand out a handle to one line and tell you when that line
  changed. CodeMirror 6 has no such thing — a line is not an object there, it is
  a range of a document that has just been replaced — so this listens to the
  editor and the reaction below decides whether the change was on the line it
  cared about, which is the question it was really asking."
  [_ed change]
  (object/raise hinter :line-change nil change))

(behavior ::set-hint-limit
          :triggers #{:object.instant}
          :type :user
          :desc "Auto-complete: Set maximum length of an autocomplete hint"
          :params [{:label "Number"
                    :example 1000}]
          :reaction (fn [this n]
                      (object/merge! this {:hint-limit n})))

(behavior ::textual-hints
          :triggers #{:hints+}
          :reaction (fn [this hints]
                      (concat (::hints @this) hints)))

(behavior ::escape!
          :triggers #{:escape!}
          :reaction (fn [this force?]
                      (let [elem (object/->content this)]
                        (when-let [watching (:watching @this)]
                          (editor/off watching :change on-editor-change))
                        (ctx/out! [:editor.keys.hinting.active])
                        (object/merge! this {:active false
                                             :selected 0
                                             :ed nil
                                             :watching nil
                                             :starting-token nil
                                             :token nil
                                             :search ""})
                        (object/raise this :inactive)
                        (when (dom/parent elem)
                          (dom/remove elem)))))

(behavior ::select
          :triggers #{:select}
          :reaction (fn [this c]
                      (let [token (:token @this)
                            start {:line (:line token)
                                   :ch (:start token)}
                            end {:line (:line token)
                                 :ch (:end token)}]
                        (object/merge! this {:active false})
                        (if (.-select c)
                          ((.-select c) (partial editor/replace (:ed @this) start end) c)
                          (editor/replace (:ed @this) start end (.-completion ^js c)))
                        (object/raise this :escape!))))

(behavior ::select-unknown
          :triggers #{:select-unknown}
          :reaction (fn [this v]
                      (object/raise this :escape!)
                      (keyboard/passthrough)))

(behavior ::line-change
          :triggers #{:line-change}
          :reaction (fn [this l c]
                      (when (:active @hinter)
                        (let [pos (editor/->cursor (:ed @this))
                              token (get-token (:ed @this) pos)]
                          (if (or (non-token-change? (:ed @this) c)
                                  (< (:ch pos) (-> @hinter :starting-token :start)))
                            (object/raise hinter :escape!)
                            (do
                              (object/raise hinter :change! (:string token))
                              (if (= 0 (count (:cur @hinter)))
                                (ctx/out! [:editor.keys.hinting.active :filter-list.input])
                                (when-not (ctx/in? :editor.keys.hinting.active)
                                  (ctx/in! [:filter-list.input] hinter)
                                  (ctx/in! [:editor.keys.hinting.active] (:ed @hinter))))
                              (object/merge! hinter {:token token})))))))

(behavior ::async-hint-tokens
          :triggers #{:hint-tokens}
          :reaction (fn [this tokens]
                      (object/merge! this {::hints tokens})))

(behavior ::intra-buffer-string-hints
          :triggers #{:change}
          :debounce 400
          :reaction (fn [this ch]
                      (when (or (not= (:ed @hinter) this)
                                (not (:active @hinter)))
                        (async-hints this))
                      ))

(defn start-hinting
  ([this] (start-hinting this nil))
  ([this opts]
   (let [pos (editor/->cursor this)
         token (get-token this pos)
         elem (object/->content hinter)]
     (ctx/in! [:editor.keys.hinting.active] this)
     (object/merge! hinter {:token token
                            :starting-token token
                            :ed this
                            :active true})
     (object/raise hinter :change! (:string token))
     (object/raise hinter :active)
     (let [count (count (:cur @hinter))]
       (cond
        (= 0 count) (ctx/out! [:editor.keys.hinting.active :filter-list.input])
        (and (= 1 count)
             (:select-single opts)) (object/raise hinter :select! 0)
        :else (do
                (editor/on this :change on-editor-change)
                (object/merge! hinter {:watching this})
                (dom/append (dom/$ :body) elem)
                (.positionHint modules/cm-hint (editor/->cm-ed this) elem (:start token))))))))

(behavior ::show-hint
          :triggers #{:hint}
          :reaction (fn [this opts]
                      (let [cur (string/trim (editor/get-char this -1))
                            opts (merge {:select-single true} opts)]
                        (cond
                         (and (:active @hinter)
                              (= (:ed @hinter) this)) (object/raise hinter :select!)
                         (and (empty? cur)
                              (not (:force? opts))) (keyboard/passthrough)
                         (:active @hinter) (do (object/raise hinter :escape!) (start-hinting this))
                         :else (start-hinting this opts)))))

(behavior ::remove-on-scroll-inactive
          :triggers #{:scroll :inactive}
          :reaction (fn [this]
                      (when (:active @hinter)
                        (object/raise hinter :escape!))))

(behavior ::remove-on-move-line
          :triggers #{:move}
          :reaction (fn [this c]
                      (when (:active @hinter)
                        ;;HACK: line change events are sent *after* cursor move
                        ;;this means that we need to wait for those to fire and then
                        ;;check if we're out of bounds.
                        (wait 0 (fn []
                                  (let [starting (:starting-token @hinter)
                                        cur (:token @hinter)
                                        cursor (editor/->cursor this)]
                                    (when (and starting
                                               cur
                                               (or (not (<= (:start cur) (:ch cursor) (:end cur)))
                                                   (not= (:line starting) (:line cursor))))
                                      (object/raise hinter :escape!))))))))

(behavior ::auto-show-on-input
          :triggers #{:input}
          :type :user
          :desc "Auto-complete: Show on change"
          :reaction (fn [this _ ch]
                      (when-not (non-token-change? this ch)
                        (when-not (and (:active @hinter)
                                       (= (:ed @hinter) this))
                          (object/raise this :hint {:select-single false})))))

(cmd/command {:command :auto-complete.remove
              :hidden true
              :desc "Editor: Auto complete hide"
              :exec (fn []
                      (when (:active @hinter)
                        (object/raise hinter :escape!))
                      (keyboard/passthrough))})

(cmd/command {:command :auto-complete
              :hidden true
              :desc "Editor: Auto complete"
              :exec (fn []
                      (let [ed (pool/last-active)]
                        (if-not (editor/selection? ed)
                          (object/raise ed :hint)
                          (keyboard/passthrough))))})

(cmd/command {:command :auto-complete.force
              :hidden true
              :desc "Editor: Force auto complete"
              :exec (fn []
                      (let [ed (pool/last-active)]
                        (object/raise ed :hint {:force? true})))})

;;*********************************************************
;; What counts as a word
;;*********************************************************

(def lisp-pattern
  "Clojure's, which is most of the punctuation on the keyboard.

  `foo` at the end is not a typo of anything — it has been in the character
  class since 2013, and since `f`, `o` and `o` are already `\\w` it has never
  meant a thing. Kept because removing it is a change to what completes and this
  is not the change that should make it."
  #"[\w\-\>\:\*\$\?\<\!\+\.\/foo]")

(behavior ::init
          :triggers #{:init}
          :reaction (fn [this]
                      ;; A map rather than `CodeMirror.extendMode`, which put
                      ;; these on a CodeMirror 5 mode object — a thing that does
                      ;; not exist on the other engine and never had much to do
                      ;; with drawing text in the first place.
                      (hint-pattern! "clojure" lisp-pattern)
                      (hint-pattern! "text/x-clojurescript" lisp-pattern)
                      (hint-pattern! "css" #"[\w\.\-\#]")))
