(ns lt.objs.clients.tcp
  "Define tcp server for use with language plugins"
  (:refer-clojure :exclude [send])
  (:require [lt.object :as object]
            [lt.objs.clients :as clients]
            [lt.objs.console :as console]
            [lt.util.bridge :as bridge])
  (:require-macros [lt.macros :refer [behavior]]))


(declare server)

(defn send-to [id msg]
  (if id
    (.send server id (str (.stringify js/JSON msg) "\n"))
    ;;TODO: some system-wide error reporting
    (println (str "No such client: " id))))

(defn store-client! [id data]
  (let [client (clients/by-name (:name data))
        data (if-not (:tags data)
               (assoc data :tags [:tcp.client])
               (assoc data :tags (map keyword (:tags data))))]
    (when (clients/available? client)
      (clients/close! client))
;; The connection is a number now, which is exactly what it needs to be: it is
    ;; only ever stored and compared. Closing is handled by ::on-close below.
    (clients/handle-connection! (assoc data :socket id))))

(defn on-message [data]
  (object/raise clients/clients :message data))

(def ^:private buffers
  "Partial lines per connection. A message can arrive split across packets, or
  two can arrive joined, so what has been seen is kept until a newline."
  (atom {}))

(defn each-message [id cb]
  (let [buffer (@buffers id)
        loc (.indexOf buffer "\n")]
  (loop [loc loc
         buf buffer]
    (if (and loc
               (> loc -1)
               (not (empty? buf)))
      (let [cur (subs buf 0 loc)
            next (subs buf (inc loc))
            data (try
                   (js->clj (.parse js/JSON cur) :keywordize-keys true)
                   (catch :default e
                     (console/error e)))]
        (cb data)
        (recur (.indexOf next "\n") next))
      (swap! buffers assoc id buf)))))

(defn on-result [id data]
  ;;handle the case where two events come in at once and get joined
  ;;on a new line
  (swap! buffers update id str data)
  (each-message id (fn [data]
                     (if (map? data)
                       (store-client! id data)
                       (on-message data)))))

(defn on-connect [id]
  (swap! buffers assoc id ""))

(defn on-close [id]
  (swap! buffers dissoc id)
  (doseq [cur (filter #(= id (:socket @%)) (vals @clients/cs))]
    (clients/rem! cur)))

(def server
  (try
    (.tcp bridge/servers #js {:onConnect on-connect
                              :onData on-result
                              :onClose on-close})
    (catch :default e
      (console/error "Error starting tcp server" e))))

;; The port is assigned once the server is listening, so it is read rather than
;; captured. `port` stays a var for the clients that read it.
(defn ->port [] (if server (.port server) 0))

(behavior ::send!
          :triggers #{:send!}
          :reaction (fn [this msg]
                      (send-to (:socket @this) (array (:cb msg) (:command msg) (-> msg :data clj->js)))))


(behavior ::kill-on-closed
          :triggers #{:closed}
          :reaction (fn [app]
                      (.close server)))

