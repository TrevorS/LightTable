(ns lt.util.load.compiled
  "Whether compiled ClojureScript belongs to the build that is running.

  A ClojureScript plugin is compiled as a module of the editor's own bundle,
  and shadow hoists every literal keyword and symbol in that bundle into a
  top-level var whose name carries both an index and the value:
  `cljs$cst$110$listeners`. The numbering is per compilation, so 110 is a
  different keyword in the next build — in the one before this was written it
  was `cljs$cst$110$tags`.

  A module from an earlier build therefore names constants this one does not
  have, and fails at eval with a ReferenceError naming one of them. That error
  says nothing about the cause, and it has cost three debugging sessions: the
  same mismatch turned up in `deploy/plugins`, then in the packaged plugins,
  then in the User plugin.

  Reading it out of the code beforehand costs about a millisecond per plugin
  and turns the ReferenceError into a sentence. Nothing here touches the
  window, so the deciding is testable and only the lookup is not.")

(def ^:private referenced
  "Every hoisted constant the code names."
  (js/RegExp. "cljs\\$cst\\$[A-Za-z0-9_$]+" "g"))

(def ^:private declared
  "The ones the code declares itself, from its leading
  `var cljs$cst$1$a = …, cljs$cst$2$b = …`. A module carries the constants used
  only by its own code, and those are not in the bundle and never will be."
  (js/RegExp. "[,;\\s(](cljs\\$cst\\$[A-Za-z0-9_$]+)\\s*=" "g"))

(defn mismatched-constants
  "The constants `code` uses that are neither its own nor `defined?`.

  Empty means the code can run against this build — including for JavaScript
  that was never ClojureScript, which names none of these at all. Anything
  else means it was compiled against a different one, and the first name is
  what the ReferenceError would have said."
  [code defined?]
  (let [own (into #{} (map #(aget % 1)) (es6-iterator-seq (.matchAll code declared)))]
    (into []
          (comp (distinct) (remove own) (remove defined?))
          (array-seq (or (.match code referenced) #js [])))))
