import { test, expect, evalClj as evalWith, evalData } from './fixtures';
import type { Page } from '@playwright/test';

const ev = (window: Page, source: string) => evalWith(window, source, { tries: 300 });
const drift = async (window: Page) =>
    await window.evaluate(() => (globalThis as any).lt.objs.control.request('drift', {}));

test('probe: which projected facts go stale', async ({ window }) => {
    await ev(window, '(do (lt.state.objects/sync!) :settled)');
    console.log('baseline:', JSON.stringify((await drift(window)).drifted));

    // 1. A client destroyed. `sync-from-clients` triggers on :destroy, and
    //    `destroy!` removes the instance after raising it — the same ordering
    //    as the editor bug.
    await ev(window, `
        (do (def probe-client
              (object/create (object/object* :lt.probe/c :tags #{:client} :init (fn [_] nil))))
            (object/merge! probe-client {:name "probe-client"})
            (swap! lt.objs.clients/cs assoc (lt.objs.clients/->id probe-client) probe-client)
            (lt.state.objects/sync!)
            :added)`);
    console.log('after add:', JSON.stringify((await drift(window)).drifted.map((d: any) => d.key)));
    await ev(window, `
        (do (swap! lt.objs.clients/cs dissoc (lt.objs.clients/->id probe-client))
            (object/destroy! probe-client)
            :destroyed)`);
    await window.waitForTimeout(300);
    const afterDestroy = (await drift(window)).drifted;
    console.log('CLIENT DESTROY drift:', JSON.stringify(afterDestroy.map((d: any) => d.key)));

    await ev(window, '(do (lt.state.objects/sync!) :resync)');

    // 2. A command registered after the last sync. The command bar draws from
    //    the projection, and `cmd/manager` raises `:added` that nothing hears.
    await ev(window, `
        (do (cmd/command {:command :lt.probe/late :desc "Probe: registered late"
                          :exec (fn [] nil)})
            :registered)`);
    await window.waitForTimeout(300);
    const afterCommand = (await drift(window)).drifted;
    console.log('COMMAND drift:', JSON.stringify(afterCommand.map((d: any) => d.key)));
    console.log('  in the bar?',
        await evalData(window, `
            (boolean (some #(= "Probe: registered late" (:label %))
                           (:commands (:command-bar @lt.state/app))))`));

    await ev(window, '(do (lt.state.objects/sync!) :resync)');

    // 3. The agent client's own status, which changes as work starts and
    //    finishes and is projected into the connect panel.
    console.log('agent state keys:', await evalData(window, '(pr-str (lt.objs.clients.agent/state))'));
    expect(true).toBe(true);
});
