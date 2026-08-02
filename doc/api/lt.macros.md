# lt.macros

Macros used across LT

Source: [`src/lt/macros.cljc`](https://github.com/TrevorS/LightTable/blob/develop/src/lt/macros.cljc)

[← API index](README.md)

| | |
|---|---|
| [`->params`](#var-params) | — |
| [`behavior`](#var-behavior) | Define a behavior with a unique namespaced keyword and multiple key value pairs. |

## Vars

<a id="var-params"></a>

### `->params`

```clojure
(->params body)
```

*Undocumented.*

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/macros.cljc#L36)

<a id="var-behavior"></a>

### `behavior`

*macro*

```clojure
(behavior name & {:keys [reaction] :as r})
```

Define a behavior with a unique namespaced keyword and multiple key value pairs.
Keys are:

* :reaction (required) - Function to invoke when behavior is called.
                         First arg is object behavior is attached to
* :triggers (required) - Set of keyword triggers that trigger behavior
* :desc - Brief description of behavior.
* :doc - Equivalent to a traditional function docstring.
* :type - When set to :user, shows up in hints. Not enabled by default
* :params - Vector of maps describing behavior args. Each map contains required :label key
            and optional keys of :type (:string, :number or :list), :items and :example
* :throttle - Number of ms to throttle reaction fn
* :debounce - Number of ms to debounce reaction fn

[source](https://github.com/TrevorS/LightTable/blob/develop/src/lt/macros.cljc#L8)
