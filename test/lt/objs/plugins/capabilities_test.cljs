(ns lt.objs.plugins.capabilities-test
  "Tests for capability inference.

  The cases are taken from real published plugins, because the interesting
  failures are not 'does the regex work' but 'does this classify the ecosystem
  the way a person would'. Terminal is here because it is the plugin that
  disproves the obvious design: it requires nothing from Node and spawns
  processes anyway."
  (:require [cljs.test :refer [deftest is testing]]
            [lt.objs.plugins.capabilities :as caps]))

(deftest finds-node-requires
  (is (= #{:files} (caps/scan "var fs = require('fs');")))
  (is (= #{:files} (caps/scan "var p = require(\"path\");")))
  (is (= #{:processes} (caps/scan "require('child_process').spawn(cmd);")))
  (is (= #{:network} (caps/scan "var net = require('net');"))))

(deftest finds-light-tables-own-privileged-namespaces
  (testing "the route a manifest built on require would miss entirely"
    ;; Terminal's shape: no require anywhere, and it starts processes.
    (is (= #{:processes}
           (caps/scan "lt.objs.proc.exec.call(null, cmd);"))))
  (is (= #{:files} (caps/scan "lt.objs.files.open_sync(path);")))
  (is (= #{:worker} (caps/scan "lt.objs.thread.thread_STAR_(x);")))
  (is (= #{:network} (caps/scan "lt.objs.clients.tcp.connect(port);"))))

(deftest both-routes-to-a-thing-are-one-capability
  (is (= (caps/scan "require('child_process')")
         (caps/scan "lt.objs.proc.exec(x)")
         #{:processes})))

(deftest splits-the-platform-namespace-by-what-is-being-done
  (is (= #{:clipboard} (caps/scan "lt.objs.platform.copy(text);")))
  (is (= #{:clipboard} (caps/scan "lt.util.bridge.clipboard.readText();")))
  (is (= #{:desktop} (caps/scan "lt.objs.platform.open_url(href);")))
  (is (= #{:desktop} (caps/scan "lt.util.bridge.shell.openExternal(u);")))
  (testing "so a plugin that only copies text does not read as opening things"
    (is (not (contains? (caps/scan "lt.objs.platform.copy(t);") :desktop)))))

(deftest a-bare-require-of-a-namespace-is-not-use-of-it
  (testing "every plugin's compiled output names lt.objs.plugins this way"
    (is (= #{} (caps/scan "goog.require('lt.objs.plugins');"))))
  (is (= #{:plugins} (caps/scan "lt.objs.plugins.find_plugin(name);"))))

(deftest a-plugin-that-needs-nothing-gets-nothing
  ;; Emmet, Paredit and the themes: pure editor extensions.
  (is (= #{} (caps/scan "lt.objs.editor.pool.last_active();")))
  (is (= #{} (caps/scan "")))
  (is (= #{} (caps/scan nil))))

(deftest evidence-names-what-was-matched
  (let [found (caps/evidence "lt.objs.proc.exec(c); var fs = require('fs');")]
    (is (= #{:processes :files} (set (keys found))))
    (is (= ["lt.objs.proc.e"] (:processes found))
        "the matched text, so a finding can be checked rather than believed")))

(deftest undeclared-compares-a-manifest-against-what-was-found
  (testing "a plugin held to what it said"
    (is (= #{:processes}
           (caps/undeclared {:capabilities #{:files}} #{:files :processes}))))
  (testing "declaring more than is used is not a violation"
    (is (= #{} (caps/undeclared {:capabilities #{:files :processes}} #{:files}))))
  (testing "an unmanifested plugin claimed nothing, so it broke no claim"
    (is (= #{} (caps/undeclared {} #{:files :processes}))))
  (testing "declaring nothing is a claim, unlike declaring nothing at all"
    (is (= #{:files} (caps/undeclared {:capabilities #{}} #{:files})))))

(deftest unknown-catches-manifest-typos
  (is (= #{:filez} (caps/unknown {:capabilities #{:files :filez}})))
  (is (= #{} (caps/unknown {:capabilities #{:files}})))
  (is (= #{} (caps/unknown {}))))

(deftest every-capability-describes-itself
  (doseq [c caps/known]
    (is (string? (caps/describe c)) (str c " has no description"))))

(deftest verdict-decides-what-to-do-about-a-violation
  (let [clean {:name "Emmet" :declared #{} :undeclared #{} :unknown #{}}
        over  {:name "Rogue" :declared #{:clipboard} :undeclared #{:processes} :unknown #{}}]
    (testing "a plugin inside its manifest is allowed under every mode"
      (doseq [mode caps/modes]
        (is (= :allow (caps/verdict mode clean)) (str "under " mode))))
    (testing "and one outside it depends on the mode"
      (is (= :allow (caps/verdict :report over)))
      (is (= :warn (caps/verdict :warn over)))
      (is (= :refuse (caps/verdict :refuse over))))))

(deftest an-unmanifested-plugin-is-never-a-violation
  (testing "it claimed nothing, so nothing it does contradicts a claim"
    (let [legacy {:name "Clojure" :declared nil
                  :undeclared (caps/undeclared {} #{:files :processes})
                  :unknown (caps/unknown {})}]
      (is (not (caps/violation? legacy)))
      (is (= :allow (caps/verdict :refuse legacy))
          "even the strictest mode, because enforcement is for promises"))))

(deftest describe-violation-says-what-is-wrong
  (is (nil? (caps/describe-violation {:declared #{} :undeclared #{} :unknown #{}})))
  (is (= "Rogue declares clipboard but uses processes"
         (caps/describe-violation {:name "Rogue" :declared #{:clipboard}
                                   :undeclared [:processes] :unknown #{}})))
  (is (= "Typo declares filez, which is not a capability"
         (caps/describe-violation {:name "Typo" :declared #{:files}
                                   :undeclared [] :unknown [:filez]}))))
