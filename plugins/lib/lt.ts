// Helpers every TypeScript plugin needs, for talking to ClojureScript.
//
// A TypeScript namespace rather than a module, because plugins are loaded as
// global-scope scripts and each plugin's output is a single concatenated file.
// A namespace compiles to an idempotent global: two plugins that both include
// this end up populating the same object rather than fighting over it.

namespace LT {

    /** `(keyword "name")` or `(keyword "ns" "name")`. */
    export function keyword(name: string): Keyword;
    export function keyword(ns: string | null, name: string): Keyword;
    export function keyword(a: string | null, b?: string): Keyword {
        return b === undefined
            ? (cljs.core.keyword as (n: string) => Keyword)(a as string)
            : (cljs.core.keyword as (ns: string | null, n: string) => Keyword)(a, b);
    }

    /**
     * A ClojureScript map from a JS object, with each key turned into a
     * keyword. Values pass through unchanged, which is what Light Table wants
     * for functions and strings alike.
     */
    export function map(o: Record<string, unknown>): CljsMap {
        const entries = Object.entries(o).filter(([, v]) => v !== undefined);
        return cljs.core.PersistentHashMap.fromArrays(
            entries.map(([k]) => keyword(k)),
            entries.map(([, v]) => v)
        );
    }

    export interface Command {
        /** Unique name, e.g. "hello-ts.say-hello". */
        command: string;
        /** Shown in the command bar. */
        desc: string;
        exec: () => void;
        /** Keeps it out of the command bar. */
        hidden?: boolean;
    }

    /**
     * Register a command. The point of taking a typed object rather than a map
     * is that a command missing `exec`, or with a misspelled key, is a compile
     * error instead of a command that silently does nothing.
     */
    export function command(cmd: Command): void {
        lt.objs.command.command(map({
            command: keyword(cmd.command),
            desc: cmd.desc,
            exec: cmd.exec,
            hidden: cmd.hidden
        }));
    }

    /** A message in the status bar. */
    export function notify(msg: string): void {
        lt.objs.notifos.set_msg_BANG_(msg);
    }

    /** A line in Light Table's own console. */
    export function log(msg: string): void {
        lt.objs.console.log(msg);
    }

    /** A line in the console, marked as a failure. */
    export function error(msg: string): void {
        lt.objs.console.error(msg);
    }

    /** The path of the file in the active editor, or null. */
    export function currentPath(): string | null {
        const ed = lt.objs.editor.pool.last_active();
        if (!ed) return null;
        const info = cljs.core.get(cljs.core.deref(ed), keyword('info'));
        const path = info && cljs.core.get(info, keyword('path'));
        return typeof path === 'string' ? path : null;
    }

    /** Open a path in an editor tab, the same way the file navigator does. */
    export function open(path: string): void {
        lt.objs.command.exec_BANG_(keyword('open-path'), path);
    }

    /**
     * Path arithmetic and existence checks, over the bridge.
     *
     * Reaching these declares the `:files` capability — see plugins/README.md.
     * They are here rather than left to each plugin because a language plugin
     * needs exactly this much to find the file that defines a project.
     */
    export const files = {
        exists(path: string): boolean { return lt.util.bridge.files.existsSync(path); },
        read(path: string): string { return lt.util.bridge.files.readFileSync(path); },
        join(...parts: string[]): string { return lt.util.bridge.path.join(...parts); },
        dirname(path: string): string { return lt.util.bridge.path.dirname(path); },
        basename(path: string): string { return lt.util.bridge.path.basename(path); }
    };

    /**
     * The nearest ancestor of `from` containing any of `markers`, or null.
     *
     * What "the project" means is a per-language question — tsconfig.json,
     * project.clj, pyproject.toml — but finding it is not, so it lives here.
     */
    export function projectRoot(from: string, markers: string[]): string | null {
        let dir = files.dirname(from);
        for (;;) {
            for (const marker of markers) {
                if (files.exists(files.join(dir, marker))) return dir;
            }
            const parent = files.dirname(dir);
            if (parent === dir) return null;
            dir = parent;
        }
    }

    export interface ProcessOptions {
        cwd?: string;
        onStdout?: (chunk: string) => void;
        onStderr?: (chunk: string) => void;
        onExit?: (code: number | null) => void;
        onError?: (message: string) => void;
    }

    export interface ProcessHandle {
        readonly pid: number | undefined;
        kill(): void;
    }

    /**
     * Run a program, streaming its output.
     *
     * Declares the `:processes` capability. Streaming rather than
     * collect-and-return on purpose: a type checker reports as it goes, and a
     * language server never finishes at all — whatever eventually speaks
     * JSON-RPC to one will want this same shape, not a promise.
     */
    export function spawn(command: string, args: string[], opts: ProcessOptions = {}): ProcessHandle {
        const handle = lt.util.bridge.processes.spawn(command, args,
                                                      opts.cwd === undefined ? {} : { cwd: opts.cwd });
        if (opts.onStdout) handle.onStdout(opts.onStdout);
        if (opts.onStderr) handle.onStderr(opts.onStderr);
        if (opts.onExit) handle.onExit(opts.onExit);
        if (opts.onError) handle.onError(opts.onError);
        return handle;
    }
}
