// Walking a directory tree, on the worker thread.
//
// This is what fills the navigate-to-file bar, and a workspace can be large
// enough that walking it on the main thread stalls typing. Iterative rather
// than recursive, and synchronous: the worker has nothing else to do while it
// runs, and a queue is cheaper than a promise per directory.

import * as fs from 'fs';
import * as path from 'path';

export interface WalkOptions {
    /** Paths whose basename matches are skipped, directories included. */
    filter?: RegExp | undefined;
    /** Stop after roughly this many files. */
    limit?: number | undefined;
}

export interface WalkResult {
    /** How long the walk took, in milliseconds. */
    time: number;
    paths: string[];
    total: number;
    /** True when `limit` cut the walk short, so the caller knows it is partial. */
    limited: boolean;
}

/**
 * Every file under `root`, which may be one directory or a list of them.
 *
 * A directory that cannot be read is reported and skipped rather than
 * abandoning the walk — an unreadable directory somewhere in a workspace should
 * cost its own contents and nothing else. A file that cannot be stat'd is
 * retried with `lstat`, which is what answers for a broken symlink.
 */
export function walk(root: string | string[], options: WalkOptions): WalkResult {
    const start = Date.now();
    const { filter, limit } = options;
    const found: string[] = [];
    let queue: string[] = Array.isArray(root) ? root.slice() : [root];
    let limited = false;

    let cur: string | undefined;
    while ((cur = queue.shift()) !== undefined) {
        let children: string[];
        try {
            children = fs.readdirSync(cur);
        } catch {
            console.error("Couldn't read dir " + cur);
            continue;
        }

        for (const child of children) {
            const full = path.join(cur, child);
            let stat: fs.Stats;
            try {
                stat = fs.statSync(full);
            } catch {
                try {
                    stat = fs.lstatSync(full);
                } catch {
                    continue;
                }
            }

            const isDir = stat.isDirectory();
            // The filter is matched against a trailing separator for
            // directories, so a pattern can exclude `node_modules/` without
            // also excluding a file called node_modules.
            if (filter && (isDir ? child + path.sep : child).match(filter)) continue;

            if (isDir) {
                queue.push(full);
            } else if (!limit || found.length <= limit) {
                found.push(full);
            } else {
                queue = [];
                limited = true;
                break;
            }
        }
    }

    return { time: Date.now() - start, paths: found, total: found.length, limited };
}
