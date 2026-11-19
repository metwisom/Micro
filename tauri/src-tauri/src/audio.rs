// Native audio output via cpal.
// The net thread pushes decoded mono 48 kHz f32 samples into a lock-free SPSC ring; the cpal
// callback (real-time thread) pops them — no mutex on the audio path — through an adaptive jitter
// buffer + 4-point Catmull-Rom resampler (to the device rate) + gain + soft-knee limiter. A second
// (monitor) stream can play the same audio to another device (headphones).

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use ringbuf::traits::{Consumer, Observer, Producer, Split};
use ringbuf::{HeapCons, HeapProd, HeapRb};
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use tauri::{AppHandle, Emitter};

use crate::state::LockExt; // poison-proof `.lock_safe()` (see state.rs)

const FRAME: usize = 480;
const SRC_RATE: f32 = 48000.0;
const RING_CAP: usize = 96000; // ~2 s of mono 48 kHz
/// After a failed device open, wait this long before trying again (instead of re-enumerating the
/// host and allocating rings every loop tick while no output device exists).
const REBUILD_BACKOFF: Duration = Duration::from_secs(1);
/// A ring that stays empty longer than this after an underrun was a *pause* (mute, phone silent,
/// session lost), not network jitter: the latency setpoint is not raised for it.
const PAUSE_GAP: Duration = Duration::from_millis(200);
/// With no underrun for this long, walk the latency setpoint one frame back toward its floor.
const TARGET_DECAY: Duration = Duration::from_secs(30);

/// Soft-knee limiter. Linear (untouched) below ±THRESH, then a smooth tanh knee that asymptotes to
/// ±1 — so a boosted signal (default gain 1.5) rounds its peaks instead of hard-clipping into
/// audible distortion. C1-continuous at the knee (tanh'(0) = 1 matches the unity slope below it).
#[inline]
fn soft_limit(x: f32) -> f32 {
    const THRESH: f32 = 0.8;
    let a = x.abs();
    if a <= THRESH {
        x
    } else {
        let head = 1.0 - THRESH;
        x.signum() * (THRESH + head * ((a - THRESH) / head).tanh())
    }
}

type Prod = HeapProd<f32>;
type ProdSlot = Arc<Mutex<Option<Prod>>>;

/// Consumer side — lives entirely inside the cpal callback (single owner → lock-free).
/// Adaptive jitter (one bump per underrun event) + 4-point Catmull-Rom resampler to the device
/// rate. Cubic interpolation rolls off the imaging/aliasing that plain linear leaves on the high
/// end when the device isn't 48 kHz, for noticeably cleaner highs at near-zero extra cost.
struct Player {
    cons: HeapCons<f32>,
    target: usize, // source (48 kHz) samples
    min: usize,
    max: usize,
    started: bool,
    streak: u32,
    ratio: f32, // source samples per output sample (48000 / device_rate)
    phase: f32,
    // 4-point window: sm1, s0, s1, s2 — output is interpolated between s0 and s1.
    sm1: f32,
    s0: f32,
    s1: f32,
    s2: f32,
    fade: f32,      // 0→1 gain ramp applied on (re)start to avoid a silence→signal click
    fade_step: f32, // per-output-sample ramp increment (~5 ms)
    underrun_at: Option<Instant>, // when the ring last ran dry (None once the setpoint was judged)
    last_underrun: Instant, // for the slow decay of `target`
    last_decay: Instant,
}

impl Player {
    fn new(cons: HeapCons<f32>, prefill_frames: usize, device_rate: u32) -> Self {
        // Clamp against the ring itself: a prefill target the ring cannot physically hold would
        // never be reached, and the player would stay silent forever. Half of RING_CAP leaves
        // room for the producer to keep writing while we wait.
        let min = prefill_frames.clamp(1, RING_CAP / FRAME / 2) * FRAME;
        Player {
            cons,
            target: min,
            min,
            max: min + 8 * FRAME,
            started: false,
            streak: 0,
            ratio: SRC_RATE / device_rate as f32,
            phase: 0.0,
            sm1: 0.0,
            s0: 0.0,
            s1: 0.0,
            s2: 0.0,
            fade: 0.0,
            fade_step: 1.0 / (device_rate as f32 * 0.005).max(1.0), // ramp to full over ~5 ms
            underrun_at: None,
            last_underrun: Instant::now(),
            last_decay: Instant::now(),
        }
    }

    fn fill(&mut self, out: &mut [f32], ch: usize, gain: f32) {
        let ch = ch.max(1);
        let frames = out.len() / ch;
        if !self.started {
            if self.cons.occupied_len() < self.target {
                out.iter_mut().for_each(|v| *v = 0.0);
                return;
            }
            // Judge the last underrun now that data is back: a quick refill means the network
            // starved us (raise the setpoint, one bump per event); a long gap was a deliberate pause.
            if let Some(t) = self.underrun_at.take() {
                if t.elapsed() < PAUSE_GAP && self.target < self.max {
                    self.target += FRAME;
                }
            }
            self.started = true;
            self.sm1 = 0.0;
            self.s0 = self.cons.try_pop().unwrap_or(0.0);
            self.s1 = self.cons.try_pop().unwrap_or(0.0);
            self.s2 = self.cons.try_pop().unwrap_or(0.0);
            self.phase = 0.0;
            self.fade = 0.0; // fade in from silence on every (re)start to suppress the click
        }

        let mut underran = false;
        for f in 0..frames {
            // Catmull-Rom cubic over (sm1, s0, s1, s2) at fractional position `phase` in [s0, s1).
            let t = self.phase;
            let (a, b, c0, d) = (self.sm1, self.s0, self.s1, self.s2);
            let interp = b + 0.5
                * t
                * (c0 - a + t * (2.0 * a - 5.0 * b + 4.0 * c0 - d + t * (3.0 * (b - c0) + d - a)));
            let v = soft_limit(interp * gain * self.fade);
            if self.fade < 1.0 {
                self.fade = (self.fade + self.fade_step).min(1.0);
            }
            let base = f * ch;
            for c in 0..ch {
                out[base + c] = v;
            }
            self.phase += self.ratio;
            while self.phase >= 1.0 {
                self.phase -= 1.0;
                self.sm1 = self.s0;
                self.s0 = self.s1;
                self.s1 = self.s2;
                match self.cons.try_pop() {
                    Some(x) => self.s2 = x,
                    None => {
                        self.s2 = 0.0;
                        underran = true;
                    }
                }
            }
        }

        if underran {
            self.started = false;
            self.underrun_at = Some(Instant::now());
            self.last_underrun = Instant::now();
            self.streak = 0;
        } else if self.target > self.min
            && self.last_underrun.elapsed() > TARGET_DECAY
            && self.last_decay.elapsed() > TARGET_DECAY
        {
            // Long clean run: give back one frame of latency at a time.
            self.target -= FRAME;
            self.last_decay = Instant::now();
        } else if self.cons.occupied_len() > self.target + 2 * FRAME {
            self.streak += 1;
            // Sustained overfill ⇒ the sender's clock runs a hair faster than ours. Trim a tiny
            // (~1.3 ms) slice rather than a whole 10 ms frame, so the correction is inaudible instead
            // of an audible click; the lower streak threshold keeps the long-run drift-rejection rate.
            if self.streak > 5 {
                const TRIM: usize = FRAME / 8; // 60 samples ≈ 1.3 ms @ 48 kHz
                for _ in 0..TRIM {
                    let _ = self.cons.try_pop();
                }
                // Only walk the latency setpoint down when we're *well* above it even after trimming,
                // so normal jitter doesn't drag the buffer toward the underrun floor.
                if self.target > self.min && self.cons.occupied_len() > self.target + 4 * FRAME {
                    self.target -= FRAME;
                }
                self.streak = 0;
            }
        } else {
            self.streak = 0;
        }
    }
}

struct Ctrl {
    stop: AtomicBool,
    rebuild: AtomicBool,     // main device changed / errored
    rebuild_mon: AtomicBool, // monitor stream errored
    monitor_on: AtomicBool,
    main_device: Mutex<Option<String>>, // None = system default
    monitor_device: Mutex<Option<String>>,
    gain: AtomicU32, // f32 bits
}

/// Cheap clonable handle the net thread uses to push samples. The producer half sits behind a mutex
/// that's only ever contended when the audio thread swaps streams (device rebuild); the realtime
/// cpal callback (the consumer) stays fully lock-free.
#[derive(Clone)]
pub struct Sink {
    main: ProdSlot,
    monitor: ProdSlot,
    ctrl: Arc<Ctrl>,
}

impl Sink {
    pub fn push(&self, s: &[f32]) {
        if let Some(p) = self.main.lock_safe().as_mut() {
            let n = p.push_slice(s);
            // Ring full → the cpal callback has stalled (device hiccup / lost stream). The Player
            // drains toward `target` long before the 2 s cap fills, so this is essentially
            // unreachable in normal jitter; log it rather than silently discarding freshest audio.
            if n < s.len() {
                log::debug!("output ring full — dropped {} samples", s.len() - n);
            }
        }
        if self.ctrl.monitor_on.load(Ordering::Relaxed) {
            if let Some(p) = self.monitor.lock_safe().as_mut() {
                p.push_slice(s); // monitor is best-effort; a full ring here is harmless
            }
        }
    }
}

pub struct Output {
    sink: Sink,
    handle: Option<JoinHandle<()>>,
}

impl Output {
    pub fn start(app: AppHandle, prefill_frames: usize, device: Option<String>, gain: f32) -> Output {
        let ctrl = Arc::new(Ctrl {
            stop: AtomicBool::new(false),
            rebuild: AtomicBool::new(false),
            rebuild_mon: AtomicBool::new(false),
            monitor_on: AtomicBool::new(false),
            main_device: Mutex::new(device),
            monitor_device: Mutex::new(None),
            gain: AtomicU32::new(gain.to_bits()),
        });
        let main: ProdSlot = Arc::new(Mutex::new(None));
        let monitor: ProdSlot = Arc::new(Mutex::new(None));
        let sink = Sink { main: main.clone(), monitor: monitor.clone(), ctrl: ctrl.clone() };
        let handle = std::thread::spawn(move || audio_thread(app, main, monitor, ctrl, prefill_frames));
        Output { sink, handle: Some(handle) }
    }

    pub fn sink(&self) -> Sink {
        self.sink.clone()
    }
    pub fn set_gain(&self, g: f32) {
        self.sink.ctrl.gain.store(g.to_bits(), Ordering::Relaxed);
    }
    pub fn set_device(&self, name: Option<String>) {
        *self.sink.ctrl.main_device.lock_safe() = name;
        self.sink.ctrl.rebuild.store(true, Ordering::Relaxed);
    }
    pub fn set_monitor(&self, on: bool, device: Option<String>) {
        *self.sink.ctrl.monitor_device.lock_safe() = device;
        self.sink.ctrl.monitor_on.store(on, Ordering::Relaxed);
    }
}

impl Drop for Output {
    fn drop(&mut self) {
        self.sink.ctrl.stop.store(true, Ordering::Relaxed);
        if let Some(h) = self.handle.take() {
            let _ = h.join();
        }
    }
}

pub fn list_outputs() -> Vec<String> {
    let host = cpal::default_host();
    let mut names = Vec::new();
    if let Ok(devs) = host.output_devices() {
        for d in devs {
            if let Ok(n) = d.name() {
                names.push(n);
            }
        }
    }
    names
}

fn find_device(host: &cpal::Host, name: &Option<String>) -> Option<cpal::Device> {
    match name {
        // Respect the explicit choice: if that device is gone, return None (→ "no output device"
        // warning) instead of silently leaking audio to the default speakers.
        Some(n) => host.output_devices().ok()?.find(|d| d.name().map(|x| &x == n).unwrap_or(false)),
        None => host.default_output_device(),
    }
}

/// Build a cpal output stream, install its producer into `slot`, run a `Player` in the callback.
/// Tries a small fixed buffer first (lower latency) where the backend honours it, then the default.
fn build_stream(
    host: &cpal::Host,
    name: &Option<String>,
    slot: &ProdSlot,
    ctrl: &Arc<Ctrl>,
    is_main: bool,
    prefill: usize,
) -> Option<cpal::Stream> {
    let dev = find_device(host, name)?;
    let supported = dev.default_output_config().ok()?;
    let rate = supported.sample_rate().0;
    let ch = supported.channels() as usize;
    let fmt = supported.sample_format();

    let base: cpal::StreamConfig = supported.config();
    // WASAPI (shared mode) does not accept a fixed buffer size; skip the attempt there rather than
    // building and discarding a ring for a call that always fails.
    if !cfg!(windows) {
        let mut low = base.clone();
        low.buffer_size = cpal::BufferSize::Fixed(256);
        if let Some(s) = try_build(&dev, &low, fmt, ch, rate, slot, ctrl, is_main, prefill) {
            return Some(s);
        }
    }
    try_build(&dev, &base, fmt, ch, rate, slot, ctrl, is_main, prefill)
}

#[allow(clippy::too_many_arguments)]
fn try_build(
    dev: &cpal::Device,
    config: &cpal::StreamConfig,
    fmt: cpal::SampleFormat,
    ch: usize,
    rate: u32,
    slot: &ProdSlot,
    ctrl: &Arc<Ctrl>,
    is_main: bool,
    prefill: usize,
) -> Option<cpal::Stream> {
    // Fresh ring per attempt; the Player (sole consumer) lives inside the callback → lock-free.
    let rb = HeapRb::<f32>::new(RING_CAP);
    let (prod, cons) = rb.split();
    let mut player = Player::new(cons, prefill, rate);

    let ectrl = ctrl.clone();
    let err = move |e| {
        log::warn!("audio stream error: {e}");
        if is_main {
            ectrl.rebuild.store(true, Ordering::Relaxed);
        } else {
            ectrl.rebuild_mon.store(true, Ordering::Relaxed);
        }
    };
    let gain_src = ctrl.clone();

    // Pre-size the i16/u16 conversion scratch so the realtime callback never allocates in steady
    // state (RT-audio hygiene). The in-callback resize stays only as a safety net for an unexpectedly
    // large buffer; with a fixed device buffer it never fires.
    let init_scratch = match config.buffer_size {
        cpal::BufferSize::Fixed(n) => (n as usize) * ch.max(1),
        _ => 8192,
    };

    let stream = match fmt {
        cpal::SampleFormat::F32 => dev
            .build_output_stream(
                config,
                move |data: &mut [f32], _| {
                    let gv = f32::from_bits(gain_src.gain.load(Ordering::Relaxed));
                    player.fill(data, ch, gv);
                },
                err,
                None,
            )
            .ok()?,
        cpal::SampleFormat::I16 => {
            let mut scratch: Vec<f32> = vec![0.0; init_scratch];
            dev.build_output_stream(
                config,
                move |data: &mut [i16], _| {
                    if scratch.len() < data.len() {
                        scratch.resize(data.len(), 0.0);
                    }
                    let buf = &mut scratch[..data.len()];
                    let gv = f32::from_bits(gain_src.gain.load(Ordering::Relaxed));
                    player.fill(buf, ch, gv);
                    for (o, s) in data.iter_mut().zip(buf.iter()) {
                        *o = (*s * 32767.0).round() as i16; // saturating cast; soft_limit keeps |s| ≤ 1
                    }
                },
                err,
                None,
            )
            .ok()?
        }
        cpal::SampleFormat::U16 => {
            let mut scratch: Vec<f32> = vec![0.0; init_scratch];
            dev.build_output_stream(
                config,
                move |data: &mut [u16], _| {
                    if scratch.len() < data.len() {
                        scratch.resize(data.len(), 0.0);
                    }
                    let buf = &mut scratch[..data.len()];
                    let gv = f32::from_bits(gain_src.gain.load(Ordering::Relaxed));
                    player.fill(buf, ch, gv);
                    for (o, s) in data.iter_mut().zip(buf.iter()) {
                        *o = (((*s * 0.5) + 0.5) * 65535.0).round() as u16;
                    }
                },
                err,
                None,
            )
            .ok()?
        }
        _ => return None,
    };

    stream.play().ok()?;
    *slot.lock_safe() = Some(prod); // only expose the producer once the stream runs
    Some(stream)
}

fn audio_thread(app: AppHandle, main: ProdSlot, monitor: ProdSlot, ctrl: Arc<Ctrl>, prefill: usize) {
    let host = cpal::default_host();
    let mut main_stream: Option<cpal::Stream> = None;
    let mut mon_stream: Option<cpal::Stream> = None;
    let mut have_main_dev: Option<String> = Some("<init>".into());
    let mut mon_active = false;
    let mut mon_dev: Option<String> = Some("<init>".into());
    let mut last_ok = true;
    let mut next_main_try = Instant::now();
    let mut next_mon_try = Instant::now();

    while !ctrl.stop.load(Ordering::Relaxed) {
        let want_main = ctrl.main_device.lock_safe().clone();
        let changed = ctrl.rebuild.swap(false, Ordering::Relaxed) || want_main != have_main_dev;
        if changed || (main_stream.is_none() && Instant::now() >= next_main_try) {
            drop(main_stream.take()); // free the old device first
            *main.lock_safe() = None; // and stop feeding a ring nobody drains
            main_stream = build_stream(&host, &want_main, &main, &ctrl, true, prefill);
            have_main_dev = want_main;
            let ok = main_stream.is_some();
            if !ok {
                next_main_try = Instant::now() + REBUILD_BACKOFF;
            }
            if ok != last_ok {
                let _ = app.emit("audio-output", ok); // UI: true = playing, false = no device
                last_ok = ok;
            }
        }

        let want_mon = ctrl.monitor_on.load(Ordering::Relaxed);
        let want_mon_dev = ctrl.monitor_device.lock_safe().clone();
        let mon_errored = ctrl.rebuild_mon.swap(false, Ordering::Relaxed);
        if want_mon
            && (!mon_active
                || mon_errored
                || want_mon_dev != mon_dev
                || (mon_stream.is_none() && Instant::now() >= next_mon_try))
        {
            drop(mon_stream.take());
            *monitor.lock_safe() = None;
            mon_stream = build_stream(&host, &want_mon_dev, &monitor, &ctrl, false, prefill);
            if mon_stream.is_none() {
                next_mon_try = Instant::now() + REBUILD_BACKOFF;
            }
            mon_active = true;
            mon_dev = want_mon_dev;
        } else if !want_mon && mon_active {
            drop(mon_stream.take()); // stops playback
            *monitor.lock_safe() = None;
            mon_active = false;
        }

        std::thread::sleep(Duration::from_millis(120));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn soft_limit_is_transparent_below_the_knee_and_bounded_above() {
        for x in [-0.8f32, -0.3, 0.0, 0.25, 0.8] {
            assert_eq!(soft_limit(x), x);
        }
        let mut prev = soft_limit(0.8);
        for i in 1..200 {
            let x = 0.8 + i as f32 * 0.05;
            let y = soft_limit(x);
            // Non-decreasing and never above full scale (tanh saturates to exactly 1.0 in f32).
            assert!(y >= prev && y <= 1.0, "monotonic and ≤ 1: {x} → {y}");
            assert_eq!(soft_limit(-x), -y);
            prev = y;
        }
        assert!(soft_limit(1.0) > 0.9, "gentle knee: 1.0 → {}", soft_limit(1.0));
    }

    fn player(prefill_frames: usize, rate: u32) -> (HeapProd<f32>, Player) {
        let (prod, cons) = HeapRb::<f32>::new(RING_CAP).split();
        (prod, Player::new(cons, prefill_frames, rate))
    }

    #[test]
    fn player_waits_for_prefill_then_passes_audio_through_at_unity_ratio() {
        let (mut prod, mut p) = player(2, 48000);
        let mut out = vec![1.0f32; 2 * 480]; // stereo, 480 frames
        prod.push_slice(&[0.5; 480]); // one frame < target of two
        p.fill(&mut out, 2, 1.0);
        assert!(out.iter().all(|&v| v == 0.0), "silence while below prefill");
        assert!(!p.started);
        prod.push_slice(&[0.5; 480 * 4]);
        p.fill(&mut out, 2, 1.0);
        assert!(p.started);
        // After the ~5 ms fade-in the constant input comes out unchanged, interleaved to both channels.
        let tail = &out[out.len() - 100..];
        assert!(tail.iter().all(|&v| (v - 0.5).abs() < 1e-4), "tail: {:?}", &tail[..4]);
        // Gain is applied and limited: 0.5 * 3.0 = 1.5 → limited below 1.0.
        p.fill(&mut out, 2, 3.0);
        let v = out[out.len() - 1];
        assert!(v > 0.9 && v < 1.0, "limited: {v}");
    }

    #[test]
    fn player_underrun_restarts_prefill_and_raises_the_target_only_for_jitter() {
        let (mut prod, mut p) = player(1, 48000);
        let mut out = vec![0.0f32; 480];
        prod.push_slice(&[0.2; 600]);
        p.fill(&mut out, 1, 1.0); // starts (600 ≥ 480 target), consumes 480 (+3 lookahead)
        assert!(p.started);
        let target_before = p.target;
        p.fill(&mut out, 1, 1.0); // only ~117 left → underrun
        assert!(!p.started, "underrun → wait for prefill again");
        assert_eq!(p.target, target_before, "the setpoint is judged when data returns, not at the underrun");
        // Data back immediately → network starvation → one bump.
        prod.push_slice(&[0.2; 1000]);
        p.fill(&mut out, 1, 1.0);
        assert_eq!(p.target, target_before + FRAME, "one bump per underrun event");
        assert!(p.target <= p.max);
        // A deliberate pause (mute / silence) longer than PAUSE_GAP does not raise the setpoint.
        while p.started {
            p.fill(&mut out, 1, 1.0); // keep pulling until the ring runs dry again
        }
        p.underrun_at = Some(Instant::now() - PAUSE_GAP - Duration::from_millis(1));
        let t = p.target;
        prod.push_slice(&[0.2; 1000]);
        p.fill(&mut out, 1, 1.0);
        assert_eq!(p.target, t, "a pause is not jitter");
        // A long clean run walks the setpoint back down.
        p.last_underrun = Instant::now() - TARGET_DECAY - Duration::from_secs(1);
        p.last_decay = p.last_underrun;
        prod.push_slice(&[0.2; 480]);
        p.fill(&mut out, 1, 1.0);
        assert_eq!(p.target, t - FRAME, "decays one frame toward the floor");
        assert!(p.target >= p.min);
    }

    #[test]
    fn player_resamples_to_the_device_rate() {
        // 48 kHz source → 96 kHz device: each source sample yields two output samples.
        let (mut prod, mut p) = player(1, 96000);
        prod.push_slice(&[0.3; 480 * 4]);
        let mut out = vec![0.0f32; 960];
        p.fill(&mut out, 1, 1.0);
        let consumed = 480 * 4 - prod.occupied_len();
        assert!((consumed as i64 - (480 + 3)).abs() <= 2, "consumed ≈ half the output: {consumed}");
    }
}
