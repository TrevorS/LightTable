(ns lt.background.navigate
  "Collects the workspace's files for the navigate-to-file bar. Walking a large
  tree is slow enough that doing it on the main thread stalls typing."
  (:require [lt.background.runtime :as bg]))

(defn- folder-files
  "Every file under `folder` matching `pattern`, up to `limit`, as pairs of full
  path and path relative to the folder's parent."
  [fpath walkdir pattern limit folder]
  (let [root-length (inc (count (.dirname fpath folder)))
        ^js walked (walkdir folder (js-obj "filter" (js/RegExp. pattern) "limit" limit))]
    (.map (.-paths walked)
          #(js-obj "full" % "rel" (subs % root-length)))))

(defn workspace-files
  "Send back every file in the workspace, both those under its folders and those
  added individually."
  [obj-id {:keys [lim pattern ws]}]
  (let [fpath (js/require "path")
        walkdir (bg/require-lt "core/lighttable/background/walkdir2.js")
        from-folders (reduce (fn [acc folder]
                               (.concat acc (folder-files fpath walkdir pattern lim folder)))
                             (array)
                             (:folders ws))
        loose-files (.map (to-array (:files ws))
                          #(js-obj "full" % "rel" (.basename fpath %)))]
    (bg/send! obj-id :workspace-files (.concat from-folders loose-files) :raw)))
