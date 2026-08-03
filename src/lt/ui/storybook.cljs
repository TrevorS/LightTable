(ns lt.ui.storybook
  "The kit, rendered outside the editor.

  Light Table already has a component catalogue — [[lt.ui.catalogue]] — and it
  draws the components *in the editor they belong to*, from the same alias
  registry the window renders from, so the two cannot disagree. That is worth
  more than a picture and it is not what this is for.

  This is for the questions the in-editor catalogue cannot answer, because
  answering them means not being in the editor:

  * What does this component do with a prop you have not tried? A catalogue
    card shows the states someone wrote down; a control shows the ones nobody
    did.
  * Does it survive a narrow viewport, a long label, a missing icon?
  * Does it look right in a theme that is not the one you are running?
  * Can someone who is not running Light Table see it at all? A static
    Storybook build is a URL.

  **The registry is the contract.** A story is a map in [[stories]], and the
  JavaScript side does nothing but enumerate it and call [[render!]]. Nothing
  about a component is written twice, which is the same rule the catalogue
  follows and the reason it has stayed true.

  **Nothing here reaches the editor.** Thirty of the thirty-five aliases
  require nothing but Replicant, so they render in any browser; the five that
  do not are `lt.ui.pane`'s two and `lt.ui.kit`'s two, which reach the object
  world and the state atom. Keeping them out is what lets this build be a
  browser bundle with no Electron, no bridge and no stubs — and a stub is a
  second implementation of the thing under test, which is how a component
  passes in a gallery and fails in the product."
  (:require [lt.ui.band :as band]
            [lt.ui.chrome :as chrome]
            [lt.ui.host :as host]
            [lt.ui.row :as row]
            [replicant.dom :as rdom]))

;; Required for their aliases, which register on load. Referred so the
;; namespaces are not dropped as unused, and so this file says what it draws.
(comment band/band chrome/status-dot host/host row/list-row)

(def stories
  "Every story, keyed by id.

  A story is `{:title :name :hiccup}` — where it sits in the sidebar, what the
  state is called, and what to draw. Hand-written here in module 1 to prove the
  path end to end; module 2 replaces this with a `defstory` that writes into it
  from the namespace the component lives in.

  Exported to JavaScript by the `:storybook` build in shadow-cljs.edn, so the
  story files are generated from this rather than maintained beside it."
  (atom (into {}
              ;; `:status`, and it is worth saying why this is a `for` rather
              ;; than eleven literals. The first version of this story was
              ;; hand-written and passed `{:tone :ok}` — a prop the alias does
              ;; not take. It rendered, because an alias ignores an attribute
              ;; it does not destructure, and what it rendered was the default
              ;; state under a story called something else. A check that a
              ;; story draws cannot catch that; deriving the states from the
              ;; one place they are defined can.
              (for [status [:idle :connecting :executing :queued :finished
                            :result :error :warning :info :lost :restarting
                            :shutting :agent]]
                [(str "chrome/status-dot--" (name status))
                 {:title "Kit/chrome/status-dot"
                  :name (name status)
                  :hiccup [::chrome/status-dot {:status status}]}]))))

(defn ^:export render!
  "Draw the story `id` into a fresh element and hand it back.

  Storybook's html renderer takes a DOM node from a story's `render`, so this
  is the whole bridge: Replicant renders hiccup into a node, and a node is what
  the other side wanted.

  A new element every call rather than one reused. Replicant reconciles against
  what it rendered last into a given node, and Storybook remounts a story
  whenever a control changes — handing back a node it has already discarded
  gives you the previous story's DOM with the new story's diff applied to it."
  [id]
  (let [el (js/document.createElement "div")]
    (set! (.-className el) "lt-story")
    (when-let [{:keys [hiccup]} (get @stories id)]
      (rdom/render el hiccup))
    el))

(defn ^:export ids
  "Every story id, sorted. What the generator enumerates."
  []
  (clj->js (vec (sort (keys @stories)))))

(defn ^:export describe
  "One story's metadata, as a plain object for the JavaScript side."
  [id]
  (when-let [s (get @stories id)]
    #js {:title (:title s) :name (:name s)}))
