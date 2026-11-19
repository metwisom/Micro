# Contributing to Micro

By taking part you agree to the [Code of Conduct](CODE_OF_CONDUCT.md).

Thanks for taking a look. Micro is a small hobby project, and patches are welcome — bug fixes,
platform quirks, translations, documentation. This file describes how the repository is put
together and what a change has to satisfy before it can be merged.

## Project shape

Micro is two programs that talk to each other over UDP on the LAN:

| Path | Role |
|------|------|
| [`android/`](android) | **Server.** Kotlin + NDK libopus. Captures the microphone, encrypts, streams. |
| [`tauri/`](tauri) | **Client.** Tauri v2 (Rust + webview). Decrypts, decodes, feeds the virtual mic. |

The direction surprises people: the *phone* is the server (it binds port 50005 and answers
discovery), and the *desktop* is the client that connects to it.

The wire protocol is normative in [`PROTOCOL.md`](PROTOCOL.md) and is implemented **twice** — once
in Kotlin, once in Rust. There is no shared library and no code generation between them, so the two
implementations only stay in sync because the tests force them to. That shapes almost every rule
below.

Inside the desktop client:

- `tauri/src-tauri/src/lib.rs` — module map and the Tauri builder, nothing else. Read it first:
  it names every module and what each one owns.
- `proto.rs`, `crypto.rs`, `jitter.rs` — free of I/O and app state on purpose. The invariants the
  two implementations must agree on are unit-tested here, next to the code they constrain.
- `net.rs` — the connection end to end: pairing, key exchange, control channel, audio receive.
- `commands.rs`, `tray.rs`, `discovery.rs`, `link.rs`, `store.rs`, `state.rs`, `events.rs`,
  `platform.rs`, `rnnoise.rs`, `audio.rs` — one concern each, as their headers say.
- `tauri/src-tauri/rnnoise/` — vendored RNNoise C source (BSD 3-clause; see its `COPYING`).
- `tauri/src/main.js` — the UI; `tauri/src/state.js` — pure logic, deliberately DOM-free so it can
  be unit-tested with plain `node --test`.

## Getting set up

The quickest path is [`scripts/`](scripts): one build script per host OS that checks the toolchain,
installs the JS dependencies and produces the installers. On Linux it installs the system
dependencies too.

```bash
./scripts/build-linux.sh          # deps + .deb/.AppImage
./scripts/build-macos.sh          # .app + .dmg
.\scripts\build-windows.ps1       # NSIS installer + portable .exe
```

[`BUILD.md`](BUILD.md) documents the same steps by hand, per platform, including the Android APK.

Toolchain versions in use:

| Part | Requirement |
|------|-------------|
| Desktop (Rust) | Rust edition 2021, `rust-version = "1.77"`, Tauri 2.11.3 |
| Desktop (JS) | Node >= 18 (Vite 6 needs it); 22+ to run the JS tests, see below. `npm ci` when `package-lock.json` is present, otherwise `npm install` |
| Android | AGP 9.0.1, Gradle 9.1.0 (wrapper), JDK 21, compileSdk 36, minSdk 26, targetSdk 36, Java source/target 11 |

Two things that catch people out:

- **Linux needs system packages even to run the Rust tests**, because the library depends on Tauri
  and therefore on GTK/WebKit. `webkit2gtk-4.1` is the right series (4.0 is Tauri v1), and
  `libopus-dev` matters because `audiopus_sys` finds libopus through pkg-config — without it, it
  falls back to an autotools build of bundled Opus. `./scripts/build-linux.sh --deps-only` prepares
  the machine without building.
- **Windows needs neither cmake nor nasm** for the desktop client; `audiopus_sys` links a prebuilt
  libopus for MSVC. macOS needs the Xcode command line tools plus `pkg-config` and `opus` from
  Homebrew.

For an iteration loop on the desktop client, use the dev server — it hot-reloads the frontend and
rebuilds the Rust side on change:

```bash
cd tauri
npm run tauri dev
```

## Running the tests

Three suites. **All three must pass before a pull request.** Each command prints its own count;
no total is quoted here, because a number in prose rots the moment someone adds a test.

| Suite | Command |
|-------|---------|
| Rust (desktop client) | `cd tauri/src-tauri && cargo test --lib` |
| Kotlin (Android server) | `cd android && ./gradlew testDebugUnitTest` |
| JavaScript (frontend logic) | `cd tauri && npm test` |

On Windows, use `gradlew.bat` instead of `./gradlew`.

The Android unit tests are cheap to run: `testDebugUnitTest` does **not** trigger the NDK/CMake
build (only `compileDebugKotlin` and `testDebugUnitTest` run), so a JDK plus the Android SDK is
enough — no NDK and no network fetch of Opus. A full `assembleRelease` is the expensive one: it
needs the NDK and CMake 3.22.1 and downloads the pinned Opus tarball.

A fresh clone has no `android/local.properties` (it is machine-specific and ignored), so Gradle
cannot find the SDK on its own and stops with *"SDK location not found"*. Point it at yours once,
either by exporting `ANDROID_HOME` or by creating that file:

```properties
# android/local.properties  — never commit this
sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk
```

Opening the `android/` directory in Android Studio writes it for you. CI needs neither, because the
GitHub runners already export `ANDROID_HOME`.

The frontend suite is `node --test` over `tauri/tests/`, with no browser and no DOM. Keep new
testable logic in `tauri/src/state.js` rather than in `main.js` so it stays that way. It wants
Node 22+: `npm test` hands the glob `tests/**/*.test.mjs` to `node --test`, which expands it itself
only from 22 on — an older runtime takes the pattern as a literal path and the suite never runs.

Also check that the frontend still builds (`cd tauri && npm run build`) if you touched anything
under `tauri/src/`.

The same three suites run in CI on every pull request
([`.github/workflows/ci.yml`](.github/workflows/ci.yml)), alongside `cargo clippy -- -D warnings`
and the Vite build — but run them locally first; CI is the second opinion, not the first.

## The rule that matters most: protocol changes land on both sides at once

A change to the wire format is only complete when **all three** of the following are in the same
commit or pull request:

1. **Both implementations.** The constants are mirrored between
   `android/app/src/main/java/ru/metwisom/micro/AudioProtocol.kt` and
   `tauri/src-tauri/src/proto.rs`. Neither is generated from the other.
2. **The tests that pin them.** `ProtocolConstantsTest` (Kotlin) and `protocol_constants_locked`
   (Rust) assert the same port, frame geometry, control-message types, nonce-space bytes and
   direction bytes. The crypto outputs are pinned by cross-implementation known-answer tests —
   `CryptoSessionTest` on the Kotlin side and the tests in `crypto.rs` on the Rust side — whose
   vectors were computed by a third, independent stack, so agreeing with each other is not enough:
   both must agree with the vectors.
3. **[`PROTOCOL.md`](PROTOCOL.md).** It is the normative description, not a summary written
   afterwards. If the document and the code disagree, that is a bug.

Editing one side alone fails the build. That is the intended behaviour, not an obstacle to work
around — a silent one-sided edit would ship two programs that no longer interoperate, or worse, that
appear to interoperate until a particular packet arrives. If you genuinely need to change a vector,
say in the pull request how the new value was derived and by what.

The same care applies to the crypto invariants: nonce spaces are disjoint by their first byte
(0 audio, 1 control, 2 HELO tag, 3 KEYS tag), and replay counters are strictly increasing per
direction. The audio sequence and the control counters live inside the session object precisely so
that a key can never be observed together with stale counters. Do not lift them back out.

## Code style

Match the surrounding code — the existing files are the reference, and a patch that reads like the
file it lands in is easier to review than one that is stylistically "correct" in isolation.

- **Rust** is clippy-clean, and CI enforces that (`cargo clippy -- -D warnings`) — run clippy before
  sending. The tree **is** rustfmt-formatted, to the style pinned by
  [`tauri/src-tauri/rustfmt.toml`](tauri/src-tauri/rustfmt.toml) — run `cargo fmt` before sending and
  the diff stays limited to what you changed. Leaving format-on-save enabled is fine.
- **Kotlin** follows the standard 4-space Android style already in the tree.
- **JavaScript** is plain ES modules, 2-space indent, no framework and no bundled UI library.
- Indentation, line endings and whitespace are covered by [`.editorconfig`](.editorconfig); most
  editors pick it up automatically.
- **Comments explain why, not what.** The interesting comments in this repository record a reason a
  reader could not recover from the code: why the loss estimate is floored at 5 % (libopus codes no
  in-band FEC at 0 %), why the packets are marked DSCP EF, why a lock is released before a rekey.
  Write that kind, and skip the kind that restates the next line.
- New user-visible strings go through the existing i18n tables (EN and RU) on both the desktop and
  the Android side. Do not hard-code English in the UI.

## Commit messages

[Conventional Commits](https://www.conventionalcommits.org/), imperative mood, lower-case subject,
no trailing period:

```
feat(android): release the wake lock when no client is connected
fix(client): rebind the socket after a network interface change
docs(protocol): document the KEYS authentication tag
```

Types in use: `feat`, `fix`, `docs`, `build`, `chore`, `test`, `refactor`. Add a scope when it
clarifies (`android`, `client`, `protocol`, `scripts`). Use the body to explain the reasoning
whenever the change is not self-evident — what was wrong, why this fix and not an obvious
alternative, what you ruled out. A one-line diff with a paragraph of reasoning is a good commit;
the reverse rarely is.

## Pull requests

Include in the description:

- **What changed and why.** The reasoning matters more than the diff summary; the diff is visible.
- **Which tests you ran**, with their output — all three suites, plus `npm run build` if the
  frontend changed.
- **Which platforms you actually tested on.** Say "built and ran on Windows 11 + Pixel 6" or
  whatever it was, and say plainly which platforms you did *not* try. Half of this project is
  platform-specific plumbing (virtual microphone creation, wake locks, tray behaviour, window
  placement), so an untested platform is a real risk, not a formality.
- **Anything that touches the wire format**, called out explicitly, with the three items from the
  section above accounted for.

Keep pull requests focused: one concern per PR, and unrelated cleanups in their own commits.

Builds are **unsigned** by design — Windows SmartScreen and macOS Gatekeeper will warn, and the
Android release APK is unsigned — so reviewers build and test from source. Make sure your branch
actually builds from a clean checkout, and mention any new dependency or system package you added,
because someone else has to install it.

## Security issues

Please do not open a public pull request or issue for a security problem. See
[`SECURITY.md`](SECURITY.md) for how to report one privately, and for the honest scope of what this
project does and does not defend against.

## Licence

Micro is MIT-licensed. By contributing you agree that your contribution is licensed under the same
terms. If you add third-party code, it must be license-compatible, and its licence and copyright
notice must be preserved in the tree along with it.
