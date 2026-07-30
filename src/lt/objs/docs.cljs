(ns lt.objs.docs
  "Provide command to see LT documentation"
  (:require [lt.objs.command :as cmd]
            [lt.objs.repo :as repo]))

(cmd/command {:command :show-docs
              :desc "Docs: Open Light Table's documentation"
              :exec (fn []
                      ;; Not docs.lighttable.com. That was the answer for a
                      ;; decade and it now returns 503 — it is a service this
                      ;; project does not run and cannot bring back, and
                      ;; sending a new user to an error page is worse than
                      ;; sending them nowhere.
                      ;;
                      ;; The repository is what is actually maintained: its
                      ;; README, and doc/ beside it — the BOT model the whole
                      ;; editor is built on, a typical session, the Electron
                      ;; layer, how the window came to be isolated, and where
                      ;; language support is going.
                      (cmd/exec! :add-browser-tab (str repo/url "#readme")))})
