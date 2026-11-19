#!/usr/bin/env bash
# Build the Micro desktop client on macOS.
#
#   ./scripts/build-macos.sh                 # .app + .dmg for this machine's architecture
#   ./scripts/build-macos.sh --universal     # one binary for Intel + Apple Silicon
#   ./scripts/build-macos.sh --yes           # no prompts (CI)
#   ./scripts/build-macos.sh --bundles app   # pick bundle formats (app, dmg, none)
#
# Dependencies are NOT installed system-wide here: macOS needs only the Xcode command line
# tools plus pkg-config + opus (Homebrew), and each is prompted for individually.
set -euo pipefail

# shellcheck source=common.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

BUNDLES="app,dmg"
UNIVERSAL=0
EXTRA=()

while [ $# -gt 0 ]; do
  case "$1" in
    -y | --yes) ASSUME_YES=1 ;;
    --universal) UNIVERSAL=1 ;;
    --bundles) shift; BUNDLES="${1:-}"; [ -n "$BUNDLES" ] || die "--bundles needs a value" ;;
    -h | --help) awk 'NR>1 && /^#/ { sub(/^# ?/, ""); print; next } NR>1 { exit }' "$0"; exit 0 ;;
    --) shift; EXTRA=("$@"); break ;;
    *) die "unknown option: $1 (try --help)" ;;
  esac
  shift
done
export ASSUME_YES="${ASSUME_YES:-0}"

[ "$(uname -s)" = "Darwin" ] || die "this script is for macOS; use build-linux.sh or build-windows.ps1."

# --- Toolchain ---------------------------------------------------------------
ensure_xcode_clt() {
  step "Checking Xcode command line tools"
  if xcode-select -p >/dev/null 2>&1; then
    ok "$(xcode-select -p)"
    return
  fi
  log "not installed (needed for the C compiler and system headers)."
  if confirm "    Launch the installer now?"; then
    xcode-select --install || true
    die "finish the Xcode command line tools installation in the dialog, then re-run this script."
  fi
  die "Xcode command line tools are required: xcode-select --install"
}

# audiopus_sys locates libopus with pkg-config and links it statically on macOS. Without both, it
# falls back to building the bundled Opus with autogen.sh/configure/make, which additionally needs
# autoconf/automake/libtool — so installing the two packages is by far the shorter path.
ensure_opus() {
  step "Checking pkg-config and libopus"
  local missing=()
  have pkg-config || missing+=("pkg-config")
  if have pkg-config && ! pkg-config --exists opus; then missing+=("opus"); fi
  if [ ${#missing[@]} -eq 0 ]; then
    ok "pkg-config $(pkg-config --version), opus $(pkg-config --modversion opus 2>/dev/null)"
    return
  fi
  log "missing: ${missing[*]} — audiopus needs them to link libopus"
  if have brew && confirm "    Install with Homebrew now (brew install ${missing[*]})?"; then
    brew install "${missing[@]}"
  else
    die "Install Homebrew (https://brew.sh), then: brew install ${missing[*]}"
  fi
  ok "libopus ready"
}

# --- Run ---------------------------------------------------------------------
printf '%sMicro — desktop client build (macOS)%s\n\n' "$C_BOLD" "$C_RESET"

ensure_xcode_clt
ensure_opus
ensure_rust
ensure_node

TRIPLE=""
if [ "$UNIVERSAL" = "1" ]; then
  step "Adding the universal build targets"
  rustup target add x86_64-apple-darwin aarch64-apple-darwin
  TRIPLE="universal-apple-darwin"
  EXTRA=("--target" "$TRIPLE" "${EXTRA[@]+"${EXTRA[@]}"}")
  ok "x86_64 + aarch64 targets ready"
fi

npm_deps

# Stamped before the build so show_outputs can tell this run's installers from older ones.
BUILD_MARK="$(mktemp)"; trap 'rm -f "$BUILD_MARK"' EXIT

if [ "$BUNDLES" = "none" ]; then
  step "Building (no installer bundles)"
  cd "$TAURI_DIR" && npm run tauri build -- --no-bundle "${EXTRA[@]+"${EXTRA[@]}"}"
else
  tauri_build "$BUNDLES" "${EXTRA[@]+"${EXTRA[@]}"}"
fi

show_outputs "$TRIPLE"
printf '\n%sNote:%s builds are unsigned, so Gatekeeper warns on first launch —\n' "$C_BOLD" "$C_RESET"
printf 'right-click the app and choose Open, or run: %sxattr -dr com.apple.quarantine /Applications/Micro.app%s\n' "$C_BOLD" "$C_RESET"
printf '%sVirtual mic:%s install BlackHole (https://existential.audio/blackhole/) and select it as the output device.\n' "$C_BOLD" "$C_RESET"
