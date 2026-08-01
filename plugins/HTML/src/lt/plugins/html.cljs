(ns lt.plugins.html
  (:require [lt.object :as object]
            [lt.objs.eval :as eval]
            [lt.objs.editor :as ed]
            [lt.objs.command :as cmd]
            ;; lt.objs.editor was required twice, once as `ed` and once as
            ;; `editor`, and both aliases were in use. clj-kondo calls that an
            ;; error rather than a warning.
            [lt.objs.editor.pool :as pool]
            [lt.objs.clients :as clients]
            [lt.util.dom])
  (:require-macros [lt.macros :refer [behavior]]))

;; Forward reference: this namespace is written in call order — see
;; plugins/HTML/VENDORED.md.
(declare html-lang)

(defn start-browser [path]
  (cmd/exec! :add-browser-tab (str "file://" path)))

(behavior ::on-eval
          :triggers #{:eval
                      :eval.one}
          :reaction (fn [editor]
                      (eval/get-client! {:command :editor.eval.html
                                         :origin editor
                                         :create (fn [] (start-browser (-> @editor :info :path)))
                                         :info (:info @editor)})
                      (object/raise editor :save)))

(behavior ::eval-on-save
          :triggers #{:save}
          :reaction (fn [editor]
                      (when (and (-> @editor :client :default)
                                 (not (clients/placeholder? (-> @editor :client :default))))
                        (object/raise html-lang :eval! {:origin editor
                                                        :info (assoc (@editor :info)
                                                                :code (ed/->val (:ed @editor)))}))))

(behavior ::eval!
          :triggers #{:eval!}
          :reaction (fn [this event]
                      (let [{:keys [info origin]} event]
                        (clients/send (eval/get-client! {:command :editor.eval.html
                                                         :origin origin
                                                         :info info})
                                      :editor.eval.html
                                      info
                                      :only origin))))

(object/object* ::html-lang
                :tags #{:html.lang}
                :behaviors [::eval!]
                :triggers #{:eval!})

(def html-lang (object/create ::html-lang))

(cmd/command {:command :html.jump-to-matching-tag
              :desc "HTML: Jump to matching tag"
              ;; `toMatchingTag` came from a CodeMirror 5 addon that walked the
              ;; token stream looking for a tag name. The editor parses HTML
              ;; into a tree now, and a tag's match is its element's other end —
              ;; `selectMatchingBracket` finds it the same way it finds a brace,
              ;; because in a tree they are the same question.
              :exec (fn []
                      (when-let [ed (pool/last-active)]
                        (ed/exec-command! ed "selectMatchingBracket")))})
