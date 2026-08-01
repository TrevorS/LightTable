(ns lt.util.load.compiled-test
  (:require [cljs.test :refer-macros [deftest is]]
            [lt.util.load.compiled :as compiled]))

;; What the bundle has. Real shape: shadow declares every hoisted constant as
;; one top-level var, and the name carries the index and the value.
(def bundle
  #{"cljs$cst$110$listeners" "cljs$cst$2357$h1" "cljs$cst$846$nil"})

(defn- missing [code]
  (compiled/mismatched-constants code bundle))

(deftest javascript-that-was-never-clojurescript
  ;; The TypeScript plugin compiles with tsc and names none of these, so the
  ;; check has to be free rather than wrong for it.
  (is (= [] (missing "'use strict';\nexports.foo = function (a) { return a + 1; };\n"))))

(deftest constants-the-bundle-has
  (is (= [] (missing "lt.plugins.x.go = function() { return cljs$cst$2357$h1; };"))))

(deftest a-module-declaring-its-own
  ;; A module carries the constants only its own code uses. They are not in the
  ;; bundle and never will be, so flagging them would refuse every plugin.
  (is (= [] (missing (str "'use strict';\n"
                          "var cljs$cst$2680$mine = new cljs.core.Keyword(null, \"mine\", \"mine\", 1),\n"
                          "    cljs$cst$2681$yours = new cljs.core.Keyword(null, \"yours\", \"yours\", 2);\n"
                          "lt.plugins.x.go = function() { return [cljs$cst$2680$mine, cljs$cst$2681$yours,"
                          " cljs$cst$2357$h1]; };")))))

(deftest a-module-from-an-earlier-build
  ;; The one that cost three sessions. Index 110 exists in both builds and
  ;; means something different in each, so an index is not enough to compare —
  ;; this is the pair that actually occurred.
  (is (= ["cljs$cst$110$tags"]
         (missing "lt.plugins.x.go = function() { return cljs$cst$110$tags; };"))))

(deftest every-one-of-them-once
  (is (= ["cljs$cst$1$a" "cljs$cst$2$b"]
         (missing "f(cljs$cst$1$a, cljs$cst$2$b, cljs$cst$1$a, cljs$cst$110$listeners);"))))
