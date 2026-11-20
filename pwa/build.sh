#!/usr/bin/env bash
#
# Build helper for family-group-web.
#
# The sibling Android projects have a build.sh, so this one does too — same commands where
# they mean the same thing, so muscle memory carries across. Usage:
#
#   ./build.sh                 production build into build/
#   ./build.sh dev             dev server with hot reload
#   ./build.sh preview         serve the production build locally
#   ./build.sh test            unit tests
#   ./build.sh check           svelte-check + TypeScript
#   ./build.sh format          prettier --write
#   ./build.sh clean           delete build output and caches
#   ./build.sh all             check + test + build
#
# Anything after the command goes straight to the underlying tool, e.g.
#   ./build.sh dev --host
#
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; OFF=$'\033[0m'
info() { printf '%s==>%s %s\n' "$GREEN" "$OFF" "$*"; }
warn() { printf '%s==>%s %s\n' "$YELLOW" "$OFF" "$*" >&2; }
fail() { printf '%serror:%s %s\n' "$RED" "$OFF" "$*" >&2; exit 1; }

# The comment block at the top of this file is the usage text; keep the two in sync by
# printing it rather than repeating it.
usage() { awk 'NR>2 && /^#/ { sub(/^# ?/, ""); print; next } NR>2 { exit }' "${BASH_SOURCE[0]}"; }

case "${1:-}" in
    -h|--help|help) usage; exit 0 ;;
esac

command -v node >/dev/null || fail "node not found; this needs Node 20 or newer"
node_major=$(node -p 'process.versions.node.split(".")[0]')
(( node_major >= 20 )) || fail "Node ${node_major} is too old; Vite 8 needs Node 20 or newer"

if [[ ! -d node_modules ]]; then
    info "installing dependencies"
    npm install
fi

cmd="${1:-build}"; shift || true

case "$cmd" in
    build)
        info "building"
        npm run build -- "$@"
        npm run sync
        ;;
    dev)     npm run dev -- "$@" ;;
    preview) npm run preview -- "$@" ;;
    test)    npm run test -- "$@" ;;
    check)   npm run check -- "$@" ;;
    format)  npm run format ;;
    clean)
        info "removing build output and caches"
        rm -rf build .svelte-kit node_modules/.vite
        ;;
    all)
        npm run check
        npm run test
        npm run build
        ;;
    *) fail "unknown command '$cmd' — try ./build.sh help" ;;
esac
