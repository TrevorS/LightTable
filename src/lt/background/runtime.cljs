(ns lt.background.runtime
  "Support for code running on the worker thread.

  Background work used to be shipped across as text: the renderer stringified a
  compiled function and the worker `eval`'d it. That made the worker's contents
  invisible to the compiler, and it broke outright under any optimization that
  hoists values out of a function's body, since the hoisted names only existed
  back in the renderer's bundle.

  The worker is now compiled like everything else, and this namespace holds the
  two things it needs from its host: where Light Table is installed, and how to
  send a result home."
  (:require [clojure.string :as string]))

(defonce ^:private lt-path (atom nil))

(defn set-lt-path!
  "Record Light Table's install directory, sent by the renderer on startup."
  [path]
  (reset! lt-path path))

(defn require-lt
  "Require a module from the Light Table install, by a path relative to its
  root. Worker code cannot use a bare require for these: the worker's own
  location is not where they live."
  [relative-path]
  (js/require (str @lt-path "/" (string/replace relative-path #"^/" ""))))

(defn send!
  "Send `value` back to the object that asked for the work, as a `key` message.

  Values are read back on the other side, so anything printable survives. Pass
  `:raw` to send a JavaScript value through untouched."
  ([obj-id key value] (send! obj-id key value :clj))
  ([obj-id key value encoding]
   (.send js/process
          #js {:obj    obj-id
               :msg    (name key)
               :res    (if (= encoding :raw) value (pr-str value))
               :format (if (= encoding :raw) "json" "clj")})))
