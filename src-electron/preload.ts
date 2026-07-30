// The bridge between Light Table's window and its privileged half.
//
// What the window can ask for is exactly the surface below. That is the point:
// once contextIsolation is on, code in the window — Light Table's own and every
// plugin's — cannot reach anything that is not named here.
//
// Two kinds of capability live in this file, and the difference is where the
// work happens rather than anything the window can see.
//
// Some are forwarded to the main process over ipc: windows, menus, dialogs and
// the shell all belong to it, and a round trip is the only way to reach them.
// Others are served right here, because a preload with `sandbox: false` keeps
// Node, and crossing into this world costs about a microsecond where a round
// trip to the main process costs two hundred. Measured on Electron 43:
// existsSync is 1.7us direct, 2.9us across contextBridge, and 194us over
// synchronous ipc. That is what lets Light Table's filesystem layer keep its
// synchronous API instead of being redesigned around it.
//
// So `sandbox` stays false for now, and the trade is explicit: this file has
// Node, and the window does not. Turning the sandbox on later would move these
// capabilities to the main process and force the coarse, asynchronous redesign
// that the cheap path avoids — defence in depth rather than a new boundary,
// since by then nothing in the window can reach past this list either way.
// doc/context-isolation.md has the reasoning and the numbers.
//
// Light Table still runs with contextIsolation off, so contextBridge is not
// available yet and the API is assigned to the window instead. The shape is
// identical either way, which is what lets the window migrate onto it before
// isolation is turned on rather than in the same change.

import { contextBridge, ipcRenderer, webFrame } from 'electron';
import * as fs from 'node:fs';
import * as nodePath from 'node:path';
import * as os from 'node:os';
import * as childProcess from 'node:child_process';
import * as util from 'node:util';
import * as http from 'node:http';
import * as https from 'node:https';
import * as tls from 'node:tls';
import * as net from 'node:net';

/** Values the window reads once, while starting up. */
export interface AppInfo {
    appPath: string;
    platform: NodeJS.Platform;
    parsedArgs: { help?: boolean; add?: boolean; _: string[] };
    openFiles: string[];
    argv: string[];
}

/** What Light Table asks of a stat, as data rather than as an object. */
export interface FileStat {
    isDirectory: boolean;
    isFile: boolean;
    size: number;
    /** Permission bits, as `fs.Stats.mode`. */
    mode: number;
    mtimeMs: number;
}

export interface SpawnOptions {
    cwd?: string | undefined;
    /** Replaces the environment entirely when given. */
    env?: Record<string, string> | undefined;
}

export interface ForkOptions extends SpawnOptions {
    execPath?: string | undefined;
}

/** A running program, reached by function rather than by object. */
export interface ProcessHandle {
    readonly pid: number | undefined;
    kill(): void;
    onStdout(callback: (chunk: string) => void): void;
    onStderr(callback: (chunk: string) => void): void;
    /** The exit code, or null when the process was killed by a signal. */
    onExit(callback: (code: number | null) => void): void;
    onError(callback: (message: string) => void): void;
}

/** A forked node script, which can also be sent messages. */
export interface ForkHandle extends ProcessHandle {
    send(message: unknown): void;
    onMessage(callback: (message: unknown) => void): void;
}

export interface TcpHandlers {
    onConnect(id: number): void;
    onData(id: number, chunk: string): void;
    onClose(id: number): void;
}

export interface WsHandlers {
    onInit(id: number, data: unknown): void;
    onResult(id: number, data: unknown): void;
    onDisconnect(id: number): void;
}

export interface ServerHandle {
    /** 0 until the server is listening. */
    port(): number;
    /** Send to one connection. tcp takes text; ws takes an event and data. */
    send(id: number, payload: string): void;
    send(id: number, event: string, data: unknown): void;
    close(): void;
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
    /**
     * The filesystem. Served in this world rather than forwarded, so these stay
     * as cheap as the calls they replace and Light Table's synchronous file
     * layer can keep its shape.
     *
     * Grown as namespaces migrate onto it rather than written out in advance:
     * a capability nothing calls is a capability nobody checked.
     */
    files: {
        /**
         * Named exactly as Node names them. This is a capability list, not an
         * fs re-export, but the migration onto it is otherwise mechanical, and
         * a surface that is *almost* the same is worse than either — three
         * shortened names were enough to break it silently the first time.
         */
        existsSync(path: string): boolean;
        readFileSync(path: string): string;
        readFile(path: string): Promise<string>;
        writeFileSync(path: string, content: string): void;
        appendFileSync(path: string, content: string): void;
        mkdirSync(path: string): void;
        readdirSync(path: string): string[];
        realpathSync(path: string): string;
        renameSync(from: string, to: string): void;
        rmSync(path: string, recursive?: boolean): void;
        cpSync(from: string, to: string, recursive?: boolean): void;
        /**
         * Plain data, not an fs.Stats. Stats carries `isDirectory()` and
         * `isFile()` as methods on a prototype, and a prototype does not
         * survive the crossing — so the answers are computed here and the
         * result is something that can actually be cloned.
         *
         * Null when the path does not exist, which is what every caller wanted
         * anyway: they all checked first.
         */
        statSync(path: string): FileStat | null;
        /**
         * Watch a path, and stop by closing the handle.
         *
         * fs pairs watchFile with unwatchFile keyed on the callback's
         * identity, which does not survive being proxied across a boundary.
         * A handle sidesteps that: the real listener never leaves this side.
         * The callback is given the path's stat, or null once it is gone.
         */
        watch(path: string, intervalMs: number,
              onChange: (stat: FileStat | null) => void): { close(): void };
    };
    /**
     * Path arithmetic. No filesystem access of its own — it is here because the
     * window loses `require('path')` along with everything else, not because it
     * is privileged.
     */
    path: {
        join(...parts: string[]): string;
        relative(from: string, to: string): string;
        dirname(path: string): string;
        basename(path: string, ext?: string): string;
        extname(path: string): string;
        resolve(...parts: string[]): string;
        isAbsolute(path: string): boolean;
        readonly sep: string;
    };
    /** Facts about the operating system the window cannot ask for itself. */
    os: {
        readonly EOL: string;
        /**
         * Drive letters on Windows, empty elsewhere.
         *
         * A coarse capability on purpose: the window used to shell out to
         * `wmic` for this, and "which drives exist" is the question it was
         * actually asking. Nothing here needs to hand the window a way to run
         * commands.
         */
        drives(): Promise<string[]>;
    };
    /**
     * Fetching things over the network.
     *
     * One coarse capability rather than an http client, and that is the shape
     * to reach for: what the window wants is a file downloaded, not a socket.
     * Redirects, proxy tunnelling and the write stream all stay on this side,
     * which is both a smaller surface and less code in the window.
     */
    net: {
        /**
         * Download `url` to `dest`, following redirects and honouring the
         * http_proxy and https_proxy environment variables. Resolves once the
         * file has been fully written.
         */
        download(url: string, dest: string, strictSSL: boolean): Promise<void>;
    };
    /**
     * Other programs.
     *
     * Handles, for the reason every stateful capability is: a ChildProcess
     * cannot cross — it would arrive cloned, with its streams and its kill
     * method gone — so the process stays here and a set of functions crosses.
     */
    processes: {
        /** Start a program. */
        spawn(command: string, args: string[], options: SpawnOptions): ProcessHandle;
        /** Run a command through the shell and collect what it printed. */
        exec(command: string, callback: (error: string | null, stdout: string, stderr: string) => void): void;
        /**
         * Fork a node script that talks back over an ipc channel. Used for
         * Light Table's background worker, and for nothing else.
         */
        fork(script: string, args: string[], options: ForkOptions): ForkHandle;
    };
    /**
     * The servers Light Table's clients connect back to.
     *
     * Whole servers rather than sockets, for the same reason downloading is
     * one capability rather than an http client: a socket cannot cross, and
     * what the window actually does with these is wait for connections and
     * send messages. Connections are identified by number, so the window has
     * something it can compare and store without holding an object that
     * belongs over here.
     */
    servers: {
        /** Line-delimited JSON, which is what Light Table's tcp clients speak. */
        tcp(handlers: TcpHandlers): ServerHandle;
        /**
         * socket.io, for clients running in a browser. `clientShim` is served
         * at /lighttable/ws.js; socket.io dropped its static-file API in 2.0.
         */
        ws(clientShim: string, handlers: WsHandlers): ServerHandle;
    };
    /** Read-only facts about the host, resolved once at startup. */
    host: {
        appInfo(): AppInfo;
        /**
         * Node's `util.inspect`, for printing a value to Light Table's own
         * console. Worth noting for later: once contextIsolation is on, what
         * arrives here is a clone, so functions and DOM nodes will print less
         * usefully than they do today. Plain data is unaffected, which is what
         * this is used for.
         */
        inspect(value: unknown, depth: number): string;
        /** The directory the process was started in. */
        cwd(): string;
        /** The environment, as a plain object. */
        env(): Record<string, string | undefined>;
        /** Set one variable, for processes started afterwards. */
        setEnv(name: string, value: string): void;
        /** The node binary this application is running, for forking with. */
        execPath(): string;
        /** Electron, Chromium and node versions. */
        versions(): Record<string, string | undefined>;
        /**
         * The directory the application was loaded from — what `__dirname` was
         * in the window before it stopped having one.
         */
        appDir(): string;
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

const MAX_REDIRECTS = 5;

/** The proxy configured for `url`, or undefined when there is none. */
function proxyFor(url: string): string | undefined {
    const env = process.env;
    return url.startsWith('https:') ? (env['https_proxy'] ?? env['HTTPS_PROXY'])
                                    : (env['http_proxy'] ?? env['HTTP_PROXY']);
}

/** GET an https `url` through `proxy` by opening a CONNECT tunnel. */
function getThroughProxy(url: string, proxy: string, strictSSL: boolean,
                         onResponse: (r: http.IncomingMessage) => void,
                         onError: (e: Error) => void): void {
    const target = new URL(url);
    const p = new URL(proxy);
    const req = http.request({
        host: p.hostname,
        port: p.port || 80,
        method: 'CONNECT',
        path: `${target.hostname}:${target.port || 443}`
    });
    req.on('connect', (res, socket) => {
        if (res.statusCode !== 200) {
            return onError(new Error(`Proxy CONNECT failed with status ${res.statusCode}`));
        }
        https.get({
            host: target.hostname,
            path: target.pathname + target.search,
            // The tunnel's socket, handed over rather than dialled again.
            // createConnection rather than a `socket` option: both work at
            // runtime, and this is the one Node documents and types.
            createConnection: () => tls.connect({ socket, servername: target.hostname,
                                                  rejectUnauthorized: strictSSL }),
            agent: false,
            rejectUnauthorized: strictSSL,
            headers: { 'User-Agent': 'Light Table' }
        }, onResponse).on('error', onError);
    });
    req.on('error', onError);
    req.end();
}

/**
 * GET `url`, following up to `redirects` redirects. GitHub's download
 * endpoints redirect, so this cannot be skipped.
 */
function getUrl(url: string, redirects: number, strictSSL: boolean,
                onResponse: (r: http.IncomingMessage) => void,
                onError: (e: Error) => void): void {
    const handle = (resp: http.IncomingMessage) => {
        const status = resp.statusCode ?? 0;
        const location = resp.headers.location;
        if (location && status >= 300 && status < 400) {
            resp.resume();
            if (redirects <= 0) return onError(new Error('Too many redirects downloading: ' + url));
            return getUrl(new URL(location, url).toString(), redirects - 1, strictSSL, onResponse, onError);
        }
        onResponse(resp);
    };

    const isHttps = url.startsWith('https:');
    const proxy = isHttps ? proxyFor(url) : undefined;
    if (proxy) return getThroughProxy(url, proxy, strictSSL, handle, onError);

    const target = new URL(url);
    const options = {
        host: target.hostname,
        port: target.port || undefined,
        path: target.pathname + target.search,
        headers: { 'User-Agent': 'Light Table' },
        rejectUnauthorized: strictSSL
    };
    (isHttps ? https : http).get(options, handle).on('error', onError);
}

function download(url: string, dest: string, strictSSL: boolean): Promise<void> {
    return new Promise((resolve, reject) => {
        getUrl(url, MAX_REDIRECTS, strictSSL, (resp) => {
            if (resp.statusCode !== 200) {
                resp.resume();
                return reject(new Error(`Error downloading: ${url} status code: ${resp.statusCode}`));
            }
            const out = fs.createWriteStream(dest);
            out.on('error', reject);
            out.on('finish', () => resolve());
            resp.pipe(out);
        }, reject);
    });
}

/** Wraps a child process in the functions the window is allowed to call. */
function handleFor(child: childProcess.ChildProcess): ProcessHandle {
    return {
        pid: child.pid,
        kill: () => { child.kill(); },
        // Chunks arrive as Buffers; the window has no Buffer, so they are
        // decoded here rather than crossing as cloned byte arrays.
        onStdout: (callback) => { child.stdout?.on('data', (d) => callback(String(d))); },
        onStderr: (callback) => { child.stderr?.on('data', (d) => callback(String(d))); },
        onExit: (callback) => { child.on('exit', (code) => callback(code)); },
        onError: (callback) => { child.on('error', (e) => callback(e.message)); }
    };
}

/** A stat as data, or null when the path is not there. */
function statOf(path: string): FileStat | null {
    const s = fs.statSync(path, { throwIfNoEntry: false });
    if (!s) return null;
    return { isDirectory: s.isDirectory(), isFile: s.isFile(),
             size: s.size, mode: s.mode, mtimeMs: s.mtimeMs };
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
    files: {
        existsSync: (path) => fs.existsSync(path),
        readFileSync: (path) => fs.readFileSync(path, 'utf8'),
        readFile: (path) => fs.promises.readFile(path, 'utf8'),
        writeFileSync: (path, content) => fs.writeFileSync(path, content),
        appendFileSync: (path, content) => fs.appendFileSync(path, content),
        mkdirSync: (path) => fs.mkdirSync(path),
        readdirSync: (path) => fs.readdirSync(path),
        realpathSync: (path) => fs.realpathSync(path),
        renameSync: (from, to) => fs.renameSync(from, to),
        rmSync: (path, recursive) => fs.rmSync(path, { recursive: Boolean(recursive), force: true }),
        cpSync: (from, to, recursive) => fs.cpSync(from, to, { recursive: Boolean(recursive) }),
        statSync: (path) => statOf(path),
        watch: (path, intervalMs, onChange) => {
            const listener = () => onChange(statOf(path));
            fs.watchFile(path, { interval: intervalMs, persistent: false }, listener);
            return { close: () => fs.unwatchFile(path, listener) };
        }
    },
    path: {
        join: (...parts) => nodePath.join(...parts),
        relative: (from, to) => nodePath.relative(from, to),
        dirname: (path) => nodePath.dirname(path),
        basename: (path, ext) => (ext === undefined ? nodePath.basename(path) : nodePath.basename(path, ext)),
        extname: (path) => nodePath.extname(path),
        resolve: (...parts) => nodePath.resolve(...parts),
        isAbsolute: (path) => nodePath.isAbsolute(path),
        sep: nodePath.sep
    },
    os: {
        EOL: os.EOL,
        drives: () => new Promise((resolve) => {
            if (process.platform !== 'win32') return resolve([]);
            childProcess.exec('wmic logicaldisk get name', (err, out) => {
                if (err) return resolve([]);
                resolve(out.split(/\r\n|\r|\n/).slice(1)
                           .map((line) => line.trim())
                           .filter((line) => line.length > 0)
                           .map((drive) => drive + nodePath.sep));
            });
        })
    },
    net: {
        download: (url, dest, strictSSL) => download(url, dest, strictSSL)
    },
    processes: {
        spawn: (command, args, options) => handleFor(
            childProcess.spawn(command, args, { cwd: options.cwd, env: options.env })),
        exec: (command, callback) => {
            childProcess.exec(command, (error, stdout, stderr) => {
                callback(error ? error.message : null, String(stdout), String(stderr));
            });
        },
        fork: (script, args, options) => {
            const child = childProcess.fork(script, args, {
                execPath: options.execPath,
                cwd: options.cwd,
                env: options.env,
                // Light Table reads the worker's output as console lines, so
                // it has to be piped rather than inherited.
                silent: true
            });
            const handle = handleFor(child) as ForkHandle;
            handle.send = (message) => { child.send(message as object); };
            handle.onMessage = (callback) => { child.on('message', (m) => callback(m)); };
            return handle;
        }
    },
    servers: {
        tcp: (handlers) => {
            const sockets = new Map<number, net.Socket>();
            let nextId = 1;
            let port = 0;
            const server = net.createServer((socket) => {
                const id = nextId++;
                sockets.set(id, socket);
                socket.on('data', (d) => handlers.onData(id, String(d)));
                socket.on('close', () => { sockets.delete(id); handlers.onClose(id); });
                socket.on('error', () => { sockets.delete(id); });
                handlers.onConnect(id);
            });
            server.on('listening', () => { port = (server.address() as net.AddressInfo).port; });
            server.listen(0);
            return {
                port: () => port,
                send: (id: number, payload: string) => { sockets.get(id)?.write(payload); },
                close: () => server.close()
            } as ServerHandle;
        },

        ws: (clientShim, handlers) => {
            const io = require(__dirname + '/node_modules/socket.io');
            const clients = new Map<number, { emit(event: string, data: unknown): void }>();
            let nextId = 1;
            let port = 0;

            const httpServer = http.createServer((req, res) => {
                if (req.url === '/lighttable/ws.js') {
                    res.writeHead(200, { 'Content-Type': 'application/javascript' });
                    res.end(clientShim);
                } else {
                    res.writeHead(404);
                    res.end();
                }
            });
            httpServer.on('error', (e: NodeJS.ErrnoException) => {
                if (e.code !== 'EADDRINUSE') throw e;
                // Another Light Table has the default port. Take any free one.
                httpServer.listen(0);
            });
            httpServer.on('listening', () => { port = (httpServer.address() as net.AddressInfo).port; });

            const server = new io.Server(httpServer, { serveClient: true });
            server.on('connection', (socket: any) => {
                const id = nextId++;
                clients.set(id, socket);
                socket.on('init', (data: unknown) => handlers.onInit(id, data));
                socket.on('result', (data: unknown) => handlers.onResult(id, data));
                socket.on('disconnect', () => { clients.delete(id); handlers.onDisconnect(id); });
            });
            httpServer.listen(5678);
            return {
                port: () => port,
                send: (id: number, event: string, data?: unknown) => { clients.get(id)?.emit(event, data); },
                close: () => { server.close(); httpServer.close(); }
            } as ServerHandle;
        }
    },
    host: {
        appInfo: () => ipcRenderer.sendSync('lt:app-info'),
        cwd: () => process.cwd(),
        env: () => Object.assign({}, process.env),
        setEnv: (name, value) => { process.env[name] = value; },
        execPath: () => process.execPath,
        versions: () => Object.assign({}, process.versions),
        inspect: (value, depth) => util.inspect(value, { showHidden: false, depth }),
        appDir: () => __dirname
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
