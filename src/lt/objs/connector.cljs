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
  (:require-macros [lt.macros :refer [behavior defui]]))

(defui client-button [popup client cb]
  [:li.button (:name @client)]
  :click (fn []
           (cb client)
           (when-let [p @popup] (object/raise p :close!))))

(defn- select-client!
  "Ask which of `clients` to use, and hand the answer to `cb`.

  Cancelling answers nothing, which is the point: an evaluation nobody chose a
  client for should not happen rather than happen against a guess."
  [clients cb]
  (let [popup (atom nil)]
    (reset! popup
            (popup/popup! {:header "Which client?"
                           :body (list [:p "There are multiple clients that could potentially handle this.
                                        Which one do you want us to use for this file?"]
                                       [:ul (map #(client-button popup % cb) clients)])
                           :buttons [popup/cancel-button]}))))

(behavior ::select-client
          :triggers #{:select-client}
          :reaction (fn [_ potentials cb]
                      (select-client! potentials cb)))

(object/add-behavior! eval/evaler ::select-client)
