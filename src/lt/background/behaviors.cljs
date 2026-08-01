(ns lt.background.behaviors
  "Parses behavior files off the main thread. These are read on startup and
  again whenever one changes, and can be large enough to be felt.

  The parser is bundled — see src-worker/behaviors-parser.ts, compiled onto the
  source path at lt/background/ so the relative require below resolves against
  the same classpath directory this namespace is in. It brings its own scanner:
  that used to be CodeMirror 5's `StringStream`, read out of the installed
  package, which was a dependency on an editor to walk a string."
  (:require ["./behaviors-parser.js" :as parser]
            [lt.background.runtime :as bg]))

(defn parse-flat
  "Parse `contents` of a behavior file and send the result back."
  [obj-id contents]
  (bg/send! obj-id :parsed (parser/parseFlat (parser/stringStream contents)) :raw))
