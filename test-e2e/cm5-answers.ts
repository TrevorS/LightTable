// What CodeMirror 5 said, recorded on the day it was deleted.
//
// The port's tests were comparisons: run the same call on both engines and
// assert they agree. That is the right test while both are here and an
// impossible one afterwards, so the answers were captured from a running
// CodeMirror 5 and are now the expectation. Same assertions, one engine.
//
// Nothing regenerates this. If an answer here is wrong it was wrong in
// CodeMirror 5 too, and changing it is a decision to differ from the editor
// this replaced — worth making, worth writing down next to the change.

import answers from './cm5-answers.json';

const table = answers as Record<string, unknown>;

/**
 * What CodeMirror 5 answered for `key`.
 *
 * Throws on a key nobody recorded, rather than comparing against undefined —
 * a test that passes because both sides are missing is the failure this is
 * meant to prevent.
 */
export function cm5(key: string): unknown {
    if (!(key in table)) {
        throw new Error(`No recorded CodeMirror 5 answer for ${JSON.stringify(key)}. ` +
                        'It was captured from a running editor; see cm5-answers.ts.');
    }
    const value = table[key];
    // JSON has no way to say `undefined`, and CodeMirror 5 answers it for a
    // line past the end of the document. Spelled out rather than recorded as
    // null, which is a different answer.
    if (value && typeof value === 'object' && '__undefined__' in value) return undefined;
    return value;
}
