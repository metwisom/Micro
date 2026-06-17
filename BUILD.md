# Build commands

Two artifacts: the **client** (`tauri/`, desktop) and the **server** (`android/`, APK).
The Tauri client must be built **on the target OS** — there's no reliable cross-compile.
Code signing is intentionally skipped (unsigned builds; OS may warn on first run).

---

## Client — Tauri (Windows / macOS / Linux)

One-time, on any OS:
```bash
cd tauri
npm install
# regenerate the icon set if you change icon.png:
npm run tauri icon ../icon.png
```

### Windows
Prereqs: Rust (`x86_64-pc-windows-msvc`), **VS Build Tools** (MSVC), WebView2 runtime (preinstalled on Win10/11). Make sure `cargo` is on PATH:
```powershell
$env:Path = "$HOME\.cargo\bin;$env:Path"
cd tauri
npm install
npm run tauri build -- --bundles nsis
```
Output: `tauri\src-tauri\target\release\micro-client.exe` (portable) and
`...\target\release\bundle\nsis\Micro_1.0.0_x64-setup.exe` (installer).
For an MSI too: `--bundles nsis,msi` (downloads WiX on first run).

### macOS
Prereqs: Rust, Xcode Command Line Tools (`xcode-select --install`).
```bash
cd tauri
npm install
npm run tauri build -- --bundles app,dmg
```
Universal (Intel + Apple Silicon):
```bash
rustup target add x86_64-apple-darwin aarch64-apple-darwin
npm run tauri build -- --target universal-apple-darwin --bundles app,dmg
```
Output: `tauri/src-tauri/target/release/bundle/macos/Micro.app` and `.../dmg/Micro_1.0.0_*.dmg`.
Virtual mic: install **BlackHole**; pick it as the output device, apps record from it.

### Linux
Prereqs (Debian/Ubuntu):
```bash
sudo apt install -y build-essential pkg-config libwebkit2gtk-4.1-dev \
  libssl-dev libayatana-appindicator3-dev librsvg2-dev libasound2-dev
# Rust via rustup if not present.
```
Build:
```bash
cd tauri
npm install
npm run tauri build -- --bundles deb,appimage    # add ,rpm on Fedora/openSUSE
```
Output: `tauri/src-tauri/target/release/bundle/{deb,appimage}/...`.
Virtual mic is **created automatically at launch** (PulseAudio/PipeWire null-sink "Micro") —
needs `pactl` (present with Pulse/PipeWire). Apps record from the "Micro" source.

### Dev run (any OS)
```bash
cd tauri
npm run tauri dev
```

---

## Server — Android (APK)

Prereqs: Android Studio (or command-line SDK) with **NDK + CMake 3.22.1** (for native libopus),
JDK 17. First build downloads the Opus source (needs network) and compiles it (~1 min).

```bash
cd android
./gradlew assembleDebug      # debug APK (installable, auto-signed with debug key)
# or:
./gradlew assembleRelease    # release APK (unsigned — signing skipped)
```
On Windows use `gradlew.bat` instead of `./gradlew`.
Output: `android/app/build/outputs/apk/{debug,release}/app-*.apk`.

Install on a connected phone:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## All platforms at once (CI)
Tauri can't cross-compile cleanly, so for "all OSes in one go" use a CI matrix
(GitHub Actions `runs-on: [windows-latest, macos-latest, ubuntu-latest]`) running the per-OS
commands above. Android builds on any runner with the SDK/NDK.
