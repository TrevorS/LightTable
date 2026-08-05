(ns lt.objs.plugins.edn-format
  "Writing the user plugin's `plugin.edn` back out, as a pure function.

  **Not `lt.objs.plugins.plugin-edn`**, which is what this was called for about
  ten minutes and is a name that cannot exist. `lt.objs.plugins` already has a
  var `plugin-edn`, and ClojureScript munges both a var and a namespace segment
  the same way — so both wanted to be the JavaScript property
  `lt.objs.plugins.plugin_edn`, and one of them won. What that looked like was
  `TypeError: Cannot set properties of undefined (setting 'format_edn')`, thrown
  while `lt.objs.plugins` was initialising, which aborted the namespace and left
  239 of the editor's 573 behaviors unregistered. So: no compiler error, a
  passing `build:cljs`, 327 passing unit tests, and 119 failing integration
  tests whose message was that a settings screen never appeared.

  The six sibling namespaces here — `attribution`, `capabilities`, `scopes`,
  `require-shim`, `local-modules`, `node-modules` — avoid it by not sharing a name
  with any var in the parent. That was luck rather than a rule until now.

  `clojure.string` and nothing else, deliberately: this is one string transform
  and it needs to be testable under plain node. [[lt.objs.plugins]] requires
  `lt.object`, and through it `lt.util.dom`, so a test that reached this there
  would need a DOM and a preload to run at all. `lt.objs.proc.shell-env` and
  `lt.background.rg` are split the same way and for the same reason — and, as
  there, the reason is not tidiness. The bug was in here."
  (:require [clojure.string :as string]))

(defn format-edn
  "`pr-str` output, one key per line, so a diff of the file is readable.

  Until ClojureScript gets `pprint`.

  **The requirement is that it reads back as what went in**, and it did not. Given
  any capture group, `clojure.string/replace` calls a replacement *function* with
  a **vector** of `[match & groups]` rather than with the matched string — and the
  pattern here used to be `#\"(\\\"\\s*,|\\{|\\},)\"`, wrapping the whole
  alternation in a group nothing used. So `#(str % \"\\n\")` stringified a vector,
  and the first time anybody opened the plugin manager their `plugin.edn` became:

  ```
  [\"{\" \"{\"]
  :name \"User[\"\\\",\" \"\\\",\"]
  ```

  A file the user owns, destroyed on first open. Everything after it followed from
  that and named something else: `read-string` on the result returns the leading
  *vector*, so the next read logged `FAILED to load plugin.edn`, and the next save
  threw `Vector's key for assoc must be a number` from an `assoc` onto it —
  surfaced as `Behavior threw: save-user-plugin-dependencies`. Three messages, no
  mention of a missing `first`.

  So: no capture group, and a named argument rather than `%`, because not being
  able to see what `%` was is how this survived."
  [s]
  (-> s
      (string/replace #"\"\s*,|\{|\}," (fn [match] (str match "\n")))
      (string/replace-first #"^\{\n" "{")
      (string/replace-first #":dependencies"
                            ";; Do not edit - :dependencies are auto-generated\n:dependencies")))
