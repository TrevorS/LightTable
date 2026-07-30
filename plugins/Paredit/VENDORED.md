# Paredit, in this repository

Copied from [LightTable/Paredit](https://github.com/LightTable/Paredit) at
version 0.0.4, under its own MIT licence (`LICENSE.md`), which is not this
project's.

Upstream shipped `paredit_compiled.js`, a checked-in build artifact that
nothing rebuilt — the same arrangement that left the default user plugin
calling `crate.core/html` for years after that rename. Here the ClojureScript
is a module of the `:app` build, so it compiles against the editor it extends
and a rename in Light Table fails the build.

One change to the source, which is what compiling it against the editor turned
up on the first attempt: `batched-edits` calls `do-edit` above the `defmulti`
that defines it, so ClojureScript reports an undeclared var. Added a `declare`.
Upstream ships with that warning unaddressed, which is the sort of thing an
unrebuilt artifact hides.

Otherwise unmodified. If upstream moves, the diff should be readable.
