//! Key derivation, authentication tags and the small encodings the wire format is built from.
//!
//! Every function here has a counterpart in the phone's `CryptoSession.kt`, and the tests below are
//! known-answer vectors computed by an independent stack (Node/OpenSSL). `CryptoSessionTest.kt`
//! asserts the SAME values with BouncyCastle, so a divergence in SAS, HKDF, the AEAD or any tag
//! fails a build on one side before it can silently break interop on the wire.

use base64::Engine;
use chacha20poly1305::aead::AeadInPlace;
use chacha20poly1305::{ChaCha20Poly1305, Key, KeyInit, Nonce, Tag};
use hkdf::Hkdf;
use sha2::Sha256;
use x25519_dalek::{PublicKey, StaticSecret};
use zeroize::Zeroize;

use crate::proto::auth_nonce;

pub(crate) fn b64(bytes: &[u8]) -> String {
    base64::engine::general_purpose::STANDARD.encode(bytes)
}

pub(crate) fn unb64(s: &str) -> Option<Vec<u8>> {
    base64::engine::general_purpose::STANDARD.decode(s.trim()).ok()
}

pub(crate) fn unb64_n<const N: usize>(s: &str) -> Option<[u8; N]> {
    let raw = unb64(s)?;
    if raw.len() == N {
        let mut a = [0u8; N];
        a.copy_from_slice(&raw);
        Some(a)
    } else {
        None
    }
}

pub(crate) fn unb64_32(s: &str) -> Option<[u8; 32]> {
    unb64_n::<32>(s)
}

pub(crate) fn sha256(data: &[u8]) -> [u8; 32] {
    use sha2::Digest;
    let mut h = sha2::Sha256::new();
    h.update(data);
    h.finalize().into()
}

/// 4-digit Short Authentication String over both long-term public keys plus the client's fresh
/// per-pairing nonce (so the code differs on every attempt). The nonce is committed to in `PC`, so
/// mixing it in doesn't let a MITM grind the code — the per-attempt 1/10000 bound is unchanged.
pub(crate) fn sas(client_pub: &[u8; 32], server_pub: &[u8; 32], client_nonce: &[u8; 16]) -> String {
    use sha2::Digest;
    let mut h = sha2::Sha256::new();
    h.update(b"MICRO-SAS-v1");
    h.update(client_pub);
    h.update(server_pub);
    h.update(client_nonce);
    let d = h.finalize();
    // Reduce 32 bits, not 16. Folding 65536 values into 10000 left codes 0000-5535 with seven
    // preimages and the rest with six, so the best guess succeeded with p = 7/65536, about 7%
    // above the 1/10000 the SAS is supposed to bound. Over 2^32 the residual bias is ~2e-6.
    let v = u32::from_be_bytes([d[0], d[1], d[2], d[3]]) % 10000;
    format!("{v:04}")
}

/// Short, human-comparable fingerprint of the server identity being pinned.
pub(crate) fn fingerprint(server_pub: &[u8; 32]) -> String {
    let d = sha256(server_pub);
    format!("{:02X}{:02X}-{:02X}{:02X}", d[0], d[1], d[2], d[3])
}

/// HELO/KEYS authentication key: static×static DH of the two pinned identities → HKDF (info
/// "micro-helo-v1"). Only the genuine paired peers can compute it, so neither a HELO nor a KEYS can
/// be forged by a LAN host that merely sniffed the plaintext fields.
pub(crate) fn helo_auth_cipher(static_secret: &StaticSecret, server_static: &[u8; 32]) -> ChaCha20Poly1305 {
    let ds = static_secret.diffie_hellman(&PublicKey::from(*server_static));
    let hk = Hkdf::<Sha256>::new(None, ds.as_bytes());
    let mut key = [0u8; 32];
    hk.expand(b"micro-helo-v1", &mut key).unwrap();
    let cipher = ChaCha20Poly1305::new(Key::from_slice(&key));
    key.zeroize();
    cipher
}

/// 16-byte tag over `aad` (empty plaintext). Mirrors the Android `CryptoSession.authTag` (KAT-locked).
pub(crate) fn auth_tag(cipher: &ChaCha20Poly1305, kind: u8, ctr: u64, aad: &[u8]) -> [u8; 16] {
    let tag = cipher
        .encrypt_in_place_detached(Nonce::from_slice(&auth_nonce(kind, ctr)), aad, &mut [])
        .expect("auth tag");
    let mut out = [0u8; 16];
    out.copy_from_slice(tag.as_slice());
    out
}

/// Constant-time verification of a tag produced by `auth_tag` on the other side.
pub(crate) fn auth_tag_ok(cipher: &ChaCha20Poly1305, kind: u8, ctr: u64, aad: &[u8], tag: &[u8; 16]) -> bool {
    cipher
        .decrypt_in_place_detached(
            Nonce::from_slice(&auth_nonce(kind, ctr)),
            aad,
            &mut [],
            Tag::from_slice(tag),
        )
        .is_ok()
}

/// Session key: ephemeral-ephemeral DH (forward secrecy) mixed with static-static DH of the
/// pinned long-term keys (authentication). Returns None for a non-contributory (low-order point)
/// peer key — such a KEYS can't be genuine, and we never derive a key from an all-zero secret.
pub(crate) fn session_cipher(
    eph_secret: &StaticSecret,
    static_secret: &StaticSecret,
    their_eph: &[u8; 32],
    their_static: &[u8; 32],
    salt: &[u8],
) -> Option<ChaCha20Poly1305> {
    let de = eph_secret.diffie_hellman(&PublicKey::from(*their_eph));
    let ds = static_secret.diffie_hellman(&PublicKey::from(*their_static));
    if !de.was_contributory() || !ds.was_contributory() {
        return None;
    }
    let mut ikm = Vec::with_capacity(64);
    ikm.extend_from_slice(de.as_bytes());
    ikm.extend_from_slice(ds.as_bytes());
    let hk = Hkdf::<Sha256>::new(Some(salt), &ikm);
    let mut key = [0u8; 32];
    hk.expand(b"micro-session", &mut key).unwrap();
    let cipher = ChaCha20Poly1305::new(Key::from_slice(&key));
    // Don't leave the raw session key / DH material lying in freed heap & stack.
    key.zeroize();
    ikm.zeroize();
    Some(cipher)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::proto::{ctl_nonce, CTL_C2S, CTL_S2C, CT_BYE, CT_PONG, NONCE_AUDIO, NONCE_HELO, NONCE_KEYS};

    // Cross-implementation known-answer vectors, computed by an independent stack (Node/OpenSSL).
    // The Android server has a mirror test (CryptoSessionTest.kt) asserting the SAME values with
    // BouncyCastle, so a divergence in SAS, HKDF, the AEAD or any tag fails a build before it could
    // silently break interop on the wire.
    const KAT_SAS: &str = "9555"; //         sas([0x11;32], [0x22;32], nonce [0x33;16])
    const KAT_HKDF: &str = "2d2a66ea48d6bc8bf032a70bbee4a6e0122c7c6b10d6d6687bc22b2250eb470f";
    const KAT_AEAD: &str = "516b4ccb79d49eeec40db5d35a7dd90195409e45e81a9140be4b7960342091a0";
    const KAT_HELO_KEY: &str = "07a9697c252ce982488e1b965f63af4129bede3fb851f4b5d8bf1f8993ca8a8b";
    const KAT_HELO_TAG: &str = "a21c2cb564bdd5024b9e2d7bdc92852e";
    const KAT_KEYS_TAG: &str = "57b75c149e34e51764bb7b4bc811d7a3";
    const KAT_CT_S2C: &str = "32ebb904de32f6a521bbcf493ae3cebe09ffef7bde7ba6631c"; // PONG 01..08, ctr 7

    fn hex(bytes: &[u8]) -> String {
        bytes.iter().map(|b| format!("{b:02x}")).collect()
    }
    fn unhex(s: &str) -> Vec<u8> {
        s.as_bytes()
            .chunks(2)
            .map(|b| u8::from_str_radix(std::str::from_utf8(b).unwrap(), 16).unwrap())
            .collect()
    }
    fn kat_session() -> ChaCha20Poly1305 {
        ChaCha20Poly1305::new(Key::from_slice(&unhex(KAT_HKDF)))
    }
    fn kat_helo_cipher() -> ChaCha20Poly1305 {
        let hk = Hkdf::<Sha256>::new(None, &[0x77u8; 32]);
        let mut key = [0u8; 32];
        hk.expand(b"micro-helo-v1", &mut key).unwrap();
        assert_eq!(hex(&key), KAT_HELO_KEY);
        ChaCha20Poly1305::new(Key::from_slice(&key))
    }

    #[test]
    fn sas_format_and_known_answer() {
        let s = sas(&[0x11; 32], &[0x22; 32], &[0x33; 16]);
        assert_eq!(s.len(), 4);
        assert!(s.chars().all(|c| c.is_ascii_digit()));
        assert_eq!(s, KAT_SAS); // must match the Android side bit-for-bit
    }

    #[test]
    fn hkdf_known_answer() {
        let mut ikm = Vec::new();
        ikm.extend_from_slice(&[0x33u8; 32]);
        ikm.extend_from_slice(&[0x44u8; 32]);
        let hk = Hkdf::<Sha256>::new(Some(&[0x55u8; 16]), &ikm);
        let mut key = [0u8; 32];
        hk.expand(b"micro-session", &mut key).unwrap();
        assert_eq!(hex(&key), KAT_HKDF);
    }

    #[test]
    fn audio_aead_known_answer() {
        let cipher = kat_session();
        let mut nonce = [0u8; 12];
        nonce[0] = NONCE_AUDIO;
        nonce[8..].copy_from_slice(&42u32.to_be_bytes());
        let mut buf = b"micro test frame".to_vec();
        cipher.encrypt_in_place(Nonce::from_slice(&nonce), b"", &mut buf).unwrap();
        assert_eq!(hex(&buf), KAT_AEAD); // ciphertext || 16-byte tag
    }

    #[test]
    fn helo_and_keys_tags_known_answer() {
        let cipher = kat_helo_cipher();
        assert_eq!(hex(&auth_tag(&cipher, NONCE_HELO, 42, b"HELO|kat")), KAT_HELO_TAG);
        assert_eq!(hex(&auth_tag(&cipher, NONCE_KEYS, 42, b"KEYS|kat")), KAT_KEYS_TAG);
        // Verification accepts the genuine tag and rejects a wrong kind / counter / AAD / bit flip.
        let keys_tag: [u8; 16] = unhex(KAT_KEYS_TAG).try_into().unwrap();
        assert!(auth_tag_ok(&cipher, NONCE_KEYS, 42, b"KEYS|kat", &keys_tag));
        assert!(!auth_tag_ok(&cipher, NONCE_HELO, 42, b"KEYS|kat", &keys_tag));
        assert!(!auth_tag_ok(&cipher, NONCE_KEYS, 43, b"KEYS|kat", &keys_tag));
        assert!(!auth_tag_ok(&cipher, NONCE_KEYS, 42, b"KEYS|kax", &keys_tag));
        let mut flipped = keys_tag;
        flipped[0] ^= 1;
        assert!(!auth_tag_ok(&cipher, NONCE_KEYS, 42, b"KEYS|kat", &flipped));
    }

    #[test]
    fn server_control_message_known_answer() {
        // What the server seals (S2C, ctr 7, PONG + token 01..08) must open on our side exactly.
        let cipher = kat_session();
        let mut data = unhex(KAT_CT_S2C);
        cipher.decrypt_in_place(Nonce::from_slice(&ctl_nonce(CTL_S2C, 7)), b"", &mut data).unwrap();
        assert_eq!(data, vec![CT_PONG, 1, 2, 3, 4, 5, 6, 7, 8]);
        // Direction byte stops reflection: the same bytes don't open as C2S.
        let mut again = unhex(KAT_CT_S2C);
        assert!(cipher.decrypt_in_place(Nonce::from_slice(&ctl_nonce(CTL_C2S, 7)), b"", &mut again).is_err());
    }

    #[test]
    fn b64_roundtrips() {
        let x = [7u8; 32];
        assert_eq!(unb64_32(&b64(&x)), Some(x));
        assert_eq!(unb64_32("not valid base64 !!"), None);
        assert_eq!(unb64_32(&b64(&[0u8; 16])), None); // wrong length rejected
        assert_eq!(unb64_n::<8>(&b64(&42u64.to_be_bytes())).map(u64::from_be_bytes), Some(42));
    }

    #[test]
    fn session_key_is_symmetric_authenticated_and_contributory() {
        let c_eph = StaticSecret::from([3u8; 32]);
        let c_stat = StaticSecret::from([4u8; 32]);
        let s_eph = StaticSecret::from([5u8; 32]);
        let s_stat = StaticSecret::from([6u8; 32]);
        let salt = [9u8; 16];
        let client = session_cipher(
            &c_eph,
            &c_stat,
            &PublicKey::from(&s_eph).to_bytes(),
            &PublicKey::from(&s_stat).to_bytes(),
            &salt,
        )
        .unwrap();
        let server = session_cipher(
            &s_eph,
            &s_stat,
            &PublicKey::from(&c_eph).to_bytes(),
            &PublicKey::from(&c_stat).to_bytes(),
            &salt,
        )
        .unwrap();
        let mut nonce = [0u8; 12];
        nonce[8..].copy_from_slice(&7u32.to_be_bytes());
        let mut frame = b"opus payload".to_vec();
        server.encrypt_in_place(Nonce::from_slice(&nonce), b"", &mut frame).unwrap();
        client.decrypt_in_place(Nonce::from_slice(&nonce), b"", &mut frame).unwrap();
        assert_eq!(&frame, b"opus payload");

        let impostor = session_cipher(
            &s_eph,
            &StaticSecret::from([0xAAu8; 32]), // wrong static key
            &PublicKey::from(&c_eph).to_bytes(),
            &PublicKey::from(&c_stat).to_bytes(),
            &salt,
        )
        .unwrap();
        let mut frame2 = b"opus payload".to_vec();
        server.encrypt_in_place(Nonce::from_slice(&nonce), b"", &mut frame2).unwrap();
        assert!(impostor.decrypt_in_place(Nonce::from_slice(&nonce), b"", &mut frame2).is_err());

        // A low-order peer point (all-zero) yields a non-contributory secret → refused.
        assert!(session_cipher(&c_eph, &c_stat, &[0u8; 32], &PublicKey::from(&s_stat).to_bytes(), &salt)
            .is_none());
    }

    #[test]
    fn fixed_identity_known_answers_through_the_real_key_derivations() {
        // Same fixed scalars as CryptoSessionTest.kt (client 0x11, server 0x22, ephemerals 0x33/0x44,
        // salt 0x55); the vectors were computed by Node/OpenSSL, so all three stacks must agree.
        let c_stat = StaticSecret::from([0x11u8; 32]);
        let s_stat = StaticSecret::from([0x22u8; 32]);
        let c_eph = StaticSecret::from([0x33u8; 32]);
        let s_eph = StaticSecret::from([0x44u8; 32]);
        assert_eq!(
            hex(&PublicKey::from(&c_stat).to_bytes()),
            "7b4e909bbe7ffe44c465a220037d608ee35897d31ef972f07f74892cb0f73f13"
        );
        let ka = helo_auth_cipher(&c_stat, &PublicKey::from(&s_stat).to_bytes());
        assert_eq!(hex(&auth_tag(&ka, NONCE_HELO, 42, b"HELO|kat")), "d24ab59a17da02202f5e6f6220781fc4");
        let sess = session_cipher(
            &c_eph,
            &c_stat,
            &PublicKey::from(&s_eph).to_bytes(),
            &PublicKey::from(&s_stat).to_bytes(),
            &[0x55u8; 16],
        )
        .unwrap();
        let mut body = vec![CT_BYE];
        sess.encrypt_in_place(Nonce::from_slice(&ctl_nonce(CTL_C2S, 1)), b"", &mut body).unwrap();
        assert_eq!(hex(&body), "1b0b2f158471bec4b85a84576dbbc4034f");
    }
}
