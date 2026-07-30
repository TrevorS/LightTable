(ns lt.objs.repo
  "Where this build of Light Table comes from.

  One value, because it is the answer to four separate questions — where to
  report a bug, where the documentation is, where to check for a newer version,
  and where to download one. Those were four string literals naming
  LightTable/LightTable, spread across four namespaces, so a fork checked
  upstream for its updates and sent its users' bug reports to a repository
  whose maintainers cannot act on them.

  Deliberately dependency-free. The obvious home was lt.objs.deploy, which
  already owned two of the four, but lt.objs.menu needs it too and requiring
  deploy from there closes a cycle:

    menu -> deploy -> clients -> notifos -> statusbar -> tabs -> editor -> menu

  A namespace with nothing in it but data cannot be part of one."
  (:require [clojure.string :as string]))

(def repo
  "owner/name on GitHub."
  "TrevorS/LightTable")

(def url
  "The repository's web address, without a trailing slash."
  (str "https://github.com/" repo))

(def api
  "The repository's GitHub API root, without a trailing slash."
  (str "https://api.github.com/repos/" repo))

(defn at
  "A path under the repository's web address: `(at \"issues\")`."
  [& parts]
  (str url "/" (string/join "/" parts)))
