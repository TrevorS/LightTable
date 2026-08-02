// The plugins in plugins/, built against the editor they extend.
//
// A subsequence of `build:cljs`, which is why hygiene.md records the build
// having run this twice once: the same TypeScript compile, the same dependency
// install, the same directory copy. It stays as its own entry point because
// `make build-plugins` is what you want after touching one plugin, and the
// ClojureScript half of the bundle is 20 seconds you do not need for that.
//
// Nothing is cloned and nothing is downloaded but each plugin's own npm
// dependencies, which are private and lockfile-pinned on purpose.

import { spawn } from 'node:child_process';
import * as path from 'node:path';

const ROOT = path.join(import.meta.dirname, '..');

function run(command: string, args: string[]): Promise<void> {
    return new Promise((resolve, reject) => {
        const child = spawn(command, args, { cwd: ROOT, stdio: 'inherit' });
        child.on('close', (code) =>
            code === 0 ? resolve() : reject(new Error(`${command} ${args.join(' ')} exited ${code}`)));
        child.on('error', reject);
    });
}

const node = (script: string) => run(process.execPath, [path.join(ROOT, 'script', script)]);

const STEPS: Array<[string, () => Promise<void>]> = [
    ['TypeScript plugin', () => run('npx', ['tsc', '-p', 'plugins/TypeScript/tsconfig.json'])],
    ['plugin deps', () => node('install-plugin-deps.mts')],
    ['plugins', () => node('place-plugins.mts')]
];

for (const [name, step] of STEPS) {
    try {
        await step();
    } catch (e) {
        console.error(`\nbuild:plugins failed at "${name}": ${(e as Error).message}`);
        process.exit(1);
    }
}
