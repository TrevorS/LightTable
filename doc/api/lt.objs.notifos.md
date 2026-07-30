# lt.objs.notifos

Provide fns for displaying messages and spinner in bottom statusbar

Source: [`src/lt/objs/notifos.cljs`](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/notifos.cljs)

[← API index](README.md)

| | |
|---|---|
| [`done-working`](#var-done-working) | Hide working spinner with optional statusbar message |
| [`set-msg!`](#var-set-msg) | Display message in bottom statusbar. |
| [`working`](#var-working) | Display working spinner with optional statusbar message |

## Vars

<a id="var-done-working"></a>

### `done-working`

```clojure
(done-working)
(done-working msg)
```

Hide working spinner with optional statusbar message

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/notifos.cljs#L43)

<a id="var-set-msg"></a>

### `set-msg!`

```clojure
(set-msg! msg)
(set-msg! msg opts)
```

Display message in bottom statusbar. Takes map of options with following keys:

* :class - css class for message. Use 'error' to display error message
* :timeout - Number of ms before message times out. Default is 10000 (10s)

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/notifos.cljs#L20)

<a id="var-working"></a>

### `working`

```clojure
(working)
(working msg)
```

Display working spinner with optional statusbar message

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/notifos.cljs#L35)
