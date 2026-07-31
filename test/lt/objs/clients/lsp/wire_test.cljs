(ns lt.objs.clients.lsp.wire-test
  "Framing is where an LSP client actually breaks, and it breaks quietly: a
  desynchronised stream turns every subsequent message into garbage, several
  messages after the one that caused it.

  These are the cases that do it. None of them is exercised by a happy-path
  integration test, because in a happy path the OS happens to hand you one
  whole message per read."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [lt.objs.clients.lsp.wire :as wire]))

(defn- bytes-of [s]
  (.encode (js/TextEncoder.) s))

(defn- framed
  "A message the way a server would put it on the wire."
  [body]
  (str "Content-Length: " (.-length (bytes-of body)) "\r\n\r\n" body))

(defn- feed-all
  "Feed `chunks` in order through a fresh buffer, collecting everything."
  [chunks]
  (reduce (fn [acc chunk]
            (let [res (wire/feed (:buffer acc) (bytes-of chunk))]
              {:buffer (:buffer res)
               :messages (into (:messages acc) (:messages res))
               :errors (into (:errors acc) (:errors res))}))
          {:buffer wire/empty-buffer :messages [] :errors []}
          chunks))

;;*********************************************************
;; Reading
;;*********************************************************

(deftest reads-one-whole-message
  (let [res (feed-all [(framed "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}")])]
    (is (= [{:jsonrpc "2.0" :id 1 :result {:ok true}}] (:messages res)))
    (is (zero? (.-length (:buffer res))) "nothing left over")
    (is (empty? (:errors res)))))

(deftest reads-several-messages-from-one-chunk
  (testing "a server that has just loaded a project publishes in a burst"
    (let [res (feed-all [(str (framed "{\"id\":1,\"result\":\"a\"}")
                              (framed "{\"id\":2,\"result\":\"b\"}")
                              (framed "{\"id\":3,\"result\":\"c\"}"))])]
      (is (= [1 2 3] (mapv :id (:messages res))))
      (is (zero? (.-length (:buffer res)))))))

(deftest a-header-split-across-reads
  (testing "the chunk boundary falls inside Content-Length"
    (let [whole (framed "{\"id\":7,\"result\":\"split\"}")
          res (feed-all [(subs whole 0 10) (subs whole 10)])]
      (is (= [7] (mapv :id (:messages res)))))))

(deftest a-body-split-across-many-reads
  (let [whole (framed "{\"id\":9,\"result\":\"abcdefghijklmnop\"}")
        chunks (map #(subs whole % (min (count whole) (+ % 5)))
                    (range 0 (count whole) 5))
        res (feed-all chunks)]
    (is (= [9] (mapv :id (:messages res))))
    (is (= "abcdefghijklmnop" (:result (first (:messages res)))))))

(deftest an-incomplete-message-is-kept-not-lost
  (let [whole (framed "{\"id\":4,\"result\":\"later\"}")
        res (wire/feed wire/empty-buffer (bytes-of (subs whole 0 (- (count whole) 3))))]
    (is (empty? (:messages res)))
    (is (pos? (.-length (:buffer res))) "the partial message waits in the buffer")
    (let [done (wire/feed (:buffer res) (bytes-of (subs whole (- (count whole) 3))))]
      (is (= [4] (mapv :id (:messages done)))))))

;;*********************************************************
;; Bytes, not characters
;;*********************************************************

(deftest content-length-is-bytes-not-characters
  (testing "a body whose byte length exceeds its character count"
    (let [body "{\"id\":1,\"result\":\"héllo wörld — ünïcode\"}"
          res (feed-all [(framed body)])]
      (is (> (.-length (bytes-of body)) (count body))
          "the premise: this body is longer in bytes than in characters")
      (is (= "héllo wörld — ünïcode" (:result (first (:messages res))))
          "slicing by characters would truncate the body and desynchronise")
      (is (zero? (.-length (:buffer res)))
          "and would leave the tail of it in the buffer, corrupting the next read"))))

(deftest a-multi-byte-character-split-across-chunks
  (testing "the chunk boundary falls inside a single character's bytes"
    (let [body "{\"id\":2,\"result\":\"日本語\"}"
          whole (bytes-of (framed body))
          ;; Cut inside the three bytes of a Japanese character.
          cut (- (.-length whole) 5)
          first-half (.slice whole 0 cut)
          second-half (.slice whole cut)
          a (wire/feed wire/empty-buffer first-half)
          b (wire/feed (:buffer a) second-half)]
      (is (empty? (:messages a)) "not decodable yet, and must not be guessed at")
      (is (= "日本語" (:result (first (:messages b))))
          "decoding per chunk would have mangled the character"))))

(deftest emoji-survive
  (testing "four-byte characters, which are two UTF-16 units in a JS string"
    (let [res (feed-all [(framed "{\"id\":3,\"result\":\"ok 🎉 done\"}")])]
      (is (= "ok 🎉 done" (:result (first (:messages res))))))))

;;*********************************************************
;; Malformed input
;;*********************************************************

(deftest a-bad-body-costs-only-itself
  (testing "the stream is still framed correctly after it"
    (let [res (feed-all [(str (framed "{\"id\":1,\"result\":\"before\"}")
                              (framed "{not json at all}")
                              (framed "{\"id\":3,\"result\":\"after\"}"))])]
      (is (= [1 3] (mapv :id (:messages res)))
          "the messages either side arrive")
      (is (= 1 (count (:errors res))))
      (is (= :bad-json (:error (first (:errors res))))))))

(deftest a-missing-content-length-cannot-be-recovered-from
  (testing "there is no way to know where the body ends, so the buffer is dropped"
    (let [res (feed-all ["Content-Type: application/vscode-jsonrpc\r\n\r\n{\"id\":1}"])]
      (is (empty? (:messages res)))
      (is (= :no-content-length (:error (first (:errors res)))))
      (is (zero? (.-length (:buffer res)))
          "keeping it would mean reparsing the same broken header forever"))))

(deftest headers-are-case-insensitive
  (testing "the protocol does not promise a case and servers differ"
    (let [body "{\"id\":5,\"result\":\"ok\"}"
          res (feed-all [(str "content-length: " (.-length (bytes-of body)) "\r\n\r\n" body)])]
      (is (= [5] (mapv :id (:messages res)))))))

(deftest extra-headers-are-ignored
  (let [body "{\"id\":6,\"result\":\"ok\"}"
        res (feed-all [(str "Content-Length: " (.-length (bytes-of body)) "\r\n"
                            "Content-Type: application/vscode-jsonrpc; charset=utf-8\r\n\r\n"
                            body)])]
    (is (= [6] (mapv :id (:messages res))))))

;;*********************************************************
;; Writing
;;*********************************************************

(deftest encode-frames-what-feed-reads
  (testing "a round trip, which is the property that actually matters"
    (let [msg (wire/request 1 "textDocument/hover" {:textDocument {:uri "file:///a.ts"}})
          res (wire/feed wire/empty-buffer (wire/encode msg))]
      (is (= 1 (count (:messages res))))
      (is (= "textDocument/hover" (:method (first (:messages res)))))
      (is (= "file:///a.ts" (get-in (first (:messages res)) [:params :textDocument :uri]))))))

(deftest encode-counts-bytes
  (testing "a header whose length is the encoded size, not the string length"
    (let [encoded (wire/encode (wire/notification "x" {:s "üü"}))
          text (.decode (js/TextDecoder.) encoded)
          declared (js/parseInt (second (re-find #"Content-Length: (\d+)" text)) 10)
          body-start (+ 4 (.indexOf text "\r\n\r\n"))
          body (subs text body-start)]
      (is (= declared (.-length (.encode (js/TextEncoder.) body)))
          "the declared length matches the body in bytes")
      (is (not= declared (count body))
          "and differs from its length in characters, which is the trap"))))

(deftest encode-string-and-encode-agree
  (testing "the string form is what gets written, so it must frame identically"
    (let [msg (wire/notification "textDocument/didOpen" {:text "héllo 🎉"})
          from-string (wire/feed wire/empty-buffer
                                 (.encode (js/TextEncoder.) (wire/encode-string msg)))
          from-bytes (wire/feed wire/empty-buffer (wire/encode msg))]
      (is (= (:messages from-string) (:messages from-bytes)))
      (is (= "héllo 🎉" (get-in (first (:messages from-string)) [:params :text]))))))

(deftest encode-string-declares-byte-length
  (testing "writing it as UTF-8 must reproduce exactly that many bytes"
    (let [framed (wire/encode-string (wire/notification "x" {:s "日本語"}))
          declared (js/parseInt (second (re-find #"Content-Length: (\d+)" framed)) 10)
          body (subs framed (+ 4 (.indexOf framed "\r\n\r\n")))]
      (is (= declared (.-length (.encode (js/TextEncoder.) body))))
      (is (> declared (count body)) "and that is not the character count"))))

(deftest a-round-trip-of-many-messages
  (testing "encoding several and feeding them as one chunk"
    (let [msgs [(wire/request 1 "a" nil)
                (wire/notification "b" {:x 1})
                (wire/response 2 {:ok true})]
          joined (reduce (fn [acc m]
                           (let [enc (wire/encode m)
                                 out (js/Uint8Array. (+ (.-length acc) (.-length enc)))]
                             (.set out acc 0)
                             (.set out enc (.-length acc))
                             out))
                         wire/empty-buffer msgs)
          res (wire/feed wire/empty-buffer joined)]
      (is (= 3 (count (:messages res))))
      (is (= ["a" "b" nil] (mapv :method (:messages res)))))))

;;*********************************************************
;; Message shapes
;;*********************************************************

(deftest message-kind-distinguishes-by-shape
  (testing "which fields are present, not what the message claims to be"
    (is (= :request (wire/message-kind {:id 1 :method "m"})))
    (is (= :notification (wire/message-kind {:method "m"})))
    (is (= :response (wire/message-kind {:id 1 :result nil})))
    (is (= :unknown (wire/message-kind {:jsonrpc "2.0"}))))

  (testing "id zero is an id, which `if id` would get wrong"
    (is (= :response (wire/message-kind {:id 0 :result 1})))
    (is (= :request (wire/message-kind {:id 0 :method "m"})))))

(deftest a-notification-carries-no-id
  (testing "a server is entitled to reject one that does"
    (is (not (contains? (wire/notification "initialized" {}) :id)))))

;;*********************************************************
;; Keys that are not identifiers
;;*********************************************************

(deftest identifier-keys-become-keywords
  (is (= {:jsonrpc "2.0" :id 1 :method "textDocument/rename"}
         (wire/->clj #js {"jsonrpc" "2.0" "id" 1 "method" "textDocument/rename"}))))

(deftest uri-keys-stay-strings
  ;; The one shape in LSP keyed by arbitrary text. Keywordising it produced a
  ;; keyword whose namespace was "file:" and lost the path, so a rename could
  ;; not name the file it was renaming in.
  (let [edit (wire/->clj #js {"changes" #js {"file:///src/probe.clj"
                                             #js [#js {"newText" "plus"}]}})]
    (is (= ["file:///src/probe.clj"] (keys (:changes edit))))
    (is (= [{:newText "plus"}] (get-in edit [:changes "file:///src/probe.clj"])))))

(deftest nested-and-mixed
  (let [v (wire/->clj #js {"result" #js {"changes" #js {"file:///a.clj" #js []}
                                         "documentChanges" #js [#js {"textDocument" #js {"uri" "file:///a.clj"}}]}})]
    (is (= ["file:///a.clj"] (keys (get-in v [:result :changes]))))
    (is (= "file:///a.clj" (get-in v [:result :documentChanges 0 :textDocument :uri])))))

(deftest scalars-and-nil-survive
  (is (nil? (wire/->clj nil)))
  (is (= 3 (wire/->clj 3)))
  (is (= "x" (wire/->clj "x")))
  (is (= [1 "two" {:three 3}] (wire/->clj #js [1 "two" #js {"three" 3}]))))
