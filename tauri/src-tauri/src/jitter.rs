//! Packet-level reorder / jitter buffer: decrypted Opus payloads keyed by sequence number, decoded
//! strictly in order, with Opus in-band FEC for a single recent loss and PLC for older gaps.
//!
//! It owns no sockets and no keys, so every recovery path — in-order, reordered, single loss, huge
//! gap, sequence wraparound — is exercised by the tests below against a real Opus codec.

use std::collections::BTreeMap;

use crate::proto::{FRAME, MAX_PLC, REORDER};

pub(crate) struct JitterBuffer {
    reorder: BTreeMap<u32, Vec<u8>>,
    /// Lowest frame we still intend to output (the cursor).
    next_seq: Option<u32>,
    /// Recycled payload buffers (no per-frame heap alloc).
    free: Vec<Vec<u8>>,
}

impl JitterBuffer {
    pub(crate) fn new() -> Self {
        JitterBuffer { reorder: BTreeMap::new(), next_seq: None, free: Vec::new() }
    }

    /// Drop everything, cursor included. Only correct for a *new* key: the sequence space belongs
    /// to the key, so rewinding the cursor under a key we have already played frames with would
    /// re-open that key's whole sequence range to replay.
    pub(crate) fn reset(&mut self) {
        self.next_seq = None;
        self.reorder.clear();
        self.free.clear();
    }

    /// Drop buffered payloads but keep the output cursor — for a pause under the *same* key
    /// (session declared lost and later re-adopted). Anything at or behind the cursor stays
    /// refused by `behind_cursor`, so a recorded frame cannot be played a second time.
    pub(crate) fn drop_buffered(&mut self) {
        self.reorder.clear();
        self.free.clear();
    }

    pub(crate) fn contains(&self, seq: u32) -> bool {
        self.reorder.contains_key(&seq)
    }

    /// `seq` is behind the output cursor (already played or concealed) → cheap pre-decrypt drop.
    pub(crate) fn behind_cursor(&self, seq: u32) -> bool {
        matches!(self.next_seq, Some(next) if seq != next && next.wrapping_sub(seq) < 0x8000_0000)
    }

    /// A recycled buffer to move a freshly-decrypted payload into (caller swaps its scratch in).
    pub(crate) fn take_free(&mut self) -> Vec<u8> {
        self.free.pop().unwrap_or_default()
    }

    pub(crate) fn insert(&mut self, seq: u32, payload: Vec<u8>) {
        self.reorder.insert(seq, payload);
        self.next_seq.get_or_insert(seq); // first frame of the session sets the cursor
    }

    /// Drain in strict sequence order, emitting every frame we can (real decode, FEC, or PLC).
    /// `emit` receives decoded mono i16 PCM (exactly FRAME samples).
    pub(crate) fn drain(
        &mut self,
        decoder: &mut audiopus::coder::Decoder,
        pcm: &mut [i16],
        recv_frames: &mut u32,
        lost_frames: &mut u32,
        mut emit: impl FnMut(&[i16]),
    ) {
        while let Some(next) = self.next_seq {
            if let Some(payload) = self.reorder.remove(&next) {
                // Expected frame is present → decode it for real. A corrupt payload is concealed
                // like a lost one rather than leaving a silent hole.
                match decoder.decode(Some(payload.as_slice()), &mut pcm[..], false) {
                    Ok(s) if s >= FRAME => {
                        *recv_frames += 1;
                        emit(&pcm[..FRAME]);
                    }
                    _ => {
                        if decoder.decode(Option::<&[u8]>::None, &mut pcm[..FRAME], false).is_ok() {
                            *lost_frames += 1;
                            emit(&pcm[..FRAME]);
                        }
                    }
                }
                self.free.push(payload); // recycle the buffer
                self.next_seq = Some(next.wrapping_add(1));
                continue;
            }

            // `next` is missing. Keep waiting unless we've received a frame far enough ahead
            // (REORDER slots) that `next` is unlikely to still be in flight. This also bounds the
            // buffer: anything further ahead is concealed or resynced to right here, so at most
            // REORDER frames ever wait.
            let (lo, hi) = match (self.reorder.keys().next(), self.reorder.keys().next_back()) {
                (Some(&lo), Some(&hi)) => (lo, hi),
                _ => break, // nothing buffered ahead → wait for more packets
            };
            if hi.wrapping_sub(next) < REORDER {
                break; // not late enough yet — give it a little more time
            }
            let distance = lo.wrapping_sub(next); // ≥ 1
            if distance > MAX_PLC {
                self.next_seq = Some(lo); // resync across a huge gap instead of mass-concealing
                continue;
            }
            if distance == 1 {
                // Recover `next` via the in-band FEC carried in the next packet (decode_fec = true).
                if let Some(payload) = self.reorder.get(&lo) {
                    if let Ok(s) = decoder.decode(Some(payload.as_slice()), &mut pcm[..FRAME], true) {
                        if s >= FRAME {
                            *lost_frames += 1;
                            emit(&pcm[..FRAME]);
                        }
                    }
                }
            } else if decoder.decode(Option::<&[u8]>::None, &mut pcm[..FRAME], false).is_ok() {
                *lost_frames += 1; // PLC for an older missing frame
                emit(&pcm[..FRAME]);
            }
            self.next_seq = Some(next.wrapping_add(1));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn opus_frames(n: usize) -> Vec<Vec<u8>> {
        let mut enc = audiopus::coder::Encoder::new(
            audiopus::SampleRate::Hz48000,
            audiopus::Channels::Mono,
            audiopus::Application::Voip,
        )
        .unwrap();
        enc.set_inband_fec(true).unwrap();
        enc.set_packet_loss_perc(5).unwrap();
        let mut out = Vec::new();
        for i in 0..n {
            let pcm: Vec<i16> = (0..FRAME)
                .map(|k| ((k + i * FRAME) as f32 * 0.05).sin() * 8000.0)
                .map(|v| v as i16)
                .collect();
            let mut buf = vec![0u8; 1500];
            let len = enc.encode(&pcm, &mut buf).unwrap();
            buf.truncate(len);
            out.push(buf);
        }
        out
    }
    fn decoder() -> audiopus::coder::Decoder {
        audiopus::coder::Decoder::new(audiopus::SampleRate::Hz48000, audiopus::Channels::Mono).unwrap()
    }
    /// Feed (seq, payload) pairs in arrival order (same duplicate/late filter as net_loop);
    /// returns (emitted, recv, lost).
    fn run_jb(arrivals: &[(u32, &[u8])]) -> (usize, u32, u32) {
        let mut jb = JitterBuffer::new();
        let mut dec = decoder();
        let mut pcm = [0i16; 1920];
        let (mut recv, mut lost, mut emitted) = (0u32, 0u32, 0usize);
        for &(seq, payload) in arrivals {
            if jb.contains(seq) || jb.behind_cursor(seq) {
                continue;
            }
            jb.insert(seq, payload.to_vec());
            jb.drain(&mut dec, &mut pcm, &mut recv, &mut lost, |f| {
                assert_eq!(f.len(), FRAME);
                emitted += 1;
            });
        }
        (emitted, recv, lost)
    }

    #[test]
    fn jitter_in_order_emits_every_frame() {
        let f = opus_frames(10);
        let arrivals: Vec<(u32, &[u8])> =
            f.iter().enumerate().map(|(i, p)| (i as u32, p.as_slice())).collect();
        assert_eq!(run_jb(&arrivals), (10, 10, 0));
    }

    #[test]
    fn jitter_single_loss_is_recovered_with_fec() {
        let f = opus_frames(7);
        // seq 3 never arrives; once 5 is in (REORDER frames ahead), 3 is concealed from 4's FEC.
        let arrivals: Vec<(u32, &[u8])> =
            [0, 1, 2, 4, 5, 6].iter().map(|&i| (i as u32, f[i].as_slice())).collect();
        let (emitted, recv, lost) = run_jb(&arrivals);
        assert_eq!((emitted, recv, lost), (7, 6, 1));
    }

    #[test]
    fn jitter_reordered_frames_play_in_order_without_loss() {
        let f = opus_frames(5);
        let arrivals: Vec<(u32, &[u8])> =
            [0, 1, 3, 2, 4].iter().map(|&i| (i as u32, f[i].as_slice())).collect();
        assert_eq!(run_jb(&arrivals), (5, 5, 0));
    }

    #[test]
    fn jitter_resyncs_across_a_huge_gap_instead_of_mass_concealing() {
        let f = opus_frames(5);
        let arrivals: Vec<(u32, &[u8])> = [(0, &f[0]), (1, &f[1]), (100, &f[2]), (101, &f[3]), (102, &f[4])]
            .iter()
            .map(|&(s, p)| (s as u32, p.as_slice()))
            .collect();
        assert_eq!(run_jb(&arrivals), (5, 5, 0));
    }

    #[test]
    fn jitter_handles_sequence_wraparound() {
        let f = opus_frames(4);
        let seqs = [u32::MAX - 1, u32::MAX, 0, 1];
        let arrivals: Vec<(u32, &[u8])> = seqs.iter().zip(&f).map(|(&s, p)| (s, p.as_slice())).collect();
        assert_eq!(run_jb(&arrivals), (4, 4, 0));
    }

    #[test]
    fn jitter_first_arrival_sets_the_cursor_and_late_frames_are_dropped() {
        // Frames 1, 0, 2: playback starts from the first arrival (1); the overtaken frame 0 is a
        // single late frame and is dropped, never concealed or played out of order.
        let f = opus_frames(3);
        let arrivals: Vec<(u32, &[u8])> =
            [(1u32, &f[1]), (0, &f[0]), (2, &f[2])].iter().map(|&(s, p)| (s, p.as_slice())).collect();
        assert_eq!(run_jb(&arrivals), (2, 2, 0));
        let mut jb = JitterBuffer::new();
        jb.next_seq = Some(10);
        assert!(jb.behind_cursor(5));
        assert!(!jb.behind_cursor(10));
        assert!(!jb.behind_cursor(11));
        assert!(jb.behind_cursor(10u32.wrapping_sub(1))); // wraparound-aware
    }

    #[test]
    fn a_same_key_pause_does_not_reopen_the_sequence_space_to_replay() {
        // Regression: the client used to clear the cursor whenever a session was declared lost, and
        // the server can re-adopt that very same session (same key) afterwards. With the cursor gone,
        // the first frame to arrive set it again — so an attacker replaying recorded datagrams from
        // earlier in the SAME key had the whole sequence range accepted a second time.
        let f = opus_frames(4);
        let mut jb = JitterBuffer::new();
        let mut dec = decoder();
        let mut pcm = [0i16; 1920];
        let (mut recv, mut lost, mut emitted) = (0u32, 0u32, 0usize);
        for i in 0..3u32 {
            jb.insert(i, f[i as usize].clone());
            jb.drain(&mut dec, &mut pcm, &mut recv, &mut lost, |_| emitted += 1);
        }
        assert_eq!(emitted, 3);

        // Session declared lost, then the same key comes back.
        jb.drop_buffered();

        // Every frame already played is still refused, without decrypting it again.
        for i in 0..3u32 {
            assert!(jb.behind_cursor(i), "seq {i} must stay behind the cursor across the pause");
        }
        // And the stream continues where it left off.
        assert!(!jb.behind_cursor(3));
        jb.insert(3, f[3].clone());
        jb.drain(&mut dec, &mut pcm, &mut recv, &mut lost, |_| emitted += 1);
        assert_eq!(emitted, 4);

        // A genuinely new key does rewind — that is what `reset` is for.
        jb.reset();
        assert!(!jb.behind_cursor(0));
    }

    #[test]
    fn jitter_buffer_stays_bounded_under_a_flood_of_future_frames() {
        // Far-future frames (gap > MAX_PLC) resync the cursor instead of piling up.
        let f = opus_frames(1);
        let mut jb = JitterBuffer::new();
        let mut dec = decoder();
        let mut pcm = [0i16; 1920];
        let (mut recv, mut lost) = (0u32, 0u32);
        jb.insert(0, f[0].clone());
        jb.drain(&mut dec, &mut pcm, &mut recv, &mut lost, |_| {});
        assert_eq!(jb.next_seq, Some(1));
        for s in 0..64u32 {
            jb.insert(1000 + s * 2, f[0].clone());
            jb.drain(&mut dec, &mut pcm, &mut recv, &mut lost, |_| {});
            assert!(jb.reorder.len() <= REORDER as usize, "buffer bounded: {}", jb.reorder.len());
        }
    }
}
