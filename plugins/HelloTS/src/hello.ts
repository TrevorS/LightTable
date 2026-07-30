// The first Light Table plugin written in TypeScript.
//
// Deliberately small: what it demonstrates is the shape, not the feature. It
// registers a command, reads the editor through the typed API, and uses the
// preload bridge for the one thing it does that touches the desktop. It
// declares no capabilities beyond that, which its plugin.edn says out loud.
//
// Compiled by `npm run build:plugins`, which concatenates plugins/lib/lt.ts
// ahead of this file — see plugins/README.md for why plugins are global
// scripts rather than modules.

namespace HelloTS {

    /** Whatever is selected, or a greeting when nothing is. */
    function currentText(): string {
        const selection = window.getSelection();
        const selected = selection ? selection.toString() : '';
        return selected || 'Hello from TypeScript';
    }

    LT.command({
        command: 'hello-ts.say-hello',
        desc: 'Hello TS: Say hello',
        exec: function() {
            LT.notify(currentText());
        }
    });

    LT.command({
        command: 'hello-ts.copy-greeting',
        desc: 'Hello TS: Copy greeting to the clipboard',
        exec: function() {
            // Through the bridge, not through Electron. A sandboxed window has
            // no other way, and this is the way that keeps working when it is.
            lt.util.bridge.clipboard.writeText(currentText());
            LT.notify('Copied.');
        }
    });

    /**
     * Plugins are evaluated by `lt.util.load/js` through `window.eval`, and
     * this file is strict-mode, so its top-level `var` bindings stay inside
     * that eval rather than becoming globals. Two TypeScript plugins can each
     * carry their own copy of the helpers without colliding, which is worth
     * having — but it does mean anything a plugin wants to publish has to be
     * assigned somewhere deliberately. `lt.plugins` is where ClojureScript
     * plugins land, so it is where this goes too.
     */
    lt.plugins['hello-ts'] = { version: '0.1.0' };
}
