(ns lt.objs.plugins.scopes-test
  "Tests for where a capability reaches.

  The cases here are not \"does prefix matching work\". They are the four ways a
  scope leaks, three of which this repository has already been bitten by:

  | | |
  |---|---|
  | a sibling whose name starts the same way | `require-shim/in-dir?`, and `pool/containing-path` |
  | `..` climbing out | not yet, and the reason [[lt.objs.plugins.scopes/normalize]] exists |
  | a symlink pointing out | [[lt.objs.plugins.scopes/resolve-through]], against a fake filesystem below |
  | a subdomain rule that matches the wrong domain | the network shape of the first one |

  A fifth, found by making the fourth testable: resolving a path whose ancestors
  all fail to exist used to *double* it, which is the case the resolution exists
  for. That is `a-path-whose-ancestors-all-vanish-is-not-doubled`."
  (:require [cljs.test :refer [deftest is testing]]
            [lt.objs.plugins.scopes :as scopes]))

;;*********************************************************
;; normalize
;;*********************************************************

(deftest normalize-resolves-dot-segments
  (is (= "/a/c" (scopes/normalize "/a/b/../c")))
  (is (= "/a" (scopes/normalize "/a/b/..")))
  (is (= "/a/b" (scopes/normalize "/a/./b")))
  (is (= "/a/b" (scopes/normalize "/a//b")))
  (is (= "/a/b" (scopes/normalize "/a/b/")))
  (is (= "/" (scopes/normalize "/"))))

(deftest dot-dot-cannot-climb-above-an-absolute-root
  (testing "because /../x and /x are the same file"
    (is (= "/x" (scopes/normalize "/../x")))
    (is (= "/x" (scopes/normalize "/a/../../x")))))

(deftest dot-dot-is-kept-on-a-relative-path
  (testing "../x is a real place and is not the same one as x"
    (is (= "../x" (scopes/normalize "../x")))
    (is (= "." (scopes/normalize "")))))

;;*********************************************************
;; under?
;;*********************************************************

(deftest a-root-contains-itself
  (is (scopes/under? "/src/app" "/src/app"))
  (testing "with or without a trailing separator on either side"
    (is (scopes/under? "/src/app/" "/src/app"))
    (is (scopes/under? "/src/app" "/src/app/"))))

(deftest a-sibling-with-a-shared-prefix-is-not-inside
  (testing "the bug require-shim/in-dir? had, and pool/containing-path after it"
    (is (not (scopes/under? "/src/app" "/src/application")))
    (is (not (scopes/under? "/src/app" "/src/app-2/x")))
    (is (not (scopes/under? "/plugins/C" "/plugins/Clojure/c.js")))))

(deftest what-is-inside-is-inside
  (is (scopes/under? "/src/app" "/src/app/deep/file.txt"))
  (is (scopes/under? "/" "/anything")))

(deftest a-path-cannot-climb-out-of-a-root
  (testing "which is normalize's job, and the reason under? calls it"
    (is (not (scopes/under? "/src/app" "/src/app/../../etc/passwd")))
    (is (scopes/under? "/src/app" "/src/app/sub/../ok.txt"))))

;;*********************************************************
;; The manifest, in both forms
;;*********************************************************

(deftest a-set-declares-capabilities-and-no-scope
  (is (= #{:files :network} (scopes/declared-capabilities #{:files :network})))
  (testing "and every one of them reaches anywhere, which is what it always meant"
    (is (= :all (scopes/declared-roots #{:files :network} :files)))))

(deftest a-map-declares-both
  (let [caps {:files [:self :workspace] :network :all}]
    (is (= #{:files :network} (scopes/declared-capabilities caps)))
    (is (= [:self :workspace] (scopes/declared-roots caps :files)))
    (is (= :all (scopes/declared-roots caps :network)))))

(deftest nil-and-empty-are-different-answers
  (testing "nil is a plugin that predates manifests; #{} asserts it needs nothing"
    (is (nil? (scopes/declared-capabilities nil)))
    (is (= #{} (scopes/declared-capabilities #{})))))

(deftest a-single-root-need-not-be-wrapped
  (is (= [:self] (scopes/declared-roots {:files :self} :files)))
  (is (= ["/tmp"] (scopes/declared-roots {:files "/tmp"} :files))))

(deftest an-unmentioned-capability-is-not-this-namespaces-question
  (testing "whether it may use it at all is capabilities/declared's"
    (is (= :all (scopes/declared-roots {:files [:self]} :network)))))

(deftest scoped-is-only-true-when-something-is-actually-narrowed
  (is (not (scopes/scoped? #{:files})))
  (is (not (scopes/scoped? {:files :all})))
  (is (not (scopes/scoped? nil)))
  (is (scopes/scoped? {:files [:self]})))

;;*********************************************************
;; permits?
;;*********************************************************

(deftest all-permits-everything
  (is (scopes/permits? :all ["/anywhere/at/all"])))

(deftest a-path-must-be-under-some-root
  (is (scopes/permits? ["/a" "/b"] ["/b/file"]))
  (is (not (scopes/permits? ["/a" "/b"] ["/c/file"]))))

(deftest every-path-must-be-permitted-not-just-one
  (testing "a copy out of a scope is not half allowed"
    (is (not (scopes/permits? ["/a"] ["/a/from" "/b/to"])))
    (is (scopes/permits? ["/a"] ["/a/from" "/a/to"]))))

(deftest no-roots-permits-nothing
  (testing "an empty root list is not the same as :all"
    (is (not (scopes/permits? [] ["/a"])))))

(deftest a-call-with-no-paths-is-not-a-path-question
  (is (scopes/permits? ["/a"] [])))

;;*********************************************************
;; Hosts
;;*********************************************************

(deftest url-host-reads-the-host-out-of-a-url
  (is (= "github.com" (scopes/url-host "https://github.com/a/b")))
  (is (= "github.com" (scopes/url-host "github.com")))
  (is (= "github.com" (scopes/url-host "GitHub.com")))
  (testing "and a url whose host is not where it looks"
    ;; The reason this is js/URL and not a regex.
    (is (= "evil.com" (scopes/url-host "https://evil.com#@github.com")))))

(deftest a-subdomain-is-under-its-domain
  (is (scopes/host-under? "github.com" "github.com"))
  (is (scopes/host-under? "github.com" "codeload.github.com"))
  (is (scopes/host-under? "github.com" "https://codeload.github.com/x")))

(deftest a-domain-that-merely-ends-the-same-way-is-not
  (testing "the dot boundary, which is the separator rule in network shape"
    (is (not (scopes/host-under? "github.com" "notgithub.com")))
    (is (not (scopes/host-under? "github.com" "github.com.evil.net")))))

(deftest hosts-are-permitted-the-same-way-paths-are
  (is (scopes/permits-hosts? ["github.com"] ["https://codeload.github.com/x"]))
  (is (not (scopes/permits-hosts? ["github.com"] ["https://evil.net/x"])))
  (is (scopes/permits-hosts? :all ["https://anywhere.example/x"])))

;;*********************************************************
;; What a denial says
;;*********************************************************

(deftest a-denial-names-the-plugin-the-capability-and-the-scope
  (let [line (scopes/describe-denial "Clojure" :files "/etc/passwd" ["/home/u/src"])]
    (is (re-find #"Clojure" line))
    (is (re-find #"files" line))
    (is (re-find #"/etc/passwd" line))
    (testing "and where it may reach instead, which is what makes the fix obvious"
      (is (re-find #"/home/u/src" line)))))

;;*********************************************************
;; resolve-through
;;*********************************************************

;; A fake filesystem: a set of what exists, and a map of symlinks. Enough to ask
;; the three questions that matter, and the reason `resolve-through` takes its
;; two answers as arguments instead of reaching for a bridge.
(def ^:private on-disk #{"/" "/home" "/home/u" "/home/u/src" "/home/u/link"})
(def ^:private links {"/home/u/link" "/home/u/src"})

;; Not named `exists?` — that is `cljs.core/exists?`, and shadowing it is a
;; clj-kondo error rather than a warning.
(defn- realpath [p] (get links p p))
(defn- there? [p] (contains? on-disk p))

(deftest an-existing-path-resolves-to-itself
  (is (= "/home/u/src" (scopes/resolve-through there? realpath "/home/u/src"))))

(deftest a-symlink-resolves-to-what-it-points-at
  (testing "which is the whole reason this exists — a scope on the string is one a symlink leaves"
    (is (= "/home/u/src" (scopes/resolve-through there? realpath "/home/u/link")))))

(deftest a-path-that-does-not-exist-yet-resolves-through-the-part-that-does
  ;; writeFileSync names a file with no realpath. The ancestor is what carries
  ;; the symlink, so resolving only the existing part is what puts the write
  ;; inside the scope the symlink points at.
  (is (= "/home/u/src/new.txt"
         (scopes/resolve-through there? realpath "/home/u/link/new.txt")))
  (is (= "/home/u/src/deep/new.txt"
         (scopes/resolve-through there? realpath "/home/u/link/deep/new.txt"))))

(deftest a-path-whose-ancestors-all-vanish-is-not-doubled
  ;; The bug an earlier version had: it rebuilt the answer from the original path
  ;; *plus* the accumulated suffix, so this came back as
  ;; "/nowhere/at/all/nowhere/at/all". Reachable by any write under a directory
  ;; that does not exist, which is the case the function is for.
  (is (= "/nowhere/at/all"
         (scopes/resolve-through (constantly false) realpath "/nowhere/at/all"))))

(deftest resolving-normalises-on-the-way-through
  (is (= "/home/u/src" (scopes/resolve-through there? realpath "/home/u/src/")))
  (is (= "/home/u/src" (scopes/resolve-through there? realpath "/home/u/./src")))
  (testing "and a climb out is resolved before anything is compared"
    (is (= "/home" (scopes/resolve-through there? realpath "/home/u/src/../..")))))

(deftest a-resolved-symlink-is-what-the-scope-check-then-sees
  ;; The two halves together, which is the claim the design rests on: a plugin
  ;; scoped to /home/u/src cannot escape by way of a link that points into it,
  ;; and cannot enter by way of one that points out.
  (let [resolved (scopes/resolve-through there? realpath "/home/u/link/x.txt")]
    (is (scopes/permits? ["/home/u/src"] [resolved]))
    (is (not (scopes/permits? ["/home/u/link"] [resolved]))
        "a root is resolved too — see plugin-roots, which resolves both sides")))
