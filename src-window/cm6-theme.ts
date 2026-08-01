// Light Table's thirty themes, applied to a CodeMirror 6 editor.
//
// The themes are not ours to rewrite. `deploy/core/css/themes` is thirty files
// of other people's colour schemes, each written against CodeMirror 5's class
// names, and a port that required editing all of them would be a port nobody
// finishes — and would break every theme a user wrote.
//
// So this makes CodeMirror 6 answer to those names instead, in two halves that
// turn out to be very different sizes.
//
// **The colours are nearly free.** CodeMirror 5 marks a keyword `cm-keyword`,
// and so does every theme. CodeMirror 6 does not name tokens at all — it has a
// syntax tree tagged with `@lezer/highlight` tags, and a `HighlightStyle` says
// what to do with them. Saying "put class `cm-keyword` on it" is a table, below,
// and once it is written every theme's token colours work untouched.
//
// **The chrome is the work.** Twenty-one structural class names — the cursor,
// the selection, the gutters, the active line — and CodeMirror 6 calls each of
// them something else. Some sit on elements that exist for the editor's whole
// life and can simply be labelled with both names. The rest are on elements
// CodeMirror creates and destroys as you scroll, where there is nothing to
// label, so the *rules* are mirrored instead: each theme rule that mentions a
// CodeMirror 5 class gets a twin naming the CodeMirror 6 one. That happens at
// runtime, against the stylesheet as loaded, which means a theme nobody
// anticipated works the same as the thirty that ship.

import { HighlightStyle, syntaxHighlighting } from '@codemirror/language';
import { tags as t } from '@lezer/highlight';
import { EditorView, ViewPlugin } from '@codemirror/view';
import type { Extension } from '@codemirror/state';

/**
 * CodeMirror 5's token vocabulary, as CodeMirror 6 tags.
 *
 * No colours here on purpose: the class is the whole output, and what it looks
 * like is the theme's business. That is the same division CodeMirror 5 had, and
 * keeping it is why the theme files did not have to change.
 */
export const legacyHighlightStyle = HighlightStyle.define([
    { tag: [t.keyword, t.moduleKeyword, t.controlKeyword, t.definitionKeyword,
            t.operatorKeyword, t.self], class: 'cm-keyword' },
    { tag: [t.atom, t.bool, t.null, t.unit], class: 'cm-atom' },
    { tag: [t.number, t.integer, t.float], class: 'cm-number' },
    { tag: [t.definition(t.variableName), t.definition(t.propertyName)], class: 'cm-def' },
    { tag: [t.variableName, t.name, t.labelName], class: 'cm-variable' },
    { tag: [t.special(t.variableName), t.local(t.variableName)], class: 'cm-variable-2' },
    // CodeMirror 5 has no `cm-type`; a type name is a `variable-3`, which is
    // why themes colour that one and not the obvious name.
    { tag: [t.typeName, t.namespace, t.className], class: 'cm-variable-3' },
    { tag: [t.propertyName], class: 'cm-property' },
    { tag: [t.operator, t.derefOperator, t.arithmeticOperator, t.logicOperator,
            t.bitwiseOperator, t.compareOperator, t.updateOperator,
            t.definitionOperator, t.typeOperator], class: 'cm-operator' },
    { tag: [t.comment, t.lineComment, t.blockComment, t.docComment], class: 'cm-comment' },
    { tag: [t.string, t.docString, t.character], class: 'cm-string' },
    { tag: [t.special(t.string), t.regexp, t.escape], class: 'cm-string-2' },
    { tag: [t.meta, t.processingInstruction, t.annotation, t.documentMeta], class: 'cm-meta' },
    { tag: [t.modifier], class: 'cm-qualifier' },
    { tag: [t.standard(t.variableName), t.standard(t.name), t.macroName], class: 'cm-builtin' },
    { tag: [t.bracket, t.paren, t.brace, t.squareBracket, t.angleBracket], class: 'cm-bracket' },
    { tag: [t.tagName], class: 'cm-tag' },
    { tag: [t.attributeName], class: 'cm-attribute' },
    { tag: [t.heading], class: 'cm-header' },
    { tag: [t.quote], class: 'cm-quote' },
    { tag: [t.link, t.url], class: 'cm-link' },
    { tag: [t.invalid], class: 'cm-error' },
    { tag: [t.contentSeparator], class: 'cm-hr' },
    { tag: [t.emphasis], class: 'cm-em' },
    { tag: [t.strong], class: 'cm-strong' },
    { tag: [t.strikethrough], class: 'cm-strikethrough' },
    { tag: [t.function(t.variableName), t.function(t.propertyName)], class: 'cm-variable' }
]);

/**
 * Elements that live as long as the editor, labelled with both names.
 *
 * Only four, and they are the four that carry a theme's typography and
 * background — so this alone is the difference between an editor that looks
 * like a text area and one that looks like Light Table.
 */
const stampLegacyClasses = ViewPlugin.fromClass(class {
    constructor(view: EditorView) { this.stamp(view); }
    update(update: { view: EditorView }): void { this.stamp(update.view); }

    private stamp(view: EditorView): void {
        view.dom.classList.add('CodeMirror');
        view.scrollDOM.classList.add('CodeMirror-scroll');
        view.contentDOM.classList.add('CodeMirror-code', 'CodeMirror-lines');
        // The gutters element is built with the view but is not one of the
        // three it hands out, so it is found rather than known. Re-checked on
        // update because a configuration change can rebuild it.
        view.dom.querySelector('.cm-gutters')?.classList.add('CodeMirror-gutters');
    }
});

/**
 * CodeMirror 5's structural class names, and what CodeMirror 6 calls them.
 *
 * Longest first, because `.CodeMirror-cursor` must be recognised before
 * `.CodeMirror`, and a substitution that ran in the other order would turn it
 * into `.cm-editor-cursor` — a selector that matches nothing and would have
 * been very hard to see.
 *
 * `.CodeMirror-hints` is missing on purpose, and permanently: the autocomplete
 * list is Light Table's own element wearing a CodeMirror-shaped class name, not
 * anything CodeMirror draws. It keeps that name on both engines, so a theme's
 * rules already apply and a twin would have nothing to match. The search
 * classes were in this list's other category — absent because unported — until
 * search landed, which is what the list is for: a gap you can see is a gap
 * somebody closes.
 */
const CLASS_MAP: [string, string][] = [
    ['.CodeMirror-activeline-background', '.cm-activeLine'],
    ['.CodeMirror-nonmatchingbracket', '.cm-nonmatchingBracket'],
    ['.CodeMirror-secondarycursor', '.cm-cursor-secondary'],
    ['.CodeMirror-matchingbracket', '.cm-matchingBracket'],
    ['.CodeMirror-activeline', '.cm-activeLine'],
    ['.CodeMirror-linenumbers', '.cm-lineNumbers'],
    ['.CodeMirror-linenumber', '.cm-lineNumbers .cm-gutterElement'],
    ['.CodeMirror-searching-active', '.cm-searchMatch-selected'],
    ['.CodeMirror-matchhighlight', '.cm-selectionMatch'],
    ['.CodeMirror-searching', '.cm-searchMatch'],
    ['.CodeMirror-selected', '.cm-selectionBackground'],
    ['.CodeMirror-cursors', '.cm-cursorLayer'],
    ['.CodeMirror-gutters', '.cm-gutters'],
    ['.CodeMirror-focused', '.cm-focused'],
    ['.CodeMirror-cursor', '.cm-cursor'],
    ['.CodeMirror-lines', '.cm-content'],
    ['.CodeMirror', '.cm-editor']
];

/** The CodeMirror 6 twin of a selector, or null when it says nothing about one. */
export function mirrorSelector(selector: string): string | null {
    let out = selector;
    let touched = false;
    for (const [legacy, modern] of CLASS_MAP) {
        // A class name ends where a character that cannot be in one begins.
        // Without that guard `.CodeMirror` would also match inside
        // `.CodeMirror-foo` for any name not in the table above, and rewrite it
        // into nonsense.
        const pattern = new RegExp(escapeForRegExp(legacy) + '(?![\\w-])', 'g');
        if (pattern.test(out)) {
            out = out.replace(pattern, modern);
            touched = true;
        }
    }
    return touched ? out : null;
}

const escapeForRegExp = (s: string): string => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/** Sheets already mirrored, so a second pass over `<head>` is free. */
const mirrored = new WeakSet<CSSStyleSheet>();

/**
 * Give every rule in `sheet` that names a CodeMirror 5 class a twin naming the
 * CodeMirror 6 one.
 *
 * Appended to the same sheet, immediately after the original, so specificity
 * and order work out the way the theme's author intended — a later rule in the
 * file still wins over an earlier one, including over an earlier twin.
 */
export function mirrorSheet(sheet: CSSStyleSheet): number {
    if (mirrored.has(sheet)) return 0;
    mirrored.add(sheet);

    let rules: CSSRuleList;
    try {
        rules = sheet.cssRules;
    } catch {
        // A stylesheet the document may not read. Ours are all local, so this
        // is somebody else's and not ours to mirror.
        return 0;
    }

    const twins: string[] = [];
    for (const rule of Array.from(rules)) {
        if (!(rule instanceof CSSStyleRule)) continue;
        const selector = mirrorSelector(rule.selectorText);
        if (selector) twins.push(`${selector} { ${rule.style.cssText} }`);
    }

    let added = 0;
    for (const twin of twins) {
        try {
            sheet.insertRule(twin, sheet.cssRules.length);
            added += 1;
        } catch {
            // One rule the browser will not take is one rule, not a reason to
            // abandon the theme.
        }
    }
    return added;
}

/** Every stylesheet in the document, mirrored. Safe to call repeatedly. */
export function mirrorAllSheets(): number {
    let added = 0;
    for (const sheet of Array.from(document.styleSheets)) {
        added += mirrorSheet(sheet as CSSStyleSheet);
    }
    return added;
}

/**
 * Keep mirroring as themes arrive.
 *
 * `lt.objs.style/inject-theme` appends a `<link>` to the head when you pick a
 * theme, and its rules are not readable until it has loaded — so this watches
 * for the element and then for the load, rather than assuming either.
 */
export function watchForThemes(): void {
    const onLoad = (node: HTMLLinkElement): void => {
        if (node.sheet) mirrorSheet(node.sheet);
        else node.addEventListener('load', () => { if (node.sheet) mirrorSheet(node.sheet); }, { once: true });
    };

    new MutationObserver((records) => {
        for (const record of records) {
            for (const node of Array.from(record.addedNodes)) {
                if (node instanceof HTMLLinkElement && node.rel === 'stylesheet') onLoad(node);
                if (node instanceof HTMLStyleElement && node.sheet) mirrorSheet(node.sheet);
            }
        }
    }).observe(document.head, { childList: true });

    mirrorAllSheets();
}

/** Everything an editor needs to wear a Light Table theme. */
export function themeExtensions(): Extension {
    return [syntaxHighlighting(legacyHighlightStyle, { fallback: true }), stampLegacyClasses];
}

declare global {
    interface Window { ltCm6Theme?: unknown }
}

window.ltCm6Theme = { mirrorSelector, mirrorSheet, mirrorAllSheets, watchForThemes, legacyHighlightStyle };
