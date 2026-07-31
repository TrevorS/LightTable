# Elixir

Elixir support for Light Table, through
[`elixir-ls`](https://microsoft.github.io/language-server-protocol/implementors/servers/).

One declaration and no code.

## Installing the server

    see github.com/elixir-lsp/elixir-ls

Light Table does not download it. If it is not on `PATH`, the console says
so by name.

## Changing it

`:lt.objs.editor.lsp/language-servers` is a `:user` behavior and a later
declaration wins, so point it somewhere else from `user.behaviors` rather
than editing this plugin.
