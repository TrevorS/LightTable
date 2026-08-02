// The devtools object inspector, which is objects hosting objects.
//
// Reachable only with a browser tab connected to Chromium's remote debugging
// protocol, so it had no coverage of any kind — and it is the most recursive
// thing in the editor: every expandable property is another inspector object
// whose DOM is placed inside its parent's.
//
// Driven through the object with a client that is not connected. `send` queues
// when the socket is down rather than throwing, so opening one asks a question
// nobody answers, and the answer can be supplied by hand — which is exactly
// what the protocol would have delivered.

import { test, expect, evalClj, evalData } from './fixtures';

/** An inspector for a fake object, mounted where a test can see it. */
async function inspect(window: import('@playwright/test').Page, info: string) {
    await evalClj(window, `
        (do (when (exists? js/probeInspector)
              (.remove (object/->content probe-inspector))
              (object/destroy! probe-inspector))
            (def probe-client
              (object/create :lt.objs.clients.devtools/devtools-client "about:blank"))
            (def probe-inspector
              (object/create :lt.objs.clients.devtools/inspector-object probe-client ${info}))
            (set! js/probeInspector true)
            (def probe-host (js/document.createElement "div"))
            (set! (.-className probe-host) "probe-inspector-host")
            ;; Over the chrome, so a real click reaches it.
            (set! (.-style.cssText probe-host)
                  "position:fixed;top:0;left:0;z-index:99999;background:#222")
            (js/document.body.appendChild probe-host)
            (.appendChild probe-host (object/->content probe-inspector))
            :mounted)`);
    await expect(window.locator('.probe-inspector-host .inspector-object')).toHaveCount(1);
}

const clean = (window: import('@playwright/test').Page) =>
    evalClj(window, '(do (.remove probe-host) :gone)');

test('an inspector names the value and opens on a click', async ({ window }) => {
    await inspect(window, '{:value {:type "object" :description "Object" :objectId "1"}}');

    const root = window.locator('.probe-inspector-host .inspector-object');
    expect(await root.getAttribute('class')).toBe('inspector-object');
    expect(await root.locator('> h2').innerText()).toContain('Object');

    // Open is a class on the element the object hands out, which is what
    // `structure.css` shows the property list off — and the one thing a view
    // cannot reach, so it goes through `lt.ui/node`'s `attrs`.
    await root.locator('> h2').click();
    await expect.poll(async () => await root.getAttribute('class')).toBe('inspector-object open');
    expect(await evalData(window, '(boolean (:open @probe-inspector))')).toBe(true);

    await root.locator('> h2').click();
    await expect.poll(async () => await root.getAttribute('class')).toBe('inspector-object');

    await clean(window);
});

test('its properties are values, and an object among them is another inspector', async ({ window }) => {
    await inspect(window, '{:value {:type "object" :description "Object" :objectId "1"}}');

    // The reply the protocol would have sent. One plain value and one object.
    await evalClj(window, `
        (do (object/raise probe-inspector :open)
            (lt.objs.clients.devtools/receive-children!
             probe-inspector
             [{:name "count" :value {:type "number" :value 42 :description "42"}}
              {:name "nested" :value {:type "object" :description "Object" :objectId "2"}}])
            :children)`);

    const rows = window.locator('.probe-inspector-host .inspector-object > div > ul > li');
    await expect.poll(async () => await rows.count()).toBe(2);

    // Sorted by name, so `count` comes before `nested`.
    expect(await rows.first().innerText()).toContain('count');
    expect(await rows.first().innerText()).toContain('42');

    // And the object one is a whole inspector of its own, hosted inside the row.
    await expect(rows.nth(1).locator('.inspector-object')).toHaveCount(1);
    expect(await evalData(window, `
        (identical? (.-firstChild ^js (.querySelector (object/->content probe-inspector)
                                                     "li:nth-child(2) span"))
                    (object/->content (get (:child-objects @probe-inspector) "nested")))`))
        .toBe(true);

    await clean(window);
});

test('and redrawing does not build the children again', async ({ window }) => {
    // The trap in converting this. `props` called `object/create` while drawing
    // and got away with it because `bound` re-ran it only when the children
    // arrived. A view re-runs whenever anything about the object changes —
    // opening it, for one — so drawing would have built a fresh inspector every
    // time, each losing whatever the last had been expanded to.
    await inspect(window, '{:value {:type "object" :description "Object" :objectId "1"}}');
    await evalClj(window, `
        (do (lt.objs.clients.devtools/receive-children!
             probe-inspector
             [{:name "nested" :value {:type "object" :description "Object" :objectId "2"}}])
            :children)`);

    const before = await evalData(window, '(count (object/by-tag :inspector.object))');
    await evalData(window, `
        (do (object/merge! (get (:child-objects @probe-inspector) "nested") {:open true}) true)`);

    // Six redraws of the parent, from opening and closing it.
    for (let i = 0; i < 3; i++) {
        await evalClj(window, '(do (object/merge! probe-inspector {:open true}) :o)');
        await evalClj(window, '(do (object/merge! probe-inspector {:open false}) :c)');
    }

    expect(await evalData(window, '(count (object/by-tag :inspector.object))'),
           'redrawing should not have created more inspectors').toBe(before);
    // And the child kept what it was expanded to.
    expect(await evalData(window,
        '(:open @(get (:child-objects @probe-inspector) "nested"))')).toBe(true);

    await clean(window);
});
