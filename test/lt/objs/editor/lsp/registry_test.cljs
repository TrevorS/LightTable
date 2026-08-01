(ns lt.objs.editor.lsp.registry-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [lt.objs.editor.lsp.registry :as registry]))

(def core
  {:tags [:editor.typescript]
   :language-id "typescript"
   :root ["tsconfig.json"]
   :command "typescript-language-server"
   :args ["--stdio"]})

(def plugin
  (assoc core :command "/opt/plugin/typescript-language-server"))

(def user
  (assoc core :command "/Users/me/.bun/bin/typescript-language-server"))

(defn- declare-all
  "A table built by raising the behavior once per declaration, in the order
  `lt.objs.settings` loads the files it came from."
  [& declarations]
  (reduce registry/add [] declarations))

(deftest finds-a-server-by-tag
  (is (= core (registry/for-tags [core] #{:editor.typescript})))
  (is (nil? (registry/for-tags [core] #{:editor.python})))
  (is (nil? (registry/for-tags [] #{:editor.typescript}))))

(deftest one-entry-answers-for-several-tags
  (let [clojure-lsp {:tags [:editor.clj :editor.cljs]
                     :language-id "clojure"
                     :command "clojure-lsp"}]
    (is (= clojure-lsp (registry/for-tags [clojure-lsp] #{:editor.clj})))
    (is (= clojure-lsp (registry/for-tags [clojure-lsp] #{:editor.cljs})))
    ;; The tags an editor carries are a set, and an editor carries several.
    (is (= clojure-lsp (registry/for-tags [clojure-lsp]
                                          #{:editor :editor.cljs :editor.clojurescript})))))

(deftest later-declarations-win
  (testing "a plugin beats Light Table's own"
    (is (= plugin (registry/for-tags (declare-all [core] [plugin])
                                     #{:editor.typescript}))))
  (testing "a user beats a plugin"
    (is (= user (registry/for-tags (declare-all [core] [plugin] [user])
                                   #{:editor.typescript}))))
  (testing "and beats it whatever else was declared in between"
    (let [other {:tags [:editor.python] :command "pylsp"}]
      (is (= user (registry/for-tags (declare-all [core] [plugin] [user] [other])
                                     #{:editor.typescript}))))))

(deftest a-declaration-arriving-twice-counts-as-the-later-one
  ;; deploy/core/User is both the user directory and an installed plugin, so
  ;; user.behaviors is read twice — once at the plugin stage and once at the
  ;; user stage. Keeping the first arrival parked the user's entry ahead of the
  ;; plugin it was written to override, and clojure-lsp won against a
  ;; user.behaviors that said otherwise.
  (is (= user (registry/for-tags (declare-all [user] [core] [plugin] [user])
                                 #{:editor.typescript})))
  (testing "a plugin re-declaring does overtake an earlier user entry"
    ;; Which is the same rule, and why it is stated as position-by-last-arrival
    ;; rather than as a ranking of who declared it.
    (is (= plugin (registry/for-tags (declare-all [core] [user] [plugin])
                                     #{:editor.typescript})))))

(deftest reloading-lands-where-it-already-was
  ;; lt.objs.settings re-raises :object.instant on every behavior reload, so
  ;; every declaration arrives again in the same order.
  (let [order [[user] [core] [plugin] [user]]
        loaded (reduce registry/add [] order)
        reloaded (reduce registry/add loaded order)]
    (is (= loaded reloaded))
    (is (= user (registry/for-tags reloaded #{:editor.typescript})))))

(deftest a-declaration-may-carry-several-entries
  (let [ts (assoc core :tags [:editor.typescript])
        tsx (assoc core :tags [:editor.tsx] :language-id "typescriptreact")]
    (is (= [ts tsx] (registry/add [] [ts tsx])))
    (is (= tsx (registry/for-tags [ts tsx] #{:editor.tsx})))))

;;*********************************************************
;; More than one server for a language
;;*********************************************************

(def biome
  {:tags [:editor.typescript]
   :language-id "typescript"
   :root ["biome.json"]
   :command "biome"
   :args ["lsp-proxy"]})

(deftest a-second-server-runs-beside-the-first
  ;; The case this exists for: a type checker and a linter answering for one
  ;; language. Neither replaces the other.
  (let [table (declare-all [core] [biome])]
    (is (= [core biome] (registry/all-for-tags table #{:editor.typescript})))
    ;; And for the surfaces where only one can answer, the later one.
    (is (= biome (registry/for-tags table #{:editor.typescript})))))

(deftest pointing-at-a-path-replaces-rather-than-stacks
  ;; `user` is the same executable named absolutely, which is the ordinary way
  ;; to override a declaration — a version manager's shim, a project-local
  ;; build. Starting the same server twice because one entry spelled it out is
  ;; exactly what the id is for.
  (is (= [user] (registry/all-for-tags (declare-all [core] [plugin] [user])
                                       #{:editor.typescript})))
  (testing "and keeps the place the first declaration had"
    ;; Otherwise overriding a server's arguments would also move it past the
    ;; linter declared after it, and quietly hand it the formatting.
    (is (= [user biome] (registry/all-for-tags (declare-all [core] [biome] [user])
                                               #{:editor.typescript})))))

(deftest an-explicit-id-replaces-a-different-executable
  (let [swapped (assoc biome :command "eslint-lsp" :id "biome")]
    (is (= [core swapped] (registry/all-for-tags (declare-all [core] [biome] [swapped])
                                                 #{:editor.typescript})))))

(deftest a-server-is-turned-off-by-declaring-it-with-nothing-to-run
  (let [off {:tags [:editor.typescript] :id "biome" :command nil}]
    (is (= [core] (registry/all-for-tags (declare-all [core] [biome] [off])
                                         #{:editor.typescript})))
    (testing "and the last one still standing is the one that answers"
      (is (= core (registry/for-tags (declare-all [core] [biome] [off])
                                     #{:editor.typescript}))))))
