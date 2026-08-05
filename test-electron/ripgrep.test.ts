// Where the bundled ripgrep is, agreed on by everything that has an opinion.
//
// Three places name that path and none of them can see the others:
//
//   script/fetch-ripgrep.mts   writes the binary there
//   script/build-app.sh        signs it there, on macOS
//   lt.background.search       looks for it there, from the worker
//
// Getting one of them wrong is not a crash. Search falls back to the tree walk
// and returns *identical results*, several times slower, saying nothing — which
// is exactly what happened: the worker resolved `bin/rg` instead of
// `core/bin/rg`, because Light Table's home directory is the one *containing*
// core rather than core itself. Every test passed.
//
// So the agreement is asserted here, where it is cheap, and the smoke test
// separately asserts which engine actually answered, where it is true.
//
// All three files are read as **text** rather than imported. Two of them are not
// TypeScript, so there was never a choice about those — and importing the third
// pulls `script/`'s ES modules into this project's tsconfig, which does not allow
// `import.meta`. Reading a literal out of the source is also closer to what is
// being checked: that three files spell the same path.

import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import * as fs from 'node:fs';
import * as path from 'node:path';

/** `npm run test:electron` runs from the repository root. */
const ROOT = process.cwd();

function source(...parts: string[]): string {
    const file = path.join(ROOT, ...parts);
    assert.ok(fs.existsSync(file), `${parts.join('/')} should exist — is this running from the root?`);
    return fs.readFileSync(file, 'utf8');
}

describe('the bundled ripgrep', () => {
    test('is fetched into deploy/core/bin, which is what gets copied into the bundle', () => {
        // `cp -R deploy/core $RELEASE_RSRC/app/` is what carries it, so anywhere
        // else in the tree would work locally and not ship.
        const fetcher = source('script', 'fetch-ripgrep.mts');
        assert.match(fetcher, /path\.join\(ROOT, 'deploy', 'core', 'bin',/,
                     'the fetcher writes somewhere other than deploy/core/bin');
        assert.match(fetcher, /'rg\.exe' : 'rg'/);
    });

    test('is where the worker looks for it', () => {
        // lt.background.search/binary builds `<lt-home>/core/bin/rg`, and
        // lt-home is `<app-dir>/..` — so `core/` is part of the path and is the
        // exact piece that was missing.
        const searcher = source('src', 'lt', 'background', 'search.cljs');
        const match = searcher.match(/\(str "([^"]+)"/);
        assert.ok(match, 'lt.background.search/binary still builds its path from a literal');
        assert.equal(match![1], 'core/bin/rg',
                     'the worker looks somewhere other than where the fetcher writes');
    });

    test('is signed by the macOS build, at the path it is actually placed at', () => {
        // An unsigned executable inside a signed bundle is refused at exec time
        // on Apple Silicon, so this is not cosmetic.
        const build = source('script', 'build-app.sh');
        assert.match(build, /Contents\/Resources\/app\/core\/bin\/rg/,
                     'build-app.sh signs a different path than the fetcher writes');
        assert.match(build, /codesign --force --sign - "\$RG_PATH"/);
    });

    test('is pinned to a version with a digest for every platform it ships on', () => {
        const fetcher = source('script', 'fetch-ripgrep.mts');
        assert.match(fetcher, /const VERSION = '\d+\.\d+\.\d+';/);
        // Every triple `target()` can return. A digest table is exactly the
        // thing nobody should be able to shrink by accident.
        for (const triple of ['aarch64-apple-darwin', 'x86_64-apple-darwin',
                              'aarch64-unknown-linux-musl', 'x86_64-unknown-linux-musl',
                              'aarch64-pc-windows-msvc', 'x86_64-pc-windows-msvc']) {
            assert.match(fetcher, new RegExp(`'${triple}':\\s*'[0-9a-f]{64}'`),
                         `no pinned digest for ${triple}`);
        }
    });

    test('is not committed', () => {
        // The policy plugins/README.md states and fetch-clj-kondo.mts restates:
        // source in the repository, no binaries in it.
        assert.match(source('.gitignore'), /^\/deploy\/core\/bin\/$/m,
                     'the fetched binary would be committed');
    });
});
