(ns lt.objs.clients.agent
  "An agent is a client, in the same namespace as nREPL.

  The design's second open question, and the one it calls a real commitment:
  *is the agent a client in the same namespace as nREPL, or a separate
  subsystem?* It draws the agent as a peer and gives the state shape a `:via`
  to say what it evaluates through. This is that, taken.

  Why a peer rather than a subsystem: what an agent does to this editor is what
  a REPL does to it — evaluate, read, and change files — so a second mechanism
  would mean two answers to \"what is connected\", two places to sever, and a
  connections panel that is honest about one of them. Something the user cannot
  see in the same list as their REPL is something they cannot reason about, and
  an agent is exactly the connection worth being able to reason about.

  It also costs almost nothing, which is the tell that it is the right shape:
  the client registry already holds heterogeneous things — an nREPL socket, a
  browser over websockets, this window itself — and none of them share an
  implementation. What they share is a name, a status, and the fact that an
  evaluation can be sent to them.

  `:via` is the one thing an agent has that the others do not. An agent
  evaluating ClojureScript reaches the window; evaluating Clojure it reaches
  whichever REPL is bound. Drawing that is the difference between the panel
  saying an agent is connected and the panel answering where its work actually
  runs."
  (:require [lt.object :as object]
            [lt.objs.clients :as clients]
            [lt.objs.clients.local :as local]))

(def ^:private client-name "claude · control surface")

(defn connect!
  "The client for whatever is driving this editor from outside.

  Idempotent, and created on first use rather than at startup: an editor nobody
  is driving should not claim an agent is connected."
  []
  (or (clients/by-name client-name)
      (clients/handle-connection!
       {:name client-name
        :tags [:client.agent]
        ;; What it can be asked for. The control surface is the door, so these
        ;; are its operations rather than an evaluation protocol — an agent
        ;; that wants to evaluate says so and this reaches the window's own
        ;; client to do it, which is what :via reports.
        :commands #{:agent.snapshot :agent.eval :agent.open}
        :type "agent"})))

(defn via
  "The client an agent's work actually runs in, or nil.

  The window's own client: an agent evaluating ClojureScript is evaluating in
  this window, through the same path a person's `Light Table UI` connection
  takes. When an agent evaluates Clojure this will be the bound REPL instead,
  and the panel will say so without anything here changing."
  []
  (some-> (local/connect!) object/->id))

(defn note-activity!
  "Say that the agent did something, so the panel can show it working.

  Called from the control surface rather than inferred: a client that looks
  busy because someone asked it a question a minute ago is a client that lies."
  [busy?]
  (when-let [c (clients/by-name client-name)]
    (object/merge! c {::busy? busy?
                      ::via (via)})))

(defn state
  "What the connections panel needs, for a client that may not exist yet."
  []
  (when-let [c (clients/by-name client-name)]
    {:name client-name
     :kind :agent
     :status (if (::busy? @c) :executing :idle)
     :via (::via @c)}))
