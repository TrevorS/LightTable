// Is this checkout in a state where the next thing you try will work?
//
// Every check here was a confusing failure before it was a check. A missing
// Electron reads as a broken script; a bundle older than `src` reads as a fix
// that did not work — twice, this session, costing a round of "it still
// doesn't work" each time; an editor already holding the debugging port reads
// as a smoke test that cannot boot, which it says, and nothing else did.
//
// Reports everything rather than stopping at the first problem, and exits
// non-zero only for the ones that will actually stop you.

import { execFileSync } from 'node:child_process';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { ROOT, CORE, BUILDS, electronBinary, packagedApp } from './lib/paths.mts';

type State = 'ok' | 'warn' | 'fail';
const results: Array<{ state: State; name: string; detail: string }> = [];
const say = (state: State, name: string, detail: string) => results.push({ state, name, detail });

/** The newest mtime under `dir` for files matching `ext`. */
function newest(dir: string, ext: RegExp): number {
    let latest = 0;
    const walk = (d: string) => {
        if (!fs.existsSync(d)) return;
        for (const e of fs.readdirSync(d, { withFileTypes: true })) {
            if (e.name === 'node_modules' || e.name.startsWith('.')) continue;
            const full = path.join(d, e.name);
            if (e.isDirectory()) walk(full);
            else if (ext.test(e.name)) latest = Math.max(latest, fs.statSync(full).mtimeMs);
        }
    };
    walk(dir);
    return latest;
}

const ago = (ms: number) => {
    const mins = Math.round((Date.now() - ms) / 60000);
    if (mins < 1) return 'just now';
    if (mins < 60) return `${mins}m ago`;
    const hours = Math.round(mins / 60);
    return hours < 48 ? `${hours}h ago` : `${Math.round(hours / 24)}d ago`;
};

// ── Dependencies ────────────────────────────────────────────────────────────

const electron = await electronBinary().catch(() => null);
if (electron && fs.existsSync(electron)) say('ok', 'electron', path.relative(ROOT, electron));
else say('fail', 'electron', 'missing — run `make deps`');

for (const [what, dir] of [['root', ROOT], ['deploy/core', path.join(ROOT, 'deploy', 'core')],
                           ['deploy/electron', path.join(ROOT, 'deploy', 'electron')]] as const) {
    say(fs.existsSync(path.join(dir, 'node_modules')) ? 'ok' : 'fail',
        `node_modules (${what})`,
        fs.existsSync(path.join(dir, 'node_modules')) ? 'installed' : 'missing — run `make deps`');
}

say(fs.existsSync(path.join(ROOT, '.tools', 'clj-kondo')) ? 'ok' : 'warn', 'clj-kondo',
    fs.existsSync(path.join(ROOT, '.tools', 'clj-kondo'))
        ? 'fetched' : 'not fetched — `npm run tools`, or lint:cljs will do it');

// ── The bundle, and whether it is older than the source ─────────────────────
//
// The check that would have saved this session two rounds: a window running a
// bundle built before the fix looks exactly like a fix that did not work.

const bundle = path.join(CORE, 'lighttable', 'bootstrap.js');
if (!fs.existsSync(bundle)) {
    say('fail', 'bundle', 'not built — run `make build-cljs`');
} else {
    const built = fs.statSync(bundle).mtimeMs;
    const source = Math.max(newest(path.join(ROOT, 'src'), /\.cljs$/),
                            newest(path.join(ROOT, 'plugins'), /\.cljs$/));
    say(built >= source ? 'ok' : 'warn', 'bundle',
        built >= source
            ? `built ${ago(built)}, newer than src`
            : `built ${ago(built)} — src has changed since. Run \`make build-cljs\``);
}

const stampFile = path.join(CORE, 'lighttable', 'build.json');
if (fs.existsSync(stampFile)) {
    const s = JSON.parse(fs.readFileSync(stampFile, 'utf8'));
    say('ok', 'build stamp', `${s.commit}${s.dirty ? '+dirty' : ''} on ${s.branch}`);
} else {
    say('warn', 'build stamp', 'none — `App: What build is this?` will say so');
}

// The plugins the loader reads, which are a copy rather than the source.
const placed = path.join(ROOT, 'deploy', 'plugins');
const pluginCount = fs.existsSync(placed) ? fs.readdirSync(placed).length : 0;
say(pluginCount > 0 ? 'ok' : 'warn', 'plugins placed',
    pluginCount > 0 ? `${pluginCount} in deploy/plugins` : 'none — run `make build-plugins`');

// ── The packaged build, and whether it is what HEAD says ────────────────────

const app = packagedApp();
if (!app) {
    say('warn', 'packaged app', 'none in builds/ — `make build`, needed for `lt-repl --release`');
} else {
    const packagedStamp = path.join(BUILDS, fs.readdirSync(BUILDS)[0],
                                    'LightTable.app', 'Contents', 'Resources', 'app',
                                    'core', 'lighttable', 'build.json');
    let head: string | null = null;
    try { head = execFileSync('git', ['rev-parse', '--short', 'HEAD'],
                              { cwd: ROOT, encoding: 'utf8' }).trim(); } catch { /* no git */ }
    if (fs.existsSync(packagedStamp)) {
        const s = JSON.parse(fs.readFileSync(packagedStamp, 'utf8'));
        say(head && s.commit === head ? 'ok' : 'warn', 'packaged app',
            head && s.commit === head
                ? `${s.commit}, matches HEAD`
                : `${s.commit}${s.dirty ? '+dirty' : ''} — HEAD is ${head}. Repackage with \`make build\``);
    } else {
        say('warn', 'packaged app', 'present but unstamped — packaged before stamps existed');
    }
}

// ── Anything already holding the debugging port ─────────────────────────────
//
// smoke-test says this clearly and everything else fails obscurely.

const port = await fetch('http://127.0.0.1:8315/json')
    .then(() => true).catch(() => false);
say(port ? 'warn' : 'ok', 'port 8315',
    port ? 'in use — `script/lt-repl.sh stop` before smoke or e2e' : 'free');

// ── Report ──────────────────────────────────────────────────────────────────

const mark = { ok: ' ok ', warn: 'note', fail: 'FAIL' };
for (const { state, name, detail } of results) {
    console.log(`  ${mark[state]} ${name.padEnd(22)} ${detail}`);
}
const bad = results.filter((r) => r.state === 'fail');
if (bad.length) {
    console.log(`\n${bad.length} thing(s) will stop you.`);
    process.exit(1);
}
