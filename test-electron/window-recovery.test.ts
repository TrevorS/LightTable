// What Light Table does about a window that stops being a window.
//
// The behaviour this covers used not to exist, and its absence had a very
// specific look: the renderer process goes away, the window stays exactly where
// it was — a white rectangle, the right size, with a title bar — and nothing is
// logged and nothing is said. Indistinguishable from a hang, so the reasonable
// thing to do is force-quit, and then it looks like Light Table crashes on
// startup.
//
// `recoveryFor` is the decision, separated from the event handlers in main.ts
// because the cases are the whole point and are awkward to reach by hand: a
// renderer has to die twice, without loading in between, on a build that is not
// headless. Every one of them is a line here instead.
//
// The wiring is asserted too, against the compiled main.js. Three of these
// answers only mean anything if something is listening for the event that asks
// the question, and a handler that was never attached is exactly the bug that
// left `devtools-opened` firing on the wrong object for years.

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import { recoveryFor } from '../src-electron/config.ts';

describe('what to do about a window that failed', () => {
    test('a crash reloads, because asking preserves nothing', () => {
        // The renderer holding the unsaved work is already gone, and the open
        // files come back from the session. A dialog here is just a step between
        // the user and a working editor.
        assert.equal(recoveryFor('gone', 1, false), 'reload');
        assert.equal(recoveryFor('load', 1, false), 'reload');
    });

    test('and it reloads headless too, because reloading is not a dialog', () => {
        // It is the *asking* a test run cannot do. Recovery still happens, which
        // is also what makes this path reachable from a test at all.
        assert.equal(recoveryFor('gone', 1, true), 'reload');
    });

    test('the second failure stops, rather than reloading for ever', () => {
        // A renderer that dies while starting would otherwise crash, reload and
        // crash again without end — worse than a blank window, because it never
        // settles and burns a core doing it.
        assert.equal(recoveryFor('gone', 2, false), 'ask');
        assert.equal(recoveryFor('load', 2, false), 'ask');
        assert.equal(recoveryFor('gone', 9, false), 'ask');
    });

    test('unresponsive never reloads, however many times it happens', () => {
        // That renderer is alive and may come back, so reloading it throws away
        // work that is still there. VS Code offers Keep Waiting for this reason.
        assert.equal(recoveryFor('unresponsive', 1, false), 'ask');
        assert.equal(recoveryFor('unresponsive', 2, false), 'ask');
    });

    test('headless disposes instead of asking, at every point where it would ask', () => {
        // A modal in a test run is a hang, not a failure, and nobody is there to
        // click it.
        assert.equal(recoveryFor('unresponsive', 1, true), 'destroy');
        assert.equal(recoveryFor('gone', 2, true), 'destroy');
        assert.equal(recoveryFor('load', 2, true), 'destroy');
    });
});

describe('the events that ask the question', () => {
    // Read from the build output rather than the source, because what matters is
    // what runs. `npm run build:main` produces this, and `npm test` runs after it
    // in CI.
    const main = fs.readFileSync('deploy/core/main.js', 'utf8');

    test('a dead renderer is listened for', () => {
        assert.match(main, /webContents\.on\("render-process-gone"/);
    });

    test('so is a load that failed', () => {
        assert.match(main, /webContents\.on\("did-fail-load"/);
    });

    test('and a renderer that has wedged', () => {
        assert.match(main, /\.on\("unresponsive"/);
    });

    test('the dialog is not parented to the window that failed', () => {
        // A message box attached to a BrowserWindow is a sheet, and a sheet on a
        // window whose renderer is gone does not draw — which made the first
        // version of this appear to do nothing at all. `showMessageBox` must be
        // called with options alone.
        assert.match(main, /dialog\.showMessageBox\(\{/,
                     'showMessageBox must not be passed a parent window');
    });

    test('recovery destroys rather than closes', () => {
        // `close` is intercepted and handed to the renderer, so on a window with
        // no renderer it does nothing — which is how a blank window becomes one
        // that cannot even be dismissed.
        assert.match(main, /window\.destroy\(\)/);
    });

    test('close does not cancel itself when there is no renderer to ask', () => {
        // `preventDefault` was unconditional. With the renderer gone nobody calls
        // back, so the window could not be closed by the button, by the shortcut,
        // or by quitting.
        assert.match(main, /isDestroyed\(\)\)\s*return;\s*sendTo\(window, "app", "close!"\)/,
                     'the close handler must bail out before preventDefault when the renderer is gone');
    });

    test('nothing sends to a renderer without going through sendTo', () => {
        // A bare send to a dead renderer throws, from handlers that have no
        // business failing — blurring a window should not be able to raise.
        const bare = main.split('\n')
            .map((line, i) => [i + 1, line] as const)
            .filter(([, line]) => /webContents\.send\(/.test(line))
            // sendTo itself is the one place allowed to call it.
            .filter(([, line]) => !/^\s*window\.webContents\.send\(channel, \.\.\.args\)/.test(line));
        assert.deepEqual(bare, [], `bare webContents.send at ${bare.map(([n]) => n).join(', ')}`);
    });

    test('a window is not shown before it has painted', () => {
        // An Electron window with nothing drawn in it is white, and it is shown
        // as soon as it exists — so every launch flashed a white rectangle the
        // size of the editor before the dark skin arrived.
        assert.match(main, /\{ show: false \}/);
        assert.match(main, /once\("ready-to-show"/);
    });

    test('and it is shown even if it never paints', () => {
        // `ready-to-show` is tied to the first paint, so anything that stops the
        // renderer painting would leave the window hidden for ever. An
        // application that never appears is worse than one that appears
        // unpainted.
        assert.match(main, /setTimeout\(reveal/);
    });
});

describe('the colour of a window with nothing in it', () => {
    test('is set on the window, so the gap before first paint is not white', () => {
        const pkg = JSON.parse(fs.readFileSync('deploy/core/package.json', 'utf8')) as
            { browserWindowOptions?: { backgroundColor?: string } };
        assert.ok(pkg.browserWindowOptions?.backgroundColor,
                  'browserWindowOptions needs a backgroundColor, or an unpainted window is white');
    });

    test('and the reset stylesheet no longer paints the body white over it', () => {
        // The skin is injected by a behavior, so it arrives after the reset has
        // applied. Between the two the body was white, full size, under a dark
        // editor.
        const reset = fs.readFileSync('deploy/core/css/reset.css', 'utf8');
        assert.doesNotMatch(reset, /background:\s*white/,
                            'reset.css must not paint the body white before the skin arrives');
    });

    test('the window colour matches what the skin paints, so the change is invisible', () => {
        const pkg = JSON.parse(fs.readFileSync('deploy/core/package.json', 'utf8')) as
            { browserWindowOptions: { backgroundColor: string } };
        const skin = fs.readFileSync('deploy/core/css/skins/new-dark.css', 'utf8');
        const body = /body\s*\{[^}]*background:\s*(#[0-9a-fA-F]{3,6})/.exec(skin);
        assert.ok(body, 'the dark skin should set a body background');
        assert.equal(pkg.browserWindowOptions.backgroundColor.toLowerCase(), body[1]!.toLowerCase());
    });
});
