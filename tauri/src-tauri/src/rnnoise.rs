//! Safe wrapper over the vendored C RNNoise (see `rnnoise/`, BSD-licensed, built by `build.rs`).

use crate::proto::FRAME;

pub(crate) enum DenoiseState {} // opaque

extern "C" {
    fn rnnoise_create() -> *mut DenoiseState;
    fn rnnoise_destroy(st: *mut DenoiseState);
    fn rnnoise_process_frame(st: *mut DenoiseState, out: *mut f32, inp: *const f32) -> f32;
}

/// Processes exactly 480-sample (10 ms) f32 frames in [-32768, 32767].
pub(crate) struct Denoiser {
    st: *mut DenoiseState,
}

impl Denoiser {
    pub(crate) fn new() -> Self {
        let st = unsafe { rnnoise_create() };
        assert!(!st.is_null(), "rnnoise_create returned NULL (out of memory)");
        Denoiser { st }
    }

    /// Denoise one frame. Both slices must be exactly one frame long — the C side reads/writes
    /// that many samples unconditionally, so the wrapper enforces it.
    pub(crate) fn process(&mut self, out: &mut [f32], inp: &[f32]) -> f32 {
        assert_eq!(out.len(), FRAME, "rnnoise output frame size");
        assert_eq!(inp.len(), FRAME, "rnnoise input frame size");
        unsafe { rnnoise_process_frame(self.st, out.as_mut_ptr(), inp.as_ptr()) }
    }
}

impl Drop for Denoiser {
    fn drop(&mut self) {
        unsafe { rnnoise_destroy(self.st) };
    }
}

// The state is owned exclusively by the net thread.
unsafe impl Send for Denoiser {}
