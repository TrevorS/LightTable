(ns lt.core
  "Entry point for the compiled bundle.

  Light Table registers its behaviors, commands and objects as a side effect of
  namespaces being loaded, and many of those namespaces are not required by any
  other. Under lein-cljsbuild that did not matter: :simple compiles everything
  on the source path into one file. A bundler only keeps what an entry point can
  reach, so the namespaces nothing else pulls in are listed here explicitly."
  (:require [lt.objs.app :as app]
   ;; Generated: every CodeMirror mode and fold addon, so they are bundled.
   ;; Light Table's own JavaScript, bundled.
   [lt.window.modules :as modules]
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
            [lt.actions.effects :as effects]
            [lt.ui.bands :as bands]
            [lt.ui.catalogue]
            [lt.ui.kit]
            [lt.ui.pane]
            [lt.ui.window]
            [lt.util.style]))

;; Teach Replicant that an event handler may be data rather than a closure.
;; Global, and set once: without it every `:on {:click [[:review/goto 3]]}` in
;; the kit is a vector where a function was expected, and nothing happens.
(actions/install!)
(effects/install!)

;; One watcher drives both halves of the design's structural claim: the chrome
;; renders from the state by value, and the bands are drawn into the editor's
;; own line widgets by effect. See doc/rendering.md.
(bands/install!)

;; Light Table's themes are written against CodeMirror 5's class names, and
;; CodeMirror 6 calls its elements something else. Rather than edit thirty theme
;; files — and break every theme a user wrote — each rule that names a
;; CodeMirror 5 class gets a twin naming the CodeMirror 6 one, against the
;; stylesheet as loaded. See src-window/cm6-theme.ts.
(.watchForThemes modules/cm6-theme)
