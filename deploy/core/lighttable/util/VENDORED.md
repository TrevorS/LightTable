# keyevents.js — Mousetrap, forked

[Mousetrap](https://github.com/ccampbell/mousetrap) 1.6.5 by Craig Campbell,
Apache 2.0 (see the header in the file), with Light Table's changes.

## Why it is a fork rather than a dependency

Light Table needs the character from a `keypress` paired with the keycode from
the `keydown` that produced it, and it needs `keyup` handling that Mousetrap
does not expose. That means rewriting the dispatch inside `_handleKeyEvent`, a
private function — so the npm package cannot be used with an adapter on top.

## What was changed

Every change is inside a `/** START LIGHT TABLE ... **/` block. There is no
unmarked drift, which is the property worth keeping: it means the fork can be
compared against upstream mechanically.

```sh
npm pack mousetrap@1.6.5 && tar xzf mousetrap-1.6.5.tgz
diff -u package/mousetrap.js deploy/core/lighttable/util/keyevents.js
```

Every line of that diff is inside a marked block.

1. **`keyDownOnly`** — whether an event is a key that only produces `keydown`.
2. **`_handleKeyUp`** — a hook to override, which upstream has no equivalent of.
3. **The `_handleKeyEvent` dispatch** — routes `keydown`, `keypress` and `keyup`
   separately so a character can be paired with its keycode.
4. **`Mousetrap.prototype.handleKeyUp`** — publishes the hook, the way upstream
   publishes `handleKey`. `lt.objs.keyboard` replaces both.
5. **`keydown` is listened for in the capture phase**, with a `capture` argument
   added to `_addEvent` to say so. Upstream listens on the bubble, which was fine
   while CodeMirror 5 read input through a hidden textarea; CodeMirror 6's handler
   is on `.cm-content`, deeper than this document listener, so on the bubble it
   ran first — and `cmd-enter` inserted a newline, moved the cursor to it, and
   *then* let `:eval-editor-form` read the cursor and evaluate the blank line it
   had been moved to. Capture makes the keymap authoritative, which is what it is
   for; typing is unaffected because of deviation 3, and an unbound key prevents
   nothing so the editor still gets it.

## Upgrading

This was 1.6.0 until it was moved to 1.6.5 by re-applying the blocks above
onto the newer upstream. It was cheap: 1.6.0 → 1.6.5 is 28 lines, and one of the
two substantive changes was a numpad fix
([PR #258](https://github.com/ccampbell/mousetrap/pull/258)) that Light Table
had already backported as an unmarked inline edit — so upgrading *removed* a
deviation rather than adding one. The other adds shadow DOM `composedPath`
support.

Do the same next time: apply the marked blocks to the new upstream, then check
that the diff is still only those blocks. `script/smoke-test.sh` dispatches a
real key event and asserts it reaches Light Table's handler, which is what
catches a dispatch rewrite that did not land correctly.

## Porting it to TypeScript

Considered, and deliberately not done. The rest of this project's hand-written
JavaScript is being ported, but that argument does not apply here: this file is
not edited, and typing it would destroy the only thing keeping it maintainable —
a mechanical diff against upstream. The same reasoning keeps the forked
CodeMirror addons as they are.
