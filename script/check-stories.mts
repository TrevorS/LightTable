// Every story renders, and none of them says anything to the console.
//
// A Storybook build succeeding proves the bundle compiled. It does not prove a
// single component drew anything: a story whose render throws shows an empty
// frame and a green build, which is the same shape of silence this project has
// paid for several times elsewhere.
//
// So this drives the static build the way a person would — one page per story,
// through the same `iframe.html` the sidebar loads — and asserts three things
// per story: the root has content, the content is not just an empty wrapper,
// and nothing reached the console. The story list comes from the built
// `index.json`, so a story that exists and is never opened is impossible.
//
// Needs `npm run storybook:build` first. `make storybook-check` does both.

import { execFileSync } from 'node:child_process';
import * as http from 'node:http';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { chromium } from '@playwright/test';

const ROOT = path.join(import.meta.dirname, '..', 'storybook-static');
const PORT = 6107;

const TYPES: Record<string, string> = {
    '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
    '.json': 'application/json', '.map': 'application/json', '.svg': 'image/svg+xml',
    '.woff2': 'font/woff2', '.woff': 'font/woff', '.png': 'image/png'
};

if (!fs.existsSync(path.join(ROOT, 'index.json'))) {
    console.error('No storybook-static/index.json — run `npm run storybook:build` first.');
    process.exit(1);
}

const server = http.createServer((req, res) => {
    const url = decodeURIComponent((req.url ?? '/').split('?')[0]!);
    let file = path.join(ROOT, url === '/' ? 'index.html' : url);
    // Storybook's own routing is client-side; anything unresolved is the shell.
    if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(ROOT, 'index.html');
    res.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] ?? 'application/octet-stream' });
    fs.createReadStream(file).pipe(res);
});
await new Promise<void>((resolve) => server.listen(PORT, resolve));

interface Entry { id: string; title: string; name: string; type: string }
const index = JSON.parse(fs.readFileSync(path.join(ROOT, 'index.json'), 'utf8')) as
    { entries: Record<string, Entry> };
const stories = Object.values(index.entries).filter((e) => e.type === 'story');

const browser = await chromium.launch();
const page = await browser.newPage();
const noise: string[] = [];
page.on('pageerror', (e) => noise.push(`${current}: ${e}`));
page.on('console', (m) => { if (m.type() === 'error') noise.push(`${current}: ${m.text()}`); });

let current = '';
const empty: string[] = [];

for (const story of stories) {
    current = story.id;
    await page.goto(`http://localhost:${PORT}/iframe.html?viewMode=story&id=${story.id}`,
                    { waitUntil: 'networkidle' });
    // Text or an element with a class: a component that renders one empty div
    // has technically drawn and has shown nothing.
    const drawn = await page.evaluate(() => {
        const root = document.querySelector('#storybook-root');
        if (!root) return 0;
        return root.textContent!.trim().length
            + root.querySelectorAll('[class]:not(.lt-story)').length;
    });
    if (!drawn) empty.push(story.id);
}

await browser.close();
server.close();

// Every alias in the kit has stories, or says why it does not.
//
// The same completeness argument `test-e2e/catalogue.spec.ts` makes for the
// in-editor catalogue, made here: a component nobody wrote stories for is
// invisible, and a gallery whose gaps are invisible is a gallery that stops
// being true one component at a time. `:excluded` in the registry is how a
// deliberate absence says so — `lt.ui.pane/pane` is drawn with a live editor by
// the catalogue and would be a picture of a text area here.
const aliases = JSON.parse(execFileSync(
    process.execPath,
    [path.join(import.meta.dirname, '..', 'target', 'stories-manifest.js'), '--aliases'],
    { encoding: 'utf8' })) as { alias: string; states: number; excluded: string | null }[];
const silent = aliases.filter((a) => !a.states && !a.excluded).map((a) => a.alias);

for (const id of empty) console.error(`  FAIL ${id} — rendered nothing`);
for (const line of noise) console.error(`  FAIL ${line}`);
for (const alias of silent) console.error(`  FAIL ${alias} — no states and no :excluded reason`);

if (empty.length || noise.length || silent.length) {
    console.error(`${stories.length} stories, ${empty.length} empty, ` +
                  `${noise.length} console errors, ${silent.length} undescribed`);
    process.exit(1);
}
const excluded = aliases.filter((a) => a.excluded).length;
console.log(`  ok   ${stories.length} stories over ${aliases.length - excluded} components ` +
            `render, no console errors` + (excluded ? `; ${excluded} deliberately excluded` : ''));
