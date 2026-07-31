// Where things are, worked out once.
//
// `__dirname` does not exist in an ES module, and every script here wants the
// repository root, so this is the one place that derives it. import.meta.url
// is a file:// URL — fileURLToPath rather than slicing it, because a checkout
// under a path with a space in it produces %20 and a script that cannot find
// itself.

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
