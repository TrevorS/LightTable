#!/usr/bin/env bash
set -e
# Wrapper for script/lt-repl.js. See that file for what it does.
cd "$(dirname "${BASH_SOURCE[0]}")/.."
exec node script/lt-repl.js "$@"
