#!/usr/bin/env node
'use strict';

// Generates the plugin API reference into doc/api/ as markdown.
//
// This replaces codox, which was a Leiningen plugin and so required a
// project.clj that built nothing else, a JVM, and a `--add-opens` to get past
// the module system. The input here is clj-kondo's static analysis, which the
// repository already depends on for linting, so the whole toolchain is one
// native binary that is installed anyway.
//
// Markdown rather than HTML on purpose. The output is committed, so it reads
// on GitHub as well as on the published site, and a diff shows what changed
// about the API rather than what changed about a doc generator's templates.
// CI regenerates and fails if the tree differs, which is what keeps it honest.
//
//     node script/gen-api-docs.js
//     node script/gen-api-docs.js --check   # exit 1 if the output would change

const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.join(__dirname, '..');
const OUT_DIR = path.join(ROOT, 'doc', 'api');
const REPO = 'TrevorS/LightTable';
const REF = 'develop';

// The public surface a plugin is written against. This list is the definition
// of that surface — a namespace is in it because plugins are expected to call
// it, not because it happens to be public. It is the set codox published,
// unchanged, so the published API does not silently grow or shrink with this
// change.
const NAMESPACES = [
  { ns: 'lt.macros', file: 'src/lt/macros.cljc' },
  { ns: 'lt.object', file: 'src/lt/object.cljs' },
  { ns: 'lt.objs.command', file: 'src/lt/objs/command.cljs' },
  { ns: 'lt.objs.editor', file: 'src/lt/objs/editor.cljs' },
  { ns: 'lt.objs.editor.pool', file: 'src/lt/objs/editor/pool.cljs' },
  { ns: 'lt.objs.files', file: 'src/lt/objs/files.cljs' },
  { ns: 'lt.objs.notifos', file: 'src/lt/objs/notifos.cljs' },
];

const KIND = {
  'clojure.core/defmacro': 'macro',
  'cljs.core/defmacro': 'macro',
  'lt.macros/defui': 'ui',
  'clojure.core/defmulti': 'multimethod',
  'cljs.core/defmulti': 'multimethod',
  'clojure.core/def': 'var',
  'cljs.core/def': 'var',
};

function analyze() {
  const args = [
    '--no-install', 'clj-kondo',
    '--lint', NAMESPACES.map((n) => n.file).join(':'),
    '--config',
    // skip-comments because a (comment ...) block is a scratchpad, and
    // src/lt/macros.cljc has one defining a `cool` element that no caller has
    // ever been able to reach.
    '{:skip-comments true :output {:analysis {:arglists true} :format :json} :linters {}}',
  ];
  const out = execFileSync('npx', args, {
    cwd: ROOT,
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
    // clj-kondo exits 2 when it finds anything; the linters are off, but the
    // exit code is not worth depending on either way.
    stdio: ['ignore', 'pipe', 'ignore'],
  });
  return JSON.parse(out).analysis;
}

// A .cljc file is analysed once per dialect, so every var in lt.macros arrives
// twice. Keyed by namespace and name, first wins — the two are identical for
// everything this documents.
function dedupe(items) {
  const seen = new Map();
  for (const item of items) {
    const key = `${item.ns || item.name}/${item.name}`;
    if (!seen.has(key)) seen.set(key, item);
  }
  return [...seen.values()];
}

// Clojure names carry characters an HTML id cannot, and flattening them
// collides: `cursor` and `->cursor` reduce to the same thing, as do
// `selection` and `+selection`. Slugs are therefore assigned per page, with a
// counter on anything already taken, so a link never lands on the wrong var.
function slugger() {
  const taken = new Map();
  return (name) => {
    const base = `var-${name.replace(/[^A-Za-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'x'}`;
    const n = (taken.get(base) || 0) + 1;
    taken.set(base, n);
    return n === 1 ? base : `${base}-${n}`;
  };
}

function sourceUrl(file, line) {
  return `https://github.com/${REPO}/blob/${REF}/${file}${line ? `#L${line}` : ''}`;
}

// Docstrings are indented to sit under the opening quote in source, so every
// line after the first carries the indentation of the form. Left as-is, a
// four-space run makes markdown render the rest of the docstring as a code
// block. Strip the common indent and keep the relative shape, which is what
// the nested bullet lists in these docstrings need.
function dedent(doc) {
  const lines = doc.replace(/\t/g, '  ').split('\n');
  const rest = lines.slice(1).filter((l) => l.trim());
  const indent = rest.length
    ? Math.min(...rest.map((l) => l.match(/^ */)[0].length))
    : 0;
  return [lines[0].trim(), ...lines.slice(1).map((l) => l.slice(indent).trimEnd())]
    .join('\n')
    .trim();
}

// `[[cursor]]` in a docstring is codox's wiki-link syntax, which markdown
// renders verbatim. A name defined on the same page becomes a real link; a
// name from elsewhere — `[[lt.objs.plugins/scan]]` — becomes code, because
// pointing at a page that is not generated would be a dead link.
function wikilinks(text, anchorFor) {
  return text.replace(/\[\[([^\][\s]+)\]\]/g, (_, name) => {
    const anchor = anchorFor(name);
    return anchor ? `[\`${name}\`](#${anchor})` : `\`${name}\``;
  });
}

function summary(doc) {
  if (!doc) return '';
  const body = dedent(doc);
  const first = body.split('\n')[0].trim();
  const stop = first.match(/^(.*?[.!?])(\s|$)/);
  // A first line with no sentence end, followed by more, is a fragment: it
  // gets an ellipsis rather than whatever punctuation the line break fell
  // after. A one-line docstring is the whole summary and is left alone.
  const truncated = !stop && (body.length > first.length);
  const text = stop ? stop[1] : first + (truncated ? '…' : '');
  return text.replace(/\s*[,;:]…$/, '…').replace(/\|/g, '\\|');
}

function renderVar(v, file, anchor, anchorFor) {
  const lines = [];
  lines.push(`<a id="${anchor}"></a>`);
  lines.push('');
  lines.push(`### \`${v.name}\``);
  lines.push('');

  const kind = KIND[v['defined-by']];
  if (kind && kind !== 'var') lines.push(`*${kind}*`, '');

  const arglists = v['arglist-strs'] || [];
  if (arglists.length) {
    lines.push('```clojure');
    for (const a of arglists) lines.push(`(${v.name} ${a.replace(/^\[|\]$/g, '')})`.replace(/ \)$/, ')'));
    lines.push('```');
    lines.push('');
  }

  lines.push(v.doc ? wikilinks(dedent(v.doc), anchorFor) : '*Undocumented.*');
  lines.push('');
  lines.push(`[source](${sourceUrl(file, v['name-row'])})`);
  lines.push('');
  return lines.join('\n');
}

function renderNamespace(entry, nsDoc, vars) {
  const lines = [];
  lines.push(`# ${entry.ns}`);
  lines.push('');
  if (nsDoc) lines.push(dedent(nsDoc), '');
  lines.push(`Source: [\`${entry.file}\`](${sourceUrl(entry.file)})`);
  lines.push('');
  lines.push('[← API index](README.md)');
  lines.push('');

  if (!vars.length) {
    lines.push('This namespace has no public vars.');
    lines.push('');
    return lines.join('\n');
  }

  const next = slugger();
  const anchors = vars.map((v) => next(v.name));
  // First definition wins where a name was slugged twice, which is the one a
  // docstring saying [[cursor]] means.
  const byName = new Map();
  vars.forEach((v, i) => { if (!byName.has(v.name)) byName.set(v.name, anchors[i]); });
  const anchorFor = (name) => byName.get(name);

  lines.push('| | |');
  lines.push('|---|---|');
  vars.forEach((v, i) => {
    lines.push(`| [\`${v.name}\`](#${anchors[i]}) | ${wikilinks(summary(v.doc), anchorFor) || '—'} |`);
  });
  lines.push('');
  lines.push('## Vars');
  lines.push('');
  vars.forEach((v, i) => lines.push(renderVar(v, entry.file, anchors[i], anchorFor)));
  return lines.join('\n');
}

function renderIndex(pages) {
  const lines = [];
  lines.push('# API reference');
  lines.push('');
  lines.push(
    'The namespaces a plugin is written against. Generated from the source by',
    '`script/gen-api-docs.js`, so anything here is what the editor on `' + REF + '`',
    'actually defines; if a docstring is missing below it is missing in the source.',
    '',
    'This is the plugin-facing surface, not every public var in Light Table. A',
    'namespace outside this list can still be required, but nothing promises it',
    'will keep its shape.',
    '',
  );
  lines.push('| | | |');
  lines.push('|---|---|---|');
  for (const p of pages) {
    lines.push(`| [\`${p.ns}\`](${p.ns}.md) | ${p.doc ? summary(p.doc) : '—'} | ${p.count} vars |`);
  }
  lines.push('');
  lines.push('See also [Behaviors, Objects and Tags](../BOT.md) for the model these');
  lines.push('namespaces implement, and');
  lines.push(`[plugins/README.md](https://github.com/${REPO}/blob/${REF}/plugins/README.md)`);
  lines.push('for how a plugin is packaged.');
  lines.push('');
  return lines.join('\n');
}

function build() {
  const analysis = analyze();
  const wanted = new Set(NAMESPACES.map((n) => n.ns));

  const nsDocs = new Map();
  for (const n of analysis['namespace-definitions'] || []) {
    if (wanted.has(n.name) && n.doc && !nsDocs.has(n.name)) nsDocs.set(n.name, n.doc);
  }

  const byNs = new Map(NAMESPACES.map((n) => [n.ns, []]));
  for (const v of dedupe(analysis['var-definitions'] || [])) {
    if (v.private || !byNs.has(v.ns)) continue;
    byNs.get(v.ns).push(v);
  }

  const files = new Map();
  const pages = [];
  for (const entry of NAMESPACES) {
    const vars = byNs.get(entry.ns).sort((a, b) => a.name.localeCompare(b.name, 'en'));
    const doc = nsDocs.get(entry.ns);
    files.set(`${entry.ns}.md`, renderNamespace(entry, doc, vars));
    pages.push({ ns: entry.ns, doc, count: vars.length });
  }
  files.set('README.md', renderIndex(pages));
  return files;
}

function main() {
  const check = process.argv.includes('--check');
  const files = build();

  const existing = fs.existsSync(OUT_DIR) ? fs.readdirSync(OUT_DIR) : [];
  const stale = existing.filter((f) => !files.has(f));
  const changed = [];

  for (const [name, body] of files) {
    const target = path.join(OUT_DIR, name);
    const before = fs.existsSync(target) ? fs.readFileSync(target, 'utf8') : null;
    if (before === body) continue;
    changed.push(name);
    if (!check) {
      fs.mkdirSync(OUT_DIR, { recursive: true });
      fs.writeFileSync(target, body);
    }
  }
  if (!check) for (const f of stale) fs.rmSync(path.join(OUT_DIR, f));

  if (check && (changed.length || stale.length)) {
    const out = [...changed.map((f) => `changed: ${f}`), ...stale.map((f) => `stale: ${f}`)];
    console.error(`doc/api is out of date; run \`npm run docs:api\`\n${out.join('\n')}`);
    process.exit(1);
  }
  console.log(
    check
      ? 'doc/api is up to date'
      : `wrote ${files.size} file(s) to doc/api (${changed.length} changed, ${stale.length} removed)`,
  );
}

main();
