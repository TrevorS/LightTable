(ns lt.ui.story
  "What a component is, as data, in one place.

  There were two answers to \"what states does this component have?\" — the
  cards in [[lt.ui.catalogue]] and, as of the last commit, a Storybook
  registry. Two is the number that goes stale. This is the one both read.

  ```clojure
  (story/of ::chrome/status-dot
    {:doc    \"Where a connection is, as one character.\"
     :props  [[\"status\" \"keyword\" \"idle, connecting, executing, …\"]
              [\"hollow\" \"boolean\" \"an outline rather than a fill\"]]
     :usage  \"the statusbar, the connect panel\"
     :states {:idle       {:status :idle}
              :connecting {:status :connecting :pulse true}}})
  ```

  **A state is props, not markup**, and that is the change this format exists
  to make. An alias silently ignores an attribute it does not destructure, so a
  state written as hiccup can pass `{:tone :ok}` to a component that takes
  `:status`, render the default, and look like it worked — which is exactly
  what happened here, and what the render check could not catch. Props are also
  what a Storybook control varies, so the knobs come from the same map.

  Markup is still available, because not every component is worth seeing alone:
  a band wants a line of code under it and a row wants something in its slots.
  Pass `:hiccup` for that state and it is drawn as written.

  **Naming the alias is the other half.** `::chrome/status-dot` is a keyword the
  compiler resolves, so a renamed component is a compile error in its stories
  rather than a card that quietly draws nothing. That is the whole argument for
  writing these in ClojureScript instead of in the JavaScript that has to exist
  for Storybook's indexer anyway.

  Nothing here renders. This is a registry and a shape, so it loads under node
  with no DOM — which is what lets `script/gen-stories.mts` ask what stories
  exist without starting a browser."
  (:require [clojure.string :as string]))

(defonce ^{:doc "Every component that has told this namespace about itself, by alias."}
  registry
  (atom {}))

(defn short-ns
  "`lt.ui.chrome` → `chrome`. Every one of these is `lt.ui.something` and the
  prefix is noise in a sidebar and on a card."
  [alias]
  (last (string/split (namespace alias) #"\.")))

(defn story-id
  "What one state is addressed by: `chrome/status-dot--connecting`.

  Derived from the alias rather than given, so it cannot disagree with the
  component it draws."
  [alias state]
  (str (short-ns alias) "/" (name alias) "--" (name state)))

(defn title
  "Where the component sits in Storybook's sidebar."
  [alias]
  (str "Kit/" (short-ns alias) "/" (name alias)))

(defn hiccup-for
  "What to draw for one state.

  Props become an alias call; `:hiccup` is taken as written; `:children` are
  what the alias receives as its second argument.

  `:hiccup` may be a function, called at render time with no arguments. That is
  for the two components whose content is DOM somebody else owns — a static
  registry cannot hold a node, and building one at namespace load would mean
  this file could not be read under node, which is exactly what
  `script/gen-stories.mts` does."
  [alias spec]
  (let [h (:hiccup spec)]
    (cond
      (fn? h) (h)
      h h
      :else (let [props (dissoc spec :hiccup :children :doc)]
              (cond-> [alias props]
                (:children spec) (into (:children spec)))))))

(defn of
  "Register everything known about `alias`.

  `spec` carries `:doc`, `:props` as `[name type note]` triples, `:usage`,
  `:width` for the catalogue's layout, `:badge` for the things that look like
  components and deliberately are not one, and `:states`.

  Called at namespace load, and the story namespaces are entries of the
  `:storybook` build and required by the catalogue — so a component described
  once is described in both places."
  [alias spec]
  (swap! registry assoc alias (assoc spec :alias alias)))

(defn states
  "One component's states, in declaration order, as `[name hiccup]`.

  Declaration order rather than sorted: the order states are written in is the
  order they make sense in — idle before connecting before finished — and
  alphabetising that is losing information the author had."
  [alias]
  (when-let [spec (get @registry alias)]
    (vec (for [[state s] (:states spec)]
           [(name state) (hiccup-for alias (if (map? s) s {}))]))))

(defn manifest
  "Every state of every component, as plain data, for the generator.

  Sorted by id, because this becomes files on disk and an unstable order is a
  diff that says nothing."
  []
  (vec (sort-by :id
                (for [[alias spec] @registry
                      [state s] (:states spec)]
                  {:id (story-id alias state)
                   :title (title alias)
                   :name (name state)
                   :doc (or (:doc (when (map? s) s)) (:doc spec))
                   ;; A string: prop maps hold keywords and ClojureScript
                   ;; collections, and what the docs panel needs is to show
                   ;; them. Reading them back is module 5's problem.
                   :props (pr-str (dissoc (if (map? s) s {}) :hiccup :children :doc))}))))
