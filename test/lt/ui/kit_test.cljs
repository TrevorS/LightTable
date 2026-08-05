(ns lt.ui.kit-test
  "The kit, checked against the document that specifies it.

  `Light Table Kit.dc.html` draws twenty-five components and states two rules
  about all of them. Both are checkable, and this is where: a design document
  and an implementation agreeing on the day they were written is not worth much,
  and the way they stop agreeing is that a component is added, renamed or
  quietly given a colour of its own.

  Thirty-one now. The six that are not the document's are [[lt.ui.field]]'s, and
  this test is how they came to be written down — the settings screen added them
  and this failed, which is the whole of what it is for.

  Aliases are functions in a registry, so this needs no DOM and no editor —
  requiring the three namespaces registers them, and an alias is called the way
  Replicant calls it: `(f attrs children)`."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [clojure.string :as string]
            [lt.support.hiccup :as h]
            [lt.ui.band :as band]
            [lt.ui.chrome :as chrome]
            ;; Required so the registration is this file's doing rather than
            ;; something another test namespace happened to pull in.
            [lt.ui.field]
            [lt.ui.row]
            [replicant.alias :as alias]))

(def ^:private documented
  "The twenty-five, exactly as the document's closing table lists them.

  Written out rather than derived, because this is the half of the comparison
  that is supposed to be independent of the code."
  #{:lt.ui.row/list-row
    :lt.ui.row/tree-row
    :lt.ui.chrome/status-dot
    :lt.ui.chrome/status
    :lt.ui.chrome/count-pill
    :lt.ui.chrome/kbd
    :lt.ui.chrome/chip
    :lt.ui.chrome/path-label
    :lt.ui.chrome/elapsed
    :lt.ui.chrome/tab
    :lt.ui.chrome/excerpt-header
    :lt.ui.chrome/fold-row
    :lt.ui.chrome/breadcrumb
    :lt.ui.chrome/cause-row
    :lt.ui.chrome/action
    :lt.ui.chrome/action-cluster
    :lt.ui.chrome/connection-row
    :lt.ui.chrome/panel-header
    :lt.ui.chrome/empty-state
    :lt.ui.band/result
    :lt.ui.band/watch
    :lt.ui.band/evidence
    :lt.ui.band/proposed-edit
    :lt.ui.band/conflict
    :lt.ui.band/diagnostic

    ;; The six the settings screen added. Not in the document, which predates
    ;; it, and written out here for the same reason the twenty-five are: the
    ;; point of this list is to be the half of the comparison that does not
    ;; come from the code.
    ;;
    ;; They are a family rather than six decisions. A behavior declares its
    ;; parameters and their types, so what the kit needed was one control per
    ;; type and two pieces of chrome around them — see [[lt.ui.field]].
    :lt.ui.field/field
    :lt.ui.field/text-input
    :lt.ui.field/number-input
    :lt.ui.field/toggle
    :lt.ui.field/choice
    :lt.ui.field/source})

(defn- registered []
  (set (keys (alias/get-registered-aliases))))

(defn- expand
  "What alias `k` draws, given `attrs` and `body` — the call Replicant makes."
  ([k attrs] (expand k attrs nil))
  ([k attrs body]
   ((get (alias/get-registered-aliases) k) attrs body)))

(deftest the-kit-is-the-twenty-five-the-document-draws
  ;; The three kit namespaces and nothing else, which is what makes the count
  ;; exact here: a live window also has `:lt.ui.pane/pane`, registered by a
  ;; namespace that loads an editor and so cannot be required under node. That
  ;; one is the registry's twenty-sixth entry and deliberately not of the kit —
  ;; `test-e2e/catalogue.spec.ts` is where the running registry is checked.
  (let [registered (registered)]
    (testing "nothing in the document is missing from the registry"
      (is (empty? (sort (remove registered documented)))))
    (testing "and nothing in the registry is missing from the document"
      ;; A component added to the kit and not written down is the usual way a
      ;; catalogue starts lying, and it is silent — this is the noise.
      (is (empty? (sort (remove documented registered)))))
    (is (= 31 (count registered)))))

(deftest no-component-can-draw-nothing
  ;; A rule the document does not state and the framework enforces brutally.
  ;; Replicant takes an alias's return value *as the node*, so an alias that
  ;; returns nil becomes `createElement(":lt.ui.field/source")` — an
  ;; `InvalidCharacterError` thrown inside Replicant's own render, which it
  ;; catches, logs as "you may have misbehaving aliases", and then skips the
  ;; whole render. Nothing looks broken; a surface is simply blank.
  ;;
  ;; `field/source` was written `(when from …)` and `setting-row` passes `:from`
  ;; for every setting, so the first setting still at its default would have
  ;; taken the settings screen with it. A story state written to show "this draws
  ;; nothing" is what found it, in a browser. This is the same question asked
  ;; without one.
  ;;
  ;; Called with no attributes at all, deliberately: that is the state a
  ;; component is likeliest to be nil in, and any component that cannot survive
  ;; it is one a view has to remember to guard.
  (let [empty-handed (for [[k f] (alias/get-registered-aliases)
                           :when (nil? (f {} nil))]
                       k)]
    (is (empty? (sort empty-handed))
        "an alias must always return an element — see lt.ui.field/source")))

(deftest no-component-owns-a-colour
  ;; The second of the two rules the document states, and the reason the token
  ;; sheet exists: a component that named a colour could not be reskinned. Every
  ;; alias is expanded and its inline styles are read, because a `:style` map is
  ;; the only place hiccup can name one.
  (let [colour? #{:color :background :background-color :border-color :fill :box-shadow}
        offenders (for [[k f] (alias/get-registered-aliases)
                        :let [drawn (f {:line 1 :count 2 :ms 1000 :lines 3 :keys "⏎"
                                        :path "a/b.ts" :root "@x" :segments [:a]
                                        :siblings [1 2] :status :executing :depth 1
                                        :message "m" :value "v" :before "b" :after "a"
                                        :yours "y" :name-of "n" :what "w"}
                                       "body")]
                        node (h/nodes drawn)
                        :let [style (when (map? (second node)) (:style (second node)))]
                        :when (map? style)
                        [prop] style
                        :when (colour? prop)]
                    [k prop])]
    (is (empty? offenders)
        "a component names a role and kit.css resolves it")))

(deftest a-row-tints-entirely-or-not-at-all
  ;; The first rule. A left-border accent strip is the thing it forbids, and the
  ;; way it would come back is as a `border-left` on the row or as a modifier
  ;; class that a stylesheet hangs one off — so the tone is asserted to be a
  ;; whole-row class and nothing else.
  (doseq [tone [:warning :error :agent :disabled]]
    (let [classes (h/classes-in (expand :lt.ui.row/list-row {:tone tone} "x"))]
      (is (contains? classes (str "row--" (name tone)))
          (str "tone " tone " is a role the stylesheet resolves"))))
  (let [drawn (expand :lt.ui.row/list-row {:tone :error} "x")
        style (:style (second drawn))]
    (is (nil? (:border-left style)))
    (is (nil? (:border-left-color style)))))

(deftest an-alias-draws-every-attribute-it-is-handed
  ;; The failure this exists for is silent, and it had already happened: an
  ;; alias is called with its call site's attrs as an argument, and Replicant
  ;; merges *none* of them onto what it returns — `from-alias` carries the key
  ;; and nothing else. So an attribute the alias does not name is dropped
  ;; without a warning, the markup still looks right, and the tree renders flat.
  (testing "a tree row is indented by its depth"
    (doseq [[depth expected] {0 "8px" 1 "22px" 3 "50px"}]
      (let [drawn (expand :lt.ui.row/tree-row {:depth depth} "f.ts")
            ;; The expansion is the list-row alias node, which is expanded in
            ;; turn — so the indent has to survive being passed *between* two
            ;; aliases, which is exactly where it was being lost.
            row (expand :lt.ui.row/list-row (second drawn) (drop 2 drawn))]
        (is (= expected (get-in (second row) [:style :padding-left]))
            (str "depth " depth)))))
  (testing "and the twist sits in the leading slot, so names line up down the list"
    (let [drawn (expand :lt.ui.row/tree-row {:depth 0 :open? true} "src")]
      (is (contains? (h/classes-in (expand :lt.ui.row/list-row (second drawn) (drop 2 drawn)))
                     "row__leading")))))

(deftest a-band-is-complete-from-its-props-alone
  ;; What lets the same alias draw in the catalogue and inside a line widget in
  ;; a live buffer. Every band is expanded with nothing but props and asked for
  ;; the parts the document says it has.
  (testing "a result carries its own gutter, so it lands on the code"
    (is (contains? (h/classes-in (expand :lt.ui.band/result {:line 6 :value "v"}))
                   "band__gutter")))
  (testing "a stale value is marked rather than cleared"
    (let [classes (h/classes-in (expand :lt.ui.band/result
                                      {:line 6 :value "v" :status :lost :stale? true}))]
      (is (contains? classes "band--stale"))
      (is (contains? classes "band__stale"))))
  (testing "a watch inside a loop is a sequence, not its last value"
    (let [drawn (expand :lt.ui.band/watch {:line 19 :history [0 1 2] :reads 3})]
      (is (contains? (h/classes-in drawn) "band__history"))
      (is (= 3 (count (filter #(= :span.band__read (first %)) (h/nodes drawn)))))))
  (testing "severity carries the diagnostic's tint, so a warning is not an error"
    (is (contains? (h/classes-in (expand :lt.ui.band/diagnostic {:line 1 :message "m"}))
                   "band--error")
        "error by default")
    (is (contains? (h/classes-in (expand :lt.ui.band/diagnostic
                                       {:line 1 :severity :warning :message "m"}))
                   "band--warning")))
  (testing "and no band replaces its line number with a marker"
    ;; A diagnostic in this editor is drawn beside the code, not as a mark in a
    ;; column you then have to hover — `lt.objs.editor.lsp` is where that is
    ;; argued, and a band that swapped its address for a dot would be quietly
    ;; reversing it. Every band says which line it belongs to.
    (doseq [[k attrs] {:lt.ui.band/diagnostic {:line 27 :severity :warning :message "m"}
                       :lt.ui.band/conflict {:line 44 :yours "y"}
                       :lt.ui.band/result {:line 6 :value "v"}
                       :lt.ui.band/watch {:line 19 :value "v"}
                       :lt.ui.band/proposed-edit {:line 14 :before "b" :after "a"}}]
      (let [gutter (first (filter #(= :div.band__gutter (first %))
                                  (h/nodes (expand k attrs))))]
        (is (some #(= (:line attrs) %) (flatten gutter))
            (str k " names the line it belongs to"))
        (is (empty? (filter #(string/starts-with? % "dot--") (h/classes-in gutter)))
            (str k " draws no marker"))))))

(deftest the-gutter-column-still-takes-a-marker
  ;; Not an alias, so it is asked directly. The column is what an editor line's
  ;; gutter would be, and that is the one place a marker belongs — it replaces
  ;; the number rather than crowding it, because there is one column.
  (let [marked (band/gutter 90 {:marker :error})]
    (is (contains? (h/classes-in marked) "band__gutter--marked"))
    (is (contains? (h/classes-in marked) "dot--error"))
    (is (not (some #(= 90 %) (flatten marked))) "the number is gone, not beside it"))
  (is (contains? (h/classes-in (band/gutter 89 {:active? true})) "band__gutter--active"))
  (is (some #(= 88 %) (flatten (band/gutter 88)))))

(deftest evidence-holds-more-than-two-claims
  ;; "before, after, types, callers" — a component that could only hold two
  ;; would have decided for you which one to drop.
  (let [drawn (expand :lt.ui.band/evidence
                      {:ran-at 2000 :client "tsserver"
                       :rows [{:label "before" :value "TypeError" :tone :before}
                              {:label "after" :value "(\"a.ts\")" :tone :after}
                              {:label "types" :value "clean" :tone :after}]})]
    (is (= 3 (count (filter #(= :div.evidence__row (first %)) (h/nodes drawn)))))
    (testing "the age is shown rather than hidden"
      (is (string/includes? (pr-str drawn) "just now"))))
  (testing "and the two-row shorthand still draws two"
    (let [drawn (expand :lt.ui.band/evidence {:before "was" :after "is"})]
      (is (= 2 (count (filter #(= :div.evidence__row (first %)) (h/nodes drawn))))))))

(deftest time-is-formatted-from-an-age-rather-than-a-clock
  ;; Which is what keeps every component above renderable twice from the same
  ;; arguments — a component that read the clock could not be tested at all.
  (is (= "8s" (chrome/duration 8000)))
  (is (= "3m" (chrome/duration 180000)))
  (is (= "2h" (chrome/duration 7200000)))
  (is (= "just now" (chrome/ago 1000)))
  (is (= "40s ago" (chrome/ago 40000))))

(deftest a-value-that-is-not-data-is-handed-to-something-that-can-draw-it
  ;; The decision rather than the markup, which is why it is a function and not
  ;; an alias: a plugin teaching Light Table a new mime changes this and nothing
  ;; else.
  (is (= :div.band__value (first (band/value-content "application/edn" "{:a 1}"))))
  (is (= :div.band__value (first (band/value-content nil "plain"))))
  (is (= :div.band__host (first (band/value-content "text/html" "<b>x</b>"))))
  (is (= :div.band__host (first (band/value-content "image/png" "AAAA"))))
  (testing "and a mime nothing can draw says so rather than showing you its source"
    (let [drawn (band/value-content "text/markdown" "**bold**")]
      (is (= :div.band__note (first drawn)))
      (is (string/includes? (str drawn) "text/markdown")))))
