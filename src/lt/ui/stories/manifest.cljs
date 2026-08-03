(ns lt.ui.stories.manifest
  "Print the story registry as JSON, for `script/gen-stories.mts`.

  A node entry point rather than reading the browser bundle, because the
  browser bundle is a browser bundle: it exists to be imported by Vite, and
  making it also run under node would mean either a DOM shim or a registry that
  cannot mention `replicant.dom`. Neither is worth it when the registry is
  plain data and a second entry point is four lines.

  Requiring the story namespaces is what fills the registry — `story/of` is
  called at namespace load — so this file is also the list of what exists."
  (:require [cljs.core :as core]
            [lt.ui.stories.chrome]
            [lt.ui.story :as story]))

(defn ^:export main []
  (core/println (.stringify js/JSON (clj->js (story/manifest)))))
