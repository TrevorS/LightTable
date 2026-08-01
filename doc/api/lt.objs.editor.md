# lt.objs.editor

Provide fns and behaviors for interfacing with a CodeMirror editor
object. Also manage defining and loading [CodeMirror](http://codemirror.net/doc/manual.html).

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
| [`cm6?`](#var-cm6) | True when `e` is a CodeMirror 6 editor. |
| [`copy`](#var-copy) | Copies currently selected text from editor. |
| [`cursor`](#var-cursor-2) | Return cursor position of editor `e`'s as js object. |
| [`cut`](#var-cut) | Cut currently selected text from editor. |
| [`dirty?`](#var-dirty) | Returns true if document is not clean for generation `gen`. |
| [`engine`](#var-engine) | Which engine new editors are built on: `:cm5` or `:cm6`. |
| [`exec-command!`](#var-exec-command) | Run the CodeMirror command named `cmd` on editor `e`. |
| [`extension`](#var-extension) | Add function `func` named `name` to CodeMirror API. |
| [`find-marks`](#var-find-marks) | Returns marks found at `pos` in . |
| [`find-next!`](#var-find-next) | Move to the next match, or the previous one when `reverse?`. |
| [`find!`](#var-find) | Search editor `e` for `query` and move to the first match. |
| [`first-line`](#var-first-line) | Returns the first line of editor `e`. |
| [`focus`](#var-focus) | Return focus of editor. |
| [`fold-code`](#var-fold-code) | Attempts to fold code starting at position `loc`. |
| [`get-char`](#var-get-char) | Returns the characters found from integer offest `dir` to the current cursor position. |
| [`get-doc`](#var-get-doc) | Returns currently active document for the editor. |
| [`get-history`](#var-get-history) | Returns the history of editor `e`. |
| [`indent-line`](#var-indent-line) | Indents the line `l` based on the `dir` specified for editor `e`. |
| [`indent-lines`](#var-indent-lines) | Indents lines within the range resulting from `from` and `to` based on the `dir` specified… |
| [`indent-selection`](#var-indent-selection) | Intent current selection in editor `e` by integer offset `dir`. |
| [`inner-mode`](#var-inner-mode) | Sets the innerMode of editor `e`'s CodeMirror object with `state` if provided. |
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
| [`on-change`](#var-on-change) | Add function `func` to trigger when `onChange` event fires. |
| [`on-click`](#var-on-click) | Add function `func` to trigger when `:mousedown` fires. |
| [`on-move`](#var-on-move) | Add function `func` to trigger when `onCursorActivity` event fires. |
| [`on-scroll`](#var-on-scroll) | Add function `func` to trigger when `onScroll` event fires. |
| [`on-update`](#var-on-update) | Add function `func` to trigger when `onUpdate` event fires. |
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
| [`scroll-to`](#var-scroll-to) | Scroll editor to pixel position `x`,`y`. |
| [`select-all`](#var-select-all) | Select all lines from editor `e`. |
| [`selection`](#var-selection) | Returns currently selected text in editor. |
| [`selection-bounds`](#var-selection-bounds) | When text is selected, returns position `{:from x :to y}` where `x` and `y` are the cursor's start and end values. |
| [`selection?`](#var-selection-2) | True if text is selected in editor. |
| [`set-doc!`](#var-set-doc) | Adds document `doc` to editor `e`. |
| [`set-engine!`](#var-set-engine) | Build subsequent editors on `k` (`:cm5` or `:cm6`), or on the default again… |
| [`set-extending`](#var-set-extending) | Sets editor's 'extending' flag to `ext?`. |
| [`set-history`](#var-set-history) | Set the history of editor `e` with provided value `v`. |
| [`set-line`](#var-set-line) | Replace content at line `l` with `text` for editor `e`. |
| [`set-mode`](#var-set-mode) | Set mode option for editor `e`. |
| [`set-options`](#var-set-options) | Given a map of options, set each pair as an option on editor `e`'s… |
| [`set-selection`](#var-set-selection) | Sets editor's selection to `start` and `end` positions. |
| [`set-val`](#var-set-val) | Set content value `v` of editor `e`'s CodeMirror object. |
| [`set-val-and-keep-cursor`](#var-set-val-and-keep-cursor) | Same as [`set-val`](#var-set-val) but current cursor position is kept. |
| [`show-hints`](#var-show-hints) | Display hint `hint-fn` for editor `e` with any provided options. |
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

Hinted ^js so that the compiler can resolve the CodeMirror methods called on
the result, rather than warning at every one of them.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L49)

<a id="var-cursor"></a>

### `->cursor`

```clojure
(->cursor e & [side])
```

Same as [`cursor`](#var-cursor-2) but returned as edn.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L263)

<a id="var-elem"></a>

### `->elem`

```clojure
(->elem e)
```

Return DOM element of editor `e`'s CodeMirror object

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L59)

<a id="var-generation"></a>

### `->generation`

```clojure
(->generation e)
```

Returns an integer that can be used to test if edits have occurred.

See [changeGeneration](http://codemirror.net/doc/manual.html#changeGeneration).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L782)

<a id="var-mode"></a>

### `->mode`

```clojure
(->mode e)
```

Return outer mode object for editor `e`.

See [getMode](http://codemirror.net/doc/manual.html#getMode).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L318)

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

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L221)

<a id="var-token-type"></a>

### `->token-type`

```clojure
(->token-type e pos)
```

Return the type of token located at position `pos` for editor `e`.

See [getTokenTypeAt](http://codemirror.net/doc/manual.html#getTokenTypeAt).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L244)

<a id="var-val"></a>

### `->val`

```clojure
(->val e)
```

Return editor `e`'s buffer content.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L216)

<a id="var-line-class"></a>

### `-line-class`

```clojure
(-line-class e lh plane class)
```

Remove CSS class name `class` from LineHandle `lh` at element `plane` for editor `e`.
Opposite of `+line-class`.

See [removeLineClass](http://codemirror.net/doc/manual.html#removeLineClass).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L683)

<a id="var-line-class-2"></a>

### `+line-class`

```clojure
(+line-class e lh plane class)
```

Add CSS class name `class` to LineHandle `lh` at element `plane` for editor `e`.

See [addLineClass](http://codemirror.net/doc/manual.html#addLineClass).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L676)

<a id="var-add-gutter"></a>

### `add-gutter`

```clojure
(add-gutter e class-name width)
```

Add gutter with `class-name` of specified `width` to editor `e`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L839)

<a id="var-adjust-loc"></a>

### `adjust-loc`

```clojure
(adjust-loc loc dir)
(adjust-loc loc dir axis)
```

Adjust position `loc` with integer offset `dir` and the key `axis`. Axis should either be `:line` or `:ch`.
If `axis` is not specified, defaults to `:ch`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L707)

<a id="var-block-comment"></a>

### `block-comment`

```clojure
(block-comment e from to opts)
```

Wrap lines within range of `from` and `to` for editor `e`.

See [blockComment](http://codemirror.net/doc/manual.html#blockComment).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L767)

<a id="var-blur"></a>

### `blur`

```clojure
(blur e)
```

Blurs input field `e`. Returns `e`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L340)

<a id="var-bookmark"></a>

### `bookmark`

```clojure
(bookmark e from widg)
```

Insert bookmark at position `from` for widget `widg`.

See [setBookmark](http://codemirror.net/doc/manual.html#setBookmark).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L296)

<a id="var-center-cursor"></a>

### `center-cursor`

```clojure
(center-cursor ed)
```

Scrolls editor `ed` to the cursor and places it in the center of screen.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L442)

<a id="var-char-coords"></a>

### `char-coords`

```clojure
(char-coords e pos)
```

Returns position and dimension, based off of `pos` for editor `e`, in map consisting of `{:left :right :top :bottom}`.

See [charChords](http://codemirror.net/doc/manual.html#charCoords).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L522)

<a id="var-clear-history"></a>

### `clear-history`

```clojure
(clear-history e)
```

Clear the history of editor `e`. Returns `e`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L85)

<a id="var-clear-search"></a>

### `clear-search!`

```clojure
(clear-search! e)
```

Forget the query, which takes the match highlighting with it.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L592)

<a id="var-cm6"></a>

### `cm6?`

```clojure
(cm6? e)
```

True when `e` is a CodeMirror 6 editor.

Asked of the object rather than of [`engine`](#var-engine), because the setting can change
under an editor that was already made and the object is the fact.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L143)

<a id="var-copy"></a>

### `copy`

```clojure
(copy e)
```

Copies currently selected text from editor.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L506)

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

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L251)

<a id="var-cut"></a>

### `cut`

```clojure
(cut e)
```

Cut currently selected text from editor.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L511)

<a id="var-dirty"></a>

### `dirty?`

```clojure
(dirty? e gen)
```

Returns true if document is not clean for generation `gen`. The document is not clean if it has been modified since it was in a clean state.

See [isClean](http://codemirror.net/doc/manual.html#isClean).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L789)

<a id="var-engine"></a>

### `engine`

```clojure
(engine)
```

Which engine new editors are built on: `:cm5` or `:cm6`.

CodeMirror 6 by default. `window.ltEditorEngine` chooses at startup and
[`set-engine!`](#var-set-engine) changes it afterwards, so both can be running in one session
and a test can compare them. Existing editors keep the engine they were made
with — this is read once, when one is created.

CodeMirror 5 is still here and still works, and going back is one command.
The one behaviour it has that CodeMirror 6 does not is a shared `Doc`: two
editors on the same document seeing each other's edits. See `make`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L122)

<a id="var-exec-command"></a>

### `exec-command!`

```clojure
(exec-command! e cmd & args)
```

Run the CodeMirror command named `cmd` on editor `e`.

The global `CodeMirror.commands` table belongs to CodeMirror 5 and its
functions reach into a CodeMirror 5 editor, so calling one on a CodeMirror 6
editor is a TypeError in whichever feature happens to reach it. This is the
one place that knows which table to look in — see `cm6-commands.ts` for the
other one, and `lt.objs.editor.pool` for the fifty-odd commands that used to
each make this decision by not making it.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L553)

<a id="var-extension"></a>

### `extension`

```clojure
(extension name func)
```

Add function `func` named `name` to CodeMirror API.

See [defineExtension](http://codemirror.net/doc/manual.html#defineExtension).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L546)

<a id="var-find-marks"></a>

### `find-marks`

```clojure
(find-marks e pos)
```

Returns marks found at `pos` in .

See [findMarksAt](http://codemirror.net/doc/manual.html#findMarksAt).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L289)

<a id="var-find-next"></a>

### `find-next!`

```clojure
(find-next! e & [reverse?])
```

Move to the next match, or the previous one when `reverse?`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L582)

<a id="var-find"></a>

### `find!`

```clojure
(find! e query & [reverse?])
```

Search editor `e` for `query` and move to the first match.

One function rather than a command name, because the two engines put search
in different places: CodeMirror 5 registers global commands from an addon,
and CodeMirror 6 makes the query part of the editor's state. Callers should
not have to know which — see `lt.objs.find`, where none of them do.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L569)

<a id="var-first-line"></a>

### `first-line`

```clojure
(first-line e)
```

Returns the first line of editor `e`.

See [firstLine](http://codemirror.net/doc/manual.html#firstLine).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L627)

<a id="var-focus"></a>

### `focus`

```clojure
(focus e)
```

Return focus of editor.

See [focus](http://codemirror.net/doc/manual.html#focus).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L325)

<a id="var-fold-code"></a>

### `fold-code`

```clojure
(fold-code e)
(fold-code e loc)
```

Attempts to fold code starting at position `loc`. If position is not provided then folding will be attempted at the cursor position.

If the code is already folded then an attempt to unfold will occur.

See [foldcode.js](http://codemirror.net/addon/fold/foldcode.js) addon.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L811)

<a id="var-get-char"></a>

### `get-char`

```clojure
(get-char ed dir)
```

Returns the characters found from integer offest `dir` to the current cursor position.

See range.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L716)

<a id="var-get-doc"></a>

### `get-doc`

```clojure
(get-doc e)
```

Returns currently active document for the editor.

See [getDoc](http://codemirror.net/doc/manual.html#getDoc).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L796)

<a id="var-get-history"></a>

### `get-history`

```clojure
(get-history e)
```

Returns the history of editor `e`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L91)

<a id="var-indent-line"></a>

### `indent-line`

```clojure
(indent-line e l dir)
```

Indents the line `l` based on the `dir` specified for editor `e`.

See [indent-line](http://codemirror.net/doc/manual.html#indentLine).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L726)

<a id="var-indent-lines"></a>

### `indent-lines`

```clojure
(indent-lines e from to dir)
```

Indents lines within the range resulting from `from` and `to` based on the `dir` specified
for editor `e`.

See [indent-line](http://codemirror.net/doc/manual.html#indentLine).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L733)

<a id="var-indent-selection"></a>

### `indent-selection`

```clojure
(indent-selection e dir)
```

Intent current selection in editor `e` by integer offset `dir`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L746)

<a id="var-inner-mode"></a>

### `inner-mode`

```clojure
(inner-mode e)
(inner-mode e state)
```

Sets the innerMode of editor `e`'s CodeMirror object with `state` if provided. Returns the mode.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L699)

<a id="var-input-field"></a>

### `input-field`

```clojure
(input-field e)
```

Return input field element of editor.

See [getInputField](http://codemirror.net/doc/manual.html#getInputField).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L333)

<a id="var-insert-at-cursor"></a>

### `insert-at-cursor`

```clojure
(insert-at-cursor ed s)
```

Insert into editor `ed` text `s` at cursor's position. Returns `ed`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L422)

<a id="var-last-line"></a>

### `last-line`

```clojure
(last-line e)
```

Returns the last line of editor `e`.

See [lastLine](http://codemirror.net/doc/manual.html#lastLine).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L634)

<a id="var-lh-line"></a>

### `lh->line`

```clojure
(lh->line e lh)
```

Given LineHandle object `lh`, returns integer for corresponding line from editor `e`.

See [getLineNumber](http://codemirror.net/doc/manual.html#getLineNumber).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L648)

<a id="var-line"></a>

### `line`

```clojure
(line e l)
```

Returns the content of line `l` from editor `e`.

See [getLine](http://codemirror.net/doc/manual.html#getLine).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L620)

<a id="var-line-comment"></a>

### `line-comment`

```clojure
(line-comment e from to opts)
```

Changes lines within range of `from` and `to` into line comments for editor `e`.

See [lineComment](http://codemirror.net/doc/manual.html#lineComment).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L751)

<a id="var-line-count"></a>

### `line-count`

```clojure
(line-count e)
```

Returns the number of lines in the editor.

See [lineCount](http://codemirror.net/doc/manual.html#lineCount).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L415)

<a id="var-line-handle"></a>

### `line-handle`

```clojure
(line-handle e l)
```

Returns `LineHandle` object from editor `e` for line `l`.

See [getLineHandle](http://codemirror.net/doc/manual.html#getLineHandle).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L641)

<a id="var-line-length"></a>

### `line-length`

```clojure
(line-length e l)
```

Returns the length of line `l` from editor `e`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L655)

<a id="var-line-widget"></a>

### `line-widget`

```clojure
(line-widget e line elem & [opts])
```

Add line widget `elem` (an element), along with any options, at `line` to editor `e`.

See [addLineWidget](http://codemirror.net/doc/manual.html#addLineWidget).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L608)

<a id="var-mark"></a>

### `mark`

```clojure
(mark e from to opts)
```

Marks text in editor `e` within range of `from` and `to`.

See [markText](http://codemirror.net/doc/manual.html#markText).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L282)

<a id="var-move-cursor"></a>

### `move-cursor`

```clojure
(move-cursor ed pos)
```

Moves editor `ed`'s cursor to position `pos`. If `pos` is nil then default position of line 0, ch 0 is used.

See [setCursor](http://codemirror.net/doc/manual.html#setCursor).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L428)

<a id="var-off"></a>

### `off`

```clojure
(off ed ev func)
```

Remove event handler `ev`, which fires `func`, on editor `ed`'s CodeMirror object.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L193)

<a id="var-on"></a>

### `on`

```clojure
(on ed ev func)
```

Register event handler `ev`, which fires `func`, on editor `ed`'s CodeMirror object.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L188)

<a id="var-on-change"></a>

### `on-change`

```clojure
(on-change e func)
```

Add function `func` to trigger when `onChange` event fires.
`func` should take two arguments, `ed` and `delta`. Returns `e`.

See [change](http://codemirror.net/doc/manual.html#event_change)

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L365)

<a id="var-on-click"></a>

### `on-click`

```clojure
(on-click e func)
```

Add function `func` to trigger when `:mousedown` fires.

Returns editor `e`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L537)

<a id="var-on-move"></a>

### `on-move`

```clojure
(on-move e func)
```

Add function `func` to trigger when `onCursorActivity` event fires.
`func` should take two arguments, `ed` and `delta`. Returns `e`.

See [cursorActivity](http://codemirror.net/doc/manual.html#event_cursorActivity)

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L354)

<a id="var-on-scroll"></a>

### `on-scroll`

```clojure
(on-scroll e func)
```

Add function `func` to trigger when `onScroll` event fires.
`func` should take two arguments, `ed`. Returns `e`.

See [scroll](http://codemirror.net/doc/manual.html#event_scroll)

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L387)

<a id="var-on-update"></a>

### `on-update`

```clojure
(on-update e func)
```

Add function `func` to trigger when `onUpdate` event fires.
`func` should take two arguments, `ed` and `delta`. Returns `e`.

See [update](http://codemirror.net/doc/manual.html#event_update)

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L376)

<a id="var-operation"></a>

### `operation`

```clojure
(operation e func)
```

Returns `e` rather than the return value of your function `func`.

See [operation](http://codemirror.net/doc/manual.html#operation).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L529)

<a id="var-option"></a>

### `option`

```clojure
(option e o)
```

Return value for option name `o` on editor `e`.

See [getOption](http://codemirror.net/doc/manual.html#getOption).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L303)

<a id="var-paste"></a>

### `paste`

```clojure
(paste e)
```

Paste into editor's current cursor position

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L517)

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

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L270)

<a id="var-range"></a>

### `range`

```clojure
(range e from to)
```

Returns text between positions `from` and `to`.

See [getRange](http://codemirror.net/doc/manual.html#getRange).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L408)

<a id="var-redo"></a>

### `redo`

```clojure
(redo e)
```

Redo one edit for editor `e`, if any exist.

See [redo](http://codemirror.net/doc/manual.html#redo).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L499)

<a id="var-refresh"></a>

### `refresh`

```clojure
(refresh e)
```

Refreshes editor. Returns `e`.

See [refresh](http://codemirror.net/doc/manual.html#refresh).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L346)

<a id="var-remove-gutter"></a>

### `remove-gutter`

```clojure
(remove-gutter e class-name)
```

Remove gutter with `class-name` from editor `e`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L847)

<a id="var-remove-line-widget"></a>

### `remove-line-widget`

```clojure
(remove-line-widget e widg)
```

Remove widget `widg` from editor `e`. Opposite of `line-widget`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L615)

<a id="var-replace"></a>

### `replace`

```clojure
(replace e from v)
(replace e from to v)
```

Replace text starting at position `from` for editor with text `v`. If provided, replace will stop at position `to`.

See [replaceRange](http://codemirror.net/doc/manual.html#replaceRange).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L399)

<a id="var-replace-selection"></a>

### `replace-selection`

```clojure
(replace-selection e neue & [after])
```

Replace selection with `neue` for editor `e`.

See [replaceSelection](http://codemirror.net/doc/manual.html#replaceSelection).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L485)

<a id="var-replace-2"></a>

### `replace!`

```clojure
(replace! e text & [reverse? all?])
```

Replace the current match with `text`, or every match when `all?`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L600)

<a id="var-scroll-to"></a>

### `scroll-to`

```clojure
(scroll-to ed x y)
```

Scroll editor to pixel position `x`,`y`.

See [scrollTo](http://codemirror.net/doc/manual.html#scrollTo).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L435)

<a id="var-select-all"></a>

### `select-all`

```clojure
(select-all e)
```

Select all lines from editor `e`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L660)

<a id="var-selection"></a>

### `selection`

```clojure
(selection e)
```

Returns currently selected text in editor.

See [getSelection](http://codemirror.net/doc/manual.html#getSelection).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L464)

<a id="var-selection-bounds"></a>

### `selection-bounds`

```clojure
(selection-bounds e)
```

When text is selected, returns position `{:from x :to y}` where `x` and `y` are the cursor's start and end values.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L457)

<a id="var-selection-2"></a>

### `selection?`

```clojure
(selection? e)
```

True if text is selected in editor.

See [somethingSelected](http://codemirror.net/doc/manual.html#somethingSelected).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L450)

<a id="var-set-doc"></a>

### `set-doc!`

```clojure
(set-doc! e doc)
```

Adds document `doc` to editor `e`. If there is already a document associated with the editor then it is replaced. Returns old document.

See [swapDoc](http://codemirror.net/doc/manual.html#swapDoc).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L803)

<a id="var-set-engine"></a>

### `set-engine!`

```clojure
(set-engine! k)
```

Build subsequent editors on `k` (`:cm5` or `:cm6`), or on the default again
when given anything else.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L137)

<a id="var-set-extending"></a>

### `set-extending`

```clojure
(set-extending e ext?)
```

Sets editor's 'extending' flag to `ext?`.

See [setExtending](http://codemirror.net/doc/manual.html#setExtending).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L478)

<a id="var-set-history"></a>

### `set-history`

```clojure
(set-history e v)
```

Set the history of editor `e` with provided value `v`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L96)

<a id="var-set-line"></a>

### `set-line`

```clojure
(set-line e l text)
```

Replace content at line `l` with `text` for editor `e`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L667)

<a id="var-set-mode"></a>

### `set-mode`

```clojure
(set-mode e m)
```

Set mode option for editor `e`.

See [getOption](http://codemirror.net/doc/manual.html#getOption).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L310)

<a id="var-set-options"></a>

### `set-options`

```clojure
(set-options e m)
```

Given a map of options, set each pair as an option on editor `e`'s
CodeMirror object. Returns `e`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L77)

<a id="var-set-selection"></a>

### `set-selection`

```clojure
(set-selection e start end)
```

Sets editor's selection to `start` and `end` positions.

See [setSelection](http://codemirror.net/doc/manual.html#setSelection).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L471)

<a id="var-set-val"></a>

### `set-val`

```clojure
(set-val e v)
```

Set content value `v` of editor `e`'s CodeMirror object. Cursor position is lost. Returns `e`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L64)

<a id="var-set-val-and-keep-cursor"></a>

### `set-val-and-keep-cursor`

```clojure
(set-val-and-keep-cursor e v)
```

Same as [`set-val`](#var-set-val) but current cursor position is kept.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L70)

<a id="var-show-hints"></a>

### `show-hints`

```clojure
(show-hints e hint-fn options)
```

Display hint `hint-fn` for editor `e` with any provided options.

See [show-hint.js](http://codemirror.net/addon/hint/show-hint.js).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L691)

<a id="var-toggle-comment"></a>

### `toggle-comment`

```clojure
(toggle-comment e from to opts)
```

Toggle comment and if multiline toggle apply block comment

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L774)

<a id="var-uncomment"></a>

### `uncomment`

```clojure
(uncomment e from to opts)
```

Attempts to uncomment lines within range of `from` and `to` for editor `e`.

Returns `true` if comment range was successfully removed.

See [uncomment](http://codemirror.net/doc/manual.html#uncomment).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L758)

<a id="var-undo"></a>

### `undo`

```clojure
(undo e)
```

Undo one edit for editor `e`, if any exist.

See [undo](http://codemirror.net/doc/manual.html#undo).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor.cljs#L492)
