# Light Table, from a shell that does not want to remember npm script names.
#
# A wrapper, not a build system. The npm scripts in package.json and the shell
# scripts in script/ stay the source of truth — CI runs those, and a Makefile
# that reimplemented them would be a second thing to keep true. Everything here
# is one line deep on purpose.
#
#   make            what you probably want: build and run
#   make help       every target, with what it does

.DEFAULT_GOAL := run
.PHONY: help deps build build-cljs build-main build-plugins run check test \
        smoke lint typecheck repl clean dist screenshot

help: ## Show this list
	@grep -hE '^[a-z-]+:.*##' $(MAKEFILE_LIST) \
	  | sort | awk 'BEGIN {FS = ":.*## "}; {printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'

## ── Building ──────────────────────────────────────────────────────────────

deps: ## Install every dependency, including the Electron binary
	npm install
	cd deploy/core && npm install
	cd deploy/electron && npm install && npx --no-install install-electron

build: ## Full build: dependencies, plugins, and a packaged app in builds/
	script/build.sh

dist: ## build, plus a release archive
	script/build.sh --release

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

## ── Checking ──────────────────────────────────────────────────────────────

check: lint typecheck ## Lint and type-check everything

lint: ## clj-kondo over ClojureScript, eslint over JavaScript and TypeScript
	npm run lint:cljs
	npm run lint:js

typecheck: ## tsc --noEmit over all five TypeScript projects
	npm run typecheck

test: ## Unit tests, under node
	npm test

smoke: ## Boot the real application and check it — needs a build first
	script/smoke-test.sh

## ── Housekeeping ──────────────────────────────────────────────────────────

clean: ## Remove build output. Leaves node_modules and the Electron download.
	rm -rf builds target src-gen .shadow-cljs
	rm -rf deploy/core/lighttable/bootstrap.js deploy/core/lighttable/bootstrap.js.map \
	       deploy/core/lighttable/user.js deploy/core/lighttable/paredit.js \
	       deploy/core/lighttable/ws.js deploy/core/lighttable/background/worker.js \
	       deploy/core/lighttable/cljs deploy/core/lighttable/shadow \
	       deploy/core/main.js deploy/core/main.js.map \
	       deploy/core/preload.js deploy/core/preload.js.map \
	       deploy/core/browserInjection.js deploy/core/browserInjection.js.map
