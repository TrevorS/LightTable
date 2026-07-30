(ns lt.util.kahn-test
  "Light Table topologically sorts plugin dependencies before loading them, so
  that a plugin's dependencies are in place by the time it initialises. When the
  sort returns nil the plugin loader reports a dependency cycle to the user."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [lt.util.kahn :as kahn]))

(deftest normalize-adds-missing-nodes
  (testing "nodes that only ever appear as dependencies still become keys"
    (is (= {:a #{:b} :b #{}} (kahn/normalize {:a #{:b}}))))

  (testing "an already complete graph is unchanged"
    (is (= {:a #{:b} :b #{}} (kahn/normalize {:a #{:b} :b #{}})))))

(deftest sorts-dependencies-before-dependents
  (testing "a simple chain"
    (let [sorted (vec (kahn/kahn-sort {:a #{:b} :b #{:c} :c #{}}))]
      (is (= [:a :b :c] sorted))))

  (testing "a diamond keeps every edge satisfied"
    (let [graph {:top #{:left :right} :left #{:bottom} :right #{:bottom} :bottom #{}}
          sorted (vec (kahn/kahn-sort graph))
          position (into {} (map-indexed (fn [i n] [n i]) sorted))]
      (is (= 4 (count sorted)))
      (doseq [[node deps] graph
              dep deps]
        (is (< (position node) (position dep))
            (str node " must be ordered before its dependency " dep)))))

  (testing "an empty graph sorts to nothing rather than failing"
    (is (empty? (kahn/kahn-sort {})))))

(deftest reports-cycles-as-nil
  (testing "a two node cycle"
    (is (nil? (kahn/kahn-sort {:a #{:b} :b #{:a}}))))

  (testing "a longer cycle"
    (is (nil? (kahn/kahn-sort {:a #{:b} :b #{:c} :c #{:a}}))))

  (testing "a cycle is still detected when reachable from acyclic nodes"
    (is (nil? (kahn/kahn-sort {:standalone #{:a} :a #{:b} :b #{:a}})))))
