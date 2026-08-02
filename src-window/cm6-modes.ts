// Which CodeMirror 6 language a CodeMirror 5 mode name means.
//
// Light Table bundles 121 modes. 103 of them are carried forward by
// `@codemirror/legacy-modes`, which is the CodeMirror 5 mode code running
// under a CodeMirror 6 `StreamLanguage` — the same tokenizer, the same
// colours, a different host.
//
// Five have something better: a real Lezer grammar, which parses incrementally
// and produces a tree rather than a line of tokens. Those are wired to the
// grammar rather than the legacy mode, because the whole reason to move is that
// a tree is worth more than a token stream.
//
// Thirteen have neither, and they are named at the bottom with what happens
// instead. Every one is a template or overlay mode — CodeMirror 5's way of
// running one mode inside another — and none belongs to a language this editor
// supports first class.
//
// Generated once from the installed packages and then edited by hand; the
// generator is not kept, because this is a list that changes when someone
// decides it should rather than when a dependency moves.

import type { Extension } from '@codemirror/state';
import { StreamLanguage } from '@codemirror/language';
import type { StreamParser } from '@codemirror/language';
import { tags as t } from '@lezer/highlight';
import type { Tag } from '@lezer/highlight';
import { markdown } from '@codemirror/lang-markdown';
import { html } from '@codemirror/lang-html';
import { javascript } from '@codemirror/lang-javascript';
import { php } from '@codemirror/lang-php';

// Imported by name rather than by a computed path: shadow-cljs bundles what it
// can see, and `require('...' + file)` is a path it cannot. Seventy files, one
// line each, generated.
import * as m_apl from '@codemirror/legacy-modes/mode/apl';
import * as m_asciiarmor from '@codemirror/legacy-modes/mode/asciiarmor';
import * as m_asn1 from '@codemirror/legacy-modes/mode/asn1';
import * as m_asterisk from '@codemirror/legacy-modes/mode/asterisk';
import * as m_brainfuck from '@codemirror/legacy-modes/mode/brainfuck';
import * as m_clike from '@codemirror/legacy-modes/mode/clike';
import * as m_clojure from '@codemirror/legacy-modes/mode/clojure';
import * as m_cmake from '@codemirror/legacy-modes/mode/cmake';
import * as m_cobol from '@codemirror/legacy-modes/mode/cobol';
import * as m_coffeescript from '@codemirror/legacy-modes/mode/coffeescript';
import * as m_commonlisp from '@codemirror/legacy-modes/mode/commonlisp';
import * as m_crystal from '@codemirror/legacy-modes/mode/crystal';
import * as m_css from '@codemirror/legacy-modes/mode/css';
import * as m_cypher from '@codemirror/legacy-modes/mode/cypher';
import * as m_d from '@codemirror/legacy-modes/mode/d';
import * as m_diff from '@codemirror/legacy-modes/mode/diff';
import * as m_dockerfile from '@codemirror/legacy-modes/mode/dockerfile';
import * as m_dtd from '@codemirror/legacy-modes/mode/dtd';
import * as m_dylan from '@codemirror/legacy-modes/mode/dylan';
import * as m_ebnf from '@codemirror/legacy-modes/mode/ebnf';
import * as m_ecl from '@codemirror/legacy-modes/mode/ecl';
import * as m_eiffel from '@codemirror/legacy-modes/mode/eiffel';
import * as m_elm from '@codemirror/legacy-modes/mode/elm';
import * as m_erlang from '@codemirror/legacy-modes/mode/erlang';
import * as m_factor from '@codemirror/legacy-modes/mode/factor';
import * as m_fcl from '@codemirror/legacy-modes/mode/fcl';
import * as m_forth from '@codemirror/legacy-modes/mode/forth';
import * as m_fortran from '@codemirror/legacy-modes/mode/fortran';
import * as m_gas from '@codemirror/legacy-modes/mode/gas';
import * as m_gherkin from '@codemirror/legacy-modes/mode/gherkin';
import * as m_go from '@codemirror/legacy-modes/mode/go';
import * as m_groovy from '@codemirror/legacy-modes/mode/groovy';
import * as m_haskell from '@codemirror/legacy-modes/mode/haskell';
import * as m_haxe from '@codemirror/legacy-modes/mode/haxe';
import * as m_http from '@codemirror/legacy-modes/mode/http';
import * as m_idl from '@codemirror/legacy-modes/mode/idl';
import * as m_javascript from '@codemirror/legacy-modes/mode/javascript';
import * as m_jinja2 from '@codemirror/legacy-modes/mode/jinja2';
import * as m_julia from '@codemirror/legacy-modes/mode/julia';
import * as m_livescript from '@codemirror/legacy-modes/mode/livescript';
import * as m_lua from '@codemirror/legacy-modes/mode/lua';
import * as m_mathematica from '@codemirror/legacy-modes/mode/mathematica';
import * as m_mbox from '@codemirror/legacy-modes/mode/mbox';
import * as m_mirc from '@codemirror/legacy-modes/mode/mirc';
import * as m_mllike from '@codemirror/legacy-modes/mode/mllike';
import * as m_modelica from '@codemirror/legacy-modes/mode/modelica';
import * as m_mscgen from '@codemirror/legacy-modes/mode/mscgen';
import * as m_mumps from '@codemirror/legacy-modes/mode/mumps';
import * as m_nginx from '@codemirror/legacy-modes/mode/nginx';
import * as m_nsis from '@codemirror/legacy-modes/mode/nsis';
import * as m_ntriples from '@codemirror/legacy-modes/mode/ntriples';
import * as m_octave from '@codemirror/legacy-modes/mode/octave';
import * as m_oz from '@codemirror/legacy-modes/mode/oz';
import * as m_pascal from '@codemirror/legacy-modes/mode/pascal';
import * as m_pegjs from '@codemirror/legacy-modes/mode/pegjs';
import * as m_perl from '@codemirror/legacy-modes/mode/perl';
import * as m_pig from '@codemirror/legacy-modes/mode/pig';
import * as m_powershell from '@codemirror/legacy-modes/mode/powershell';
import * as m_properties from '@codemirror/legacy-modes/mode/properties';
import * as m_protobuf from '@codemirror/legacy-modes/mode/protobuf';
import * as m_pug from '@codemirror/legacy-modes/mode/pug';
import * as m_puppet from '@codemirror/legacy-modes/mode/puppet';
import * as m_python from '@codemirror/legacy-modes/mode/python';
import * as m_q from '@codemirror/legacy-modes/mode/q';
import * as m_r from '@codemirror/legacy-modes/mode/r';
import * as m_rpm from '@codemirror/legacy-modes/mode/rpm';
import * as m_ruby from '@codemirror/legacy-modes/mode/ruby';
import * as m_rust from '@codemirror/legacy-modes/mode/rust';
import * as m_sas from '@codemirror/legacy-modes/mode/sas';
import * as m_sass from '@codemirror/legacy-modes/mode/sass';
import * as m_scheme from '@codemirror/legacy-modes/mode/scheme';
import * as m_shell from '@codemirror/legacy-modes/mode/shell';
import * as m_sieve from '@codemirror/legacy-modes/mode/sieve';
import * as m_smalltalk from '@codemirror/legacy-modes/mode/smalltalk';
import * as m_solr from '@codemirror/legacy-modes/mode/solr';
import * as m_sparql from '@codemirror/legacy-modes/mode/sparql';
import * as m_spreadsheet from '@codemirror/legacy-modes/mode/spreadsheet';
import * as m_sql from '@codemirror/legacy-modes/mode/sql';
import * as m_stex from '@codemirror/legacy-modes/mode/stex';
import * as m_stylus from '@codemirror/legacy-modes/mode/stylus';
import * as m_swift from '@codemirror/legacy-modes/mode/swift';
import * as m_tcl from '@codemirror/legacy-modes/mode/tcl';
import * as m_textile from '@codemirror/legacy-modes/mode/textile';
import * as m_tiddlywiki from '@codemirror/legacy-modes/mode/tiddlywiki';
import * as m_tiki from '@codemirror/legacy-modes/mode/tiki';
import * as m_toml from '@codemirror/legacy-modes/mode/toml';
import * as m_troff from '@codemirror/legacy-modes/mode/troff';
import * as m_ttcn from '@codemirror/legacy-modes/mode/ttcn';
import * as m_ttcn_cfg from '@codemirror/legacy-modes/mode/ttcn-cfg';
import * as m_turtle from '@codemirror/legacy-modes/mode/turtle';
import * as m_vb from '@codemirror/legacy-modes/mode/vb';
import * as m_vbscript from '@codemirror/legacy-modes/mode/vbscript';
import * as m_velocity from '@codemirror/legacy-modes/mode/velocity';
import * as m_verilog from '@codemirror/legacy-modes/mode/verilog';
import * as m_vhdl from '@codemirror/legacy-modes/mode/vhdl';
import * as m_wast from '@codemirror/legacy-modes/mode/wast';
import * as m_webidl from '@codemirror/legacy-modes/mode/webidl';
import * as m_xml from '@codemirror/legacy-modes/mode/xml';
import * as m_xquery from '@codemirror/legacy-modes/mode/xquery';
import * as m_yacas from '@codemirror/legacy-modes/mode/yacas';
import * as m_yaml from '@codemirror/legacy-modes/mode/yaml';
import * as m_z80 from '@codemirror/legacy-modes/mode/z80';

const MODULES: Record<string, Record<string, unknown>> = {
    'apl': m_apl,
    'asciiarmor': m_asciiarmor,
    'asn1': m_asn1,
    'asterisk': m_asterisk,
    'brainfuck': m_brainfuck,
    'clike': m_clike,
    'clojure': m_clojure,
    'cmake': m_cmake,
    'cobol': m_cobol,
    'coffeescript': m_coffeescript,
    'commonlisp': m_commonlisp,
    'crystal': m_crystal,
    'css': m_css,
    'cypher': m_cypher,
    'd': m_d,
    'diff': m_diff,
    'dockerfile': m_dockerfile,
    'dtd': m_dtd,
    'dylan': m_dylan,
    'ebnf': m_ebnf,
    'ecl': m_ecl,
    'eiffel': m_eiffel,
    'elm': m_elm,
    'erlang': m_erlang,
    'factor': m_factor,
    'fcl': m_fcl,
    'forth': m_forth,
    'fortran': m_fortran,
    'gas': m_gas,
    'gherkin': m_gherkin,
    'go': m_go,
    'groovy': m_groovy,
    'haskell': m_haskell,
    'haxe': m_haxe,
    'http': m_http,
    'idl': m_idl,
    'javascript': m_javascript,
    'jinja2': m_jinja2,
    'julia': m_julia,
    'livescript': m_livescript,
    'lua': m_lua,
    'mathematica': m_mathematica,
    'mbox': m_mbox,
    'mirc': m_mirc,
    'mllike': m_mllike,
    'modelica': m_modelica,
    'mscgen': m_mscgen,
    'mumps': m_mumps,
    'nginx': m_nginx,
    'nsis': m_nsis,
    'ntriples': m_ntriples,
    'octave': m_octave,
    'oz': m_oz,
    'pascal': m_pascal,
    'pegjs': m_pegjs,
    'perl': m_perl,
    'pig': m_pig,
    'powershell': m_powershell,
    'properties': m_properties,
    'protobuf': m_protobuf,
    'pug': m_pug,
    'puppet': m_puppet,
    'python': m_python,
    'q': m_q,
    'r': m_r,
    'rpm': m_rpm,
    'ruby': m_ruby,
    'rust': m_rust,
    'sas': m_sas,
    'sass': m_sass,
    'scheme': m_scheme,
    'shell': m_shell,
    'sieve': m_sieve,
    'smalltalk': m_smalltalk,
    'solr': m_solr,
    'sparql': m_sparql,
    'spreadsheet': m_spreadsheet,
    'sql': m_sql,
    'stex': m_stex,
    'stylus': m_stylus,
    'swift': m_swift,
    'tcl': m_tcl,
    'textile': m_textile,
    'tiddlywiki': m_tiddlywiki,
    'tiki': m_tiki,
    'toml': m_toml,
    'troff': m_troff,
    'ttcn': m_ttcn,
    'ttcn-cfg': m_ttcn_cfg,
    'turtle': m_turtle,
    'vb': m_vb,
    'vbscript': m_vbscript,
    'velocity': m_velocity,
    'verilog': m_verilog,
    'vhdl': m_vhdl,
    'wast': m_wast,
    'webidl': m_webidl,
    'xml': m_xml,
    'xquery': m_xquery,
    'yacas': m_yacas,
    'yaml': m_yaml,
    'z80': m_z80
};

/** mode name -> [legacy-modes file, the export inside it] */
const LEGACY: Record<string, [string, string]> = {
    'apl': ['apl', 'apl'],
    'asciiarmor': ['asciiarmor', 'asciiArmor'],
    'asn.1': ['asn1', 'asn1'],
    'asterisk': ['asterisk', 'asterisk'],
    'brainfuck': ['brainfuck', 'brainfuck'],
    'clike': ['clike', 'clike'],
    'clojure': ['clojure', 'clojure'],
    'cmake': ['cmake', 'cmake'],
    'cobol': ['cobol', 'cobol'],
    'coffeescript': ['coffeescript', 'coffeeScript'],
    'commonlisp': ['commonlisp', 'commonLisp'],
    'crystal': ['crystal', 'crystal'],
    'css': ['css', 'css'],
    'cypher': ['cypher', 'cypher'],
    'd': ['d', 'd'],
    'dart': ['clike', 'dart'],
    'diff': ['diff', 'diff'],
    'dockerfile': ['dockerfile', 'dockerFile'],
    'dtd': ['dtd', 'dtd'],
    'dylan': ['dylan', 'dylan'],
    'ebnf': ['ebnf', 'ebnf'],
    'ecl': ['ecl', 'ecl'],
    'eiffel': ['eiffel', 'eiffel'],
    'elm': ['elm', 'elm'],
    'erlang': ['erlang', 'erlang'],
    'factor': ['factor', 'factor'],
    'fcl': ['fcl', 'fcl'],
    'forth': ['forth', 'forth'],
    'fortran': ['fortran', 'fortran'],
    'gas': ['gas', 'gas'],
    'gherkin': ['gherkin', 'gherkin'],
    'go': ['go', 'go'],
    'groovy': ['groovy', 'groovy'],
    'haskell': ['haskell', 'haskell'],
    'haxe': ['haxe', 'haxe'],
    'http': ['http', 'http'],
    'idl': ['idl', 'idl'],
    'javascript': ['javascript', 'javascript'],
    'jinja2': ['jinja2', 'jinja2'],
    'julia': ['julia', 'julia'],
    'livescript': ['livescript', 'liveScript'],
    'lua': ['lua', 'lua'],
    'mathematica': ['mathematica', 'mathematica'],
    'mbox': ['mbox', 'mbox'],
    'mirc': ['mirc', 'mirc'],
    'mllike': ['mllike', 'oCaml'],
    'modelica': ['modelica', 'modelica'],
    'mscgen': ['mscgen', 'mscgen'],
    'mumps': ['mumps', 'mumps'],
    'nginx': ['nginx', 'nginx'],
    'nsis': ['nsis', 'nsis'],
    'ntriples': ['ntriples', 'ntriples'],
    'octave': ['octave', 'octave'],
    'oz': ['oz', 'oz'],
    'pascal': ['pascal', 'pascal'],
    'pegjs': ['pegjs', 'pegjs'],
    'perl': ['perl', 'perl'],
    'pig': ['pig', 'pig'],
    'powershell': ['powershell', 'powerShell'],
    'properties': ['properties', 'properties'],
    'protobuf': ['protobuf', 'protobuf'],
    'pug': ['pug', 'pug'],
    'puppet': ['puppet', 'puppet'],
    'python': ['python', 'python'],
    'q': ['q', 'q'],
    'r': ['r', 'r'],
    'rpm': ['rpm', 'rpmSpec'],
    // One file, two modes, and neither is called `rpm`: CodeMirror 5 defines
    // `rpm-spec` and `rpm-changes`, which is what the MIMEs resolve to.
    'rpm-spec': ['rpm', 'rpmSpec'],
    'rpm-changes': ['rpm', 'rpmChanges'],
    'ruby': ['ruby', 'ruby'],
    'rust': ['rust', 'rust'],
    'sas': ['sas', 'sas'],
    'sass': ['sass', 'sass'],
    'scheme': ['scheme', 'scheme'],
    'shell': ['shell', 'shell'],
    'sieve': ['sieve', 'sieve'],
    'smalltalk': ['smalltalk', 'smalltalk'],
    'solr': ['solr', 'solr'],
    'sparql': ['sparql', 'sparql'],
    'spreadsheet': ['spreadsheet', 'spreadsheet'],
    'sql': ['sql', 'sql'],
    'stex': ['stex', 'stex'],
    'stylus': ['stylus', 'stylus'],
    'swift': ['swift', 'swift'],
    'tcl': ['tcl', 'tcl'],
    'textile': ['textile', 'textile'],
    'tiddlywiki': ['tiddlywiki', 'tiddlyWiki'],
    'tiki': ['tiki', 'tiki'],
    'toml': ['toml', 'toml'],
    'troff': ['troff', 'troff'],
    'ttcn': ['ttcn', 'ttcn'],
    'ttcn-cfg': ['ttcn-cfg', 'ttcnCfg'],
    'turtle': ['turtle', 'turtle'],
    'vb': ['vb', 'vb'],
    'vbscript': ['vbscript', 'vbScript'],
    'velocity': ['velocity', 'velocity'],
    'verilog': ['verilog', 'verilog'],
    'vhdl': ['vhdl', 'vhdl'],
    'wast': ['wast', 'wast'],
    'webidl': ['webidl', 'webIDL'],
    'xml': ['xml', 'xml'],
    'xquery': ['xquery', 'xQuery'],
    'yacas': ['yacas', 'yacas'],
    'yaml': ['yaml', 'yaml'],
    'z80': ['z80', 'z80'],
};

/**
 * CodeMirror 5 token names that CodeMirror 6 only understands on their own.
 *
 * `@codemirror/language` translates the legacy vocabulary — `variable` to
 * `variableName`, `property` to `propertyName` and so on — but only when the
 * whole token string is one of them. Several modes emit *compound* tokens
 * instead: `javascript` marks a quoted object key as `cx.style + " property"`,
 * which arrives as `"string property"`, and the translation table never sees
 * it. Each space- or dot-separated part is looked up in `@lezer/highlight`'s
 * tags directly, `property` is not one of those, and the result is a warning
 * on the console and a key that is styled as a plain string.
 *
 * `tokenTable` is the documented way to add names, and it is consulted for
 * every part — so naming the legacy vocabulary here fixes the compound case
 * without touching the simple one. A mode with a `tokenTable` of its own
 * still wins, because it knows more about itself than this does.
 */
const LEGACY_TOKENS: Record<string, Tag> = {
    variable: t.variableName,
    'variable-2': t.special(t.variableName),
    'variable-3': t.typeName,
    property: t.propertyName,
    def: t.definition(t.variableName),
    builtin: t.standard(t.variableName),
    tag: t.tagName,
    attribute: t.attributeName,
    type: t.typeName,
    qualifier: t.modifier,
    error: t.invalid,
    header: t.heading,
    'string-2': t.special(t.string)
};

/**
 * The five with a real grammar.
 *
 * `gfm` is here rather than in the missing list: GitHub-flavoured markdown is
 * markdown with extensions, and `lang-markdown` has them.
 */
const LEZER: Record<string, () => Extension> = {
    markdown: () => markdown(),
    gfm: () => markdown(),
    htmlmixed: () => html(),
    html: () => html(),
    jsx: () => javascript({ jsx: true }),
    // TypeScript is not a mode of its own in CodeMirror 5 — it is the
    // javascript mode with `typescript: true` in the MIME's configuration, and
    // the legacy parser here is built from the mode name alone, which loses
    // that. So they are named, and get the grammar that knows the difference:
    // without this a `.ts` file has no language at all, and everything that
    // reads a syntax tree — the token under the cursor, indentation, folding —
    // has nothing to read.
    typescript: () => javascript({ typescript: true }),
    tsx: () => javascript({ jsx: true, typescript: true }),
    php: () => php()
};

/**
 * The thirteen with neither, and what they get instead.
 *
 * The template languages overlay HTML, so HTML is what the overlay was
 * decorating and the nearest true answer. The rest get nothing, which is
 * honest: highlighting reStructuredText as something else would be worse than
 * not highlighting it.
 *
 * Seven of the thirteen — handlebars, haskell-literate, htmlembedded, soy,
 * twig, vue, yaml-frontmatter — are not reachable at all: no file type in
 * `deploy/settings/default/default.behaviors` names them, so nothing can ask
 * for one. They are listed because a list you have to look up is worse than a
 * list you can read.
 */
const FALLBACK: Record<string, string | null> = {
    django: 'htmlmixed',
    smarty: 'htmlmixed',
    tornado: 'htmlmixed',
    twig: 'htmlmixed',
    handlebars: 'htmlmixed',
    htmlembedded: 'htmlmixed',
    soy: 'htmlmixed',
    vue: 'htmlmixed',
    haml: null,
    slim: null,
    rst: null,
    'haskell-literate': null,
    'yaml-frontmatter': 'markdown'
};

const loaded = new Map<string, Extension>();

/**
 * The mode a MIME type means, where the name does not simply fall out of it.
 *
 * Light Table opens a file as a MIME rather than as a mode name, and stripping
 * the prefix off one is right about half the time: `text/x-clojure` really is
 * `clojure`, but `text/x-clojurescript` is *also* `clojure`, `text/x-c` is
 * `clike`, `application/json` is `javascript` and `text/x-rsrc` is `r`. This is
 * every MIME the 130 bundled modes register where the answer is not the name.
 *
 * Ninety-eight of two hundred, which is why it is a table rather than a rule.
 * Generated once from `CodeMirror.mimeModes` — the modes fill that in as they
 * load — and kept, rather than read at runtime, because the point of the port
 * is that CodeMirror 5 stops being here to ask.
 *
 * The MIME can also carry *configuration* — `text/typescript` is the javascript
 * mode with `typescript: true` — and a name cannot. Where that difference
 * matters, the entry is in LEZER above under the name the MIME strips to.
 */
const MIME_MODES: Record<string, string> = {
    'application/dart': 'clike',
    'application/ecmascript': 'javascript',
    'application/edn': 'clojure',
    'application/javascript': 'javascript',
    'application/json': 'javascript',
    'application/ld+json': 'javascript',
    'application/manifest+json': 'javascript',
    'application/mbox': 'mbox',
    'application/n-quads': 'ntriples',
    'application/n-triples': 'ntriples',
    'application/pgp': 'asciiarmor',
    'application/pgp-encrypted': 'asciiarmor',
    'application/pgp-keys': 'asciiarmor',
    'application/pgp-signature': 'asciiarmor',
    'application/sieve': 'sieve',
    'application/sparql-query': 'sparql',
    'application/typescript': 'javascript',
    'application/vnd.coffeescript': 'coffeescript',
    'application/x-aspx': 'htmlembedded',
    'application/x-cypher-query': 'cypher',
    'application/x-ejs': 'htmlembedded',
    'application/x-erb': 'htmlembedded',
    'application/x-httpd-php': 'php',
    'application/x-httpd-php-open': 'php',
    'application/x-json': 'javascript',
    'application/x-jsp': 'htmlembedded',
    'application/x-sh': 'shell',
    'application/xml': 'xml',
    'application/xml-dtd': 'dtd',
    'application/xquery': 'xquery',
    'message/http': 'http',
    'script/x-vue': 'vue',
    'text/ecmascript': 'javascript',
    'text/html': 'htmlmixed',
    'text/n-triples': 'ntriples',
    'text/plain': 'null',
    'text/typescript': 'javascript',
    'text/typescript-jsx': 'jsx',
    'text/webassembly': 'wast',
    'text/x-c': 'clike',
    'text/x-c++hdr': 'clike',
    'text/x-c++src': 'clike',
    'text/x-cassandra': 'sql',
    'text/x-ceylon': 'clike',
    'text/x-chdr': 'clike',
    'text/x-clojurescript': 'clojure',
    'text/x-common-lisp': 'commonlisp',
    'text/x-csharp': 'clike',
    'text/x-csrc': 'clike',
    'text/x-cython': 'python',
    'text/x-esper': 'sql',
    'text/x-ez80': 'z80',
    'text/x-feature': 'gherkin',
    'text/x-fsharp': 'mllike',
    'text/x-gpsql': 'sql',
    'text/x-gql': 'sql',
    'text/x-gss': 'css',
    'text/x-handlebars-template': 'handlebars',
    'text/x-hive': 'sql',
    'text/x-ini': 'properties',
    'text/x-jade': 'pug',
    'text/x-java': 'clike',
    'text/x-kotlin': 'clike',
    'text/x-latex': 'stex',
    'text/x-less': 'css',
    'text/x-literate-haskell': 'haskell-literate',
    'text/x-mariadb': 'sql',
    'text/x-msgenny': 'mscgen',
    'text/x-mssql': 'sql',
    'text/x-mysql': 'sql',
    'text/x-nesc': 'clike',
    'text/x-nginx-conf': 'nginx',
    'text/x-objectivec': 'clike',
    'text/x-objectivec++': 'clike',
    'text/x-ocaml': 'mllike',
    'text/x-pgsql': 'sql',
    'text/x-php': 'clike',
    'text/x-plsql': 'sql',
    'text/x-rsrc': 'r',
    'text/x-rustsrc': 'rust',
    'text/x-scala': 'clike',
    'text/x-scss': 'css',
    'text/x-sh': 'shell',
    'text/x-sml': 'mllike',
    'text/x-sparksql': 'sql',
    'text/x-sqlite': 'sql',
    'text/x-squirrel': 'clike',
    'text/x-stsrc': 'smalltalk',
    'text/x-styl': 'stylus',
    'text/x-systemverilog': 'verilog',
    'text/x-tlv': 'verilog',
    'text/x-trino': 'sql',
    'text/x-ttcn-asn': 'asn.1',
    'text/x-ttcn3': 'ttcn',
    'text/x-ttcnpp': 'ttcn',
    'text/x-xu': 'mscgen',
    'x-shader/x-fragment': 'clike',
    'x-shader/x-vertex': 'clike',
};

/**
 * What to look this name up as.
 *
 * The stripped name first, so `typescript` and `tsx` reach the entries written
 * for them here rather than being resolved through CodeMirror 5's table to
 * plain `javascript` — which is what that table says they are, because there
 * the difference is carried in the MIME's configuration and not in the name.
 */
function resolve(name: string): string {
    const stripped = (name || '').replace(/^text\/x-|^application\/x-|^text\//, '').toLowerCase();
    if (stripped in LEZER || stripped in LEGACY || stripped in FALLBACK) return stripped;
    return MIME_MODES[(name || '').toLowerCase()] ?? stripped;
}

/**
 * The extension for `name`, or an empty one when nothing here knows it.
 *
 * An empty extension is a document with no highlighting, which is what
 * CodeMirror 5 does for a mode it has not loaded — so an unknown name is the
 * same non-event it always was.
 */
export function modeExtension(name: string): Extension {
    const mode = resolve(name);
    if (loaded.has(mode)) return loaded.get(mode)!;

    let extension: Extension = [];
    const lezer = LEZER[mode];
    const legacy = LEGACY[mode];
    if (lezer) {
        extension = lezer();
    } else if (legacy) {
        const [file, exp] = legacy;
        const found = MODULES[file]?.[exp];
        // A few of these export a *factory* rather than a parser — `clike` is
        // one, because C, Java and friends are the same tokenizer with
        // different keyword sets. Calling it with no options gives the plain
        // one, which is what a mode name with no configuration asked for.
        const parser = (typeof found === 'function'
            ? (found as (config: unknown) => StreamParser<unknown>)({})
            : found) as StreamParser<unknown> | undefined;
        if (parser && typeof parser.token === 'function') {
            extension = StreamLanguage.define({ ...parser, tokenTable: { ...LEGACY_TOKENS, ...parser.tokenTable } });
        }
    } else if (mode in FALLBACK) {
        const to = FALLBACK[mode];
        extension = to ? modeExtension(to) : [];
    }

    loaded.set(mode, extension);
    return extension;
}

/** Every mode name this can answer for. */
export function knownModes(): string[] {
    return [...new Set([...Object.keys(LEZER), ...Object.keys(LEGACY), ...Object.keys(FALLBACK)])].sort();
}

/** The ones with no CodeMirror 6 grammar, and what they fall back to. */
export function unsupportedModes(): Record<string, string | null> {
    return { ...FALLBACK };
}

declare global {
    interface Window { ltCm6Modes?: unknown }
}

window.ltCm6Modes = { modeExtension, knownModes, unsupportedModes };
