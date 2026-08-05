# Light Table, from a shell that does not want to remember npm script names.
#
# A wrapper, not a build system. The npm scripts in package.json and the shell
# scripts in script/ stay the source of truth — CI runs those, and a Makefile
# that reimplemented them would be a second thing to keep true. Everything here
# is one line deep on purpose.
#
#   make            what you probably want: build and run
#   make help       every target, with what it does
#
# The test targets run headless. To watch one:
#
#   make smoke ARGS=--headed
#   make test-e2e ARGS="--headed --grep window"

.DEFAULT_GOAL := run
.PHONY: help deps build build-cljs build-main build-plugins run check test \
        test-cljs test-electron test-e2e smoke lint typecheck docs repl clean \
        clean-all dist screenshot doctor audit

help: ## Show this list
	@grep -hE '^[a-z-]+:.*##' $(MAKEFILE_LIST) \
	  | sort | awk 'BEGIN {FS = ":.*## "}; {printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'

## ── Building ──────────────────────────────────────────────────────────────

deps: ## Install every dependency, including the Electron binary and ripgrep
	npm install
	cd deploy/core && npm install
	cd deploy/electron && npm install && npx --no-install install-electron
	node script/fetch-ripgrep.mts

build: ## Full build: dependencies, plugins, and a packaged app in builds/
	script/build.sh

dist: ## build, plus a release archive
	script/build.sh --release

install: ## Install the packaged build — make install DEST=~/Applications
	node script/install.mts

build-cljs: ## The window bundle and the worker, after a source change
	npm run build:cljs

build-main: ## The main process and preload only
	npm run build:main

build-plugins: ## The plugins in plugins/
	npm run build:plugins

## ── Running ───────────────────────────────────────────────────────────────

run: ## Run what is built, from the tree
	script/light.sh

repl: ## Boot the editor and attach a REPL to it (see script/lt-repl.sh)
	script/lt-repl.sh start

screenshot: ## Screenshot files in the editor — make screenshot FILES="a.ts b.clj"
	script/screenshot.sh $(FILES)

storybook: ## The component kit in a browser, on http://localhost:6106
	npm run storybook

storybook-build: ## A static Storybook in storybook-static/
	npm run storybook:build

storybook-check: ## Build it, then prove every story actually renders
	npm run --silent storybook:check

bench-search: ## How long a project-wide search takes — make bench-search ARGS="--all"
	npx shadow-cljs compile bench-search
	node target/bench-search.js $(ARGS)

## ── Checking ──────────────────────────────────────────────────────────────

check: ## Lint, type-check, and confirm doc/api matches the source
	npm run --silent check

lint: ## clj-kondo over ClojureScript, eslint over JavaScript and TypeScript
	npm run lint:cljs
	npm run lint:js

doctor: ## Is this checkout in a state where the next thing you try will work
	npm run --silent doctor

audit: ## Cross-checks no compiler or linter performs: dead code, unwired behaviors
	npm run --silent audit

typecheck: ## tsc --noEmit over every TypeScript project, tests included
	npm run typecheck

test: ## Every unit test: ClojureScript and the main process
	npm test

test-cljs: ## ClojureScript units, under node — no DOM, no Electron
	npm run test:cljs

test-electron: ## Main-process units, under plain node
	npm run test:electron

test-e2e: ## Integration tests: the real app, driven by Playwright — needs a build
	script/e2e.sh $(ARGS)

smoke: ## Boot the real application and check it — needs a build first
	script/smoke-test.sh $(ARGS)

docs: ## Regenerate doc/api from the source docstrings
	npm run --silent docs:api

## ── Housekeeping ──────────────────────────────────────────────────────────

# Every module in shadow-cljs.edn's :modules. `clean` listed five of the nine
# once, so it left clojure.js, javascript.js, css.js, html.js and python.js
# behind — and a stale compiled artifact surviving a rebuild is a failure this
# project has already had.
#
# Expanded by make rather than by the shell. It was written
# `{bootstrap,user,…}.js` and make runs recipes with `/bin/sh`, which is bash
# on macOS and dash on Linux — and dash does not expand braces. So on every
# Linux machine this was `rm -f` against one file named `{bootstrap,user,…}.js`,
# which does not exist, and `-f` made that silent. The same bug as the missing
# four, in a form that only showed up on the machines CI runs on.
CLJS_MODULES := bootstrap user paredit clojure javascript css html python
MODULE_JS := $(addprefix deploy/core/lighttable/,$(addsuffix .js,$(CLJS_MODULES)))

clean: ## Remove build output. Keeps node_modules, the Electron download, and the compiler caches.
	rm -rf builds target src-gen
	rm -f $(MODULE_JS) $(addsuffix .map,$(MODULE_JS))
	rm -rf deploy/core/lighttable/ws.js deploy/core/lighttable/background/worker.js \
	       deploy/core/lighttable/cljs deploy/core/lighttable/shadow \
	       deploy/core/lighttable/cljs-cache \
	       deploy/core/main.js deploy/core/main.js.map \
	       deploy/core/config.js deploy/core/config.js.map \
	       deploy/core/preload.js deploy/core/preload.js.map \
	       deploy/core/browserInjection.js deploy/core/browserInjection.js.map

clean-all: clean ## Also drop the ClojureScript compiler cache, forcing a cold rebuild
	rm -rf .shadow-cljs
