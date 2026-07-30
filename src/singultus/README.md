# singultus (vendored)

This is third-party code, vendored into Light Table rather than pulled from a
dependency. It is **not** covered by Light Table's MIT license — see below.

## Provenance

* [crate](https://github.com/ibdknox/crate) by Chris Granger — a ClojureScript
  implementation of [Hiccup](https://github.com/weavejester/hiccup).
  crate's README states it is "Distributed under the Eclipse Public License,
  the same as Clojure."
* [singultus](https://github.com/prertik/singultus) — a fork of crate, published
  to Clojars as `org.clojars.prertik/singultus`. The fork declares no license of
  its own, so the EPL terms it inherited from crate are what apply.

Light Table previously consumed the fork as a Maven dependency. It was vendored
here because the fork is unmaintained and single-owner, and because the code
needed fixes that could not be made from outside it (see below). Keeping the
`singultus.*` namespaces means plugins that use `defui`/`defpartial` keep
working unchanged.

Only the namespaces Light Table actually uses were brought over: `core`,
`compiler`, `util`, `binding` and the `def-macros` macros. `element`, `form`,
`page`, `form-macros` and `util-macros` were left behind.

## Local changes

* `core/raw` called `goog.dom/htmlToDocumentFragment`, which has since been
  deleted from the Closure Library, so the fn threw on every call. It is now
  implemented with an inert `<template>` element, which also means the markup
  is parsed without running scripts or fetching subresources.
