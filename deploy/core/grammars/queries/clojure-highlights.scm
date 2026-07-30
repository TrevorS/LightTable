; Clojure highlights, written here rather than vendored.
;
; The tree-sitter-clojure npm package ships no queries, so this is Light
; Table's. It is worth the effort: the grammar distinguishes things the
; CodeMirror clojure mode cannot see at all — a defn's name from its docstring
; from its parameter vector, an interop call from a symbol, a keyword from a
; symbol — and Clojure is the language this editor is written in.
;
; Order matters. Later captures win, so the broad rules come first.

; ── Everything is a symbol until proven otherwise ─────────────────────────
(symbol) @variable

; ── Literals ──────────────────────────────────────────────────────────────
(number) @number
(string) @string
(regex) @string.regexp
(character) @character
[(boolean) (nil)] @constant.builtin
[(symbolic_value) (infinity) (negative_infinity) (not_a_number)] @constant.builtin

; Keywords are Clojure's own — :foo and ::foo and :ns/foo.
[(keyword) (qualified_keyword)] @constant

; ── Comments ──────────────────────────────────────────────────────────────
(comment) @comment
(shebang_line) @comment
; #_ elides the next form. Dimming it as a comment is what it means.
(ignore_form) @comment

; ── Definitions ───────────────────────────────────────────────────────────
; The grammar gives defn its own node with named children, which is why this
; can distinguish the three things a reader most wants distinguished.
(defn (function_name) @function)
; The string *inside* the docstring, not the docstring node wrapping it: the
; broad `(string) @string` rule matches that inner node too, and a narrower
; capture would otherwise win over the wider one.
(defn (docstring (string) @comment.doc))
(defn (params (vector (symbol) @variable.parameter)))

(anonymous_function (params (vector (symbol) @variable.parameter)))
(shorthand_function_arg) @variable.parameter

; ── Interop and namespaces ────────────────────────────────────────────────
; `.length`, `Math/abs`, `app.core` — a call into the host, not a local.
(interop) @function.method
(new_class) @type
(field_access) @property
(member_access) @function.method

; ── Reader syntax ─────────────────────────────────────────────────────────
[(quote) (syntax_quote) (unquote) (unquote_splice) (var_quote) (deref)] @punctuation.special
(metadata) @attribute
(metadata_shorthand) @attribute
(tagged_literal) @function.macro
(reader_conditional) @function.macro
(threading_macro) @function.macro
(gensym) @variable

; A symbol in first position is being called...
(list . (symbol) @function.call)

; ...unless it is a special form, which is why this rule comes second: later
; captures win, so `if` and `let` end up keywords rather than calls. Clojure
; has no reserved words — `if` is an ordinary symbol — so only its spelling
; and position tell a reader which it is.
((symbol) @keyword
 (#match? @keyword "^(def|defn|defn-|defmacro|defmulti|defmethod|defprotocol|defrecord|deftype|definterface|defstruct|defonce|declare|ns|in-ns|require|use|import|refer|let|letfn|fn|if|if-not|if-let|if-some|when|when-not|when-let|when-some|cond|condp|case|do|loop|recur|for|doseq|dotimes|while|try|catch|finally|throw|new|set!|var|quote|binding|with-open|with-local-vars|with-redefs|lazy-seq|delay|future|reify|proxy|extend|extend-type|extend-protocol)$"))

; ── Structure ─────────────────────────────────────────────────────────────
["(" ")" "[" "]" "{" "}"] @punctuation.bracket
