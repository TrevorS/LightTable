(ns lt.ui.stories.band
  "What the six band components are, as data.

  A band is the thing the product is for: a value on the line that produced it.
  `band/result` is the one the catalogue calls the thesis component, and the
  reason its states are worth having all six of is that the interesting ones
  are the unhappy ones — stale, lost, computed against edits you have not
  applied."
  (:require [lt.ui.band :as band]
            [lt.ui.chrome :as chrome]
            [lt.ui.story :as story]))

(story/of ::band/result
  {:doc "The thesis component. A value, on the line that produced it, in every
         state it has."
   :props [[":line" "number" "zero-based, as every address in lt.state is"]
           [":value" "any" ""]
           [":mime" "string" "handed to value-content"]
           [":status" "one of the eight" ""]
           [":stale?" "boolean" "last known value, marked"]
           [":against-unapplied" "number?" "computed against edits you have not applied"]]
   :usage "every editor pane — this is the one the product is for"
   :width :wide
   :note "queued shows no band at all — the gutter dot carries it"
   :states (array-map
            :finished {:line 6 :status :finished :value "({:count 2} {:count 1})"}
            :executing {:line 12 :status :executing :value "({:count 2})" :note "previous"}
            :connecting {:line 13 :status :connecting :note "nREPL 51423"}
            :shutting-down {:line 14 :status :shutting-down :stale? true
                            :value "({:count 2} {:count 1})"}
            :lost {:line 15 :status :lost :stale? true :value "({:count 2})"}
            :against-unapplied {:line 16 :status :finished :value "[{:start 0}]"
                                :against-unapplied 2})})

(story/of ::band/watch
  {:doc "A value under observation. Teal, because it re-reads itself."
   :props [[":line" "number" ""]
           [":expression" "string" "the path being watched"]
           [":value" "any" "when there is only one"]
           [":history" "any[]" "when it is a recurrence"]
           [":reads" "number" ""]]
   :usage "the watch bands in a live buffer"
   :width :wide
   :note "a walked path in the inspector can be promoted to this — inspection and
          observation are one gesture apart"
   :states (array-map
            :recurring {:line 19 :expression "i " :history [0 1 2 3 4 5 6 7] :reads 8}
            :single {:line 24 :expression "(count tabs) " :value "3" :reads 1})})

(story/of ::band/evidence
  {:doc "The whole argument of the design: what the value was, and what it becomes."
   :props [[":rows" "[{:label :expr :value :tone}]" "before, after, types, callers"]
           [":ran-at" "number" "an age in ms — age is shown, not hidden"]
           [":client" "string" "which connection produced it"]
           [":before" "/ :after" "the two-row shorthand"]]
   :usage "band/proposed-edit · view/multibuffer"
   :width :wide
   :note "three claims, not two — what it returned, what it will return, and what the
          type checker says. A component that held only two would have chosen for you."
   :states (array-map
            :three-claims
            {:ran-at 2000 :client "tsserver and node"
             :rows [{:label "before" :expr "walk(\"src-worker\")"
                     :value "TypeError" :tone :before}
                    {:label "after" :expr "walk(\"src-worker\")"
                     :value "(\"walkdir.ts\")" :tone :after}
                    {:label "types" :expr "walkdir.ts"
                     :value "1 error → clean" :tone :after}]}
            :two-row
            {:ran-at 400 :client "node"
             :before "walk(\"src-worker\")" :after "(\"walkdir.ts\")"})})

(story/of ::band/proposed-edit
  {:doc "Struck original, tinted replacement, both on the same code column."
   :props [[":line" "number" ""]
           [":before" "string" ""]
           [":after" "string" ""]
           ["body" "children" "evidence, attached below"]]
   :usage "view/multibuffer"
   :width :wide
   :note "the block is inset by exactly its own padding so the code lands on the
          gutter column"
   :states (array-map
            :bare {:line 14
                   :before "const e = fs.readdir(dir)"
                   :after "const e = fsp.readdir(dir)"}
            ;; Hiccup, because the body is the point of the second state: an
            ;; edit with its evidence under it is what the multibuffer draws.
            :with-evidence
            {:hiccup [::band/proposed-edit
                      {:line 14
                       :before "const e = fs.readdir(dir)"
                       :after "const e = fsp.readdir(dir)"}
                      [::band/evidence
                       {:ran-at 2000 :client "node"
                        :before "walk(\"src-worker\")" :after "(\"walkdir.ts\")"}]]})})

(story/of ::band/conflict
  {:doc "You edited a line a run had already read. Routine, not an error."
   :props [[":line" "number" ""]
           [":yours" "string" "the line as you left it"]
           [":theirs" "string?" "what the run proposed for it"]
           [":age" "number" "how long ago you typed, in ms"]
           [":note" "string" "why the two are not the same change"]]
   :usage "view/multibuffer"
   :width :wide
   :states (array-map
            :with-actions
            {:hiccup [::band/conflict
                      {:line 44
                       :yours "  (score nm q)"
                       :theirs "  (score nm q {:as :range})"
                       :age 40000
                       :note "Yours changes the threshold, the proposal changes the return type — not the same change."}
                      [::chrome/action-cluster {:tone :warning}
                       [::chrome/action {:weight :primary} "Show both evaluated"]
                       [::chrome/action {:weight :secondary} "Re-run against mine"]
                       [::chrome/action {:weight :tertiary} "Keep mine"]]]}
            :no-proposal
            {:line 44 :yours "  (score nm q)" :age 4000
             :note "The run has not proposed anything for this line yet."})})

(story/of ::band/diagnostic
  {:doc "An LSP diagnostic, in the buffer, with the reason beside the line."
   :props [[":line" "number" ""]
           [":severity" ":error | :warning | :info" "error by default"]
           [":code" "string" "ts2769, clj-kondo …"]
           [":message" "string" ""]
           [":fix" "node?" "when the server offers one"]]
   :usage "every editor pane with a language server"
   :width :wide
   :states (array-map
            :error-with-fix
            {:line 27 :code "ts2769"
             :message "No overload matches this call — the options form is only on fs/promises."
             :fix [::chrome/action-cluster {}
                   [::chrome/action {:weight :secondary} "Import fs/promises"]]}
            :warning {:line 31 :severity :warning :code "clj-kondo"
                      :message "unused binding: opts"}
            :info {:line 33 :severity :info :code "clj-kondo"
                   :message "Use `seq` rather than `(not (empty? …))`."})})
