(ns lt.objs.plugins.edn-format-test
  "Tests for how the user plugin's `plugin.edn` is written back.

  There is one assertion that matters here and the rest are it in detail: what
  this writes has to read back as the map that went in. It did not. Opening the
  plugin manager rewrote the file as

      [\"{\" \"{\"]
      :name \"User[\"\\\",\" \"\\\",\"]

  because `clojure.string/replace` hands a replacement *function* a **vector** of
  `[match & groups]` when the pattern has any capture group, and the pattern had
  one wrapping its whole alternation, used by nothing. So `(str % \"\\n\")`
  stringified a vector.

  It destroyed a file the user owns, on the first open of the plugin manager, and
  the three errors it then produced named three different things and never the
  cause: `FAILED to load plugin.edn`, `Invalid behavior:
  save-user-plugin-dependencies`, and `Vector's key for assoc must be a number`.

  A round trip is the test because a round trip is the requirement. Asserting the
  exact output string would have passed against the broken version the moment
  somebody pasted its output in as the expectation."
  (:require [cljs.test :refer [deftest is testing]]
            [cljs.reader :as reader]
            [clojure.string :as string]
            [lt.objs.plugins.edn-format :as edn-format]))

(def ^:private user-plugin
  {:name "User"
   :version "0.0.1"
   :author "TODO"
   :source "TODO"
   :desc "TODO"
   :behaviors "user.behaviors"})

(defn- round-trip
  "What comes back out after formatting `m` and reading it again."
  [m]
  (reader/read-string (edn-format/format-edn (pr-str m))))

(deftest what-is-written-reads-back-as-what-went-in
  (is (= user-plugin (round-trip user-plugin))))

(deftest and-still-does-with-the-dependencies-on-it
  ;; The real shape: `save-plugins` adds `:dependencies` as a sorted map of every
  ;; installed plugin, which is where the nested `{` and the `",` sequences that
  ;; the formatter matches on actually come from.
  (let [m (assoc user-plugin :dependencies (into (sorted-map)
                                                 {"Clojure" "0.3.3"
                                                  "CSS" "0.0.6"
                                                  "TypeScript" "0.1.0"}))]
    (is (= m (round-trip m)))))

(deftest it-is-a-map-and-not-a-vector
  ;; Named on its own, because a vector is exactly what the bug produced and
  ;; `(assoc a-vector :dependencies …)` is the error the next save threw.
  (is (map? (round-trip user-plugin)))
  (is (map? (round-trip (assoc user-plugin :dependencies {"Go" "0.1.0"})))))

(deftest nothing-in-the-output-is-a-stringified-collection
  ;; The signature of the bug, in the one form that would have caught it while
  ;; reading the file rather than parsing it: a vector printed where a delimiter
  ;; belonged.
  (let [out (edn-format/format-edn (pr-str user-plugin))]
    (is (not (string/includes? out "[\"{\""))
        "a stringified match vector was written where a brace belonged")
    (is (string/starts-with? out "{:name"))))

(deftest it-is-still-a-file-somebody-can-read
  (testing "one key per line, which is the whole point of formatting it"
    (let [lines (string/split-lines (edn-format/format-edn (pr-str user-plugin)))]
      (is (= (count user-plugin) (count lines))
          "one line per key")))
  (testing "and the generated half says not to edit it"
    (let [out (edn-format/format-edn
               (pr-str (assoc user-plugin :dependencies {"Go" "0.1.0"})))]
      (is (string/includes? out ";; Do not edit"))
      ;; The comment has to be a comment rather than part of the data, which is
      ;; only true if the reader still gets the same map — asserted above, and
      ;; restated here because this is the line that puts it there.
      (is (= (assoc user-plugin :dependencies {"Go" "0.1.0"})
             (reader/read-string out))))))
