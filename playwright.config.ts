// Integration tests: the real application, one instance per test file.
//
// This is the layer script/smoke-test.mts could not be. That harness boots one
// window and asserts eighty-odd things against it in sequence, which is a good
// answer to "is the assembled application wired together" and a bad one to
// everything else — a test cannot arrange its own state, a failure halfway
// through costs every check after it, and the main process is out of reach
// entirely. The bug that shipped a white second window lived exactly there.
//
// Playwright's Electron support reaches both halves: `page` for the window and
// `electronApp.evaluate` for the main process, in the same test.
//
// Parallel across files, one Electron per worker. Every launch gets its own
// `LT_USER_DIR` — settings, workspace, logs and caches all live under it — so
// instances started at once have nothing to fight over. That was not always
// true, and `workers: 1` outlived the reason for it by long enough to make the
// suite four minutes of booting Electron.

import { defineConfig } from '@playwright/test';

export default defineConfig({
    testDir: './test-e2e',
    // Booting the editor and waiting for its object graph is seconds, not
    // milliseconds, and the ClojureScript compiler test loads an analysis
    // cache on top of that.
    timeout: 120_000,
    expect: { timeout: 30_000 },

    // Files run in parallel, tests within a file in order — which is what the
    // worker-scoped `app` fixture in test-e2e/fixtures.ts assumes.
    fullyParallel: false,
    workers: process.env.CI ? 2 : 4,

    // A test that only passes when retried is a flaky test, and hiding it
    // locally is how it reaches CI. Retry once there, where a cold runner is
    // its own source of noise, and never here.
    retries: process.env.CI ? 1 : 0,
    forbidOnly: !!process.env.CI,

    reporter: process.env.CI ? [['github'], ['list']] : [['list']],

    use: {
        // Kept for the failures worth reading: what the window looked like and
        // what it was doing when it stopped.
        trace: 'retain-on-failure',
        screenshot: 'only-on-failure'
    }
});
