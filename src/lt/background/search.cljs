(ns lt.background.search
  "Project-wide search and replace, run off the main thread so that walking a
  large workspace does not block the editor."
  (:require [lt.background.runtime :as bg]))

(defn- ->pattern
  "A search is a plain string unless it is written /like this/, in which case it
  is a regex."
  [search]
  (if-let [[_ pattern] (first (re-seq #"^/(.+)/$" search))]
    (js/RegExp. pattern)
    search))

(defn- case-sensitive?
  "Searches are case insensitive until the user types a capital, which is taken
  as a signal that case was meant."
  [search]
  (boolean (re-seq #"[A-Z]" search)))

(defn search
  "Search `paths` for `:search`, sending each hit back as it is found and a
  summary once finished. With a `:replacement` the matches are rewritten in
  place."
  [obj-id {:keys [search exclude replacement paths] :as opts}]
  (let [replacer (bg/require-lt "core/node_modules/replace")
        result (replacer (clj->js {:regex       (->pattern search)
                                   :exclude     (when exclude (js/RegExp. exclude))
                                   :recursive   true
                                   :ignoreCase  (not (case-sensitive? search))
                                   :replacement replacement
                                   :paths       paths
                                   :result      (fn [r] (bg/send! obj-id :result r :raw))}))]
    (bg/send! obj-id :done-searching {:total    (.-totalFiles result)
                                      :time     (.-time result)
                                      :replace? (boolean replacement)})))
