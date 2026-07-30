(ns lt.background.search
  "Project-wide search and replace, run off the main thread so that walking a
  large workspace does not block the editor.

  The searching itself is [[lt.background.file-search]], which knows nothing
  about the worker and can therefore be tested. This is only the wiring: turn
  the message from the renderer into a call, and turn each match back into a
  message."
  (:require [lt.background.file-search :as file-search]
            [lt.background.runtime :as bg]))

(defn search
  "Search `paths` for `:search`, sending each matching file back as it is found
  and a summary once finished. With a `:replacement` the matches are rewritten
  in place."
  [obj-id {:keys [search exclude replacement paths]}]
  (let [summary (file-search/search
                 {:paths paths
                  :pattern search
                  :exclude (when exclude (js/RegExp. exclude))
                  :replacement replacement
                  ;; :raw because the searcher reads .file and .results off
                  ;; these directly, one message per matching file so results
                  ;; appear while the walk is still going.
                  :on-file (fn [res] (bg/send! obj-id :result (clj->js res) :raw))})]
    (bg/send! obj-id :done-searching {:total (:total-files summary)
                                      :time (:time summary)
                                      :replace? (boolean replacement)})))
