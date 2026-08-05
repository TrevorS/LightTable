#!/usr/bin/env node

// Fetch the ripgrep binary Light Table searches with, pinned and verified.
//
// Unlike script/fetch-clj-kondo.mts, which fetches a linter into .tools/, this
// one fetches something that **ships**: it lands in deploy/core/bin and goes
// into the packaged application. Two consequences follow, and both are handled
// elsewhere rather than here, so they are named:
//
//   - On macOS it has to be code-signed. script/build-app.sh signs the app
//     bundle and, before this existed, only walked Contents/Frameworks for
//     .app/.framework/.dylib — so a plain executable under Resources was signed
//     by nothing, and on Apple Silicon an unsigned executable inside a signed
//     bundle is refused at exec time.
//   - If it is missing, search still works. lt.background.search falls back to
//     the tree walk it used before, which is tested and correct and merely
//     slower. A missing binary must not mean an editor with no search.
//
// The policy this follows is the one already written down in
// [the plugin docs](../plugins/README.md) and restated by the clj-kondo
// fetcher: source in the repository, no binaries in the repository, and
// binaries fetched at build time pinned and checksummed.
//
// **Why upstream rather than @vscode/ripgrep.** VS Code's package downloads
// from microsoft/ripgrep-prebuilt in a postinstall script, and
// deploy/core/.npmrc sets ignore-scripts, so it would not run — the same way
// binwrap did not for clj-kondo. Going to the release directly avoids that, and
// it is also a stronger guarantee: BurntSushi/ripgrep publishes a .sha256
// beside every asset and the prebuilt fork does not, so the digests below were
// read from upstream rather than computed from whatever arrived.
//
//     node script/fetch-ripgrep.mts          fetch if missing
//     node script/fetch-ripgrep.mts --force  fetch again
//     node script/fetch-ripgrep.mts --path   print the path and exit

import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import * as crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { ROOT } from './lib/paths.mts';

const VERSION = '15.2.0';

// From ripgrep-<version>-<target>.{tar.gz,zip}.sha256 in the GitHub release.
// Update all of them together with the version: a bump with stale digests fails
// the check, which is the intended outcome rather than an inconvenience.
//
// musl for Linux on purpose. The gnu builds link against whatever glibc the
// build machine had, and this binary is copied into a bundle that runs on
// somebody else's; a statically linked one has no such opinion.
const DIGESTS: Record<string, string> = {
    'aarch64-apple-darwin':       '3750b2e93f37e0c692657da574d7019a101c0084da05a790c83fd335bad973e4',
    'x86_64-apple-darwin':        'af7825fcc69a2afc7a7aea55fc9af90e26421d8f20fe59df32e233c0b8a231c1',
    'aarch64-unknown-linux-musl': '800b1e7206afe799dfb5a6901f23147cfaabe0e52210538100f61e86e1740915',
    'x86_64-unknown-linux-musl':  '33e15bcf1624b25cdd2a55813a47a2f95dbe126268203e76aa6a585d1e7b149c',
    'aarch64-pc-windows-msvc':    'e4abca10c3a64ebea742667dd7009449d49403db5460dd6873e389fa2945360f',
    'x86_64-pc-windows-msvc':     '71b2fef860abe467217a538ff31de02f5258807c0129f771846f87bd029aafc5'
};

// Under deploy/core because `cp -R deploy/core` is what carries it into the
// bundle — see script/build-app.sh. Anywhere else in the tree would work locally
// and not ship, and test-electron/ripgrep.test.ts is what says so.
const BINARY = path.join(ROOT, 'deploy', 'core', 'bin',
                         process.platform === 'win32' ? 'rg.exe' : 'rg');

/** Which release asset this machine wants. */
function target(): string {
    const arch = ({ x64: 'x86_64', arm64: 'aarch64' } as Record<string, string>)[process.arch];
    if (!arch) throw new Error(`no ripgrep build for ${process.arch}`);
    if (process.platform === 'darwin') return `${arch}-apple-darwin`;
    if (process.platform === 'win32') return `${arch}-pc-windows-msvc`;
    if (process.platform === 'linux') return `${arch}-unknown-linux-musl`;
    throw new Error(`no ripgrep build for ${process.platform}`);
}

function sha256(buffer: Buffer): string {
    return crypto.createHash('sha256').update(buffer).digest('hex');
}

async function download(url: string): Promise<Buffer> {
    const res = await fetch(url, { redirect: 'follow' });
    if (!res.ok) throw new Error(`GET ${url} failed: ${res.status} ${res.statusText}`);
    return Buffer.from(await res.arrayBuffer());
}

/** The version string `rg --version` reports, or null if it will not run. */
function installedVersion(): string | null {
    try {
        return execFileSync(BINARY, ['--version'], { encoding: 'utf8' }).split('\n')[0]!.trim();
    } catch {
        return null;
    }
}

async function main(): Promise<void> {
    if (process.argv.includes('--path')) { console.log(BINARY); return; }

    if (fs.existsSync(BINARY) && !process.argv.includes('--force')) {
        // The binary's own answer rather than a marker file, which can outlive
        // the thing it marks.
        const have = installedVersion();
        if (have && have.includes(VERSION)) { console.log(`${have} already fetched`); return; }
    }

    const name = target();
    const expected = DIGESTS[name];
    if (!expected) throw new Error(`no pinned digest for ${name}`);

    const windows = process.platform === 'win32';
    const file = `ripgrep-${VERSION}-${name}.${windows ? 'zip' : 'tar.gz'}`;
    const url = `https://github.com/BurntSushi/ripgrep/releases/download/${VERSION}/${file}`;
    console.log(`fetching ${file}`);

    const archive = await download(url);
    const actual = sha256(archive);
    if (actual !== expected) {
        throw new Error(`${file} does not match its pinned digest\n` +
                        `  expected ${expected}\n  got      ${actual}`);
    }

    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'ripgrep-'));
    const archivePath = path.join(tmp, file);
    fs.writeFileSync(archivePath, archive);

    // The archive holds a directory with the binary, its man page and its shell
    // completions. Only the binary is wanted — a man page in an Electron bundle
    // is 400KB nobody will ever read.
    if (windows) {
        execFileSync('powershell', ['-NoProfile', '-Command',
            `Expand-Archive -LiteralPath '${archivePath}' -DestinationPath '${tmp}' -Force`]);
    } else {
        execFileSync('tar', ['-xzf', archivePath, '-C', tmp]);
    }

    const unpacked = path.join(tmp, `ripgrep-${VERSION}-${name}`, windows ? 'rg.exe' : 'rg');
    if (!fs.existsSync(unpacked)) throw new Error(`${file} did not contain an rg binary`);

    fs.mkdirSync(path.dirname(BINARY), { recursive: true });
    fs.copyFileSync(unpacked, BINARY);
    fs.chmodSync(BINARY, 0o755);
    fs.rmSync(tmp, { recursive: true, force: true });

    const version = installedVersion();
    if (!version) throw new Error(`${BINARY} was fetched but will not run`);
    if (!version.includes(VERSION)) {
        throw new Error(`fetched ${VERSION} but the binary reports ${version}`);
    }
    console.log(`${version} -> ${BINARY}`);
}

/** Where the binary is, fetching it first if it is not there yet. */
export function ensure(): string {
    if (!fs.existsSync(BINARY)) {
        execFileSync(process.execPath, [fileURLToPath(import.meta.url)], { stdio: 'inherit' });
    }
    return BINARY;
}

export { BINARY, VERSION };

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
    main().catch(function (e: Error) { console.error(String(e.message)); process.exit(1); });
}
