(defproject lein-light "0.3.4"
  :description "Provide uberjar to start headless repl with LT middleware"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  ;; Leiningen is a dependency rather than a tool here: this uberjar exists to
  ;; read a project's project.clj, resolve its classpath and start a headless
  ;; nREPL inside it, and Leiningen is the thing that can do that.
  ;;
  ;; 2.5.2 was what upstream pinned in 2015, and the artifact built from it
  ;; does not start on any JDK Light Table supports — dynapath, pulled in
  ;; underneath it, reads sun.misc.Launcher$ExtClassLoader at class-init time
  ;; and that has not existed since JDK 9. See ../VENDORED.md.
  ;;
  ;; fs 1.3.3 is gone too, and was used for two calls; lighttable.nrepl.fs
  ;; already reimplements them.
  :dependencies [[org.clojure/clojure "1.12.3"]
                 [leiningen "2.12.0"]]

  :uberjar-name "lein-light-standalone.jar"
  :profiles {:uberjar {:aot :all}}
  :source-paths ["src/"]
  :jvm-opts ["-Xmx1g"]
  :main leiningen.light-nrepl)
