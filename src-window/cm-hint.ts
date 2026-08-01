// Where the autocomplete list goes.
//
// Light Table has never used CodeMirror's completion addon — it has a hint list
// of its own, built from the same filter-list the command bar uses, and the
// only thing it wants from the editor is a rectangle to sit under. That is the
// whole of the coupling, and it is why autocomplete crossed to CodeMirror 6 by
// growing one method rather than being rewritten.
//
// So this asks for a cursor rectangle and nothing else. Either engine answers.

import CodeMirror = require('codemirror');

/** All an editor has to be for the hint list to find it. */
interface Positionable {
  cursorCoords(from: unknown): { left: number, top: number, bottom: number };
}

/**
 * Put `hints` under the cursor, or above it when there is no room below.
 *
 * `from` is handed to the editor untouched. Both engines read a number as "one
 * end of the selection" rather than as a character offset, so the list appears
 * at the cursor and not at the start of the token being completed. That is what
 * it has always done; it is written down here because the parameter's name says
 * otherwise.
 */
export function positionHint(cm: Positionable, hints: HTMLElement, from: unknown): void {
  hints.classList.add("CodeMirror-hints");

  var pos = cm.cursorCoords(from);
  var left = pos.left, top = pos.bottom;
  hints.style.left = left + "px";
  hints.style.bottom = "";
  hints.style.top = top + "px";
  // If we're at the edge of the screen, then we want the menu to appear on the left of the cursor.
  var winW = window.innerWidth || Math.max(document.body.offsetWidth, document.documentElement.offsetWidth);
  var winH = window.innerHeight || Math.max(document.body.offsetHeight, document.documentElement.offsetHeight);
  var box = hints.getBoundingClientRect();
  var overlapX = box.right - winW, overlapY = box.bottom - winH;
  if (overlapX > 0) {
    if (box.right - box.left > winW) {
      hints.style.width = (winW - 5) + "px";
      overlapX -= (box.right - box.left) - winW;
    }
    hints.style.left = (left = pos.left - overlapX) + "px";
  }
  if (overlapY > 0) {
    var height = box.bottom - box.top;
    if (box.top - (pos.bottom - pos.top) - height > 0) {
      overlapY = height + (pos.bottom - pos.top);
      hints.style.top = "";
      hints.style.bottom = winH - pos.top + 5 + "px";
    } else if (height > winH) {
      hints.style.height = (winH - 5) + "px";
      overlapY -= height - winH;
      hints.style.top = (top = pos.bottom - overlapY) + "px";
    }
  }
  document.body.appendChild(hints);
}

/** Scroll `node` into view within the hint list. */
export function ensureHintVisible(_cm: unknown, hints: HTMLElement, node: HTMLElement): void {
  if (node.offsetTop < hints.scrollTop)
    hints.scrollTop = node.offsetTop - 3;
  else if (node.offsetTop + node.offsetHeight > hints.scrollTop + hints.clientHeight)
    hints.scrollTop = node.offsetTop + node.offsetHeight - hints.clientHeight + 3;
}

// Still on the CodeMirror 5 global, because that is where a plugin looks and a
// plugin is not ours to edit. Light Table itself goes through
// `lt.window.modules` now, which is what lets the hint list position against an
// editor the global has never heard of.
CodeMirror.positionHint = positionHint as unknown as NonNullable<typeof CodeMirror.positionHint>;
CodeMirror.ensureHintVisible = ensureHintVisible;
