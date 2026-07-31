#!/usr/bin/env node

// Stamp out a plugin that teaches Light Table a language.
//
//     node script/new-language-plugin.mts Go \
//       --command gopls --root go.mod,go.work \
//       --install "go install golang.org/x/tools/gopls@latest"
//
// A plugin rather than a line in core's default.behaviors, even though the
// content is four lines of data: a language should be something you can turn
// off, replace, or take with you, and that is what a plugin is.
//
// It writes a plugin.edn, a .behaviors holding one declaration, and a README.
// No code — a language server needs none, and every LSP surface arrives at
// once because none of those behaviors knows what language it is for.
//
// The tag has to exist in core's file-types table or the plugin attaches to
// nothing, so this checks.

import * as fs from 'node:fs';
import * as path from 'node:path';
import { parseArgs } from 'node:util';
import { ROOT } from './lib/paths.mts';

const BEHAVIORS = path.join(ROOT, 'deploy', 'settings', 'default', 'default.behaviors');

interface Options {
    name: string;
    tag: string;
    languageId: string;
    command: string;
    args: string[];
    root: string[];
    install: string;
}

function usage(message?: string): never {
    if (message) console.error(message + '\n');
    console.error(`usage: node script/new-language-plugin.mts <Name> [options]

  --tag          the editor tag, e.g. editor.go   (default: editor.<name lowercased>)
  --language-id  the LSP language id              (default: <name lowercased>)
  --command      the server executable            (required)
  --args         comma-separated server arguments (default: none)
  --root         comma-separated project markers  (required)
  --install      how a person installs the server, for the README and comments
`);
    process.exit(1);
}

/** Is `tag` something core's file-types table actually puts on an editor? */
function tagIsKnown(tag: string): boolean {
    if (!fs.existsSync(BEHAVIORS)) return true;
    return fs.readFileSync(BEHAVIORS, 'utf8').includes(`:${tag}]`);
}

function edn(strings: string[]): string {
    return strings.map((s) => JSON.stringify(s)).join(' ');
}

function parse(): Options {
    const { values, positionals } = parseArgs({
        args: process.argv.slice(2),
        options: {
            tag: { type: 'string' },
            'language-id': { type: 'string' },
            command: { type: 'string' },
            args: { type: 'string' },
            root: { type: 'string' },
            install: { type: 'string' }
        },
        allowPositionals: true
    });

    const name = positionals[0];
    if (!name) usage('Which language?');
    if (!values.command) usage('--command is required: a plugin with no server declares nothing.');
    if (!values.root) usage('--root is required: without it the server is handed the file\'s own directory.');

    const lower = name.toLowerCase();
    return {
        name,
        tag: values.tag || `editor.${lower}`,
        languageId: values['language-id'] || lower,
        command: values.command,
        args: values.args ? values.args.split(',') : [],
        root: values.root.split(','),
        install: values.install || `install ${values.command} and put it on PATH`
    };
}

function main(): void {
    const o = parse();
    const dir = path.join(ROOT, 'plugins', o.name);
    if (fs.existsSync(dir)) usage(`plugins/${o.name} already exists.`);

    if (!tagIsKnown(o.tag)) {
        console.error(
            `No editor carries :${o.tag}.\n\n` +
            `Tags come from core's file-types table in\n  ${path.relative(ROOT, BEHAVIORS)}\n` +
            `Add an entry there for this language's extensions first, or pass --tag with one that exists.`);
        process.exit(1);
    }

    fs.mkdirSync(dir, { recursive: true });
    const lower = o.name.toLowerCase();

    fs.writeFileSync(path.join(dir, 'plugin.edn'),
`{:name "${o.name}"
 :version "0.1.0"
 :author "Light Table"
 :desc "${o.name} support, through ${o.command}."
 :source "https://github.com/TrevorS/LightTable"
 :behaviors "${lower}.behaviors"

 ;; None. The editor spawns the language server, not this plugin.
 :capabilities #{}}
`);

    fs.writeFileSync(path.join(dir, `${lower}.behaviors`),
`{;; ${o.name}, as data. No code in this plugin.
 :+ {:lsp.client [(:lt.objs.editor.lsp/language-servers
                   [{:tags [:${o.tag}]
                     :language-id "${o.languageId}"
                     :root [${edn(o.root)}]
                     :command "${o.command}"
                     :args [${edn(o.args)}]
                     :install "${o.install}"}])]}}
`);

    fs.writeFileSync(path.join(dir, 'README.md'),
`# ${o.name}

${o.name} support for Light Table, through
[\`${o.command}\`](https://microsoft.github.io/language-server-protocol/implementors/servers/).

One declaration and no code.

## Installing the server

    ${o.install}

Light Table does not download it. If it is not on \`PATH\`, the console says
so by name.

## Changing it

\`:lt.objs.editor.lsp/language-servers\` is a \`:user\` behavior and a later
declaration wins, so point it somewhere else from \`user.behaviors\` rather
than editing this plugin.
`);

    console.log(`plugins/${o.name}: plugin.edn, ${lower}.behaviors, README.md`);
    console.log('Nothing to build — run `npm run build:plugins` to place it.');
}

main();
