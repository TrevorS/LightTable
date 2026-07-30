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
// Run via `npm run build:plugins`, after `npm run build:cljs`.

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
    Paredit: { module: 'paredit.js', as: 'paredit_compiled.js' }
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
        fs.cpSync(dir, dest, { recursive: true });
        console.log('placed ' + name);
    }
}

main();
