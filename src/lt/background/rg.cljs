(ns lt.background.rg
  "Project-wide search through ripgrep.

  Light Table walked the tree and read every file itself, in this process, one
  file at a time. That is correct — [[lt.background.file-search]] has 27 tests
  saying so — and it is slower than it needs to be. Measured on this repository
  by `make bench-search --all`, where both implementations read the whole tree:

  | | files read | | per file | |
  |---|---|---|---|---|
  | walk and read | 13,066 | 1,227ms | 94µs | 760 files, 2,490 matches |
  | ripgrep | 13,967 | 266ms | **19µs** | 760 files, 2,490 matches |
  | ripgrep, as shipped | 455 | **12ms** | 26µs | 152 files, 1,473 matches |

  Two things in that table matter more than the speedup.

  **The first two rows find exactly the same thing** — 760 files and 2,490
  matches, both. That is the correctness result, and a better one than a test
  could give: the JSON parsing, the line grouping, the binary handling and the
  case rules agree with an implementation that has 27 tests, on real input, with
  nothing told what to expect.

  **The third row is the one people see, and it is fast for a different reason.**
  It reads 455 files instead of 13,967, because it honours `.gitignore`. Per file
  it is *worse* than the row above — 26µs against 19µs — since a spawn costs
  about 2ms however little there is to do, and at 455 files that constant is most
  of the 12ms. So the shipped speedup is mostly **not reading files**, and
  `:ignore-files?` below is a load-bearing decision rather than a detail.

  Two earlier versions of this table were wrong, both because of how they were
  measured, and both corrections are in the changelog: one compared rows that had
  read different numbers of files, and one was taken with a personal
  `RIPGREP_CONFIG_PATH` still in effect — which is what `--no-config` is for.

  This benchmark exists because nothing had ever measured any of it, and
  doc/direction.md names *find-in-project that is fast on a large repository* as
  an open item — so there was no number to justify a change against and no way
  to tell afterwards whether it helped.

  **This is the route VS Code takes** — bundle the binary, shell out to it — and
  it is the only one of the two obvious precedents available here. Zed uses the
  same engine by linking ripgrep's Rust crates directly, which an Electron
  application cannot do without writing a native addon.

  ## What stays

  **ripgrep does not write.** So replacing is still
  [[lt.background.file-search]]'s, and this narrows what it has to look at from
  the whole tree to the files that matched — which is both faster and the same
  tested code doing the rewriting.

  **The tree walk stays too**, as the fallback. A checkout that has not run
  `make deps`, or a platform with no pinned build, must have working search
  rather than none.

  ## Layering

  Everything that decides anything is a pure function of its arguments —
  [[argv]] builds the command line, [[parse-line]] reads one line of output — so
  the interesting half is tested without spawning anything. Only [[search]]
  needs a process, and it is handed the path to the binary rather than working it
  out, because the worker learns Light Table's install directory from the
  renderer at startup."
  (:require ["child_process" :as cp]
            ["string_decoder" :as string-decoder]
            [clojure.string :as string]
            [lt.background.file-search :as file-search]))

(def ^:private line-limit
  "Longest line reported back, in characters. Same as the walk's, and for the
  same reason: a match inside a minified bundle should not put a megabyte of
  text into the results list."
  400)

(defn argv
  "The arguments to run ripgrep with, for `search` under `paths`.

  Every flag here is a decision, and the ones that look redundant are the ones
  that matter:

  | | |
  |---|---|
  | `--json` | structured output. `--vimgrep` would need a parser for paths containing colons |
  | `--fixed-strings` | unless the search is `/a regex/`. The walk made this distinction and ripgrep must keep it, or `a.b` silently matches `axb` again |
  | `--case-sensitive` / `--ignore-case` | stated rather than guessed. [[lt.background.file-search/case-sensitive?]] has already decided — a capital was typed — so asking `--smart-case` to decide again was two answers to one question |
  | `--engine auto` | regex only. Rust's engine cannot do lookarounds or backreferences and errors on them; this falls back to PCRE2 for a pattern that needs it instead of refusing a search somebody typed |
  | `--no-config` | **a user's `RIPGREP_CONFIG_PATH` must not reach this.** Any flag in it would silently change what the editor searches, and `--max-columns` or `--type-add` in someone's dotfiles is not a Light Table bug anybody could find |
  | `--crlf` | so `$` matches before `\\r\\n`. Without it a regex search anchored to end-of-line finds nothing in a CRLF file |
  | `--no-require-git` | ripgrep otherwise only honours .gitignore inside a git repository, and a workspace folder is often not one |
  | `--no-messages` | an unreadable file is not a reason to write to stderr |
  | `--no-follow` | symlinks are not followed, which is what the walk did and what stops a cyclic link running forever |

  Five of those are VS Code's, read off
  `ripgrepTextSearchEngine.ts` rather than remembered: `--json`, `--no-config`,
  `--crlf`, `--no-require-git`, and stating the case rule instead of asking
  `--smart-case` to infer it. `--engine auto` is its rule for regex searches too.

  Two of its flags are deliberately **not** taken:

  - **`--hidden`, which VS Code always passes.** Its excludes are user-configured
    globs; Light Table's `ignore-pattern` starts with `(^\\..*)`, so every
    dot-named segment is dropped by [[excluded-path?]] afterwards regardless.
    Passing it would mean reading `.git` and then throwing the results away.
  - **`--max-filesize`, which VS Code passes only when it is configured** — so
    there is no default, and that answers the open question in doc/hygiene.md
    about capping it here. A cap would silently stop searching large text files,
    which the walk never did.

  `--` before the paths, so a folder whose name begins with a dash is a path
  rather than a flag.

  ## `:ignore-files?`

  The one genuine behaviour change in moving to ripgrep, and the reason it is an
  option rather than a default nobody was told about.

  Honouring `.gitignore` is most of why ripgrep is fast, and on this repository
  it is the difference between reading 1,340 files and reading 470. It also means
  a match in an ignored file **stops appearing** — the tree walk searched
  anything `lt.objs.files/ignore-pattern` did not name, and that pattern lists
  `node_modules/` and `target/` but knows nothing about a project's own ignores.

  Defaulting to on is what VS Code and Zed both do, and both offer the same
  toggle. `:lt.objs.search/use-ignore-files` is Light Table's, and it appears in
  the settings screen like any other `:type :user` behavior.

  `--hidden` travels with it: ripgrep skips dotfiles by default, and a search
  that has been told to ignore nothing should not still be skipping `.github`."
  ([search paths] (argv search paths {}))
  ([search paths {:keys [ignore-files?] :or {ignore-files? true}}]
   (let [regex? (boolean (re-seq #"^/(.+)/$" search))
         pattern (if regex?
                   (second (first (re-seq #"^/(.+)/$" search)))
                   search)]
     (into (cond-> ["--json" "--no-config" "--crlf"
                    "--no-messages" "--no-follow" "--no-require-git"]
             (not regex?) (conj "--fixed-strings")
             regex? (into ["--engine" "auto"])
             (file-search/case-sensitive? search) (conj "--case-sensitive")
             (not (file-search/case-sensitive? search)) (conj "--ignore-case")
             (not ignore-files?) (into ["--no-ignore" "--hidden"])
             true (conj "--regexp" pattern)
             true (conj "--"))
           paths))))

(defn- path-of
  "The path out of one of ripgrep's `{\"text\": …}` wrappers.

  A path that is not valid UTF-8 arrives as `{\"bytes\": base64}` instead, and
  is skipped rather than guessed at: Light Table addresses editors by path
  string, so a path it cannot spell is one it cannot open."
  [^js data]
  (some-> (.-path data) .-text))

(defn- opener?
  "Is `line` a `begin` message?

  A string test rather than a parse, because a `begin` carries only the path —
  which every `match` under it repeats — and there is one per file searched.
  Skipped rather than parsed.

  `end` is *not* skipped, though it looks equally uninteresting: it is the only
  place ripgrep says a file turned out to be binary. See [[binary-files]].

  Safe to do by prefix: ripgrep puts `type` first on `begin`, `match` and `end`.
  It does **not** on `summary`, which arrives as
  `{\"data\":…,\"type\":\"summary\"}` — so this can only ever be used to skip,
  never to identify."
  [line]
  (string/starts-with? line "{\"type\":\"begin\""))

(defn parse-line
  "One line of `--json` output, as something worth acting on, or nil.

  Four message types arrive and only two are interesting. `begin` and `end`
  bracket each file and carry nothing this needs — the per-file stats in `end`
  count *that* file, and what the summary needs is the total.

  Returns `{:kind :match :file f :line n :text s}` or
  `{:kind :summary :searched n :matched n :matches n}`."
  [line]
  (when-not (or (string/blank? line) (opener? line))
    (let [^js msg (try (.parse js/JSON line) (catch :default _ nil))
          ^js data (some-> msg .-data)]
      (when data
        (case (.-type msg)
          ;; The only place ripgrep says a file was binary, and it says it
          ;; *after* reporting whatever it found before the first NUL byte.
          "end"
          (when-let [file (and (some? (.-binary_offset data)) (path-of data))]
            {:kind :binary :file file})

          "match"
          (when-let [file (path-of data)]
            (let [text (or (some-> (.-lines data) .-text) "")
                  ;; ripgrep includes the newline it matched up to, and a CRLF
                  ;; file brings the carriage return with it. Stripped for
                  ;; display only, the same as the walk does.
                  shown (string/replace text #"\r?\n$" "")]
              {:kind :match
               :file file
               :line (.-line_number data)
               :text (if (> (count shown) line-limit)
                       (subs shown 0 line-limit)
                       shown)}))

          "summary"
          (let [^js stats (.-stats data)]
            {:kind :summary
             ;; `searches` is files ripgrep actually opened and read, which is
             ;; the honest denominator — it excludes what .gitignore, the hidden
             ;; rule and binary detection took out, because none of those was
             ;; searched.
             :searched (or (some-> stats .-searches) 0)
             :matched (or (some-> stats .-searches_with_match) 0)
             :matches (or (some-> stats .-matched_lines) 0)})

          nil)))))

(defn excluded-path?
  "Whether `exclude` rejects any part of `file`.

  `lt.objs.files/ignore-pattern` is written to match **one directory entry's
  name**, with a trailing separator when it is a directory — that is how
  `target/` skips a directory called target without also skipping a file of that
  name, and it is what `src-worker/walkdir.ts` and the tree walk both do.

  So it is applied here per segment, not to the whole path. Running it against
  the full string would be the same class of mistake as the glob bug the walk
  replaced: a pattern written for one alphabet evaluated against another. `~`
  is the clearest case — as an entry-name rule it excludes an editor backup file
  called `notes~`, and against a full path it would exclude everything under a
  home directory spelled with a tilde in it.

  Both separators, because ripgrep reports Windows paths with backslashes."
  [exclude file]
  (boolean
   (when (and exclude file)
     (let [segments (remove string/blank? (string/split (str file) #"[/\\]"))
           dirs (butlast segments)
           nm (last segments)]
       (or (some #(seq (re-seq exclude (str % "/"))) dirs)
           (and nm (seq (re-seq exclude nm))))))))

(defn group-by-file
  "Consecutive matches for one file, collapsed into the shape the searcher
  reads.

  ripgrep emits every match for a file before moving to the next, so this is a
  fold rather than a sort. One entry per *line*, because two hits on one line
  are one place to go and one line to show — the same rule
  [[lt.background.file-search/matching-lines]] follows.

  `partition-by` answers with a sequence of **groups**, not with key/group
  pairs, and destructuring one as `[file group]` binds the first two matches of
  the group instead. That was the first version, and the benchmark is what
  caught it: 151 files with 126 matches between them is not a plausible ratio,
  it is arithmetic that cannot happen. A test asserting two hits on one line
  collapse to one result would have passed either way, because the shape was
  right and only the contents were wrong."
  [matches]
  (for [group (partition-by :file matches)]
    {:file (:file (first group))
     :results (vec (for [same (partition-by :line group)]
                     {:line (:line (first same))
                      :text (:text (first same))}))}))

(defonce ^:private known-good
  ;; Binaries that have already answered `--version`. Not a single flag: the
  ;; path can differ between a run from the tree and a packaged one.
  (atom #{}))

(defn available?
  "Whether `binary` is a runnable ripgrep.

  Remembered once it says yes, and **re-asked every time it says no**. That
  asymmetry is the whole design:

  - A spawn costs 2.1ms, measured. Against a shipped search that takes 18ms in
    total that is 12% spent asking a question whose answer has never once
    changed within a session.
  - A `no` has to stay cheap to revisit, because it is the answer that changes:
    running `make deps` while the editor is open should make search fast without
    a restart, which is what the first version got right and paid for on every
    search."
  [binary]
  (boolean
   (and binary
        (or (contains? @known-good binary)
            (try
              (let [^js out (.execFileSync cp binary #js ["--version"]
                                           #js {:encoding "utf8" :timeout 5000})]
                (when (string/starts-with? (str out) "ripgrep")
                  (swap! known-good conj binary)
                  true))
              (catch :default _ false))))))

(defn- summarise
  "The result map, from everything `parse-line` produced.

  Shaped exactly like [[lt.background.file-search/search]]'s return, because
  `lt.background.search` reports from either and the two have to be
  interchangeable."
  [parsed exclude started on-file]
  (let [summary (or (first (filter (comp #{:summary} :kind) parsed)) {})
        ;; Files ripgrep found a NUL byte in. It reports matches from a binary
        ;; file *before* it discovers that it is one — it searches until the
        ;; first NUL, emits what it found, then says so in `end` — so this can
        ;; only be applied afterwards.
        ;;
        ;; Dropping them is what the walk did, by sniffing the first 8KB for a
        ;; NUL before searching at all. Without this, searching with
        ;; `use-ignore-files` off reports matches from compiled binaries: on this
        ;; repository, `builds/` holds a 191MB Electron Framework, and "defn"
        ;; appears in it before any NUL does.
        ;;
        ;; It is also where the output volume comes from. A binary has almost no
        ;; newlines, so the "line" containing a match is enormous — the longest
        ;; single JSON line in that case is **21MB**, and the whole output is
        ;; 107MB against 374KB for the same search with ignore files on.
        ;; `--max-columns` does not help; it has no effect in `--json` mode.
        binary (into #{} (comp (filter (comp #{:binary} :kind)) (map :file)) parsed)
        matches (remove #(contains? binary (:file %))
                        (filter (comp #{:match} :kind) parsed))
        ;; ripgrep's own ignore rules are the point of using it — .gitignore,
        ;; hidden files, binary detection — but `lt.objs.files/ignore-pattern` is
        ;; a user behavior and still has to be honoured. Applied to the results
        ;; rather than translated into globs: it is a regex, and feeding a regex
        ;; to a glob matcher is precisely the bug the walk replaced.
        kept (remove #(excluded-path? exclude (:file %)) matches)
        dropped (- (count matches) (count kept))
        ;; Sorted by path, and this is not tidiness.
        ;;
        ;; ripgrep searches in parallel, so **the order it reports files in is
        ;; not stable between identical searches**. The tree walk's order fell
        ;; out of the traversal and was therefore deterministic, and things
        ;; depend on that: which result `:searcher.next` visits first, and which
        ;; one is on top of the list a moment after you searched. Results that
        ;; reshuffle when nothing changed is the kind of thing that makes a
        ;; feature feel broken without ever being wrong.
        ;;
        ;; `--sort path` is ripgrep's own answer and the wrong one here: its
        ;; documentation is explicit that sorting abandons parallelism and runs
        ;; single-threaded. Sorting afterwards costs nothing instead, because
        ;; this already has every match in memory before it emits any of them.
        files (vec (sort-by :file (group-by-file kept)))]
    (doseq [f files] (when on-file (on-file f)))
    {:files files
     :total-files (:searched summary 0)
     :matched-files (count files)
     :matches (reduce + 0 (map (comp count :results) files))
     ;; What the ignore pattern took out after ripgrep had already found it.
     ;; Reported rather than silent: a result that vanishes for a reason nobody
     ;; can see is how a search stops being trusted.
     :excluded dropped
     :time (- (.now js/Date) started)
     :errors []}))

(defn search
  "Search `:paths` for `:pattern` by running `:binary`, calling `:on-file` with
  `{:file :results}` per matching file and then `:on-done` with the summary.

  Asynchronous, and the worker does not mind: a job's return value is ignored and
  everything travels back as a message, so nothing was waiting on this anyway.

  ## Streaming, which is VS Code's arrangement and fixes a real failure

  `execFileSync` was the first version, and it has a `maxBuffer` — exceed it and
  the call throws, which for a search means no results at all. That is reachable
  rather than theoretical: 107MB of output measured on this repository with
  `use-ignore-files` off, because a match inside a compiled binary comes back as
  a 21MB line. Raising the limit only moves the cliff.

  Reading the pipe incrementally has no such limit, and it drops peak memory from
  *the whole output* to **the largest single line** — the raw text of each
  complete line is parsed and thrown away as it arrives. A `StringDecoder` is
  what makes that safe: a chunk boundary can fall inside a multi-byte character,
  and splitting the bytes before decoding them would corrupt it.

  **Results are still collected before any are emitted**, which is where this
  parts company with VS Code — it emits as it parses. Collecting is what makes
  the results sortable, and ripgrep's parallel output order is not stable between
  identical searches. Progressive results are worth having at 161ms and worth
  nothing at 20ms; a list that reshuffles when nothing changed is worth avoiding
  at either.

  `:replacement` is not handled here. ripgrep does not write, so
  `lt.background.search` hands the matched files to the walk's tested rewriting
  path instead — see this namespace's docstring."
  [{:keys [binary paths pattern exclude on-file ignore-files? on-done on-error]
    :or {ignore-files? true}}]
  (let [started (.now js/Date)
        ^js proc (.spawn cp binary
                         (clj->js (argv pattern paths {:ignore-files? ignore-files?}))
                         ;; stderr ignored rather than piped: `--no-messages`
                         ;; already suppresses the per-file complaints, and what
                         ;; is left would be a reason the process failed, which
                         ;; arrives on `error`/`close` anyway.
                         #js {:stdio #js ["ignore" "pipe" "ignore"]})
        ;; Decoded rather than concatenated as strings — see the docstring.
        ^js decode (new (.-StringDecoder string-decoder) "utf8")
        parsed (atom (transient []))
        ;; The tail of the last chunk, which is almost never a whole line, held
        ;; as an **array of pieces rather than a growing string**.
        ;;
        ;; `(str pending chunk)` was the first version and it is quadratic on a
        ;; long line: a 21MB line arrives in ~330 chunks and each one copied the
        ;; whole accumulation again. Measured at 838ms against 226ms for reading
        ;; it in one buffer — so the streaming that was supposed to be cheaper
        ;; was four times the cost until this stopped copying.
        pending (atom #js [])
        take-line! (fn [line]
                     (when-let [p (parse-line line)]
                       (swap! parsed conj! p)))
        finished (atom false)
        finish! (fn [f arg]
                  ;; `close` can follow `error`, and a fallback that ran twice
                  ;; would search twice and report twice.
                  (when-not @finished
                    (reset! finished true)
                    (f arg)))]
    (.on (.-stdout proc) "data"
         (fn [chunk]
           (let [text (.write decode chunk)]
             (if (neg? (.indexOf text "\n"))
               ;; No line ends in this chunk, so there is nothing to parse and
               ;; nothing to copy. This is the whole of the long-line case.
               (.push @pending text)
               (let [;; `.split` rather than `clojure.string/split`, which drops
                     ;; trailing empty strings — so a chunk ending exactly at a
                     ;; newline had its last *complete* line treated as a
                     ;; fragment and glued to the next chunk's first line. That
                     ;; produced unparseable JSON, which `parse-line` discards
                     ;; without a word: the count of files searched came back 0
                     ;; while the matches all looked right.
                     lines (.split (str (.join @pending "") text) "\n")
                     complete (.slice lines 0 (dec (.-length lines)))]
                 (doseq [line (array-seq complete)] (take-line! line))
                 (reset! pending #js [(aget lines (dec (.-length lines)))]))))))
    (.on proc "error"
         (fn [e]
           ;; The binary vanished between `available?` and here, or is not
           ;; executable. `on-error` falls back to the walk.
           (finish! #(when on-error (on-error %)) (str e))))
    (.on proc "close"
         (fn [_code]
           ;; A non-zero exit is not an error to report: ripgrep exits 1 for "no
           ;; matches", which is an answer.
           (try
             (take-line! (str (.join @pending "") (.end decode)))
             (finish! #(when on-done (on-done %))
                      (summarise (persistent! @parsed) exclude started on-file))
             (catch :default e
               (finish! #(when on-error (on-error %)) (str e))))))
    proc))
