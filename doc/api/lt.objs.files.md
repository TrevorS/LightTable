# lt.objs.files

Provide fns for doing file related operations. A number of fns
use the node [fs library](https://nodejs.org/api/fs.html) or [path library](https://nodejs.org/api/path.html).

Source: [`src/lt/objs/files.cljs`](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs)

[← API index](README.md)

| | |
|---|---|
| [`absolute?`](#var-absolute) | True if `path` is formatted as an absolute filepath. |
| [`append`](#var-append) | Append `content` to `path`. |
| [`basename`](#var-basename) | Extracts the basename of the `path`, typically the end of the path. |
| [`copy`](#var-copy) | Copy file or directory `from` to the path `to`. |
| [`cwd`](#var-cwd) | Directory process is started in. |
| [`delete!`](#var-delete) | Delete file or directory from filesystem. |
| [`dir?`](#var-dir) | True if `path` corresponds to a directory that exists. |
| [`dirs`](#var-dirs) | Return directory's directories. |
| [`exists?`](#var-exists) | True if `path` exists on filesystem. |
| [`ext`](#var-ext) | Returns the last extention of `path`, without the leading `.`, determined by the final `.` of the path. |
| [`ext->mode`](#var-ext-mode) | Extracts the `:mime` information from `ext`, which must be a keyword. |
| [`file?`](#var-file) | True if `path` corresponds to a file that exists. |
| [`files-obj`](#var-files-obj) | — |
| [`filter-walk`](#var-filter-walk) | Returns files and directories under `path` where `func` returns true. |
| [`full-path-ls`](#var-full-path-ls) | Return directory's files as full paths. |
| [`get-roots`](#var-get-roots) | Example… |
| [`home`](#var-home) | Return users' home directory (e.g. |
| [`ignore-pattern`](#var-ignore-pattern) | What the searcher and the navigate bar do not look inside. |
| [`join`](#var-join) | Join path segments with the platform separator. |
| [`line-ending`](#var-line-ending) | Current platform-specific line ending. |
| [`ls`](#var-ls) | Return directory's files. |
| [`ls-sync`](#var-ls-sync) | Return directory's files applying ignore-pattern. |
| [`lt-home`](#var-lt-home) | Return LT's home directory. |
| [`lt-user-dir`](#var-lt-user-dir) | Return LT's user directory. |
| [`mkdir`](#var-mkdir) | Make given directory. |
| [`move!`](#var-move) | Move file or directory to given `path`. |
| [`next-available-name`](#var-next-available-name) | Given a `path`, if it already exists then append a digit (starts at 1 and increments after) to the end of `path` and check again. |
| [`open`](#var-open) | Open file and in callback return map with file's content in `:content` |
| [`open-sync`](#var-open-sync) | Open file and return map with file's content in `:content`. |
| [`parent`](#var-parent) | Return directory of `path`. |
| [`path->mode`](#var-path-mode) | Given a `path`, returns mime information. |
| [`path->type`](#var-path-type) | Given a `path`, returns type information if a file. |
| [`real-path`](#var-real-path) | Returns the canonicalized absolute pathname, expanding symbolic links. |
| [`relative`](#var-relative) | Returns a relative path, if there is one, from `a` to `b`. |
| [`resolve`](#var-resolve) | See [path.resolve](https://nodejs.org/api/path.html#path_path_resolve_path). |
| [`save`](#var-save) | Save `path` with given `content`. |
| [`separator`](#var-separator) | Current platform-specific file separator. |
| [`stats`](#var-stats) | Facts about `path`, or nil when it does not exist. |
| [`trash!`](#var-trash) | Move file to trash. |
| [`walk-up-find`](#var-walk-up-find) | Starting at `start` path, walk up parent directories and return first path… |
| [`without-ext`](#var-without-ext) | Returns the `path`, but without the last extension, determined by the final `.` of the path. |
| [`writable?`](#var-writable) | Returns 7, 6, 3, or 2 based on file permissions. |

## Vars

<a id="var-absolute"></a>

### `absolute?`

```clojure
(absolute? path)
```

True if `path` is formatted as an absolute filepath. False otherwise.
Does not check if `path` exists or otherwise valid.

Example:
```
(absolute? "/foo/bar/baz")     ;;=> true

(absolute? "/foo/bar/baz.txt") ;;=> true

(absolute? "./foo/bar")        ;;=> false

(absolute? "foo/bar")          ;;=> false
```

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L285)

<a id="var-append"></a>

### `append`

```clojure
(append path content & [cb])
```

Append `content` to `path`. Optional callback called after append.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L403)

<a id="var-basename"></a>

### `basename`

```clojure
(basename path)
(basename path ext)
```

Extracts the basename of the `path`, typically the end of the path.

If `ext` is provided then the result returned will not contain the extension.

Example:
```
(basename "/foo/bar/baz.txt")
;;=> "baz.txt"

(basename "/foo/bar/baz.txt" ".txt")
;;=> "baz"
```

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L90)

<a id="var-copy"></a>

### `copy`

```clojure
(copy from to)
```

Copy file or directory `from` to the path `to`. `to` is the destination
itself, not a directory to place the copy inside.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L432)

<a id="var-cwd"></a>

### `cwd`

Directory process is started in.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L82)

<a id="var-delete"></a>

### `delete!`

```clojure
(delete! path)
```

Delete file or directory from filesystem.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L422)

<a id="var-dir"></a>

### `dir?`

```clojure
(dir? path)
```

True if `path` corresponds to a directory that exists.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L273)

<a id="var-dirs"></a>

### `dirs`

```clojure
(dirs path)
```

Return directory's directories.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L501)

<a id="var-exists"></a>

### `exists?`

```clojure
(exists? path)
```

True if `path` exists on filesystem.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L258)

<a id="var-ext"></a>

### `ext`

```clojure
(ext path)
```

Returns the last extention of `path`, without the leading `.`, determined by the final `.` of the path.

Example:
```
(ext "foo.txt")         ;;=> "txt"

(ext "foo/bar.txt.tar") ;;=> "tar"

(ext "foo.")            ;;=> ""

(ext "foo")             ;;=> ""
```

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L136)

<a id="var-ext-mode"></a>

### `ext->mode`

```clojure
(ext->mode ext)
```

Extracts the `:mime` information from `ext`, which must be a keyword.

Example:
```
(ext->mode :txt)  ;;=> "plaintext"

(ext->mode :cljs) ;;=> "text/x-clojurescript"

(ext->mode :clj)  ;;=> "text/x-clojure"
```

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L190)

<a id="var-file"></a>

### `file?`

```clojure
(file? path)
```

True if `path` corresponds to a file that exists.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L279)

<a id="var-files-obj"></a>

### `files-obj`

*Undocumented.*

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L47)

<a id="var-filter-walk"></a>

### `filter-walk`

```clojure
(filter-walk func path)
```

Returns files and directories under `path` where `func` returns true.

Example:
```
(filter-walk
  (fn [x] (= (basename x) "LightTable"))
  "/home/sbauer/dev/LightTable/")
;;=> ("/home/sbauer/dev/LightTable/builds/lighttable-0.8.1-linux/LightTable"
     "/home/sbauer/dev/LightTable/.git/refs/remotes/LightTable"
     "/home/sbauer/dev/LightTable/.git/logs/refs/remotes/LightTable")
```

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L554)

<a id="var-full-path-ls"></a>

### `full-path-ls`

```clojure
(full-path-ls path)
```

Return directory's files as full paths.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L493)

<a id="var-get-roots"></a>

### `get-roots`

```clojure
(get-roots)
```

Example:
```
(get-roots) ;;=> #{"/"}
```

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L106)

<a id="var-home"></a>

### `home`

```clojure
(home)
(home path)
```

Return users' home directory (e.g. ~/) or path under it.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L509)

<a id="var-ignore-pattern"></a>

### `ignore-pattern`

What the searcher and the navigate bar do not look inside.

Matched against one directory entry's name, with a trailing separator when
it is a directory — so `target/` skips a directory called target without
also skipping a file of that name.

`node_modules/` is here because of a measurement rather than a hunch. On
this repository the walk visited 10,507 files, of which 7,911 were inside
`node_modules`: eight times the work to search 1,296 files anybody wanted.
Every entry above it dates from 2014, when a project's dependencies were
not a directory you carried around.

Overridable, and meant to be — `:lt.objs.files/file.ignore-pattern` is a
`:user` behavior, and asking for a directory by name searches it whatever
this says.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L29)

<a id="var-join"></a>

### `join`

```clojure
(join & segs)
```

Join path segments with the platform separator.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L24)

<a id="var-line-ending"></a>

### `line-ending`

Current platform-specific line ending.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L79)

<a id="var-ls"></a>

### `ls`

```clojure
(ls path)
(ls path cb)
```

Return directory's files.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L464)

<a id="var-ls-sync"></a>

### `ls-sync`

```clojure
(ls-sync path opts)
```

Return directory's files applying ignore-pattern. Takes map of options with keys:

* `:files` - When set only returns files
* `:dirs` - When set only return directories

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L478)

<a id="var-lt-home"></a>

### `lt-home`

```clojure
(lt-home)
(lt-home path)
```

Return LT's home directory.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L519)

<a id="var-lt-user-dir"></a>

### `lt-user-dir`

```clojure
(lt-user-dir)
(lt-user-dir path)
```

Return LT's user directory. Used for storing user-related content (e.g.,
settings, plugins, logs, and caches).

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L525)

<a id="var-mkdir"></a>

### `mkdir`

```clojure
(mkdir path)
```

Make given directory.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L440)

<a id="var-move"></a>

### `move!`

```clojure
(move! from to)
```

Move file or directory to given `path`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L427)

<a id="var-next-available-name"></a>

### `next-available-name`

```clojure
(next-available-name path)
```

Given a `path`, if it already exists then append a digit (starts at 1 and increments after) to the end of `path` and check again.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L450)

<a id="var-open"></a>

### `open`

```clojure
(open path cb)
```

Open file and in callback return map with file's content in `:content`

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L360)

<a id="var-open-sync"></a>

### `open-sync`

```clojure
(open-sync path)
```

Open file and return map with file's content in `:content`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L376)

<a id="var-parent"></a>

### `parent`

```clojure
(parent path)
```

Return directory of `path`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L445)

<a id="var-path-mode"></a>

### `path->mode`

```clojure
(path->mode path)
```

Given a `path`, returns mime information.

Example:
```
(path->mode "/foo/bar/baz.txt") ;;=> "plaintext"

(path->mode "foo.cljs")         ;;=> "text/x-clojurescript"

(path->mode "foo.clj")          ;;=> "text/x-clojure"

(path->mode "foo")              ;;=> ""
```

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L228)

<a id="var-path-type"></a>

### `path->type`

```clojure
(path->type path)
```

Given a `path`, returns type information if a file. Returns an empty string if `path` is a directory.

Example:
```
(path->type "/foo/bar/baz.txt")
;;=> {:exts [:txt], :mime "plaintext", :tags [:editor.plaintext], :name "Plain Text"}

(path->type "foo.cljs")
;;=> {:exts [:cljs], :mime "text/x-clojurescript", :tags [:editor.cljs :editor.clojurescript], :name "ClojureScript"}

(path->type "foo.clj")
;;=> {:exts [:clj], :mime "text/x-clojure", :tags [:editor.clj :editor.clojure], :name "Clojure"}

(path->type "/foo/bar/")
;;=> "" ; No type information is returned as it is a directory.
```

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L204)

<a id="var-real-path"></a>

### `real-path`

```clojure
(real-path c)
```

Returns the canonicalized absolute pathname, expanding symbolic links.

Example:

Assume current directory is `/foo/bar/` and `/foo/bar/baz` exists too.
```
(real-path "./")           ;;=> "/foo/bar/"

(real-path ".././bar/baz") ;;=> "/foo/bar/baz/"
```

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L326)

<a id="var-relative"></a>

### `relative`

```clojure
(relative a b)
```

Returns a relative path, if there is one, from `a` to `b`.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L549)

<a id="var-resolve"></a>

### `resolve`

```clojure
(resolve base cur)
```

See [path.resolve](https://nodejs.org/api/path.html#path_path_resolve_path).

Example:
```
(resolve "/" "/home/user")   ;;=> "/home/user"

(resolve "/foo" "./bar/baz") ;;=> "/foo/bar/baz"

(resolve "./" "builds")      ;;=> "/home/user/dev/LightTable/builds"
```

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L312)

<a id="var-save"></a>

### `save`

```clojure
(save path content & [cb])
```

Save `path` with given `content`. Optional callback called after save.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L392)

<a id="var-separator"></a>

### `separator`

Current platform-specific file separator.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L80)

<a id="var-stats"></a>

### `stats`

```clojure
(stats path)
```

Facts about `path`, or nil when it does not exist.

Was an [fs.Stats](https://nodejs.org/api/fs.html#fs_class_fs_stats), which
carried isDirectory and isFile as methods. It is plain data now — a prototype
does not survive the crossing into the window — with :isDirectory, :isFile,
:size, :mode and :mtimeMs as properties.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L263)

<a id="var-trash"></a>

### `trash!`

```clojure
(trash! path)
```

Move file to trash. Returns a promise that resolves once the move completes
and rejects if it fails.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L414)

<a id="var-walk-up-find"></a>

### `walk-up-find`

```clojure
(walk-up-find start find)
```

Starting at `start` path, walk up parent directories and return first path
whose basename matches find.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L534)

<a id="var-without-ext"></a>

### `without-ext`

```clojure
(without-ext path)
```

Returns the `path`, but without the last extension, determined by the final `.` of the path.

Example:
```
(without-ext "foo.txt")         ;;=> "foo"

(without-ext "foo/bar.txt.tar") ;;=> "foo/bar.txt"

(without-ext "foo.")            ;;=> "foo"

(without-ext "foo")             ;;=> "foo"
```

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L152)

<a id="var-writable"></a>

### `writable?`

```clojure
(writable? path)
```

Returns 7, 6, 3, or 2 based on file permissions. `path` must exist.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/objs/files.cljs#L302)
