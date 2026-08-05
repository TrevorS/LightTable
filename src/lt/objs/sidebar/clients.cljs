(ns lt.objs.sidebar.clients
  "The connect panel, which is `lt.ui.view/connections`.

  That view is one of the design's eight and has been written and tested since
  the kit landed, drawn only in the window-as-a-view tab. This is it in the
  window you use: the same function, the same `chrome/connection-row`, over
  `lt.state.objects/clients*`.

  It was a `map-bound` over `lt.objs.clients/cs` splicing a node per client,
  with a second list underneath it for the kinds of connection and CSS deciding
  which of the two you saw. The list is state now and `:choosing?` is which —
  the same shape the workspace panel has for tree-or-recents.

  What you can do to a connection is in its menu. The design's rule for a row
  holds: what follows the label is a hint, never a control, and `disconnect`
  one mis-click from the thing you are reading was the old panel's arrangement
  rather than a decision."
  (:require [lt.actions :as actions]
            [lt.object :as object]
            [lt.objs.clients :as clients]
            [lt.objs.clients.ws :as ws]
            [lt.objs.clients.tcp :as tcp]
            [lt.objs.command :as cmd]
            [lt.objs.editor.pool :as pool]
            [lt.objs.eval :as eval]
            [lt.objs.menu :as menu]
            [lt.objs.providers :as providers]
            [lt.objs.popup :as popup]
            [lt.objs.sidebar :as sidebar]
            [lt.state :as state]
            [lt.ui :as ui]
            [lt.ui.view :as view])
  (:require-macros [lt.macros :refer [behavior]]))

(declare panel)

(defn- dispatch! [& as] (actions/dispatch! (vec as)))

;; Connecting, disconnecting and unsetting change what is out there rather than
;; what is in the state — the panel hears about the result through the
;; projection. They are still actions, because a view emits actions.
(doseq [kind [:client/connect :client/disconnect :client/unset :client/menu]]
  (actions/register-passthrough! kind))

;;*********************************************************
;; The kinds of connection there are
;;*********************************************************

(defonce ^:private connectors (atom (sorted-map)))

(defn add-connector
  "Register a kind of connection. `c` is `{:name :desc :connect}`.

  Unchanged, because it is what every language plugin calls. The connect
  function stays here in a map rather than going into the state: it is a
  closure, and a closure is not data — the panel names the kind and this is
  what turns a name back into the thing that does it."
  [c]
  (swap! connectors assoc (:name c) c)
  (dispatch! [:client/connectors (for [[nm {:keys [desc]}] @connectors]
                                   {:name-of nm :desc desc})]))

(defn connect!
  "Make a connection of the kind called `name-of`, as clicking it would.

  The panel is a view and a view emits actions, so this is the action — which
  makes it the one way in for anything else that wants a connection made, a
  command or a test included."
  [name-of]
  (dispatch! [:client/connect name-of]))

(actions/register-effect! :client/connect
                          (fn [name-of]
                            (when-let [c (get @connectors name-of)]
                              (dispatch! [:client/choose false])
                              ((:connect c)))))

;;*********************************************************
;; What you can do to one
;;*********************************************************

(defn- client-by-id [id]
  (get @clients/cs id))

(actions/register-effect! :client/disconnect
                          (fn [id]
                            (when-let [c (client-by-id id)]
                              (clients/close! c))))

(actions/register-effect! :client/bind
                          (fn [id]
                            ;; What clicking a connection row means: this is
                            ;; where an evaluation in the buffer you are in
                            ;; goes. The row's whole purpose, and until now it
                            ;; did nothing — the effect wrote a `::client` key
                            ;; that nothing read, so the panel showed the truth
                            ;; and the click lied about changing it.
                            ;;
                            ;; The deciding is `lt.objs.eval/bind!`, beside
                            ;; `get-client!` and the `:select` branch that has
                            ;; always done this when a language asked.
                            (when-let [c (client-by-id id)]
                              (when-let [ed (pool/last-active)]
                                (eval/bind! ed c)
                                ;; Same as unset: clicking in the panel took
                                ;; focus, and the next thing anyone does is
                                ;; evaluate.
                                (pool/focus-last)))))

(actions/register-effect! :client/unset
                          (fn [id]
                            ;; Take this client off the buffer you are in, so
                            ;; the next evaluation goes looking again. The
                            ;; client itself is untouched — it is a connection,
                            ;; not a property of the editor.
                            (when-let [c (client-by-id id)]
                              (when-let [ed (pool/last-active)]
                                (doseq [[k v] (:client @ed)
                                        :when (= v c)]
                                  (object/update! ed [:client] dissoc k))
                                ;; The projection is what the panel reads, and
                                ;; `:set-client` is what tells it to look again
                                ;; — see `lt.ui.window/sync-from-objects`.
                                (object/raise ed :set-client nil)
                                (pool/focus-last)))))

(behavior ::client-menu-items
          :triggers #{:client-menu-items}
          :desc "Connect: The right-click menu for a connection"
          :reaction (fn [this items id]
                      (let [c (client-by-id id)
                            bound? (and c (contains? (set (some-> (pool/last-active) deref :client vals)) c))]
                        (concat items
                                ;; Both directions, so the menu is a complete
                                ;; account of what you can do to a connection.
                                ;; Clicking the row binds too — this is the
                                ;; discoverable version of that, and the only
                                ;; way to find it if you have not guessed the
                                ;; row is clickable.
                                (when (and c (not bound?) (providers/evaluates? @c))
                                  [{:label "Evaluate through this" :order 0
                                    :click #(dispatch! [:client/bind id])}])
                                (when bound?
                                  [{:label "Stop evaluating through this" :order 0
                                    :click #(dispatch! [:client/unset id])}])
                                [{:label "Disconnect" :order 1
                                  :click #(dispatch! [:client/disconnect id])}]))))

(actions/register-effect! :client/menu
                          (fn [id]
                            (-> (menu/menu (sort-by :order (object/raise-reduce panel :client-menu-items [] id)))
                                (menu/show-menu))))

;;*********************************************************
;; The panel
;;*********************************************************

(defn- panel-ui []
  (view/connections @state/app))

(object/object* ::sidebar.clients
                :tags #{:sidebar.clients}
                :label "connect"
                :order 2
                :init (fn [this]
                        (ui/state-node this [:div.clients] panel-ui [state/app])))

;; `panel`, not `clients` as it was. The clients are `lt.objs.clients/cs` and
;; this is the thing that draws them; naming it after what it shows is what let
;; `clients/clients` and `clients/clients` mean two different objects three
;; namespaces apart. Nothing outside called it — `add-connector` is the whole
;; of what this namespace is used for.
(def panel (object/create ::sidebar.clients))

(sidebar/add-item sidebar/rightbar panel)

(cmd/command {:command :show-connect
              :desc "Connect: Toggle connect bar"
              :exec (fn []
                      (object/raise sidebar/rightbar :toggle panel))})

(cmd/command {:command :show-add-connection
              :desc "Connect: Add Connection"
              :exec (fn []
                      (object/raise sidebar/rightbar :toggle panel {:force? true
                                                                    :transient? false})
                      (dispatch! [:client/choose true]))})

(add-connector {:name "Ports"
                :desc "the local TCP and WebSocket ports"
                :connect (fn []
                           (popup/popup! {:header "Ports"
                                          :body [:dl#ports
                                                 [:dt "TCP: "] [:dd (str (tcp/->port))]
                                                 [:dt "WebSocket: "] [:dd (str (ws/->port))]]
                                          :buttons [{:label "ok"}]}))})
