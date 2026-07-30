// Drag-and-drop reordering, for the tab bar.
//
// Ported from deploy/core/lighttable/ui/dragdrop.js, which was evaluated into
// global scope by `lt.util.load/js` and published a `window.sortable` for
// ClojureScript to find. It is a module now: `lt.objs.tabs` requires it and
// calls `sortable` directly, so nothing is published on `window` and nothing
// is evaluated.
//
// Compiled to deploy/core/window/dragdrop.js by `npm run build:window`.

/**
 * The parts of `lt.util.dom` this uses, under their compiled names.
 *
 * Declared rather than imported because it is ClojureScript: the namespace is
 * a global that the bundle defines, and `add_class` is `add-class` munged.
 */
interface Dom {
    make(html: string): HTMLElement[];
    children(el: Element): Element[];
    $$(selector: string): Element[];
    index(el: Element): number;
    height(el: Element): number;
    width(el: Element): number;
    parent(el: Element): SortableParent | null;
    css(el: Element, styles: Record<string, string>): void;
    add_class(el: Element, cls: string): void;
    remove_class(el: Element, cls: string): void;
    after(el: Element, other: Element): void;
    before(el: Element, other: Element): void;
    append(el: Element, child: Element): void;
    remove(el: Element): void;
    trigger(el: Element, event: string, data?: unknown): void;
    on(el: Element, event: string, handler: (this: Element, e: any) => unknown): void;
}

/** A list that has been made sortable, tagged with the group it connects to. */
type SortableParent = Element & { sortConnect?: string };

declare const lt: { util: { dom: Dom } };

export interface SortableOptions {
    /** Selector for other lists items may be dragged into. */
    connectWith?: string;
}

// Shared across every sortable list, because a drag moves between them: the
// element being dragged and the gap left for it are properties of the drag,
// not of the list it started in.
let dragging: Element | null = null;
let placeholder: HTMLElement | null = null;

/** Make the children of `me` reorderable by dragging. */
export function sortable(me: Element, options: SortableOptions): void {
    const dom = lt.util.dom;
    if (!placeholder) placeholder = dom.make('<li class="sortable-placeholder">')[0]!;
    const gap = placeholder;

    let index: number;
    const items = dom.children(me);

    if (options.connectWith) {
        for (const other of dom.$$(options.connectWith)) {
            (other as SortableParent).sortConnect = options.connectWith;
        }
    }

    function dragStart(this: Element): void {
        dragging = this;
        dom.add_class(me, "dragging");
        dom.css(gap, { height: dom.height(dragging) + "px",
                       width: dom.width(dragging) + "px" });
        index = dom.index(this);
        dom.add_class(dragging, "sortable-dragging");
    }

    function dragEnd(): void {
        if (!dragging) return;
        dom.remove_class(me, "dragging");
        dom.remove_class(dragging, "sortable-dragging");
        dom.css(dragging, { display: "", opacity: "" });

        const movedLists = dom.parent(dragging) !== dom.parent(gap);
        dom.after(gap, dragging);
        dom.remove(gap);

        const parent = dom.parent(dragging);
        if (movedLists) {
            if (parent) dom.trigger(parent, "moved", dragging);
        } else if (index !== dom.index(dragging)) {
            if (parent) dom.trigger(parent, "sortupdate", dom.children(parent));
        }
        dragging = null;
    }

    function dragOver(this: Element, e: DragEvent): boolean {
        if (!dragging) return false;
        dom.css(dragging, { display: "none" });

        if (this === me && dom.parent(gap) !== me) {
            dom.append(me, gap);
            return false;
        }

        const from = dom.parent(dragging);
        const to = dom.parent(this);
        // A different list, and not one this one connects to: let the event
        // through so that list can handle it.
        if (from !== to && from?.sortConnect !== to?.sortConnect) return true;

        if (e.type === 'drop') {
            e.stopPropagation();
            dom.trigger(dragging, "dragend");
            return false;
        }

        e.preventDefault();
        if (e.dataTransfer) e.dataTransfer.dropEffect = 'move';

        if (to === me) {
            if (dom.index(gap) < dom.index(this)) {
                dom.after(this, gap);
            } else {
                dom.before(this, gap);
            }
        } else if (this !== gap && to !== me) {
            dom.append(this, gap);
        }
        return false;
    }

    for (const item of items) {
        dom.on(item, "dragstart", dragStart);
        dom.on(item, "dragover", dragOver);
        dom.on(item, "drop", dragOver);
        dom.on(item, "dragenter", dragOver);
        dom.on(item, "dragend", dragEnd);
    }

    dom.on(me, "dragover", dragOver);
    dom.on(me, "drop", dragOver);
    dom.on(me, "dragenter", dragOver);
}
