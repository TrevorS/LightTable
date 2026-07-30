(ns lt.compat
  "Compatibility shims for plugins built against older versions of Light Table.

  Plugins ship precompiled JavaScript, so they are not recompiled when Light
  Table changes. That means a renamed namespace does not produce a build error
  somewhere downstream; it produces a `TypeError` in a user's editor, and only
  in whichever feature happened to touch it.

  Anything here exists to keep already-published plugins working. It should not
  be used by new code."
  (:require [singultus.core :as singultus]
            [singultus.binding :as binding]))

;; Light Table's hiccup library was crate, and became a fork named singultus
;; before this branch. Plugins published before that rename call crate.core and
;; crate.binding directly, so both flagship plugins — Clojure and Javascript —
;; break without this. Their compiled output invokes these as plain functions,
;; so aliasing the same function objects is all that is required.
(set! (.-crate js/window)
      #js {:core    #js {:html singultus/html
                         :raw  singultus/raw}
           :binding #js {:bound     binding/bound
                         :map-bound binding/map-bound
                         :subatom   binding/subatom
                         :computed  binding/computed}})
