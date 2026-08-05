(ns lt.objs.plugins.scopes
  "A capability, and where it reaches.

  The manifest vocabulary was eight capability *names*, and a name is not a
  scope: `:files` means \"reads and writes the filesystem\", which is every file
  the user can read. This adds the smallest thing that makes it a sentence a
  person can check — a **root**:

  ```clojure
  :capabilities #{:files :network}                     ; what every plugin had
  :capabilities {:files [:self :workspace] :network :all}
  ```

  Both forms are read, and the set form means `:all` for every capability in
  it. That is what keeps this backward compatible: twenty published plugins
  declared sets, and a set has to go on meaning what it meant.

  Three root names are resolved rather than compared:

  | | |
  |---|---|
  | `:all` | anywhere. The honest name for what every plugin has today |
  | `:self` | the plugin's own directory |
  | `:workspace` | the folders and files the workspace holds |

  Anything else is a path, and `~` is the user's home.

  **A path is not a file's identity**, which is the one thing in here worth
  being careful about. `realpathSync` is on the bridge, so a scope enforced on
  the string a caller passed is a scope a symlink walks out of. Resolving is
  the caller's job — see [[lt.objs.plugins.policy]] — because it needs the
  filesystem and this namespace deliberately does not: everything here is
  decidable from strings, so the containment rule can be tested against the
  cases that actually break it rather than against a mock.

  The rule itself is [[under?]], and it is a separator-boundary comparison for
  a reason this repository has already been bitten by twice — once in
  `require-shim/in-dir?` and once in `pool/containing-path`. `/src/app` must not
  contain `/src/application`."
  (:require [clojure.string :as string]))

(def ^:private separator "/")

(defn normalize
  "`path` with `.` and `..` resolved, duplicate separators collapsed, and any
  trailing separator removed.

  String arithmetic, not a filesystem question: `a/b/../c` is `a/c` whatever is
  on disk. `..` above the root is dropped rather than kept, because `/../x` and
  `/x` are the same file and a comparison that disagreed would be a way out of
  a scope."
  [path]
  (when (string? path)
    (let [absolute? (string/starts-with? path separator)
          parts (->> (string/split path #"/+")
                     (remove #(or (= "" %) (= "." %)))
                     (reduce (fn [acc part]
                               (if (= ".." part)
                                 (if (and (seq acc) (not= ".." (peek acc)))
                                   (pop acc)
                                   ;; Above an absolute root there is nowhere
                                   ;; to go, so it is dropped. On a relative
                                   ;; path it has to be kept: `../x` is a real
                                   ;; place and not the same one as `x`.
                                   (if absolute? acc (conj acc part)))
                                 (conj acc part)))
                             []))
          joined (string/join separator parts)]
      (cond
        absolute? (str separator joined)
        (seq joined) joined
        ;; An empty relative path is where you already are.
        :else "."))))

(defn resolve-through
  "`path` with symlinks resolved as far as it exists.

  A path is not a file's identity, so a scope compared against the string a
  caller passed is a scope a symlink walks out of. This is the part that closes
  that, and the awkward half is that a path being written to **does not exist
  yet** — `writeFileSync` names a file that has no realpath. So the longest
  existing ancestor is resolved and the rest is appended, which is what puts a
  write into a symlinked directory inside the scope the symlink points at rather
  than outside it.

  `exists?` and `realpath` are passed in rather than reached for, the same way
  [[lt.objs.plugins.require-shim/requirer]] takes its dependencies: it is the
  only way this walk can be tested, and it has already been wrong once. An
  earlier version rebuilt the answer from the original path *plus* the
  accumulated suffix, so `/nowhere/at/all` came back
  `/nowhere/at/all/nowhere/at/all` — doubling every path whose ancestors are all
  missing, which is exactly the case it exists for."
  [exists? realpath path]
  (let [norm (normalize path)]
    (loop [prefix norm
           suffix []]
      (cond
        (exists? prefix)
        (normalize (str (realpath prefix) separator (string/join separator suffix)))

        ;; Walked to the root and nothing on the way existed, so there is
        ;; nothing to resolve and the normalised path is the whole answer.
        (or (= prefix separator) (not (string/includes? prefix separator)))
        norm

        :else
        (let [cut (string/last-index-of prefix separator)]
          (recur (let [up (subs prefix 0 cut)]
                   (if (empty? up) separator up))
                 (cons (subs prefix (inc cut)) suffix)))))))

(defn under?
  "Is `path` `root` itself, or something inside it?

  Compared at a separator, which is the whole content of this function. A
  prefix test says `/src/app` contains `/src/application`, and the two are
  different directories owned by different things."
  [root path]
  (let [root (normalize root)
        path (normalize path)]
    (boolean
     (and root path
          (or (= root path)
              (string/starts-with? path (if (string/ends-with? root separator)
                                          root
                                          (str root separator))))))))

(defn url-host
  "The host of `s`, when `s` is a url. `s` itself when it is already a bare
  hostname, and nil when it is neither.

  `js/URL` rather than a regex, because a url is exactly the kind of string
  where a regex is nearly right — `https://evil.com#@github.com` parses
  differently from how it reads, and the parser is the thing that gets that
  correct."
  [s]
  (when (string? s)
    (if (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*:" s)
      (try
        (let [host (.-hostname (js/URL. s))]
          (when (seq host) (string/lower-case host)))
        (catch :default _ nil))
      (when (seq s) (string/lower-case s)))))

(defn host-under?
  "Is `host` `root` itself, or a subdomain of it?

  The dot boundary is the separator rule again, in the shape network names take:
  `github.com` must not contain `notgithub.com`, for the same reason `/src/app`
  must not contain `/src/application`. Getting one of those right and the other
  wrong is the likeliest way this ends up with a hole in it, so they are written
  beside each other."
  [root host]
  (let [root (url-host root)
        host (url-host host)]
    (boolean
     (and root host
          (or (= root host)
              (string/ends-with? host (str "." root)))))))

(defn declared-capabilities
  "The capability names `capabilities` declares, whichever form it is in.

  Nil in, nil out — and nil is not the empty set. Nil is a plugin that predates
  manifests and has claimed nothing; `#{}` is one asserting it needs nothing.
  [[lt.objs.plugins.capabilities/declared]] rests on that distinction."
  [capabilities]
  (cond
    (nil? capabilities) nil
    (map? capabilities) (set (keys capabilities))
    :else (set capabilities)))

(defn declared-roots
  "The roots `capabilities` declares for `capability`.

  `:all` when the declaration is a set, when the capability names no roots, or
  when it names `:all` outright — the three ways of saying \"anywhere\", which
  are one answer and should not be three code paths downstream.

  A capability the manifest does not mention at all also comes back `:all`,
  and that is deliberate: whether a plugin may use a capability is
  [[lt.objs.plugins.capabilities/declared]]'s question, and answering it here
  too would mean two places could disagree about it."
  [capabilities capability]
  (if-not (map? capabilities)
    :all
    (let [roots (get capabilities capability :all)]
      (cond
        (= :all roots) :all
        (keyword? roots) [roots]
        (string? roots) [roots]
        (empty? roots) :all
        :else (vec roots)))))

(defn scoped?
  "Does `capabilities` narrow anything below `:all`?

  The fast path's question. Attribution costs a stack read, so a window where
  no loaded plugin has narrowed anything should not be paying for one — see
  [[lt.util.bridge.guard]]."
  [capabilities]
  (boolean
   (and (map? capabilities)
        (some (fn [[cap _]] (not= :all (declared-roots capabilities cap)))
              capabilities))))

(defn- permits-by?
  [contains? roots subjects]
  (or (= :all roots)
      (and (seq roots)
           (every? (fn [subject]
                     (some #(contains? % subject) roots))
                   (remove nil? subjects)))))

(defn permits?
  "May a call reach every one of `paths`, given `roots`?

  `roots` is `:all` or a sequence of resolved absolute paths, and `paths` are
  resolved absolute paths too — resolving both is the caller's job, because it
  is the half that needs a filesystem.

  Every path must be under some root. A copy from inside the scope to outside
  it is not half-allowed, which is why `files/cpSync` and `files/renameSync`
  hand both of their arguments to this."
  [roots paths]
  (permits-by? under? roots paths))

(defn permits-hosts?
  "May a call reach every one of `hosts`, given `roots`?

  The network half of [[permits?]]. A root here is a hostname and a host under
  it is that name or a subdomain of it."
  [roots hosts]
  (permits-by? host-under? roots hosts))

(defn describe-denial
  "One line saying who was refused what, and where they may reach instead.

  Shaped after the shim's refusals, on purpose: a plugin author debugging a
  denial is the common case, and the worst possible version of it is a stack
  trace from inside `fs`. Naming the capability, the path and the scope is what
  makes the fix obvious — usually one line of `plugin.edn`."
  [plugin-name capability path roots]
  (str "Plugin '" plugin-name "' was refused " (name capability)
       " access to " path
       (if (= :all roots)
         ""
         (str " — it declared " (name capability) " for "
              (string/join ", " (map str roots))))))
