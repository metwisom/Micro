# Cutting a release

The procedure for shipping a version of Micro. Release *notes* live in
[CHANGELOG.md](CHANGELOG.md) and on the GitHub release page — not here, so this file never turns
into a copy of the last announcement.

## 1. Decide the version

The phone and the desktop speak one wire protocol and there is **no version field on the wire**, so
a mismatch shows up as "the phone is discovered but nothing ever connects" rather than as an error.
That makes the version number the only signal users get:

| Change | Version |
|--------|---------|
| Anything in [PROTOCOL.md](PROTOCOL.md) that makes an older peer unable to connect | major (`2.0.0` → `3.0.0`) |
| New capability, both ends still interoperate | minor |
| Fixes only | patch |

## 2. Bump every version string together

Six places, and they must not drift apart:

| File | Field |
|------|-------|
| `tauri/src-tauri/Cargo.toml` | `version` (then `cargo check` to refresh `Cargo.lock`, which is committed) |
| `tauri/src-tauri/tauri.conf.json` | `version` |
| `tauri/package.json` | `version` (then `npm install` to refresh `package-lock.json`) |
| `android/app/build.gradle.kts` | `versionName` |
| `android/app/build.gradle.kts` | `versionCode` — **always increment**, never reuse |

`versionCode` is what Android and any updater use to order builds; a reused one silently refuses to
install over its predecessor.

## 3. Update the changelog

Move the `[Unreleased]` entries under a new `## [x.y.z] - YYYY-MM-DD` heading, and update the link
definitions at the bottom of the file. If the wire protocol changed, say so at the top of the
section in the words a user needs: *update both ends together*.

## 4. Verify

All three suites must pass, on a clean checkout:

```sh
( cd tauri/src-tauri && cargo clippy -- -D warnings && cargo test --lib )
( cd tauri && npm ci && npm test && npm run build )
( cd android && ./gradlew testDebugUnitTest )
```

Then build and smoke-test the real artefacts on a real phone and a real desktop: pair from scratch,
stream audio, mute from each end, and pull the Wi-Fi to confirm the session recovers.

## 5. Build the artefacts

Each platform builds on its own OS — there is no cross-compilation here:

```sh
./scripts/build-linux.sh        # .deb + .AppImage
./scripts/build-macos.sh        # Micro.app + .dmg (not published; see step 6)
./scripts/build-windows.ps1     # NSIS .exe installer
cd android && ./gradlew assembleRelease
```

The Android release APK is **unsigned**: this repository carries no signing config and no key
material. Sign it yourself before publishing (`apksigner`), or publish the debug-signed build and
say so plainly on the release page.

## 6. Tag and publish

```sh
git tag -a vX.Y.Z -m "Micro vX.Y.Z"
git push origin master --follow-tags
```

Create the GitHub release from the tag, paste the changelog section as the body, and attach the
artefacts from step 5. Attach them under the names the bundlers wrote, unrenamed — anyone scripting a download depends on
them matching the previous release. Today that is `Micro-X.Y.Z.apk`, `Micro_X.Y.Z_x64-setup.exe`,
`Micro_X.Y.Z_amd64.deb` and `Micro_X.Y.Z_amd64.AppImage`.

No macOS artefact is published: nobody builds one on a Mac for each release, and the README tells
macOS users to build from source. If that changes, add `Micro_X.Y.Z_<arch>.dmg` here and restore
macOS to the README's download list — the two must not disagree.

## 7. After publishing

- Check that the README's download link resolves to the new release.
- Confirm **Private vulnerability reporting** is still enabled in the repository settings —
  [SECURITY.md](SECURITY.md) points at it.
