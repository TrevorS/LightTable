// The Light Table client, served to a connecting browser.
//
// This is the one piece of Light Table that does not run in Light Table. A page
// under development loads it with a script tag, it opens a socket.io connection
// back to the editor, and from then on evaluating JavaScript, ClojureScript,
// CSS or HTML in an editor tab happens in that page.
//
// Two things follow from where it runs, and both shape the file:
//
//   - It is served as text — lt.objs.clients.ws reads it off disk and hands it
//     to the bridge, which serves it at /lighttable/ws.js. So it stays one
//     self-contained script with no imports, and the compiler emits it as one.
//   - The globals it types against are the page's, not Light Table's. `io`
//     arrives with socket.io, and `cljs` only exists if the page happens to be
//     a ClojureScript application — which is exactly when cljs evaluation is
//     wanted, and is why that path is guarded rather than assumed.

/** socket.io's client, loaded from the editor if the page does not have one. */
interface LTSocket {
    on(event: string, handler: (message: never) => void): void;
    emit(event: string, payload: unknown): void;
    disconnect(): void;
}

declare const io: { connect(url: string): LTSocket };

/** ClojureScript's runtime, present only when the page is a cljs application. */
declare const cljs: {
    core: {
        pr_str(value: unknown): string;
        ex_data(e: unknown): unknown;
    };
};

interface Window {
    jQuery?: { prototype: unknown };
    lttools?: { watch(value: unknown, meta: WatchMeta): unknown };
    io?: unknown;
    title?: string;
}

/** What an editor sends: who asked, what to do, and the payload. */
type WireMessage = [client: unknown, command: string, data: never];

interface Envelope<T> {
    client: unknown;
    command: string;
    data: T;
}

interface EvalMeta {
    obj: unknown;
    ev: string;
    verbatim?: boolean;
    'no-inspect'?: boolean;
}

type WatchMeta = EvalMeta;

(function (window: Window & typeof globalThis) {

    function fromMessage<T>(message: WireMessage): Envelope<T> {
        return { client: message[0], command: message[1], data: message[2] as T };
    }

    function toMessage(prev: { client?: unknown }, command: string, data: unknown): unknown[] {
        return [prev.client, command, data];
    }

    // Reset per stringify rather than per value: what it is for is spotting a
    // value already visited in *this* result, which is how a cycle is caught.
    let seen: unknown[] = [];

    function replacer(this: unknown, _key: string, value: unknown): unknown {
        const jq = window.jQuery;
        if (jq && value instanceof (jq as unknown as new () => unknown)) {
            return '[jQuery $(' + (value as { selector?: string }).selector + ')]';
        }
        if (value instanceof Element) {
            return '[Element ' + value.tagName.toLowerCase() +
                   (value.id !== '' ? '#' : '') + value.id + ']';
        }
        if (value instanceof Array) return value;
        if (typeof value === 'object' && value !== null) {
            if (seen.indexOf(value) > -1) return 'circular';
            seen.push(value);
            return value;
        }
        if (typeof value === 'function') return '[function]';
        return value;
    }

    /** JSON, with DOM nodes, functions and cycles turned into something safe. */
    function safeStringify(value: unknown): string {
        seen = [];
        return JSON.stringify(value, replacer);
    }

    // Where Light Table is, taken from this script's own src: the editor picks
    // a free port, so nothing here can be hard-coded.
    const thisScript = document.getElementById('lt_ws') as HTMLScriptElement | null;
    const parts = (thisScript ? thisScript.src : '').split(':');
    const hasScheme = parts.length > 2;
    const host = hasScheme ? parts[0] + ':' + parts[1] : (parts[0] ?? '');
    const port = ((hasScheme ? parts[2] : parts[1]) ?? '').split('/')[0] ?? '';

    function init(): void {
        const socket = io.connect(host + ':' + port);

        socket.on('connect', function () {
            socket.emit('init', {
                name: window.location.host || window.title || window.location.href,
                types: ['js', 'css', 'html'],
                commands: ['editor.eval.js',
                           'editor.eval.cljs.exec',
                           'editor.eval.html',
                           'editor.eval.css']
            });
        });

        socket.on('client.close', function () {
            socket.disconnect();
        });

        socket.on('editor.eval.css', function (message: WireMessage) {
            const prev = fromMessage<{ name: string; code: string }>(message);
            // One style element per source file, replaced rather than added to,
            // so re-evaluating a stylesheet does not stack copies of it.
            const name = prev.data.name.replace('.', '-');
            const existing = document.querySelector('#' + name);
            if (existing && existing.parentNode) existing.parentNode.removeChild(existing);

            const neue = document.createElement('style');
            neue.id = name;
            neue.type = 'text/css';
            neue.innerHTML = prev.data.code;
            document.head.appendChild(neue);

            socket.emit('result', toMessage(prev, 'editor.eval.css.result', { result: name }));
        });

        socket.on('editor.eval.html', function (message: WireMessage) {
            const prev = fromMessage<unknown>(message);
            socket.emit('result', toMessage(prev, 'editor.eval.html.success', null));
            document.location.reload();
        });

        socket.on('editor.eval.js', function (message: WireMessage) {
            const prev = fromMessage<{ code: string; meta: EvalMeta }>(message);
            try {
                const res = eval.call(window, prev.data.code);
                socket.emit('result', toMessage(prev, 'editor.eval.js.result',
                                                { result: safeStringify(res),
                                                  meta: prev.data.meta,
                                                  'no-inspect': true }));
            } catch (e) {
                const ex = (e instanceof Error && e.stack) ? e.stack : String(e);
                socket.emit('result', toMessage(prev, 'editor.eval.js.exception',
                                                { ex: ex, meta: prev.data.meta }));
            }
        });

        socket.on('editor.eval.cljs.exec', function (message: WireMessage) {
            const prev = fromMessage<{ results: { code: string; meta: EvalMeta }[] }>(message);
            for (const result of prev.data.results) {
                const meta = result.meta;
                meta.verbatim = true;
                try {
                    const res = eval.call(window, result.code);
                    socket.emit('result', toMessage(prev, 'editor.eval.cljs.result',
                                                    { result: cljs.core.pr_str(res), meta: meta }));
                } catch (e) {
                    // ex-data first: a cljs exception carries its data there,
                    // and printing the exception alone loses it.
                    const exdata = cljs.core.ex_data(e);
                    let error = exdata
                        ? (e as Error).message + ': ' + cljs.core.pr_str(exdata)
                        : cljs.core.pr_str(e);
                    if (e instanceof Error && e.stack) error += '\n' + e.stack;
                    socket.emit('result', toMessage(prev, 'editor.eval.cljs.exception',
                                                    { ex: error, meta: meta }));
                }
            }
        });

        // What a watch in an editor calls when the page runs past it. It
        // returns its argument, so wrapping an expression does not change it.
        window.lttools = {
            watch: function (exp: unknown, meta: WatchMeta): unknown {
                let final: string;
                if (meta.ev === 'editor.eval.cljs.watch') {
                    final = cljs.core.pr_str(exp);
                } else {
                    meta['no-inspect'] = true;
                    final = safeStringify(exp);
                }
                socket.emit('result', toMessage({}, 'clients.raise-on-object',
                                                [meta.obj, meta.ev,
                                                 { result: final, meta: meta }]));
                return exp;
            }
        };
    }

    function loadScript(src: string, done: () => void): void {
        const script = document.createElement('script');
        script.setAttribute('src', src);
        script.setAttribute('type', 'text/javascript');
        script.onload = done;
        document.getElementsByTagName('head')[0]?.appendChild(script);
    }

    if (window.io) {
        init();
    } else {
        loadScript(host + ':' + port + '/socket.io/socket.io.js', init);
    }

})(window);
