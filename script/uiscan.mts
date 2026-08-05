#!/usr/bin/env node

// Looks at the editor, in every state worth looking at, and says what is wrong
// with the picture.
//
// Run with:  script/uiscan.sh  (needs a build: npm run build)
//
//     script/uiscan.sh                      every state
//     script/uiscan.sh --only settings,keys  just those
//     script/uiscan.sh --list                what the states are
//     script/uiscan.sh --strict              exit 1 if anything is found
//
// PNGs go to builds/uiscan/. Findings go to stdout.
//
// ## Why this exists
//
// The four test layers ask whether an element is there, holds the right text and
// answers a click. Every one of them passed while:
//
//   - the settings screen drew each row 60px into the one below it, so the whole
//     screen was two layers of text on top of each other
//   - the keymap put a key at x=220 and its command at x=1350
//   - the docs panel's language chip floated over the tab strip in every window,
//     including one with no file open
//   - every button in the application turned light grey under the pointer
//   - opening the plugin manager rewrote the user's plugin.edn as garbage
//
// None of that is invisible. It is just not the kind of thing an assertion about
// an element is looking at, and nobody was looking at the other kind. So this
// takes the screenshots — headlessly, which is the part that makes it usable: no
// window appears, nothing takes focus, and it runs the same on a CI box as on a
// desk.
//
// ## And why it does more than screenshot
//
// A PNG needs a person, and a person will not look every time. So each state is
// also *audited*: five checks — four over the rendered DOM and one over the
// console — each written from a bug that shipped, and each a measurement rather
// than a matter of taste.
//
//   overlap   two siblings in normal flow whose boxes intersect. Normal flow does
//             not overlap — that is what makes it a flow — so an intersection
//             means a fixed height with content taller than it. The settings
//             screen, exactly.
//
//   spill     a box holding more than it has room for, with nothing clipping it —
//             so the surplus is painted over whatever comes next. A fixed height
//             with content that does not fit. The settings screen, exactly, and
//             the one `overlap` could not see because the colliding boxes were in
//             different parents.
//
//   escape    an absolutely positioned element painted outside the box of the
//             thing it is positioned against. Which is what happens when a panel
//             forgets `position: relative` and its child resolves `right: 0`
//             against something three levels up. The docs chip, exactly.
//
//   error     something in the console. `lt.object` catches what a behavior throws
//             and reports it there, so a behavior that failed looks exactly like
//             one that decided not to act — the console is the only place the
//             difference shows, and a red block of stack trace at the bottom of a
//             screenshot is not a report.
//
//   clipped   text that does not fit the box it is in. Sometimes correct — a URL
//             in a sidebar has to end somewhere — so this is reported and not
//             failed, with how much is missing, because `:editor.clj.jump-to-c`
//             losing eleven characters is a different thing from a URL losing its
//             query string.
//
// It is a scanner, not a gate. `--strict` makes it one, for a CI job that wants
// to hold the line, but the default is to report — a `clipped` finding is often
// the right answer and a tool that fails on those gets turned off.

import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { _electron as electron, type ElectronApplication, type Page } from 'playwright';
import { ROOT, CORE, electronBinary } from './lib/paths.mts';

const OUT = path.join(ROOT, 'builds', 'uiscan');
const WIDTH = 1440;
const HEIGHT = 900;

/** One thing to look at: a name, and how to get the editor into that state. */
interface State {
    name: string;
    /** A command to run, when that is all it takes. */
    command?: string;
    /** Anything else, for the states a command does not describe. */
    drive?: (page: Page) => Promise<void>;
    /** How long to let it settle. Modes and language servers are not instant. */
    settle?: number;
    /** Checks to skip here, with the reason. */
    allow?: string[];
}

const STATES: State[] = [
    { name: 'empty', settle: 900 },
    { name: 'clojure', drive: openSample('sample.clj'), settle: 2500 },
    { name: 'typescript', drive: openSample('sample.ts'), settle: 3500 },
    { name: 'markdown', drive: openSample('notes.md'), settle: 2500 },
    { name: 'commandbar', command: ':show-commandbar', settle: 1200 },
    { name: 'settings', command: ':settings.screen', settle: 1800 },
    { name: 'keys', command: ':settings.keys', settle: 1800 },
    { name: 'plugins', command: ':plugin-manager.show', settle: 2200 },
    {
        name: 'plugins-hover',
        settle: 900,
        drive: async (page) => {
            await exec(page, ':plugin-manager.show');
            await wait(page, 2200);
            await page.locator('.plugin-manager .plugins li').nth(1).hover();
        }
    },
    {
        // Connected to itself first, because the panel is otherwise empty. Every
        // run gets a fresh LT_USER_DIR — which is what makes two runs comparable —
        // and a fresh one has no saved connections, so `:show-connect` on its own
        // scans a panel with nothing in it. The row is the whole point here: three
        // columns in a 280px sidebar is where they fight for width.
        name: 'connect',
        settle: 1600,
        drive: async (page) => {
            await exec(page, ':show-connect');
            await page.evaluate(
                '(lt.objs.sidebar.clients.connect_BANG_("Light Table UI"), null)');
            await wait(page, 2500);
        }
    },
    { name: 'console', command: ':console.show', settle: 1400 },
    { name: 'find', command: ':find.show', settle: 1200 },
    { name: 'searcher', command: ':searcher.show', settle: 1400 },
    { name: 'navigate', command: ':navigate-workspace', settle: 1400 },
    { name: 'docs', command: ':show-docs', settle: 1600 }
];

/** Sample files, written once into the run's own directory. */
const SAMPLES: Record<string, string> = {
    'sample.clj': `(ns sample.core
  "A docstring, so the mode has something to colour."
  (:require [clojure.string :as string]))

(def counter (atom 0))

(defn greet
  "Says hello to somebody."
  [who]
  (str "hello, " who "!"))

(comment
  (greet "world")
  @counter)
`,
    'sample.ts': `interface Person { name: string; age?: number }

export function greet(p: Person): string {
    const parts: string[] = [p.name, String(p.age)];
    return \`hello, \${parts.join(' ')}\`;
}

const wrong: number = "not a number";
`,
    'notes.md': `# A heading

Some **bold** text and a [link](https://example.com).

- one
- two

\`\`\`clojure
(inc 1)
\`\`\`
`
};

let sampleDir = '';

function openSample(file: string) {
    return async (page: Page) => {
        await exec(page, ':open-path', path.join(sampleDir, file));
    };
}

function wait(page: Page, ms: number): Promise<void> {
    return page.waitForTimeout(ms);
}

/** Runs a Light Table command, with string arguments. */
async function exec(page: Page, command: string, ...args: string[]): Promise<void> {
    const argv = args.map((a) => JSON.stringify(a)).join(' ');
    await page.evaluate(`lt.objs.command.exec_BANG_(cljs.core.keyword.call(null, ${
        JSON.stringify(command.replace(/^:/, ''))}) ${argv ? ', ' + argv : ''})`);
}

/**
 * The audits, as one function evaluated in the window.
 *
 * A string rather than a passed closure because it runs in the renderer, which
 * has none of this file's scope. Kept in one place so what it measures is
 * readable as one thing.
 */
const AUDIT = `(function () {
    var findings = [];
    var W = window.innerWidth, H = window.innerHeight;

    function box(el) { var r = el.getBoundingClientRect();
        return { x: r.left, y: r.top, w: r.width, h: r.height, r: r.right, b: r.bottom }; }
    function area(b) { return b.w * b.h; }
    function name(el) {
        var id = el.id ? '#' + el.id : '';
        var cls = typeof el.className === 'string' && el.className
            ? '.' + el.className.trim().split(/\\s+/).slice(0, 2).join('.') : '';
        return el.tagName.toLowerCase() + id + cls;
    }
    function text(el) { return (el.textContent || '').replace(/\\s+/g, ' ').trim().slice(0, 40); }

    // Which ancestors actually clip this element, which is not "all of them".
    //
    // Getting this wrong made the tool's first run report two things that are not
    // there: a find-bar button inside a \`height: 0; overflow: hidden\` bar, and the
    // docs chip inside a collapsed panel. Both have a box and neither is painted.
    //
    // The rule is the one the docs-chip bug turned on. \`overflow\` clips a
    // descendant only when the clipping element is the descendant's containing
    // block or an ancestor of it — so for an absolutely positioned element the
    // walk starts at its containing block (\`offsetParent\`) and not at its parent,
    // and anything between the two does not clip it at all. That is why a panel
    // without \`position: relative\` could not clip its own child, and it is why
    // this tool goes quiet once the panel has one.
    function clipsFor(el) {
        var s = getComputedStyle(el);
        // Fixed is clipped by the viewport only. Transformed ancestors also clip
        // it; nothing here uses transforms for layout, so that is not modelled.
        if (s.position === 'fixed') return [];
        var p = s.position === 'absolute' ? el.offsetParent : el.parentElement;
        var out = [];
        while (p && p !== document.documentElement) {
            var ps = getComputedStyle(p);
            if (ps.overflow !== 'visible' || ps.overflowX !== 'visible' || ps.overflowY !== 'visible') {
                out.push(p);
            }
            p = p.parentElement;
        }
        return out;
    }

    /** The part of an element that is actually painted, after clipping. */
    function painted(el) {
        var b = box(el);
        var x = b.x, y = b.y, r = b.r, bb = b.b;
        clipsFor(el).forEach(function (c) {
            var cr = c.getBoundingClientRect();
            x = Math.max(x, cr.left); y = Math.max(y, cr.top);
            r = Math.min(r, cr.right); bb = Math.min(bb, cr.bottom);
        });
        x = Math.max(x, 0); y = Math.max(y, 0); r = Math.min(r, W); bb = Math.min(bb, H);
        return { x: x, y: y, w: Math.max(0, r - x), h: Math.max(0, bb - y) };
    }

    /** Is any of it on the screen once its clipping ancestors have had their say. */
    function shows(el) { var p = painted(el); return p.w > 1 && p.h > 1; }

    var all = Array.from(document.querySelectorAll('body *'));

    // ---- overlap: two normal-flow siblings whose boxes intersect ------------
    //
    // Only static siblings, only when both are block-level and both painted:
    // inline boxes on one line legitimately share vertical space, and anything
    // positioned is meant to be over something.
    var parents = new Set(all.map(function (el) { return el.parentElement; }));
    parents.forEach(function (p) {
        if (!p) return;
        var kids = Array.from(p.children).filter(function (el) {
            var s = getComputedStyle(el);
            if (s.position !== 'static' || s.float !== 'none') return false;
            if (s.display === 'inline' || s.display === 'inline-block') return false;
            if (s.visibility === 'hidden' || s.display === 'none') return false;
            return area(box(el)) > 4 && shows(el);
        });
        // A flex or grid row puts its children side by side on purpose.
        var ps = getComputedStyle(p);
        var acrossOk = ps.display.indexOf('flex') >= 0 || ps.display.indexOf('grid') >= 0;
        for (var i = 0; i < kids.length; i++) {
            for (var j = i + 1; j < kids.length; j++) {
                var a = box(kids[i]), c = box(kids[j]);
                var overX = Math.min(a.r, c.r) - Math.max(a.x, c.x);
                var overY = Math.min(a.b, c.b) - Math.max(a.y, c.y);
                if (overX <= 1 || overY <= 1) continue;
                // Side-by-side in a flex row is not an overlap of the flow.
                if (acrossOk && overY > 1 && overX <= 1) continue;
                findings.push({ kind: 'overlap', by: Math.round(overY) + 'px',
                    what: name(kids[i]) + ' over ' + name(kids[j]),
                    text: text(kids[i]) });
            }
        }
    });

    // ---- spill: content taller than the box, and nothing clipping it -------
    //
    // The check the first version of this file did not have, and the one that
    // would have caught the worst bug it was written for. The settings screen
    // drew each row 60px into the one below, and \`overlap\` above could not see it:
    // the colliding boxes were not siblings. Each row was 26px, adjacent to the
    // next and not intersecting it — what intersected was the *content*, an 86px
    // \`.setting\` painting straight out of the bottom of its own 26px row.
    //
    // So this asks the question from the container's side. A box whose
    // \`scrollHeight\` exceeds its \`clientHeight\` holds more than it has room for,
    // and if nothing clips it that surplus is being painted over whatever comes
    // next. Which is a fixed height with content that does not fit — nearly always
    // a mistake, and always worth looking at.
    //
    // Grounded in an actual child rather than in \`scrollHeight\` alone, because
    // that number counts things this does not mean: an out-of-flow child is
    // deliberately somewhere else, and a scroll container is doing its job. So a
    // static, in-flow child has to be found genuinely past the padding box before
    // this says anything.
    all.forEach(function (el) {
        if (!el.children.length) return;
        var s = getComputedStyle(el);
        if (s.overflow !== 'visible' || s.overflowY !== 'visible') return;
        if (s.display === 'none' || s.visibility === 'hidden') return;
        var over = el.scrollHeight - el.clientHeight;
        if (over <= 2 || !shows(el)) return;
        var b = box(el);
        var worst = null, by = 0;
        Array.from(el.children).forEach(function (kid) {
            var ks = getComputedStyle(kid);
            if (ks.position !== 'static' && ks.position !== 'relative') return;
            if (ks.display === 'none') return;
            var kb = box(kid);
            if (!(area(kb) > 4)) return;
            var past = kb.b - b.b;
            if (past > by) { by = past; worst = kid; }
        });
        if (worst && by > 2) {
            findings.push({ kind: 'spill', by: Math.round(by) + 'px',
                what: name(worst) + ' out of ' + name(el),
                text: text(worst) });
        }
    });

    // ---- escape: positioned outside the thing it is positioned against -----
    all.forEach(function (el) {
        var s = getComputedStyle(el);
        if (s.position !== 'absolute' && s.position !== 'fixed') return;
        var b = box(el);
        if (!(area(b) > 4) || !shows(el)) return;
        var host = s.position === 'fixed'
            ? { x: 0, y: 0, r: W, b: H }
            : (el.offsetParent ? box(el.offsetParent) : null);
        if (!host) return;
        // Wholly outside its host's box, horizontally or vertically. Partly
        // outside is normal — a menu hangs off its button on purpose.
        var outX = b.r <= host.x + 1 || b.x >= host.r - 1;
        var outY = b.b <= host.y + 1 || b.y >= host.b - 1;
        if (outX || outY) {
            findings.push({ kind: 'escape',
                what: name(el) + ' outside ' + (el.offsetParent ? name(el.offsetParent) : 'the viewport'),
                text: text(el),
                at: Math.round(b.x) + ',' + Math.round(b.y) });
        }
    });

    // ---- clipped: text wider than the box holding it ------------------------
    all.forEach(function (el) {
        if (el.children.length) return;
        var s = getComputedStyle(el);
        if (s.display === 'none' || s.visibility === 'hidden') return;
        if (!(area(box(el)) > 4) || !shows(el)) return;
        var over = el.scrollWidth - el.clientWidth;
        if (over <= 1 || el.clientWidth === 0) return;
        findings.push({ kind: 'clipped', by: over + 'px',
            what: name(el), text: text(el) });
    });

    // An input's value is not in its textContent, and truncation there is the
    // one that matters most — it is a value somebody is being asked to check.
    Array.from(document.querySelectorAll('input')).forEach(function (el) {
        if (!(area(box(el)) > 4) || !shows(el)) return;
        if (!el.value) return;
        var over = el.scrollWidth - el.clientWidth;
        if (over > 1) {
            findings.push({ kind: 'clipped', by: over + 'px',
                what: name(el) + ' [value]', text: String(el.value).slice(0, 40) });
        }
    });

    return JSON.stringify(findings);
})()`;

/**
 * Anything the console is showing as an error.
 *
 * Because a screenshot found one this had no way to report: getting the connect
 * panel into a state worth looking at raised inside a behavior, and the only
 * sign was a red block of stack trace at the bottom of a PNG. `lt.object` catches
 * what a behavior throws and reports it there, so a behavior that fails looks
 * exactly like one that decided not to act — which makes the console the only
 * place the difference shows, and worth reading on every state rather than
 * hoping somebody notices the colour.
 *
 * Read the way `test-e2e/fixtures.ts` reads it, and unguarded for the reason it
 * gives: a reader that swallows its own failure turns "no errors" into "no
 * answer".
 */
async function consoleErrors(page: Page): Promise<string[]> {
    return await page.evaluate(`(function () {
        var content = lt.object.__GT_content(lt.objs.console.console);
        if (!content) return [];
        return Array.from(content.querySelectorAll('li.error')).map(function (li) {
            return (li.innerText || '').replace(/\\s+/g, ' ').trim().slice(0, 200);
        });
    })()`) as string[];
}

interface Finding { kind: string; what: string; text?: string; by?: string; at?: string }

/** Same finding twice in one state is one finding. */
function dedupe(findings: Finding[]): Finding[] {
    const seen = new Set<string>();
    return findings.filter((f) => {
        const key = `${f.kind}|${f.what}|${f.text ?? ''}`;
        if (seen.has(key)) return false;
        seen.add(key);
        return true;
    });
}

async function ready(page: Page): Promise<void> {
    await page.waitForFunction(
        "typeof lt !== 'undefined' && lt.objs && typeof lt.objs.app === 'object'",
        null, { timeout: 90_000 });
    // Behaviours keep arriving for a moment after that; settled is three
    // readings the same, which is what test-e2e/fixtures.ts waits for too.
    let last = -1, same = 0;
    while (same < 3) {
        const n = await page.evaluate(
            'cljs.core.count(cljs.core.deref(lt.object.behaviors))') as number;
        same = n === last ? same + 1 : 0;
        last = n;
        await page.waitForTimeout(100);
    }
}

async function main(): Promise<void> {
    const argv = process.argv.slice(2);
    const only = argv.includes('--only')
        ? (argv[argv.indexOf('--only') + 1] ?? '').split(',').map((s) => s.trim()).filter(Boolean)
        : null;
    const strict = argv.includes('--strict');

    if (argv.includes('--list')) {
        for (const s of STATES) console.log(s.name);
        return;
    }

    const states = only ? STATES.filter((s) => only.includes(s.name)) : STATES;
    if (!states.length) {
        console.error(`No such state. Known: ${STATES.map((s) => s.name).join(', ')}`);
        process.exit(1);
    }

    if (!fs.existsSync(path.join(CORE, 'lighttable', 'bootstrap.js'))) {
        console.error('No compiled bundle. Run `npm run build` first.');
        process.exit(1);
    }

    fs.rmSync(OUT, { recursive: true, force: true });
    fs.mkdirSync(OUT, { recursive: true });

    sampleDir = fs.mkdtempSync(path.join(os.tmpdir(), 'lt-uiscan-'));
    for (const [file, body] of Object.entries(SAMPLES)) {
        fs.writeFileSync(path.join(sampleDir, file), body);
    }
    // A home of its own, so this does not open whatever was left in the real
    // session — and so two runs see the same editor.
    const home = fs.mkdtempSync(path.join(os.tmpdir(), 'lt-uiscan-home-'));

    let app: ElectronApplication | null = null;
    let found = 0;

    try {
        app = await electron.launch({
            executablePath: await electronBinary(),
            args: [CORE, '--no-sandbox'],
            env: { LT_HEADLESS: '1', ...process.env, LT_USER_DIR: home,
                   LT_REMOTE_DEBUGGING_PORT: 'off' }
        });
        const page = await app.firstWindow();
        await ready(page);

        // Set from the main process: a window's size is not the page's to change.
        // Bigger than the 1024x700 default on purpose — a layout that only breaks
        // when there is room to spare is still broken, and the keymap's
        // thousand-pixel gap was invisible at 1024.
        await app.evaluate(({ BrowserWindow }, size) => {
            const [win] = BrowserWindow.getAllWindows();
            win!.setSize(size.w, size.h);
        }, { w: WIDTH, h: HEIGHT });
        await page.waitForTimeout(700);

        for (const state of states) {
            if (state.command) await exec(page, state.command);
            if (state.drive) await state.drive(page);
            await page.waitForTimeout(state.settle ?? 900);

            const file = path.join(OUT, `${state.name}.png`);
            await page.screenshot({ path: file });

            const raw = JSON.parse(await page.evaluate(AUDIT) as string) as Finding[];
            // An error the console is showing counts as a finding about this
            // state, and is listed first: a behavior that threw while drawing
            // explains a picture better than anything measured off the result.
            const errors: Finding[] = (await consoleErrors(page))
                .map((e) => ({ kind: 'error', what: 'the console is showing an error', text: e }));
            const findings = [...errors, ...dedupe(raw)]
                .filter((f) => !(state.allow ?? []).includes(f.kind));

            const counts = findings.reduce<Record<string, number>>((acc, f) => {
                acc[f.kind] = (acc[f.kind] ?? 0) + 1;
                return acc;
            }, {});
            const summary = Object.entries(counts).map(([k, n]) => `${n} ${k}`).join(', ');
            console.log(`${state.name.padEnd(14)} ${path.relative(ROOT, file)}${summary ? '  — ' + summary : ''}`);

            for (const f of findings) {
                const extra = [f.by, f.at].filter(Boolean).join(' ');
                console.log(`  ${f.kind.padEnd(8)} ${f.what}${extra ? '  (' + extra + ')' : ''}`
                            + (f.text ? `\n           "${f.text}"` : ''));
            }
            found += findings.length;

            // Dismiss whatever this opened, so the next state starts from the
            // editor rather than on top of the last panel.
            await page.keyboard.press('Escape');
            await page.waitForTimeout(250);
        }
    } finally {
        if (app) await app.close().catch(() => { /* already gone */ });
        fs.rmSync(sampleDir, { recursive: true, force: true });
        fs.rmSync(home, { recursive: true, force: true });
    }

    console.log(`\n${states.length} states, ${found} findings. PNGs in ${path.relative(ROOT, OUT)}/`);
    if (found && strict) process.exit(1);
}

await main();
