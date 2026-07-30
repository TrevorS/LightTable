// The Light Table API, as a TypeScript plugin sees it.
//
// Light Table is ClojureScript, and its API is the compiled `lt.*` globals. A
// plugin written in TypeScript is calling into ClojureScript, which means two
// things this file has to be honest about.
//
// First, the arguments are ClojureScript values. A command map is a keyword-
// keyed ClojureScript map, not a JS object, so a plugin builds one with the
// helpers in plugins/lib/lt.ts rather than with an object literal. The opaque
// types below exist to make passing the wrong one a compile error.
//
// Second, these declarations are hand-written against an API with no
// machine-readable schema, so they describe rather than guarantee. What they
// buy is the mistakes that actually happen: a misspelled namespace, a command
// missing :exec, a keyword built with the wrong arity. Everything declared here
// is exercised against the running editor by the smoke test.

/** An opaque ClojureScript keyword. Build them with `LT.keyword`. */
interface Keyword { readonly __keyword: unique symbol }
/** An opaque ClojureScript map. Build them with `LT.map`. */
interface CljsMap { readonly __cljsMap: unique symbol }
/** An opaque Light Table object, as created by `lt.object.create`. */
interface LTObject { readonly __ltObject: unique symbol }

/** ClojureScript's runtime, as published by the compiled bundle. */
declare const cljs: {
    core: {
        keyword: {
            (name: string): Keyword;
            (ns: string | null, name: string): Keyword;
        };
        PersistentHashMap: {
            fromArrays(keys: unknown[], values: unknown[]): CljsMap;
        };
        pr_str(x: unknown): string;
        count(x: unknown): number;
        deref(x: unknown): unknown;
        get(m: unknown, k: unknown): unknown;
    };
};

declare const lt: {
    object: {
        create(prototype: Keyword | unknown): LTObject;
        raise(obj: LTObject, event: Keyword, ...args: unknown[]): void;
        /** The object's rendered element. */
        __GT_content(obj: LTObject): HTMLElement;
    };
    objs: {
        command: {
            /**
             * Register a command. The map needs :command, :desc and :exec;
             * :hidden keeps it out of the command bar.
             */
            command(cmd: CljsMap): void;
            exec_BANG_(name: Keyword, ...args: unknown[]): void;
            manager: unknown;
        };
        notifos: {
            set_msg_BANG_(msg: string): void;
        };
        console: {
            log(msg: string): void;
            error(msg: string | Error, ...rest: unknown[]): void;
        };
        tabs: {
            add_or_focus_BANG_(obj: LTObject): void;
        };
        editor: {
            pool: { last_active(): LTObject | null };
        };
    };
    util: {
        dom: {
            $(selector: string, from?: Element): HTMLElement | null;
        };
        /**
         * Everything the window can ask the desktop for. A plugin reaching past
         * this into `require` is asking for capabilities its manifest has to
         * declare — see plugins/README.md.
         */
        bridge: {
            shell: {
                open(pathOrUrl: string): Promise<string>;
                openExternal(url: string): Promise<void>;
                showItemInFolder(path: string): void;
                trashItem(path: string): Promise<void>;
            };
            clipboard: { readText(): string; writeText(text: string): void };
            zoom: { get(): number; set(factor: number): void };
            app_info: unknown;
        };
    };
    plugins: Record<string, unknown>;
};
