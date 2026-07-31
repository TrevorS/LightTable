#!/usr/bin/env node

// Fetch the clj-kondo binary this repository lints with, pinned and verified.
//
// This replaces the `clj-kondo` npm package, which is a thin wrapper around
// `binwrap` that downloads the same binary from the same GitHub release. That
// wrapper brought `request` with it, and `request` has been deprecated since
// 2020: eight advisories reached this repository through it — three of them
// critical — every one marked "no fix available", because there is no fix
// coming for an unmaintained HTTP client.
//
// It also stopped working. npm blocks dependency install scripts by default
// now, so `binwrap-install` never ran, and a fresh clone got a clj-kondo
// package with no binary in it — a linter that a build could not find.
//
// So the binary is fetched here instead, which is what
// [the plugin policy](../plugins/README.md) already allows: source in the
// repository, no binaries in the repository, and binaries fetched at build
// time pinned and checksummed. Upstream publishes a .sha256 beside every
// asset; the digests below were read from those and are checked on every
// download, so a tampered or truncated file fails loudly rather than becoming
// a confusing lint error.
//
//     node script/fetch-clj-kondo.js          fetch if missing
//     node script/fetch-clj-kondo.js --force  fetch again
//     node script/fetch-clj-kondo.js --path   print the path and exit

import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import * as crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { ROOT } from './lib/paths.mts';

const VERSION = '2025.10.23';

// From clj-kondo-<version>-<asset>.zip.sha256 in the GitHub release. Update
// both together: a version bump with stale digests fails the check, which is
// the intended outcome.
const DIGESTS: Record<string, string> = {
    'linux-aarch64':      '75c90f734caac87e1cabb163fbe2201a2e985f6be72eb1e0f132a7f774b33fcb',
    'linux-amd64':        '7d3e563668ec4e8da164c78ed1a9264b5f442a2933c4934c6d0a06652bbfe494',
    'linux-static-amd64': '78800fe62fb20be046067e7b90e0066a4cdf0e96b5dde1e0d73c8e141fa70663',
    'macos-aarch64':      '9915429099bdb5d35ce0cc88e0e346d9be78a7fd44d9ea8689b19843927e3a07',
    'macos-amd64':        'b6876f9311f2998cce0df226adf4792eacf287cfeba9bd067f44d56650956970',
    'windows-amd64':      '0a72b26b6cd0b80089285a845b1428b6636eb2a77fbf581a78f417d1e5557c27'
};

const TOOLS = path.join(ROOT, '.tools');
const BINARY = path.join(TOOLS, process.platform === 'win32' ? 'clj-kondo.exe' : 'clj-kondo');

/** Which release asset this machine wants. */
function asset(): string {
    const arch = ({ x64: 'amd64', arm64: 'aarch64' } as Record<string, string>)[process.arch];
    if (!arch) throw new Error(`no clj-kondo build for ${process.arch}`);
    if (process.platform === 'darwin') return `macos-${arch}`;
    if (process.platform === 'win32') return 'windows-amd64';
    if (process.platform === 'linux') return `linux-${arch}`;
    throw new Error(`no clj-kondo build for ${process.platform}`);
}

function sha256(buffer: Buffer): string {
    return crypto.createHash('sha256').update(buffer).digest('hex');
}

async function download(url: string): Promise<Buffer> {
    const res = await fetch(url, { redirect: 'follow' });
    if (!res.ok) throw new Error(`GET ${url} failed: ${res.status} ${res.statusText}`);
    return Buffer.from(await res.arrayBuffer());
}

async function main(): Promise<void> {
    if (process.argv.includes('--path')) { console.log(BINARY); return; }

    if (fs.existsSync(BINARY) && !process.argv.includes('--force')) {
        // Already here and already the pinned version — the check is the
        // binary's own answer rather than a marker file that can outlive it.
        try {
            const have = execFileSync(BINARY, ['--version'], { encoding: 'utf8' }).trim();
            if (have.includes(VERSION)) { console.log(`clj-kondo ${VERSION} already fetched`); return; }
        } catch { /* unusable; fetch it again */ }
    }

    const name = asset();
    const expected = DIGESTS[name];
    if (!expected) throw new Error(`no pinned digest for ${name}`);

    const file = `clj-kondo-${VERSION}-${name}.zip`;
    const url = `https://github.com/clj-kondo/clj-kondo/releases/download/v${VERSION}/${file}`;
    console.log(`fetching ${file}`);

    const zip = await download(url);
    const actual = sha256(zip);
    if (actual !== expected) {
        throw new Error(`${file} does not match its pinned digest\n` +
                        `  expected ${expected}\n  got      ${actual}`);
    }

    fs.mkdirSync(TOOLS, { recursive: true });
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'clj-kondo-'));
    const zipPath = path.join(tmp, file);
    fs.writeFileSync(zipPath, zip);

    // Every platform this runs on has one of these, and shelling out beats a
    // zip library for a single file we have already verified.
    if (process.platform === 'win32') {
        execFileSync('powershell', ['-NoProfile', '-Command',
            `Expand-Archive -LiteralPath '${zipPath}' -DestinationPath '${tmp}' -Force`]);
    } else {
        execFileSync('unzip', ['-q', '-o', zipPath, '-d', tmp]);
    }

    const unpacked = path.join(tmp, process.platform === 'win32' ? 'clj-kondo.exe' : 'clj-kondo');
    if (!fs.existsSync(unpacked)) throw new Error(`${file} did not contain a clj-kondo binary`);
    fs.copyFileSync(unpacked, BINARY);
    fs.chmodSync(BINARY, 0o755);
    fs.rmSync(tmp, { recursive: true, force: true });

    console.log(`clj-kondo ${execFileSync(BINARY, ['--version'], { encoding: 'utf8' }).trim()} -> ${BINARY}`);
}

/** Where the binary is, fetching it first if it is not there yet. */
export function ensure(): string {
    if (!fs.existsSync(BINARY)) {
        execFileSync(process.execPath, [fileURLToPath(import.meta.url)], { stdio: 'inherit' });
    }
    return BINARY;
}

export { BINARY, VERSION };

// `require.main === module` has no ES module equivalent; comparing argv[1] to
// this file's own path is the documented one.
if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
    main().catch(function (e: Error) { console.error(String(e.message)); process.exit(1); });
}
