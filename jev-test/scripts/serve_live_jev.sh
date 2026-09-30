#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIRECTORY="$(cd -- "$SCRIPT_DIRECTORY/.." && pwd)"
KEYCHAIN_SERVICE="zeta-jev-typesafe-api-key"

if [[ ! -x "$PROJECT_DIRECTORY/.venv/bin/jev-pippa" ]]; then
    echo "Jev environment is missing. Create it with: python3 -m venv .venv && .venv/bin/pip install -e ." >&2
    exit 1
fi

if ! TYPESAFE_API_KEY="$(
    security find-generic-password \
        -a "$USER" \
        -s "$KEYCHAIN_SERVICE" \
        -w
)"; then
    echo "TypeSafe API key was not found in macOS Keychain." >&2
    echo "Run scripts/save_typesafe_api_key.sh first." >&2
    exit 1
fi

export TYPESAFE_API_KEY
cd "$PROJECT_DIRECTORY"
exec .venv/bin/jev-pippa serve "$@"
