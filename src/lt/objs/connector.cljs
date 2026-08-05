(ns lt.objs.connector
  "Asking which client should evaluate a file, when more than one could.

  No object of its own. It used to create one to hold the callback and destroy
  it when a button was clicked, so dismissing the popup with Esc instead left
  the object behind — tagged, reachable, and holding a closure over the
  clients. Closing over the callback means there is nothing to clean up, which
  is the same shape [[lt.objs.editor.lsp]] uses to offer code actions."
  (:require [lt.object :as object]
            [lt.objs.popup :as popup]
            [lt.objs.eval :as eval])
  (:require-macros [lt.macros :refer [behavior]]))

(defn- select-client!
  "Ask which of `clients` to use, and hand the answer to `cb`.

  Cancelling answers nothing, which is the point: an evaluation nobody chose a
  client for should not happen rather than happen against a guess.

  The clients are `:options` rather than `li.button` hiccup built here with a
  click closure inside it, which is what this was. Two things follow. The popup
  closes itself, so there is no `atom` holding the popup so that a button can
  reach it — the whole `(let [popup (atom nil)] (reset! popup (popup! …)))` dance
  existed only to let a hand-built handler close the thing containing it. And the
  choices are on the object, so `lt.objs.control` can list and answer this prompt
  over MCP without reading the document."
  [clients cb]
  (popup/popup! {:header "Which client?"
                 :body [:p "There are multiple clients that could potentially handle this.
                        Which one do you want us to use for this file?"]
                 :options (for [client clients]
                            {:label (:name @client)
                             :action #(cb client)})
                 :buttons [popup/cancel-button]}))

(behavior ::select-client
          :triggers #{:select-client}
          :reaction (fn [_ potentials cb]
                      (select-client! potentials cb)))

(object/add-behavior! eval/evaler ::select-client)
