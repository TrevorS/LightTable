(defproject lighttable "0.9.0"
  :description "Light Table is a next generation code editor that connects you to your creation with instant feedback. Light Table is very customizable and can display anything a Chromium browser can."
  :url "http://www.lighttable.com/"
  :dependencies [[org.clojure/clojure "1.12.5"]
                 ;; singultus is vendored under src/singultus; see its README
                 [org.clojure/clojurescript "1.12.145"
                  :exclusions [org.apache.ant/ant]]
                 [javax.xml.bind/jaxb-api "2.4.0-b180830.0359"]]

  :jvm-opts ["-Xmx1g" "-XX:+UseG1GC"] ; cljsbuild eats memory
  :cljsbuild {:builds [{:id "app"
                        :source-paths ["src"]
                        :compiler {:optimizations :simple
                                   ;; The shim emits `var process = {env:{}}` into the bundle, which
                                   ;; clobbers Electron's real `process` in the renderer and leaves
                                   ;; js/process.platform undefined. See lt.objs.platform/platform.
                                   :process-shim false
                                   :source-map "deploy/core/lighttable/bootstrap.js.map"
                                   :output-to "deploy/core/lighttable/bootstrap.js"
                                   :output-dir "deploy/core/lighttable/cljs/"
                                   :pretty-print true}}
                       {:id "cljsdeps"
                        :source-paths ["src-cljsdeps"]
                        :compiler {:optimizations :simple
                                   ;; This bundle is global.eval'd into the worker thread, so the
                                   ;; shim would clobber the worker's process and with it
                                   ;; process.send, which is how it talks back to the app.
                                   :process-shim false
                                   :output-to "deploy/core/node_modules/clojurescript/cljsDeps.js"
                                   :output-dir "deploy/core/node_modules/clojurescript/cljsDeps/"
                                   :pretty-print true}}]}

  :profiles {:doc {:dependencies [[org.clojure/clojure "1.12.5"]
                                  [org.clojure/clojurescript "1.12.145"
                                   :exclusions [org.apache.ant/ant]]]}}
  :plugins [[lein-cljsbuild "1.1.8"]
            [lein-codox "0.10.7"]
            [lein-cloverage "1.2.2"]]
  :codox {:language :clojurescript
          :project {:name "LightTable"}
          :output-path "codox"
          :doc-paths [] ;; Disable including doc/
          :namespaces [lt.macros lt.object lt.objs.command lt.objs.editor
                       lt.objs.editor.pool lt.objs.files lt.objs.notifos]
          :source-uri "https://github.com/LightTable/LightTable/blob/{version}/{filepath}#L{line}"
          ;; Be explicit that undocumented public fns should be documented
          :metadata {:doc "TODO: Add docstring"
                     :doc/format :markdown}}
  :source-paths ["src/"])
