# Go

Go support for Light Table, through
[`gopls`](https://microsoft.github.io/language-server-protocol/implementors/servers/).

This plugin is one declaration and no code. Every language-server surface the
editor has arrives with it, because none of those behaviors knows which
language it is for.

## Installing the server

Light Table does not fetch it — a language server is a tool that belongs to
your machine, not to the editor. To install: go install golang.org/x/tools/gopls@latest.

If it is not on `PATH`, Light Table says so by name rather than failing
quietly.

## Changing it

The declaration is data, and `:lt.objs.editor.lsp/language-servers` is a
`:user` behavior where a later declaration wins. To point at a different
binary, add your own to `user.behaviors` — you do not have to edit this
plugin, and you will not lose the change when it updates.
