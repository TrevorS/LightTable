// Fail if the ClojureScript build produced a warning.
//
// shadow-cljs exits zero with warnings, so something has to read its output.
// This was `grep -c 'WARNING #' build.log` inline in the workflow, which has
// the failure mode every grep-as-a-gate has: if that banner's format ever
// changes — a plausible thing for a minor version to do — the grep finds
// nothing and the step passes green with warnings present.
//
// So it checks two things that disagree when the format moves:
//
//   1. no `WARNING #` banner, which is the warning itself; and
//   2. every `Build completed` line reports `0 warnings`, which is shadow's
//      own count of what it just did.
//
// and it requires the expected number of builds to have reported at all. A
// changed format then fails loudly as "expected 3 builds, read 0" instead of
// passing silently, which is the whole point.

import * as fs from 'node:fs';

const [logPath, expectedRaw] = process.argv.slice(2);
if (!logPath) {
    console.error('usage: check-build-warnings.mts <build.log> [expected-builds]');
    process.exit(2);
}
const expected = Number(expectedRaw ?? 3);

// shadow colours its output, so the log is not plain text.
const log = fs.readFileSync(logPath, 'utf8').replace(/\[[0-9;]*m/g, '');

const problems: string[] = [];

const lines = log.split('\n');
const banners = lines
    .map((l, i) => [l, i] as const)
    .filter(([l]) => /^-+ WARNING #\d+/.test(l));
if (banners.length) {
    problems.push(`${banners.length} ClojureScript warning(s)`);
    // The banner names the linter and the line under it names the file, so a
    // few lines of context is the difference between a number and something
    // to go and fix.
    for (const [, i] of banners) problems.push(lines.slice(i, i + 8).join('\n'));
}

const completed = [...log.matchAll(/\[:(\S+)\] Build completed\. \([^)]*?(\d+) warnings?,/g)];
if (completed.length !== expected) {
    problems.push(
        `expected ${expected} builds to report completion, read ${completed.length}. ` +
        'shadow-cljs may have changed its output format — this gate reads it, so ' +
        'it has to fail rather than assume zero.');
}
for (const [, build, count] of completed) {
    if (Number(count) !== 0) problems.push(`[:${build}] reports ${count} warning(s)`);
}

if (problems.length) {
    for (const p of problems) console.error(`::error::${p}`.replace(/\n/g, '\n'));
    process.exit(1);
}

console.log(`${completed.length} builds, 0 warnings`);
