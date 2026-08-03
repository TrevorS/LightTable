# lt.objs.editor.pool

Provide manager for managing a pool of editors and several misc editor commands

Source: [`src/lt/objs/editor/pool.cljs`](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor/pool.cljs)

[← API index](README.md)

| | |
|---|---|
| [`by-path`](#var-by-path) | Return editor objects that edit given path |
| [`containing-path`](#var-containing-path) | Return editor objects that edit paths containing given path string |
| [`create`](#var-create) | Create a :lt.objs.editor/editor object with given info map and add it to current pool |
| [`focus-last`](#var-focus-last) | Focus the most recently active editor. |
| [`last-active`](#var-last-active) | Return current editor object (last active in pool) |
| [`unsaved?`](#var-unsaved) | Return truthy if any editors are currently dirty/unsaved? |

## Vars

<a id="var-by-path"></a>

### `by-path`

```clojure
(by-path path)
```

Return editor objects that edit given path

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor/pool.cljs#L49)

<a id="var-containing-path"></a>

### `containing-path`

```clojure
(containing-path path)
```

Return editor objects that edit paths containing given path string

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor/pool.cljs#L56)

<a id="var-create"></a>

### `create`

```clojure
(create info)
```

Create a :lt.objs.editor/editor object with given info map and add it to current pool

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor/pool.cljs#L175)

<a id="var-focus-last"></a>

### `focus-last`

```clojure
(focus-last)
```

Focus the most recently active editor.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor/pool.cljs#L78)

<a id="var-last-active"></a>

### `last-active`

```clojure
(last-active)
```

Return current editor object (last active in pool)

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor/pool.cljs#L71)

<a id="var-unsaved"></a>

### `unsaved?`

```clojure
(unsaved?)
```

Return truthy if any editors are currently dirty/unsaved?

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/editor/pool.cljs#L44)
