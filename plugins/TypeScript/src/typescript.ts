// TypeScript support for Light Table.
//
// Light Table itself knows what a .ts file is — deploy/settings/default has the
// extensions and the CodeMirror mime, the same way it does for every other
// language it ships. What a language *plugin* adds is everything past
// colouring, and for TypeScript the first useful thing is the type checker:
// tsc already knows the project layout, already resolves the imports, and
// already produces exactly the diagnostics an editor wants.
//
// So this runs it, and reports what it says. That is deliberately not a
// language server — see doc/language-support.md for how one would fit, and why
// it should be built once for every language rather than here. The seams this
// uses (find the project root, spawn a process, stream its output) are the
// ones an LSP client needs too, which is why they live in plugins/lib/lt.ts.

namespace LTTypeScript {

    /** What marks the root of a TypeScript project, nearest first. */
    const PROJECT_MARKERS = ['tsconfig.json', 'jsconfig.json', 'package.json'];

    /** One line of `tsc` output that names a file, a position and a problem. */
    interface Diagnostic {
        file: string;
        line: number;
        column: number;
        severity: string;
        code: string;
        message: string;
    }

    /**
     * Parse tsc's default output.
     *
     * `src/a.ts(12,5): error TS2304: Cannot find name 'foo'.`
     *
     * Parsed rather than run with `--pretty false --listFilesOnly`-style
     * machine output because tsc has no stable machine format, and this one has
     * not changed in a decade. Lines that do not match are passed through
     * untouched rather than dropped — a compiler error about the config itself
     * has no file and position, and losing it would be the worst possible
     * outcome for a type check.
     */
    export function parseDiagnostic(line: string): Diagnostic | null {
        const m = /^(.+?)\((\d+),(\d+)\):\s+(error|warning)\s+(TS\d+):\s+(.*)$/.exec(line);
        if (!m) return null;
        return {
            file: m[1] as string,
            line: Number(m[2]),
            column: Number(m[3]),
            severity: m[4] as string,
            code: m[5] as string,
            message: m[6] as string
        };
    }

    /** The tsconfig governing `path`, or null when there is none above it. */
    export function configFor(path: string): string | null {
        const root = LT.projectRoot(path, PROJECT_MARKERS);
        if (!root) return null;
        const tsconfig = LT.files.join(root, 'tsconfig.json');
        return LT.files.exists(tsconfig) ? tsconfig : null;
    }

    /**
     * Where to find tsc for a project.
     *
     * The project's own copy first: a type check run against a different
     * compiler than the project builds with reports differences that are not
     * the code's. `npx` is not used — it would reach the network for a missing
     * one, and silently checking against whatever npm downloaded is exactly the
     * failure this avoids.
     */
    export function compilerFor(root: string): string | null {
        const local = LT.files.join(root, 'node_modules', '.bin', 'tsc');
        return LT.files.exists(local) ? local : null;
    }

    function report(lines: string[]): void {
        let problems = 0;
        for (const line of lines) {
            const trimmed = line.trimEnd();
            if (!trimmed) continue;
            const d = parseDiagnostic(trimmed);
            if (d) {
                problems += 1;
                // file(line,col) is what every editor's error matcher expects,
                // and Light Table's console links it.
                LT.error(`${d.file}(${d.line},${d.column}) ${d.code}: ${d.message}`);
            } else {
                LT.log(trimmed);
            }
        }
        LT.notify(problems === 0 ? 'TypeScript: no problems' :
                  `TypeScript: ${problems} problem${problems === 1 ? '' : 's'}`);
    }

    /** Type-check the project the active file belongs to. */
    export function typeCheck(): void {
        const path = LT.currentPath();
        if (!path) { LT.notify('TypeScript: no file is open'); return; }

        const tsconfig = configFor(path);
        if (!tsconfig) {
            LT.notify('TypeScript: no tsconfig.json above ' + LT.files.basename(path));
            return;
        }
        const root = LT.files.dirname(tsconfig);
        const tsc = compilerFor(root);
        if (!tsc) {
            LT.notify('TypeScript: no compiler in ' + root + '/node_modules');
            LT.error('TypeScript: install typescript in the project — a check against a ' +
                     'different compiler than it builds with is not a check of it.');
            return;
        }

        LT.notify('TypeScript: checking ' + LT.files.basename(root) + '…');
        const output: string[] = [];
        LT.spawn(tsc, ['--noEmit', '-p', tsconfig], {
            cwd: root,
            onStdout: (chunk) => { output.push(...chunk.split('\n')); },
            onStderr: (chunk) => { output.push(...chunk.split('\n')); },
            onError: (message) => { LT.error('TypeScript: ' + message); },
            // tsc exits non-zero when it found something, which is not a
            // failure to run — the output says which it was either way.
            onExit: () => { report(output); }
        });
    }

    LT.command({
        command: 'typescript.type-check',
        desc: 'TypeScript: Type-check this project',
        exec: typeCheck
    });

    LT.command({
        command: 'typescript.open-tsconfig',
        desc: 'TypeScript: Open the tsconfig governing this file',
        exec: function() {
            const path = LT.currentPath();
            if (!path) { LT.notify('TypeScript: no file is open'); return; }
            const tsconfig = configFor(path);
            if (tsconfig) LT.open(tsconfig);
            else LT.notify('TypeScript: no tsconfig.json above ' + LT.files.basename(path));
        }
    });

    /**
     * Plugins are evaluated by `lt.util.load/js` through `window.eval`, and
     * this file is strict-mode, so its top-level bindings stay inside that
     * eval rather than becoming globals. Anything a plugin wants to publish
     * has to be assigned somewhere deliberately, and `lt.plugins` is where
     * ClojureScript plugins land.
     */
    lt.plugins['typescript'] = {
        version: '0.1.0',
        typeCheck,
        configFor,
        parseDiagnostic
    };
}
