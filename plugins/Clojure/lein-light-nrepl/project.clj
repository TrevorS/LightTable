(defproject lein-light-nrepl "0.3.3"
  :description "nrepl client for Light Table clj and cljs eval."
  :url "https://github.com/TrevorS/LightTable/tree/develop/plugins/Clojure/lein-light-nrepl"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  ;; Everything moved forward a decade at once, because the artifact this used
  ;; to ship inside does not run on any JDK Light Table supports — see
  ;; ../VENDORED.md. tools.nrepl 0.2.10 became nrepl 1.x, which is a namespace
  ;; rename plus a real change to how evaluation is queued.
  ;;
  ;; nrepl is :provided: the REPL this middleware loads into already has one,
  ;; and two nREPLs on a classpath is the kind of problem that presents as a
  ;; session that will not clone.
  :dependencies [[org.clojure/clojure "1.12.3"]
                 [org.clojure/data.json "2.5.1"]
                 [org.clojure/tools.reader "1.5.2"]
                 [clj-stacktrace "0.2.8"]
                 [commons-io/commons-io "2.20.0"]
                 [clojure-complete "0.2.5"]
                 [org.clojure/clojurescript "1.12.42"]]

  :profiles {:provided {:dependencies [[nrepl/nrepl "1.7.0"]]}}

  :jvm-opts ["-Xmx1g"])
