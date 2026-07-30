// The bridge between Light Table's window and its privileged half.
//
// Almost nothing here touches Node, and that is deliberate rather than
// stylistic. Electron has sandboxed renderers by default since v20, and a
// sandboxed preload cannot require fs, child_process, net, path or os — only
// electron, events, timers and url. So a preload cannot be a thin wrapper over
// Node even if one wanted it to be: every privileged operation has to execute
// in the main process, with this file carrying nothing but the request across.
//
// That constraint is the reason the surface below is shaped as capabilities
// rather than as a re-export of Node's modules. What the window can ask for is
// exactly this list, enforced by the process boundary rather than by
// convention, and the main process is free to refuse or to scope any of it.
//
// A few capabilities are served here rather than forwarded: webFrame is a
// renderer-side module, and a preload shares its frame, so zoom is answered
// locally. The window still sees one flat surface either way.
//
// Light Table still runs with contextIsolation off, so contextBridge is not
// available yet and the API is assigned to the window instead. The shape is
// identical either way, which is what lets the window migrate onto it before
// isolation is turned on rather than in the same change.

import { contextBridge, ipcRenderer, webFrame } from 'electron';

/** Values the window reads once, while starting up. */
export interface AppInfo {
    appPath: string;
    platform: NodeJS.Platform;
    parsedArgs: { help?: boolean; add?: boolean; _: string[] };
    openFiles: string[];
    argv: string[];
}

/** Geometry of the window this one belongs to. */
export interface WindowState {
    id: number;
    size: number[];
    position: number[];
    fullScreen: boolean;
}

/**
 * A menu as the window describes it: plain data, with `token` standing in for
 * a click handler that stays on this side.
 */
export interface MenuDescription {
    label?: string;
    role?: string;
    type?: string;
    accelerator?: string;
    enabled?: boolean;
    checked?: boolean;
    token?: number;
    submenu?: (MenuDescription | null)[];
}

export interface LightTableBridge {
    /** Opening things in the desktop environment. */
    shell: {
        /**
         * Open a path with the desktop's default handler, or, when no such
         * path exists, treat it as a url. Resolves to an error message, or to
         * an empty string on success.
         */
        open(pathOrUrl: string): Promise<string>;
        openExternal(url: string): Promise<void>;
        showItemInFolder(path: string): void;
        trashItem(path: string): Promise<void>;
    };
    /** The system clipboard. Synchronous because its callers are. */
    clipboard: {
        readText(): string;
        writeText(text: string): void;
    };
    /** The window's zoom level. Served by webFrame, in this process. */
    zoom: {
        get(): number;
        set(factor: number): void;
    };
    /**
     * This window. Every method here acts on the window that asked; none of
     * them take an id, so a window cannot name one it does not own.
     */
    window: {
        /** Synchronous: it is read while the window is closing. */
        state(): WindowState | null;
        close(): void;
        destroy(): void;
        focus(): void;
        minimize(): void;
        maximize(): void;
        setFullScreen(on: boolean): void;
        setSize(width: number, height: number): void;
        setPosition(x: number, y: number): void;
        /** Open another Light Table window. */
        openNew(): void;
        /** Finish setting up this window, once its contents have loaded. */
        init(): void;
        toggleDevTools(): void;
        /** App-level notifications: "focus", "blur", "close!", "will-navigate". */
        onAppEvent(callback: (name: string) => void): void;
        /** "disconnect" when devtools open, "reconnect!" when they close. */
        onDevtoolsEvent(callback: (name: string) => void): void;
        /** A file the desktop asked to open after startup. */
        onOpenFile(callback: (path: string) => void): void;
    };
    /** Native file dialogs, parented to this window. */
    dialog: {
        open(options: object): Promise<{ canceled: boolean; filePaths: string[] }>;
        save(options: object): Promise<{ canceled: boolean; filePath?: string }>;
    };
    /** Native menus, described as data. */
    menu: {
        popup(items: (MenuDescription | null)[]): void;
        setApplicationMenu(items: (MenuDescription | null)[]): void;
        /** Click handlers stay in the window; this delivers back their token. */
        onClick(callback: (token: number) => void): void;
    };
    /** Read-only facts about the host, resolved once at startup. */
    host: {
        appInfo(): AppInfo;
    };
}

/**
 * Electron hands listeners an event object first. It is of no use to the
 * window, and contextBridge cannot pass it across anyway, so listeners
 * registered through the bridge see only the payload.
 */
function listen(channel: string, callback: (...args: any[]) => void): void {
    ipcRenderer.on(channel, (_event, ...args) => { callback(...args); });
}

/** The window methods share one channel; main holds the list of allowed names. */
function call(method: string, ...args: unknown[]): void {
    ipcRenderer.send('lt:window-call', method, args);
}

const bridge: LightTableBridge = {
    shell: {
        open: (pathOrUrl) => ipcRenderer.invoke('lt:shell-open', pathOrUrl),
        openExternal: (url) => ipcRenderer.invoke('lt:shell-open-external', url),
        showItemInFolder: (path) => ipcRenderer.send('lt:shell-show-item', path),
        trashItem: (path) => ipcRenderer.invoke('lt:shell-trash-item', path)
    },
    clipboard: {
        readText: () => ipcRenderer.sendSync('lt:clipboard-read'),
        writeText: (text) => ipcRenderer.send('lt:clipboard-write', text)
    },
    zoom: {
        get: () => webFrame.getZoomFactor(),
        set: (factor) => webFrame.setZoomFactor(factor)
    },
    window: {
        state: () => ipcRenderer.sendSync('lt:window-state'),
        close: () => call('close'),
        destroy: () => call('destroy'),
        focus: () => call('focus'),
        minimize: () => call('minimize'),
        maximize: () => call('maximize'),
        setFullScreen: (on) => call('setFullScreen', on),
        setSize: (width, height) => call('setSize', width, height),
        setPosition: (x, y) => call('setPosition', x, y),
        openNew: () => ipcRenderer.send('createWindow'),
        init: () => ipcRenderer.send('initWindow'),
        toggleDevTools: () => ipcRenderer.send('toggleDevTools'),
        onAppEvent: (callback) => listen('app', callback),
        onDevtoolsEvent: (callback) => listen('devtools', callback),
        onOpenFile: (callback) => listen('openFileAfterStartup', callback)
    },
    dialog: {
        open: (options) => ipcRenderer.invoke('lt:dialog-open', options),
        save: (options) => ipcRenderer.invoke('lt:dialog-save', options)
    },
    menu: {
        popup: (items) => ipcRenderer.send('lt:menu-popup', items),
        setApplicationMenu: (items) => ipcRenderer.send('lt:menu-app', items),
        onClick: (callback) => listen('lt:menu-click', callback)
    },
    host: {
        appInfo: () => ipcRenderer.sendSync('lt:app-info')
    }
};

// Not `lt`: the compiled ClojureScript claims that global for its own
// namespaces, and lt.objs.console is reached through it from inside Light
// Table. Whatever the bridge is called has to be a name the bundle does not
// already own.
try {
    contextBridge.exposeInMainWorld('lightTable', bridge);
} catch (e) {
    // contextIsolation is still off, where contextBridge refuses to run. The
    // window has the same object either way; only how it gets there differs.
    (globalThis as unknown as { lightTable: LightTableBridge }).lightTable = bridge;
}
