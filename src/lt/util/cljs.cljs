(ns lt.util.cljs
  "Set up cljs and provide a few misc util fns.

  Extending `js/String` and `js/Array` is what makes `(\"key\" some-map)` and
  seq-over-array work in behaviors and plugin code, which is a great deal of
  Light Table's published API. The compiler warns about extending a base type
  and is right to in general; this build turns that one warning off, in
  shadow-cljs.edn, rather than pretend it is not happening.

  These read `js/String` and `js/Array` rather than the `js/global.String` they
  read before. Same objects — the window's — but named in a way that survives
  the window not having a `global`."
  (:refer-clojure :exclude [js->clj clj->js])
  (:require [clojure.string :as string]))

(set! *print-fn* (fn [x]
                   (when (and x (not= x "") (not= x "\n"))
                     (.log js/console (string/trim x)))))


;;NEEDED for latest CLJS
;(extend-type cljs.core/ChunkedCons
;  INext
;  (-next [this] (-seq (-rest this))))

(extend-type nil
  ISeqable
  (-seq [coll] nil))

;(extend-type cljs.core/RSeq
;  INext
;  (-next [this] (-seq (-rest this))))

(extend-type js/String
  IFn
  (-invoke
    ([this coll]
       (get coll (.toString this)))
    ([this coll not-found]
       (get coll (.toString this) not-found)))
  ISeqable
  (-seq [coll]
    (when (and coll (not (zero? (alength coll))))
                 (IndexedSeq. (js/String. coll) 0 nil))))

;; Extending a type with `IFn` makes ClojureScript emit `call` and `apply` onto
;; its prototype, so the extension above silently gave every string in the
;; window an `apply` method. (There was also an explicit
;; `(set! js/String.prototype.apply ...)` here doing the same job a second time
;; with a different signature. It is gone; this is the part that was not
;; obvious.)
;;
;; That breaks CodeMirror's simple-mode addon, which asks
;; `if (token && token.apply)` to decide whether a rule's token is a function
;; it should call. Every plain string token answered yes, the addon called a
;; string, and it threw — taking six language modes with it: dockerfile,
;; factor, handlebars, nsis, rust and wast. Rust is the one reachable from the
;; file-type table, so opening a `.rs` file threw instead of highlighting.
;;
;; Deleting it costs nothing. `("key" m)`, `(apply "key" [m])` and
;; `(map "key" ms)` all still work, because they dispatch through the protocol's
;; `-invoke` rather than through JS's `apply` — measured against the running
;; editor before removing it, and guarded by lt.util.cljs-test.
;;
;; `call` is left alone. Nothing has been observed to trip over it, and
;; removing a method nobody has complained about is how the next mystery gets
;; made.
(js-delete js/String.prototype "apply")

(extend-type js/Array
  ISeqable
  (-seq [coll]
    (when (and coll (not (zero? (alength coll))))
                 (IndexedSeq. coll 0 nil))))


(defn ->dottedkw [& args]
  (keyword (string/join "." (map name (filter identity args)))))

(defn js->clj [& args]
  (js/lt.objs.console.error "lt.util.cljs/js->clj is deprecated and will be removed in 0.9.0. Use js->clj instead")
  (apply cljs.core/js->clj args))

(defn str-contains? [str x]
  (> (.indexOf str x) -1))

(defn index-of [e coll]
  (first (keep-indexed #(if (= e %2) %1) coll)))
