// The command that reads the user's shell environment, run through a real shell.
//
// `test/lt/objs/proc/shell_env_test.cljs` covers every decision in
// `lt.objs.proc.shell-env` as a pure function, and it would not have caught the
// bug this file exists for. The command was built with the marker in *single*
// quotes, inside an argument that was already single-quoted, so the shell closed
// the string, dropped the quotes, and node evaluated the marker as a bare
// identifier. A test that compares the command to an expected string agrees with
// whatever the code says. Only a shell disagrees.
//
// It did not fail loudly either. Resolution reported nothing, `PATH` stayed as
// Finder had left it, and what the user saw was that language servers did not
// work — so the cost of not having this test was a whole investigation aimed at
// the wrong layer.
//
// **The environment is deliberately bare**, and that is the other half. Run with
// this process's own environment inherited, the assertions below pass whether or
// not the resolution works at all, because `PATH` already has everything in it.
// What is reproduced here is a launch from Finder or a desktop entry: `HOME`,
// `USER`, `SHELL`, and a `PATH` with nothing on it.
//
// `process.execPath` — node — stands in for Electron under
// `ELECTRON_RUN_AS_NODE`. The two run the same `-p` and the quoting is what is
// under test, so the substitution changes nothing and keeps this in the layer it
// belongs to: main-process units, under plain node, with no build required.

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import * as os from 'node:os';

/**
 * `lt.objs.proc.shell-env/command`, in TypeScript.
 *
 * Duplicated rather than imported, because the ClojureScript is compiled into
 * the window bundle and this project runs under plain node. So it is asserted
 * against the source as well — see `the-command-here-matches-the-source` — which
 * is the arrangement `ripgrep.test.ts` uses for the same reason.
 */
function envCommand(execPath: string, mark: string): string {
    return `'${execPath}' -p '"${mark}" + JSON.stringify(process.env) + "${mark}"'`;
}

interface Ran { code: number | null; stdout: string; stderr: string }

/** `shell args… command`, with `env` and nothing else. */
function runShell(shell: string, args: string[], command: string,
                  env: Record<string, string>): Promise<Ran> {
    return new Promise((resolve, reject) => {
        const child = spawn(shell, [...args, command], { env, stdio: ['ignore', 'pipe', 'pipe'] });
        let stdout = '', stderr = '';
        child.stdout.on('data', (d) => { stdout += String(d); });
        child.stderr.on('data', (d) => { stderr += String(d); });
        child.on('error', reject);
        child.on('close', (code) => { resolve({ code, stdout, stderr }); });
    });
}

/** What Finder gives an application: almost nothing. */
function bareEnv(): Record<string, string> {
    return {
        HOME: os.homedir(),
        USER: os.userInfo().username,
        SHELL: process.env['SHELL'] ?? '/bin/sh',
        PATH: '/usr/bin:/bin:/usr/sbin:/sbin',
        ELECTRON_RUN_AS_NODE: '1',
        ELECTRON_NO_ATTACH_CONSOLE: '1'
    };
}

describe('reading the environment out of the user\'s shell', () => {
    // Windows has no login shell to ask, and `lt.objs.proc/resolve-shell-env`
    // skips it for that reason. Nothing here would mean anything there.
    const unix = process.platform === 'darwin' || process.platform === 'linux';

    test('the command survives the shell and returns an environment', { skip: !unix }, async () => {
        const env = bareEnv();
        const mark = '__LT_ENV_test__';
        const command = envCommand(process.execPath, mark);

        const { code, stdout, stderr } = await runShell(env.SHELL!, ['-i', '-l', '-c'], command, env);

        // The marker having been eaten is the regression, so it is named.
        assert.ok(!/is not defined/.test(stderr),
                  `the shell ate the quoting around the marker:\n${stderr}`);

        const match = new RegExp(`${mark}({.*})${mark}`).exec(stdout);
        assert.ok(match, `no environment between the markers (exit ${code}):\n`
                         + `stdout: ${JSON.stringify(stdout.slice(0, 400))}\n`
                         + `stderr: ${JSON.stringify(stderr.slice(0, 400))}`);

        const resolved = JSON.parse(match[1]!) as Record<string, string>;
        assert.ok(Object.keys(resolved).length > 5, 'an environment has more than five things in it');
        assert.ok(resolved['PATH'], 'a shell always sets PATH');
    });

    test('it finds more than the bare PATH it was given', { skip: !unix }, async () => {
        // The point of the whole exercise. A login, interactive shell reads the
        // user's rc files, so what comes back must not be what went in — that is
        // what "launched from Finder" looks like, and it is the bug.
        //
        // Only asserted when the shell has something to add, because a container
        // with an empty `~/.profile` is a legitimate machine to run tests on and
        // there `PATH` genuinely does not change.
        const env = bareEnv();
        const mark = '__LT_ENV_test__';
        const { stdout } = await runShell(env.SHELL!, ['-i', '-l', '-c'],
                                          envCommand(process.execPath, mark), env);
        const match = new RegExp(`${mark}({.*})${mark}`).exec(stdout);
        assert.ok(match, 'no environment came back');

        const resolved = JSON.parse(match[1]!) as Record<string, string>;
        const control = await runShell(env.SHELL!, ['-i', '-l', '-c'],
                                       envCommand(process.execPath, mark),
                                       { ...env, PATH: '/usr/bin:/bin' });
        const controlMatch = new RegExp(`${mark}({.*})${mark}`).exec(control.stdout);
        const controlPath = controlMatch
            ? (JSON.parse(controlMatch[1]!) as Record<string, string>)['PATH']
            : '';

        // A shell that builds the same PATH from two different starting points is
        // a shell that is deciding it, which is exactly what is wanted. A shell
        // that just passed ours through would give back what it was handed.
        if (controlPath !== '/usr/bin:/bin') {
            assert.notEqual(resolved['PATH'], env.PATH,
                            'the shell added nothing, so nothing was read from the rc files');
        }
    });

    test('a value containing a newline comes back whole', { skip: !unix }, async () => {
        // Why JSON and not `env`: `env` output cannot be split into variables
        // when a value has a newline in it.
        const env = bareEnv();
        env['LT_TEST_MULTILINE'] = 'one\ntwo';
        const mark = '__LT_ENV_test__';
        const { stdout } = await runShell(env.SHELL!, ['-i', '-l', '-c'],
                                          envCommand(process.execPath, mark), env);
        const match = new RegExp(`${mark}({.*})${mark}`).exec(stdout);
        assert.ok(match, 'no environment came back');
        assert.equal((JSON.parse(match[1]!) as Record<string, string>)['LT_TEST_MULTILINE'],
                     'one\ntwo');
    });

    test('the command here matches the one the editor builds', async () => {
        // The duplication above, held to the source. `ripgrep.test.ts` reads a
        // path out of three files for the same reason: agreement between files
        // that cannot see each other is the thing worth asserting.
        const fs = await import('node:fs');
        const source = fs.readFileSync('src/lt/objs/proc/shell_env.cljs', 'utf8');
        const built = envCommand('EXEC', 'MARK');

        // The ClojureScript `str` call, evaluated by hand: this is what
        // `(str "'" exec-path "' -p '\"" mark "\" + JSON.stringify(process.env) + \"" mark "\"'")`
        // produces, so finding those literals in order proves the shapes agree.
        assert.match(source, /\(str "'" exec-path "' -p '\\""/,
                     'shell_env.cljs no longer builds the command the way this test does');
        assert.match(source, /JSON\.stringify\(process\.env\)/);
        assert.equal(built, `'EXEC' -p '"MARK" + JSON.stringify(process.env) + "MARK"'`);
    });
});
