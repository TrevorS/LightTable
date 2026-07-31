# TOML

TOML support for Light Table, through
[`taplo`](https://microsoft.github.io/language-server-protocol/implementors/servers/).

One declaration and no code.

## Installing the server

    cargo install taplo-cli --features lsp, or brew install taplo

Light Table does not download it. If it is not on `PATH`, the console says
so by name.

## Changing it

`:lt.objs.editor.lsp/language-servers` is a `:user` behavior and a later
declaration wins, so point it somewhere else from `user.behaviors` rather
than editing this plugin.
