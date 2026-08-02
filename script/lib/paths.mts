// Where things are, worked out once.
//
// `__dirname` does not exist in an ES module, and every script here wants the
// repository root, so this is the one place that derives it. import.meta.url
// is a file:// URL — fileURLToPath rather than slicing it, because a checkout
// under a path with a space in it produces %20 and a script that cannot find
// itself.

import * as fs from 'node:fs';
import * as path from 'node:path';
import { fileURLToPath } from 'node:url';

/** The directory holding the scripts. */
export const SCRIPT_DIR = path.dirname(path.dirname(fileURLToPath(import.meta.url)));

/** The repository root. */
export const ROOT = path.dirname(SCRIPT_DIR);

/** What ships: the application directory Electron is pointed at. */
export const CORE = path.join(ROOT, 'deploy', 'core');

/** Where a packaged build lands. */
export const BUILDS = path.join(ROOT, 'builds');

/**
 * The packaged application in `builds/`, as an Electron binary and the app
 * directory it was given — or null when nothing has been packaged.
 *
 * This is what a person double-clicks, and it differs from `deploy/core` in
 * ways that have mattered: its own copy of every plugin, its own Electron, its
 * own `node_modules`, and a resources layout the tree does not have. A bug
 * that only appears there is the kind nobody can reproduce.
 *
 * The binary *is* the launcher — the app directory is baked in — so `args`
 * carries no path, unlike the development binary.
 */
export function packagedApp(): { binary: string; args: string[] } | null {
    if (!fs.existsSync(BUILDS)) return null;
    for (const entry of fs.readdirSync(BUILDS)) {
        const dir = path.join(BUILDS, entry);
        const candidates = [
            // macOS: builds/LightTable-x.y.z-mac/LightTable.app
            path.join(dir, 'LightTable.app', 'Contents', 'MacOS', 'LightTable'),
            path.join(dir, 'LightTable.app', 'Contents', 'MacOS', 'Electron'),
            // Linux and Windows put the launcher beside the resources.
            path.join(dir, 'LightTable'),
            path.join(dir, 'LightTable.exe')
        ];
        const binary = candidates.find((c) => fs.existsSync(c));
        if (binary) return { binary, args: [] };
    }
    return null;
}

/**
 * The Electron binary, wherever this platform put it.
 *
 * The electron package reports its own path, which differs by platform —
 * dist/electron on Linux, dist/Electron.app/Contents/MacOS/Electron on a Mac.
 * Hard-coding the Linux one worked here and nowhere else.
 */
export async function electronBinary(): Promise<string> {
    const mod = await import(
        path.join(ROOT, 'deploy', 'electron', 'node_modules', 'electron', 'index.js'));
    return (mod.default ?? mod) as string;
}
