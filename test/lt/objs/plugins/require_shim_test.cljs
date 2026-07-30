(ns lt.objs.plugins.require-shim-test
  "Tests for what `require` gives a plugin.

  This is the half of the shim with the judgement in it, and it is deliberately
  free of the bridge so that it can be exercised here rather than only in a
  running editor. The modules themselves are in lt.objs.plugins.node-modules,
  which is bridge-backed by definition and covered by script/smoke-test.js."
  (:require [cljs.test :refer [deftest is testing]]
            [lt.objs.plugins.capabilities :as caps]
            [lt.objs.plugins.require-shim :as shim]))

(def plugins
  {"Clojure" {:name "Clojure" :dir "/home/u/LightTable/deploy/plugins/Clojure"
              :capabilities [:network]}
   "HelloTS" {:name "HelloTS" :dir "/home/u/LightTable/deploy/plugins/HelloTS"
              :capabilities []}
   "Legacy" {:name "Legacy" :dir "/home/u/LightTable/deploy/plugins/Legacy"}})

(defn- infer
  "Stands in for reading a plugin's code, which lt.objs.plugins does."
  [_]
  #{:files})

;;*********************************************************
;; Attribution
;;*********************************************************

(deftest finds-the-plugin-a-frame-belongs-to
  ;; The shape Light Table's own stacks have — see doc/context-isolation.md.
  (let [frames ["Error"
                "    at Object.notify (/home/u/LightTable/deploy/plugins/HelloTS/hello_compiled.js:42:25)"
                "    at exec (/home/u/LightTable/deploy/plugins/HelloTS/hello_compiled.js:73:16)"]]
    (is (= "HelloTS" (:name (shim/plugin-for-frames plugins frames))))))

(deftest takes-the-innermost-plugin
  (testing "a plugin calling through another's helper asks on its own behalf"
    (let [frames ["Error"
                  "    at f (/home/u/LightTable/deploy/plugins/Clojure/clojure_compiled.js:9:1)"
                  "    at g (/home/u/LightTable/deploy/plugins/HelloTS/hello_compiled.js:1:1)"]]
      (is (= "Clojure" (:name (shim/plugin-for-frames plugins frames)))))))

(deftest light-tables-own-frames-belong-to-no-plugin
  (let [frames ["Error"
                "    at lt.objs.files.open_sync (file:///home/u/LightTable/deploy/core/lighttable/bootstrap.js:1:1)"]]
    (is (nil? (shim/plugin-for-frames plugins frames)))))

(deftest a-plugin-without-a-directory-is-never-matched
  (is (nil? (shim/plugin-for-frames {"Broken" {:name "Broken"}}
                                    ["    at x (/anywhere/at/all.js:1:1)"]))))

;; Two defns rather than nested fns: ClojureScript names a nested fn after its
;; parent too, so one frame would carry both names and prove nothing.
(defn- callee [] (shim/frames))
(defn- caller [] (callee))

(deftest frames-are-captured-innermost-first
  (let [captured (caller)
        at (fn [name] (first (keep-indexed #(when (re-find name %2) %1) captured)))]
    (is (< 1 (count captured)))
    (is (< (at #"callee") (at #"caller"))
        "the innermost caller is first, which is what makes `first` the right pick")))

;;*********************************************************
;; What a plugin is allowed
;;*********************************************************

(deftest a-manifest-is-taken-at-its-word
  (is (= #{:network} (set (shim/allowed-for (plugins "Clojure") infer))))
  (testing "including when it declares nothing"
    (is (empty? (shim/allowed-for (plugins "HelloTS") infer)))))

(deftest a-plugin-with-no-manifest-gets-what-it-is-inferred-to-use
  (is (= #{:files} (shim/allowed-for (plugins "Legacy") infer))))

(deftest an-unattributed-caller-gets-everything
  (testing "Light Table's own code, until it stops calling require at all"
    (is (= caps/known (shim/allowed-for nil infer)))))

;;*********************************************************
;; Serving
;;*********************************************************

(def modules
  {"net" [:network (constantly :the-net-module)]
   "buffer" [nil (constantly :the-buffer-module)]})

(def ^:private local-files
  "Stands in for JavaScript a plugin ships, which lt.objs.plugins.local-modules
  resolves and runs for real."
  {["/home/u/LightTable/deploy/plugins/Clojure" "harbor"] :the-vendored-harbor
   ["/home/u/LightTable/deploy/plugins/Clojure" "./lib/util"] :a-relative-file})

(defn- local [from-dir request _] (local-files [from-dir request]))

(defn- require-as
  "A require that attributes every call to `plugin`, so that what is under test
  is the decision rather than this file's own stack."
  [plugin]
  (let [f (shim/requirer modules (constantly plugins) infer local)]
    (fn [module-name]
      (with-redefs [shim/plugin-for-frames (fn [_ _] plugin)]
        (f module-name)))))

(deftest serves-a-module-the-capability-covers
  (is (= :the-net-module ((require-as (plugins "Clojure")) "net"))))

(deftest refuses-a-module-the-capability-does-not-cover
  (is (thrown-with-msg? js/Error #"Plugin 'HelloTS' has not declared the 'network' capability"
                        ((require-as (plugins "HelloTS")) "net"))))

(deftest a-module-that-reaches-nothing-needs-no-capability
  (is (= :the-buffer-module ((require-as (plugins "HelloTS")) "buffer"))))

(deftest an-unmanifested-plugin-is-served-what-it-was-inferred-to-use
  (testing "so that turning this on breaks nothing that worked the day before"
    (is (thrown-with-msg? js/Error #"has not declared the 'network' capability"
                          ((require-as (plugins "Legacy")) "net")))))

(deftest falls-through-to-what-the-plugin-shipped
  (testing "a vendored package"
    (is (= :the-vendored-harbor ((require-as (plugins "Clojure")) "harbor"))))
  (testing "and a file inside it"
    (is (= :a-relative-file ((require-as (plugins "Clojure")) "./lib/util")))))

(deftest a-served-name-wins-over-a-vendored-one
  (testing "Node's rule, so a plugin with a net/ directory still gets sockets"
    (let [f (shim/requirer modules (constantly plugins) infer
                           (fn [_ _ _] :the-vendored-net))]
      (with-redefs [shim/plugin-for-frames (fn [_ _] (plugins "Clojure"))]
        (is (= :the-net-module (f "net")))))))

(deftest says-so-for-a-module-that-is-neither
  (is (thrown-with-msg? js/Error #"neither one Light Table serves to plugins nor a file"
                        ((require-as (plugins "Clojure")) "vm"))))
