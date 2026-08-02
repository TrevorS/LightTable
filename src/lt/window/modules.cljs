(ns lt.window.modules
  "Light Table's own JavaScript, bundled rather than required at runtime.

  These are compiled from src-window/ into src-gen/lt/window/, which sits on
  the ClojureScript source path — so shadow-cljs resolves the relative requires
  below against the same classpath directory this namespace lives in, and the
  result ends up inside bootstrap.js.

  They used to be loaded with `js/require` against a path assembled at runtime.
  That works only while the window has `require`, which it loses when
  contextIsolation goes on."
  ;; Aliased apart from the vars below: `(def fuzzy fuzzy)` binds the var to
  ;; itself rather than to the module, and the failure is a null dereference
  ;; somewhere else entirely.
  (:require ["./fuzzy.js" :as fuzzy-js]
            ["./treesitter.js" :as treesitter-js]
            ["./cm-hint.js" :as cm-hint-js]
            ;; The editor. `lt.objs.editor` builds one of these; the rest
            ;; register themselves on the window so a test and a REPL can reach
            ;; them. See src-window/cm6.ts.
            ["./cm6.js" :as cm6-js]
            ["./cm6-editor.js" :as cm6-editor-js]
            ["./cm6-theme.js" :as cm6-theme-js]
            ["./cm6-commands.js"]
            ["./cm6-modes.js"]
            ["./cm6-treesitter.js"]))

(def ^js fuzzy
  "Fuzzy matching for the command bar and the file navigator: stringScore,
  score, fastScore.

  `wrapMatch` was here too — it returned a string with `<em>` in it, for
  `innerHTML`. A row is hiccup now and `lt.ui.filter/highlight` builds it from
  the same match positions, so the string form had no caller left."
  fuzzy-js)

(def ^js treesitter
  "Tree-sitter highlighting: initRuntime, highlighterFor, captureClasses,
  spansFromCaptures, styleAt, runsForLine, tokenClasses. See
  src-window/treesitter.ts; the drawing half is cm6-treesitter.ts."
  treesitter-js)

(def ^js cm-hint
  "Where the autocomplete list goes: positionHint, ensureHintVisible. Takes any
  editor that can say where its cursor is. See src-window/cm-hint.ts."
  cm-hint-js)

(def ^js cm6
  "The band field: bandField, setBands, Band. Bands go through the editor
  rather than through here — see [[cm6-editor]]."
  cm6-js)

(def ^js cm6-editor
  "A CodeMirror 6 editor answering to CodeMirror 5's method names:
  makeCm6Editor, Cm6Editor. See src-window/cm6-editor.ts."
  cm6-editor-js)

(def ^js cm6-theme
  "Light Table's themes, applied to a CodeMirror 6 editor: the CodeMirror 5
  token classes as a HighlightStyle, and the structural rules mirrored onto
  CodeMirror 6's own selectors. See src-window/cm6-theme.ts."
  cm6-theme-js)
