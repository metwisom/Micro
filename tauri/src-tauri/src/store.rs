//! Everything Micro keeps between runs: the long-term identity, the pinned server keys, the HELO
//! anti-replay high-water mark and this install's client id.
//!
//! The identity lives in the OS secret store (Credential Manager / Keychain / Secret Service) so it
//! is encrypted at rest by the OS and isn't a plain file any process can read. When no store is
//! reachable (e.g. a headless Linux box with no Secret Service) we fall back to an owner-only file.

use std::io::{Read, Write};

use x25519_dalek::StaticSecret;
use zeroize::Zeroize;

use crate::crypto::{b64, unb64_32};

const KEYRING_SERVICE: &str = "ru.metwisom.micro";
const KEYRING_USER: &str = "identity";

pub(crate) fn config_dir() -> std::path::PathBuf {
    let mut d = dirs::config_dir().unwrap_or_else(|| std::path::PathBuf::from("."));
    d.push("Micro");
    d
}

pub(crate) fn load_or_create_client_id() -> String {
    let dir = config_dir();
    let file = dir.join("client-id");
    if let Ok(mut f) = std::fs::File::open(&file) {
        let mut s = String::new();
        if f.read_to_string(&mut s).is_ok() {
            let s = s.trim().to_string();
            if !s.is_empty() {
                return s;
            }
        }
    }
    let id = uuid::Uuid::new_v4().to_string();
    let _ = std::fs::create_dir_all(&dir);
    if let Ok(mut f) = std::fs::File::create(&file) {
        let _ = f.write_all(id.as_bytes());
    }
    id
}

fn keyring_entry() -> Option<keyring::Entry> {
    keyring::Entry::new(KEYRING_SERVICE, KEYRING_USER).ok()
}

// Restrict a fallback secret file to the owner (Unix). On Windows the per-user profile ACL already
// keeps it out of other users' reach.
#[cfg(unix)]
fn restrict(path: &std::path::Path) {
    use std::os::unix::fs::PermissionsExt;
    let _ = std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600));
}
#[cfg(not(unix))]
fn restrict(_path: &std::path::Path) {}

/// Loaded once at startup (a keyring round-trip per connect would be wasteful, and on Linux it can
/// prompt to unlock the Secret Service).
pub(crate) fn load_identity() -> StaticSecret {
    // 1. Prefer the OS secret store.
    if let Some(entry) = keyring_entry() {
        if let Ok(stored) = entry.get_password() {
            if let Some(sk) = unb64_32(&stored) {
                return StaticSecret::from(sk);
            }
        }
    }
    // 2. Migrate a legacy file-based identity into the store, then delete the file.
    let file = config_dir().join("identity");
    if let Ok(b) = std::fs::read(&file) {
        if b.len() >= 32 {
            let mut a = [0u8; 32];
            a.copy_from_slice(&b[..32]);
            if let Some(entry) = keyring_entry() {
                if entry.set_password(&b64(&a)).is_ok() {
                    let _ = std::fs::remove_file(&file);
                }
            }
            return StaticSecret::from(a);
        }
    }
    // 3. Fresh identity. Never ignore an RNG failure: a zeroed secret would become our identity key.
    let mut sk = [0u8; 32];
    getrandom::fill(&mut sk).expect("OS RNG unavailable — cannot create an identity key");
    let stored = keyring_entry().is_some_and(|e| e.set_password(&b64(&sk)).is_ok());
    if !stored {
        // No secret store available → persist to an owner-only file so the identity survives restarts.
        let _ = std::fs::create_dir_all(config_dir());
        let _ = std::fs::write(&file, sk);
        restrict(&file);
    }
    let secret = StaticSecret::from(sk);
    sk.zeroize();
    secret
}

/// Wipe the identity from the OS secret store (used by `reset`, which otherwise only clears files).
pub(crate) fn clear_identity() {
    if let Some(entry) = keyring_entry() {
        let _ = entry.delete_credential();
    }
}

// Pins are keyed by the server's *identity* (its long-term pubkey), not its IP — so a phone is
// recognised on any interface/IP, and a DHCP change doesn't force a re-pair.
fn pin_file_id(pubkey: &[u8; 32]) -> std::path::PathBuf {
    let name: String = pubkey.iter().map(|b| format!("{b:02x}")).collect();
    config_dir().join("pins").join(name)
}

pub(crate) fn is_pinned(pubkey: &[u8; 32]) -> bool {
    pin_file_id(pubkey).exists()
}

pub(crate) fn save_pin_id(pubkey: &[u8; 32]) {
    let _ = std::fs::create_dir_all(config_dir().join("pins"));
    let p = pin_file_id(pubkey);
    let _ = std::fs::write(&p, b64(pubkey));
    restrict(&p);
}

/// Carry pins written by 1.0.0 over to the current layout, once.
///
/// 1.0.0 keyed the phone's pin by the *host* it was reached at (`pins/<host>`); since 2.0.0 a pin is
/// keyed by the identity it pins (`pins/<hex of the public key>`), so a phone is recognised on any
/// address. The file content is the same in both — the base64 public key — so an old file carries
/// everything needed. Without this, every desktop upgraded from 1.0.0 silently lost its pairing and
/// met an unexpected code prompt on first launch, which is exactly the kind of surprise that teaches
/// people to click through prompts.
/// Returns how many were moved, for the caller to log once a logger exists — this has to run
/// before anything reads a pin, which is before the log plugin is up.
pub(crate) fn migrate_legacy_pins() -> usize {
    migrate_legacy_pins_in(&config_dir().join("pins"))
}

fn is_current_pin_name(name: &str) -> bool {
    name.len() == 64 && name.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

fn migrate_legacy_pins_in(dir: &std::path::Path) -> usize {
    let Ok(entries) = std::fs::read_dir(dir) else { return 0 };
    let mut moved = 0;
    for entry in entries.flatten() {
        let path = entry.path();
        let Some(name) = path.file_name().and_then(|n| n.to_str()) else { continue };
        if !path.is_file() || is_current_pin_name(name) {
            continue;
        }
        // Only a file that really holds a 32-byte key is a pin; anything else is left untouched.
        let Some(pk) = std::fs::read_to_string(&path).ok().and_then(|t| unb64_32(&t)) else { continue };
        let hex: String = pk.iter().map(|b| format!("{b:02x}")).collect();
        let target = dir.join(hex);
        if !target.exists() && std::fs::write(&target, b64(&pk)).is_err() {
            continue; // keep the old file: better an ignored leftover than a lost pairing
        }
        restrict(&target);
        let _ = std::fs::remove_file(&path);
        moved += 1;
    }
    moved
}

// Persisted HELO counter (the HELO-auth nonce on a *long-term* key → must never repeat or regress).
fn helo_ctr_file() -> std::path::PathBuf {
    config_dir().join("helo-ctr")
}

pub(crate) fn load_helo_ctr() -> u64 {
    std::fs::read(helo_ctr_file())
        .ok()
        .filter(|b| b.len() == 8)
        .map(|b| u64::from_be_bytes(b[..8].try_into().unwrap()))
        .unwrap_or(0)
}

/// Write the reserved high-water mark durably. Written to a temporary file, flushed, then renamed
/// over the real one: a plain write can be interrupted and leave a short file, and `load_helo_ctr`
/// would then treat the mark as missing and resume from the wall clock — below counters this
/// install may already have used under a key that outlives the process.
pub(crate) fn save_helo_ctr(v: u64) {
    use std::io::Write as _;
    let _ = std::fs::create_dir_all(config_dir());
    let p = helo_ctr_file();
    let tmp = p.with_extension("tmp");
    let wrote = (|| -> std::io::Result<()> {
        let mut f = std::fs::File::create(&tmp)?;
        f.write_all(&v.to_be_bytes())?;
        f.sync_all()?;
        Ok(())
    })()
    .is_ok();
    if wrote && std::fs::rename(&tmp, &p).is_ok() {
        restrict(&p);
    } else {
        let _ = std::fs::remove_file(&tmp);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_pins() -> std::path::PathBuf {
        let d = std::env::temp_dir().join(format!("micro-pins-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    #[test]
    fn a_pin_written_by_1_0_0_is_carried_over_to_the_identity_keyed_layout() {
        let dir = temp_pins();
        let pk = [0x5au8; 32];
        // 1.0.0 layout: file named after the host, content = base64 public key.
        std::fs::write(dir.join("192.168.1.50"), b64(&pk)).unwrap();

        assert_eq!(migrate_legacy_pins_in(&dir), 1);

        let hex: String = pk.iter().map(|b| format!("{b:02x}")).collect();
        let migrated = std::fs::read_to_string(dir.join(&hex)).expect("identity-keyed pin exists");
        assert_eq!(unb64_32(&migrated), Some(pk), "content survives unchanged");
        assert!(!dir.join("192.168.1.50").exists(), "the host-keyed file is gone");

        // Idempotent: a second launch finds nothing to do and breaks nothing.
        assert_eq!(migrate_legacy_pins_in(&dir), 0);
        assert!(dir.join(&hex).exists());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn migration_touches_nothing_that_is_not_a_legacy_pin() {
        let dir = temp_pins();
        let current = "ab".repeat(32); // already in the identity-keyed layout
        std::fs::write(dir.join(&current), b64(&[1u8; 32])).unwrap();
        std::fs::write(dir.join("notes.txt"), "not a key").unwrap();
        std::fs::write(dir.join("short"), b64(&[2u8; 16])).unwrap(); // decodes, wrong length

        assert_eq!(migrate_legacy_pins_in(&dir), 0);
        assert!(dir.join(&current).exists());
        assert!(dir.join("notes.txt").exists());
        assert!(dir.join("short").exists());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_missing_pins_directory_is_not_an_error() {
        let dir = std::env::temp_dir().join(format!("micro-absent-{}", uuid::Uuid::new_v4()));
        assert_eq!(migrate_legacy_pins_in(&dir), 0);
    }
}
