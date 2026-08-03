# Lua

Lua support for Light Table, through
[`lua-language-server`](https://github.com/LuaLS/lua-language-server).

One declaration and no code. The colouring is tree-sitter's — see
`lt.objs.editor.treesitter` — so this plugin is only what comes after it.

## Installing the server

    brew install lua-language-server, or your package manager

Light Table does not download it. If it is not on `PATH`, the console says
so by name.

## Changing it

`:lt.objs.editor.lsp/language-servers` is a `:user` behavior and a later
declaration wins, so point it somewhere else from `user.behaviors` rather
than editing this plugin.
