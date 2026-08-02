# lt.object

Define core of BOT architecture and provide fns for manipulating objects,
behaviors and tags

Source: [`src/lt/object.cljs`](https://github.com/TrevorS/LightTable/blob/develop/src/lt/object.cljs)

[← API index](README.md)

| | |
|---|---|
| [`->content`](#var-content) | Return DOM content associated with object |
| [`->dom`](#var-dom) | What an object's `:init` returned, as a DOM node. |
| [`->id`](#var-id) | Return id of given object |
| [`*behavior-meta*`](#var-behavior-meta) | Metadata of current behavior set during raise and raise-reduce |
| [`add-behavior!`](#var-add-behavior) | Add behavior to object and update its listeners |
| [`add-tags`](#var-add-tags) | Add tags to given object and updates effected behaviors and listeners. |
| [`assoc-in!`](#var-assoc-in) | Update object with assoc-in for given key and value |
| [`behavior-source`](#var-behavior-source) | — |
| [`behavior*`](#var-behavior) | Create and store a behavior. |
| [`behaviors`](#var-behaviors) | Map of behavior names to behaviors created by macros/behavior |
| [`by-id`](#var-by-id) | Find object by its unique numerical id |
| [`by-tag`](#var-by-tag) | Find objects that have given tag |
| [`call-behavior-reaction`](#var-call-behavior-reaction) | For a given behavior keyword id, call its :reaction fn with given args |
| [`clear-errors!`](#var-clear-errors) | Forget the errors seen so far, for a caller about to try something. |
| [`create`](#var-create) | — |
| [`destroy!`](#var-destroy) | Destroy object by calling its :destroy trigger, removing it from… |
| [`errors`](#var-errors) | — |
| [`has-tag?`](#var-has-tag) | Return truthy if object has tag |
| [`instances`](#var-instances) | Map of object ids to objects created by object/create |
| [`instances-by-type`](#var-instances-by-type) | Return all objects for given type (template name) |
| [`merge!`](#var-merge) | Merge map into object |
| [`negated-tags`](#var-negated-tags) | Map of tags to dissociated lists of behaviors e.g. |
| [`object-defs`](#var-object-defs) | Map of object template keys to template maps created by object/object* |
| [`object*`](#var-object) | Create object template (type) given keyword name and key-value pairs. |
| [`raise`](#var-raise) | — |
| [`raise-reduce`](#var-raise-reduce) | Reduce over invoked object's behavior fns for given trigger. |
| [`refresh!`](#var-refresh) | Re-apply an object's listeners and raise :object.refresh on it. |
| [`rem-behavior!`](#var-rem-behavior) | Remove behavior from object and update its listeners |
| [`remove-tags`](#var-remove-tags) | Remove tags from given object and updates effected behaviors and listeners. |
| [`safe-report-error`](#var-safe-report-error) | — |
| [`specificity-sort`](#var-specificity-sort) | — |
| [`tag-behaviors`](#var-tag-behaviors) | Associate behaviors to given tag and refresh objects with given tag |
| [`tags`](#var-tags) | Map of tags to associated lists of behaviors |
| [`trace-with!`](#var-trace-with) | Send every raise and every behavior invocation to `f`, or nil to stop. |
| [`update!`](#var-update) | Update object with update-in with [:key], fn and args |

## Vars

<a id="var-content"></a>

### `->content`

```clojure
(->content obj)
```

Return DOM content associated with object

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/object.cljs#L395)

<a id="var-dom"></a>

### `->dom`

```clojure
(->dom content)
```

What an object's `:init` returned, as a DOM node.

Hiccup is rendered once, by Replicant. Anything else is already a node and is used
as it is — and that second branch is the seam another renderer plugs into. An
`:init` returning what Replicant, React or plain `document.createElement`
produced needs nothing here to change, in either the create path or the
redefinition one.

It is load-bearing rather than defensive. `test-e2e/renderer.spec.ts` is what
says so, because nothing else would.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/object.cljs#L275)

<a id="var-id"></a>

### `->id`

```clojure
(->id obj)
```

Return id of given object

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/object.cljs#L54)

<a id="var-behavior-meta"></a>

### `*behavior-meta*`

Metadata of current behavior set during raise and raise-reduce

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/object.cljs#L38)

<a id="var-add-behavior"></a>

### `add-behavior!`

```clojure
(add-behavior! obj behavior)
```

Add behavior to object and update its listeners

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/object.cljs#L460)

<a id="var-add-tags"></a>

### `add-tags`

```clojure
(add-tags obj ts)
```

Add tags to given object and updates effected behaviors and listeners.
::tags-added trigger is raised on object after update

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/object.cljs#L491)

<a id="var-assoc-in"></a>

### `assoc-in!`

```clojure
(assoc-in! obj k v)
```

Update object with assoc-in for given key and value

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/object.cljs#L382)

<a id="var-behavior-source"></a>

### `behavior-source`

*Undocumented.*

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/object.cljs#L141)

<a id="var-behavior"></a>

### `behavior*`

```clojure
(behavior* name & r)
```

Create and store a behavior. Prefer the lt.macros/behavior macro, which
expands to this fn.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/object.cljs#L346)

<a id="var-behaviors"></a>

### `behaviors`

Map of behavior names to behaviors created by macros/behavior

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/object.cljs#L22)

<a id="var-by-id"></a>

### `by-id`

```clojure
(by-id id)
```

Find object by its unique numerical id

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/object.cljs#L472)

<a id="var-by-tag"></a>

### `by-tag`

```clojure
(by-tag tag)
```

Find objects that have given tag

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/object.cljs#L478)

<a id="var-call-behavior-reaction"></a>

### `call-behavior-reaction`

```clojure
(call-behavior-reaction id & args)
```

For a given behavior keyword id, call its :reaction fn with given args

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/object.cljs#L227)

<a id="var-clear-errors"></a>

### `clear-errors!`

```clojure
(clear-errors!)
```

Forget the errors seen so far, for a caller about to try something.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/object.cljs#L150)

<a id="var-create"></a>

### `create`

*Undocumented.*

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/object.cljs#L375)

<a id="var-destroy"></a>

### `destroy!`

```clojure
(destroy! obj)
```

Destroy object by calling its :destroy trigger, removing it from
cache and removing associated DOM content

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/object.cljs#L400)

<a id="var-errors"></a>

### `errors`

*Undocumented.*

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/object.cljs#L128)

<a id="var-has-tag"></a>

### `has-tag?`

```clojure
(has-tag? obj tag)
```

Return truthy if object has tag

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/object.cljs#L486)

<a id="var-instances"></a>

### `instances`

Map of object ids to objects created by object/create

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/object.cljs#L18)

<a id="var-instances-by-type"></a>

### `instances-by-type`

```clojure
(instances-by-type type)
```

Return all objects for given type (template name)

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/object.cljs#L263)

<a id="var-merge"></a>

### `merge!`

```clojure
(merge! obj m)
```

Merge map into object

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/object.cljs#L268)

<a id="var-negated-tags"></a>

### `negated-tags`

Map of tags to dissociated lists of behaviors e.g. :-behavior

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/object.cljs#L34)

<a id="var-object-defs"></a>

### `object-defs`

Map of object template keys to template maps created by object/object*

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/object.cljs#L26)

<a id="var-object"></a>

### `object*`

```clojure
(object* name & r)
```

Create object template (type) given keyword name and key-value pairs.
These pairs serve as default attributes for an object. Following keys
have special meaning:

* :behaviors - Set of object's behaviors
* :tags - Set of object's tags
* :triggers - Set of object's triggers
* :init - Init fn called when object is created. Fn's return value
          is hiccup html content and saved to :content
* :listeners (internal) - Map of triggers to vectors of behaviors
* :doc - Equivalent to a traditional function docstring.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/object.cljs#L310)

<a id="var-raise"></a>

### `raise`

*Undocumented.*

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/object.cljs#L165)

<a id="var-raise-reduce"></a>

### `raise-reduce`

```clojure
(raise-reduce obj k start & args)
```

Reduce over invoked object's behavior fns for given trigger. Start
is initial value for reduce and any args are passed to behavior fn

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/object.cljs#L355)

<a id="var-refresh"></a>

### `refresh!`

```clojure
(refresh! obj)
```

Re-apply an object's listeners and raise :object.refresh on it.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/object.cljs#L453)

<a id="var-rem-behavior"></a>

### `rem-behavior!`

```clojure
(rem-behavior! obj behavior)
```

Remove behavior from object and update its listeners

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/object.cljs#L466)

<a id="var-remove-tags"></a>

### `remove-tags`

```clojure
(remove-tags obj ts)
```

Remove tags from given object and updates effected behaviors and listeners.
::tags-removed trigger is raised on object after update

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/object.cljs#L501)

<a id="var-safe-report-error"></a>

### `safe-report-error`

```clojure
(safe-report-error e)
```

*Undocumented.*

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/object.cljs#L155)

<a id="var-specificity-sort"></a>

### `specificity-sort`

```clojure
(specificity-sort xs)
(specificity-sort xs dir)
```

*Undocumented.*

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/object.cljs#L76)

<a id="var-tag-behaviors"></a>

### `tag-behaviors`

```clojure
(tag-behaviors tag behs)
```

Associate behaviors to given tag and refresh objects with given tag

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/object.cljs#L516)

<a id="var-tags"></a>

### `tags`

Map of tags to associated lists of behaviors

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/object.cljs#L30)

<a id="var-trace-with"></a>

### `trace-with!`

```clojure
(trace-with! f)
```

Send every raise and every behavior invocation to `f`, or nil to stop.

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/object.cljs#L180)

<a id="var-update"></a>

### `update!`

```clojure
(update! obj & r)
```

Update object with update-in with [:key], fn and args

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/object.cljs#L377)
