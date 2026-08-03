// Every colour the kit actually paints, read off the rendered page.
//
// A token sheet refactor is exactly the kind of change that is obviously safe
// and silently is not: `color-mix(in srgb, X 6%, transparent)` should equal
// `rgba(X, 0.06)`, and "should" is doing a lot of work in that sentence. So
// this records what every story paints, and the same command run after the
// change says whether anything moved.
//
//     node script/kit-colours.mts > before.json     # then change the CSS
//     node script/kit-colours.mts --against before.json
//
// Needs `npm run storybook:build` first, and reads the same static build
// `script/check-stories.mts` does.

import * as http from 'node:http';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { chromium } from '@playwright/test';

const ROOT = path.join(import.meta.dirname, '..', 'storybook-static');
const PORT = 6109;
const against = process.argv.indexOf('--against');
const baseline = against >= 0 ? process.argv[against + 1] : null;

const TYPES: Record<string, string> = {
    '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
    '.json': 'application/json', '.map': 'application/json', '.svg': 'image/svg+xml',
    '.woff2': 'font/woff2', '.woff': 'font/woff', '.png': 'image/png'
};

const server = http.createServer((req, res) => {
    const url = decodeURIComponent((req.url ?? '/').split('?')[0]!);
    let file = path.join(ROOT, url === '/' ? 'index.html' : url);
    if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(ROOT, 'index.html');
    res.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] ?? 'application/octet-stream' });
    fs.createReadStream(file).pipe(res);
});
await new Promise<void>((resolve) => server.listen(PORT, resolve));

const index = JSON.parse(fs.readFileSync(path.join(ROOT, 'index.json'), 'utf8')) as
    { entries: Record<string, { id: string; type: string }> };
const stories = Object.values(index.entries).filter((e) => e.type === 'story');

const browser = await chromium.launch();
const page = await browser.newPage();
const painted: Record<string, string[]> = {};

for (const story of stories) {
    await page.goto(`http://localhost:${PORT}/iframe.html?viewMode=story&id=${story.id}`,
                    { waitUntil: 'networkidle' });
    painted[story.id] = await page.evaluate(() => {
        // Through a canvas, because two identical colours do not have to be
        // spelled identically. `color-mix` computes to `color(srgb 0.95 …)`
        // where a literal computes to `rgba(243, …)`, and comparing the strings
        // reports a change in 44 stories that all paint the same pixels.
        // Painting one and reading it back is the only comparison that means
        // what it says.
        const canvas = document.createElement('canvas');
        canvas.width = canvas.height = 1;
        const ctx = canvas.getContext('2d', { willReadFrequently: true })!;
        const px = (colour: string): string => {
            ctx.clearRect(0, 0, 1, 1);
            ctx.fillStyle = colour;
            ctx.fillRect(0, 0, 1, 1);
            return [...ctx.getImageData(0, 0, 1, 1).data].join(',');
        };
        const out: string[] = [];
        for (const el of Array.from(document.querySelectorAll('#storybook-root *'))) {
            const s = getComputedStyle(el);
            // Class first, so a diff names the rule rather than a node index.
            out.push(`${el.className || el.tagName} bg=${px(s.backgroundColor)} ` +
                     `fg=${px(s.color)} border=${px(s.borderColor)} shadow=${s.boxShadow}`);
        }
        return out;
    });
}

await browser.close();
server.close();

if (!baseline) {
    console.log(JSON.stringify(painted, null, 1));
    process.exit(0);
}

const was = JSON.parse(fs.readFileSync(baseline, 'utf8')) as Record<string, string[]>;
const moved: string[] = [];
for (const id of Object.keys({ ...was, ...painted })) {
    const a = (was[id] ?? []).join('\n');
    const b = (painted[id] ?? []).join('\n');
    if (a !== b) {
        moved.push(id);
        const al = a.split('\n'); const bl = b.split('\n');
        for (let i = 0; i < Math.max(al.length, bl.length); i++) {
            if (al[i] !== bl[i]) console.error(`  ${id}\n    was ${al[i]}\n    now ${bl[i]}`);
        }
    }
}

if (moved.length) {
    console.error(`${moved.length} of ${stories.length} stories changed colour`);
    process.exit(1);
}
console.log(`  ok   ${stories.length} stories paint exactly what they painted before`);
