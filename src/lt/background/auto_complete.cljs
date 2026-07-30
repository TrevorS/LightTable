(ns lt.background.auto-complete
  "Extracts completion candidates from an editor's contents. Runs off the main
  thread because it re-tokenizes the whole buffer on every request."
  (:require [lt.background.runtime :as bg]))

(defn- tokenize
  "Every distinct token in `text` matching `pattern`, using CodeMirror's
  tokenizer so that word boundaries match what the editor considers a word."
  [StringStream text pattern]
  (let [stream (StringStream. text)
        pattern (re-pattern pattern)
        seen (js-obj)
        advance! #(set! (.-start stream) (.-pos stream))
        skip-space! (fn []
                      (when (and (.peek stream) (re-seq #"\s" (.peek stream)))
                        (.eatSpace stream)
                        (advance!)))]
    (skip-space!)
    (while (.peek stream)
      (.eatWhile stream pattern)
      (if (seq (.current stream))
        (do
          (aset seen (.current stream) true)
          (advance!))
        (do
          (.next stream)
          (advance!)))
      (skip-space!))
    (into-array (map #(js-obj "completion" %) (js/Object.keys seen)))))

(defn hint-tokens
  "Send back the completion candidates found in `:string`."
  [obj-id {:keys [string pattern]}]
  (let [StringStream (.-StringStream (bg/require-lt "core/node_modules/codemirror/addon/runmode/runmode.node.js"))]
    (bg/send! obj-id :hint-tokens (tokenize StringStream string pattern) :raw)))
