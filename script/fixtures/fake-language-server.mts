#!/usr/bin/env node

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

/** Just enough of a JSON-RPC message for what this answers. */
interface LspMessage {
    id?: number;
    method?: string;
    params?: any;
}

const CHANGE_INCREMENTAL = 2;

// Two of these run at once in the two-servers-per-language check, so an
// instance can be told who it is. `--source` names it in every diagnostic it
// publishes and `--line` puts them somewhere the other one is not, which is
// what makes "both are being listened to" an assertion rather than a guess.
// `--diagnostics-only` advertises nothing but synchronisation, so the surfaces
// it does not offer have to be routed past it.
const args = process.argv.slice(2);
const flag = (name: string, fallback: string): string => {
    const at = args.indexOf(name);
    return at === -1 ? fallback : args[at + 1];
};
const SOURCE = flag('--source', 'fake');
const LINE = Number(flag('--line', '0'));
const DIAGNOSTICS_ONLY = args.includes('--diagnostics-only');

let buffer = Buffer.alloc(0);
let changes = 0;
let lastText = '';
let lastVersion = 0;
let lastCommand = '';

function send(message: Record<string, unknown>): void {
    const body = Buffer.from(JSON.stringify(Object.assign({ jsonrpc: '2.0' }, message)), 'utf8');
    // Bytes, not characters — the trap this whole layer exists to avoid.
    process.stdout.write('Content-Length: ' + body.length + '\r\n\r\n');
    process.stdout.write(body);
}

/** One diagnostic per line, saying what this server has been told so far. */
function publish(uri: string): void {
    send({
        method: 'textDocument/publishDiagnostics',
        params: {
            uri: uri,
            diagnostics: [
                {
                    range: { start: { line: LINE, character: 0 }, end: { line: LINE, character: 1 } },
                    message: 'changes=' + changes + ' version=' + lastVersion +
                             ' last=' + JSON.stringify(lastText) +
                             (lastCommand ? ' ran=' + lastCommand : ''),
                    severity: 1,
                    source: SOURCE
                },
                // A second diagnostic on the same line, so grouping is
                // exercised: several diagnostics on one line must become one
                // widget holding several messages, not several widgets.
                {
                    range: { start: { line: LINE, character: 0 }, end: { line: LINE, character: 1 } },
                    message: 'a second opinion about the same line',
                    severity: 2,
                    source: SOURCE
                },
                // And one further down, so there is more than one widget to
                // clear when the next publish replaces these.
                {
                    range: { start: { line: LINE + 1, character: 0 }, end: { line: LINE + 1, character: 1 } },
                    message: 'about the next line',
                    severity: 3,
                    source: SOURCE
                }
            ]
        }
    });
}

function handle(msg: LspMessage): void {
    switch (msg.method) {
    case 'initialize':
        return send({
            id: msg.id,
            result: {
                capabilities: Object.assign(
                    { textDocumentSync: { openClose: true, change: CHANGE_INCREMENTAL } },
                    // Enough for the client to earn its :formattable tag and
                    // send a request worth answering.
                    DIAGNOSTICS_ONLY ? {} : {
                        documentFormattingProvider: true,
                        codeActionProvider: true,
                        hoverProvider: true
                    }),
                serverInfo: { name: 'fake-language-server (' + SOURCE + ')' }
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
    case 'textDocument/formatting':
        // One edit, replacing the first line, so a test can tell the
        // difference between "applied" and "did nothing" without depending on
        // anybody's formatting opinion. The options are echoed back through
        // the edit text, because the client is supposed to send what the
        // editor is configured with rather than a guess.
        return send({
            id: msg.id,
            result: [{
                // Past the end of the line on purpose: a character offset
                // beyond it clamps, and the alternative is a fixture that
                // breaks whenever the file it formats gains a character.
                range: { start: { line: 0, character: 0 }, end: { line: 0, character: 10000 } },
                newText: 'FORMATTED tabSize=' + msg.params.options.tabSize +
                         ' insertSpaces=' + msg.params.options.insertSpaces
            }]
        });
    case 'textDocument/hover':
        // Markdown in an object, which is what a modern server sends and the
        // shape with the most ways to be read wrong — the other two legal ones
        // are a bare string and an array. The position is echoed back so a test
        // can tell a hover that answered the cursor from one that answered
        // wherever the client felt like asking.
        return send({
            id: msg.id,
            result: {
                contents: {
                    kind: 'markdown',
                    value: 'HOVER at ' + msg.params.position.line + ':' +
                           msg.params.position.character
                }
            }
        });
    case 'textDocument/codeAction':
        // Two, so the client has to offer a choice rather than apply the only
        // one. The first carries an edit; the second carries a command, which
        // is the other half of the shape a server may send.
        return send({
            id: msg.id,
            result: [
                {
                    title: 'Fix the first line',
                    kind: 'quickfix',
                    edit: {
                        changes: {
                            [msg.params.textDocument.uri]: [{
                                range: { start: { line: 0, character: 0 },
                                         end: { line: 0, character: 10000 } },
                                // How many diagnostics the client sent back
                                // with the request: a fix is a fix *for* a
                                // diagnostic, so a client that forgets to
                                // include them gets nothing from a real server.
                                newText: 'FIXED withDiagnostics=' +
                                         (msg.params.context.diagnostics || []).length
                            }]
                        }
                    }
                },
                { title: 'Run a command instead',
                  kind: 'refactor',
                  command: { command: 'fake.doSomething', arguments: [1, 2] } }
            ]
        });
    case 'workspace/executeCommand':
        lastCommand = msg.params.command;
        return send({ id: msg.id, result: null });
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
