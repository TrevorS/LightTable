#!/usr/bin/env node

// Install the packaged build so it can be used as an installed application.
//
// `make run` runs the editor out of the tree and `make build` packages one into
// `builds/`, and neither of those is the thing a person double-clicks. This is:
// it puts the packaged application where the desktop looks for one, so the RC can
// be tested from Spotlight and the dock with a real user-data directory rather
// than from a terminal in the repository.
//
// That difference is worth testing rather than assuming. User data used to be
// written *inside* the application directory, which worked from a checkout and
// broke a packaged build — `codesign --verify` reported a sealed resource
// missing, because the first run had added files to a signed bundle. Running from
// /Applications is the case where that fails, so it is the case worth exercising.
//
//     make install                 into /Applications, or ~/.local on Linux
//     make install DEST=~/Applications
//
// It does not build. A stale build installed silently is the same problem
// `App: What build is this?` exists for, so this reports what it is installing and
// says so when that is not HEAD.

import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { execFileSync } from 'node:child_process';
import { ROOT } from './lib/paths.mts';

const BUILDS = path.join(ROOT, 'builds');

function fail(msg: string): never {
    console.error(msg);
    process.exit(1);
}

function run(cmd: string, args: string[]): void {
    execFileSync(cmd, args, { stdio: 'inherit' });
}

/** `git args…`, or null when git has nothing to say. */
function git(...args: string[]): string | null {
    try {
        return execFileSync('git', args, { cwd: ROOT, encoding: 'utf8' }).trim();
    } catch {
        return null;
    }
}

/**
 * The packaged build in `builds/`: its directory, and the `.app` inside it on a
 * Mac.
 *
 * `packagedApp()` in lib/paths.mts finds the *launcher*, which is what booting
 * one needs. Installing needs the bundle around it, so this looks for that
 * instead rather than deriving one from the other and getting the number of
 * `..`s wrong.
 */
function build(): { dir: string; bundle: string | null; name: string } {
    if (!fs.existsSync(BUILDS)) fail('No builds/ — run `make build` first.');
    const entries = fs.readdirSync(BUILDS)
        .map((e) => path.join(BUILDS, e))
        .filter((p) => fs.statSync(p).isDirectory());

    if (!entries.length) fail('Nothing in builds/ — run `make build` first.');
    if (entries.length > 1) {
        // Two builds means two versions, and picking one for you is how the
        // wrong one gets installed. `make clean` or name it with DEST.
        fail('More than one build in builds/, so which one is ambiguous:\n' +
             entries.map((e) => `  ${path.basename(e)}`).join('\n') +
             '\nRun `make clean && make build`.');
    }

    const dir = entries[0]!;
    const app = path.join(dir, 'LightTable.app');
    return { dir, bundle: fs.existsSync(app) ? app : null, name: path.basename(dir) };
}

/** What the build says it was made from, read back from its own stamp. */
function stamp(dir: string): { commit?: string; branch?: string; dirty?: boolean } {
    const file = path.join(dir, process.platform === 'darwin'
        ? path.join('LightTable.app', 'Contents', 'Resources', 'app', 'core')
        : path.join('resources', 'app', 'core'),
        'lighttable', 'build.json');
    try {
        return JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch {
        return {};
    }
}

function describe(dir: string): void {
    const s = stamp(dir);
    const head = git('rev-parse', '--short', 'HEAD');
    const what = `${s.commit ?? '(no stamp)'}${s.dirty ? '+dirty' : ''}`
        + (s.branch ? ` on ${s.branch}` : '');
    console.log(`installing ${path.basename(dir)} — ${what}`);
    if (s.commit && head && s.commit !== head) {
        console.warn(`  warning: that is not HEAD (${head}). Repackage with \`make build\`.`);
    }
    if (s.dirty) {
        console.warn('  warning: built from uncommitted changes, so it cannot be reproduced.');
    }
}

function installMac(bundle: string, dest: string): void {
    const target = path.join(dest, 'LightTable.app');

    if (!fs.existsSync(dest)) fail(`${dest} does not exist.`);
    try {
        fs.accessSync(dest, fs.constants.W_OK);
    } catch {
        fail(`${dest} is not writable. Try \`make install DEST=~/Applications\`.`);
    }

    // Removed rather than copied over. A bundle is a sealed directory: merging a
    // new build into an old one leaves whatever the old one had that the new one
    // does not, and `codesign --verify` then fails on a resource that is present
    // and unaccounted for.
    if (fs.existsSync(target)) {
        console.log(`replacing ${target}`);
        fs.rmSync(target, { recursive: true, force: true });
    }

    // `ditto` rather than `cp -R`: it is the documented way to copy a bundle on
    // macOS and it preserves the extended attributes the code signature lives in.
    // A copy that loses those produces an application the system refuses to open,
    // and the message it gives says nothing about signatures.
    run('ditto', [bundle, target]);

    // Say whether what was installed is still valid, rather than leaving it to be
    // discovered by a launch that dies without a message.
    try {
        execFileSync('codesign', ['--verify', '--deep', '--strict', target], { stdio: 'pipe' });
        console.log('signature verifies');
    } catch {
        console.warn('warning: the installed signature did not verify; it may not launch.');
    }

    console.log(`installed ${target}`);
    console.log('Open it from Spotlight or the dock. It writes its settings to '
                + '~/Library/Application Support/LightTable, not into the bundle.');
}

function installLinux(dir: string, prefix: string): void {
    const opt = path.join(prefix, 'opt', 'lighttable');
    const bin = path.join(prefix, 'bin');

    fs.mkdirSync(path.dirname(opt), { recursive: true });
    fs.mkdirSync(bin, { recursive: true });

    if (fs.existsSync(opt)) {
        console.log(`replacing ${opt}`);
        fs.rmSync(opt, { recursive: true, force: true });
    }
    run('cp', ['-R', dir, opt]);

    const launcher = path.join(opt, 'LightTable');
    if (!fs.existsSync(launcher)) fail(`No LightTable launcher in ${opt}.`);
    fs.chmodSync(launcher, 0o755);

    const link = path.join(bin, 'lighttable');
    fs.rmSync(link, { force: true });
    fs.symlinkSync(launcher, link);

    console.log(`installed ${opt}`);
    console.log(`linked ${link}`);
    // Said rather than written: a .desktop file wants an icon path, a MIME list
    // and a category, and guessing those produces a menu entry somebody has to
    // find and delete. It is a small file and it should be a decision.
    console.log('No .desktop entry is written, so this will not appear in an '
                + 'application menu — run `lighttable` if it is on your PATH.');
}

function main(): void {
    const { dir, bundle, name } = build();
    describe(dir);

    const dest = process.env['DEST'];

    if (process.platform === 'darwin') {
        if (!bundle) fail(`${name} has no LightTable.app in it. Was it built on this platform?`);
        installMac(bundle, dest ? dest.replace(/^~/, os.homedir()) : '/Applications');
        return;
    }

    if (process.platform === 'linux') {
        installLinux(dir, dest ? dest.replace(/^~/, os.homedir())
                               : path.join(os.homedir(), '.local'));
        return;
    }

    // Windows packaging exists in build-app.sh and has no CI runner, so it is
    // untested — see doc/hygiene.md. Claiming to install it would be worse than
    // saying it is not covered.
    fail(`No install for ${process.platform}. The build is in ${dir}; copy it where you want it.`);
}

main();
