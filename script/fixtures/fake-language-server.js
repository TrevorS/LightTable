#!/usr/bin/env node
/*jshint esversion: 8 */
"use strict";

// A language server that exists to be talked to.
//
// The LSP client's framing and arithmetic have unit tests; what they cannot
// reach is the wiring — which behavior gets which argument, whether a widget
// can actually be built, whether a notification leaves the process. Both bugs
// found in the first diagnostics slice lived exactly there, and both were
// silent: Light Table catches exceptions inside behavior reactions, so a
// `didChange` that was never sent looked identical to one that was. The
// document version incremented either way.
//
// So this answers like a real server and *reports back what it was told*. Its
// diagnostics say how many changes arrived, what the last one contained, and
// at which version — which means the smoke test can assert on synchronisation
// rather than on the mere presence of a message.
//
// It speaks stdio, like every server Light Table spawns.

const CHANGE_INCREMENTAL = 2;

let buffer = Buffer.alloc(0);
let changes = 0;
let lastText = '';
let lastVersion = 0;

function send(message) {
    const body = Buffer.from(JSON.stringify(Object.assign({ jsonrpc: '2.0' }, message)), 'utf8');
    // Bytes, not characters — the trap this whole layer exists to avoid.
    process.stdout.write('Content-Length: ' + body.length + '\r\n\r\n');
    process.stdout.write(body);
}

/** One diagnostic per line, saying what this server has been told so far. */
function publish(uri) {
    send({
        method: 'textDocument/publishDiagnostics',
        params: {
            uri: uri,
            diagnostics: [
                {
                    range: { start: { line: 0, character: 0 }, end: { line: 0, character: 1 } },
                    message: 'changes=' + changes + ' version=' + lastVersion +
                             ' last=' + JSON.stringify(lastText),
                    severity: 1,
                    source: 'fake'
                },
                // A second diagnostic on the same line, so grouping is
                // exercised: several diagnostics on one line must become one
                // widget holding several messages, not several widgets.
                {
                    range: { start: { line: 0, character: 0 }, end: { line: 0, character: 1 } },
                    message: 'a second opinion about the same line',
                    severity: 2,
                    source: 'fake'
                },
                // And one further down, so there is more than one widget to
                // clear when the next publish replaces these.
                {
                    range: { start: { line: 1, character: 0 }, end: { line: 1, character: 1 } },
                    message: 'about the second line',
                    severity: 3,
                    source: 'fake'
                }
            ]
        }
    });
}

function handle(msg) {
    switch (msg.method) {
    case 'initialize':
        return send({
            id: msg.id,
            result: {
                capabilities: { textDocumentSync: { openClose: true, change: CHANGE_INCREMENTAL } },
                serverInfo: { name: 'fake-language-server' }
            }
        });
    case 'textDocument/didOpen':
        lastVersion = msg.params.textDocument.version;
        lastText = msg.params.textDocument.text.split('\n')[0];
        return publish(msg.params.textDocument.uri);
    case 'textDocument/didChange': {
        changes += 1;
        lastVersion = msg.params.textDocument.version;
        const last = msg.params.contentChanges[msg.params.contentChanges.length - 1];
        // A full-text change has no range. Saying which arrived is the point:
        // sending the kind the server did not ask for is a mistake that works
        // until it does not.
        lastText = last && last.range ? last.text : '(full text)';
        return publish(msg.params.textDocument.uri);
    }
    case 'shutdown':
        return send({ id: msg.id, result: null });
    case 'exit':
        return process.exit(0);
    default:
        // A request we do not implement still needs an answer; a notification
        // does not. Answering nothing to a request is how a client hangs.
        if (msg.id !== undefined) {
            send({ id: msg.id, error: { code: -32601, message: 'not implemented: ' + msg.method } });
        }
    }
}

process.stdin.on('data', function (chunk) {
    buffer = Buffer.concat([buffer, chunk]);
    for (;;) {
        const headerEnd = buffer.indexOf('\r\n\r\n');
        if (headerEnd === -1) return;
        const header = buffer.slice(0, headerEnd).toString('ascii');
        const match = /content-length:\s*(\d+)/i.exec(header);
        if (!match) return process.exit(2);
        const start = headerEnd + 4;
        const length = Number(match[1]);
        if (buffer.length < start + length) return;
        const body = buffer.slice(start, start + length).toString('utf8');
        buffer = buffer.slice(start + length);
        try { handle(JSON.parse(body)); } catch (e) { /* a bad frame costs only itself */ }
    }
});

process.stdin.on('end', function () { process.exit(0); });
