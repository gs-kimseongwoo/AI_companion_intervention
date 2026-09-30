#!/usr/bin/env bash
set -euo pipefail

KEYCHAIN_SERVICE="zeta-jev-typesafe-api-key"

read -r -s -p "TypeSafe API key (hidden): " typesafe_api_key
printf '\n'

if [[ -z "$typesafe_api_key" ]]; then
    echo "API key was empty; nothing was saved." >&2
    exit 1
fi

security add-generic-password \
    -U \
    -a "$USER" \
    -s "$KEYCHAIN_SERVICE" \
    -w "$typesafe_api_key" \
    >/dev/null

unset typesafe_api_key
echo "TypeSafe API key saved in macOS Keychain."
