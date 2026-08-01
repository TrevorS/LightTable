/**
 * The one type web-tree-sitter's own declarations reach for and do not define.
 *
 * It used to arrive with the CodeMirror 5 type declarations, which were deleted
 * with the engine. web-tree-sitter names it in one signature and never gives it
 * a shape, and nothing here touches the field — so it is declared rather than
 * depended on.
 */
declare type EmscriptenModule = Record<string, unknown>;
