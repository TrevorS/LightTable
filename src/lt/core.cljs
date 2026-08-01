(ns lt.core
  "Entry point for the compiled bundle.

  Light Table registers its behaviors, commands and objects as a side effect of
  namespaces being loaded, and many of those namespaces are not required by any
  other. Under lein-cljsbuild that did not matter: :simple compiles everything
  on the source path into one file. A bundler only keeps what an entry point can
  reach, so the namespaces nothing else pulls in are listed here explicitly."
  (:require [lt.objs.app :as app]
            [lt.compat]
   ;; Generated: every CodeMirror mode and fold addon, so they are bundled.
   [lt.editor.codemirror-modes]
   ;; Light Table's own JavaScript, bundled.
   [lt.window.modules]
            [lt.objs.browser]
            [lt.objs.clients.local]
            [lt.objs.connector]
            [lt.objs.control]
            [lt.objs.dev]
            [lt.objs.editor.treesitter]
            [lt.objs.editor.lsp]
            [lt.objs.docs]
            [lt.objs.find]
            [lt.objs.intro]
            [lt.objs.jump-stack]
            [lt.objs.langs.keymap]
            [lt.objs.search]
            [lt.objs.session]
            [lt.objs.sidebar.navigate]
            [lt.objs.sidebar.workspace]
            [lt.objs.version]
            [lt.plugins.auto-complete]
            [lt.plugins.auto-paren]
            [lt.plugins.doc]
            [lt.plugins.watches]
            ;; The component kit and the dispatch table it renders against.
            [lt.actions :as actions]
            [lt.ui.catalogue]
            [lt.util.style]))

;; Teach Replicant that an event handler may be data rather than a closure.
;; Global, and set once: without it every `:on {:click [[:review/goto 3]]}` in
;; the kit is a vector where a function was expected, and nothing happens.
(actions/install!)
