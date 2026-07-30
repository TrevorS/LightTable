// An empty stand-in for node's `fs`, for the window's bundle only.
//
// web-tree-sitter is emscripten output, and emscripten emits one module that
// works in both node and a browser by branching at runtime:
//
//     if (ENVIRONMENT_IS_NODE) { var fs = require("fs"); ... }
//
// The branch is dead in the window — that is what context isolation means, and
// `ENVIRONMENT_IS_NODE` is false there — but a bundler resolves requires
// statically and cannot know that, so the build fails on a module the running
// code never touches.
//
// shadow-cljs points `fs` here for the `:app` build only. If something in the
// window ever does reach for `fs` it gets an object with nothing on it, which
// is the truthful answer: the window has no filesystem. It has
// `lt.util.bridge.files`.
//
// The worker build is unaffected — it keeps `fs` as a real require, because it
// is a node process and actually has one.
//
// Plain JavaScript rather than TypeScript compiled into src-gen: shadow-cljs
// resolves this path directly, and a generated file would make a clean
// checkout's first build depend on the order of two build steps.
module.exports = {};
