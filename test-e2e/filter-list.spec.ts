// The list you narrow by typing, in the window.
//
// One widget with four instances — the command bar, the file navigator, the
// syntax selector and the auto-complete hinter — and until now none of them had
// a test at any layer. It was a fixed pool of `<li>` nodes repainted with
// `innerHTML` on every keystroke, which is not a shape anything can ask a
// question about.
//
// `test/lt/ui/filter_test.cljs` pins the part with no atom in it: how a match
// becomes hiccup, how the selection wraps, what an empty list says. This is
// the part only a window can answer — that typing narrows, that the keyboard
// moves the selection, and that choosing a row runs the thing.

import { test, expect, evalClj, scratchDir } from './fixtures';
import type { Page } from '@playwright/test';
import * as fs from 'node:fs';
import * as path from 'node:path';

/** The command bar's own list — three others live in the right bar too. */
const BAR = '#right-bar .command .filter-list';

const rows = (window: Page) =>
    window.locator(`${BAR} .filter-list__results .row`).allInnerTexts();

async function openBar(window: Page): Promise<void> {
    await evalClj(window, '(do (lt.objs.command/exec! :show-commandbar) :shown)');
    await window.locator(`${BAR} .search`).waitFor();
}

test('typing narrows the list, and the match is marked', async ({ window }) => {
    await openBar(window);
    const all = (await rows(window)).length;
    expect(all).toBeGreaterThan(20);

    await window.locator(`${BAR} .search`).fill('tabset');
    await expect.poll(async () => (await rows(window)).length).toBeLessThan(all);
    expect((await rows(window)).join(' ')).toContain('Tabset');

    // Marked as elements rather than as a string of HTML. The old highlight was
    // `<em>` concatenated into the command's own description and set with
    // innerHTML — see doc/hygiene.md.
    await expect(window.locator(`${BAR} .filter-list__results em`).first()).toBeVisible();
});

test('and a name that looks like markup is a name', async ({ window }) => {
    // The reason the highlight is hiccup. A file is a thing a person can name,
    // and this one is named after the injection it would have been.
    const dir = scratchDir('filter-html');
    const name = '<img onerror=alert(1)>.txt';
    fs.writeFileSync(path.join(dir, name), 'x\n');

    await evalClj(window, `
        (do (object/raise lt.objs.workspace/current-ws :add.folder! "${dir}") :added)`);
    await evalClj(window, '(do (lt.objs.command/exec! :navigate-workspace) :shown)');

    const nav = '#right-bar .navigate .filter-list';
    await window.locator(`${nav} .search`).fill('onerror');
    const row = window.locator(`${nav} .filter-list__results .row`).first();
    await expect(row).toContainText('onerror');

    // The whole point: no element came out of the file's name.
    await expect(row.locator('img')).toHaveCount(0);
    expect(await row.innerHTML()).not.toContain('<img');

    await evalClj(window, `
        (do (object/raise lt.objs.workspace/current-ws :remove.folder! "${dir}") :removed)`);
    fs.rmSync(dir, { recursive: true, force: true });
});

test('the keyboard moves the selection and the mouse chooses a row', async ({ window }) => {
    await openBar(window);
    await window.locator(`${BAR} .search`).fill('tabset');
    await expect.poll(async () => (await rows(window)).length).toBeGreaterThan(1);

    const selected = () =>
        window.locator(`${BAR} .filter-list__results .row--selected`).innerText();
    const first = await selected();

    await evalClj(window, '(do (lt.objs.command/exec! :filter-list.input.move-selection 1) :down)');
    await expect.poll(selected).not.toBe(first);
    await evalClj(window, '(do (lt.objs.command/exec! :filter-list.input.move-selection -1) :up)');
    await expect.poll(selected).toBe(first);

    // Exactly one row is selected — it was two DOM classes moved between two
    // nodes of a pool, and now it is one number.
    await expect(window.locator(`${BAR} .filter-list__results .row--selected`)).toHaveCount(1);

    // Choosing runs the command. `:tabset.new` adds a tabset, which is a fact
    // about the window rather than about the list.
    const before = Number(await evalClj(window, '(count (:tabsets @lt.objs.tabs/multi))'));
    await window.locator(`${BAR} .filter-list__results .row`, { hasText: 'Add a tabset' })
        .first().click();
    await expect.poll(async () =>
        Number(await evalClj(window, '(count (:tabsets @lt.objs.tabs/multi))'))).toBe(before + 1);
    await evalClj(window, '(do (lt.objs.command/exec! :tabset.close) :closed)');
});

test('a query that matches nothing says so rather than showing an empty box', async ({ window }) => {
    await openBar(window);
    await window.locator(`${BAR} .search`).fill('zzzzznotacommand');
    await expect.poll(async () => (await rows(window)).length).toBe(0);
    await expect(window.locator(`${BAR}.filter-list--empty`)).toHaveCount(1);
    // And says which nothing it is. The sentence was `content:` on a `:before`
    // rule, so it was the stylesheet's opinion rather than the panel's.
    await expect(window.locator(`${BAR} .empty__what`)).toHaveText('No command matches');
});
