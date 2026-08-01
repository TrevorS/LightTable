#!/usr/bin/env node

// What every element in the window is actually styled as.
//
// Moving a declaration from one stylesheet to another is supposed to change
// nothing, and there is no way to be sure by reading: the cascade decides, and
// the cascade depends on file order, specificity and what else matched. So take
// the answer from the browser instead. Boot Light Table, walk the DOM, record
// the computed style of every element, and compare.
//
//     node script/style-snapshot.mts before.json
//     …edit stylesheets…
//     node script/style-snapshot.mts after.json
//     node script/style-snapshot.mts --compare before.json after.json
//
// An empty comparison is the proof that a refactor was one. A non-empty one
// names the element and the property, which is also how to tell what a new skin
// is actually doing.
//
// Computed style rather than a screenshot because it is exact: no antialiasing,
// no font loading race, no cursor blinking on the frame the shot was taken.

import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { _electron as electron } from '@playwright/test';
import { ROOT, CORE, electronBinary } from './lib/paths.mts';

/** What is worth comparing: everything a skin or a theme can reach. */
const PROPERTIES = [
    'color', 'background-color', 'background-image', 'opacity',
    'border-top-color', 'border-right-color', 'border-bottom-color', 'border-left-color',
    'border-top-width', 'border-right-width', 'border-bottom-width', 'border-left-width',
    'border-top-left-radius', 'border-bottom-right-radius',
    'box-shadow', 'text-shadow', 'outline-color',
    'font-family', 'font-size', 'font-weight', 'font-style', 'line-height',
    'letter-spacing', 'text-align', 'text-decoration-line', 'text-transform',
    'display', 'position', 'top', 'right', 'bottom', 'left', 'z-index',
    'width', 'height', 'min-width', 'min-height', 'max-width', 'max-height',
    'margin-top', 'margin-right', 'margin-bottom', 'margin-left',
    'padding-top', 'padding-right', 'padding-bottom', 'padding-left',
    'overflow-x', 'overflow-y', 'visibility', 'cursor', 'flex-grow', 'flex-basis'
];

interface Snapshot {
    [selector: string]: Record<string, string>;
}

/**
 * Runs in the window. A path per element, so a difference names something a
 * person can find, and repeated siblings are numbered rather than collapsed.
 */
function collect(properties: string[]): Snapshot {
    const out: Snapshot = {};
    const path_of = (el: Element): string => {
        const parts: string[] = [];
        for (let n: Element | null = el; n && n.nodeName !== 'HTML'; n = n.parentElement) {
            let part = n.nodeName.toLowerCase();
            if (n.id) part += '#' + n.id;
            const cls = (n.getAttribute('class') || '').trim().split(/\s+/).filter(Boolean);
            if (cls.length) part += '.' + cls.join('.');
            const parent = n.parentElement;
            if (parent) {
                const same = Array.from(parent.children).filter((c) => c.nodeName === n!.nodeName);
                if (same.length > 1) part += ':' + same.indexOf(n);
            }
            parts.unshift(part);
        }
        return parts.join(' > ');
    };

    for (const el of Array.from(document.querySelectorAll('*'))) {
        if (el.nodeName === 'SCRIPT' || el.nodeName === 'LINK' || el.nodeName === 'STYLE') continue;
        const computed = getComputedStyle(el);
        const values: Record<string, string> = {};
        for (const p of properties) values[p] = computed.getPropertyValue(p);
        // Repeated identical paths (a list of unstyled spans) would overwrite
        // each other; number them instead of losing them.
        let key = path_of(el);
        for (let n = 2; key in out; n++) key = path_of(el) + ' #' + n;
        out[key] = values;
    }
    return out;
}

function compare(a: string, b: string): void {
    const before: Snapshot = JSON.parse(fs.readFileSync(a, 'utf8'));
    const after: Snapshot = JSON.parse(fs.readFileSync(b, 'utf8'));

    const gone = Object.keys(before).filter((k) => !(k in after));
    const arrived = Object.keys(after).filter((k) => !(k in before));
    let changed = 0;

    for (const key of Object.keys(before)) {
        if (!(key in after)) continue;
        const diffs = PROPERTIES
            .filter((p) => before[key][p] !== after[key][p])
            .map((p) => `      ${p}: ${before[key][p]}  ->  ${after[key][p]}`);
        if (diffs.length) {
            changed += 1;
            console.log('  ' + key);
            console.log(diffs.join('\n'));
        }
    }

    // Elements appearing or vanishing is usually the window having settled
    // differently rather than a styling change, so they are reported apart.
    for (const k of gone) console.log('  gone:     ' + k);
    for (const k of arrived) console.log('  arrived:  ' + k);

    const total = changed + gone.length + arrived.length;
    console.log(total === 0
        ? `no difference across ${Object.keys(before).length} elements`
        : `${changed} element(s) styled differently, ${gone.length} gone, ${arrived.length} new`);
    process.exit(total === 0 ? 0 : 1);
}

async function snapshot(out: string, files: string[]): Promise<void> {
    const home = fs.mkdtempSync(path.join(os.tmpdir(), 'lt-style-'));
    const app = await electron.launch({
        executablePath: await electronBinary(),
        args: [CORE, '--no-sandbox'],
        env: {
            ...process.env,
            LT_HEADLESS: '1',
            LT_USER_DIR: home,
            LT_REMOTE_DEBUGGING_PORT: 'off'
        } as Record<string, string>
    });

    const window = await app.firstWindow();
    await window.waitForFunction("typeof lt !== 'undefined' && !!lt.objs && !!lt.objs.app");
    await window.waitForSelector('#multi');

    for (const file of files) {
        await window.evaluate(
            (f) => (globalThis as any).lt.objs.command.exec_BANG_(
                (globalThis as any).cljs.core.keyword.call(null, 'open-path'), f), file);
    }
    // Plugins register behaviors as they load and several of them restyle; a
    // snapshot taken before they finish differs from one taken after for
    // reasons that have nothing to do with the stylesheets.
    await window.waitForTimeout(2000);

    const result = await window.evaluate(collect, PROPERTIES);
    fs.writeFileSync(out, JSON.stringify(result, null, 1) + '\n');
    console.log(`${path.relative(ROOT, out)}: ${Object.keys(result).length} elements`);

    try {
        await app.evaluate(({ BrowserWindow }) => {
            for (const w of BrowserWindow.getAllWindows()) w.destroy();
        });
    } catch { /* already gone */ }
    await app.close().catch(() => { /* already gone */ });
    fs.rmSync(home, { recursive: true, force: true });
}

const args = process.argv.slice(2);
if (args[0] === '--compare') {
    if (args.length !== 3) {
        console.error('usage: node script/style-snapshot.mts --compare <before.json> <after.json>');
        process.exit(2);
    }
    compare(args[1], args[2]);
} else if (args.length === 0) {
    console.error('usage: node script/style-snapshot.mts <out.json> [file-to-open ...]');
    process.exit(2);
} else {
    await snapshot(path.resolve(args[0]), args.slice(1).map((f) => path.resolve(f)));
}
