(ns lt.core-test
  "Guards the property that lt.core exists to provide: that compiling from it
  as an entry point still reaches every namespace in the tree.

  Light Table registers behaviors, commands and objects as a side effect of
  namespaces loading, and many are required by nothing. Under
  :optimizations :simple that was invisible, because the whole source path went
  into the bundle regardless. A bundler keeps only what the entry reaches, so a
  namespace added later that nothing requires would be dropped silently — the
  behaviors inside it would simply never register.

  This walks the source rather than the build output, so it fails on the commit
  that introduces the orphan instead of whenever someone notices a missing
  feature."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as string]))

(def ^:private fs (js/require "fs"))
(def ^:private fpath (js/require "path"))

(def ^:private entry-points
  "Every namespace a build compiles from. Each shadow-cljs target has one, and
  between them they must cover the tree."
  '#{lt.core                  ;; the renderer bundle
     lt.background.worker      ;; the worker thread
     lt.ui.storybook})         ;; the component kit, built for a plain browser

(defn- cljs-files
  "Every ClojureScript source file under `dir`."
  [dir]
  (reduce (fn [acc entry]
            (let [full (.join fpath dir entry)]
              (cond
                (.isDirectory (.statSync fs full)) (into acc (cljs-files full))
                (re-find #"\.clj[sc]$" entry) (conj acc full)
                :else acc)))
          []
          (array-seq (.readdirSync fs dir))))

(defn- ns-name-of [source]
  (when-let [m (re-find #"\(ns\s+([a-zA-Z0-9._*+!?<>=-]+)" source)]
    (symbol (second m))))

(defn- requires-of
  "Namespaces `source` depends on. Deliberately crude: it reads the whole file
  rather than just the ns form, so it over-approximates. That is the safe
  direction — a missed dependency would make this test fail loudly, where a
  spurious one only makes it more permissive about an orphan it should catch."
  [source]
  (into #{}
        (comp (map (fn [[_ a b]] (or a b)))
              (map symbol))
        (concat (re-seq #"\[([a-z][a-zA-Z0-9._-]*)\s+:as" source)
                (re-seq #"\[([a-z][a-zA-Z0-9._-]*)\]" source)
                (re-seq #"\(:use\s+\[([a-z][a-zA-Z0-9._-]*)" source))))

(defn- source-graph []
  (reduce (fn [acc file]
            (let [source (.toString (.readFileSync fs file))]
              (if-let [ns-sym (ns-name-of source)]
                (assoc acc ns-sym {:file file :requires (requires-of source)})
                acc)))
          {}
          (cljs-files "src")))

(defn- reachable-from [graph roots]
  (loop [seen #{} pending (vec roots)]
    (if-let [current (first pending)]
      (if (or (seen current) (not (contains? graph current)))
        (recur seen (rest pending))
        (recur (conj seen current)
               (into (vec (rest pending)) (:requires (get graph current)))))
      seen)))

(deftest every-namespace-is-reachable-from-an-entry-point
  (let [graph (source-graph)
        reachable (reachable-from graph entry-points)
        ;; Macro namespaces arrive through :require-macros, which is a
        ;; compile-time edge this walker does not model.
        runtime-ns (remove #(string/ends-with? (:file (get graph %)) ".cljc")
                           (keys graph))
        orphaned (sort (remove reachable runtime-ns))]

    (testing "the source graph was actually read"
      (is (every? #(contains? graph %) entry-points)
          (str "every entry point should be on the source path: " entry-points))
      (is (> (count graph) 50)
          (str "expected the whole tree, found " (count graph) " namespaces")))

    (testing "nothing is left out of the bundle"
      (is (empty? orphaned)
          (str "These namespaces are not reachable from any entry point, so a "
               "bundler would drop them and anything they register would never "
               "load. Require them from lt.core, or from the entry point of "
               "whichever build should contain them: "
               (string/join ", " orphaned))))))
