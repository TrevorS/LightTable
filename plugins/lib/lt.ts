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
}
