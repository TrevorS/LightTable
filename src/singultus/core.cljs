(ns singultus.core
  (:require [singultus.compiler :as compiler]
            [singultus.util :as util]))

(def group-id (atom 0))

(defn raw
  "Parse `html-str` into DOM. Returns the node itself when the markup has a
  single root, otherwise a DocumentFragment holding all the roots.

  This replaces goog.dom/htmlToDocumentFragment, which the Closure Library
  deleted. Parsing happens inside a `template` element, whose contents are
  inert: scripts do not run and subresources are not fetched. Note that the
  markup is still parsed as HTML, so callers remain responsible for anything
  they interpolate into it."
  [html-str]
  (let [tpl (.createElement js/document "template")]
    (set! (.-innerHTML tpl) html-str)
    (let [content (.-content tpl)
          children (.-childNodes content)]
      (if (= 1 (.-length children))
        (.item children 0)
        content))))

(defn html [& tags]
  (let [res (map compiler/elem-factory tags)]
    (if (second res)
      res
      (first res))))

(def ^ {:doc "Alias for singultus.util/escape-html"}
  h util/escape-html)
