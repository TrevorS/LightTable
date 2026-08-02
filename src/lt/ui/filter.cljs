(ns lt.ui.filter
  "A list you narrow by typing, as data.

  The command bar, the file navigator, the syntax selector and the
  auto-complete hinter are all one widget. It was a fixed pool of `<li>` nodes
  repainted in place on every keystroke — the old renderer drew once and had no
  diffing step, so a per-keystroke redraw had to be a mutation — and every row
  was written with `innerHTML` from a string the caller built by concatenation.

  Two things follow from drawing it instead.

  **A row is hiccup, so a name is text.** The match highlight was
  `fuzzy/wrapMatch`, which returns a string with `<em>` in it, dropped into
  `innerHTML` alongside a file path or a command description that nobody
  escaped. [[highlight]] uses the same match positions to build hiccup, so a
  file called `<img onerror=…>` is a file with an unusual name rather than
  markup.

  **`:transform` returns hiccup.** It is handed the same four things as before
  — the candidate's text, its score, that text with the match marked, and the
  item itself — and what it returns is now a tree rather than a string.

  Not one of the kit's aliases and not one of the views: a filter list has
  instance state, because there are four of them and each has its own query and
  selection. It is drawn from its own atom through [[lt.ui/node]], which is what
  that exists for. The two functions below are the part with no atom in it."
  (:require [lt.ui.chrome :as chrome]
            [lt.ui.row :as row]))

(defn highlight
  "`text` with the characters `scored` matched wrapped in `[:em …]`.

  `scored` is a `Match` from `src-window/fuzzy.ts`: `:matched` is the set of
  character positions that the query hit, as an object keyed by index. Runs of
  them become one `em` rather than one per character, which is the difference
  between reading a name and reading a ransom note."
  [text scored]
  (if-not scored
    (list text)
    (let [matched (.-matched ^js scored)
          hit? (fn [i] (boolean (aget matched i)))]
      (->> (partition-by hit? (range (count text)))
           (map (fn [run]
                  (let [s (subs text (first run) (inc (last run)))]
                    (if (hit? (first run)) [:em s] s))))))))

(defn rows
  "What the list draws, as row maps, in the order they are shown.

  A result is the five-slot array `indexed-results` produces — the item, its
  text, and the scores — and this is the last place that shape is known about.

  `:size` caps the list. It used to be the size of the DOM pool, which is why
  the default is a hundred and why the selection wraps against it: with more
  results than the list can show, arrowing past the end goes back to the top of
  what is shown rather than to a row nobody can see."
  [{:keys [results selected size search transform]}]
  (let [shown (take (or size 100) results)
        cnt (count shown)
        cur (when (pos? cnt) (mod (or selected 0) cnt))
        transform (or transform (fn [_ _ marked _] marked))]
    (map-indexed
     (fn [i res]
       (let [text (aget res 1)
             scored (aget res 4)
             marked (if (seq search) (highlight text scored) (list text))]
         {:index i
          :selected? (= i cur)
          :item (aget res 0)
          :body (transform text scored marked (aget res 0))}))
     shown)))

(defn filter-list
  "The whole widget: an input, and the rows that survived what is in it.

  `handlers` carries the four things a person can do — type, choose a row,
  focus, and leave — as functions rather than action vectors, because a filter
  list belongs to an object rather than to the state atom. See the namespace
  docstring."
  [{:keys [placeholder search empty-what empty-why] :as state}
   {:keys [on-input on-select on-focus on-blur]}]
  (let [drawn (rows state)]
    [:div.filter-list {:class (when (empty? drawn) "filter-list--empty")}
     [:input.search {:type "text"
                     :placeholder (or placeholder "search")
                     :value (or search "")
                     :tabindex "0"
                     :on {:input on-input
                          :focus on-focus
                          :blur on-blur}}]
     [:div.filter-list__results
      (for [{:keys [index selected? body]} drawn]
        [::row/list-row {:replicant/key index
                         :selected? selected?
                         ;; `mousedown` rather than `click`: the input has
                         ;; focus, and a click would blur it first — which
                         ;; closes the panel out from under the row you are
                         ;; clicking.
                         :on-mouse-down (when on-select (on-select index))}
         ;; A block inside the row, because `.row` is a flex line and a
         ;; candidate is often two — a name and the path it is in, or a command
         ;; and what it is bound to — which would otherwise sit side by side.
         [:div.filter-list__row body]])
      ;; What an empty list says is the caller's, because "no command matches"
      ;; and "there are no files in your workspace" are different facts. It was
      ;; a `:before` rule on an `:empty` class, so the sentence lived in a
      ;; stylesheet.
      (when (and (empty? drawn) empty-what)
        [::chrome/empty-state {:what empty-what} empty-why])]]))
