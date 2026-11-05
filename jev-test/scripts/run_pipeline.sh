#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIRECTORY="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIRECTORY="$(cd -- "$SCRIPT_DIRECTORY/.." && pwd)"
KEYCHAIN_SERVICE="zeta-jev-typesafe-api-key"

if [[ ! -x "$PROJECT_DIRECTORY/.venv/bin/jev-pipeline" ]]; then
    echo "Run: python3 -m venv .venv && .venv/bin/pip install -e ." >&2
    exit 1
fi

if [[ -z "${TYPESAFE_API_KEY:-}" ]]; then
    TYPESAFE_API_KEY="$(
        security find-generic-password -a "$USER" -s "$KEYCHAIN_SERVICE" -w
    )"
    export TYPESAFE_API_KEY
fi

cd "$PROJECT_DIRECTORY"
exec .venv/bin/jev-pipeline run "$@"
