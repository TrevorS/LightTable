# JSON

JSON support for Light Table, through
[`vscode-json-language-server`](https://microsoft.github.io/language-server-protocol/implementors/servers/).

One declaration and no code.

## Installing the server

    npm i -g vscode-langservers-extracted

Light Table does not download it. If it is not on `PATH`, the console says
so by name.

## Changing it

`:lt.objs.editor.lsp/language-servers` is a `:user` behavior and a later
declaration wins, so point it somewhere else from `user.behaviors` rather
than editing this plugin.
