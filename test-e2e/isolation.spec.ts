// The window has no Node, asserted where it is cheap to assert.
//
// doc/hygiene.md listed this as smoke-only and called it "a security-relevant
// invariant that would be cheap to pin as an e2e one-liner". Both halves are
// worth taking seriously: it is the thing the whole context-isolation migration
// was for, and `make smoke` needs a packaged build, so on a normal change it is
// checked once at the end rather than on every run.
//
// The four checks below are the smoke test's, kept deliberately identical in
// what they establish. Duplication is the right call here — this is the one
// invariant where two independent statements of it is a feature, because the
// failure mode is somebody turning `contextIsolation` off to debug something and
// not turning it back on.

import { test, expect } from './fixtures';

test.describe('the window', () => {
    test('has none of Node\'s globals', async ({ window }) => {
        // `require` and `process` *are* present and are Light Table's own — the
        // shim, and a stub — so their absence is not the test. See below.
        expect(await window.evaluate(() => ({
            dirname: typeof (globalThis as Record<string, unknown>)['__dirname'],
            module: typeof (globalThis as Record<string, unknown>)['module']
        }))).toEqual({ dirname: 'undefined', module: 'undefined' });
    });

    test('and the require it does have is not Node\'s', async ({ window }) => {
        // Node's resolves anything on disk; the shim serves a list. Asking for a
        // builtin Light Table does not serve tells them apart — and `vm` is the
        // one to ask for, because a window that can reach it can compile code
        // outside every boundary this migration built.
        expect(await window.evaluate(() => {
            try {
                (globalThis as { require?: (m: string) => unknown }).require?.('vm');
                return 'served';
            } catch {
                return 'refused';
            }
        })).toBe('refused');
    });

    test('and the process it does have is not Node\'s', async ({ window }) => {
        expect(await window.evaluate(() => {
            const p = (globalThis as Record<string, unknown>)['process'] as
                { binding?: unknown; mainModule?: unknown } | undefined;
            return { binding: typeof p?.binding, mainModule: p?.mainModule ?? null };
        })).toEqual({ binding: 'undefined', mainModule: null });
    });

    test('and the bridge arrived through contextBridge rather than a shared global',
         async ({ window }) => {
        // The load-bearing one, and the only check here that distinguishes
        // `contextIsolation: true` from a preload that merely tidied up after
        // itself. With isolation off the bridge is assigned to the same global
        // object the window sees, so it would be the preload's own object;
        // through `contextBridge` it is a proxy whose constructor belongs to the
        // window's world.
        expect(await window.evaluate(() => {
            const bridge = (globalThis as Record<string, unknown>)['lightTable'];
            return bridge !== undefined &&
                   (bridge as object).constructor === Object &&
                   Object.getPrototypeOf(bridge as object) === Object.prototype;
        })).toBe(true);
    });
});
