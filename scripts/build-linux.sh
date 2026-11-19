#!/usr/bin/env bash
# Build the Micro desktop client on Linux, installing the system dependencies first.
#
#   ./scripts/build-linux.sh                 # install deps (asks first), then build deb + AppImage
#   ./scripts/build-linux.sh --yes           # same, no prompts (CI)
#   ./scripts/build-linux.sh --skip-deps     # build only
#   ./scripts/build-linux.sh --deps-only     # install deps, don't build
#   ./scripts/build-linux.sh --bundles deb   # pick bundle formats (deb, appimage, rpm, none)
#
# Supports apt (Debian/Ubuntu/Mint), dnf (Fedora/RHEL), pacman (Arch), zypper (openSUSE).
set -euo pipefail

# shellcheck source=common.sh
. "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

BUNDLES="deb,appimage"
SKIP_DEPS=0
DEPS_ONLY=0
EXTRA=()

while [ $# -gt 0 ]; do
  case "$1" in
    -y | --yes) ASSUME_YES=1 ;;
    --skip-deps) SKIP_DEPS=1 ;;
    --deps-only) DEPS_ONLY=1 ;;
    --bundles) shift; BUNDLES="${1:-}" ; [ -n "$BUNDLES" ] || die "--bundles needs a value" ;;
    -h | --help) awk 'NR>1 && /^#/ { sub(/^# ?/, ""); print; next } NR>1 { exit }' "$0"; exit 0 ;;
    --) shift; EXTRA=("$@"); break ;;
    *) die "unknown option: $1 (try --help)" ;;
  esac
  shift
done
export ASSUME_YES="${ASSUME_YES:-0}"

[ "$(uname -s)" = "Linux" ] || die "this script is for Linux; use build-macos.sh or build-windows.ps1."

# --- Distro detection --------------------------------------------------------
distro_id() {
  if [ -r /etc/os-release ]; then
    # shellcheck disable=SC1091
    . /etc/os-release
    printf '%s %s' "${ID:-unknown}" "${ID_LIKE:-}"
  else
    printf 'unknown'
  fi
}

pkg_manager() {
  local ids; ids="$(distro_id)"
  case " $ids " in
    *debian*|*ubuntu*|*mint*|*pop*) echo apt ;;
    *fedora*|*rhel*|*centos*)       echo dnf ;;
    *arch*|*manjaro*|*endeavour*)   echo pacman ;;
    *suse*|*opensuse*)              echo zypper ;;
    *) if have apt-get; then echo apt
       elif have dnf; then echo dnf
       elif have pacman; then echo pacman
       elif have zypper; then echo zypper
       else echo unknown; fi ;;
  esac
}

SUDO=""
[ "$(id -u)" -ne 0 ] && SUDO="sudo"

# Install packages, falling back to one-by-one so a single name that does not exist
# on this release cannot abort the whole run.
install_pkgs() {
  local mgr="$1"; shift
  local pkgs=("$@") p
  case "$mgr" in
    apt)
      $SUDO apt-get update
      DEBIAN_FRONTEND=noninteractive $SUDO apt-get install -y "${pkgs[@]}" && return 0
      warn "batch install failed — retrying package by package"
      for p in "${pkgs[@]}"; do
        DEBIAN_FRONTEND=noninteractive $SUDO apt-get install -y "$p" || warn "skipped (not in this release's repos): $p"
      done ;;
    dnf)
      $SUDO dnf install -y "${pkgs[@]}" && return 0
      warn "batch install failed — retrying package by package"
      for p in "${pkgs[@]}"; do $SUDO dnf install -y "$p" || warn "skipped: $p"; done ;;
    pacman)
      $SUDO pacman -Sy --needed --noconfirm "${pkgs[@]}" && return 0
      warn "batch install failed — retrying package by package"
      for p in "${pkgs[@]}"; do $SUDO pacman -S --needed --noconfirm "$p" || warn "skipped: $p"; done ;;
    zypper)
      $SUDO zypper --non-interactive install --no-recommends "${pkgs[@]}" && return 0
      warn "batch install failed — retrying package by package"
      for p in "${pkgs[@]}"; do $SUDO zypper --non-interactive install --no-recommends "$p" || warn "skipped: $p"; done ;;
  esac
}

# What each package is for:
#   build-essential/gcc  — C toolchain for the vendored RNNoise (compiled by build.rs via the cc crate)
#   pkg-config           — how audiopus_sys finds libopus, and how the GTK/ALSA crates find their libs
#   libopus (dev)        — audiopus_sys probes it with pkg-config; without it the crate falls back to
#                          building the bundled Opus with autogen.sh/configure/make, hence the autotools
#   autoconf/automake/libtool — only used by that fallback path (cheap insurance)
#   webkit2gtk 4.1       — the Tauri v2 webview (4.0 is the v1 series and will not do)
#   libsoup3, javascriptcoregtk — pulled in by webkit2gtk 4.1
#   gtk3, librsvg        — window + icon rendering
#   libayatana-appindicator — the tray icon (the app runs in the tray)
#   libxdo               — global shortcut / input plumbing
#   alsa                 — cpal audio output
#   openssl              — transitive dependency of the keyring/Secret Service stack
#   libfuse2             — needed at runtime to *launch* the AppImage
deps_for() {
  case "$1" in
    apt) echo "build-essential curl wget file pkg-config libopus-dev \
               autoconf automake libtool \
               libssl-dev libgtk-3-dev libwebkit2gtk-4.1-dev libjavascriptcoregtk-4.1-dev \
               libsoup-3.0-dev libayatana-appindicator3-dev librsvg2-dev libxdo-dev \
               libasound2-dev libfuse2" ;;
    dnf) echo "gcc gcc-c++ make curl wget file pkgconf-pkg-config opus-devel \
               autoconf automake libtool \
               openssl-devel gtk3-devel webkit2gtk4.1-devel libsoup3-devel \
               libappindicator-gtk3-devel librsvg2-devel libxdo-devel \
               alsa-lib-devel fuse-libs" ;;
    pacman) echo "base-devel curl wget file pkgconf opus \
               openssl gtk3 webkit2gtk-4.1 libsoup3 libappindicator-gtk3 librsvg xdotool \
               alsa-lib fuse2" ;;
    zypper) echo "patterns-devel-base-devel_basis curl wget file pkg-config libopus-devel \
               autoconf automake libtool \
               libopenssl-devel gtk3-devel webkit2gtk3-devel libsoup-devel \
               libappindicator3-devel librsvg-devel libxdo-devel \
               alsa-devel libfuse2" ;;
  esac
}

install_deps() {
  local mgr; mgr="$(pkg_manager)"
  step "Installing system dependencies"
  if [ "$mgr" = "unknown" ]; then
    warn "unrecognised distribution — install these manually, then re-run with --skip-deps:"
    log "C toolchain, pkg-config, libopus (dev), webkit2gtk 4.1 (dev), gtk3 (dev), libsoup3 (dev),"
    log "libayatana-appindicator3 (dev), librsvg2 (dev), libxdo (dev), ALSA (dev), OpenSSL (dev)"
    return 1
  fi
  local pkgs; read -r -a pkgs <<< "$(deps_for "$mgr" | tr -s ' ')"
  log "package manager: $mgr"
  log "packages: ${pkgs[*]}"
  if [ -n "$SUDO" ]; then log "this needs sudo (system-wide install)"; fi
  confirm "    Install them now?" || { warn "skipped dependency installation"; return 0; }
  install_pkgs "$mgr" "${pkgs[@]}"
  ok "system dependencies installed"
}

# --- Run ---------------------------------------------------------------------
printf '%sMicro — desktop client build (Linux)%s\n\n' "$C_BOLD" "$C_RESET"

[ "$SKIP_DEPS" = "1" ] || install_deps
if [ "$DEPS_ONLY" = "1" ]; then ok "dependencies only — nothing built"; exit 0; fi

ensure_rust
ensure_node
npm_deps

# Stamped before the build so show_outputs can tell this run's installers from older ones.
BUILD_MARK="$(mktemp)"; trap 'rm -f "$BUILD_MARK"' EXIT

if [ "$BUNDLES" = "none" ]; then
  step "Building (no installer bundles)"
  cd "$TAURI_DIR" && npm run tauri build -- --no-bundle "${EXTRA[@]+"${EXTRA[@]}"}"
else
  tauri_build "$BUNDLES" "${EXTRA[@]+"${EXTRA[@]}"}"
fi

# The .deb links against the system libopus (audiopus links dynamically on glibc Linux), so the
# package depends on it at runtime as well.

show_outputs
printf '\n%sNext:%s the virtual microphone is created automatically at launch (PulseAudio/PipeWire\n' "$C_BOLD" "$C_RESET"
printf 'sink "Micro") — it needs %spactl%s on PATH. Then pick "Micro" as the mic in your app.\n' "$C_BOLD" "$C_RESET"
