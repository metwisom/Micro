# Build scripts — desktop client

One script per host OS. Each one verifies the toolchain, installs the JS dependencies and
produces the installer(s) for that platform. The Tauri client **cannot be cross-compiled**, so
run the script on the OS you are building for.

| Script | Host | Default output |
|--------|------|----------------|
| [`build-linux.sh`](build-linux.sh) | Linux | `.deb` + `.AppImage` |
| [`build-macos.sh`](build-macos.sh) | macOS | `Micro.app` + `.dmg` |
| [`build-windows.ps1`](build-windows.ps1) | Windows | NSIS installer + portable `.exe` |

`common.sh` holds the helpers shared by the two shell scripts — it is sourced, not run.

The Android server is not built here; see [BUILD.md](../BUILD.md) for `gradlew`.

## Linux

Installs the system dependencies too (the only platform where that is scripted), asking for
confirmation before touching anything with `sudo`.

```bash
./scripts/build-linux.sh                # install deps (prompts), then build
./scripts/build-linux.sh --yes          # no prompts, for CI
./scripts/build-linux.sh --skip-deps    # build only
./scripts/build-linux.sh --deps-only    # prepare the machine, build later
./scripts/build-linux.sh --bundles deb  # deb | appimage | rpm | none
```

Package managers: **apt** (Debian/Ubuntu/Mint/Pop), **dnf** (Fedora/RHEL), **pacman** (Arch/Manjaro),
**zypper** (openSUSE). An unknown distribution prints the dependency list and stops instead of
guessing. If one package name does not exist on your release, the script falls back to installing
them one by one and reports what it skipped rather than aborting.

What gets installed and why:

| Package | Needed for |
|---------|-----------|
| C toolchain (`build-essential` / `gcc`) | the vendored RNNoise, compiled by `build.rs` through the `cc` crate |
| `pkg-config` | how `audiopus_sys` finds libopus, and how the GTK/ALSA crates find theirs |
| `libopus` (dev) | `audiopus_sys` probes it with pkg-config and links it; **without it** the crate falls back to building the bundled Opus with `autogen.sh`/`configure`/`make` |
| `autoconf`, `automake`, `libtool` | only used by that fallback — installed as cheap insurance |
| `webkit2gtk-4.1`, `libsoup3`, `javascriptcoregtk-4.1`, `gtk3`, `librsvg2` | the Tauri v2 webview (4.0 is the v1 series and will not work) |
| `libayatana-appindicator3` | the tray icon |
| `libxdo` | global shortcut handling |
| `alsa` (`libasound2-dev`) | audio output via cpal |
| `openssl` | transitive dependency of the keyring / Secret Service stack |
| `libfuse2` | required at runtime to *launch* the AppImage |

No `cmake` is needed anywhere: nothing in the dependency graph uses the `cmake` crate.
On glibc Linux `audiopus` links libopus **dynamically**, so the machine running the `.deb`
needs the runtime `libopus0` package as well (a dependency of most desktop installs already).

At runtime the client creates its virtual microphone itself (a PulseAudio/PipeWire null sink
named `Micro`), which needs `pactl` on `PATH` — it ships with PulseAudio and PipeWire.

## macOS

```bash
./scripts/build-macos.sh              # native architecture
./scripts/build-macos.sh --universal  # Intel + Apple Silicon in one binary
./scripts/build-macos.sh --bundles app
```

Prompts for the Xcode command line tools (`xcode-select --install`) and, via Homebrew, for
`pkg-config` + `opus` if they are missing — `audiopus_sys` needs them to link libopus statically,
otherwise it falls back to an autotools build of the bundled Opus. Nothing else is installed.

Virtual mic: install [BlackHole](https://github.com/ExistentialAudio/BlackHole) and select it as
the output device. Builds are unsigned, so Gatekeeper warns on first launch — right-click → Open,
or `xattr -dr com.apple.quarantine /Applications/Micro.app`.

## Windows

```powershell
.\scripts\build-windows.ps1
.\scripts\build-windows.ps1 -Bundles nsis,msi
.\scripts\build-windows.ps1 -Bundles none   # portable .exe only
```

Checks the MSVC C++ build tools, Rust, Node and the WebView2 runtime, then builds. Nothing is
installed for you — each missing piece prints its own installer link. **No cmake or nasm is
required:** `audiopus_sys` ships a prebuilt libopus for the MSVC target, and the vendored RNNoise
is compiled by the `cc` crate with MSVC itself.

It also copes with **Micro already running**: a live process holds an exclusive handle on
`micro-client.exe`, so the linker cannot replace it and cargo would otherwise fail after several
minutes with a bare `os error 5`. Windows still allows *renaming* a running image, so the script
moves the old build aside (`micro-client.locked1.exe`) and links over the free path. You do not have
to quit Micro to build — but the instance you have open keeps running the old build until you quit
and relaunch it. Leftovers are swept on the next build.

Virtual mic: install [VB-CABLE](https://vb-audio.com/Cable/) — the client auto-selects
`CABLE Input`.

## Common notes

- **First build is slow** (several minutes): the release profile uses fat LTO with a single
  codegen unit, and RNNoise is compiled from C. Later builds reuse `tauri/src-tauri/target`.
- **Requirements:** Rust (MSVC toolchain on Windows) and Node ≥ 18 to build. `npm test` needs
  **Node 22+**, because the test script hands a glob to `node --test` and the runner only expands
  globs itself from Node 22 on — which is what CI pins.
- **Always build through these scripts (or `npm run tauri build`), never with a bare
  `cargo build --release`.** The Tauri CLI runs the frontend build first (`beforeBuildCommand`);
  plain cargo does not, so it can produce a binary with no embedded UI that falls back to the dev
  URL and greets you with `ERR_CONNECTION_REFUSED` on `localhost:1420` — observed on Windows.
- **Dev run** instead of a release build: `cd tauri && npm run tauri dev`.
- **Tests:** `cd tauri/src-tauri && cargo test` (Rust) and `cd tauri && npm test` (front-end).
- **Extra flags** are forwarded to the Tauri CLI after `--`, e.g.
  `./scripts/build-linux.sh --skip-deps -- --verbose`.
- **Executable bit** is tracked in git (`build-linux.sh`, `build-macos.sh` and `android/gradlew`
  are mode 100755), so a fresh clone can run them directly. `common.sh` is sourced, not executed,
  and stays non-executable. Add any new script with `git add --chmod=+x`.
