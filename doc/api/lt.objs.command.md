# lt.objs.command

Provide command manager and command related fns

Source: [`src/lt/objs/command.cljs`](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/command.cljs)

[← API index](README.md)

| | |
|---|---|
| [`by-id`](#var-by-id) | Return the command registered under key `k`. |
| [`command`](#var-command) | Define a command given a map with the following keys… |
| [`completions`](#var-completions) | Return command completions for `token`, for use in the command bar. |
| [`exec!`](#var-exec) | Execute a Light Table command with the given args |
| [`manager`](#var-manager) | — |

## Vars

<a id="var-by-id"></a>

### `by-id`

```clojure
(by-id k)
```

Return the command registered under key `k`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/command.cljs#L24)

<a id="var-command"></a>

### `command`

```clojure
(command cmd)
```

Define a command given a map with the following keys:

* :command (required) - Unique keyword name for command
* :desc (required) - Brief description of command
* :exec (required)  - Function to invoke when command is called
* :hidden - When true, command is hidden from command bar. Not set by default

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/command.cljs#L9)

<a id="var-completions"></a>

### `completions`

```clojure
(completions token)
```

Return command completions for `token`, for use in the command bar.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/command.cljs#L31)

<a id="var-exec"></a>

### `exec!`

```clojure
(exec! cmd & args)
```

Execute a Light Table command with the given args

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/command.cljs#L42)

<a id="var-manager"></a>

### `manager`

*Undocumented.*

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/command.cljs#L5)
