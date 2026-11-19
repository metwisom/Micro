# What and why

<!-- A couple of sentences. Link the issue it closes, if there is one. -->

## Tests run

- [ ] Rust — `cd tauri/src-tauri && cargo test --lib`
- [ ] Android — `cd android && ./gradlew testDebugUnitTest` (`gradlew.bat` on Windows)
- [ ] JS — `cd tauri && npm test`

<!-- If a suite is untouched by this change, say so instead of ticking it. -->

## Tested by hand on

- [ ] Windows
- [ ] macOS
- [ ] Linux
- [ ] An Android phone (which version and model: )
- [ ] Not tested by hand — why:

## Wire protocol

- [ ] No change to the bytes on the wire.
- [ ] Changed — and the Kotlin server, the Rust client and `PROTOCOL.md` are all updated **in this
      PR**, because a protocol change that lands in only one of them breaks pairing or audio.

## Docs

- [ ] Updated the docs this change affects (`README.md`, `BUILD.md`, `PROTOCOL.md`,
      `scripts/README.md`), or no doc change is needed.
