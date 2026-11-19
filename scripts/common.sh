#!/usr/bin/env bash
# Shared helpers for the Micro desktop-client build scripts (Linux + macOS).
# Sourced by build-linux.sh / build-macos.sh — not meant to be run on its own.

# --- Paths -------------------------------------------------------------------
_COMMON_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$_COMMON_DIR")"
TAURI_DIR="$REPO_ROOT/tauri"

# Building needs Node 18 (Vite 6). Running `npm test` needs 22: the script passes a glob to
# `node --test`, and the test runner only expands globs itself from Node 22 on. CI pins 22.
NODE_MIN_MAJOR=18
NODE_RECOMMENDED_MAJOR=22

# --- Output ------------------------------------------------------------------
if [ -t 1 ] && [ -z "${NO_COLOR:-}" ]; then
  C_RESET=$'\033[0m'; C_BOLD=$'\033[1m'; C_DIM=$'\033[2m'
  C_RED=$'\033[31m'; C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'; C_BLUE=$'\033[34m'
else
  C_RESET=""; C_BOLD=""; C_DIM=""; C_RED=""; C_GREEN=""; C_YELLOW=""; C_BLUE=""
fi

step() { printf '%s==>%s %s%s%s\n' "$C_BLUE" "$C_RESET" "$C_BOLD" "$*" "$C_RESET"; }
log()  { printf '    %s\n' "$*"; }
ok()   { printf '    %s✓%s %s\n' "$C_GREEN" "$C_RESET" "$*"; }
warn() { printf '%swarning:%s %s\n' "$C_YELLOW" "$C_RESET" "$*" >&2; }
err()  { printf '%serror:%s %s\n' "$C_RED" "$C_RESET" "$*" >&2; }
die()  { err "$*"; exit 1; }

have() { command -v "$1" >/dev/null 2>&1; }

# confirm "question" — true unless the user declines. Auto-yes with ASSUME_YES=1.
confirm() {
  if [ "${ASSUME_YES:-0}" = "1" ]; then return 0; fi
  if [ ! -t 0 ]; then
    warn "not a terminal and --yes was not given → assuming \"no\" for: $1"
    return 1
  fi
  local reply
  printf '%s [y/N] ' "$1"
  read -r reply
  case "$reply" in [yY] | [yY][eE][sS]) return 0 ;; *) return 1 ;; esac
}

# --- Toolchain ---------------------------------------------------------------
ensure_node() {
  step "Checking Node.js"
  have node || die "node not found. Install Node ${NODE_RECOMMENDED_MAJOR}+ (https://nodejs.org or nvm) and re-run."
  have npm  || die "npm not found — it ships with Node.js. Reinstall Node ${NODE_RECOMMENDED_MAJOR}+."
  local v major
  v="$(node -v)"; v="${v#v}"; major="${v%%.*}"
  if [ "$major" -lt "$NODE_MIN_MAJOR" ]; then
    die "Node $v is too old (Vite 6 needs >= $NODE_MIN_MAJOR). Install Node $NODE_RECOMMENDED_MAJOR+ — e.g. via nvm:
      curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.1/install.sh | bash && nvm install $NODE_RECOMMENDED_MAJOR"
  fi
  [ "$major" -lt "$NODE_RECOMMENDED_MAJOR" ] && warn "Node $v can build, but \`npm test\` needs Node $NODE_RECOMMENDED_MAJOR+ (glob support in the test runner)."
  ok "node $(node -v), npm $(npm -v)"
}

ensure_rust() {
  step "Checking Rust"
  if ! have cargo; then
    log "cargo not found."
    if confirm "    Install the Rust toolchain with rustup now?"; then
      have curl || die "curl is required to fetch rustup. Install curl and re-run."
      curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y --no-modify-path
      # shellcheck disable=SC1090,SC1091
      . "${CARGO_HOME:-$HOME/.cargo}/env"
    else
      die "Rust is required. Install it from https://rustup.rs and re-run."
    fi
  fi
  have cargo || die "cargo still not on PATH. Open a new shell (or source ~/.cargo/env) and re-run."
  ok "$(rustc --version), $(cargo --version)"
}

# --- Build -------------------------------------------------------------------
npm_deps() {
  step "Installing JS dependencies"
  cd "$TAURI_DIR" || die "missing directory: $TAURI_DIR"
  if [ -f package-lock.json ]; then
    # `npm ci` is reproducible but demands a lockfile in sync with package.json.
    npm ci || { warn "npm ci failed — falling back to npm install"; npm install; }
  else
    npm install
  fi
  ok "dependencies ready"
}

# tauri_build <bundle-list> [extra tauri args...]
tauri_build() {
  local bundles="$1"; shift
  step "Building the Micro client (bundles: $bundles)"
  cd "$TAURI_DIR" || die "missing directory: $TAURI_DIR"
  log "this compiles libopus and RNNoise from source — the first run takes a few minutes"
  npm run tauri build -- --bundles "$bundles" "$@"
}

# show_outputs [target-triple]  — lists what this run produced.
#
# The bundle directory keeps installers from earlier builds, and from earlier *versions* (the file
# names carry the version), so listing everything there reads as "this build made them". When the
# caller has set $BUILD_MARK — a file stamped just before the build — only newer files are shown.
show_outputs() {
  local triple="${1:-}" base="$TAURI_DIR/src-tauri/target"
  [ -n "$triple" ] && base="$base/$triple"
  base="$base/release"
  step "Build artifacts"
  local newer=()
  [ -n "${BUILD_MARK:-}" ] && newer=(-newer "$BUILD_MARK")
  if [ -d "$base/bundle" ]; then
    find "$base/bundle" -maxdepth 2 -type f "${newer[@]+"${newer[@]}"}" \
      \( -name '*.deb' -o -name '*.AppImage' -o -name '*.rpm' -o -name '*.dmg' -o -name '*.app' -o -name '*.exe' \) \
      -print 2>/dev/null | while read -r f; do
        printf '    %s  %s(%s)%s\n' "$f" "$C_DIM" "$(du -h "$f" 2>/dev/null | cut -f1)" "$C_RESET"
      done
  fi
  # Tauri recreates the .app on every bundle, so its mtime is fresh when this run produced it.
  if [ -d "$base/bundle/macos/Micro.app" ] &&
     { [ -z "${BUILD_MARK:-}" ] || [ "$base/bundle/macos/Micro.app" -nt "$BUILD_MARK" ]; }; then
    printf '    %s\n' "$base/bundle/macos/Micro.app"
  fi
  [ -x "$base/micro-client" ] && printf '    %s  %s(portable binary)%s\n' "$base/micro-client" "$C_DIM" "$C_RESET"
  ok "done"
}
