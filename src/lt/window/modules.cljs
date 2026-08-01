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
            ["./dragdrop.js" :as dragdrop-js]
            ["./treesitter.js" :as treesitter-js]
            ;; Required for their side effects: each registers itself with the
            ;; global CodeMirror as it loads.
            ["./cm-search.js"]
            ["./cm-hint.js"]
            ;; CodeMirror 6, as a spike. Nothing in the editor uses it yet —
            ;; it puts `window.ltCm6` there so the mechanism a port would be
            ;; built on can be run and asserted about. See src-window/cm6.ts.
            ["./cm6.js"]
            ["./cm6-editor.js"]
            ["./cm6-commands.js"]
            ["./cm6-modes.js"]))

(def ^js fuzzy
  "Fuzzy matching for the command bar and the file navigator: stringScore,
  score, fastScore, wrapMatch."
  fuzzy-js)

(def ^js dragdrop
  "Drag-and-drop reordering for the tab bar: sortable."
  dragdrop-js)

(def ^js treesitter
  "Tree-sitter highlighting: initRuntime, highlighterFor, makeMode,
  captureClasses, spansFromCaptures, styleAt. See src-window/treesitter.ts."
  treesitter-js)
