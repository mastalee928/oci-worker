#!/usr/bin/env bash
set -euo pipefail

# The JAR-embedded WebSSH assets are the only production source. The Go tree is
# retained as a historical reference and must never silently overwrite them.

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
PRODUCTION_DIR="$ROOT_DIR/backend/src/main/resources/static/webssh"
LEGACY_DIR="$ROOT_DIR/webssh/public"
MVC_CONFIG="$ROOT_DIR/backend/src/main/java/com/ociworker/config/WebMvcConfig.java"
PRODUCTION_INDEX="$PRODUCTION_DIR/index.html"

fail() {
    printf 'WebSSH static source check failed: %s\n' "$1" >&2
    exit 1
}

require_file() {
    local path="$1"
    [[ -f "$path" ]] || fail "required file is missing: ${path#$ROOT_DIR/}"
}

require_text() {
    local path="$1"
    local text="$2"
    grep -Fq -- "$text" "$path" || fail "missing '${text}' in ${path#$ROOT_DIR/}"
}

[[ -d "$PRODUCTION_DIR" ]] || fail "production asset directory is missing"
[[ -d "$LEGACY_DIR" ]] || fail "historical Go asset directory is missing"

for relative_path in \
    index.html \
    static/css/style.css \
    static/js/app.js \
    static/vendor/xterm.css \
    static/vendor/xterm.js \
    static/vendor/xterm-addon-fit.js \
    static/vendor/xterm-addon-web-links.js \
    static/vendor/xterm-addon-webgl.js; do
    require_file "$PRODUCTION_DIR/$relative_path"
done

# Production HTML must use the bundled, version-compatible assets. External
# CDN references would make the JAR dependent on network availability and can
# accidentally reintroduce a different xterm/addon version.
for asset in \
    '/webssh/static/vendor/xterm.css' \
    '/webssh/static/vendor/xterm.js' \
    '/webssh/static/vendor/xterm-addon-fit.js' \
    '/webssh/static/vendor/xterm-addon-web-links.js' \
    '/webssh/static/vendor/xterm-addon-webgl.js' \
    '/webssh/static/css/style.css' \
    '/webssh/static/js/app.js'; do
    require_text "$PRODUCTION_INDEX" "$asset"
done

if grep -Eq 'https://cdn\.jsdelivr\.net/npm/(xterm|xterm-addon)' "$PRODUCTION_INDEX"; then
    fail "production index still references an external xterm CDN"
fi

# Keep the server-side mapping pointed at the canonical JAR resource tree.
require_text "$MVC_CONFIG" 'classpath:/static/webssh/'

# Build and deploy entry points must not consume the historical Go snapshot.
# This intentionally excludes documentation and the legacy tree itself.
legacy_reference_pattern='webssh/public'
if git -C "$ROOT_DIR" grep -n -E "$legacy_reference_pattern" -- \
    .github backend frontend install.sh ociworker deploy.sh update.sh docker-compose.yml \
    2>/dev/null; then
    fail "a production entry point references webssh/public"
fi

# Make the ownership rule visible next to the retained historical project.
require_text "$ROOT_DIR/webssh/README.md" 'backend/src/main/resources/static/webssh'
require_text "$ROOT_DIR/webssh/README.md" '不要把本目录作为 OCI Worker 的独立部署入口'

printf 'WebSSH static source check passed: %s is canonical; %s is historical only.\n' \
    'backend/src/main/resources/static/webssh' 'webssh/public'
