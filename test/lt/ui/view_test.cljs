(ns lt.ui.view-test
  "The eight views, tested by calling them with a map.

  This is what the design buys, stated as a test file rather than as a claim:
  a view has nowhere to keep a secret, so a map goes in and hiccup comes out,
  and every question about what the window shows is answerable in milliseconds
  without an editor, a DOM, or a render.

  Hiccup is data, so the assertions walk it. `find-all` is the whole harness."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as string]
            [lt.ui.view :as view]))

(defn- nodes
  "Every vector in `hiccup`, depth first. Hiccup is a tree of vectors and seqs,
  and this flattens it into the nodes to assert about."
  [hiccup]
  (cond
    (vector? hiccup) (cons hiccup (mapcat nodes hiccup))
    (seq? hiccup) (mapcat nodes hiccup)
    :else nil))

(defn- find-all
  "Every node whose tag is `tag`."
  [hiccup tag]
  (filter #(= tag (first %)) (nodes hiccup)))

(defn- attrs-of [node]
  (let [a (second node)]
    (when (map? a) a)))

(defn- text-of
  "Every string in the tree, joined — what a person would read.

  Attribute maps are not descended into: a key and a handler are both data a
  reader never sees, and counting them as text made `:replicant/key` look like
  a label."
  [hiccup]
  (->> (tree-seq #(and (coll? %) (not (map? %))) seq hiccup)
       (filter string?)
       (string/join " ")))

(def ^:private state
  {:tabsets [{:id 0 :tabs ["src-worker/fuzzy.ts" "port-fuzzy"] :active 0}]
   :editors {"src-worker/fuzzy.ts" {:lang :ts :dirty? true}}
   :clients {51423 {:name "nREPL 51423" :kind :nrepl :status :finished :bound? true}
             :claude {:name "claude" :kind :agent :status :executing :via 51423}}
   :results {["src-worker/fuzzy.ts" 7] {:status :finished :value "({:count 2})"}}
   :watches {["src-worker/fuzzy.ts" 19 [:tabsets 0 :count]] {:reads 8}}
   :runs {"port-fuzzy" {:label "port fuzzy to ranges"
                        :status :executing
                        :grants #{:write/src-worker}
                        :edits [{:at ["src-worker/fuzzy.ts" 14]
                                 :summary "readdir → fsp.readdir"
                                 :applied? false
                                 :evidence {:as-written "fs.readdir(dir)"
                                            :if-applied "fsp.readdir(dir)"}}
                                {:at ["src-window/behaviors.cljs" 44]
                                 :summary "threshold"
                                 :applied? false
                                 :conflict "you changed this 40s ago"
                                 :yours "(score nm q)"}]}}
   :review {:run "port-fuzzy" :at 1}
   :focus [:review 1]
   :cursor {:line 6 :ch 3}
   :keymap {"⌘⏎" [[:eval/form "src-worker/fuzzy.ts" 7]]}})

(deftest a-run-is-a-tab-like-any-other
  (let [tabs (find-all (view/titlebar state) :lt.ui.chrome/tab)]
    (is (= 2 (count tabs)))
    (testing "the file is named by its leaf, because that is what a tab is for"
      (is (= "fuzzy.ts" (text-of (first tabs)))))
    (testing "and the run is named by its label, with what it is waiting on"
      (let [run (second tabs)]
        (is (= :run (:origin (attrs-of run))))
        (is (= 2 (:count (attrs-of run))) "two edits, neither applied")))
    (testing "a run that is not in the tab list is still a tab"
      ;; The projection owns the tab list and knows nothing about runs, so the
      ;; view is what makes "a run is a tab like any other" true on screen.
      (let [projected (assoc-in state [:tabsets 0 :tabs] ["src-worker/fuzzy.ts"])]
        (is (= 2 (count (find-all (view/titlebar projected) :lt.ui.chrome/tab))))))
    (testing "and it is not listed twice when it is in both"
      (is (= 2 (count tabs))))
    (testing "the active one says so"
      (is (true? (:active? (attrs-of (first tabs))))))
    (testing "a dirty editor says so in the tab, as a prop rather than a child"
      ;; The dot is the tab's to draw. A view that appended one would be
      ;; deciding what dirty looks like, which is the component's decision and
      ;; would have to be made the same way in every other place a tab appears.
      (is (true? (:dirty? (attrs-of (first tabs)))))
      (is (false? (:dirty? (attrs-of (second tabs))))))))

(deftest the-review-queue-is-rows-keyed-by-address
  (let [rows (find-all (view/review-queue state) :lt.ui.row/list-row)]
    (is (= 2 (count rows)))
    (testing "keyed by [path line], so a row survives the list changing under it"
      (is (= ["src-worker/fuzzy.ts" 14] (:replicant/key (attrs-of (first rows))))))
    (testing "the selected one is the review cursor, not a remembered click"
      (is (false? (:selected? (attrs-of (first rows)))))
      (is (true? (:selected? (attrs-of (second rows))))))
    (testing "a conflict is a tone, and tone is a role rather than a colour"
      (is (= :warning (:tone (attrs-of (second rows))))))
    (testing "focus is separate from selection, because a keyboard has both"
      (is (true? (:focused? (attrs-of (second rows))))))
    (testing "and the handler is a vector"
      (is (= [[:review/goto 0]] (:on-select (attrs-of (first rows))))))))

(deftest an-empty-queue-says-what-would-fill-it
  (let [empty-state (assoc-in state [:runs "port-fuzzy" :edits] [])]
    (is (seq (find-all (view/review-queue empty-state) :lt.ui.chrome/empty-state)))))

(deftest the-agent-is-a-client-like-the-others
  (let [rows (find-all (view/connections state) :lt.ui.chrome/connection-row)]
    (is (= 2 (count rows)))
    (testing "and where it evaluates through is drawn rather than assumed"
      (let [agent (first (filter #(= :agent (:kind (attrs-of %))) rows))]
        (is (some? agent))
        (is (re-find #"through 51423" (:what (attrs-of agent))))))))

(deftest the-statusbar-counts-what-is-waiting-on-you
  (let [bar (view/statusbar state)]
    (testing "line and column are one-based on screen and zero-based in the data"
      (is (re-find #"7 / 4" (text-of bar))))
    (testing "two unapplied edits, one executing run"
      (is (= 2 (:count (attrs-of (first (find-all bar :lt.ui.chrome/count-pill))))))
      (is (re-find #"1 run" (text-of bar))))
    (testing "and a quiet editor says nothing"
      (let [quiet (assoc state :runs {})]
        (is (empty? (find-all (view/statusbar quiet) :lt.ui.chrome/count-pill)))))))

(deftest the-command-bar-is-a-view-over-the-table
  (let [with-bar (assoc state :command-bar
                        {:open? true :query "eva" :at 0
                         :commands [{:label "Evaluate this form" :action [:eval/form "f" 1]}
                                    {:label "Rename symbol" :action [:editor/rename]}]})]
    (testing "it is a search across labels"
      (let [rows (find-all (view/command-bar with-bar) :lt.ui.row/list-row)]
        (is (= 1 (count rows)))
        (is (= "Evaluate this form" (text-of (first rows))))))
    (testing "choosing one runs the action it names, which is the same value the keymap holds"
      (is (= [[:eval/form "f" 1]]
             (:on-select (attrs-of (first (find-all (view/command-bar with-bar)
                                                    :lt.ui.row/list-row)))))))
    (testing "and it is not there when it is not open"
      (is (nil? (view/command-bar state))))
    (testing "a query that matches nothing says so"
      (is (seq (find-all (view/command-bar (assoc-in with-bar [:command-bar :query] "zzz"))
                         :lt.ui.chrome/empty-state))))))

(deftest the-multibuffer-is-assembled-by-run
  (let [mb (view/multibuffer state)]
    (testing "two files, because a run touches files rather than a file"
      (is (= 2 (count (find-all mb :lt.ui.chrome/excerpt-header)))))
    (testing "the ordinary edit is a proposal"
      (is (= 1 (count (find-all mb :lt.ui.band/proposed-edit)))))
    (testing "the one you also edited is a conflict, not an error"
      (is (= 1 (count (find-all mb :lt.ui.band/conflict)))))
    (testing "and evidence is what the value was and what it becomes"
      (let [ev (attrs-of (first (find-all mb :lt.ui.band/evidence)))]
        (is (= "fs.readdir(dir)" (:before ev)))
        (is (= "fsp.readdir(dir)" (:after ev)))))))

(deftest settings-is-a-view-over-the-keymap
  (let [rows (find-all (view/settings state) :lt.ui.row/list-row)]
    (is (= 1 (count rows)))
    (is (re-find #"eval/form" (text-of (first rows)))
        "the binding shows the action vector, because that is what it is")))

(deftest the-window-is-one-function-of-one-value
  (let [w (view/window state)]
    (is (= :div.window (first w)))
    (testing "and it contains every view that has something to show"
      (is (seq (find-all w :lt.ui.chrome/tab)))
      (is (seq (find-all w :lt.ui.row/list-row)))
      (is (seq (find-all w :lt.ui.chrome/connection-row)))
      ;; The pane, not the multibuffer: the window shows one or the other, and
      ;; this state has an editor open. What the multibuffer contains is
      ;; `the-multibuffer-is-a-window-onto-the-edits` below.
      (is (seq (find-all w :lt.ui.pane/pane)))
      (is (empty? (find-all w :lt.ui.band/proposed-edit)))))
  (testing "an empty state renders rather than throwing, which is what a new window is"
    (is (vector? (view/window {})))))

(deftest the-multibuffer-is-a-window-onto-the-edits
  ;; Six excerpts is hiccup, six hundred is a virtual list — so only the ones
  ;; near where you are looking are rendered, and the rest are a count.
  (let [many (vec (for [i (range 60)]
                    {:at ["a.ts" i] :summary (str "edit " i) :applied? false
                     :evidence {:as-written "x" :if-applied "y"}}))
        state (-> state
                  (assoc-in [:runs "port-fuzzy" :edits] many)
                  (assoc-in [:review :at] 30))
        mb (view/multibuffer state)]
    (is (= 25 (count (find-all mb :lt.ui.chrome/excerpt-header)))
        "twelve either side of the cursor, and the cursor's own")
    (testing "and what is not rendered is said rather than dropped"
      (let [folds (map attrs-of (find-all mb :lt.ui.chrome/fold-row))]
        (is (= [18 17] (map :lines folds)))))
    (testing "keyed by address, so an excerpt that scrolls out and back is the same node"
      (is (= ["a.ts" 18] (:replicant/key (attrs-of (first (find-all mb :div.excerpt-group)))))))))

(deftest a-review-cursor-outside-the-list-does-not-take-the-window-down
  ;; The cursor is state and the list is a projection, so the two are allowed
  ;; to disagree for a moment.
  (let [past-the-end (assoc-in state [:review :at] 99)]
    (is (vector? (view/multibuffer past-the-end)))
    (is (= 2 (count (find-all (view/multibuffer past-the-end) :lt.ui.chrome/excerpt-header)))))
  (let [nothing (-> state (assoc-in [:runs "port-fuzzy" :edits] []) (assoc-in [:review :at] 5))]
    (is (vector? (view/multibuffer nothing)))
    (is (empty? (find-all (view/multibuffer nothing) :lt.ui.chrome/excerpt-header)))))
