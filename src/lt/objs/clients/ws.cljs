(ns lt.objs.clients.ws
  "Define websocket server for use with language plugins e.g. JavaScript"
  (:refer-clojure :exclude [send])
  (:require [lt.object :as object]
            [lt.objs.files :as files]
            [lt.objs.clients :as clients]
            [lt.util.bridge :as bridge])
  (:require-macros [lt.macros :refer [behavior]]))

(def sockets (atom {}))

(declare server)

(defn send-to [id data]
  (if id
    (.send server id (-> data second) data)
    ;;TODO: some system-wide error reporting
    (.log js/console (str "No such client: " id))))

(defn ->client [data]
  (let [d (js->clj data :keywordize-keys true)]
    (assoc d
           :type :ws)))

(defn store-client! [id data]
  (let [data (js->clj data :keywordize-keys true)
        client (clients/by-name (:name data))
        data (if-not (:tags data)
               (assoc data :tags [:ws.client])
               (assoc data :tags (map keyword (:tags data))))]
    ;; Disconnecting is handled by ::on-disconnect below; the connection is a
    ;; number, which is all this ever needed it to be.
    (if (clients/available? client)
      (object/merge! client {:socket id})
      (clients/handle-connection! (assoc data :socket id :type :websocket)))))

(defn on-result [id data]
  (object/raise clients/clients :message (js->clj data :keywordize-keys true)))

(defn on-disconnect [id]
  (doseq [cur (filter #(= id (:socket @%)) (vals @clients/cs))]
    (clients/rem! cur)))

(behavior ::send!
          :triggers #{:send!}
          :reaction (fn [this msg]
                      (send-to (:socket @this) (array (:cb msg) (:command msg) (-> msg :data clj->js)))))

(def server
  (try
    ;; The shim is read here and handed over: the window has the filesystem
    ;; capability and knows its own layout, and the server does not need to.
    (.ws bridge/servers (:content (files/open-sync (files/lt-home "core/lighttable/ws.js")))
         #js {:onInit store-client!
              :onResult on-result
              :onDisconnect on-disconnect})
    (catch :default e
      (.error js/console "Error starting socket.io server" e))))

(defn ->port [] (if server (.port server) 0))

(behavior ::kill-on-closed
          :triggers #{:closed}
          :reaction (fn [app]
                      (.close server)))
