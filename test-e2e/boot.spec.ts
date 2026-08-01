// What the window shows when the editor does not start.
//
// Everything else in this directory asks a running Light Table questions.
// This asks what happens when there is no running Light Table: the page holds
// a loader until the editor raises :show, so a failure before that point is a
// dark screen and nothing else, with the reason in a developer console a
// released app does not open. Reported as "I think it's white screened", which
// is all the information there was.

import * as fs from 'node:fs';
import * as path from 'node:path';
import { test, expect, launch, teardown, CORE, scratchDir } from './fixtures';

/**
 * A deploy/core that is real except for `lighttable/bootstrap.js`.
 *
 * Directories are symlinked — deploy/core is hundreds of megabytes of
 * node_modules and plugins, and none of it is what the test is changing. The
 * loose files are copied, because main.js finds everything else with
 * `__dirname` and node resolves a symlinked module to where it really is: a
 * symlinked main.js loads the original page and the substitution does nothing.
 */
function coreWithBundle(bundle: string | null): string {
    const dir = scratchDir('boot-core');
    const mirror = (from: string, to: string, skip: string) => {
        fs.mkdirSync(to, { recursive: true });
        for (const entry of fs.readdirSync(from)) {
            if (entry === skip) continue;
            const source = path.join(from, entry);
            if (fs.statSync(source).isDirectory()) fs.symlinkSync(source, path.join(to, entry));
            else fs.copyFileSync(source, path.join(to, entry));
        }
    };

    mirror(CORE, dir, 'lighttable');
    mirror(path.join(CORE, 'lighttable'), path.join(dir, 'lighttable'), 'bootstrap.js');
    if (bundle !== null) fs.writeFileSync(path.join(dir, 'lighttable', 'bootstrap.js'), bundle);
    return dir;
}

async function saidOnThePage(core: string): Promise<string> {
    const app = await launch({}, core);
    const window = await app.firstWindow();
    await window.waitForSelector('#loader pre', { timeout: 20000 });
    const said = await window.textContent('#loader pre') ?? '';
    // teardown rather than close: a window that never finished starting never
    // answers the close handler, and close() then waits for as long as the run
    // is allowed to take.
    await teardown(app);
    return said;
}

test('a bundle that did not build says so instead of showing nothing', async () => {
    // A bundle that loads and defines nothing — a build that stopped halfway,
    // or a file truncated by a full disk. `lt.objs.app.init()` is then a
    // TypeError, which the page used to swallow into console.error.
    const core = coreWithBundle('var lt = {};\n');
    const said = await saidOnThePage(core);

    expect(said).toContain('Light Table could not start');
    expect(said).toContain('lt.objs.app.init()');
    // The actual error, not just that there was one.
    expect(said).toMatch(/TypeError|undefined/);
    expect(said).toContain('mismatched build');

    fs.rmSync(core, { recursive: true, force: true });
});

test('and a bundle that is not there at all is reported too', async () => {
    // The one nothing reported: onload never fires, so before this the loader
    // simply stayed up for as long as the window did.
    const core = coreWithBundle(null);
    const said = await saidOnThePage(core);

    expect(said).toContain('Light Table could not start');
    expect(said).toContain('bootstrap.js did not load');

    fs.rmSync(core, { recursive: true, force: true });
});
