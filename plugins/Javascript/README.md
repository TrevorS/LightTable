##JavaScript for Light Table

The official JavaScript language plugin for Light Table.

### For Committers

* See package.json for node dependencies. `script/install-plugin-deps.js`
  installs them at build time; node_modules is not committed. See VENDORED.md.
* The CodeMirror mode this used to ship is gone — Light Table bundles all of
  CodeMirror's modes now.
* We rely on acorn for parsing JS. See [its readme](https://github.com/acornjs/acorn#main-parser) for
  several parsing options

###License

Copyright (C) 2013 Kodowa Inc.

Distributed under the MIT license, see LICENSE.md for the full text.
