(ns lt.ui.settings
  "The settings screen, as a tab.

  A tab rather than a panel, for the reason the plugin manager and the component
  kit are tabs: it is a thing you go and look at rather than a thing that sits
  beside what you are working on. The tab strip already handles a tab with a
  name and no path.

  What draws it is [[lt.ui.view/settings-screen]] — a pure function of the state
  atom, through [[lt.ui/state-node]]. That is the same arrangement `Window as a
  view` uses, and it is worth saying why it is not simply
  [[lt.ui/node]]: `node` watches the *object*, which is right when the object is
  where the facts are, and here they are not. The settings are the behavior
  registry and the keymap, projected into the state — so the thing to watch is
  the state, and this object holds nothing but the fact that the tab is open.

  The consequence is the one worth having. Everything on this screen can be
  asked of a map in a test, with no window, which is what
  `test/lt/ui/view_test.cljs` does — and the coverage gap doc/hygiene.md
  recorded as *the settings and keymap UI: nothing, at any layer* is closed by
  ordinary view tests rather than by driving a real one."
  (:require [lt.actions :as actions]
            [lt.object :as object]
            [lt.objs.command :as cmd]
            [lt.objs.keyboard :as kb]
            [lt.objs.menu :as menu]
            [lt.objs.settings :as settings]
            [lt.objs.tabs :as tabs]
            [lt.state :as state]
            [lt.state.objects :as from-objects]
            [lt.ui :as ui]
            [lt.ui.view :as view])
  (:require-macros [lt.macros :refer [behavior]]))

(behavior ::on-close-destroy
          :triggers #{:close}
          :reaction (fn [this]
                      ;; The keyboard is re-enabled on the way out. Closing the
                      ;; tab while a key was being captured would otherwise
                      ;; leave the editor with no keyboard at all, which is the
                      ;; kind of bug that reads as the whole editor being
                      ;; broken.
                      (kb/enable)
                      (object/raise this :destroy)))

(object/object* ::settings
                :tags #{:settings.screen}
                :behaviors [::on-close-destroy]
                :name "Settings"
                :init (fn [this]
                        ;; Sync first: the screen is a view of a projection, and
                        ;; an unprojected one draws an empty settings list and an
                        ;; empty keymap. Both look like a bug and neither is.
                        (from-objects/sync!)
                        (ui/state-node this [:div.settings-host]
                                       (fn [] (view/settings-screen @state/app))
                                       [state/app])))

(defn- open!
  "Open the screen, or bring the one that is already open to the front.

  One at a time, deliberately. Two settings tabs would be two views of the same
  projection, which is harmless and still a thing nobody wants two of — and
  unlike a file, there is no second one to look at.

  **`by-tag` is not enough to decide that**, which is the whole reason this is a
  `filter` rather than a `first`. An object that has been closed can still be in
  the registry — `object/destroy!` raises `:destroy` before removing the
  instance, which is the same ordering `lt.objs.control/drift` was built to find
  — and a closed tab leaves an object whose `::tabset` is gone. Focusing either
  of those puts nothing on screen.

  The symptom was worth the comment: the e2e specs passed and failed in strict
  alternation. One test opened a real screen, the next focused the corpse the
  first left behind and timed out waiting for an element that was never going to
  exist, and its failure left no tab, so the one after it opened a real screen
  again."
  [showing]
  (actions/dispatch! [[:settings/show showing]])
  (let [live (first (filter #(and (deref %) (tabs/in-tab? %))
                            (object/by-tag :settings.screen)))]
    (tabs/add-or-focus! (or live (object/create ::settings)))))

(cmd/command {:command :settings.screen
              :desc "Settings: Open settings"
              :exec (fn [] (open! :settings))})

(cmd/command {:command :settings.keys
              :desc "Settings: Open keys"
              :exec (fn [] (open! :keys))})

;;*********************************************************
;; What the window has to lend it
;;*********************************************************

(actions/register-effect!
 :keymap/menu
 (fn [key]
   (-> (menu/menu [{:label "Rebind" :click #(actions/dispatch! [[:keymap/capture-start key]])}
                   {:label "Unbind" :click #(actions/dispatch! [[:keymap/unbind key]])}
                   {:type "separator"}
                   {:label "Open user.keymap"
                    :click #(cmd/exec! :open-path settings/user-keymap-path)}])
       (menu/show-menu))))

(defn install!
  "Teach [[lt.actions]] how a keystroke becomes a keymap key.

  `lt.objs.keyboard/->keystr` is the only correct answer — it is what the
  keyboard itself uses, so a key captured here and a key pressed in an editor
  produce the same string, including `cmd-` on a Mac and `meta-` everywhere
  else. It is installed rather than required because `lt.actions` has to load
  under node, and reaching the platform would take a bridge with it.

  Called from [[lt.core]], beside the other install.

  The bug this prevents is worth naming: without it the actions fall back to a
  platform-free spelling, so on a Mac every binding captured through the screen
  would be written as `meta-s` while the keymap dispatches `cmd-s`. The row
  would show the new key and the key would do nothing."
  []
  (actions/keystr-with! (fn [e] (kb/->keystr (.-key e) e))))
