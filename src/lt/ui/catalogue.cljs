(ns lt.ui.catalogue
  "The kit, rendered from the kit.

  The design document draws twenty-five components and this draws the same
  twenty-five from the aliases the editor actually uses — so the document and
  the implementation cannot disagree about one of them without the disagreement
  being visible. That was the design's own proposed next step, and it is the
  only way a component catalogue is worth having: a picture of a component that
  is not the component is a picture that goes stale.

  Three things follow the document rather than convention, and each is load
  bearing:

  * A card is named for the alias it draws — `chrome/status-dot`, not
    `StatusDot` — because the name on the card is the name you type.
  * A card lists the props it takes, and those are the keys the alias actually
    destructures. A prop table copied from a design note is a second source of
    truth.
  * The closing table is the registry itself, not a list. An alias nobody drew
    here shows up in it marked, which is the disagreement made visible — and
    `test-e2e/catalogue.spec.ts` fails on a mark, so it is a build failure
    rather than a note somebody reads later.

  **Light Table: Component kit** opens it."
  (:require [clojure.string :as string]
            [lt.object :as object]
            [lt.objs.command :as cmd]
            [lt.objs.style :as style]
            [lt.objs.tabs :as tabs]
            [lt.ui :as ui]
            [lt.ui.band :as band]
            [lt.ui.chrome :as chrome]
            [lt.ui.kit :as kit]
            [lt.ui.row :as row]
            ;; Required for their `story/of` calls, which are what fill the
            ;; registry every `told-card` below reads. Requiring the component
            ;; namespaces is not enough: an alias exists without anything
            ;; having described it.
            [lt.ui.stories.band]
            [lt.ui.stories.chrome]
            [lt.ui.stories.host]
            [lt.ui.stories.pane]
            [lt.ui.stories.row]
            [lt.ui.story :as story]
            [lt.ui.view :as view])
  (:require-macros [lt.macros :refer [behavior]]))

;;*********************************************************
;; the card
;;*********************************************************

(defn- card
  "One component, in every state it has, on the ground it actually sits on.

  `spec` is the card's own metadata rather than positional arguments, because
  by the fourth optional one — a badge, a width, a props table, a usage line —
  positions stop being readable.

  * `:ns` and `:nm` — the alias, split so the namespace can recede
  * `:badge` — for the things here that look like components and are
    deliberately not registered as any
  * `:desc` — one sentence, the same one that is in the source
  * `:width` — `:wide` for anything the width of a buffer, `:narrow` for an
    atom with three states
  * `:props` — `[name type note]` triples, naming keys the alias destructures
  * `:usage` — where it is drawn, so a component nothing uses is obvious"
  [{:keys [ns nm badge desc width props usage]} & demo]
  [:div {:class ["kit__card" (case width
                               :wide "kit__card--wide"
                               :narrow "kit__card--narrow"
                               nil)]}
   [:div.kit__card-head
    [:span.kit__name (when ns [:span.kit__ns ns]) nm]
    (when badge [:span.kit__badge badge])
    [:span.kit__desc desc]]
   [:div.kit__demo demo]
   (when (seq props)
     [:div.kit__props
      (for [[prop type hint] props]
        [:div.kit__prop {:replicant/key prop}
         [:span.kit__prop-name prop]
         [:span.kit__prop-type type]
         [:span.kit__prop-note hint]])])
   (when usage [:div.kit__usage "in " usage])])

(defn- told-card
  "The card for a component that has described itself in [[lt.ui.story]].

  Everything on it — the sentence, the prop table, where it is used, every
  state — comes from the one `story/of` call the Storybook build reads too.
  A card written here and a story written there is two answers to \"what states
  does this have?\", and two is the number that goes stale.

  `demo` is still accepted for the states that are a composition rather than a
  call, and appended after the ones the registry knows. A component with
  nothing but props needs none."
  [alias & demo]
  (let [{:keys [doc props usage width badge]} (get @story/registry alias)]
    (apply card {:ns (str (story/short-ns alias) "/")
                 :nm (name alias)
                 :desc doc
                 :props props
                 :usage usage
                 :width width
                 :badge badge}
           [:div.kit__demo-row
            (for [[state hiccup] (story/states alias)]
              [:span.status {:replicant/key state} hiccup (str state)])]
           demo)))

(defn- section [num title desc & cards]

  [:div.kit__section
   [:div.kit__section-head
    [:span.kit__section-num num]
    [:span.kit__section-name title]
    [:span.kit__section-desc desc]]
   [:div.kit__cards cards]])

(defn- note
  "What the demo above is doing, in the vocabulary of the token sheet.

  The document writes these as role names — \"element.selected — sky @ 15%\" —
  so that a declaration in `kit.css` can be checked against the page by reading
  it."
  [& body]
  [:div.kit__demo-note body])

;;*********************************************************
;; 01 · atoms
;;*********************************************************

(defn- themed
  "A ground carrying the class the editor's own theme is scoped by.

  Read rather than named, so the two code cards show the theme that is actually
  loaded. Writing `cm-s-catppuccin-mocha` here would have made them a picture of
  one theme that goes on claiming to be the editor's after you change it — the
  precise failure this whole page exists to avoid."
  [& body]
  [:div {:class (str "cm-s-" (:theme @style/styles "default"))} body])

(defn- syntax
  "A token, in whatever colour the active theme gives that capture.

  Nothing here styles it: `treesitter.css` declares which capture names exist
  and a theme overrides the ones it has an opinion about, so this card is drawn
  by the theme file and changes when it does."
  [capture text]
  [:span {:class (str "cm-ts-" capture)} text])

(defn- code-line
  "A line of the gutter card, drawn with the real gutter.

  `band/gutter` rather than a span shaped like one, which is the whole
  discipline of this page: the alternative is markup here that looks like the
  gutter and stops being it the first time the gutter changes."
  [{:keys [line] :as opts} & body]
  [:div.kit__code {:class (when (:active? opts) "kit__code--active")}
   (band/gutter line opts)
   [:span body]])

(defn- atoms []
  (section
   "01 · aliases" "Atoms"
   (list "Seven aliases in "
         [:span.kit__name "lt.ui.chrome"]
         " that carry no layout of their own — plus two things that look like
          components here and are deliberately not registered as any.")

   ;; The first card told from the registry rather than written here. Its
   ;; sentence, prop table, usage line and ten states all come from the one
   ;; `story/of` call in lt.ui.stories.chrome, which the Storybook build reads
   ;; too. Module 3 converts the rest.
   (told-card ::chrome/status-dot)

   (told-card ::chrome/status)

   (told-card ::chrome/count-pill)

   (told-card ::chrome/kbd)

   (told-card ::chrome/chip)

   (card {:ns "band/" :nm "gutter" :badge "not an alias"
          :desc "The line number column, and the only place a marker would replace the number."
          :props [["line" "number" ""]
                  [":active?" "boolean" "the cursor line"]
                  [":marker" ":error | :warning | :info | :agent" "replaces the number"]]
          :usage "every band — with a line and nothing else, because a diagnostic here is drawn inline"}
         (themed
          (code-line {:line 88}
                     (syntax "punctuation" "(") (syntax "function" "send") " "
                     (syntax "variable-parameter" "client") (syntax "punctuation" ")"))
          (code-line {:line 89 :active? true}
                     (syntax "punctuation" "(") (syntax "keyword" "defn") " "
                     (syntax "function" "active?") " " (syntax "punctuation" "[")
                     (syntax "variable-parameter" "c") (syntax "punctuation" "]"))
          (code-line {:line 90 :marker :error}
                     "  " (syntax "punctuation" "(") (syntax "function" "str") " "
                     (syntax "variable-parameter" "c") (syntax "punctuation" ")")))
         (note "rest surface2 · active-line subtext0 · a marker replaces the number, never crowds it")
         (note "the marker is what an editor's own gutter would carry. Light Table draws a
                diagnostic beside the code instead — see band/diagnostic, and lt.objs.editor.lsp
                for why — so no band passes one."))

   (card {:nm "syntax tokens" :badge "not an alias"
          :desc "The twelve tree-sitter captures. Comments are the only cursive in the product."
          :props [["capture" "@keyword | @function | …" "tree-sitter capture name"]
                  ["lang" "string" "the same capture, any grammar"]]
          :usage "every code pane — drawn by the theme, not by the kit"}
         (themed
          [:div.kit__code
           (syntax "keyword" "defn") " " (syntax "function" "tab-label") " "
           (syntax "punctuation" "[") (syntax "variable-parameter" "e")
           (syntax "punctuation" "]") " " (syntax "string" "\"*\"") " "
           (syntax "number" "0") " " (syntax "type" "string") " "
           (syntax "property" ":name") " " (syntax "operator" "=>") " "
           (syntax "module" "fs") " " (syntax "attribute" "#[derive]") " "
           (syntax "comment" ";; note")])
         (note "keyword mauve · function blue · type yellow · param maroon · property lavender ·
                string green · number peach · operator sky · module flamingo · attribute teal ·
                punctuation overlay2 · comment overlay0 — as the active theme resolves them,
                and these are catppuccin-mocha's"))

   (told-card ::chrome/path-label)

   (told-card ::chrome/elapsed)))

;;*********************************************************
;; 02 · rows and chrome
;;*********************************************************

(defn- rows-and-chrome []
  (section
   "02 · aliases" "Rows and chrome"
   (list "Ten of the twelve rows-and-chrome aliases — "
         [:span.kit__name "panel-header"] " and " [:span.kit__name "empty-state"]
         " are drawn in 04 beside the views they serve. The first here is the only
          one that matters: the tree, the review queue, the run list, the command
          results and the connection panel are all "
         [:span.kit__name "row/list-row"] ".")

   (told-card ::row/list-row)

   (told-card ::row/tree-row)

   (told-card ::chrome/tab)

   (told-card ::chrome/excerpt-header)

   (told-card ::chrome/fold-row)

   (told-card ::chrome/breadcrumb)

   (told-card ::chrome/cause-row)

   (told-card ::chrome/action)

   (told-card ::chrome/action-cluster)

   (told-card ::chrome/connection-row)))

;;*********************************************************
;; 03 · bands
;;*********************************************************

;; A small SVG, so the image case is drawn by the code that draws images rather
;; than described. Base64 because that is the shape `value-content` takes.
(def ^:private swatch
  (str "PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHdpZHRoPSIyMjAiIGhlaWdodD0iNDgi"
       "PjxyZWN0IHdpZHRoPSIyMjAiIGhlaWdodD0iNDgiIGZpbGw9IiMxZTFlMmUiLz48Y2lyY2xlIGN4PSIzMCIg"
       "Y3k9IjI0IiByPSIxMiIgZmlsbD0iIzg5ZGNlYiIvPjxjaXJjbGUgY3g9IjcwIiBjeT0iMjQiIHI9IjEyIiBm"
       "aWxsPSIjY2JhNmY3Ii8+PGNpcmNsZSBjeD0iMTEwIiBjeT0iMjQiIHI9IjEyIiBmaWxsPSIjOTRlMmQ1Ii8+"
       "PGNpcmNsZSBjeD0iMTUwIiBjeT0iMjQiIHI9IjEyIiBmaWxsPSIjZjllMmFmIi8+PC9zdmc+"))

(defn- bands []
  (section
   "03 · bands" "Bands"
   (list "Six aliases in " [:span.kit__name "lt.ui.band"] " — plus "
         [:span.kit__name "value-content"]
         ", which is a public function rather than an alias because it is the
          decision, not the markup: a plugin teaching Light Table a new mime
          changes that and nothing else. The six are the only components that
          never appear in the chrome tree — the editor hands over a node per
          line widget and these render into it. A band's hiccup must be complete
          from its props alone, which is what lets the same alias draw here and
          in a live buffer.")

   (told-card ::band/result)

   (card {:ns "band/" :nm "value-content" :badge "public fn" :width :wide
          :desc "The value declares its type; the band picks who draws it."
          :props [["mime" "string" "application/edn, text/html, image/*, …"]
                  ["value" "any" "text, markup or base64"]]
          :usage "band/result, and any plugin teaching a new mime"}
         (band/value-content "application/edn" "({:count 2} {:count 1})")
         (band/value-content "image/svg+xml" swatch)
         (band/value-content "text/html" "<b>a sandboxed frame</b> — because a value is not trusted markup")
         (band/value-content "text/markdown" "**bold**")
         (note "the last is not a gap in the card. Nothing here renders markdown yet, so it
                says so rather than showing you asterisks and calling it a document —
                which is also what LSP hover text currently falls back to."))

   (told-card ::band/watch)

   (told-card ::band/evidence)

   (told-card ::band/proposed-edit)

   (told-card ::band/conflict)

   (told-card ::band/diagnostic)))

;;*********************************************************
;; 04 · views
;;*********************************************************

(def ^:private demo-state
  "A window's worth of state, so the views draw themselves rather than being
  described. The same shape `test/lt/ui/view_test.cljs` uses — a view is a
  function of the whole state, which is exactly what makes this possible."
  {:tabsets [{:id 0 :active 0 :active? true
              :tabs [{:id "src-worker/fuzzy.ts" :label "fuzzy.ts"
                      :path "src-worker/fuzzy.ts" :dirty? false :closable? true}
                     {:id "src-window/tabs.cljs" :label "tabs.cljs"
                      :path "src-window/tabs.cljs" :dirty? true}]}]
   :editors {"src-window/tabs.cljs" {:dirty? true}}
   :runs {"port-fuzzy" {:label "port fuzzy to ranges"
                        :status :executing
                        :edits [{:at ["src-worker/fuzzy.ts" 14] :applied? false}
                                {:at ["src-worker/fuzzy.ts" 22] :applied? false}
                                {:at ["src-window/tabs.cljs" 44] :applied? false}]}}
   :cursor {:line 89 :ch 33}
   :clients {51423 {:name "nREPL 51423" :kind :nrepl :status :finished :bound? true}
             :claude {:name "claude" :kind :agent :status :executing :via 51423}}
   :connect {:choosing? false :connectors []}
   :workspace {:roots ["src-worker" "notes.md"]
               :nodes {"src-worker" {:dir? true :open? true :loaded? true
                                     :children ["src-worker/lib" "src-worker/fuzzy.ts"]}
                       "src-worker/lib" {:dir? true :open? false :loaded? false}
                       "src-worker/fuzzy.ts" {:dir? false}
                       "notes.md" {:dir? false}}}
   :command-bar {:open? true
                 :query "eva"
                 :at 0
                 :commands [{:label "Evaluate this form" :action [:eval/form]}
                            {:label "Evaluate this editor" :action [:eval/editor]}]}
   :keymap {"⌘⏎" [[:eval/form]]}})

(defn- views []
  (section
   "04 · views" "Views"
   (list "Five of the nine views in " [:span.kit__name "lt.ui.view"]
         ", each a pure function of the whole state — not aliases, which is why
          the keyword says " [:span.kit__name "view/"]
         ". They are drawn here by calling them with a map, which is the same
          thing the test file does. The two aliases below them serve every
          panel, and none of the five is a toolbar.")

   (card {:ns "view/" :nm "titlebar" :width :wide
          :desc "Frameless. Tabs live in it, and a run is one of them."
          :props [[":tabsets" "one per tabset — a strip belongs to its column" ""]
                  [":runs" "id → run" "appended, because a run is a tab like any other"]]
          :usage "the strip of every tabset — lt.objs.tabs"}
         (view/titlebar demo-state)
         (note "drag to reorder is `:on-drag-start` and `:on-drop` — two state changes, no library"))

   (card {:ns "view/" :nm "connections" :width :narrow
          :desc "The clients, or the kinds of client there are. One panel, two lists."
          :props [[":clients" "id → client" "from lt.state.objects"]
                  [":connect" "{:choosing? :connectors}" "which of the two lists is up"]]
          :usage "the right bar of this window — lt.objs.sidebar.clients"}
         (view/connections demo-state)
         (note "`:choosing?` is which — not CSS hiding one of them"))

   (card {:ns "view/" :nm "workspace" :width :narrow
          :desc "The file tree, from a map of paths. A closed folder is not drawn rather than hidden."
          :props [[":workspace" "{:roots :nodes :renaming :recents}" "path → what is known about it"]
                  [":editors" "path → editor" "for the dirty dot"]
                  [":tabsets" "the active one's tabs" "which row is the file you are in"]]
          :usage "the left sidebar of this window — lt.objs.sidebar.workspace"}
         (view/workspace demo-state)
         (note "one row per visible path — a folder that is shut is not descended into"))

   (told-card ::chrome/panel-header)

   (card {:ns "view/" :nm "statusbar" :width :wide
          :desc "What is working, what it said, and what waits on you. 28px, mantle."
          :props [[":cursor" "{:line :ch}" "one-based on screen, zero-based in the data"]
                  [":runs" "id → run" "counts what is waiting on you"]
                  [":message" "{:text :tone}" "tone is :error or nothing"]
                  [":loading" "number?" "how many things are working, not whether"]
                  [":console" "{:unread :tone}" "absent when you are caught up"]]
          :usage "the bar along the bottom of this window — lt.objs.statusbar"}
         (view/statusbar (assoc demo-state
                                :message {:text "saved src-worker/fuzzy.ts"}
                                :loading 1))
         (view/statusbar (assoc demo-state
                                :message {:text "could not reach the language server" :tone :error}
                                :console {:unread 3 :tone :error}))
         (note "a status bar that reports what is fine is a status bar nobody reads"))

   (card {:ns "view/" :nm "command-bar" :width :wide
          :desc "A fuzzy search over the dispatch table. Elevated surface."
          :props [[":command-bar" "{:open? :query :at :commands}" ""]
                  [":keymap" "keys → actions" "the binding is found, not stored twice"]]
          :usage "one of the three surfaces that fall out of actions being data"}
         [:div {:style {:position "relative" :height "150px"}}
          (view/command-bar demo-state)])

   (told-card ::chrome/empty-state)))

;;*********************************************************
;; the kit is a table
;;*********************************************************

(def ^:private descriptions
  "One line each, for the table at the bottom.

  Keyed by the registered alias keyword rather than by a name, so this map and
  the registry are compared rather than assumed equal — see [[registry]]."
  {:lt.ui.row/list-row "Five surfaces are one component."
   :lt.ui.row/tree-row "list-row plus depth and a disclosure twist."
   :lt.ui.chrome/status-dot "Seven execution states plus connection health."
   :lt.ui.chrome/status "The dot with its label."
   :lt.ui.chrome/count-pill "A count that belongs to the thing beside it."
   :lt.ui.chrome/kbd "A binding, never a button."
   :lt.ui.chrome/chip "A scoped fact."
   :lt.ui.chrome/path-label "Only the leaf matters."
   :lt.ui.chrome/elapsed "Time, while it is still relevant."
   :lt.ui.chrome/tab "A run is a tab like any other."
   :lt.ui.chrome/excerpt-header "File, range, and whose edit it is."
   :lt.ui.chrome/fold-row "Never a number without a count."
   :lt.ui.chrome/breadcrumb "The path you walked into a value."
   :lt.ui.chrome/cause-row "Only the root cause gets actions."
   :lt.ui.chrome/action "One button, three weights."
   :lt.ui.chrome/action-cluster "The row that has an opinion about order."
   :lt.ui.chrome/connection-row "What an eval will actually reach."
   :lt.ui.chrome/panel-header "A label and a count. No toolbars."
   :lt.ui.chrome/empty-state "What is missing, and the narrowest fix."
   :lt.ui.band/result "The one the product is for."
   :lt.ui.band/watch "A value that re-reads itself."
   :lt.ui.band/evidence "What the value was, and what it becomes."
   :lt.ui.band/proposed-edit "Struck original, tinted replacement."
   :lt.ui.band/conflict "You edited a line a run had read."
   :lt.ui.band/diagnostic "An LSP diagnostic, in the buffer."})

(def ^:private hosted
  "The aliases that are registered and are not among the twenty-five.

  Both are the same idea and the document's closing note names it: DOM
  Replicant must never diff. Nothing inside either belongs to it, so neither is
  a component — they are the seam where something else's element is placed into
  hiccup, and what is on the other side is an editor or another object.

  They are aliases rather than functions for a reason worth the line:
  [[lt.ui.view]] names the pane by keyword rather than requiring it, because
  requiring it would load an editor and everything under one into a namespace
  whose whole value is being a pure function of a map.

  So the registry has two more entries than the kit has components, and this is
  the difference rather than a discrepancy."
  {:lt.ui.pane/pane "An editor, hosted. Replicant is told nothing about what is inside."
   :lt.ui.host/host "DOM another object owns, placed rather than described."})

(def ^:private view-descriptions
  "The nine, in the order [[lt.ui.view]] defines them."
  [["titlebar" "Drawn in 04. The strip of every tabset in this window."]
   ["review-queue" "The edits a run proposes for this buffer."]
   ["connections" "Drawn in 04. The connect panel in the right bar of this window."]
   ["workspace" "Drawn in 04. The file tree, in the left sidebar of this window."]
   ["sidebar" "review-queue then connections, in work order."]
   ["statusbar" "Drawn in 04. Its own render root — the cursor is a different clock."]
   ["command-bar" "Drawn in 04. A fuzzy search over the dispatch table."]
   ["multibuffer" "Excerpts by run. 12 either side of the cursor are real."]
   ["settings" "A view over the keymap, which is a view over the actions."]])

(defn- table-row
  "One alias, with its namespace receding the way a path's directories do.

  The split tolerates a keyword with no namespace in it. That cannot come from
  `defalias`, which always qualifies — but it can come from [[lt.ui.kit/redefine!]],
  which takes whatever key it is handed, and a table whose job is to show you
  what drifted should not be the thing that throws when something does."
  [k desc undrawn?]
  (let [nm (str k)
        cut (some-> (string/index-of nm "/") inc)]
    [:div.kit__table-row {:replicant/key k
                          :class (when undrawn? "kit__table-row--undrawn")}
     [:span.kit__table-name
      (when cut [:span.kit__ns (subs nm 0 cut)])
      (subs nm (or cut 0))]
     [:span.kit__table-desc desc]]))

(defn- registry
  "The kit as the registry holds it, which is the claim this page is making.

  Not a list written here: [[lt.ui.kit/aliases]] is asked, and an alias with no
  description is drawn marked rather than dropped. A component added to the kit
  and forgotten here shows up as a line with nothing beside it, which is the
  smallest possible version of the document and the implementation being unable
  to disagree quietly."
  []
  (let [registered (kit/aliases)
        known (merge descriptions hosted)
        kit-of (fn [ks] (filter #(contains? descriptions %) ks))]
    [:div.kit__card.kit__card--wide
     [:div.kit__card-head
      [:span.kit__name "The kit is a table"]
      [:span.kit__desc
       "Because a component is a keyword in a registry rather than a function, the
        set of them is a value: lt.ui.kit/aliases returns this, and kit/redefine!
        replaces one while the editor runs. That is the same claim as
        [tag behavior-keyword] in default.behaviors — what the editor is made of
        is data you can edit from inside it."]]
     [:div.kit__table
      [:div.kit__table-head
       (count (kit-of registered)) " aliases · markup, no state, no data access"]
      (for [k (kit-of registered)]
        (table-row k (get descriptions k) false))
      ;; Everything else the registry holds. An alias nobody described is drawn
      ;; marked rather than dropped — a component added to the kit and forgotten
      ;; here is exactly how a catalogue starts lying, and it is silent.
      (when-let [others (seq (remove #(contains? descriptions %) registered))]
        (list
         [:div.kit__table-head {:replicant/key :others}
          (count others) " registered, and not of the kit"]
         (for [k others]
           (table-row k
                      (get known k "— registered, and not drawn on this page")
                      (not (contains? known k))))))
      ;; A description with no alias behind it: the other direction of the same
      ;; disagreement, and the one that would otherwise be silent.
      (for [k (sort (remove (set registered) (keys descriptions)))]
        (table-row k "— drawn on this page, and not in the registry" true))
      [:div.kit__table-head
       (count view-descriptions) " views · pure functions of the whole state, the only things that read it"]
      (for [[nm desc] view-descriptions]
        [:div.kit__table-row {:replicant/key nm}
         [:span.kit__table-name [:span.kit__ns "lt.ui.view/"] nm]
         [:span.kit__table-desc desc]])]
     [:div.kit__usage
      "Three kinds, and the sort is the architecture: an alias is markup, a band is
       markup with a foreign parent, a view is the only thing that reads state. So
       every question about whether the window is right is a question about eight
       functions — and test/lt/ui/view_test.cljs asks them with a map, in
       milliseconds, with no editor and no DOM."]]))

(defn- refused []
  [:div.kit__note
   [:div.kit__note-name "What is deliberately not a component"]
   "Tabsets and splits are layout, not components. The multibuffer is a view
    composing excerpt-header, proposed-edit, evidence and fold-row, so it has no
    alias of its own. Nor does the editor pane: it is an empty keyed element with
    a mount hook, and everything inside belongs to CodeMirror — a diffing
    renderer that thought it owned those nodes would throw away the selection and
    the undo history on the next draw. The gutter column in 01 is a plain
    function in band.cljs rather than an alias — the registry is the set of
    components and it is not one, whatever it is public for; syntax tokens are
    not in the kit at all, being the editor's own theme. The rule: assembled one
    way, it is a view; assembled the same way four times, it is an alias."])

;;*********************************************************
;; the page
;;*********************************************************

(defn- toggle [this k label]
  [::chrome/action {:weight (if (get @this k) :secondary :tertiary)
                    :on-select (fn [_] (object/update! this [k] not))}
   label])

(defn- catalogue-ui [this]
  (list
   [:div.kit__title "Twenty-five components, and only one of them is the idea"]
   [:div.kit__blurb
    "Pulled apart, the whole editor is one row primitive, one band primitive and a
     small amount of chrome — which is the argument for building it this way:
     band/result is where the product lives, and everything else exists to stay
     out of its way. Every cell below is drawn by the same alias the editor uses,
     so this page and the implementation cannot disagree about a component."]
   [:div.kit__rules
    [:div.kit__rules-name "Two rules the kit enforces"]
    "A row tints entirely or not at all — no left-border accent strips. And no
     component owns a colour; it names a role and the theme resolves it."]
   [:div.kit__toggles
    (toggle this :props? "Props")
    (toggle this :usage? "Usage")]
   ;; The toggles are classes rather than conditionals so that the cards stay
   ;; pure functions of their own spec — nothing below has to be handed the
   ;; state of a checkbox at the top of the page.
   [:div.kit__body {:class [(when-not (:props? @this) "kit--no-props")
                            (when-not (:usage? @this) "kit--no-usage")]}
    (atoms)
    (rows-and-chrome)
    (bands)
    (views)
    (registry)
    (refused)]))

(behavior ::on-close-destroy
          :triggers #{:close}
          :reaction (fn [this]
                      (object/raise this :destroy)))

(object/object* ::catalogue
                :tags #{:kit.catalogue}
                :behaviors [::on-close-destroy]
                :name "Component kit"
                :props? true
                :usage? true
                :init (fn [this]
                        (ui/node this [:div.kit] catalogue-ui)))

(cmd/command {:command :kit.catalogue
              :desc "Light Table: Component kit"
              :exec (fn []
                      (let [c (object/create ::catalogue)]
                        (tabs/add! c)
                        (tabs/active! c)))})
