fn main() {
    // Vendored C RNNoise (Xiph/Mozilla BSD) — faster than the pure-Rust port.
    let mut b = cc::Build::new();
    b.include("rnnoise")
        .files([
            "rnnoise/denoise.c",
            "rnnoise/celt_lpc.c",
            "rnnoise/pitch.c",
            "rnnoise/rnn.c",
            "rnnoise/rnn_data.c",
        ])
        .opt_level(3)
        .warnings(false);
    // Match the Rust side's microarchitecture level (.cargo/config.toml sets x86-64-v2 for rustc,
    // but rustflags never reach the C compiler). GCC/Clang only — MSVC has no equivalent switch
    // below AVX, and SSE2 is its x64 baseline anyway.
    let target = std::env::var("TARGET").unwrap_or_default();
    if target.starts_with("x86_64") && !target.contains("msvc") {
        b.flag_if_supported("-march=x86-64-v2");
    }
    // RNNoise is forked from Opus/CELT and shares function names with libopus (audiopus),
    // which collide at link time. Rename the shared ones in *our* RNNoise build only.
    for sym in [
        "_celt_lpc",
        "_celt_autocorr",
        "celt_iir",
        "celt_fir",
        "pitch_downsample",
        "pitch_search",
        "remove_doubling",
        "celt_pitch_xcorr",
    ] {
        b.define(sym, format!("rnn_{}", sym.trim_start_matches('_')).as_str());
    }
    b.compile("rnnoise");
    println!("cargo:rerun-if-changed=rnnoise");

    tauri_build::build();
}
