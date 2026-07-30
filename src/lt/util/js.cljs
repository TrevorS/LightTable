(ns lt.util.js
  "Provide misc Javascript related functions.")

(defn fetch-text
  "GET `url` and call `cb` with the response body as a string.

  On a network failure or a non-2xx response `cb` is not called and `on-error`
  is invoked with the error instead, defaulting to reporting it on the console.
  Pass an explicit `on-error` for requests that are allowed to fail quietly,
  such as background checks that simply need the network to be up."
  ([url cb]
   ;; console is referenced through js/ to avoid a cycle: it depends on this ns.
   (fetch-text url cb (fn [e] (js/lt.objs.console.error e))))
  ([url cb on-error]
   (-> (js/fetch url)
       (.then (fn [res]
                (if (.-ok res)
                  (.text res)
                  (throw (js/Error. (str "GET " url " failed: "
                                         (.-status res) " " (.-statusText res)))))))
       (.then cb)
       (.catch on-error))))

(defn every
  "Execute `func` every `ms` milliseconds."
  [ms func]
  (js/setInterval func ms))

(defn wait
  "Wait `ms` milliseconds before executing `func`."
  [ms func]
  (js/setTimeout func ms))

(defn now
  "Return the current time in milliseconds starting from the Unix epoch."
  []
  (.getTime (js/Date.)))

(defn toggler
  "If `cur` equals `op` then return `op2`. Otherwise return `op`."
  [cur op op2]
  (if (= cur op)
    op2
    op))

(defn debounce
  "Debounce execution of `func` with a delay of `ts` milliseconds.

  In other words, returns a new function that executes `func` only once
  after `ts` milliseconds regardless the number of times the new function is called
  during the `ts` milliseconds.

  See [[throttle]]."
  [ts func]
  ;; For js/Cowboy, see deploy/core/lighttable/throttle.js
  (.debounce js/Cowboy ts func))

(defn throttle
  "Throttle execution of `func` with a delay of `ts` milliseconds.

  In other words, returns a new function that executes `func` no more than
  once every `ts` milliseconds.

  See [[debounce]]."
  [ts func]
  ;; For js/Cowboy, see deploy/core/lighttable/throttle.js
  (.throttle js/Cowboy ts func))

(defn ->clj
  "Convert JSON `data` to ClojureScript with keywords enabled.

  See [js->clj](http://cljs.github.io/api/cljs.core/js-GTclj)."
  [data]
  (js->clj data :keywordize-keys true))

(def entities
  "Map of entities, such as `&`, to their corresponding character reference."
  {"&" "&amp;"
   "<" "&lt;"
   ">" "&gt;"
   "\"" "&quot;"
   "'" "&#39;"
   "/" "&#x2F;"})

(defn escape
  "Replace characters in `str` that are in [[entities]] with their escaped equivalent."
  [str]
  (when str
    (.replace str (js/RegExp. "[&<>\"'/]" "g") (fn [s]
                                                 (entities s)))))
