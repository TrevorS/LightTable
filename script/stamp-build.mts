// Record what this build was made from, next to what it made.
//
// "Is my change in the window I am looking at?" had no answer. The bundle is a
// build artifact with no identity — `version.json` carries the release number,
// which is the same string across every build between two releases — so the
// only way to tell a stale window from a fresh one was to grep the compiled
// JavaScript for a string you had just written. That is what actually happened,
// twice, and it cost a round of "still doesn't work" each time.
//
// Written by `build:cljs`, beside the bundle it describes, and gitignored:
// stamping a tracked file would make the tree dirty on every build, which is
// worse than the problem.

import { execFileSync } from 'node:child_process';
import * as fs from 'node:fs';
import * as path from 'node:path';

const ROOT = path.join(import.meta.dirname, '..');
const OUT = path.join(ROOT, 'deploy', 'core', 'lighttable', 'build.json');

/** `git args…`, or null when git has nothing to say — a tarball, no repository. */
function git(...args: string[]): string | null {
    try {
        return execFileSync('git', args, { cwd: ROOT, encoding: 'utf8' }).trim();
    } catch {
        return null;
    }
}

// `dirty` matters more than the commit here. A window built from uncommitted
// work is the normal case while you are working, and it is exactly the case
// where "which commit" answers the wrong question.
const status = git('status', '--porcelain');

const stamp = {
    commit: git('rev-parse', '--short', 'HEAD'),
    branch: git('rev-parse', '--abbrev-ref', 'HEAD'),
    dirty: status === null ? null : status.length > 0,
    built: new Date().toISOString()
};

fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, JSON.stringify(stamp, null, 2) + '\n');

console.log(`built ${stamp.commit ?? '(no git)'}${stamp.dirty ? '+dirty' : ''}`
            + ` on ${stamp.branch ?? '?'} at ${stamp.built}`);
