#!/usr/bin/env node
/*jshint esversion: 8 */
"use strict";

// Fetches the nREPL server the Clojure plugin starts.
//
// This is the one thing in plugins/ that is not source. lein-light-standalone
// is a 15MB uberjar, and the repository's rule is that no binary is committed
// and no plugin code is cloned at build time — a pinned, checksummed binary is
// the stated exception, and this is it.
//
// It is a *runtime* artifact rather than build output: the plugin resolves it
// by path and hands it to `java -jar`. lt.plugins.clojure/jar-path is where it
// is expected to be, relative to the plugin's own directory, which is why it
// lands under plugins/Clojure/ and rides along in script/place-plugins.js's
// copy into deploy/plugins/.
//
// FUTURE WORK: build it from plugins/Clojure/runner with Leiningen instead.
// The sources for both halves — the lein plugin and the nREPL middleware — are
// vendored beside it, so nothing is missing except a JVM build step and a
// decision about where Leiningen comes from. That would make this a build
// output like everything else and retire the exception.

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const ROOT = path.join(__dirname, '..');

// Pinned to the tag script/build.sh used to clone, and to the bytes that tag
// holds. Bump all three together or not at all.
const JAR = {
    tag: '0.3.3',
    url: 'https://raw.githubusercontent.com/LightTable/Clojure/0.3.3/runner/target/lein-light-standalone.jar',
    sha256: 'e95b07fbe22d454a5e9c6e885a71dc2db269b92f8f179b4da66491137e988bf9',
    to: path.join(ROOT, 'plugins', 'Clojure', 'runner', 'target', 'lein-light-standalone.jar')
};

function digest(file) {
    return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
}

async function main() {
    if (fs.existsSync(JAR.to)) {
        // Verified rather than assumed present: an interrupted build leaves a
        // truncated file behind, and `java -jar` on one of those reports a
        // corrupt zip rather than a missing download.
        if (digest(JAR.to) === JAR.sha256) {
            console.log(`clojure nrepl jar: ${JAR.tag} already present`);
            return;
        }
        console.log('clojure nrepl jar: present but does not match its checksum, refetching');
        fs.rmSync(JAR.to);
    }

    console.log(`clojure nrepl jar: fetching ${JAR.tag}`);
    const response = await fetch(JAR.url);
    if (!response.ok) {
        console.error(`Failed to fetch ${JAR.url}: ${response.status} ${response.statusText}`);
        process.exit(1);
    }
    const bytes = Buffer.from(await response.arrayBuffer());

    const got = crypto.createHash('sha256').update(bytes).digest('hex');
    if (got !== JAR.sha256) {
        console.error(`Checksum mismatch for ${JAR.url}\n  expected ${JAR.sha256}\n  got      ${got}`);
        process.exit(1);
    }

    fs.mkdirSync(path.dirname(JAR.to), { recursive: true });
    fs.writeFileSync(JAR.to, bytes);
    console.log(`clojure nrepl jar: ${(bytes.length / 1e6).toFixed(1)}MB, checksum verified`);
}

main();
