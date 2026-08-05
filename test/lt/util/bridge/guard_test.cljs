(ns lt.util.bridge.guard-test
  "Tests for the bridge's permission check.

  Two halves, and the second is the one that matters more. The first is that a
  guarded call does what the policy says. The second is that the **tables are
  complete** — a function taking a path that nobody added to `path-args` is
  unguarded, and an unguarded argument is this design's entire failure mode. So
  the preload is parsed and compared against the table rather than trusted to
  agree with it."
  (:require [cljs.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as string]
            [lt.objs.plugins.capabilities :as caps]
            [lt.util.bridge.guard :as guard]))

(use-fixtures :each {:after guard/uninstall!})

(defn- fake-group
  "An object shaped like one of the bridge's groups: two functions that report
  what they were given, and a string property that is not a function."
  []
  #js {:readFileSync (fn [path] (str "read:" path))
       :cpSync (fn [from to] (str "cp:" from "->" to))
       :sep "/"})

(defn- policy
  "A policy that records what it was asked and answers `verdict`."
  [verdict plugin & [log]]
  {:checking? (constantly true)
   :caller (constantly plugin)
   :verdict (fn [& args]
              (when log (swap! log conj (vec args)))
              verdict)
   :refused (fn [_plugin capability paths _hosts group member refuse?]
              (when refuse?
                (throw (js/Error. (str "refused " (name capability) " "
                                       (first paths) " at " group "/" member)))))})

;;*********************************************************
;; The facade
;;*********************************************************

(deftest a-guarded-group-keeps-its-shape
  (let [g (guard/guard "files" (fake-group))]
    (is (= "read:/a" (.readFileSync g "/a")))
    (testing "and its non-function properties, which path arithmetic needs"
      (is (= "/" (.-sep g))))))

(deftest an-unenumerable-group-is-a-loud-failure-rather-than-a-quiet-hole
  (testing "if contextBridge ever stops enumerating, an empty facade would mean no checks"
    (is (thrown-with-msg? js/Error #"no enumerable properties"
                          (guard/guard "files" #js {})))))

(deftest nothing-is-checked-until-a-policy-is-installed
  (let [g (guard/guard "files" (fake-group))]
    (is (= "read:/etc/passwd" (.readFileSync g "/etc/passwd")))))

(deftest light-tables-own-code-is-not-asked-for-permission
  (let [asked (atom [])
        g (guard/guard "files" (fake-group))]
    ;; A nil caller is a frame no plugin directory claims.
    (guard/install! (policy :refuse nil asked))
    (is (= "read:/etc/passwd" (.readFileSync g "/etc/passwd")))
    (is (empty? @asked) "the verdict is never even consulted")))

(deftest the-fast-path-skips-the-stack-read-entirely
  (let [looked (atom 0)
        g (guard/guard "files" (fake-group))]
    (guard/install! {:checking? (constantly false)
                     :caller (fn [] (swap! looked inc) nil)
                     :verdict (constantly :refuse)
                     :refused (fn [& _] nil)})
    (is (= "read:/a" (.readFileSync g "/a")))
    (is (zero? @looked)
        "attribution costs 1.3-2.7µs and a window with nothing to check must not pay it")))

;;*********************************************************
;; What a verdict does
;;*********************************************************

(deftest an-allowed-call-goes-through
  (let [g (guard/guard "files" (fake-group))]
    (guard/install! (policy :allow {:name "P"}))
    (is (= "read:/a" (.readFileSync g "/a")))))

(deftest a-warned-call-goes-through-too
  (testing "which is what keeps the shipped default from breaking a working plugin"
    (let [g (guard/guard "files" (fake-group))]
      (guard/install! (policy :warn {:name "P"}))
      (is (= "read:/a" (.readFileSync g "/a"))))))

(deftest a-refused-call-throws-rather-than-returning-nil
  (testing "there is no honest value for readFileSync, and nil reads as an empty file"
    (let [g (guard/guard "files" (fake-group))]
      (guard/install! (policy :refuse {:name "P"}))
      (is (thrown-with-msg? js/Error #"refused files /a at files/readFileSync"
                            (.readFileSync g "/a"))))))

(deftest both-ends-of-a-copy-are-handed-to-the-policy
  (let [asked (atom [])
        g (guard/guard "files" (fake-group))]
    (guard/install! (policy :allow {:name "P"} asked))
    (.cpSync g "/a/from" "/b/to")
    (let [[[_plugin capability paths _hosts]] @asked]
      (is (= :files capability))
      (is (= ["/a/from" "/b/to"] paths)
          "a copy out of a scope is not half allowed"))))

(deftest an-absent-optional-argument-is-not-a-path
  (let [asked (atom [])
        g (guard/guard "files" #js {:rmSync (fn [& _] nil)})]
    (guard/install! (policy :allow {:name "P"} asked))
    (.rmSync g "/a")
    (is (= ["/a"] (nth (first @asked) 2)))))

;;*********************************************************
;; The tables, which are the part worth checking
;;*********************************************************

(deftest the-guard-and-the-scanner-agree-about-what-each-group-implies
  (testing "two tables saying the same thing is a smell; two checked against each other is the point"
    ;; They answer the same question at different resolutions, which is why this
    ;; is a subset rather than an equality. The scanner reads text and asks
    ;; "could this group imply a capability" — one answer per group is all a
    ;; regex can act on. The guard sees the actual call and can be per-member,
    ;; which `host` is: reaching `appDir` implies nothing and reaching `env`
    ;; implies `:environment`.
    ;;
    ;; So what has to hold is that the guard never names a capability for a
    ;; group that the scanner would not attribute to it. A guard that checked
    ;; for `:files` where the scanner reported `:network` would mean a plugin
    ;; could pass the audit and be refused at runtime, or the reverse.
    (doseq [[group _] guard/groups]
      (let [scanner (get caps/bridge-surface group)
            guarded (->> (cons (get guard/groups group)
                               (for [[k v] guard/members
                                     :when (string/starts-with? k (str group "/"))]
                                 v))
                         (remove nil?)
                         set)]
        (is (every? #(= scanner %) guarded)
            (str "guard and capabilities/bridge-surface disagree about " group
                 ": guard can require " guarded ", the scanner reports " scanner))))))

(deftest a-group-the-guard-checks-per-member-still-has-a-scanner-classification
  (testing "or a plugin reaching it through text would be invisible to the audit"
    (doseq [[k capability] guard/members
            :let [group (first (string/split k #"/"))]]
      (is (= capability (get caps/bridge-surface group))
          (str k " needs " capability " but the scanner classifies " group
               " as " (get caps/bridge-surface group))))))

(deftest every-capability-the-guard-names-is-one-a-manifest-can-declare
  (is (every? caps/known (remove nil? (concat (vals guard/groups)
                                             (vals guard/members))))))

(defn- preload-source []
  (.readFileSync (js/require "fs") "src-electron/preload.ts" "utf8"))

(defn- depth-change
  "How much `s` opens or closes parentheses, net."
  [s]
  (- (count (filter #(= "(" %) s))
     (count (filter #(= ")" %) s))))

(defn- signatures
  "`[group member params]` for every function the preload's bridge declares.

  Parsed from the interface rather than from the implementation: the interface
  is where the types are, and a `path: string` is the thing being looked for.
  Group boundaries come from the four-space-indented `name: {` lines, which is
  how that file is laid out throughout.

  A signature is accumulated until its parentheses balance rather than read off
  one line, and that is not defensiveness — `files/watch` wraps onto a second
  line and carries a callback type with parentheses of its own. Reading one line
  found fourteen of the fifteen file functions and silently left `watch`
  unchecked, which is precisely the failure this test exists to catch, arriving
  in the test instead of in the table."
  [source]
  (let [lines (string/split-lines source)]
    (:found
     (reduce
      (fn [{:keys [group pending] :as acc} line]
        (cond
          ;; Mid-signature: keep going until the parens close.
          pending
          (let [text (str pending " " (string/trim line))
                depth (depth-change text)]
            (if (pos? depth)
              (assoc acc :pending text)
              (let [[_ member params] (re-find #"^([a-zA-Z]+)\((.*)\)" text)]
                (-> acc
                    (assoc :pending nil)
                    (cond-> member (update :found conj [group member params]))))))

          (re-find #"^    ([a-z]+): \{$" line)
          (assoc acc :group (second (re-find #"^    ([a-z]+): \{$" line)))

          (re-find #"^    \},?$" line)
          (assoc acc :group nil)

          :else
          (if-let [[_ start] (and group (re-find #"^        ([a-zA-Z]+\(.*)$" line))]
            (if (pos? (depth-change start))
              (assoc acc :pending start)
              (let [[_ member params] (re-find #"^([a-zA-Z]+)\((.*)\)" start)]
                (cond-> acc member (update :found conj [group member params]))))
            acc)))
      {:group nil :pending nil :found []}
      lines))))

(deftest every-preload-function-taking-a-path-is-in-the-table
  (testing "an argument nobody added to path-args is an argument nobody checks"
    (let [source (preload-source)
          found (signatures source)
          ;; The parameter names the preload uses for a filesystem path. Named
          ;; rather than pattern-matched on `: string`, because a command, a
          ;; url and a host are strings too and are scoped differently or not
          ;; at all.
          path-param #"^(path|from|to|dest|archive|script)$"
          unguarded (for [[group member params] found
                          :let [key (str group "/" member)
                                names (->> (string/split params #",")
                                           (map #(-> % string/trim (string/split #"[?:]") first string/trim)))]
                          :when (and (some #(re-find path-param %) names)
                                     (contains? guard/groups group)
                                     (not (contains? guard/path-args key)))]
                      key)]
      (is (seq found) "the preload still parses the way this test assumes")
      (is (empty? unguarded)
          (str "bridge functions take a path and are not scoped: "
               (string/join " " unguarded)
               " — add them to lt.util.bridge.guard/path-args")))))

(deftest nothing-in-the-table-has-stopped-existing
  (let [found (set (map (fn [[g m _]] (str g "/" m)) (signatures (preload-source))))]
    (is (empty? (remove found (keys guard/path-args)))
        (str "path-args names functions the preload no longer has: "
             (string/join " " (remove found (keys guard/path-args)))))
    (is (empty? (remove found (keys guard/host-args)))
        (str "host-args names functions the preload no longer has: "
             (string/join " " (remove found (keys guard/host-args)))))))

(deftest the-filesystem-group-is-fifteen-functions
  (testing "doc/permissions.md scoped the work at twelve, from a stale docstring"
    (is (= 15 (count (filter (fn [[k _]] (string/starts-with? k "files/"))
                             guard/path-args))))))
