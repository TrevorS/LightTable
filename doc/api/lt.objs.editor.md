# lt.objs.editor

Provide fns and behaviors for interfacing with an editor object.

The editor is CodeMirror 6 wearing CodeMirror 5's method names — see
src-window/cm6-editor.ts for why, and note that the documentation links below
point at CodeMirror 5's manual because that is what the names mean.

Editor objects are frequently used as arguments for functions, but often only the internal
CodeMirror object is actually used. Where the following documentation referers to the editor,
it is informally referring to the editor's CodeMirror object.

Commonly encountered argument names:

* `e` - Editor
* `v` - Value
* `m` - Map
* `cm` - CodeMirror object
* `opts` - Options
* `ev` - Event Handler
* `pos` - Position: depending on the context, either a Javascript object
          (e.g., `{"line": 0, "ch": 0}`) or cljs map (e.g., `{:line 0 :ch 0}`).

Source: [`src/lt/objs/editor.cljs`](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs)

[← API index](README.md)

| | |
|---|---|
| [`->cm-ed`](#var-cm-ed) | Return editor `e`'s CodeMirror object. |
| [`->cursor`](#var-cursor) | Same as [`cursor`](#var-cursor-2) but returned as edn. |
| [`->elem`](#var-elem) | Return DOM element of editor `e`'s CodeMirror object |
| [`->generation`](#var-generation) | Returns an integer that can be used to test if edits have occurred. |
| [`->mode`](#var-mode) | Return outer mode object for editor `e`. |
| [`->token`](#var-token) | Returns token located as `pos` within editor `e`. |
| [`->token-type`](#var-token-type) | Return the type of token located at position `pos` for editor `e`. |
| [`->val`](#var-val) | Return editor `e`'s buffer content. |
| [`-line-class`](#var-line-class) | Remove CSS class name `class` from LineHandle `lh` at element `plane` for editor `e`. |
| [`+line-class`](#var-line-class-2) | Add CSS class name `class` to LineHandle `lh` at element `plane` for editor `e`. |
| [`add-gutter`](#var-add-gutter) | Add gutter with `class-name` of specified `width` to editor `e`. |
| [`adjust-loc`](#var-adjust-loc) | Adjust position `loc` with integer offset `dir` and the key `axis`. |
| [`block-comment`](#var-block-comment) | Wrap lines within range of `from` and `to` for editor `e`. |
| [`blur`](#var-blur) | Blurs input field `e`. |
| [`bookmark`](#var-bookmark) | Insert bookmark at position `from` for widget `widg`. |
| [`center-cursor`](#var-center-cursor) | Scrolls editor `ed` to the cursor and places it in the center of screen. |
| [`char-coords`](#var-char-coords) | Returns position and dimension, based off of `pos` for editor `e`, in map consisting of `{:left :right :top :bottom}`. |
| [`clear-history`](#var-clear-history) | Clear the history of editor `e`. |
| [`clear-search!`](#var-clear-search) | Forget the query, which takes the match highlighting with it. |
| [`copy`](#var-copy) | Copies currently selected text from editor. |
| [`cursor`](#var-cursor-2) | Return cursor position of editor `e`'s as js object. |
| [`cut`](#var-cut) | Cut currently selected text from editor. |
| [`dirty?`](#var-dirty) | Returns true if document is not clean for generation `gen`. |
| [`exec-command!`](#var-exec-command) | Run the CodeMirror command named `cmd` on editor `e`. |
| [`find-marks`](#var-find-marks) | Returns marks found at `pos` in . |
| [`find-next!`](#var-find-next) | Move to the next match, or the previous one when `reverse?`. |
| [`find!`](#var-find) | Search editor `e` for `query` and move to the first match. |
| [`first-line`](#var-first-line) | Returns the first line of editor `e`. |
| [`focus`](#var-focus) | Return focus of editor. |
| [`fold-code`](#var-fold-code) | Attempts to fold code starting at position `loc`. |
| [`get-char`](#var-get-char) | Returns the characters found from integer offest `dir` to the current cursor position. |
| [`get-history`](#var-get-history) | Returns the history of editor `e`. |
| [`indent-line`](#var-indent-line) | Indents the line `l` based on the `dir` specified for editor `e`. |
| [`indent-lines`](#var-indent-lines) | Indents lines within the range resulting from `from` and `to` based on the `dir` specified… |
| [`indent-selection`](#var-indent-selection) | Intent current selection in editor `e` by integer offset `dir`. |
| [`input-field`](#var-input-field) | Return input field element of editor. |
| [`insert-at-cursor`](#var-insert-at-cursor) | Insert into editor `ed` text `s` at cursor's position. |
| [`last-line`](#var-last-line) | Returns the last line of editor `e`. |
| [`lh->line`](#var-lh-line) | Given LineHandle object `lh`, returns integer for corresponding line from editor `e`. |
| [`line`](#var-line) | Returns the content of line `l` from editor `e`. |
| [`line-comment`](#var-line-comment) | Changes lines within range of `from` and `to` into line comments for editor `e`. |
| [`line-count`](#var-line-count) | Returns the number of lines in the editor. |
| [`line-handle`](#var-line-handle) | Returns `LineHandle` object from editor `e` for line `l`. |
| [`line-length`](#var-line-length) | Returns the length of line `l` from editor `e`. |
| [`line-widget`](#var-line-widget) | Add line widget `elem` (an element), along with any options, at `line` to editor `e`. |
| [`mark`](#var-mark) | Marks text in editor `e` within range of `from` and `to`. |
| [`move-cursor`](#var-move-cursor) | Moves editor `ed`'s cursor to position `pos`. |
| [`off`](#var-off) | Remove event handler `ev`, which fires `func`, on editor `ed`'s CodeMirror object. |
| [`on`](#var-on) | Register event handler `ev`, which fires `func`, on editor `ed`'s CodeMirror object. |
| [`on-click`](#var-on-click) | Add function `func` to trigger when `:mousedown` fires. |
| [`operation`](#var-operation) | Returns `e` rather than the return value of your function `func`. |
| [`option`](#var-option) | Return value for option name `o` on editor `e`. |
| [`paste`](#var-paste) | Paste into editor's current cursor position |
| [`pos->index`](#var-pos-index) | Returns integer based on position `pos` from editor's CodeMirror Object. |
| [`range`](#var-range) | Returns text between positions `from` and `to`. |
| [`redo`](#var-redo) | Redo one edit for editor `e`, if any exist. |
| [`refresh`](#var-refresh) | Refreshes editor. |
| [`remove-gutter`](#var-remove-gutter) | Remove gutter with `class-name` from editor `e`. |
| [`remove-line-widget`](#var-remove-line-widget) | Remove widget `widg` from editor `e`. |
| [`replace`](#var-replace) | Replace text starting at position `from` for editor with text `v`. |
| [`replace-selection`](#var-replace-selection) | Replace selection with `neue` for editor `e`. |
| [`replace!`](#var-replace-2) | Replace the current match with `text`, or every match when `all?`. |
| [`scratch`](#var-scratch) | A detached editor holding `text`, for code that needs somewhere to put marks… |
| [`scroll-to`](#var-scroll-to) | Scroll editor to pixel position `x`,`y`. |
| [`select-all`](#var-select-all) | Select all lines from editor `e`. |
| [`selection`](#var-selection) | Returns currently selected text in editor. |
| [`selection-bounds`](#var-selection-bounds) | When text is selected, returns position `{:from x :to y}` where `x` and `y` are the cursor's start and end values. |
| [`selection?`](#var-selection-2) | True if text is selected in editor. |
| [`set-doc!`](#var-set-doc) | Show `doc`'s text in editor `e`, and remember which document it is. |
| [`set-history`](#var-set-history) | Set the history of editor `e` with provided value `v`. |
| [`set-line`](#var-set-line) | Replace content at line `l` with `text` for editor `e`. |
| [`set-mode`](#var-set-mode) | Set mode option for editor `e`. |
| [`set-options`](#var-set-options) | Given a map of options, set each pair as an option on editor `e`'s… |
| [`set-selection`](#var-set-selection) | Sets editor's selection to `start` and `end` positions. |
| [`set-val`](#var-set-val) | Set content value `v` of editor `e`'s CodeMirror object. |
| [`set-val-and-keep-cursor`](#var-set-val-and-keep-cursor) | Same as [`set-val`](#var-set-val) but current cursor position is kept. |
| [`toggle-comment`](#var-toggle-comment) | Toggle comment and if multiline toggle apply block comment |
| [`uncomment`](#var-uncomment) | Attempts to uncomment lines within range of `from` and `to` for editor `e`. |
| [`undo`](#var-undo) | Undo one edit for editor `e`, if any exist. |

## Vars

<a id="var-cm-ed"></a>

### `->cm-ed`

```clojure
(->cm-ed e)
```

Return editor `e`'s CodeMirror object.

Hinted ^js so that the compiler can resolve the methods called on the result,
rather than warning at every one of them.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L36)

<a id="var-cursor"></a>

### `->cursor`

```clojure
(->cursor e & [side])
```

Same as [`cursor`](#var-cursor-2) but returned as edn.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L207)

<a id="var-elem"></a>

### `->elem`

```clojure
(->elem e)
```

Return DOM element of editor `e`'s CodeMirror object

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L46)

<a id="var-generation"></a>

### `->generation`

```clojure
(->generation e)
```

Returns an integer that can be used to test if edits have occurred.

See [changeGeneration](http://codemirror.net/doc/manual.html#changeGeneration).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L636)

<a id="var-mode"></a>

### `->mode`

```clojure
(->mode e)
```

Return outer mode object for editor `e`.

See [getMode](http://codemirror.net/doc/manual.html#getMode).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L262)

<a id="var-token"></a>

### `->token`

```clojure
(->token e pos)
```

Returns token located as `pos` within editor `e`.

See [getTokenAt](http://codemirror.net/doc/manual.html#getTokenAt).

Built field by field rather than with `js->clj`, which is what this did and
which silently returned nothing: CodeMirror constructs a token with `new
Token(...)`, and `js->clj` converts *plain* objects only — a class instance
comes back unchanged, so every `(:string token)` in the editor was nil. That
took out `find-symbol-at-cursor`, and with it documentation and
jump-to-definition, for every language that asked.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L168)

<a id="var-token-type"></a>

### `->token-type`

```clojure
(->token-type e pos)
```

Return the type of token located at position `pos` for editor `e`.

See [getTokenTypeAt](http://codemirror.net/doc/manual.html#getTokenTypeAt).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L188)

<a id="var-val"></a>

### `->val`

```clojure
(->val e)
```

Return editor `e`'s buffer content.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L163)

<a id="var-line-class"></a>

### `-line-class`

```clojure
(-line-class e lh plane class)
```

Remove CSS class name `class` from LineHandle `lh` at element `plane` for editor `e`.
Opposite of `+line-class`.

See [removeLineClass](http://codemirror.net/doc/manual.html#removeLineClass).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L553)

<a id="var-line-class-2"></a>

### `+line-class`

```clojure
(+line-class e lh plane class)
```

Add CSS class name `class` to LineHandle `lh` at element `plane` for editor `e`.

See [addLineClass](http://codemirror.net/doc/manual.html#addLineClass).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L546)

<a id="var-add-gutter"></a>

### `add-gutter`

```clojure
(add-gutter e class-name width)
```

Add gutter with `class-name` of specified `width` to editor `e`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L687)

<a id="var-adjust-loc"></a>

### `adjust-loc`

```clojure
(adjust-loc loc dir)
(adjust-loc loc dir axis)
```

Adjust position `loc` with integer offset `dir` and the key `axis`. Axis should either be `:line` or `:ch`.
If `axis` is not specified, defaults to `:ch`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L561)

<a id="var-block-comment"></a>

### `block-comment`

```clojure
(block-comment e from to opts)
```

Wrap lines within range of `from` and `to` for editor `e`.

See [blockComment](http://codemirror.net/doc/manual.html#blockComment).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L621)

<a id="var-blur"></a>

### `blur`

```clojure
(blur e)
```

Blurs input field `e`. Returns `e`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L284)

<a id="var-bookmark"></a>

### `bookmark`

```clojure
(bookmark e from widg)
```

Insert bookmark at position `from` for widget `widg`.

See [setBookmark](http://codemirror.net/doc/manual.html#setBookmark).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L240)

<a id="var-center-cursor"></a>

### `center-cursor`

```clojure
(center-cursor ed)
```

Scrolls editor `ed` to the cursor and places it in the center of screen.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L348)

<a id="var-char-coords"></a>

### `char-coords`

```clojure
(char-coords e pos)
```

Returns position and dimension, based off of `pos` for editor `e`, in map consisting of `{:left :right :top :bottom}`.

See [charChords](http://codemirror.net/doc/manual.html#charCoords).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L421)

<a id="var-clear-history"></a>

### `clear-history`

```clojure
(clear-history e)
```

Clear the history of editor `e`. Returns `e`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L72)

<a id="var-clear-search"></a>

### `clear-search!`

```clojure
(clear-search! e)
```

Forget the query, which takes the match highlighting with it.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L468)

<a id="var-copy"></a>

### `copy`

```clojure
(copy e)
```

Copies currently selected text from editor.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L405)

<a id="var-cursor-2"></a>

### `cursor`

```clojure
(cursor e)
(cursor e side)
```

Return cursor position of editor `e`'s as js object. Returns JSON not edn...
use [`->cursor`](#var-cursor) for edn.

Example:
```
(cursor e)
;;=> {"line": 144, "ch": 9}
```

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L195)

<a id="var-cut"></a>

### `cut`

```clojure
(cut e)
```

Cut currently selected text from editor.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L410)

<a id="var-dirty"></a>

### `dirty?`

```clojure
(dirty? e gen)
```

Returns true if document is not clean for generation `gen`. The document is not clean if it has been modified since it was in a clean state.

See [isClean](http://codemirror.net/doc/manual.html#isClean).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L643)

<a id="var-exec-command"></a>

### `exec-command!`

```clojure
(exec-command! e cmd & _args)
```

Run the CodeMirror command named `cmd` on editor `e`.

The command table is `cm6-commands.ts`. One place knows about it, rather than
the fifty-odd commands in `lt.objs.editor.pool` that each used to decide
where to look by not deciding.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L445)

<a id="var-find-marks"></a>

### `find-marks`

```clojure
(find-marks e pos)
```

Returns marks found at `pos` in .

See [findMarksAt](http://codemirror.net/doc/manual.html#findMarksAt).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L233)

<a id="var-find-next"></a>

### `find-next!`

```clojure
(find-next! e & [reverse?])
```

Move to the next match, or the previous one when `reverse?`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L463)

<a id="var-find"></a>

### `find!`

```clojure
(find! e query & [reverse?])
```

Search editor `e` for `query` and move to the first match.

A function rather than a command name, because the query is part of the
editor's state rather than an argument to a global — see `lt.objs.find`,
which does not know that.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L454)

<a id="var-first-line"></a>

### `first-line`

```clojure
(first-line e)
```

Returns the first line of editor `e`.

See [firstLine](http://codemirror.net/doc/manual.html#firstLine).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L497)

<a id="var-focus"></a>

### `focus`

```clojure
(focus e)
```

Return focus of editor.

See [focus](http://codemirror.net/doc/manual.html#focus).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L269)

<a id="var-fold-code"></a>

### `fold-code`

```clojure
(fold-code e)
(fold-code e loc)
```

Attempts to fold code starting at position `loc`. If position is not provided then folding will be attempted at the cursor position.

If the code is already folded then an attempt to unfold will occur.

See [foldcode.js](http://codemirror.net/addon/fold/foldcode.js) addon.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L659)

<a id="var-get-char"></a>

### `get-char`

```clojure
(get-char ed dir)
```

Returns the characters found from integer offest `dir` to the current cursor position.

See range.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L570)

<a id="var-get-history"></a>

### `get-history`

```clojure
(get-history e)
```

Returns the history of editor `e`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L78)

<a id="var-indent-line"></a>

### `indent-line`

```clojure
(indent-line e l dir)
```

Indents the line `l` based on the `dir` specified for editor `e`.

See [indent-line](http://codemirror.net/doc/manual.html#indentLine).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L580)

<a id="var-indent-lines"></a>

### `indent-lines`

```clojure
(indent-lines e from to dir)
```

Indents lines within the range resulting from `from` and `to` based on the `dir` specified
for editor `e`.

See [indent-line](http://codemirror.net/doc/manual.html#indentLine).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L587)

<a id="var-indent-selection"></a>

### `indent-selection`

```clojure
(indent-selection e dir)
```

Intent current selection in editor `e` by integer offset `dir`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L600)

<a id="var-input-field"></a>

### `input-field`

```clojure
(input-field e)
```

Return input field element of editor.

See [getInputField](http://codemirror.net/doc/manual.html#getInputField).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L277)

<a id="var-insert-at-cursor"></a>

### `insert-at-cursor`

```clojure
(insert-at-cursor ed s)
```

Insert into editor `ed` text `s` at cursor's position. Returns `ed`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L328)

<a id="var-last-line"></a>

### `last-line`

```clojure
(last-line e)
```

Returns the last line of editor `e`.

See [lastLine](http://codemirror.net/doc/manual.html#lastLine).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L504)

<a id="var-lh-line"></a>

### `lh->line`

```clojure
(lh->line e lh)
```

Given LineHandle object `lh`, returns integer for corresponding line from editor `e`.

See [getLineNumber](http://codemirror.net/doc/manual.html#getLineNumber).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L518)

<a id="var-line"></a>

### `line`

```clojure
(line e l)
```

Returns the content of line `l` from editor `e`.

See [getLine](http://codemirror.net/doc/manual.html#getLine).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L490)

<a id="var-line-comment"></a>

### `line-comment`

```clojure
(line-comment e from to opts)
```

Changes lines within range of `from` and `to` into line comments for editor `e`.

See [lineComment](http://codemirror.net/doc/manual.html#lineComment).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L605)

<a id="var-line-count"></a>

### `line-count`

```clojure
(line-count e)
```

Returns the number of lines in the editor.

See [lineCount](http://codemirror.net/doc/manual.html#lineCount).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L321)

<a id="var-line-handle"></a>

### `line-handle`

```clojure
(line-handle e l)
```

Returns `LineHandle` object from editor `e` for line `l`.

See [getLineHandle](http://codemirror.net/doc/manual.html#getLineHandle).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L511)

<a id="var-line-length"></a>

### `line-length`

```clojure
(line-length e l)
```

Returns the length of line `l` from editor `e`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L525)

<a id="var-line-widget"></a>

### `line-widget`

```clojure
(line-widget e line elem & [opts])
```

Add line widget `elem` (an element), along with any options, at `line` to editor `e`.

See [addLineWidget](http://codemirror.net/doc/manual.html#addLineWidget).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L478)

<a id="var-mark"></a>

### `mark`

```clojure
(mark e from to opts)
```

Marks text in editor `e` within range of `from` and `to`.

See [markText](http://codemirror.net/doc/manual.html#markText).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L226)

<a id="var-move-cursor"></a>

### `move-cursor`

```clojure
(move-cursor ed pos)
```

Moves editor `ed`'s cursor to position `pos`. If `pos` is nil then default position of line 0, ch 0 is used.

See [setCursor](http://codemirror.net/doc/manual.html#setCursor).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L334)

<a id="var-off"></a>

### `off`

```clojure
(off ed ev func)
```

Remove event handler `ev`, which fires `func`, on editor `ed`'s CodeMirror object.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L140)

<a id="var-on"></a>

### `on`

```clojure
(on ed ev func)
```

Register event handler `ev`, which fires `func`, on editor `ed`'s CodeMirror object.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L135)

<a id="var-on-click"></a>

### `on-click`

```clojure
(on-click e func)
```

Add function `func` to trigger when `:mousedown` fires.

Returns editor `e`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L436)

<a id="var-operation"></a>

### `operation`

```clojure
(operation e func)
```

Returns `e` rather than the return value of your function `func`.

See [operation](http://codemirror.net/doc/manual.html#operation).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L428)

<a id="var-option"></a>

### `option`

```clojure
(option e o)
```

Return value for option name `o` on editor `e`.

See [getOption](http://codemirror.net/doc/manual.html#getOption).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L247)

<a id="var-paste"></a>

### `paste`

```clojure
(paste e)
```

Paste into editor's current cursor position

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L416)

<a id="var-pos-index"></a>

### `pos->index`

```clojure
(pos->index e pos)
```

Returns integer based on position `pos` from editor's CodeMirror Object.
Position consists of line and character indexes as JSON, such as:

```
{"line": 144, "ch": 9}
```

Reverse of [posFromIndex](http://codemirror.net/doc/manual.html#posFromIndex).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L214)

<a id="var-range"></a>

### `range`

```clojure
(range e from to)
```

Returns text between positions `from` and `to`.

See [getRange](http://codemirror.net/doc/manual.html#getRange).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L314)

<a id="var-redo"></a>

### `redo`

```clojure
(redo e)
```

Redo one edit for editor `e`, if any exist.

See [redo](http://codemirror.net/doc/manual.html#redo).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L398)

<a id="var-refresh"></a>

### `refresh`

```clojure
(refresh e)
```

Refreshes editor. Returns `e`.

See [refresh](http://codemirror.net/doc/manual.html#refresh).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L290)

<a id="var-remove-gutter"></a>

### `remove-gutter`

```clojure
(remove-gutter e class-name)
```

Remove gutter with `class-name` from editor `e`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L695)

<a id="var-remove-line-widget"></a>

### `remove-line-widget`

```clojure
(remove-line-widget e widg)
```

Remove widget `widg` from editor `e`. Opposite of `line-widget`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L485)

<a id="var-replace"></a>

### `replace`

```clojure
(replace e from v)
(replace e from to v)
```

Replace text starting at position `from` for editor with text `v`. If provided, replace will stop at position `to`.

See [replaceRange](http://codemirror.net/doc/manual.html#replaceRange).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L305)

<a id="var-replace-selection"></a>

### `replace-selection`

```clojure
(replace-selection e neue & [after])
```

Replace selection with `neue` for editor `e`.

See [replaceSelection](http://codemirror.net/doc/manual.html#replaceSelection).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L384)

<a id="var-replace-2"></a>

### `replace!`

```clojure
(replace! e text & [reverse? all?])
```

Replace the current match with `text`, or every match when `all?`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L473)

<a id="var-scratch"></a>

### `scratch`

```clojure
(scratch text)
```

A detached editor holding `text`, for code that needs somewhere to put marks
and make edits without touching what anybody is looking at.

`lt.plugins.watches` is the caller: it copies the buffer, marks the watched
expressions in the copy and rewrites each one with instrumented source, so
that the positions stay right while the text underneath them changes. That
used to be a CodeMirror 5 `Doc`, which was the same idea with a lighter
object behind it.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L105)

<a id="var-scroll-to"></a>

### `scroll-to`

```clojure
(scroll-to ed x y)
```

Scroll editor to pixel position `x`,`y`.

See [scrollTo](http://codemirror.net/doc/manual.html#scrollTo).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L341)

<a id="var-select-all"></a>

### `select-all`

```clojure
(select-all e)
```

Select all lines from editor `e`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L530)

<a id="var-selection"></a>

### `selection`

```clojure
(selection e)
```

Returns currently selected text in editor.

See [getSelection](http://codemirror.net/doc/manual.html#getSelection).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L370)

<a id="var-selection-bounds"></a>

### `selection-bounds`

```clojure
(selection-bounds e)
```

When text is selected, returns position `{:from x :to y}` where `x` and `y` are the cursor's start and end values.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L363)

<a id="var-selection-2"></a>

### `selection?`

```clojure
(selection? e)
```

True if text is selected in editor.

See [somethingSelected](http://codemirror.net/doc/manual.html#somethingSelected).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L356)

<a id="var-set-doc"></a>

### `set-doc!`

```clojure
(set-doc! e doc)
```

Show `doc`'s text in editor `e`, and remember which document it is.

The text, because a document is a file's identity here rather than a buffer
two editors can share — see `lt.objs.document`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L650)

<a id="var-set-history"></a>

### `set-history`

```clojure
(set-history e v)
```

Set the history of editor `e` with provided value `v`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L83)

<a id="var-set-line"></a>

### `set-line`

```clojure
(set-line e l text)
```

Replace content at line `l` with `text` for editor `e`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L537)

<a id="var-set-mode"></a>

### `set-mode`

```clojure
(set-mode e m)
```

Set mode option for editor `e`.

See [getOption](http://codemirror.net/doc/manual.html#getOption).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L254)

<a id="var-set-options"></a>

### `set-options`

```clojure
(set-options e m)
```

Given a map of options, set each pair as an option on editor `e`'s
CodeMirror object. Returns `e`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L64)

<a id="var-set-selection"></a>

### `set-selection`

```clojure
(set-selection e start end)
```

Sets editor's selection to `start` and `end` positions.

See [setSelection](http://codemirror.net/doc/manual.html#setSelection).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L377)

<a id="var-set-val"></a>

### `set-val`

```clojure
(set-val e v)
```

Set content value `v` of editor `e`'s CodeMirror object. Cursor position is lost. Returns `e`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L51)

<a id="var-set-val-and-keep-cursor"></a>

### `set-val-and-keep-cursor`

```clojure
(set-val-and-keep-cursor e v)
```

Same as [`set-val`](#var-set-val) but current cursor position is kept.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L57)

<a id="var-toggle-comment"></a>

### `toggle-comment`

```clojure
(toggle-comment e from to opts)
```

Toggle comment and if multiline toggle apply block comment

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L628)

<a id="var-uncomment"></a>

### `uncomment`

```clojure
(uncomment e from to opts)
```

Attempts to uncomment lines within range of `from` and `to` for editor `e`.

Returns `true` if comment range was successfully removed.

See [uncomment](http://codemirror.net/doc/manual.html#uncomment).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L612)

<a id="var-undo"></a>

### `undo`

```clojure
(undo e)
```

Undo one edit for editor `e`, if any exist.

See [undo](http://codemirror.net/doc/manual.html#undo).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L391)
