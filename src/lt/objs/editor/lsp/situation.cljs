(ns lt.objs.editor.lsp.situation
  "What the language server situation *is*, as a function of the facts.

  `lt.objs.editor.lsp/status` gathers the facts by reading the editor and the
  connections. Deciding what they mean is this, and it is here rather than
  there so it can be called with a map — which is the whole of the test file
  beside it.

  There is one rule and it took two bugs to find: **what is connected is asked
  about before what is declared.** The singular keys — `:root`, `:found`,
  `:command` — describe the *last-declared* server, and `:connected?` and
  `:ready?` describe all of them. So an editor answered by a working vtsls,
  with biome also declared and not installed, reported biome. \"No project root
  above … — looked for biome.json\" is a true sentence about biome and a
  useless one when vtsls is the one answering.

  That ordering was got wrong independently in the sentence and in the
  statusbar's dot, because each wrote its own `cond`. [[phase]] is the one
  `cond` now, and both read it."
  (:require [clojure.string :as string]))

(defn phase
  "Which of the six situations `status` is in.

  Ordered, and the order is the point:

  | phase | meaning |
  |---|---|
  | `:no-file` | the editor is not backed by a file, so nothing was looked for |
  | `:unconfigured` | no server is declared for this file type |
  | `:ready` | one is connected and past its handshake |
  | `:starting` | one is connected and still indexing |
  | `:missing` | one is declared, and there is no binary to run |
  | `:idle` | there is a binary and this editor is not connected to it |

  `:ready` and `:starting` precede `:missing` because a second declared server
  that is not installed must not describe a live connection to the first."
  [{:keys [path language-id found connected? ready?]}]
  (cond
    (nil? path) :no-file
    (nil? language-id) :unconfigured
    ready? :ready
    connected? :starting
    (nil? found) :missing
    :else :idle))

(defn- connected-to
  "The `Connected to …` half. Two servers for one language is the case where
  \"connected\" on its own answers the wrong question, so both are named."
  [{:keys [command found diagnostics servers connections]}]
  (let [drawn (str " — " diagnostics
                   (if (= 1 diagnostics) " diagnostic" " diagnostics") " on screen")]
    (if (> (count servers) 1)
      (str "Connected to " connections " of " (count servers) " servers ("
           (string/join ", " (map :command servers)) ")" drawn)
      (str "Connected to " (or found command) drawn))))

(defn line
  "One sentence saying which of the ways this can be quiet is the one in play.

  Silence is this feature's hard part. A project with no server installed is
  deliberately not an error — nothing starts, nothing is said — and that is
  indistinguishable from a bug you have just introduced. This names every place
  that was looked.

  `:missing` splits in two here and nowhere else: whether the search stopped at
  the project root or at the binary is the difference between two different
  things to go and do."
  [{:keys [path command markers root found] :as status}]
  (case (phase status)
    :no-file "This editor is not backed by a file."
    :unconfigured "No language server is configured for this file type."
    :starting (str "Starting " (or found command) " …")
    :ready (connected-to status)
    :missing (if (nil? root)
               (str "No project root above " path " — looked for "
                    (string/join ", " markers))
               (str "No " command " in " root "/node_modules/.bin, or on PATH. "
                    "Install it in the project, or globally."))
    :idle (str "Found " found ", but this editor is not connected to it.")))

(def ^:private dot
  "What the statusbar draws for each phase, in `lt.ui.chrome/status`'s
  vocabulary. `nil` is not a state worth drawing — most files have no server
  declared for them, and a bar that said so for every one of them would be
  saying nothing at all, loudly."
  {:ready :finished
   :starting :connecting
   :missing :lost
   :idle :queued})

(defn indicator
  "The statusbar's version: `{:command :status :diagnostics}`, or nil.

  Same `cond` as the sentence, which is the reason this is not written where
  the bar is drawn."
  [{:keys [command diagnostics] :as status}]
  (when-let [drawn (dot (phase status))]
    {:command command
     :diagnostics diagnostics
     :status drawn}))
