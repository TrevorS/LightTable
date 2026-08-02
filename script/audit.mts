// Cross-checks that no compiler, linter or test performs.
//
// Every check here found something real, and every one of them was written as
// a throwaway and thrown away. clj-kondo sees one file at a time and cannot
// know that a `behavior` is never named in a `.behaviors` file; the
// ClojureScript compiler cannot know that a public var has no caller; eslint
// cannot know that a CSS class is constructed nowhere. The gaps between the
// tools are where this repository's dead code has been living.
//
// What each has found, so a reader can judge whether it earns its place:
//
//   unreferenced      four editor event wrappers subscribing to names the
//                     engine never emits; five dead helpers, two of which
//                     wrote DOM the state had taken over
//   unwired           the collapsible exception — an object, a view and eight
//                     behavior lines, all downstream of two behaviors that
//                     were never named in clojure.behaviors
//   duplicate         `:editor.select-all` registered twice with different
//                     implementations, the second silently winning
//   missing           behaviors named in a .behaviors file that do not exist
//
// Advisory by default: it reports and exits zero, because "no caller in this
// repository" is a fact rather than a verdict — `lt.objs.editor` is a
// plugin-facing API and some of it is meant to sit there. `--strict` exits
// non-zero for the two checks that are unambiguous, which is what CI could
// take if it ever wants to.

import * as fs from 'node:fs';
import * as path from 'node:path';
import { ROOT } from './lib/paths.mts';

const strict = process.argv.includes('--strict');
const only = process.argv.find((a) => a.startsWith('--only='))?.slice(7);

/** Every file under `dir` matching `ext`, skipping node_modules and output. */
function walk(dir: string, ext: RegExp, out: string[] = []): string[] {
    if (!fs.existsSync(dir)) return out;
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        if (entry.name === 'node_modules' || entry.name.startsWith('.')) continue;
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) walk(full, ext, out);
        else if (ext.test(entry.name) && !/_compiled\.js$/.test(entry.name)) out.push(full);
    }
    return out;
}

const read = (f: string) => fs.readFileSync(f, 'utf8');
const rel = (f: string) => path.relative(ROOT, f);

const CLJS = [...walk(path.join(ROOT, 'src'), /\.cljs$/),
              ...walk(path.join(ROOT, 'plugins'), /\.cljs$/),
              ...walk(path.join(ROOT, 'deploy', 'core', 'User', 'src'), /\.cljs$/)];
const BEHAVIOR_FILES = [...walk(path.join(ROOT, 'deploy', 'settings'), /\.behaviors$/),
                        ...walk(path.join(ROOT, 'plugins'), /\.behaviors$/)];
const TESTS = [...walk(path.join(ROOT, 'test'), /\.cljs$/),
               ...walk(path.join(ROOT, 'test-e2e'), /\.ts$/),
               ...walk(path.join(ROOT, 'script'), /\.mts$/)];
const TS = [...walk(path.join(ROOT, 'src-electron'), /\.ts$/),
            ...walk(path.join(ROOT, 'src-window'), /\.ts$/),
            ...walk(path.join(ROOT, 'src-worker'), /\.ts$/),
            ...walk(path.join(ROOT, 'src-browser'), /\.ts$/)];

interface Finding { check: string; what: string; where?: string; hard?: boolean }
const findings: Finding[] = [];
const ran: string[] = [];

function check(name: string, f: () => void): void {
    if (only && only !== name) return;
    ran.push(name);
    f();
}

// ── Public vars nothing calls ───────────────────────────────────────────────
//
// Namespace-qualified uses count, which is the whole difficulty: `editor/line`
// and `line` are the same var. Counting occurrences of the bare name and
// calling one occurrence "only the definition" is crude and is why this is
// advisory — a name that is also a common word will read as used.
check('unreferenced', () => {
    const sources = CLJS.map(read);
    const haystack = [...sources, ...TESTS.map(read), ...BEHAVIOR_FILES.map(read)].join('\n');
    const defs: Array<[string, string]> = [];
    for (const f of CLJS) {
        for (const m of read(f).matchAll(/^\(def(n-|n|)\s+(\^\S+\s+)?([a-zA-Z0-9!?*<>=+_.\-]+)/gm)) {
            if (m[1] === 'n-') continue;      // private: clj-kondo already reports these
            defs.push([f, m[3]]);
        }
    }
    for (const [f, name] of defs) {
        const pattern = new RegExp(`(?<![a-zA-Z0-9!?*<>=+_.\\-])${
            name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}(?![a-zA-Z0-9!?*<>=+_.\\-])`, 'g');
        if ((haystack.match(pattern) ?? []).length <= 1) {
            findings.push({ check: 'unreferenced', what: name, where: rel(f) });
        }
    }
});

// ── Behaviors declared and never wired ──────────────────────────────────────
//
// `:type :user` is excluded: those are the documented opt-in configuration
// surface, and being absent from default.behaviors is what "opt in" means.
check('unwired', () => {
    const wired = new Set<string>();
    for (const f of BEHAVIOR_FILES) {
        for (const m of read(f).matchAll(/:([a-zA-Z0-9._\-]+)\/([a-zA-Z0-9!?*<>=+_.\-]+)/g)) {
            wired.add(`${m[1]}/${m[2]}`);
        }
    }
    const declared = new Map<string, { file: string; user: boolean }>();
    for (const f of CLJS) {
        const text = read(f);
        const ns = /^\(ns\s+([a-zA-Z0-9._\-]+)/m.exec(text)?.[1];
        if (!ns) continue;
        for (const m of text.matchAll(/\(behavior\s+::([a-zA-Z0-9!?*<>=+_.\-]+)([\s\S]*?)(?=\n\(|$)/g)) {
            declared.set(`${ns}/${m[1]}`, { file: f, user: /:type\s+:user/.test(m[2]) });
        }
    }
    const source = CLJS.map(read).join('\n');
    for (const [full, { file, user }] of declared) {
        if (user || wired.has(full)) continue;
        // Referenced from ClojureScript instead — `object/tag-behaviors`, or a
        // plugin adding one at runtime — counts as wired.
        const short = full.split('/')[1];
        const uses = (source.match(new RegExp(`::${short.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}(?![a-zA-Z0-9!?*<>=+_.\\-])`, 'g')) ?? []).length;
        if (uses <= 1) findings.push({ check: 'unwired', what: full, where: rel(file) });
    }
});

// ── Behaviors wired and never declared ──────────────────────────────────────
//
// The unambiguous half: a `.behaviors` line naming something that does not
// exist is silent at load and does nothing forever. Five of these were found
// once, including two asking for a CodeMirror 5 function removed years before.
check('missing', () => {
    const declared = new Set<string>();
    for (const f of CLJS) {
        const text = read(f);
        const ns = /^\(ns\s+([a-zA-Z0-9._\-]+)/m.exec(text)?.[1];
        if (!ns) continue;
        for (const m of text.matchAll(/\(behavior\s+::([a-zA-Z0-9!?*<>=+_.\-]+)/g)) {
            declared.add(`${ns}/${m[1]}`);
        }
    }
    for (const f of BEHAVIOR_FILES) {
        for (const line of read(f).split('\n')) {
            if (/^\s*;/.test(line)) continue;
            for (const m of line.matchAll(/:(lt\.[a-zA-Z0-9._\-]+)\/([a-zA-Z0-9!?*<>=+_.\-]+)/g)) {
                const full = `${m[1]}/${m[2]}`;
                // Only behaviors: a .behaviors line can also name a plain fn
                // argument. Anything whose namespace has no behaviors at all is
                // not something this can judge.
                if (!declared.has(full) && [...declared].some((d) => d.startsWith(m[1] + '/'))) {
                    findings.push({ check: 'missing', what: full, where: rel(f), hard: true });
                }
            }
        }
    }
});

// ── Two commands claiming one key ───────────────────────────────────────────
//
// `lt.objs.command/command` reports a clash whose descriptions differ, and
// stays quiet when they agree so that live editing does not shout on every
// re-evaluation. Two *copies* of one command therefore only show up here.
check('duplicate', () => {
    const seen = new Map<string, string[]>();
    for (const f of CLJS) {
        for (const m of read(f).matchAll(/command\s*\{:command\s+(:[a-zA-Z0-9._!?><\-]+)/g)) {
            seen.set(m[1], [...(seen.get(m[1]) ?? []), rel(f)]);
        }
    }
    for (const [key, files] of seen) {
        if (files.length > 1) {
            findings.push({ check: 'duplicate', what: `${key} × ${files.length}`,
                            where: [...new Set(files)].join(', '), hard: true });
        }
    }
});

// ── TypeScript exports nothing imports ──────────────────────────────────────
check('unused-export', () => {
    const haystack = [...TS, ...TESTS].map(read).join('\n');
    for (const f of TS) {
        for (const m of read(f).matchAll(
            /^export\s+(?:async\s+)?(?:function|const|class|interface|type)\s+([A-Za-z0-9_]+)/gm)) {
            const uses = (haystack.match(new RegExp(`(?<![A-Za-z0-9_])${m[1]}(?![A-Za-z0-9_])`, 'g')) ?? []).length;
            if (uses <= 1) findings.push({ check: 'unused-export', what: m[1], where: rel(f) });
        }
    }
});

// ── Reporting ───────────────────────────────────────────────────────────────

const byCheck = new Map<string, Finding[]>();
for (const f of findings) byCheck.set(f.check, [...(byCheck.get(f.check) ?? []), f]);

for (const name of ran) {
    const found = byCheck.get(name) ?? [];
    if (!found.length) { console.log(`  ok   ${name}`); continue; }
    console.log(`  ${found.some((f) => f.hard) ? 'FAIL' : 'note'} ${name} — ${found.length}`);
    for (const f of found) console.log(`         ${f.what}${f.where ? `   ${f.where}` : ''}`);
}

const hard = findings.filter((f) => f.hard);
if (strict && hard.length) process.exit(1);
