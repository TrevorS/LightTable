// The docs sidebar: search a language's documentation from the right bar.
//
// It had no coverage at any layer, which is how it came to be converted with
// three behaviors reaching into its DOM — `dom/empty` on the results, a
// `dom/replace-with` on the type list, and a `.no-client` div inserted before
// one of them and removed by another. Every one of those is state now, and
// none of it was observable from a test.
//
// Driven through the object rather than the Clojure plugin's real doc search,
// because what is under test is the panel: which types it offers, what it does
// with results, and the order it puts them in.

import { test, expect, evalClj, evalData } from './fixtures';

/** The panel, created and put in the right bar the way `::init-doc-search` does. */
async function open(window: import('@playwright/test').Page) {
    await evalClj(window, `
        (do (when-not lt.plugins.doc/doc-search
              (set! lt.plugins.doc/doc-search
                    (object/create :lt.plugins.doc/sidebar.doc.search))
              (lt.objs.sidebar/add-item lt.objs.sidebar/rightbar lt.plugins.doc/doc-search))
            (object/raise lt.objs.sidebar/rightbar :toggle lt.plugins.doc/doc-search {:force? true})
            :shown)`);
    await expect(window.locator('.docs-search')).toHaveCount(1);
}

const results = (window: import('@playwright/test').Page) =>
    window.locator('.docs-search .results > li');

test('the types come from the behaviors, and picking one redraws the list', async ({ window }) => {
    await open(window);

    // `:types+` is a reduction, so the Clojure plugin's two entries are here
    // because that plugin loaded — not because this panel knows about Clojure.
    const labels = window.locator('.docs-search .types ul.types li');
    await expect.poll(async () => await labels.allInnerTexts())
        .toEqual(expect.arrayContaining(['clj', 'cljs']));

    // The current one is named beside the list, and it used to be redrawn by
    // replacing the `.types` node from a behavior.
    expect(await window.textContent('.docs-search div.types > span')).toBe('clj');

    // Hovered first: the list is a dropdown that `structure.css` keeps at
    // `display:none` until `.types:hover`, so this is also how you reach it.
    await window.locator('.docs-search div.types').hover();
    await labels.filter({ hasText: 'cljs' }).first().click();
    await expect.poll(async () => await window.textContent('.docs-search div.types > span')).toBe('cljs');
    expect(await evalData(window, '(:label (:cur @lt.plugins.doc/doc-search))')).toBe('cljs');
});

test('results are listed, exact matches first, however many replies it took', async ({ window }) => {
    await open(window);
    await evalClj(window, '(do (object/raise lt.plugins.doc/doc-search :clear!) :cleared)');

    // The query is kept on the object now — it used to be read back out of the
    // input every time a reply arrived.
    await evalClj(window, `
        (do (object/merge! lt.plugins.doc/doc-search {:query "map"})
            (object/raise lt.plugins.doc/doc-search :doc.search.results
                          [{:name "reduce" :ns "clojure.core" :args "[f coll]" :doc "folds"}
                           {:name "map" :ns "clojure.core" :args "[f coll]" :doc "maps"}])
            :first-reply)`);
    await expect.poll(async () => await results(window).count()).toBe(2);

    // A second reply, with its own exact match. Both fragments used to be
    // spliced per batch — one prepended, one appended — so an exact match in a
    // later reply landed below the earlier batch's non-matches.
    await evalClj(window, `
        (do (object/raise lt.plugins.doc/doc-search :doc.search.results
                          [{:name "filter" :ns "clojure.core" :args "[p coll]" :doc "keeps"}
                           {:name "mapcat" :ns "clojure.core" :args "[f coll]" :doc "maps and cats"}])
            :second-reply)`);
    await expect.poll(async () => await results(window).count()).toBe(4);

    expect(await window.locator('.docs-search .results h2').allInnerTexts())
        .toEqual(['map', 'mapcat', 'reduce', 'filter']);

    // The same result twice is one row: several clients answer one search, and
    // the dedup a set was doing is explicit now.
    await evalClj(window, `
        (do (object/raise lt.plugins.doc/doc-search :doc.search.results
                          [{:name "map" :ns "clojure.core" :args "[f coll]" :doc "maps"}])
            :again)`);
    await expect.poll(async () => await results(window).count()).toBe(4);

    // And clearing is the vector being emptied rather than the `ul`.
    await evalClj(window, '(do (object/raise lt.plugins.doc/doc-search :clear!) :cleared)');
    await expect.poll(async () => await results(window).count()).toBe(0);
});

test('with nothing connected it says so, and stops saying so', async ({ window }) => {
    await open(window);

    // This was a div inserted before the results by one behavior and removed by
    // a function in another — so whether it was showing lived in the DOM, and
    // the only way to ask was to query for it.
    const notice = window.locator('.docs-search .no-client');
    await evalClj(window, '(do (object/merge! lt.plugins.doc/doc-search {:no-client? false}) :hidden)');
    await expect(notice).toHaveCount(0);

    await evalClj(window, '(do (object/raise lt.plugins.doc/doc-search :no-client) :none)');
    await expect(notice).toHaveCount(1);
    expect(await notice.locator('button').innerText()).toBe('Connect');

    // Raising it twice is one notice, which the DOM version had to check for.
    await evalClj(window, '(do (object/raise lt.plugins.doc/doc-search :no-client) :again)');
    await expect(notice).toHaveCount(1);

    await evalClj(window, '(do (object/merge! lt.plugins.doc/doc-search {:no-client? false}) :hidden)');
    await expect(notice).toHaveCount(0);
});
