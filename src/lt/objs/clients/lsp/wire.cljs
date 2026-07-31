(ns lt.objs.clients.lsp.wire
  "The Language Server Protocol's framing: bytes in, messages out.

  LSP is JSON-RPC over a stream, framed the way HTTP frames a body:

      Content-Length: 42\\r\\n
      \\r\\n
      {\"jsonrpc\":\"2.0\", ...}

  This namespace is deliberately the only pure one in the LSP client. It knows
  nothing about processes, editors or Light Table, so it can be tested as a
  function of a byte vector — which matters, because framing is where the
  awkward cases live and they are all invisible in a happy-path integration
  test:

  - A header split across two reads, or a body split across five. A process's
    stdout arrives in whatever chunks the OS felt like.
  - **`Content-Length` counts bytes, not characters.** A message containing
    one `\\u00e9` is one byte longer than its string length, and slicing by
    characters silently desynchronises the stream from there on — every
    subsequent message is garbage, and the cause is several messages back.
    This is why `lt.util.bridge.processes.onStdoutBytes` exists.
  - Several messages arriving in one chunk, which is normal when a server
    publishes diagnostics for a project it has just finished loading.

  Everything here works in bytes and decodes only a complete body."
  (:require [clojure.string :as string]
            [goog :as goog]))

(defn- index-of-separator
  "Where the header block ends in `bytes`, or nil.

  A byte scan rather than decoding and searching for a string: the headers are
  ASCII, but whatever follows them is not necessarily, and decoding a partial
  body to find a header boundary is how the multi-byte bug gets in."
  [^js bytes]
  (let [n (.-length bytes)]
    (loop [i 0]
      (cond
        (> (+ i 4) n) nil
        (and (= 13 (aget bytes i))
             (= 10 (aget bytes (+ i 1)))
             (= 13 (aget bytes (+ i 2)))
             (= 10 (aget bytes (+ i 3)))) i
        :else (recur (inc i))))))

(defn- decode-ascii
  "The header block, which the protocol requires to be ASCII.

  Built a character at a time rather than through a TextDecoder: this runs on
  the header bytes only, and the point of not decoding is that the bytes after
  them may not be ASCII at all."
  [^js bytes from to]
  (let [out (array)]
    (loop [i from]
      (if (>= i to)
        (.join out "")
        (do (.push out (js/String.fromCharCode (aget bytes i)))
            (recur (inc i)))))))

(defn parse-headers
  "The header block as a map of lower-cased name to value.

  Lower-cased because the protocol does not promise a case, and a server
  sending `content-length` is not wrong."
  [text]
  (into {}
        (keep (fn [line]
                (let [idx (.indexOf line ":")]
                  (when (pos? idx)
                    [(string/lower-case (string/trim (subs line 0 idx)))
                     (string/trim (subs line (inc idx)))])))
              (string/split text #"\r\n"))))

(defn- content-length [headers]
  (when-let [raw (get headers "content-length")]
    (let [n (js/parseInt raw 10)]
      (when-not (js/isNaN n) n))))

(defn- concat-bytes [^js a ^js b]
  (cond
    (zero? (.-length a)) b
    (zero? (.-length b)) a
    :else (let [out (js/Uint8Array. (+ (.-length a) (.-length b)))]
            (.set out a 0)
            (.set out b (.-length a))
            out)))

(def empty-buffer (js/Uint8Array. 0))

(def ^:private identifier?
  "Whether a JSON key is a plain identifier, and therefore safe as a keyword."
  #(re-matches #"[A-Za-z_$][A-Za-z0-9_$]*" %))

(defn ->clj
  "A parsed JSON value as ClojureScript, keywordising only the keys it is safe
  to keywordise.

  `(js->clj … :keywordize-keys true)` is the obvious thing and is wrong for
  this protocol. Almost every key in LSP is a fixed camelCase name and reads
  better as a keyword — but not all of them. `WorkspaceEdit.changes` is keyed
  by *document URI*, and `file:///src/probe.clj` becomes a keyword whose
  namespace is `file:` and whose name is the rest of the path; what comes back
  out is `\"file:\"`. A rename could not name a single file it was renaming in.

  So the rule is a property of the key rather than of the caller: an
  identifier becomes a keyword, anything else stays the string it was. Nothing
  else in the protocol changes shape, which is why this is one function here
  instead of a special case in whichever surface trips over it next — and the
  next one would be `didChangeWatchedFiles`, then `codeAction.changes`, then
  the one nobody predicted."
  [x]
  (cond
    (array? x) (mapv ->clj x)
    (identical? "object" (goog/typeOf x))
    (if (nil? x)
      nil
      (persistent!
       (reduce (fn [m k]
                 (assoc! m (if (identifier? k) (keyword k) k) (->clj (aget x k))))
               (transient {})
               (js/Object.keys x))))
    :else x))

(defn feed
  "Append `chunk` to `buffer` and take every complete message out of it.

  Returns `{:buffer :messages :errors}`. The buffer is what is left over and
  must be passed back next time; the messages are parsed JSON as ClojureScript
  data with keyword keys.

  A message whose body is not valid JSON is reported in `:errors` and skipped
  rather than throwing: the stream is still correctly framed after it, so one
  malformed message should cost itself and not the connection. A header block
  with no usable `Content-Length` is different — there is no way to know where
  the body ends, so the stream cannot be resynchronised and the buffer is
  dropped."
  [buffer chunk]
  (loop [^js buf (concat-bytes buffer chunk)
         messages []
         errors []]
    (if-let [sep (index-of-separator buf)]
      (let [headers (parse-headers (decode-ascii buf 0 sep))
            len (content-length headers)
            body-start (+ sep 4)]
        (cond
          (nil? len)
          {:buffer empty-buffer
           :messages messages
           :errors (conj errors {:error :no-content-length :headers headers})}

          ;; The body has not all arrived yet. Keep everything and wait.
          (< (.-length buf) (+ body-start len))
          {:buffer buf :messages messages :errors errors}

          :else
          (let [body (.subarray buf body-start (+ body-start len))
                text (.decode (js/TextDecoder. "utf-8") body)
                parsed (try (->clj (.parse js/JSON text))
                            (catch :default e {::parse-error (str e)}))
                rest-buf (.slice buf (+ body-start len))]
            (if (and (map? parsed) (::parse-error parsed))
              (recur rest-buf messages (conj errors {:error :bad-json
                                                     :detail (::parse-error parsed)
                                                     :body text}))
              (recur rest-buf (conj messages parsed) errors)))))
      {:buffer buf :messages messages :errors errors})))

(defn encode-string
  "A message as a framed string, ready for `ProcessHandle.write`.

  The length is the *encoded byte* count, and that is the part that must never
  be taken from the string: computing it with `count` would be right only for
  ASCII and would corrupt every message after the first one that was not.

  Returning a string is safe despite that. The bridge's `write` takes a string
  because a handle cannot carry a Buffer across, and node writes it as UTF-8 —
  reproducing exactly the bytes whose length was just declared."
  [message]
  (let [json (.stringify js/JSON (clj->js message))
        n (.-length (.encode (js/TextEncoder.) json))]
    (str "Content-Length: " n "\r\n\r\n" json)))

(defn encode
  "The same framing as [[encode-string]], as bytes."
  [message]
  (.encode (js/TextEncoder.) (encode-string message)))

;;*********************************************************
;; Messages
;;*********************************************************

(defn request
  "A JSON-RPC request, which expects a response with the same `id`."
  [id method params]
  {:jsonrpc "2.0" :id id :method method :params (or params {})})

(defn notification
  "A JSON-RPC notification, which expects no response and must not carry an id."
  [method params]
  {:jsonrpc "2.0" :method method :params (or params {})})

(defn response
  "A reply to a request the server sent us."
  [id result]
  {:jsonrpc "2.0" :id id :result result})

(defn error-response
  "A refusal. `code` is a JSON-RPC error code; -32601 is method-not-found,
  which is the honest answer to a server-initiated request we do not implement."
  [id code message]
  {:jsonrpc "2.0" :id id :error {:code code :message message}})

(defn message-kind
  "What a received message is, which decides who handles it.

  The three are distinguished by which fields are present, not by anything the
  message says about itself: a response has an id and no method, a request has
  both, a notification has a method and no id."
  [{:keys [id method]}]
  (cond
    (and method (some? id)) :request
    method :notification
    (some? id) :response
    :else :unknown))
