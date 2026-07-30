;; Light Table is built with shadow-cljs; see shadow-cljs.edn and script/build.sh.
;;
;; This file remains only to generate the plugin API documentation published at
;; lighttable.github.io, because codox is a Leiningen plugin with no shadow-cljs
;; equivalent. It builds nothing.
;;
;;     lein codox
;;
(defproject lighttable "0.9.0"
  :description "Light Table is a next generation code editor that connects you to your creation with instant feedback. Light Table is very customizable and can display anything a Chromium browser can."
  :url "http://www.lighttable.com/"

  :dependencies [[org.clojure/clojure "1.12.5"]
                 [org.clojure/clojurescript "1.12.145"
                  :exclusions [org.apache.ant/ant]]]

  :source-paths ["src"]

  ;; codox reaches into ClassLoader internals, which the module system has
  ;; blocked by default since JDK 16.
  :jvm-opts ["--add-opens" "java.base/java.lang=ALL-UNNAMED"]

  :plugins [[lein-codox "0.10.7"]]

  :codox {:language :clojurescript
          :project {:name "LightTable"}
          :output-path "codox"
          :doc-paths [] ;; Disable including doc/
          :namespaces [lt.macros lt.object lt.objs.command lt.objs.editor
                       lt.objs.editor.pool lt.objs.files lt.objs.notifos]
          :source-uri "https://github.com/LightTable/LightTable/blob/{version}/{filepath}#L{line}"
          ;; Be explicit that undocumented public fns should be documented
          :metadata {:doc "TODO: Add docstring"
                     :doc/format :markdown}})
