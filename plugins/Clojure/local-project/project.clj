;; The project a Light Table REPL runs in when the file being evaluated has no
;; project.clj above it. It exists only to give Leiningen something to read;
;; the middleware's own dependencies are added on the command line by
;; lt.plugins.clojure/lein-args.
;;
;; Clojure 1.5.1 upstream, which predates the reader conditionals
;; lighttable.nrepl.eval reads with.
(defproject local-client "0.0.1"
  :description "A local Light Table project"
  :dependencies [[org.clojure/clojure "1.12.3"]])
