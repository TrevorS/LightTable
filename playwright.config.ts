// Integration tests: the real application, one instance per test file.
//
// This is the layer script/smoke-test.js could not be. That harness boots one
// window and asserts eighty-odd things against it in sequence, which is a good
// answer to "is the assembled application wired together" and a bad one to
// everything else — a test cannot arrange its own state, a failure halfway
// through costs every check after it, and the main process is out of reach
// entirely. The bug that shipped a white second window lived exactly there.
//
// Playwright's Electron support reaches both halves: `page` for the window and
// `electronApp.evaluate` for the main process, in the same test.
//
// One worker on purpose. Light Table writes its settings and workspace under a
// single home directory, and instances started at once would fight over it.
// Isolation here is per file rather than per assertion.

import { defineConfig } from '@playwright/test';

export default defineConfig({
    testDir: './test-e2e',
    // Booting the editor and waiting for its object graph is seconds, not
    // milliseconds, and the ClojureScript compiler test loads an analysis
    // cache on top of that.
    timeout: 120_000,
    expect: { timeout: 30_000 },

    fullyParallel: false,
    workers: 1,

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
