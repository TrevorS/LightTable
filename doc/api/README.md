# API reference

The namespaces a plugin is written against. Generated from the source by
`script/gen-api-docs.js`, so anything here is what the editor on `develop`
actually defines; if a docstring is missing below it is missing in the source.

This is the plugin-facing surface, not every public var in Light Table. A
namespace outside this list can still be required, but nothing promises it
will keep its shape.

| | | |
|---|---|---|
| [`lt.macros`](lt.macros.md) | Macros used across LT | 2 vars |
| [`lt.object`](lt.object.md) | Define core of BOT architecture and provide fns for manipulating objects… | 35 vars |
| [`lt.objs.command`](lt.objs.command.md) | Provide command manager and command related fns | 5 vars |
| [`lt.objs.editor`](lt.objs.editor.md) | Provide fns and behaviors for interfacing with an editor object. | 80 vars |
| [`lt.objs.editor.pool`](lt.objs.editor.pool.md) | Provide manager for managing a pool of editors and several misc editor commands | 6 vars |
| [`lt.objs.files`](lt.objs.files.md) | Provide fns for doing file related operations. | 42 vars |
| [`lt.objs.notifos`](lt.objs.notifos.md) | Provide fns for displaying messages and spinner in bottom statusbar | 3 vars |

See also [Behaviors, Objects and Tags](../BOT.md) for the model these
namespaces implement, and
[plugins/README.md](https://github.com/TrevorS/LightTable/blob/develop/plugins/README.md)
for how a plugin is packaged.
