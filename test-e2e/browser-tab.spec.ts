// The browser tab, whose whole point is a `<webview>` Light Table must not
// touch after it attaches.
//
// Covered by `script/smoke-test.mts` and nothing else, which was thin for the
// feature most likely to break on an Electron bump — and much too thin for a
// renderer swap. `src` is set once and deliberately never bound to `:url`: an
// Electron `<webview>` reads it when it attaches and then keeps it in step with
// whatever it has actually loaded, so assigning later leaves the tab on
// about:blank. Navigation goes through `loadURL`.
//
// Which makes one thing load-bearing: redrawing the chrome around it must not
// replace the element. That is `:replicant/key`, the same mechanism `lt.ui.pane`
// uses to host a CodeMirror, and it is what this file is mostly about.
//
// The devtools connection needs the remote debugging port, which the fixture
// turns off — so nothing here asserts on it.

import { test, expect, evalClj, evalData } from './fixtures';

const make = async (window: import('@playwright/test').Page) => {
    await evalClj(window, '(do (def browser-probe (lt.objs.browser/add)) :made)');
    await expect(window.locator('#browser')).toHaveCount(1);
};

const close = (window: import('@playwright/test').Page) =>
    evalClj(window, '(do (object/raise browser-probe :close) :closed)');

test('the tab draws a webview and the controls around it', async ({ window }) => {
    await make(window);

    await expect(window.locator('#browser webview')).toHaveCount(1);
    await expect(window.locator('#browser .frame-shade')).toHaveCount(1);
    expect(await window.locator('#browser nav button').allInnerTexts()).toEqual(['<', '>', '↺']);
    expect(await window.locator('#browser .url-bar').inputValue()).toBe('about:blank');

    // No preload attribute: the guest's preload is pinned by the main process,
    // which is the only side that should decide what runs inside an arbitrary
    // web page. See secureWebContents() in src-electron/main.ts.
    expect(await window.locator('#browser webview').getAttribute('preload')).toBe(null);

    await close(window);
});

test('and redrawing the chrome does not replace the webview', async ({ window }) => {
    await make(window);

    // The element itself, marked so it can be recognised after a render. A
    // replaced webview would be a fresh one without this.
    await evalClj(window, `
        (do (set! (.-ltProbe ^js (js/document.querySelector "#browser webview")) "original")
            :marked)`);

    // Something the view draws from, changed. The URL bar follows :url, so this
    // is a real render rather than a no-op.
    await evalClj(window, '(do (object/merge! browser-probe {:url "https://example.invalid/one"}) :navigated)');
    await expect.poll(async () => await window.locator('#browser .url-bar').inputValue())
        .toBe('https://example.invalid/one');

    expect(await evalData(window,
        '(.-ltProbe ^js (js/document.querySelector "#browser webview"))'),
    'the webview was torn down and rebuilt by a redraw').toBe('original');

    // And again, several times over, because a key that is wrong intermittently
    // is worse than one that is wrong every time.
    for (let i = 2; i < 6; i++) {
        await evalClj(window, `(do (object/merge! browser-probe {:url "https://example.invalid/${i}"}) :n)`);
    }
    await expect.poll(async () => await window.locator('#browser .url-bar').inputValue())
        .toBe('https://example.invalid/5');
    expect(await evalData(window,
        '(.-ltProbe ^js (js/document.querySelector "#browser webview"))')).toBe('original');
    expect(await window.locator('#browser webview').count()).toBe(1);

    await close(window);
});

test('and the nav buttons raise what the behaviors listen for', async ({ window }) => {
    await make(window);

    // Each button was a `defui` with a click handler; they are handlers in the
    // hiccup now. A button that stopped raising would look like a browser that
    // could not go back, which is indistinguishable from a page with no
    // history — so the triggers are counted rather than the effect.
    await evalClj(window, `
        (do (def raised (atom []))
            (object/add-behavior! browser-probe
                                  (object/behavior* :lt.probe/count-nav
                                                    :triggers #{:back! :forward! :refresh!}
                                                    :reaction (fn [_] (swap! raised conj :one))))
            :watching)`);

    for (const label of ['<', '>', '↺']) {
        await window.locator('#browser nav button', { hasText: label }).click();
    }
    await expect.poll(async () => await evalData(window, '(count @raised)')).toBe(3);

    await close(window);
});
