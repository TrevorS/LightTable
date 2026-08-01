(ns lt.background.auto-complete
  "Extracts completion candidates from an editor's contents. Runs off the main
  thread because it re-tokenizes the whole buffer on every request."
  (:require [lt.background.runtime :as bg]))

(defn- tokenize
  "Every distinct run of `pattern` characters in `text`.

  A hint pattern is a character class, so one regular expression finds every
  run — the same scan `lt.plugins.auto-complete/tokens` does on the other side
  of the thread, and for the same reason: this used to drive CodeMirror 5's
  `StringStream` a character at a time, and that came out of the editor."
  [text pattern]
  (let [re (js/RegExp. (str "(?:" pattern ")+") "g")
        seen (js-obj)]
    (loop []
      (when-let [m (.exec re text)]
        (let [s (aget m 0)]
          (if (empty? s)
            (set! (.-lastIndex re) (inc (.-lastIndex re)))
            (aset seen s true)))
        (recur)))
    (into-array (map #(js-obj "completion" %) (js/Object.keys seen)))))

(defn hint-tokens
  "Send back the completion candidates found in `:string`."
  [obj-id {:keys [string pattern]}]
  (bg/send! obj-id :hint-tokens (tokenize string pattern) :raw))
