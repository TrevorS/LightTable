(ns lt.ui.stories.manifest
  "What stories exist, as JSON on stdout, for the two scripts that ask.

  A node entry point rather than reading the browser bundle, because the
  browser bundle is a browser bundle: it exists to be imported by Vite, and
  making it also run under node would mean either a DOM shim or a registry that
  cannot mention `replicant.dom`. Neither is worth it when the registry is
  plain data and a second entry point is a few lines.

  Requiring the story namespaces is what fills the registry — `story/of` is
  called at namespace load — so this file is also the list of what exists.

  Two questions, two flags. Without one, every story, which is what
  `script/gen-stories.mts` turns into files. With `--aliases`, every component
  and how many states it has, which is what `script/check-stories.mts` reads to
  find the ones nobody has described."
  (:require [cljs.core :as core]
            [lt.ui.stories.band]
            [lt.ui.stories.chrome]
            [lt.ui.stories.field]
            [lt.ui.stories.host]
            [lt.ui.stories.pane]
            [lt.ui.stories.row]
            [lt.ui.story :as story]
            [replicant.alias :as alias]))

(defn- aliases
  "Every alias, described or not, with what is known about it.

  The union of two registries, and it has to be the union. Replicant's holds
  what has been *defined*, which is what a completeness check must be complete
  against — a component nobody wrote a `story/of` for has to show up as zero
  rather than not at all. Ours holds what has been *described*, which for
  `lt.ui.pane/pane` is a description and a reason it has no states; that
  namespace is deliberately not required here, so Replicant has never heard of
  it."
  []
  (let [described @story/registry
        defined (set (keys (alias/get-registered-aliases)))]
    (vec (for [a (sort (into defined (keys described)))
               :let [spec (get described a)]]
           {:alias (str a)
            :states (count (:states spec))
            :excluded (:excluded spec)}))))

(defn ^:export main [& args]
  (core/println
   (.stringify js/JSON
               (clj->js (if (some #{"--aliases"} args)
                          (aliases)
                          (story/manifest))))))
