(ns lt.objs.clients.lsp.sync-test
  "Synchronisation is the layer that decides whether LSP reads as working. A
  protocol bug announces itself; a sync bug does not — the server answers
  confidently about a document one keystroke out of date, and the diagnostics
  land on the wrong lines.

  So the arithmetic is pure and it is tested here: URIs that survive a space in
  a path, changes translated the way CodeMirror actually reports them, and the
  server's declared sync kind respected rather than assumed."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [lt.objs.clients.lsp.sync :as sync]))

;;*********************************************************
;; URIs
;;*********************************************************

(deftest a-path-becomes-a-file-uri
  (is (= "file:///home/user/a.ts" (sync/->uri "/home/user/a.ts"))))

(deftest separators-survive-encoding
  (testing "encoding the whole path would turn every slash into %2F"
    (is (= "file:///home/user/a.ts" (sync/->uri "/home/user/a.ts")))
    (is (not (re-find #"%2F" (sync/->uri "/home/user/a.ts"))))))

(deftest awkward-characters-are-encoded
  (testing "a space in a path is common and breaks a server that gets it raw"
    (is (= "file:///home/my%20project/a.ts" (sync/->uri "/home/my project/a.ts"))))
  (testing "and so is a hash, which would otherwise start a fragment"
    (is (= "file:///tmp/a%23b.ts" (sync/->uri "/tmp/a#b.ts")))))

(deftest uris-round-trip
  (doseq [path ["/home/user/a.ts"
                "/home/my project/a.ts"
                "/tmp/a#b.ts"
                "/tmp/ünïcode/日本語.ts"]]
    (is (= path (sync/uri->path (sync/->uri path)))
        (str "round trip of " path))))

(deftest a-windows-path-gets-its-leading-slash
  (testing "a drive letter is not a slash, and the URI needs one"
    (is (= "file:///C%3A/code/a.ts" (sync/->uri "C:\\code\\a.ts")))))

;;*********************************************************
;; Positions
;;*********************************************************

(deftest positions-are-a-rename-not-a-conversion
  (testing "both count lines from zero and characters in UTF-16 units"
    (is (= {:line 4 :character 12} (sync/->position {:line 4 :ch 12})))
    (is (= {:line 4 :ch 12} (sync/->loc {:line 4 :character 12}))))

  (testing "line zero and character zero are real values, not absent ones"
    (is (= {:line 0 :character 0} (sync/->position {:line 0 :ch 0})))))

(deftest a-range-yields-its-start
  (is (= {:line 2 :ch 5}
         (sync/range->loc {:start {:line 2 :character 5}
                           :end {:line 2 :character 9}}))))

;;*********************************************************
;; Changes
;;*********************************************************

(deftest a-codemirror-change-translates-directly
  (testing "typing one character"
    (is (= {:range {:start {:line 3 :character 7} :end {:line 3 :character 7}}
            :text "x"}
           (sync/change->content-change {:from {:line 3 :ch 7}
                                         :to {:line 3 :ch 7}
                                         :text ["x"]}))))

  (testing "deleting a selection, which is an empty insert over a range"
    (is (= {:range {:start {:line 1 :character 0} :end {:line 2 :character 4}}
            :text ""}
           (sync/change->content-change {:from {:line 1 :ch 0}
                                         :to {:line 2 :ch 4}
                                         :text [""]}))))

  (testing "a multi-line paste, which CodeMirror reports as lines"
    (is (= {:range {:start {:line 0 :character 0} :end {:line 0 :character 0}}
            :text "one\ntwo\nthree"}
           (sync/change->content-change {:from {:line 0 :ch 0}
                                         :to {:line 0 :ch 0}
                                         :text ["one" "two" "three"]})))))

(deftest the-server-decides-full-or-incremental
  (let [changes [{:from {:line 0 :ch 0} :to {:line 0 :ch 0} :text ["a"]}]
        whole "a whole document"]

    (testing "incremental, when the server asked for it"
      (is (= [{:range {:start {:line 0 :character 0} :end {:line 0 :character 0}} :text "a"}]
             (sync/content-changes 2 changes whole))))

    (testing "full text, when it asked for that"
      (is (= [{:text whole}] (sync/content-changes 1 changes whole))))

    (testing "and full text is the fallback, because every server understands it"
      (is (= [{:text whole}] (sync/content-changes nil changes whole))))))

(deftest sync-kind-reads-both-shapes
  (testing "the bare number, from before the field grew options"
    (is (= 2 (sync/sync-kind {:textDocumentSync 2})))
    (is (= 1 (sync/sync-kind {:textDocumentSync 1}))))

  (testing "the map, which is what a current server sends"
    (is (= 2 (sync/sync-kind {:textDocumentSync {:openClose true :change 2}}))))

  (testing "and full text when the server said nothing"
    (is (= 1 (sync/sync-kind {})))))

(deftest open-close-can-be-declined
  (testing "a server that says no is working off the filesystem"
    (is (false? (sync/open-close? {:textDocumentSync {:openClose false}}))))
  (testing "and absent means yes"
    (is (true? (sync/open-close? {:textDocumentSync {:change 2}})))
    (is (true? (sync/open-close? {})))))

;;*********************************************************
;; Versions
;;*********************************************************

(deftest versions-start-at-one-and-increase
  (let [doc (sync/document "/a.ts" "typescript")]
    (is (= 1 (:version doc)))
    (is (= 2 (:version (sync/bump doc))))
    (is (= 4 (:version (-> doc sync/bump sync/bump sync/bump))))))

(deftest a-document-carries-its-uri-and-language
  (let [doc (sync/document "/home/user/a.ts" "typescript")]
    (is (= "file:///home/user/a.ts" (:uri doc)))
    (is (= "typescript" (:language-id doc)))
    (is (= "/home/user/a.ts" (:path doc)))))

(deftest a-response-for-an-older-version-is-stale
  (testing "this is what stops diagnostics being drawn at offsets that moved"
    (let [doc (-> (sync/document "/a.ts" "typescript") sync/bump sync/bump)]
      (is (= 3 (:version doc)))
      (is (false? (sync/stale? doc 3)) "the current version is what to draw")
      (is (true? (sync/stale? doc 2)) "one edit behind")
      (is (true? (sync/stale? doc 1))))))
