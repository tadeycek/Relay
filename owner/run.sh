#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
owner_python="${RELAY_OWNER_PYTHON:-$HOME/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3}"
if [[ ! -x "$owner_python" ]]; then owner_python=python3; fi
if [[ ! -x .local/owner-env/bin/python ]]; then
    "$owner_python" -m venv .local/owner-env
fi
if ! .local/owner-env/bin/python -c 'import nostr_sdk' >/dev/null 2>&1; then
    .local/owner-env/bin/python -m pip install --only-binary=:all: -r owner/requirements.txt
fi
exec .local/owner-env/bin/python owner/owner_app.py "$@"
