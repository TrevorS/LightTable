(ns lt.ui.stories.field
  "What the six settings controls are, as data.

  These are the only components in the kit whose states are not a design
  decision. A behavior declares its parameters and their types, so the states
  below are the types — `:string`, `:number`, `:boolean`, `:list`, and the
  untyped case that has to keep working because 21 of the 49 settable behaviors
  are it.

  So the useful thing to see side by side here is not the styling. It is that
  every control is the same height and the same ground, because a settings
  screen generated from a registry has no author to make them line up."
  (:require [lt.ui.field :as field]
            [lt.ui.story :as story]))

(story/of ::field/field
  {:doc "A label, a control and an optional note. The unit the screen lays out."
   :props [[":label" "string" "the parameter's own :label"]
           [":note" "string?" "its :example, when it declared one"]]
   :usage "view/settings — one per parameter of a settable behavior"
   :note "a real <label> wrapping the control, so no generated id has to survive a re-render"
   :states (array-map
            :labelled {:label "Font family"
                       :children [[::field/text-input {:value "Menlo"}]]}
            :with-a-note {:label "Vector of rulers"
                          :note "e.g. [80]"
                          :children [[::field/text-input {:value "[80]"}]]})})

(story/of ::field/text-input
  {:doc "A parameter declared :string, and every parameter that declared no type."
   :props [[":value" "string" "what it is set to now"]
           [":placeholder" "string?" "the parameter's :example"]
           [":on-change" "actions" "on change, not on input — see the note"]]
   :usage "view/settings · field/control for :string and for no type at all"
   :note "change and not input: a write per keystroke is a behaviors reload per keystroke"
   :states (array-map
            :set {:value "Menlo"}
            :empty {:value "" :placeholder "Menlo"}
            :holding-edn {:value "[1 80]"})})

(story/of ::field/number-input
  {:doc "A parameter declared :number. Narrow, because a number is short."
   :props [[":value" "number" ""]
           [":placeholder" "string?" "the parameter's :example"]]
   :usage "view/settings — tab size, font size, hint limits"
   :states (array-map
            :set {:value 13}
            :empty {:value "" :placeholder "1000"})})

(story/of ::field/toggle
  {:doc "A parameter declared :boolean, and how a behavior with no parameters is set at all."
   :props [[":on?" "boolean" ""]
           [":on-change" "actions" "carries :event/checked rather than :event/value"]]
   :usage "view/settings — Use tabs?, and every parameterless behavior's row"
   :note "a checkbox drawn as one: a switch is prettier and has to explain which way is on"
   :states (array-map
            :on {:on? true}
            :off {:on? false})})

(story/of ::field/choice
  {:doc "A parameter declared :list, over whatever its :items function answers."
   :props [[":value" "string" ""]
           [":items" "coll" "strings, or [value label] pairs"]
           [":on-change" "actions" ""]]
   :usage "view/settings — theme and skin, whose items are what the plugins provided"
   :note ":items is called at render, because a plugin adds a theme after this namespace exists"
   :states (array-map
            :chosen {:value "dark" :items ["dark" "light" "solarized"]}
            :pairs {:value "cm-s-dark" :items [["cm-s-dark" "Dark"] ["cm-s-light" "Light"]]})})

(story/of ::field/source
  {:doc "Which file a value came from, when it did not come from the defaults."
   :props [[":from" "string?" "a full path; only the leaf is drawn"]]
   :usage "view/settings — beside the tag on every setting that has been changed"
   :note "the question a settings screen usually cannot answer: why is this not the default"
   :states (array-map
            :user {:from "/home/u/.lighttable/User/user.behaviors"}
            :workspace {:from "/home/u/src/thing/workspace.behaviors"}
            ;; Nil draws nothing at all, which is the common case and is worth
            ;; being a named state rather than an absence: a component that
            ;; returned an empty span would put a gap in every unchanged row.
            :from-the-defaults {:from nil})})
