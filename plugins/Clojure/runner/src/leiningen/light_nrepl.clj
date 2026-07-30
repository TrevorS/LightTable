(ns leiningen.light-nrepl
  (:gen-class)
  (:require [leiningen.core.project :as lp]
            [leiningen.repl :as repl]
            [clojure.java.io :as io]
            [clojure.string :as string]))

;; fs 1.3.3 was pulled in for two calls and is a decade unmaintained. Both are
;; one line of java.io.

(defn parse-version [ver]
  (assert (and ver (re-find #"^\d+\.\d+\.\d+" ver))
          (str "Invalid version number: " (pr-str ver) ". Must be in format X.X.X"))
  (let [[major minor patch] (string/split ver #"\.")
        [patch extra] (string/split patch #"-")]
    {:major (Integer. major)
     :minor (Integer. minor)
     :patch (Integer. patch)}))

(defn at-least-version? [ver min-version]
  (let [{:keys [major minor patch]} (parse-version ver)]
    (or (> major (:major min-version))
        (and (= major (:major min-version)) (> minor (:minor min-version)))
        (and (= major (:major min-version)) (= minor (:minor min-version)) (>= patch (:patch min-version))))))

(defn maintained-clojure-version?
  "Determine if Clojure version is supported for latest middleware.
  Return true for nil since there is no version and thus we are providing one"
  [ver]
  (if-not ver
    true
    ;; 1.7.0 candidates conflict with 1.7.0
    (and (not (.startsWith ver "1.7.0-"))
         (at-least-version? ver {:major 1 :minor 7 :patch 0}))))

(def ^:dynamic *middleware-src*
  "Directory holding lighttable.nrepl's sources, passed in by Light Table.

  Upstream pulled the middleware off Clojars instead — `lein-light-nrepl`
  0.3.3 and `lein-light-nrepl-instarepl` 0.3.1 — which made starting a REPL a
  network fetch of somebody else's artifact, and pinned it to a version of
  nREPL that no longer exists. The source is vendored beside this project now
  and is put on the project's source path directly."
  nil)

(def middleware-dependencies
  "What lighttable.nrepl needs on the REPL's classpath.

  Kept in step with ../lein-light-nrepl/project.clj. nREPL itself is not here:
  the REPL being started already has one, and two on a classpath is the kind
  of problem that presents as a session that will not clone."
  '[[org.clojure/data.json "2.5.1"]
    [org.clojure/tools.reader "1.5.2"]
    [clj-stacktrace "0.2.8"]
    [commons-io/commons-io "2.20.0"]
    [clojure-complete "0.2.5"]
    ;; lighttable.nrepl.cljs drives the ClojureScript compiler directly, so
    ;; the namespace will not even load without it — and the handler requires
    ;; that namespace, so neither will Clojure evaluation. A project with its
    ;; own ClojureScript wins on Leiningen's normal resolution.
    [org.clojure/clojurescript "1.12.42"]])

(defn prep
  "Build a project map for the repl with LT middleware injected"
  [project name _clj-version]
  ;; `:project (quote ~project)` upstream, which embedded the whole Leiningen
  ;; project map in the init form the REPL subprocess reads back. Leiningen's
  ;; project map holds functions now — `:repositories` carries a reducer — and
  ;; a function does not survive pr-str/read, so the subprocess died on a
  ;; syntax error 25kB into a generated file. Nothing ever read it back:
  ;; `client.settings` dissoc's `:project` before sending, and that was its
  ;; only mention.
  (let [init `(swap! lighttable.nrepl.core/my-settings merge {:name ~(or name (str (:name project) " " (:version project)))})
        init (if-let [cur-init (-> project :repl-options :init)]
               (list 'do cur-init init)
               init)
        _ (assert *middleware-src*
                  "No middleware source directory was given. Light Table passes it as the second argument.")
        profile {:source-paths [*middleware-src*]
                 :dependencies middleware-dependencies
                 :repl-options {:nrepl-middleware ['lighttable.nrepl.handler/lighttable-ops]
                                :init (with-meta init {:replace true})}}]
    (lp/merge-profiles project [profile])))

(defn abort-unsupported-versions [clj-version cljs-version]
  (when (and clj-version (not (at-least-version? clj-version {:major 1 :minor 5 :patch 1})))
    (binding [*out* *err*]
      (println "Light Table requires Clojure Version 1.5.1 or higher")
      (System/exit 0)))
  (when (and cljs-version (at-least-version? cljs-version {:major 0 :minor 0 :patch 2341})
             (not (maintained-clojure-version? clj-version)))
    (binding [*out* *err*]
      (println "Light Table requires Clojure Version >= 1.7.0 for ClojureScript versions >= 0.0-2341")
      (System/exit 0))))

(defn find-dependency-version [proj dep]
  (let [deps (:dependencies proj)]
    (second (first (filter #(= (first %) dep) deps)))))

(defn light
  "Start a Light Table client for this project"
  [project name]
  (let [clj-version (find-dependency-version project 'org.clojure/clojure)
        cljs-version (some-> (find-dependency-version project 'org.clojure/clojurescript)
                             ;; Make 0.0- conform to standard patch versions
                             (string/replace "0.0-" "0.0."))]
    (abort-unsupported-versions clj-version cljs-version)
    (try
      (repl/repl (prep project name clj-version) ":headless")
      (catch Exception e
        (.printStackTrace e)
        (System/exit 1)))))

(defn -main [& [name middleware-src]]
  (let [cwd (.getAbsolutePath (io/file (System/getProperty "user.dir")))
        path (str cwd "/project.clj")]
    (if (.exists (io/file path))
      (binding [*middleware-src* (or middleware-src *middleware-src*)]
        (light (lp/init-project (lp/read path)) name))
      (binding [*out* *err*]
        (println "Could not find project.clj file at: " path)))))
