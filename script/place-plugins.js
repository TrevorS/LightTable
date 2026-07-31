#!/usr/bin/env node
/*jshint esversion: 8 */
"use strict";

// Puts built plugins where Light Table's loader looks for them.
//
// Plugins in this repository are built two ways. TypeScript ones compile with
// tsc straight into their own directory. ClojureScript ones are modules of the
// :app build, so shadow writes them beside the bundle instead — and the loader
// resolves a plugin's load-js path relative to the plugin's own directory, so
// they have to be moved next to their plugin.edn.
//
// Then every plugin directory is copied into deploy/plugins/, alongside the
// published ones script/build.sh clones.
//
// Run by both `npm run build:cljs` and `npm run build:plugins`. It has to be
// part of the ClojureScript build rather than a step after it: a module
// references the bundle's hoisted constants (cljs$cst$NNN), and those are
// numbered per-compilation, so a plugin module left over from an earlier build
// fails at load with an undefined constant. Placing it in the same command that
// produced it is what stops that from being possible.

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const SRC = path.join(ROOT, 'plugins');
const MODULES = path.join(ROOT, 'deploy', 'core', 'lighttable');
const DEST = path.join(ROOT, 'deploy', 'plugins');

// ClojureScript plugins: shadow module name -> the file its plugin.edn expects.
// The default user plugin is not here because it does not live in plugins/;
// `npm run place:user-plugin` handles that one.
const CLJS_PLUGINS = {
    Paredit: { module: 'paredit.js', as: 'paredit_compiled.js' },
    Clojure: { module: 'clojure.js', as: 'clojure_compiled.js' },
    Javascript: { module: 'javascript.js', as: 'javascript_compiled.js' },
    CSS: { module: 'css.js', as: 'css_compiled.js' },
    HTML: { module: 'html.js', as: 'html_compiled.js' },
    Python: { module: 'python.js', as: 'python_compiled.js' }
};

function main() {
    if (!fs.existsSync(SRC)) return;
    fs.mkdirSync(DEST, { recursive: true });

    for (const name of fs.readdirSync(SRC).sort()) {
        const dir = path.join(SRC, name);
        // types/ and lib/ are shared sources, not plugins. A plugin.edn is what
        // makes a directory one.
        if (!fs.existsSync(path.join(dir, 'plugin.edn'))) continue;

        const cljs = CLJS_PLUGINS[name];
        if (cljs) {
            const built = path.join(MODULES, cljs.module);
            if (!fs.existsSync(built)) {
                console.error(`${name}: ${cljs.module} is missing. Run \`npm run build:cljs\` first.`);
                process.exit(1);
            }
            fs.copyFileSync(built, path.join(dir, cljs.as));
        }

        const dest = path.join(DEST, name);
        fs.rmSync(dest, { recursive: true, force: true });
        // verbatimSymlinks, because node's cp resolves a symlink's target to an
        // absolute path unless told not to. A plugin's node_modules/.bin holds
        // relative links like ../acorn/bin/acorn; copied without this they
        // became absolute paths into whoever's checkout built the release, and
        // codesign rejected the app bundle for it — "invalid destination for
        // symbolic link in bundle", followed by "the signature did not verify;
        // the app may not launch".
        fs.cpSync(dir, dest, { recursive: true, verbatimSymlinks: true });
        console.log('placed ' + name);
    }
}

main();
