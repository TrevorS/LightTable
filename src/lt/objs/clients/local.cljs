(ns lt.objs.clients.local
  "The editor as a thing you can evaluate into.

  Every other client in Light Table reaches somewhere else — a REPL, a
  browser, a node process. This one is the window itself, so a form evaluated
  through it changes the editor that is drawing the buffer it came from. That
  is the feature the editor is named for.

  It answers three languages:

  - `:editor.eval.js` — `js/eval`, straight into the window.
  - `:editor.eval.css` — a `<style>` element, replaced by name, so a stylesheet
    re-evaluates instead of accumulating.
  - `:editor.eval.cljs` — compiled first, by [[lt.objs.cljs-compiler]], which
    is the ClojureScript compiler running in this window.

  `:editor.eval.cljs.exec` is the fourth and is the same door from the other
  side: JavaScript that something *else* compiled, which is how the browser
  clients and a remote nREPL compiler deliver ClojureScript. Both routes end
  in the same `js/eval`."
  (:refer-clojure :exclude [send])
  (:require [cljs.reader :as reader]
            [lt.object :as object]
            [lt.objs.files :as files]
            [lt.objs.clients :as clients]
            [lt.objs.cljs-compiler :as cljs-compiler]
            [lt.objs.sidebar.clients :as scl]
            [lt.objs.eval :as eval]
            [lt.objs.console :as console]
            [clojure.string :as string]
            [singultus.core :as crate]
            [lt.util.dom :refer [$ append remove]])
  (:require-macros [lt.macros :refer [behavior]]))

(def client-name "LightTable-UI")

(defmulti on-message identity)

(defmethod on-message :editor.eval.cljs.exec [_ data cb]
  (doseq [res (:results data)]
    (let [code (:code res)]
      (try
        (object/raise clients/clients :message
                      [cb
                       :editor.eval.cljs.result
                       {:result (eval/cljs-result-format (.call js/eval js/window code))
                        :meta (merge (:meta data) (:meta res))}])
        (catch :default e
          (object/raise clients/clients :message [cb :editor.eval.cljs.exception {:ex e :meta (:meta res)}]))))))

(defmethod on-message :editor.eval.cljs [_ data cb]
  ;; The reply carries `:results`, one per form, each with the `:meta` saying
  ;; which lines it belongs beside — the shape lt.plugins.clojure's result
  ;; behaviors already read, so an inline result appears next to the form that
  ;; produced it exactly as it does for Clojure.
  (cljs-compiler/eval-forms
    data
    (fn [results]
      (object/raise clients/clients :message
                    [cb
                     :editor.eval.cljs.result
                     {:results results
                      :meta (:meta data)}]))))

(defmethod on-message :editor.eval.js [_ data cb]
  (let [code (-> (:code data)
                 (eval/append-source-file (:path data)))]
      (try
        (object/raise clients/clients :message
                      [cb
                       :editor.eval.js.result
                       {:result (.call js/eval js/window code)
                        :meta (:meta data)}])
        (catch :default e
          (object/raise clients/clients :message [cb :editor.eval.js.exception {:ex e :meta (:meta data)}])))))

(defmethod on-message :editor.eval.css [_ data cb]
  (let [name (str "local-" (string/replace (:name data) #"[^a-zA-Z0-9]+" "-"))
        cur ($ (str "#" name))]
    (when cur
      (remove cur))
    (append ($ :head)
            (crate/html [:style {:type "text/css" :id name} (:code data)]))))

(defmethod on-message :client.close [_ _ _]
  (clients/rem! (clients/by-name client-name)))

(defmethod on-message :default [])

(behavior ::send!
          :triggers #{:send!}
          :reaction (fn [this data]
                      (on-message (keyword (:command data)) (:data data) (:cb data))))

(defn init []
  (clients/handle-connection! {:name client-name
                               :tags [:client.local]
                               :root-relative (files/lt-home "core")
                               :commands #{:editor.eval.cljs
                                           :editor.eval.cljs.exec
                                           :editor.eval.js
                                           :editor.eval.css}
                               :type "LT-UI"}))

(defn connect!
  "The client for this window, starting it if it is not already up.

  Cheap and idempotent — there is no process to start and no socket to open,
  because the thing being connected to is the window doing the connecting."
  []
  (or (clients/by-name client-name)
      (init)))

(scl/add-connector {:name "Light Table UI"
                    :desc "Connect to this instance of Light Table and evaluate in the local context."
                    :connect connect!})
