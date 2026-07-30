// Light Table's browser tab, from inside the page.
//
// This is the preload for the <webview> that backs lt.objs.browser. It runs in
// the guest page, which is an arbitrary website, and it is how the editor
// evaluates code against that page and gets results back.
//
// It is the guest's whole Node surface: a webview guest gets no node
// integration (main.ts pins that when the view attaches), and this file uses
// only ipcRenderer, which a sandboxed preload is allowed. contextIsolation is
// deliberately not on for guests — the injection has to run in the page's own
// world for `eval` and `lttools` to mean anything there, which is the feature.
//
// Compiled to deploy/core/browserInjection.js by `npm run build:main`.

import { ipcRenderer } from 'electron';

/** What lt.objs.browser sends when a stylesheet is evaluated. */
interface CssEval {
    name: string;
    code: string;
}

/** One expression the editor asked the page to evaluate. */
interface EvalResult {
    code: string;
    meta: Record<string, unknown>;
}

interface CljsEval {
    client: number;
    results: EvalResult[];
}

declare const cljs: any;
declare const jQuery: any;

function toArray<T extends Element>(arrayLike: HTMLCollectionOf<T> | NodeListOf<T>): T[] {
    const final: T[] = [];
    for (let i = 0, len = arrayLike.length; i < len; i++) {
        final.push(arrayLike[i]!);
    }
    return final;
}

// These were registered on ipcMain, which does not exist in a renderer — so
// this file threw on its first statement and none of the browser tab worked.
// The listener signature was missing Electron's event argument too.
ipcRenderer.on("editor.eval.css", function(_event, args: CssEval) {
    const nodeName = args.name.replace(/\./, "-");
    const styleElem = document.createElement("style");
    styleElem.type = "text/css";
    styleElem.id = nodeName;
    styleElem.innerHTML = args.code;
    const prev = document.getElementById(nodeName);
    if (prev) {
        prev.parentNode?.removeChild(prev);
    } else {
        const link = toArray(document.head.querySelectorAll("link")).filter(function(cur) {
            return cur.href.indexOf(args.name) > -1;
        });
        if (link[0]) {
            link[0].parentNode?.removeChild(link[0]);
        }
    }
    document.head.appendChild(styleElem);
});

ipcRenderer.on("editor.eval.cljs.exec", function(_event, args: CljsEval) {
    for (const result of args.results) {
        const meta = result.meta;
        meta["verbatim"] = true;
        try {
            // Evaluated against the page's own global scope, which is the
            // whole point of the browser tab.
            const res = eval.call(window, result.code);
            const printed = (window as any).cljs ? cljs.core.pr_str(res) : safeStringify(res);
            ipcRenderer.sendToHost("browser-raise",
                                   [args.client, "editor.eval.cljs.result", { result: printed, meta }]);
        } catch (e) {
            const exdata = (window as any).cljs ? cljs.core.ex_data(e) : null;
            let error = exdata
                ? (e as Error).message + ": " + cljs.core.pr_str(exdata)
                // Was cljs.core.pr_str unconditionally, which throws its own
                // error on any page that is not a ClojureScript app.
                : String((e as Error).message ?? e);
            if ((e as Error).stack) {
                error += "\n" + (e as Error).stack;
            }
            ipcRenderer.sendToHost("browser-raise",
                                   [args.client, "editor.eval.cljs.exception", { ex: error, meta }]);
        }
    }
});

window.addEventListener("hashchange", function() {
    ipcRenderer.sendToHost("browser-event",
                           ["hashchange", { href: window.location.href, hash: window.location.hash }]);
});

// Values seen while stringifying the current result, so cycles can be caught.
// Was an implicit global, assigned before it was ever declared.
let cache: unknown[] = [];

function replacer(_key: string, value: unknown): unknown {
    if ((window as any).jQuery && value instanceof jQuery) {
        return "[jQuery $(" + (value as any).selector + ")]";
    }
    if (value instanceof Element) {
        return "[Element " + value.tagName.toLowerCase() + (value.id != "" ? "#" : "") + value.id + "]";
    }
    if (value instanceof Array) {
        return value;
    }
    if (typeof value == "object" && value !== null) {
        if (cache.indexOf(value) > -1) {
            return "circular";
        }
        cache.push(value);
        return value;
    }
    if (typeof value == "function") {
        return "[function]";
    }
    return value;
}

function safeStringify(res: unknown): string {
    cache = [];
    return JSON.stringify(res, replacer);
}

// How watches in the editor report back from the page.
(window as any).lttools = {
    watch: function(exp: unknown, meta: Record<string, unknown>) {
        let final;
        if (meta["ev"] == "editor.eval.cljs.watch") {
            final = cljs.core.pr_str(exp);
        } else {
            meta["no-inspect"] = true;
            final = safeStringify(exp);
        }
        ipcRenderer.sendToHost("browser-raise", [meta["obj"], meta["ev"], { result: final, meta }]);
        return exp;
    }
};
