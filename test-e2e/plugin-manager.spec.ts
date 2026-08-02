// The plugin manager, which draws two lists and had no coverage for either.
//
// It was twelve `defui` and two behaviors that emptied a `ul` and appended a
// document fragment into it — plus an install handler that reached for its own
// row with `this-as` and removed it from the DOM, because nothing else was
// going to make it go away.
//
// The server metadata is fetched from the network, so these drive the lists
// through the object: `:plugin-results` is what a fetch raises, and `:refresh!`
// is what rereads the disk.

import { test, expect, evalClj, evalData } from './fixtures';

const open = async (window: import('@playwright/test').Page) => {
    await evalClj(window, '(do (tabs/add-or-focus! lt.objs.plugins/manager) :shown)');
    await expect(window.locator('.plugin-manager')).toHaveCount(1);
};

test('the installed list is what is on disk, sorted', async ({ window }) => {
    await open(window);
    await evalClj(window, '(do (object/raise lt.objs.plugins/manager :refresh! :ignore-missing true) :refreshed)');

    // The plugins that ship. Read off the screen and compared with what the
    // object says is installed, so the assertion cannot pass by drawing
    // something else.
    const shown = window.locator('.plugin-manager ul.plugins > li h1 .link');
    await expect.poll(async () => await shown.count()).toBeGreaterThan(5);

    const drawn = await shown.allInnerTexts();
    const known = await evalData<string[]>(window,
        '(vec (sort (map #(.toUpperCase (str %)) (keys (:installed @lt.objs.plugins/manager)))))');
    expect(drawn.map((s) => s.toUpperCase())).toEqual(known);
    expect(drawn).toContain('Clojure');

    // Every row carries its version and a way to remove it.
    expect(await window.locator('.plugin-manager ul.plugins > li .version').first().innerText())
        .toMatch(/\d/);
    await expect(window.locator('.plugin-manager ul.plugins > li .uninstall').first()).toHaveCount(1);
});

test('the available list drops what is already installed', async ({ window }) => {
    await open(window);
    await evalClj(window, '(do (object/raise lt.objs.plugins/manager :refresh! :ignore-missing true) :refreshed)');

    // What a metadata fetch raises. One of these is installed and one is not.
    await evalClj(window, `
        (do (object/raise lt.objs.plugins/manager :plugin-results
                          [{:name "Clojure" :version "0.0.1" :author "somebody" :desc "already here"
                            :source "https://example.invalid/clojure"}
                           {:name "Fictional" :version "1.2.3" :author "nobody" :desc "not here"
                            :source "https://example.invalid/fictional"}])
            :results)`);

    const available = window.locator('.plugin-manager ul.server-plugins > li');
    await expect.poll(async () => await available.count()).toBe(1);
    expect(await available.first().locator('h1 .link').innerText()).toBe('Fictional');

    // Not installed, so it offers to install rather than showing a tick. That
    // decision used to be read out of `@app/app` inside the row; it is the
    // manager's own copy of what is installed now.
    await expect(available.first().locator('.install')).toHaveCount(1);

    // The raw answer is kept whole — the filtering is the view's, which is what
    // lets installing one take it out of the list without the list being sent
    // again.
    expect(await evalData(window, '(count (:server-results @lt.objs.plugins/manager))')).toBe(2);
});

test('and an installed plugin with a newer version offers the update', async ({ window }) => {
    await open(window);
    await evalClj(window, '(do (object/raise lt.objs.plugins/manager :refresh! :ignore-missing true) :refreshed)');

    // `:server-plugins` is the metadata cache, keyed by keyword, and the
    // installed row reads the latest version out of it.
    await evalClj(window, `
        (do (object/merge! lt.objs.plugins/manager
                           {:server-plugins {:Clojure {:latest-version "9999.0.0"}}})
            :cached)`);

    const row = window.locator('.plugin-manager ul.plugins > li', { hasText: 'Clojure' }).first();
    await expect.poll(async () => await row.getAttribute('class')).toContain('has-update');
    await expect(row.locator('.update')).toHaveCount(1);

    await evalClj(window, '(do (object/merge! lt.objs.plugins/manager {:server-plugins {}}) :uncached)');
    await expect.poll(async () => await row.getAttribute('class')).not.toContain('has-update');
});

test('the tabs are a value, and switching them is the root class changing', async ({ window }) => {
    await open(window);

    // `structure.css` shows one list or the other off `.plugin-manager.server`,
    // so which tab you are on is a class on the element the object hands out —
    // the one thing a view cannot reach, and what `lt.ui/node`'s `attrs` is for.
    const root = window.locator('.plugin-manager');
    await evalClj(window, '(do (object/merge! lt.objs.plugins/manager {:tab :installed}) :installed)');
    await expect.poll(async () => await root.getAttribute('class')).toBe('plugin-manager');

    await window.locator('.plugin-manager .tabs button', { hasText: 'Available' }).click();
    await expect.poll(async () => await root.getAttribute('class')).toBe('plugin-manager server');
    expect(await evalData(window, '(name (:tab @lt.objs.plugins/manager))')).toBe('server');

    // And the button says which one you are on.
    expect(await window.locator('.plugin-manager .tabs button.active').innerText()).toBe('Available');

    await window.locator('.plugin-manager .tabs button', { hasText: 'Installed' }).click();
    await expect.poll(async () => await root.getAttribute('class')).toBe('plugin-manager');
});
