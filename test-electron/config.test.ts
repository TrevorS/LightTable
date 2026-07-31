// Unit tests for the main process's decisions.
//
// Run by `npm run test:electron`, under plain node with no Electron and no
// window — which is the point. Everything here used to be inline in main.ts,
// where testing it meant booting an application, so it was tested by hand
// until it wasn't.
//
// Node runs these .ts files directly by stripping the types, so there is no
// build step between the source and the test.

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';

import {
    windowOptions,
    resolveDebugPort,
    headless,
    DEFAULT_DEBUG_PORT,
    type WindowOptionDefaults
} from '../src-electron/config.ts';

/** What deploy/core/package.json actually holds, trimmed to what matters here. */
function defaults(): WindowOptionDefaults {
    return {
        icon: 'img/lticon.png',
        width: 1024,
        height: 700,
        webPreferences: {
            nodeIntegration: false,
            contextIsolation: true,
            preload: 'preload.js'
        }
    };
}

describe('windowOptions', function() {
    test('resolves the preload and the icon against the app directory', function() {
        const opts = windowOptions(defaults(), '/app');
        assert.equal((opts.webPreferences as Record<string, unknown>).preload, '/app/preload.js');
        assert.equal(opts.icon, '/app/img/lticon.png');
    });

    test('carries the other options through untouched', function() {
        const opts = windowOptions(defaults(), '/app');
        assert.equal(opts.width, 1024);
        assert.equal(opts.height, 700);
        const prefs = opts.webPreferences as Record<string, unknown>;
        assert.equal(prefs.contextIsolation, true);
        assert.equal(prefs.nodeIntegration, false);
    });

    // The regression. A shipped build opened one working window and then
    // white ones, because __dirname was prepended to the object require()
    // caches: the first window got /app/preload.js and the second asked for
    // /app//app/preload.js, loaded no preload, and threw on the first thing
    // that reached the bridge.
    test('does not modify the defaults it was given', function() {
        const shared = defaults();
        windowOptions(shared, '/app');
        assert.equal(shared.webPreferences!.preload, 'preload.js');
        assert.equal(shared.icon, 'img/lticon.png');
    });

    test('so every window gets the same paths, however many are opened', function() {
        const shared = defaults();
        const paths = [1, 2, 3].map(function() {
            const opts = windowOptions(shared, '/app');
            return (opts.webPreferences as Record<string, unknown>).preload;
        });
        assert.deepEqual(paths, ['/app/preload.js', '/app/preload.js', '/app/preload.js']);
    });

    test('and the objects handed to Electron do not alias each other', function() {
        const shared = defaults();
        const first = windowOptions(shared, '/app');
        const second = windowOptions(shared, '/app');
        assert.notEqual(first, second);
        assert.notEqual(first.webPreferences, second.webPreferences);
        assert.notEqual(first.webPreferences, shared.webPreferences);
    });

    test('merges extra options last, so a caller can override size', function() {
        const opts = windowOptions(defaults(), '/app', { show: false, width: 1280 });
        assert.equal(opts.show, false);
        assert.equal(opts.width, 1280);
        assert.equal(opts.height, 700);
    });

    test('tolerates options with no icon and no webPreferences', function() {
        const opts = windowOptions({ width: 800 }, '/app');
        assert.deepEqual(opts, { width: 800 });
        assert.equal('icon' in opts, false);
        assert.equal('webPreferences' in opts, false);
    });

    test('keeps webPreferences that name no preload', function() {
        const opts = windowOptions({ webPreferences: { sandbox: true } }, '/app');
        assert.deepEqual(opts.webPreferences, { sandbox: true });
    });
});

describe('resolveDebugPort', function() {
    test('defaults to the port the browser tab expects', function() {
        assert.deepEqual(resolveDebugPort({}), { port: DEFAULT_DEBUG_PORT, reason: 'default' });
    });

    test('treats an empty value as unset', function() {
        assert.equal(resolveDebugPort({ LT_REMOTE_DEBUGGING_PORT: '' }).port, DEFAULT_DEBUG_PORT);
    });

    test('takes a port when given one', function() {
        assert.deepEqual(resolveDebugPort({ LT_REMOTE_DEBUGGING_PORT: '9222' }),
                         { port: 9222, reason: 'configured' });
    });

    // Playwright's Electron launcher appends its own --remote-debugging-port
    // and reads back the port Chromium chose. With ours also appended the two
    // disagree and the launch hangs until the harness times out, which is how
    // this option came to exist.
    test('can be turned off so a launcher can choose', function() {
        for (const value of ['off', 'none', 'false', '0', 'OFF', ' off ']) {
            assert.deepEqual(resolveDebugPort({ LT_REMOTE_DEBUGGING_PORT: value }),
                             { port: null, reason: 'disabled' }, value);
        }
    });

    // Reported rather than silently swapped for the default: a typo that
    // quietly opens the port you were moving away from is worse than a line
    // in the log.
    test('reports a value it cannot use', function() {
        for (const value of ['no', '99999', '-1', '80.5', 'eight']) {
            assert.equal(resolveDebugPort({ LT_REMOTE_DEBUGGING_PORT: value }).reason, 'invalid', value);
        }
    });

    test('and falls back to the default when it cannot use one', function() {
        assert.equal(resolveDebugPort({ LT_REMOTE_DEBUGGING_PORT: 'eight' }).port, DEFAULT_DEBUG_PORT);
    });
});

describe('headless', function() {
    test('shows windows when nothing says otherwise', function() {
        assert.equal(headless({}), false);
    });

    test('hides them when a harness asks', function() {
        for (const value of ['1', 'true', 'yes', 'on', 'anything']) {
            assert.equal(headless({ LT_HEADLESS: value }), true, value);
        }
    });

    test('treats the ways of saying no as no', function() {
        for (const value of ['', '0', 'false', 'off', 'no', ' OFF ']) {
            assert.equal(headless({ LT_HEADLESS: value }), false, value);
        }
    });

    // So that one variable turns a debugging run visible without editing the
    // script that set the other one.
    test('and LT_HEADED wins, whatever LT_HEADLESS says', function() {
        assert.equal(headless({ LT_HEADLESS: '1', LT_HEADED: '1' }), false);
        assert.equal(headless({ LT_HEADLESS: '1', LT_HEADED: '0' }), true);
    });
});
