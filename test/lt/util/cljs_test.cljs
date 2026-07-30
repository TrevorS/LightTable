(ns lt.util.cljs-test
  "`(\"key\" some-map)` is published Light Table API — behaviors and plugin code
  use a string as a lookup function all over. It works because `lt.util.cljs`
  extends `js/String` with `IFn`.

  There used to be a `(set! js/String.prototype.apply ...)` beside that
  extension doing the same job a second time, and it broke CodeMirror's
  simple-mode addon: the addon decides whether a token is a function by asking
  for `.apply`, so every string token looked callable and threw. Removing it
  fixed six language modes.

  These tests exist so that removal stays safe. They cover the three ways a
  string reaches its lookup — directly, through `apply`, and as a
  higher-order function — because that is what the deleted code appeared to be
  for, and the claim being made is that the protocol already covers all of it."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [lt.util.cljs]))

(def ^:private m {"key" "found" "other" 2})

(deftest a-string-is-a-lookup-function
  (testing "called directly"
    (is (= "found" ("key" m)))
    (is (= 2 ("other" m))))

  (testing "with a not-found argument"
    (is (= :missing ("nope" m :missing)))
    (is (nil? ("nope" m))))

  (testing "through apply, which is what the deleted prototype method was for"
    (is (= "found" (apply "key" [m])))
    (is (= :missing (apply "nope" [m :missing]))))

  (testing "as a higher-order function"
    (is (= ["found" 2] (mapv #(% m) ["key" "other"])))
    (is (= ["found"] (mapv "key" [m])))))

(deftest a-string-still-seqs
  (testing "the other half of the extension"
    (is (= [\a \b \c] (seq "abc")))
    (is (nil? (seq "")))))

(deftest strings-do-not-look-like-functions
  (testing "no `apply` on the prototype, which is what broke simple-mode"
    (is (undefined? (.-apply "a-plain-string"))
        "CodeMirror's simple-mode addon reads this to decide whether a rule's
         token is a function to call. A string answering yes made it call a
         string, which threw and took Rust highlighting with it.")))

(deftest an-array-seqs
  (testing "the js/Array half of the same namespace"
    (is (= [1 2 3] (seq #js [1 2 3])))
    (is (nil? (seq #js [])))))
