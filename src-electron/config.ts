// Decisions the main process makes before it does anything, with no Electron
// and no filesystem in them.
//
// They live here because both were wrong in a shipped build and neither was
// reachable by a test. `main.ts` owns windows, menus and ipc, so testing it
// means starting Electron; everything in this file is a function from values
// to values, and `test-electron/config.test.ts` covers it under plain node.
//
// The rule this file exists to hold: if it is a decision rather than an
// effect, it belongs here.

/** The subset of package.json's browserWindowOptions this module reasons about. */
export interface WindowOptionDefaults {
    icon?: string;
    webPreferences?: { preload?: string;[key: string]: unknown };
    [key: string]: unknown;
}

/**
 * Options for one BrowserWindow, resolved against the application directory.
 *
 * Electron resolves neither the icon nor the preload relative to the app, so
 * both need `__dirname`. The reason this is a function returning a new object
 * rather than three lines that add `__dirname` in place: `defaults` is what
 * `require('./package.json')` returned, and `require` hands back the same
 * object every time. Prepending to it worked once and then compounded — the
 * second window asked for `<app>/<app>/preload.js`, got no preload, and came
 * up white, while the first window and every existing check stayed green.
 *
 * `extra` is merged last so a caller can override size or visibility without
 * reaching into the result.
 */
export function windowOptions(
    defaults: WindowOptionDefaults,
    appDir: string,
    extra: Record<string, unknown> = {}
): Record<string, unknown> {
    const { icon, webPreferences, ...rest } = defaults;
    const resolved: Record<string, unknown> = { ...rest };

    if (icon !== undefined) resolved.icon = appDir + '/' + icon;

    if (webPreferences !== undefined) {
        const { preload, ...prefs } = webPreferences;
        resolved.webPreferences = preload === undefined
            ? { ...prefs }
            : { ...prefs, preload: appDir + '/' + preload };
    }

    return { ...resolved, ...extra };
}

/**
 * Should windows be created hidden?
 *
 * Electron has no headless mode — Chromium's is not exposed — so the closest
 * thing is a window that is never shown. It still lays out, still runs
 * scripts, and `getComputedStyle` still answers; it just does not appear or
 * take focus.
 *
 * That matters for test runs. A suite that opens sixteen windows across your
 * desktop and steals focus from whatever you were typing into is a suite
 * people stop running locally, and on a CI runner the windows are invisible
 * anyway.
 *
 * Opt-in rather than opt-out: the application shows its window, and a harness
 * says otherwise. `LT_HEADED` wins over `LT_HEADLESS`, so a single variable
 * turns a debugging run back into a visible one without editing anything.
 */
export function headless(env: Record<string, string | undefined>): boolean {
    if (truthy(env.LT_HEADED)) return false;
    return truthy(env.LT_HEADLESS);
}

function truthy(value: string | undefined): boolean {
    if (value === undefined) return false;
    const v = value.trim().toLowerCase();
    return v !== '' && v !== '0' && v !== 'false' && v !== 'off' && v !== 'no';
}

/** What `resolveDebugPort` decided, and why, so a caller can log it. */
export interface DebugPort {
    port: number | null;
    reason: 'default' | 'configured' | 'disabled' | 'invalid';
}

export const DEFAULT_DEBUG_PORT = 8315;

/**
 * The port Chromium should open its DevTools endpoint on, or null for none.
 *
 * Light Table uses this itself: the browser tab evaluates by speaking the
 * DevTools protocol to the guest page, so it is a feature rather than only a
 * debugging aid, and that is why it defaults to on.
 *
 * It is settable because a fixed port is not always the caller's to take.
 * A test harness that launches the application — Playwright's Electron
 * launcher is the one that made this necessary — appends its own
 * `--remote-debugging-port` and reads back the port Chromium chose. Two
 * switches then disagree, and what you get is a launch that hangs until the
 * harness times out, which is a bad way to learn about a conflict.
 *
 *     LT_REMOTE_DEBUGGING_PORT=off     let the launcher decide
 *     LT_REMOTE_DEBUGGING_PORT=9222    somewhere else
 *
 * An unusable value is reported rather than silently swapped for the default:
 * a typo that quietly opens the port you were trying to move is worse than a
 * line in the log saying so.
 */
export function resolveDebugPort(env: Record<string, string | undefined>): DebugPort {
    const raw = env.LT_REMOTE_DEBUGGING_PORT;
    if (raw === undefined || raw === '') return { port: DEFAULT_DEBUG_PORT, reason: 'default' };

    const value = raw.trim().toLowerCase();
    if (value === 'off' || value === 'none' || value === 'false' || value === '0') {
        return { port: null, reason: 'disabled' };
    }

    const port = Number(value);
    if (!Number.isInteger(port) || port < 1 || port > 65535) {
        return { port: DEFAULT_DEBUG_PORT, reason: 'invalid' };
    }
    return { port, reason: 'configured' };
}

/** How a window can fail badly enough that it stops being a window. */
export type WindowFailure = 'gone' | 'load' | 'unresponsive';

/** What to do about it. */
export type Recovery = 'reload' | 'ask' | 'destroy';

/**
 * What to do when a window fails: reload it, ask, or dispose of it.
 *
 * Here rather than inside the event handler in main.ts because it is the part
 * with the reasoning in it, and because the three cases that matter are awkward
 * to reach by hand — a renderer has to actually die twice, without loading in
 * between, on a build that is not headless.
 *
 * `failures` counts how many times this window has failed *without a successful
 * load in between*, this one included, so the first call for a window is 1.
 *
 * - **A crash or a failed load reloads, once.** Asking preserves nothing: the
 *   renderer that had the unsaved work is already gone, and Light Table restores
 *   the open files from the session on load. VS Code asks instead, because its
 *   editor state lives in the renderer it is abandoning; here it is on disk.
 *
 * - **The second one asks**, because a renderer that dies while starting would
 *   otherwise reload, die and reload for ever — a worse failure than a blank
 *   window, since it never settles and burns a core doing it.
 *
 * - **Unresponsive never reloads.** That renderer is alive and may come back, so
 *   reloading it would be throwing away work that is still there. VS Code offers
 *   Keep Waiting for the same reason.
 *
 * - **Headless never asks.** A modal in a test run is a hang rather than a
 *   failure, and nobody is there to click it. It is the dialog that is skipped,
 *   not the recovery: a headless window still reloads on its first crash, which
 *   is both the right behaviour and the reason the reload path is reachable from
 *   a test at all.
 */
export function recoveryFor(kind: WindowFailure, failures: number, isHeadless: boolean): Recovery {
    if (kind !== 'unresponsive' && failures <= 1) return 'reload';
    return isHeadless ? 'destroy' : 'ask';
}
