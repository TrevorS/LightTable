(ns lt.background.behaviors
  "Parses behavior files off the main thread. These are read on startup and
  again whenever one changes, and can be large enough to be felt."
  (:require [lt.background.runtime :as bg]))

(defn parse-flat
  "Parse `contents` of a behavior file and send the result back."
  [obj-id contents]
  (let [StringStream (.-StringStream (bg/require-lt "core/node_modules/codemirror/addon/runmode/runmode.node.js"))
        parse (.-parseFlat (bg/require-lt "core/lighttable/background/behaviorsParser.js"))]
    (bg/send! obj-id :parsed (parse (StringStream. contents)) :raw)))
