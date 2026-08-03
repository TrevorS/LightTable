(ns lt.objs.browser
  "Provide browser object which wraps around Electron's webview"
  (:require [lt.object :as object]
            [lt.objs.tabs :as tabs]
            [lt.objs.command :as cmd]
            [lt.objs.eval :as eval]
            [lt.objs.clients :as clients]
            [lt.objs.sidebar.clients :as scl]
            [lt.objs.menu :as menu]
            [lt.objs.context :as ctx]
            [lt.objs.editor :as editor]
            [lt.objs.editor.pool :as pool]
            [lt.objs.notifos :as notifos]
            [lt.objs.clients.devtools :as devtools]
            [lt.util.dom :as dom]
            [lt.ui :as ui])
  (:require-macros [lt.macros :refer [behavior]]))

(def utils (js-obj))

;; Assigned through window rather than as js/lttools: shadow infers an extern
;; from the bare global, and Closure then reports the assignment as writing to
;; a constant.
(set! (.-lttools js/window) utils)

(defn check-http [url]
  (if (and (= (.indexOf url "http") -1)
           (= (.indexOf url "file://") -1))
    (str "http://" url)
    url))

(defn add-util [nme fn]
  (aset utils (name nme) fn))

(defn browser-id [this]
  (str "browser" (object/->id this)))

(defn to-frame [this]
  (dom/$ :webview (object/->content this)))

(defn navigate!
  "Point this browser tab's guest at `url`.

  A webview cannot be navigated before its guest page exists, and a tab is
  created and navigated in the same turn — `:add-browser-tab` does both — so
  being too early is the normal case rather than a corner. loadURL throws then,
  which is the signal to hold the url until `dom-ready` fires and flush it
  there."
  [this url]
  (let [^js frame (to-frame this)]
    (try
      (.loadURL frame url)
      (catch :default _
        (object/merge! this {::pending-url url})))))

(defn client->devtools [ed]
  (-> @ed (:frame) (deref) (:devtools-client)))

(defn handle-cb [cbid command data]
  (object/raise clients/clients :message [cbid command data]))


(defn connect-client [this]
  (clients/handle-connection! {:name (:url @this)
                               :frame this
                               :frame-id (browser-id this)
                               :tags [:frame.client]
                               :commands #{:editor.eval.cljs.exec
                                           :editor.eval.js
                                           :editor.eval.html
                                           :editor.eval.css}
                               :type :frame}))


(defn add []
  (let [browser (object/create ::browser)]
    (tabs/add! browser)
    (tabs/active! browser)
    browser))

(defn- url-bar [this]
  ;; `:value` is the object's `:url`, so navigating rewrites the bar. It is safe
  ;; to draw it that way here and not in the find bar or the searcher: typing in
  ;; this one changes nothing on the object, so nothing re-renders under you —
  ;; only `::navigate!` sets `:url`, and having the bar follow it is the point.
  [:input.url-bar {:type "text" :placeholder "url" :value (:url @this)
                   :on {:focus (fn []
                                 (ctx/in! :browser.url-bar this)
                                 (object/raise this :active))
                        :blur (fn []
                                (object/raise this :inactive)
                                (ctx/out! :browser.url-bar))}}])

(defn- nav-button [label value f]
  [:button {:value value :on {:click f}} label])

(declare webview)

(defn- browser-ui [this]
  (list
   [:div.frame-shade]
   (webview this)
   [:nav
    (nav-button "<" "<" (fn [] (object/raise this :back!)))
    (nav-button ">" ">" (fn [] (object/raise this :forward!)))
    (url-bar this)
    (nav-button "↺" "re" (fn [] (object/raise this :refresh!)))]))

(defn- webview [this]
  ;; No :preload attribute: the guest's preload is pinned by the main process,
  ;; which is the only side that should decide what runs inside an arbitrary
  ;; web page. See secureWebContents() in src-electron/main.ts.
  ;;
  ;; src is set once and deliberately not bound to :url. An Electron <webview>
  ;; reads src when it attaches and then keeps it in step with whatever it has
  ;; actually loaded — so assigning to it later is overwritten by the page that
  ;; is already there, and the tab sits on about:blank. Navigation goes through
  ;; loadURL instead; see navigate!.
  ;;
  ;; Keyed, which is the other half of that: a key is what tells Replicant this
  ;; is the same element across renders, so the URL bar above it can be redrawn
  ;; without the guest being torn down and reloaded. Same mechanism as
  ;; `lt.ui.pane` uses to host a CodeMirror.
  [:webview {:replicant/key (browser-id this)
             :src "about:blank"
             :id (browser-id this)
             :on {:focus (fn [] (object/raise this :active))
                  :blur (fn [] (object/raise this :inactive))}}])

;;*********************************************************
;; Object
;;*********************************************************

(object/object* ::browser
                :name "browser"
                :tags #{:browser}
                :url "about:blank"
                :init (fn [this]
                        (object/merge! this {:client (connect-client this)
                                             :devtools-client (object/create :lt.objs.clients.devtools/devtools-client (:url @this))})
                        (object/raise (:devtools-client @this) :reconnect!)
                        (ui/node this [:div#browser] browser-ui)))


;;*********************************************************
;; Behaviors
;;*********************************************************

(behavior ::reconnect-on-move
          :triggers #{:move}
          :reaction (fn [this]
                      ;;When the tab is moved the webview is destroyed and recreated
                      ;;this causes devtools connections to die, so we have to
                      ;;tell it to reconnect
                      (object/raise (:devtools-client @this) :reconnect!)))

(behavior ::destroy-on-close
          :triggers #{:close}
          :reaction (fn [this]
                      (object/raise this :inactive)
                      (object/destroy! (:devtools-client @this))
                      (object/destroy! this)))

(behavior ::rem-client
          :triggers #{:destroy}
          :reaction (fn [this]
                      (when (= (ctx/->obj :global.browser) this)
                        (ctx/out! :global.browser))
                      (when-let [b (first (remove #{this} (object/by-tag :browser)))]
                        (ctx/in! :global.browser b))
                      (clients/rem! (:client @this))))

(behavior ::navigate!
          :triggers #{:navigate!}
          :reaction (fn [this n]
                      (let [bar (dom/$ :input (object/->content this))
                            url (check-http (or n (dom/val bar)))]
                        (notifos/working)
                        (object/merge! this {:url url :loading-counter (inc (:loading-counter @this 0))})
                        ;; Show where it is going straight away; did-finish-load
                        ;; replaces this with where it actually arrived.
                        (dom/val bar url)
                        (navigate! this url))))

(behavior ::url-focus!
          :triggers #{:url.focus!}
          :reaction (fn [this]
                      (let [url-input (dom/$ :input (object/->content this))]
                        (dom/focus url-input)
                        (.select url-input))))

(behavior ::focus!
          :triggers #{:focus!}
          :reaction (fn [this]
                      (dom/focus (dom/$ :webview (object/->content this)))))

(behavior ::back!
          :triggers #{:back!}
          :reaction (fn [this]
                      (let [^js frame (to-frame this)]
                        (.goBack frame))))

(behavior ::forward!
          :triggers #{:forward!}
          :reaction (fn [this]
                      (let [^js frame (to-frame this)]
                        (.goForward frame))))

(behavior ::refresh!
          :triggers #{:refresh!}
          :reaction (fn [this]
                      (let [frame (to-frame this)]
                        (.reload frame))))

(behavior ::menu!
          :triggers #{:menu!}
          :reaction (fn [this e]
                      (let [items (sort-by :order (object/raise-reduce this :menu+ []))]
                        (-> (menu/menu items)
                            (menu/show-menu)))
                      (dom/prevent e)
                      (dom/stop-propagation e)))

(behavior ::menu+
          :triggers #{:menu+}
          :reaction (fn [this menu]
                      (conj menu
                            {:label "forward"
                             :order 0
                             :click (fn [e]
                                      (cmd/exec! :browser.forward))}
                            {:label "back"
                             :order 1
                             :click (fn [e]
                                      (cmd/exec! :browser.back))}
                            {:type "separator"
                             :order 2}
                            {:label "copy"
                             :order 3
                             :click (fn [e]
                                      (.copy ^js (to-frame this)))}
                            {:label "paste"
                             :order 4
                             :click (fn [e]
                                      (.paste ^js (to-frame this)))})))


(behavior ::init!
          :triggers #{:init}
          :reaction (fn [this]
                      (let [frame (dom/$ :webview (object/->content this))
                            bar (dom/$ :input (object/->content this))]
                        (.addEventListener frame "ipc-message" (fn [^js e arg]
                                                                 (let [args (aget (.-args e) 0)]
                                                                   (condp = (.-channel e)
                                                                     "browser-event" (object/raise this (keyword (aget args 0)) (aget args 1))
                                                                     "browser-raise" (object/raise (object/by-id (aget args 0))
                                                                                                   (keyword (aget args 1))
                                                                                                   (js->clj (aget args 2) :keywordize-keys true))
                                                                     ))))
                        (.addEventListener frame "contextmenu" (fn [e]
                                                                 (object/raise this :menu! e)))
                        ;; The guest is ready; anything navigate! could not
                        ;; deliver before now goes through here.
                        (.addEventListener frame "dom-ready"
                                           (fn []
                                             (when-let [pending (::pending-url @this)]
                                               (object/merge! this {::pending-url nil})
                                               (.loadURL ^js frame pending))))
                        (.addEventListener frame "did-finish-load" (fn []
                                                                     (let [loc (.getURL ^js frame)]
                                                                       (devtools/clear-scripts! (:devtools-client @this))
                                                                       (dom/val bar loc)
                                                                       (object/raise this :navigate loc))
                                                                     )))))

(behavior ::handle-hash-change
          :triggers #{:hashchange}
          :reaction (fn [this info]
                      (dom/val (dom/$ :input (object/->content this)) (.-href info))))

(behavior ::set-client-name
          :triggers #{:navigate}
          :reaction (fn [this loc]
                      (let [title (.getTitle ^js (to-frame this))
                            title (if-not (empty? title)
                                    title
                                    "browser")]
                        (object/merge! this {:name title})
                        (tabs/refresh! this)
                        (dotimes [x (:loading-counter @this)]
                          (notifos/done-working))
                        (object/merge! (:client @this) {:name loc}))))

(behavior ::update-devtools-client-url
          :triggers #{:navigate}
          :reaction (fn [this loc]
                      (object/merge! (:devtools-client @this) {:url loc})))

(behavior ::set-active
          :triggers #{:active :show}
          :reaction (fn [this]
                      (ctx/in! :global.browser this)))

(behavior ::active-context
          :triggers #{:active :show}
          :reaction (fn [this]
                      (ctx/in! :browser this)))

(behavior ::focus-on-show
          :triggers #{:show}
          :reaction (fn [this]
                      (object/raise this :focus!)))

(behavior ::inactive-context
          :triggers #{:inactive}
          :reaction (fn [this]
                      (ctx/out! :browser)))

(behavior ::handle-send!
          :triggers #{:send!}
          :reaction (fn [this msg]
                      (object/raise this (keyword (str (:command msg) "!")) msg)))

(behavior ::handle-refresh!
          :triggers #{:client.refresh!}
          :reaction (fn [this]
                      (object/raise (:frame @this) :refresh!)))

(behavior ::handle-close!
          :triggers #{:client.close!}
          :reaction (fn [this]
                      (object/raise (:frame @this) :close)
                      (clients/rem! this)))

(behavior ::change-live
          :triggers #{:editor.eval.js.change-live!}
          :reaction (fn [this msg]
                      (when-let [ed (clients/cb->obj (:cb msg))]
                        (when (-> msg :data :path)
                          (devtools/changelive! ed (-> msg :data :path) (js/lt.plugins.watches.watched-range ed nil nil js/lt.objs.langs.js.src->watch)
                                                (fn [res]
                                                  ;;TODO: check for exception, otherwise, assume success
                                                  (object/raise ed :editor.eval.js.change-live.success)
                                                  )
                                                identity)))))

(behavior ::js-eval-file
          :triggers #{:editor.eval.js.file!}
          :reaction (fn [this msg cb]
                      (when-let [ed (clients/cb->obj (:cb msg))]
                        (let [data (:data msg)
                              data (assoc data :code (str (:code data) "\n\n//# sourceURL=" (:path data)))]
                          (devtools/eval-in-webview-client (client->devtools this) data (fn [res]
                                                                                          (when cb (cb))
                                                                                          ;;TODO: check for exception, otherwise, assume success
                                                                                          (object/raise ed :editor.eval.js.file.success)))))))

(behavior ::html-eval
          :triggers #{:editor.eval.html!}
          :reaction (fn [this msg]
                      (object/raise this :client.refresh!)))

(behavior ::css-eval
          :triggers #{:editor.eval.css!}
          :reaction (fn [this msg]
                      (let [info (:data msg)
                            frame (to-frame (:frame @this))]
                        (.send frame "editor.eval.css" #js {:name (:name info)
                                                            :code (:code info)}))))

(behavior ::cljs-exec
          :triggers #{:editor.eval.cljs.exec!}
          :reaction (fn [this msg]
                      (let [frame (to-frame (:frame @this))
                            info (:data msg)]
                        (.send frame "editor.eval.cljs.exec" #js {:results (clj->js (:results info))
                                                                  :client (:cb msg)}))))

(defn eval-js-form [this msg]
  (let [data (assoc (:data msg) :code (eval/append-source-file (-> msg :data :code) (-> msg :data :path)))
        devtools-client (client->devtools this)]
    (devtools/eval-in-webview-client devtools-client data
                                     (fn [res]
                                       (let [result (devtools/inspector->result devtools-client res)
                                             req (:data msg)
                                             result (assoc result :meta (:meta req) :no-inspect true)]
                                         (if-not (:ex result)
                                           (handle-cb (:cb msg) :editor.eval.js.result result)
                                           (handle-cb (:cb msg) :editor.eval.js.exception result)))))
    (object/raise this :editor.eval.js.change-live! msg)))

(defn must-eval-file? [devtools msg]
  ;;we eval the whole file if there's no meta, or this file isn't loaded in the current page
  (when (-> msg :data :path)
    (or (not (-> msg :data :meta))
        (not (devtools/find-script devtools (-> msg :data :path))))))


(behavior ::js-eval
          :triggers #{:editor.eval.js!}
          :reaction (fn [this msg]
                      (let [devtools (client->devtools this)]
                        (if (must-eval-file? devtools msg)
                          (when-let [ed (object/by-id (:cb msg))]
                            (let [data (:data msg)
                                  data (assoc data :code (str (editor/->val ed) "\n\n//# sourceURL=" (-> data :path)))]
                              (devtools/eval-in-webview-client (client->devtools this) data (fn [res]
                                                                                              (eval-js-form this msg)))))
                          (eval-js-form this msg)))))

;;*********************************************************
;; Commands
;;*********************************************************

(cmd/command {:command :browser.url-bar.navigate!
              :desc "BrowserUrlBar: navigate to location"
              :hidden true
              :exec (fn [loc]
                      (when-let [b (ctx/->obj :browser.url-bar)]
                        (when @b
                          (object/raise b :navigate! loc))))})

(cmd/command {:command :browser.url-bar.focus
              :desc "Browser: focus url"
              :hidden true
              :exec (fn [loc]
                      (when-let [b (ctx/->obj :browser)]
                        (when @b
                          (object/raise b :url.focus!))))})

(cmd/command {:command :browser.focus-content
              :desc "Browser: focus content"
              :hidden true
              :exec (fn []
                      (when-let [b (ctx/->obj :browser)]
                        (when @b
                          (object/raise b :focus!))))})

(cmd/command {:command :browser.back
              :desc "Browser: back"
              :exec (fn []
                      (when-let [b (ctx/->obj :browser)]
                        (when @b
                          (object/raise b :back!))))})

(cmd/command {:command :browser.forward
              :desc "Browser: forward"
              :exec (fn []
                      (when-let [b (ctx/->obj :browser)]
                        (when @b
                          (object/raise b :forward!))))})

(cmd/command {:command :add-browser-tab
              :desc "Browser: add browser tab"
              :exec (fn [loc]
                      (let [b (add)]
                        (if-not loc
                          (object/raise b :focus!)
                          (object/raise b :navigate! loc))))})

(cmd/command {:command :refresh-connected-browser
              :desc "Browser: refresh active browser tab"
              :exec (fn []
                      (when-let [b (ctx/->obj :global.browser)]
                        (when @b
                          (object/raise b :refresh!))))})

(cmd/command {:command :editor.open-current-file-in-browser
              :desc "Editor: Open current file in browser"
              :exec (fn []
                      (let [b (ctx/->obj :global.browser)
                            ed (pool/last-active)]
                        (when (and ed (-> @ed :info :path))
                          (when-not b
                            (cmd/exec! :add-browser-tab))
                          (object/raise (ctx/->obj :global.browser) :navigate! (str "file://" (-> @ed :info :path))))))})


;;*********************************************************
;; Misc
;;*********************************************************

(scl/add-connector {:name "Browser"
                    :desc "Open a browser tab to eval JavaScript, CSS, and HTML live."
                    :connect (fn []
                               (cmd/exec! :add-browser-tab))})
