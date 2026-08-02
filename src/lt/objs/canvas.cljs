(ns lt.objs.canvas
  "Provide canvas object which contains the primary div of the UI: #canvas.
  Children divs are #multi (tabs), #side, #right-bar and #bottombar"
  (:refer-clojure :exclude [rem])
  (:require [lt.object :as object]
            [lt.objs.context :as ctx]
            [lt.util.dom :refer [$ append] :as dom])
  (:require-macros [lt.macros :refer [behavior]]))

;;*********************************************************
;; Object
;;*********************************************************

(object/object* ::canvas
                ;; The one div everything else is appended into. No handlers,
                ;; nothing to draw, and it never changes — so hiccup straight
                ;; through `lt.object/->dom` is the whole of it, and the `defui`
                ;; that used to wrap it added a macro and a function call.
                :init (fn [_] [:div#canvas]))

(def canvas (object/create ::canvas))
(append ($ "#wrapper") (object/->content canvas))

(defn add! [obj & [position?]]
  (append (object/->content canvas) (object/->content obj))
  (object/raise obj :show))

;;*********************************************************
;; Behaviors
;;*********************************************************

(behavior ::append-canvas
          :triggers #{:show}
          :reaction (fn [app]
                      (dom/css ($ :#loader) {:opacity 0})
                      (dom/css ($ :#wrapper) {:opacity 1})))

