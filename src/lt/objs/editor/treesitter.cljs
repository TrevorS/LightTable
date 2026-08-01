(ns lt.objs.editor.treesitter
  "Tree-sitter syntax highlighting, wired into editors by tag.

  The parsing is `src-window/treesitter.ts` and the drawing is
  `cm6-treesitter.ts`, which turns its per-line spans into decorations. This is
  the part that knows about Light Table: which grammar goes with which editor,
  where the `.wasm` files are, and when to reparse.

  **Why bother, when 130 stream modes already work.** A mode is a per-line
  state machine. It can tell you `foo` is an identifier and cannot tell you
  whether it is a parameter, a call, a type or a local, so a theme cannot
  colour them differently however much it would like to. Measured on a
  realistic TypeScript file, CodeMirror's javascript mode emits seven token
  types and the default theme renders them in six colours.

  A tree-sitter highlight query emits a named capture per node, from a
  vocabulary — `function`, `variable.parameter`, `type.builtin`,
  `keyword.control` — that Helix, Neovim and Zed themes are already written
  against. That is the real win, and it is not mainly about having more
  colours: it means a theme is a stylesheet rather than a port, and the
  selectors are a hierarchy instead of a flat list.

  **What it costs.** 4.8ms to parse a file cold, 0.2ms to reparse after one
  keystroke, both measured. Grammars are ~400KB of WebAssembly each and load on
  first use, not at startup — opening a Python file should not pay for Rust.

  Turn it off with the `::use-treesitter` behavior and the language's own
  colouring takes over again. Only the colouring is replaced: the language stays
  configured, and it is what decides indentation, comment syntax, bracket
  matching and folding."
  (:require [clojure.string :as string]
            [lt.object :as object]
            [lt.objs.editor :as editor]
            [lt.objs.notifos :as notifos]
            [lt.util.bridge :as bridge]
            [lt.window.modules :as modules])
  (:require-macros [lt.macros :refer [behavior]]))

(def ^:private ts modules/treesitter)

(defn- core-path
  "A path inside the installed `deploy/core`, which is what `app-dir` is.

  Relative to `deploy/core` rather than to `node_modules`, because not every
  grammar comes from npm: the ones nobody publishes a prebuilt `.wasm` for are
  built from source and vendored under `grammars/`. See that directory's
  README for how."
  [& parts]
  (apply str bridge/app-dir "/" parts))

(defn- read-bytes
  "The byte reader `src-window/treesitter.ts` needs. A `.wasm` read as UTF-8 is
  corrupt, which is why `readFileBytesSync` exists at all."
  [path]
  (.readFileBytesSync bridge/files path))

(def grammars
  "Editor tag to grammar, for the languages whose grammars Light Table bundles.

  `:wasm` and `:query` are paths under `deploy/core/node_modules`, read on
  first use. A language is here because its npm package ships both a prebuilt
  `.wasm` and a `queries/highlights.scm` — no compilation step, no download.

  Adding one is two lines plus the dependency. Nothing about this map is
  privileged: a plugin can `swap!` into it, which is how a language plugin
  should bring its own grammar rather than waiting for an editor release."
  (atom
   (let [npm  (fn [pkg file] (str "node_modules/" pkg "/" file))
         js   (npm "tree-sitter-javascript" "queries/highlights.scm")
         jsx  (npm "tree-sitter-javascript" "queries/highlights-jsx.scm")
         tsq  (npm "tree-sitter-typescript" "queries/highlights.scm")
         cq   (npm "tree-sitter-c" "queries/highlights.scm")
         ;; A grammar whose npm package ships both a prebuilt .wasm and a
         ;; highlights.scm needs nothing but these two lines.
         simple (fn [pkg]
                  {:wasm (npm pkg (str pkg ".wasm"))
                   :queries [(npm pkg "queries/highlights.scm")]})
         clojure {:wasm "grammars/tree-sitter-clojure.wasm"
                  :queries ["grammars/queries/clojure-highlights.scm"]}]
     {:editor.javascript {:wasm (npm "tree-sitter-javascript" "tree-sitter-javascript.wasm")
                          :queries [js]}
      :editor.jsx        {:wasm (npm "tree-sitter-javascript" "tree-sitter-javascript.wasm")
                          :queries [js jsx]}
      ;; Two queries, in this order, and the order is the point. TypeScript's
      ;; own file is a 35-line supplement — types, parameters, its extra
      ;; keywords — written to sit on top of JavaScript's rather than replace
      ;; it. Later captures win, so `(type_identifier) @type` beats
      ;; JavaScript's blanket `(identifier) @variable` for the same node, which
      ;; is what the query author meant by writing it second.
      :editor.typescript {:wasm (npm "tree-sitter-typescript" "tree-sitter-typescript.wasm")
                          :queries [js tsq]}
      :editor.tsx        {:wasm (npm "tree-sitter-typescript" "tree-sitter-tsx.wasm")
                          :queries [js jsx tsq]}

      ;; Nobody publishes a prebuilt Clojure grammar, and no package ships
      ;; Clojure queries, so both are ours — see deploy/core/grammars/README.md.
      ;; Worth the effort for the language this editor is written in: the
      ;; grammar separates a defn's name from its docstring from its parameter
      ;; vector, and an interop call from a local, none of which the CodeMirror
      ;; mode can see.
      ;; Every tag a Clojure file might carry. `.clj` and friends are tagged by
      ;; the Clojure plugin rather than by the built-in file-type table, which
      ;; only knows `.edn`, so listing one tag would cover the wrong half.
      :editor.clj        clojure
      :editor.cljs       clojure
      :editor.cljc       clojure
      :editor.cljx       clojure
      :editor.edn        clojure
      :editor.clojure    clojure
      :editor.behaviors  clojure
      :editor.keymap     clojure

      :editor.python     (simple "tree-sitter-python")
      :editor.rust       (simple "tree-sitter-rust")
      :editor.go         (simple "tree-sitter-go")
      :editor.json       (simple "tree-sitter-json")
      :editor.css        (simple "tree-sitter-css")
      :editor.html       (simple "tree-sitter-html")
      :editor.bash       (simple "tree-sitter-bash")
      :editor.c          (simple "tree-sitter-c")
      ;; C++ is C plus its own rules, the same shape as TypeScript over
      ;; JavaScript.
      :editor.cpp        {:wasm (npm "tree-sitter-cpp" "tree-sitter-cpp.wasm")
                          :queries [cq (npm "tree-sitter-cpp" "queries/highlights.scm")]}
      :editor.java       (simple "tree-sitter-java")
      :editor.ruby       (simple "tree-sitter-ruby")
      :editor.php        (simple "tree-sitter-php")
      :editor.yaml       {:wasm (npm "@tree-sitter-grammars/tree-sitter-yaml" "tree-sitter-yaml.wasm")
                          :queries [(npm "@tree-sitter-grammars/tree-sitter-yaml" "queries/highlights.scm")]}
      :editor.toml       {:wasm (npm "@tree-sitter-grammars/tree-sitter-toml" "tree-sitter-toml.wasm")
                          :queries [(npm "@tree-sitter-grammars/tree-sitter-toml" "queries/highlights.scm")]}})))

(defn grammar-for
  "The grammar for an editor's tags, or nil. First match wins, which only
  matters for an editor carrying two language tags."
  [tags]
  (some #(get @grammars %) tags))

(defn grammar-name
  "Where a grammar came from, for reporting: the npm package, or `grammars` for
  one built and vendored here."
  [grammar]
  (when grammar
    (let [parts (string/split (:wasm grammar) #"/")]
      (if (= "node_modules" (first parts))
        (string/join "/" (rest (butlast parts)))
        (first parts)))))

(defn- read-query
  "One query file, or nothing if it is missing. A grammar that ships fewer
  query files than expected should highlight less, not fail to load."
  [relative]
  (let [path (core-path relative)]
    (when (.existsSync bridge/files path)
      (.readFileSync bridge/files path))))

(defn- spec
  "A grammar's wasm path and its query text, concatenated in declaration order.

  Concatenation is how tree-sitter query files compose: a language's own file
  often supplements a base language's rather than standing alone. Order carries
  meaning, because later captures win."
  [{:keys [wasm queries]}]
  #js {:wasm (core-path wasm)
       :query (->> queries (map read-query) (remove nil?) (string/join "\n"))})

(defonce ^:private runtime
  ;; One runtime for the window, initialised on first use rather than at
  ;; startup: 200KB of WebAssembly nobody needs until a supported file opens.
  (delay (.initRuntime ts read-bytes (core-path "node_modules/web-tree-sitter/web-tree-sitter.wasm"))))

(defn- highlighter-for [grammar]
  (.then @runtime (fn [_] (.highlighterFor ts read-bytes (spec grammar)))))

(defn- install-highlighter!
  "Draw `ed` from `hl`, or from its own language again when `hl` is nil.

  Called again after every reparse, and the repetition is the point: the
  highlighter is the same object each time and the spans behind it are not, so
  the effect says outright that what it is drawing from has changed. See
  src-window/cm6-treesitter.ts, which turns those spans into decorations."
  [ed ^js hl]
  (.setHighlighter ^js (editor/->cm-ed ed) hl))

;;*********************************************************
;; Behaviors
;;*********************************************************

(behavior ::use-treesitter
          ;; Both triggers, because an editor does not know its language when it
          ;; is created: `lt.objs.opener` adds the file type's tags afterwards,
          ;; so on `:object.instant` the tag set is still just `#{:editor ...}`
          ;; and there is nothing to look a grammar up by.
          :triggers #{:object.instant :lt.object/tags-added}
          :desc "Editor: Highlight with tree-sitter when a grammar is bundled"
          :doc "Replaces the CodeMirror mode's colouring with a tree-sitter
                highlight query, which distinguishes far more than a per-line
                tokenizer can — a call from a variable, a type from a value, a
                parameter from a local.

                The mime still decides indentation, commenting, bracket
                matching and folding; only the colouring changes. Editors whose
                language has no bundled grammar are untouched."
          :type :user
          :reaction (fn [this & _]
                      (when-let [grammar (grammar-for (:tags @this))]
                        ;; Tags can be added more than once, and this fires on
                        ;; each. Installing twice would leak a tree and reset
                        ;; the mode under the user for no reason.
                        (when-not (= grammar (::grammar @this))
                          (object/merge! this {::grammar grammar})
                          (-> (highlighter-for grammar)
                              (.then (fn [hl]
                                       (.parse hl (editor/->val this))
                                       (object/merge! this {::highlighter hl})
                                       ;; Swapped only once there is something
                                       ;; to show, so the editor is never
                                       ;; briefly blank.
                                       (install-highlighter! this hl)))
                              (.catch (fn [e]
                                        ;; A missing or broken grammar must cost
                                        ;; its own language and nothing else:
                                        ;; the CodeMirror mode is still there.
                                        (object/merge! this {::grammar nil})
                                        (js/lt.objs.console.error
                                         (str "tree-sitter highlighting unavailable: " e)))))))))

(behavior ::reparse-on-change
          :triggers #{:change}
          :desc "Editor: Reparse for tree-sitter highlighting"
          :doc "Incremental: the parser is told what changed, so a keystroke
                costs about 0.2ms rather than a whole reparse."
          :reaction (fn [this _]
                      (when-let [^js hl (::highlighter @this)]
                        ;; The edit is described from the document rather than
                        ;; from the change object: CodeMirror reports a change
                        ;; in lines and columns, tree-sitter wants byte offsets
                        ;; too, and deriving both from the new text is simpler
                        ;; than translating and cheap at this size.
                        (.parse hl (editor/->val this))
                        ;; Reinstalling is how CodeMirror is told the
                        ;; highlighting is stale. Without it, it keeps the
                        ;; tokens it cached for lines whose text did not change
                        ;; — which is most of them, and most of what a reparse
                        ;; changes.
                        (install-highlighter! this hl))))

(behavior ::dispose-highlighter
          :triggers #{:destroy :close}
          :desc "Editor: Release the tree-sitter tree"
          :reaction (fn [this]
                      (when-let [^js hl (::highlighter @this)]
                        ;; Handed back before it is freed, so an editor that
                        ;; outlives this is drawn by its own mode rather than
                        ;; from a tree that has been deleted.
                        (install-highlighter! this nil)
                        (.dispose hl)
                        (object/merge! this {::highlighter nil}))))

;;*********************************************************
;; The parse tree, for things other than colour
;;*********************************************************

(defn top-level-forms
  "The top-level forms of `ed`, in order, as `{:start {:line :ch} :end {…}}`.

  Nil when this editor has no grammar or has not parsed yet, which is a real
  answer and not a failure — a caller wanting to evaluate form by form should
  fall back to the whole region rather than guess where the forms are.

  Highlighting is what a parse tree gets used for first, and it is not what a
  parse tree is *for*. This is the second use: it is how a result can appear
  beside each form rather than one result for a whole file, which is the thing
  Light Table exists to do. That used to be answered by a language's own nREPL
  middleware, so it worked for one language, only with a REPL attached, and
  only after the round trip. Here it is a property of the buffer."
  [ed]
  (when-let [^js hl (::highlighter @ed)]
    (when-let [forms (.topLevelForms hl)]
      (vec (for [^js f forms]
             {:start {:line (.-startLine f) :ch (.-startCh f)}
              :end {:line (.-endLine f) :ch (.-endCh f)}
              :type (.-type f)})))))

(defn form-at
  "The top-level form of `ed` containing `loc`, or nil.

  `loc` is `{:line :ch}`. A cursor resting after the last form — on a blank
  line at the end of a file — is inside none of them, and nil says so."
  [ed loc]
  (let [line (:line loc)]
    (first (filter (fn [{:keys [start end]}]
                     (and (<= (:line start) line) (<= line (:line end))))
                   (top-level-forms ed)))))

;;*********************************************************
;; Commands
;;*********************************************************

(defn line-classes
  "Every token class tree-sitter gives `ed`, sorted and without repeats.

  Read from the span table rather than from what was drawn, because
  neither can be read without a window that paints: CodeMirror 5 answers
  `getLineTokens` from a mode it only runs when asked, and CodeMirror 6
  decorates the lines it can see. This is the thing they are both drawing
  from — `runsForLine` and `tokenClasses` in src-window/treesitter.ts are the
  functions each of them calls."
  [ed]
  (when-let [^js hl (::highlighter @ed)]
    (->> (range (editor/line-count ed))
         (mapcat (fn [n]
                   (let [length (count (or (editor/line ed n) ""))]
                     (map (fn [^js run] (.-style run))
                          (.runsForLine ts (.spansForLine hl n) length)))))
         (mapcat #(string/split (.tokenClasses ts %) #"\s+"))
         (remove string/blank?)
         distinct
         sort
         vec)))

(defn report
  "What tree-sitter is doing for the active editor, for the command below and
  for the smoke test."
  [ed]
  (let [grammar (grammar-for (:tags @ed))
        ^js hl (::highlighter @ed)]
    {:tags (vec (:tags @ed))
     :grammar (grammar-name grammar)
     :active (boolean hl)
     :generation (when hl (.-generation hl))}))
