#!/usr/bin/env node
/*jshint esversion: 8 */
"use strict";

// Drop what the in-window ClojureScript compiler will never read.
//
// `shadow-cljs release bootstrap` writes three directories. The compiler in
// the window — see src/lt/objs/cljs_compiler.cljs — reads exactly one and a
// half of them:
//
//   ana/    every namespace's analysis. This is the point of the build.
//   js/     compiled code, fetched only for namespaces the window does not
//           already have. It already has nearly all of them, because they are
//           the editor. What is left is the macro namespaces, which are
//           compile-time only and therefore in no bundle.
//   src/    never read at all. shadow's browser loader fetches :js-name and
//           :ana-name and nothing else.
//
// Left alone that is ~16MB shipped to read ~3MB of. This deletes src/ outright
// and deletes the js/ files whose namespaces the window already provides.
//
// Which namespaces those are is not guessed. A build that requires
// shadow.cljs.bootstrap.browser is a "bootstrap host", and shadow appends a
// literal `shadow.cljs.bootstrap.env.set_loaded([...])` to every module naming
// everything it provides. That call is what makes the split work at runtime,
// so reading it here means this script and the loader cannot disagree about
// which files are dead — they are reading the same list.

const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const LIGHTTABLE = path.join(ROOT, 'deploy', 'core', 'lighttable');
const CACHE = path.join(LIGHTTABLE, 'cljs-cache');

/**
 * Every namespace the built modules say they provide.
 *
 * shadow appends the call as a JSON array, and Closure rewrites long array
 * literals into `"a b c".split(" ")` — so a release says it one way and an
 * unoptimized build the other. Both are read here; matching only the array
 * was worth 7 files instead of 300 and looked like a working prune.
 */
function provided() {
    const names = new Set();
    const call = /shadow\.cljs\.bootstrap\.env\.set_loaded\(\s*(\[[\s\S]*?\]|"(?:[^"\\]|\\.)*")/g;
    for (const file of fs.readdirSync(LIGHTTABLE)) {
        if (!file.endsWith('.js')) continue;
        const text = fs.readFileSync(path.join(LIGHTTABLE, file), 'utf8');
        let m;
        while ((m = call.exec(text)) !== null) {
            const parsed = JSON.parse(m[1]);
            const list = Array.isArray(parsed) ? parsed : parsed.split(/\s+/);
            for (const ns of list) { if (ns) names.add(ns); }
        }
    }
    return names;
}

/**
 * What a compiled file provides.
 *
 * ClojureScript output says so itself, in goog.provide calls at the top.
 * shadow's bundled npm output does not — it is `shadow$provide[n]=` — but for
 * those the filename is the provide symbol, which is how shadow names them.
 */
function providesOf(file, head) {
    const goog = [...head.matchAll(/goog\.provide\(['"]([^'"]+)['"]\)/g)].map((m) => m[1]);
    if (goog.length) return goog;
    const name = file.replace(/^[0-9a-f]{8}\./, '').replace(/\.js$/, '');
    return name.startsWith('module$') ? [name] : [];
}

function bytes(n) { return (n / (1024 * 1024)).toFixed(1) + 'MB'; }

function rmDir(dir) {
    if (!fs.existsSync(dir)) return 0;
    let total = 0;
    for (const f of fs.readdirSync(dir)) total += fs.statSync(path.join(dir, f)).size;
    fs.rmSync(dir, { recursive: true, force: true });
    return total;
}

/**
 * Delete all but the newest file per namespace.
 *
 * Release output is named `<content-hash>.<ns>`, and shadow writes a new file
 * when a namespace changes without removing the old one — so rebuilding in
 * place grows the cache forever with versions the index no longer points at.
 * A duplicate only exists because this build rewrote one, so the newest is
 * the live one.
 */
function dropStale(dir) {
    if (!fs.existsSync(dir)) return 0;
    const byNs = new Map();
    for (const file of fs.readdirSync(dir)) {
        const ns = file.replace(/^[0-9a-f]{8}\./, '');
        if (!byNs.has(ns)) byNs.set(ns, []);
        byNs.get(ns).push(file);
    }
    let freed = 0;
    for (const files of byNs.values()) {
        if (files.length < 2) continue;
        const stamped = files.map((f) => ({ f, m: fs.statSync(path.join(dir, f)).mtimeMs }));
        stamped.sort((a, b) => b.m - a.m);
        for (const { f } of stamped.slice(1)) {
            freed += fs.statSync(path.join(dir, f)).size;
            fs.unlinkSync(path.join(dir, f));
        }
    }
    return freed;
}

function main() {
    if (!fs.existsSync(CACHE)) {
        console.error('No cljs-cache. Run `shadow-cljs release bootstrap` first.');
        process.exit(1);
    }

    const loaded = provided();
    if (!loaded.size) {
        // Without the list every js file looks live, so pruning would do
        // nothing and silently look like it worked. Say so instead.
        console.error('No set_loaded call in the built modules — is ' +
                      'lt.objs.cljs-compiler still required from lt.core?');
        process.exit(1);
    }

    let freed = rmDir(path.join(CACHE, 'src'));
    freed += dropStale(path.join(CACHE, 'ana'));
    freed += dropStale(path.join(CACHE, 'js'));

    const jsDir = path.join(CACHE, 'js');
    let kept = 0, dropped = 0;
    for (const file of fs.readdirSync(jsDir)) {
        const full = path.join(jsDir, file);
        const fd = fs.openSync(full, 'r');
        const buf = Buffer.alloc(4096);
        const read = fs.readSync(fd, buf, 0, 4096, 0);
        fs.closeSync(fd);

        const provides = providesOf(file, buf.subarray(0, read).toString('utf8'));
        if (provides.length && provides.every((ns) => loaded.has(ns))) {
            freed += fs.statSync(full).size;
            fs.unlinkSync(full);
            dropped++;
        } else {
            kept++;
        }
    }

    console.log(`cljs-cache: dropped src/ and ${dropped} already-loaded js files, ` +
                `kept ${kept} (${bytes(freed)} freed)`);
}

main();
