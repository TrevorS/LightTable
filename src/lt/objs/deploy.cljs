(ns lt.objs.deploy
  "Provide behaviors to check for app updates and fns for downloading
  and unpacking downloaded assets"
  (:require [lt.object :as object]
            [lt.objs.clients :as clients]
            [lt.objs.files :as files]
            [lt.objs.popup :as popup]
            [lt.objs.cache :as cache]
            [lt.objs.notifos :as notifos]
            [lt.objs.platform :as platform]
            [lt.objs.repo :as repo]
            [lt.util.bridge :as bridge]
            [lt.objs.sidebar.command :as cmd]
            [lt.objs.console :as console]
            [lt.objs.app :as app]
            [lt.util.js :as js-util :refer [every]]
            [clojure.string :as string])
  (:require-macros [lt.macros :refer [behavior]]))

(def home-path (files/lt-home ""))
(def strict-ssl? true)

(defn tar-path [v]
  (if (cache/fetch :edge)
    (str repo/api "/tarball/master")
    (str repo/api "/tarball/" v)))

(def version-regex #"^\d+\.\d+\.\d+(-.*)?$")

(defn get-versions []
  (let [vstr (:content (files/open-sync (files/lt-home "core/version.json")))]
    (js->clj (.parse js/JSON vstr) :keywordize-keys true)))

(def version-timeout (* 60 60 1000))
(def version (get-versions))

(defn str->version [s]
  (let [[major minor patch] (string/split s ".")]
    {:major (js/parseInt major)
     :minor (js/parseInt minor)
     :patch (js/parseInt patch)}))

(defn compare-versions [v1 v2]
  (if (= v1 v2)
    false
    (not (or (< (:major v2) (:major v1))
             (and (= (:major v2) (:major v1))
                  (< (:minor v2) (:minor v1)))
             (and (= (:major v2) (:major v1))
                  (= (:minor v2) (:minor v1))
                  (< (:patch v2) (:patch v1)))))))

(defn is-newer?
  "Returns true if second version is newer/greater than first version."
  [v1 v2]
  (compare-versions (str->version v1) (str->version v2)))

(defn download-file
  "Download `from` to the path `to`, calling `cb` once the file has been fully
  written. Follows redirects and honours the http_proxy/https_proxy
  environment variables.

  Redirect following, proxy tunnelling and the write stream all live on the
  other side of the bridge now. What the window wanted was a file downloaded,
  not an http client, and saying so took about seventy lines out of here."
  [from to cb]
  (-> (.download bridge/net from to strict-ssl?)
      (.then cb)
      (.catch (fn [e]
                (notifos/done-working)
                (console/error e)))))

(defn download-zip [ver cb]
  (let [n (notifos/working (str "Downloading version " ver " .."))]
    (download-file (tar-path ver) (str home-path "/tmp.tar.gz") (fn []
                                                                  (notifos/done-working)
                                                                  (cb)))))

(defn untar
  "Extract the gzipped tarball `from` into the directory `to`, calling `cb` once
  extraction has finished.

  One bridge capability rather than a tar library in the window, for the same
  reason downloading is one rather than an http client: what this wants is a
  release unpacked."
  [from to cb]
  (-> (.extract bridge/files from to)
      ;; .then hands the resolution value to cb, and every caller passes a
      ;; zero-arity fn, so swallow the argument rather than blowing up on arity.
      (.then (fn [_] (cb)))
      (.catch (fn [e]
                (notifos/done-working)
                (console/error e)))))


(defn move-tmp []
  (let [parent-dir (first (files/full-path-ls (str home-path "/tmp/")))]
    (doseq [file (files/full-path-ls (str parent-dir "/deploy/"))]
      ;; files/copy takes the destination itself, where `cp -rf` took the
      ;; directory to drop the copy into.
      (files/copy file (files/join home-path (files/basename file)))))
  ;; download-zip leaves the tarball behind and untar expands it into tmp/.
  (files/delete! (str home-path "/tmp.tar.gz"))
  (files/delete! (str home-path "/tmp")))

(defn fetch-and-deploy [ver]
  (download-zip ver (fn []
                      (notifos/working "Extracting update...")
                      (untar (str home-path "/tmp.tar.gz") (str home-path "/tmp")
                             (fn []
                               (move-tmp)
                               (notifos/done-working)
                               (set! version (assoc version :version ver))
                               (popup/popup! {:header "Light Table has been updated!"
                                              :body (str "Light Table has been updated to " ver "! Just
                                                         restart to get the latest and greatest.")
                                              :buttons [{:label "ok"}]}))))))

(def tags-url (str repo/api "/tags"))

(defn should-update-popup [data]
  (popup/popup! {:header "There's a newer version of Light Table!"
                 :body (str "Would you like us to download and install version " data "?")
                 :buttons [{:label "Cancel"}
                           {:label "Download and install"
                            :action (fn []
                                      (fetch-and-deploy data))}]}))

(defn ->latest-version
  "Returns latest LT version for github api tags endpoint."
  [body]
  (when-let [parsed-body
             (try (js/JSON.parse body)
               (catch :default e
                 (console/error (str "Invalid JSON response from " tags-url ": " (pr-str body)))))]
    (->> parsed-body
         ;; Ensure only version tags
         (keep #(when (re-find version-regex (.-name %)) (.-name %)))
         sort
         last)))

(defn unreachable
  "What a background check does when it cannot reach the network.

  Not `console/error`. A laptop that is offline is not a fault in the editor,
  and `TypeError: Failed to fetch` with a stack trace under it is how a
  console stops meaning anything — the update check runs at every startup and
  then on a timer, so on a train it is the only thing in there.

  When the user asked for the check they get an answer; when it was the timer,
  it is a log line saying which host was not there."
  [what notify?]
  (fn [^js e]
    (if notify?
      (notifos/set-msg! (str "Could not reach " what) {:class "error"})
      (console/log (str "Could not reach " what ": " (.-message e))))))

(defn check-version [& [notify?]]
  (js-util/fetch-text tags-url
             (fn [data]
               (let [latest-version (->latest-version data)]
                 ;; nil when the repository has no version tags at all, which
                 ;; is what a fork looks like before its first release. re-find
                 ;; throws on nil, so this used to make "check for updates"
                 ;; report an error rather than "nothing to update to".
                 (if-not (and latest-version (re-find version-regex latest-version))
                   (when notify?
                     (notifos/set-msg! (str "No releases published at " repo/repo)))
                   (if (and (not= latest-version "")
                            (not= latest-version (:version version))
                            (is-newer? (:version version) latest-version)
                            (or notify?
                                (not= js/localStorage.fetchedVersion latest-version)))
                     (do
                       (set! js/localStorage.fetchedVersion latest-version)
                       (should-update-popup latest-version))
                     (when notify?
                       (notifos/set-msg! (str "At latest version: " (:version version)))))))) 
             ;; This runs at startup and then on a timer regardless of whether
             ;; the machine is online, so a failure is only worth the user's
             ;; attention when the user asked for it.
             (unreachable (str repo/repo " to check for updates") notify?)))

(defn binary-version
  "Binary/electron version. The two versions are in sync since binaries updates
  only occur with electron updates."
  []
  (aget (.versions bridge/host) "electron"))

(defn alert-binary-update []
  (popup/popup! {:header "There's been a binary update!"
                 :body "There's a new version of the Light Table binary. Clicking below will open the
                                 releases page so you can download the updated version."
                 :buttons [{:label "Download latest"
                            :action (fn []
                                      ;; Not lighttable.com: that publishes
                                      ;; upstream's builds, and this is not one.
                                      (platform/open-url (repo/at "releases"))
                                      (popup/remain-open))}]}))

;;*********************************************************
;; Behaviors
;;*********************************************************

(behavior ::check-deploy
          :triggers #{:deploy}
          :reaction (fn [this]
                      ;; Latest :electron version changes after LT auto-updates and user restarts
                      (when (is-newer? (binary-version) (:electron version))
                        (alert-binary-update))))

(behavior ::check-version
          :triggers #{:init}
          :type :user
          :desc "App: Automatically check for updates"
          :reaction (fn [this]
                      (when (app/first-window?)
                        (set! js/localStorage.fetchedVersion nil))
                      (check-version)
                      (every version-timeout check-version)))

(behavior ::strict-ssl
          :triggers #{:object.instant}
          :type :user
          :exclusive [::disable-strict-ssl]
          :desc "Enables strict SSL certificate checking when downloading LT and LT plugin repos (default setting)"
          :reaction (fn [this]
                      (set! strict-ssl? true)))

(behavior ::disable-strict-ssl
          :triggers #{:object.instant}
          :type :user
          :exclusive [::strict-ssl]
          :desc "Disables strict SSL certificate checking when downloading LT and LT plugin repos"
          :details "In some enterprise environments with SSL proxies strict certificate checking will fail due to MITM certificates used for monitoring SSL traffic. This option allows these network requests to succeed in such environments."
          :reaction (fn [this]
                      (set! strict-ssl? false)))

(defn build-stamp
  "What this window was built from, or nil.

  `script/stamp-build.mts` writes it beside the bundle at the end of
  `build:cljs`. Nil for a build made before that existed, or one made without
  git — which is a real answer rather than a failure, and says so below."
  []
  (when-let [raw (:content (files/open-sync
                            (files/lt-home "core/lighttable/build.json")))]
    (try
      (js->clj (.parse js/JSON raw) :keywordize-keys true)
      (catch :default _ nil))))

(defn build-line
  "One sentence naming the build, for [[build-stamp]]'s map."
  [{:keys [commit branch dirty built]}]
  (if-not commit
    (str "Light Table " (:version version)
         " — this build carries no stamp, so it was made before one was"
         " written or outside a git checkout.")
    (str "Light Table " (:version version) " — " commit
         (when dirty " with uncommitted changes")
         " on " branch
         (when built (str ", built " built)))))

(cmd/command {:command :build.info
              :desc "App: What build is this?"
              :doc "Says which commit the running window was compiled from.

                    It exists because \"is my change in the window I am looking
                    at\" had no answer: the bundle is an artifact with no
                    identity, `version.json` is the same string across every
                    build between two releases, and so telling a stale window
                    from a fresh one meant grepping the compiled JavaScript for
                    a string you had just typed. Twice that is what happened,
                    and each time it cost a round of \"it still does not work\"
                    about code that was fixed and not loaded.

                    `make build-cljs && make run` is the loop this reports on."
              :exec (fn []
                      (let [line (build-line (build-stamp))]
                        (notifos/set-msg! line {:timeout 20000})
                        (js/lt.objs.console.log line)))})

(object/tag-behaviors :app [::check-deploy])
