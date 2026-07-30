(ns lt.background.behaviors
  "Parses behavior files off the main thread. These are read on startup and
  again whenever one changes, and can be large enough to be felt.

  The parser is bundled — see src-worker/behaviors-parser.ts, compiled onto the
  source path at lt/background/ so the relative require below resolves against
  the same classpath directory this namespace is in. CodeMirror's StringStream
  is still read off the install directory, because it is an npm package rather
  than Light Table's own code."
  (:require ["./behaviors-parser.js" :as parser]
            [lt.background.runtime :as bg]))

(defn parse-flat
  "Parse `contents` of a behavior file and send the result back."
  [obj-id contents]
  (let [StringStream (.-StringStream (bg/require-lt "core/node_modules/codemirror/addon/runmode/runmode.node.js"))]
    (bg/send! obj-id :parsed (parser/parseFlat (StringStream. contents)) :raw)))
