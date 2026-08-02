(ns lt.ui.filter-test
  "The list you narrow by typing.

  It backs the command bar, the file navigator, the syntax selector and the
  auto-complete hinter, and none of those had a test at any layer — the widget
  was a pool of `<li>` nodes repainted with `innerHTML`, so nothing about it
  was a value you could ask a question about.

  Two things are worth pinning. The highlight is hiccup rather than a string of
  HTML, which is what makes a file called `<img onerror=…>` a file with an
  unusual name. And the selection wraps against what is shown rather than
  against how many results there are."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [lt.support.hiccup :as h]
            [lt.ui.filter :as f]))

(defn- match
  "A `Match` as `src-window/fuzzy.ts` produces one: which positions were hit."
  [& positions]
  (let [m (js-obj)]
    (doseq [i positions] (aset m (str i) true))
    #js {:score 1 :matched m}))

(defn- result
  "One row of `indexed-results`' five-slot array."
  ([item text] (result item text nil))
  ([item text scored] #js [item text nil nil scored]))

(deftest a-run-of-matched-characters-is-one-em
  ;; One per run rather than one per character — the difference between reading
  ;; a name and reading a ransom note.
  (is (= [[:em "ab"] "cd"] (vec (f/highlight "abcd" (match 0 1)))))
  (is (= ["a" [:em "bc"] "d"] (vec (f/highlight "abcd" (match 1 2)))))
  (testing "and a match at both ends is two, not one"
    (is (= [[:em "a"] "bc" [:em "d"]] (vec (f/highlight "abcd" (match 0 3)))))))

(deftest a-name-is-text-even-when-it-looks-like-markup
  ;; The reason this is hiccup. The old highlight was a string with `<em>` in
  ;; it, set as `innerHTML` — so a file with an angle bracket in its name was
  ;; markup, and a file is a thing a person can name.
  (let [drawn (f/highlight "<img onerror=x>" (match 0))]
    (is (= [:em "<"] (first drawn)))
    (is (= "img onerror=x>" (second drawn)))
    (testing "nothing in it is a tag"
      (is (every? #(or (string? %) (= :em (first %))) drawn)))))

(deftest nothing-matched-is-the-text-unchanged
  (is (= ["plain"] (vec (f/highlight "plain" nil))))
  (is (= ["plain"] (vec (f/highlight "plain" (match))))))

(deftest a-row-is-drawn-for-every-result-that-fits
  (let [results [(result :a "alpha") (result :b "beta") (result :c "gamma")]]
    (is (= 3 (count (f/rows {:results results}))))
    (testing "`:size` is what the list shows, so the rest are not drawn"
      (is (= [:a :b] (map :item (f/rows {:results results :size 2})))))))

(deftest the-selection-wraps-against-what-is-shown
  ;; Not against the number of results: with more results than the list shows,
  ;; arrowing past the end has to come back to the top of what is on screen
  ;; rather than to a row nobody can see.
  (let [results (mapv #(result % (str %)) (range 10))
        selected-at (fn [i size]
                      (:item (first (filter :selected? (f/rows {:results results
                                                                :selected i
                                                                :size size})))))]
    (is (= 0 (selected-at 0 3)))
    (is (= 2 (selected-at 2 3)))
    (is (= 0 (selected-at 3 3)) "past the end of what is shown is the top of it")
    (is (= 2 (selected-at -1 3)) "and before the start is the bottom")
    (testing "nothing is selected when there is nothing to select"
      (is (empty? (filter :selected? (f/rows {:results [] :selected 0})))))))

(deftest a-transform-decides-what-a-row-says
  (let [results [(result {:path "src/a.cljs"} "src/a.cljs" (match 4))]
        drawn (f/rows {:results results
                       :search "a"
                       :transform (fn [text _ marked item]
                                    (list [:h2 (:path item)] [:p marked] [:span text]))})]
    (is (= 1 (count drawn)))
    (testing "it is handed the text, the match, the marked-up text and the item"
      (let [body (:body (first drawn))]
        (is (= "src/a.cljs" (h/text-of [:div (first body)])))
        (is (seq (h/find-all [:div body] :em)) "the marked text is hiccup, not a string")))
    (testing "and without one the row is the marked text"
      (is (seq (h/find-all [:div (:body (first (f/rows {:results results :search "a"})))] :em))))))

(deftest the-list-says-what-would-fill-it-when-it-is-empty
  ;; The sentence used to be `content:` on a `:before` rule, so "no command
  ;; matches" and "there are no files in your workspace" were a stylesheet's
  ;; opinion. It is the caller's now.
  (let [drawn (f/filter-list {:results [] :empty-what "No files to navigate"
                              :empty-why "Add a folder."} {})]
    (is (seq (h/find-all drawn :lt.ui.chrome/empty-state)))
    (is (contains? (h/classes-in drawn) "filter-list--empty")))
  (testing "and a list with results says nothing of the sort"
    (let [drawn (f/filter-list {:results [(result :a "alpha")] :empty-what "x"} {})]
      (is (empty? (h/find-all drawn :lt.ui.chrome/empty-state)))
      (is (not (contains? (h/classes-in drawn) "filter-list--empty"))))))

(deftest choosing-a-row-is-mousedown-rather-than-click
  ;; The input has focus and blurring it is what closes the panel, so a click
  ;; would take the row away before the click landed on it.
  (let [chosen (atom nil)
        drawn (f/filter-list {:results [(result :a "alpha") (result :b "beta")]}
                             {:on-select (fn [i] (fn [_] (reset! chosen i)))})
        rows (h/find-all drawn :lt.ui.row/list-row)]
    (is (= 2 (count rows)))
    (is (nil? (:on-select (h/attrs-of (first rows)))))
    ((:on-mouse-down (h/attrs-of (second rows))) nil)
    (is (= 1 @chosen))))
