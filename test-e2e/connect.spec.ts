// The connect panel, and the one thing you can do to a row in it.
//
// doc/hygiene.md listed this as an open correctness item for a reason worth
// keeping: `:bound?` was read truthfully from the editor, so the panel *showed*
// the right thing, and only the click lied — the effect wrote a `::client` key
// that nothing ever read. A panel that reports correctly and acts on nothing is
// the hardest kind of broken to notice, because looking at it tells you nothing
// is wrong.
//
// So every assertion here reads the editor's own `:client` map rather than the
// panel. What the panel draws is `view/connections`' business and is covered by
// view tests with a literal map; what the *click* does is only answerable here.

import { test, expect, evalClj, evalData, openFile, scratchDir,
         connectLocalClient } from './fixtures';
import * as fs from 'node:fs';
import * as path from 'node:path';

/** The keys of the active editor's client map, and the id each is bound to.
 *
 * By id rather than by name: a client's `:name` is whatever made the connection
 * put there — a URL, a keyword, and in one case a JavaScript object, which is why
 * `lt.state.objects/clients*` coerces it with `str` before it reaches a view. An
 * id is a number and means one thing. */
async function bindings(window: import('@playwright/test').Page):
    Promise<Record<string, number>> {
    return await evalData(window, `
        (into {} (for [[k c] (:client @(lt.objs.editor.pool/last-active))]
                   [(str k) (lt.objs.clients/->id c)]))`);
}

test.describe('the connect panel', () => {
    let file: string;

    test.beforeEach(async ({ window }) => {
        const dir = scratchDir('connect');
        file = path.join(dir, 'probe.cljs');
        fs.writeFileSync(file, '(+ 1 1)\n');
        await openFile(window, file);
        await connectLocalClient(window);
    });

    test('clicking a connection makes it what the buffer evaluates through', async ({ window }) => {
        // Nothing bound to start with: a buffer that has never been evaluated
        // has no client, which is why `get-client!` exists.
        expect(await bindings(window)).toEqual({});

        const id = await evalClj(window,
            '(lt.objs.clients/->id (lt.objs.clients/by-name "LightTable-UI"))');
        await evalClj(window, `(do (lt.actions/dispatch! [[:client/bind ${id}]]) :bound)`);

        // Under :default, which is the key ten of the eleven get-client! call
        // sites use and therefore what "where does an evaluation go" means.
        expect(await bindings(window)).toEqual({ ':default': Number(id) });
    });

    test('and the panel then says so, because :bound? is read from the editor', async ({ window }) => {
        const id = await evalClj(window,
            '(lt.objs.clients/->id (lt.objs.clients/by-name "LightTable-UI"))');
        await evalClj(window, `(do (lt.actions/dispatch! [[:client/bind ${id}]]) :bound)`);

        // The projection, not the click's optimistic state update — this is the
        // half that was always honest and is what makes the other half testable.
        expect(await evalData(window, `
            (do (lt.state.objects/sync!)
                (boolean (:bound? (get (:clients @lt.state/app) ${id}))))`)).toBe(true);
    });

    test('unset is the inverse, and takes it off every key it was on', async ({ window }) => {
        const id = await evalClj(window,
            '(lt.objs.clients/->id (lt.objs.clients/by-name "LightTable-UI"))');
        await evalClj(window, `(do (lt.actions/dispatch! [[:client/bind ${id}]]) :bound)`);
        expect(await bindings(window)).toEqual({ ':default': Number(id) });

        await evalClj(window, `(do (lt.actions/dispatch! [[:client/unset ${id}]]) :unset)`);
        expect(await bindings(window)).toEqual({});
    });

    test('binding is idempotent rather than a toggle', async ({ window }) => {
        // Clicking the row you are already evaluating through should leave you
        // evaluating through it. Unbinding is `:client/unset`, in the menu,
        // because the kit's rule is that a row is not a control.
        const id = await evalClj(window,
            '(lt.objs.clients/->id (lt.objs.clients/by-name "LightTable-UI"))');
        for (let i = 0; i < 3; i++) {
            await evalClj(window, `(do (lt.actions/dispatch! [[:client/bind ${id}]]) :bound)`);
        }
        expect(await bindings(window)).toEqual({ ':default': Number(id) });
    });

    test('a client that cannot evaluate is refused, and says why', async ({ window }) => {
        // The guard that matters: `get-client!` reuses whatever is bound if it is
        // merely *available*, and never checks it can serve the command. So a
        // client with no evaluation command bound under :default would be a
        // buffer whose next evaluation goes nowhere with nothing to say why.
        const id = await evalClj(window, `
            (let [c (lt.objs.clients/client! :test.doc-only)]
              (lt.objs.clients/merge-info c {:name "DocOnly" :commands ["editor.clj.doc"]})
              (lt.objs.clients/->id c))`);

        await evalClj(window, `(do (lt.actions/dispatch! [[:client/bind ${id}]]) :tried)`);

        expect(await bindings(window), 'nothing was bound').toEqual({});
        expect(await evalClj(window, '(:text (:message @lt.state/app))'))
            .toContain('cannot evaluate');
    });

    test('the menu offers both directions, and only the applicable one', async ({ window }) => {
        const id = await evalClj(window,
            '(lt.objs.clients/->id (lt.objs.clients/by-name "LightTable-UI"))');
        const labels = async () => await evalData<string[]>(window, `
            (mapv :label (lt.object/raise-reduce lt.objs.sidebar.clients/panel
                                                 :client-menu-items [] ${id}))`);

        expect(await labels()).toContain('Evaluate through this');
        expect(await labels()).not.toContain('Stop evaluating through this');

        await evalClj(window, `(do (lt.actions/dispatch! [[:client/bind ${id}]]) :bound)`);

        // And the other way round once it is bound, so the menu is never
        // offering the thing you have already done.
        expect(await labels()).not.toContain('Evaluate through this');
        expect(await labels()).toContain('Stop evaluating through this');
    });

    test('and evaluation reads what the click wrote', async ({ window }) => {
        // The loop closed. Everything above asserts the key is written; this
        // asserts the key is what an evaluation actually goes through, which is
        // the only reason writing it matters.
        //
        // `get-client!` short-circuits on `(-> @origin :client key)` before it
        // discovers anything, so a bound client is returned without a search —
        // and that short-circuit is the mechanism the panel has always been a
        // control for.
        const id = await evalClj(window,
            '(lt.objs.clients/->id (lt.objs.clients/by-name "LightTable-UI"))');
        await evalClj(window, `(do (lt.actions/dispatch! [[:client/bind ${id}]]) :bound)`);

        const served = await evalClj(window, `
            (lt.objs.clients/->id
              (lt.objs.eval/get-client!
                {:origin (lt.objs.editor.pool/last-active)
                 :command :editor.eval.cljs
                 :create (fn [_] (throw (js/Error. "discovered instead of using the binding")))}))`);
        expect(Number(served)).toBe(Number(id));
    });

    test('and binding raises nothing', async ({ window, ltErrors }) => {
        const id = await evalClj(window,
            '(lt.objs.clients/->id (lt.objs.clients/by-name "LightTable-UI"))');
        await evalClj(window, `(do (lt.actions/dispatch! [[:client/bind ${id}]]) :bound)`);
        expect((await ltErrors()).join('\n')).toBe('');
    });
});
