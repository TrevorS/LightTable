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
            [lt.objs.dev]
            [lt.objs.editor.treesitter]
            [lt.objs.clients.lsp]
            [lt.objs.docs]
            [lt.objs.find]
            [lt.objs.intro]
            [lt.objs.jump-stack]
            [lt.objs.langs.keymap]
            [lt.objs.search]
            [lt.objs.sidebar.navigate]
            [lt.objs.sidebar.workspace]
            [lt.objs.version]
            [lt.plugins.auto-complete]
            [lt.plugins.auto-paren]
            [lt.plugins.doc]
            [lt.plugins.watches]
            [lt.util.style]))
