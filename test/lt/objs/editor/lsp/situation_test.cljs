(ns lt.objs.editor.lsp.situation-test
  "What the language server situation means, tested by asking.

  Three reports of \"toggle docs isn't working\" and one of \"I can't tell if
  the language server is doing anything\" all ended here, and the last of them
  found the same ordering mistake written out twice — once in the sentence
  `:lsp.status` prints and once in the statusbar's dot. Both are one function
  now, and this is the file that says which order it is in."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [lt.objs.editor.lsp.situation :as situation]))

(def ^:private answering
  "One server, installed, connected, past its handshake."
  {:path "/p/a.ts"
   :language-id "typescript"
   :command "vtsls"
   :markers ["tsconfig.json"]
   :root "/p"
   :found "/p/node_modules/.bin/vtsls"
   :connected? true
   :ready? true
   :diagnostics 0
   :servers [{:command "vtsls" :language-id "typescript" :root "/p"}]
   :connections 1})

(deftest the-six-phases-are-what-they-say
  (is (= :no-file (situation/phase (assoc answering :path nil))))
  (is (= :unconfigured (situation/phase (assoc answering :language-id nil))))
  (is (= :ready (situation/phase answering)))
  (is (= :starting (situation/phase (assoc answering :ready? false))))
  (is (= :missing (situation/phase (assoc answering :connected? false :ready? false
                                       :found nil))))
  (is (= :idle (situation/phase (assoc answering :connected? false :ready? false)))))

(deftest a-file-with-no-server-declared-is-not-a-problem
  ;; Most files. Said plainly rather than reported, and drawn as nothing at all.
  (let [s (assoc answering :language-id nil)]
    (is (= "No language server is configured for this file type." (situation/line s)))
    (is (nil? (situation/indicator s)) "nothing to draw, so nothing is drawn"))
  (is (nil? (situation/indicator (assoc answering :path nil)))))

(deftest a-second-declared-server-cannot-describe-a-live-connection
  ;; The bug this file exists for. vtsls is connected and answering; biome is
  ;; also declared for TypeScript and is not installed, so it is `found` nil
  ;; and it is the *last* declared, which is what the singular keys describe.
  (let [two (merge answering
                   {:command "biome"
                    :markers ["biome.json"]
                    :root nil
                    :found nil
                    :servers [{:command "vtsls" :language-id "typescript" :root "/p"}
                              {:command "biome" :language-id "typescript" :root nil}]
                    :connections 1})]
    (is (= :ready (situation/phase two))
        "connected beats declared, or the sentence describes the wrong server")
    (is (= "Connected to 1 of 2 servers (vtsls, biome) — 0 diagnostics on screen"
           (situation/line two))
        "both are named, because \"connected\" alone answers the wrong question")
    (is (= :finished (:status (situation/indicator two))))
    (testing "the sentence it used to print"
      (is (not= "No project root above /p/a.ts — looked for biome.json"
                (situation/line two))))))

(deftest starting-is-distinguishable-from-absent
  ;; A server takes seconds to minutes to index a project, and for all of them
  ;; it answers nothing. That is the state a person mistakes for a broken build.
  (let [s (assoc answering :ready? false)]
    (is (= "Starting /p/node_modules/.bin/vtsls …" (situation/line s)))
    (is (= :connecting (:status (situation/indicator s)))))
  (testing "and it says the command when the binary was never located"
    (is (= "Starting vtsls …"
           (situation/line (assoc answering :ready? false :found nil))))))

(deftest missing-splits-into-the-two-things-to-go-and-do
  (let [absent (assoc answering :connected? false :ready? false :found nil)]
    (is (= "No project root above /p/a.ts — looked for tsconfig.json"
           (situation/line (assoc absent :root nil)))
        "stopped at the root: the marker files are what to add")
    (is (= (str "No vtsls in /p/node_modules/.bin, or on PATH. "
                "Install it in the project, or globally.")
           (situation/line absent))
        "stopped at the binary: installing it is what to do"))
  (is (= :lost (:status (situation/indicator (assoc answering :connected? false
                                                 :ready? false :found nil))))))

(deftest installed-and-unconnected-is-its-own-answer
  (let [s (assoc answering :connected? false :ready? false)]
    (is (= "Found /p/node_modules/.bin/vtsls, but this editor is not connected to it."
           (situation/line s)))
    (is (= :queued (:status (situation/indicator s))))))

(deftest a-diagnostic-count-is-pluralised-and-carried
  (is (= "Connected to /p/node_modules/.bin/vtsls — 1 diagnostic on screen"
         (situation/line (assoc answering :diagnostics 1))))
  (is (= "Connected to /p/node_modules/.bin/vtsls — 3 diagnostics on screen"
         (situation/line (assoc answering :diagnostics 3))))
  (is (= 3 (:diagnostics (situation/indicator (assoc answering :diagnostics 3))))
      "the bar draws the count beside the name, so it has to reach it"))
