//! Opt-in Linux memory diagnostics; no audio, addresses or device names are recorded.

use serde::Deserialize;

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct UiSample {
    vu_events: u64,
    stats_events: u64,
    layout_events: u64,
    dom_nodes: usize,
    hidden: bool,
    running: bool,
}

#[cfg(target_os = "linux")]
mod linux {
    use super::UiSample;
    use std::{collections::BTreeSet, fs::{self, File, OpenOptions}, io::Write, os::unix::fs::OpenOptionsExt, sync::Mutex, time::{SystemTime, UNIX_EPOCH}};

    const MAX_BYTES: u64 = 2 * 1024 * 1024;
    static LOG: Mutex<Option<File>> = Mutex::new(None);

    fn value<'a>(text: &'a str, key: &str) -> Option<&'a str> {
        text.lines().find_map(|line| line.strip_prefix(key)?.split_whitespace().next())
    }

    fn open_log() -> Result<File, String> {
        let dir = dirs::cache_dir().ok_or("no cache directory")?.join("Micro");
        fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
        let started = SystemTime::now().duration_since(UNIX_EPOCH).map_err(|e| e.to_string())?.as_secs();
        let path = dir.join(format!("memory-{}-{started}.log", std::process::id()));
        let file = OpenOptions::new().write(true).create_new(true).mode(0o600).open(&path).map_err(|e| format!("{}: {e}", path.display()))?;
        eprintln!("Memory profile: {}", path.display());
        Ok(file)
    }

    fn processes() -> BTreeSet<u32> {
        let mut pids = BTreeSet::from([std::process::id()]);
        if let Ok(tasks) = fs::read_dir("/proc/self/task") {
            for task in tasks.flatten() {
                if let Ok(children) = fs::read_to_string(task.path().join("children")) {
                    pids.extend(children.split_whitespace().filter_map(|s| s.parse::<u32>().ok()));
                }
            }
        }
        pids
    }

    pub(super) fn sample(ui: UiSample) -> Result<bool, String> {
        if std::env::var_os("MICRO_MEMORY_PROFILE").as_deref() != Some(std::ffi::OsStr::new("1")) {
            return Ok(false);
        }
        let mut guard = LOG.lock().map_err(|e| e.to_string())?;
        if guard.is_none() {
            *guard = Some(open_log()?);
        }
        let log = guard.as_mut().unwrap();
        if log.metadata().map_err(|e| e.to_string())?.len() >= MAX_BYTES {
            return Err("memory profile reached 2 MiB; restart to begin a new log".into());
        }
        let now = SystemTime::now().duration_since(UNIX_EPOCH).map_err(|e| e.to_string())?.as_secs();
        writeln!(log, "time={now} vu={} stats={} layouts={} nodes={} hidden={} running={}", ui.vu_events, ui.stats_events, ui.layout_events, ui.dom_nodes, ui.hidden, ui.running).map_err(|e| e.to_string())?;
        for pid in processes() {
            let name = fs::read_to_string(format!("/proc/{pid}/comm")).unwrap_or_default();
            let name = name.trim();
            if pid != std::process::id() && !name.starts_with("WebKit") { continue; }
            let memory = fs::read_to_string(format!("/proc/{pid}/smaps_rollup")).unwrap_or_default();
            writeln!(log, "  pid={pid} process={name} rss_kib={} pss_kib={} anonymous_kib={} swap_kib={}",
                value(&memory, "Rss:").unwrap_or("unavailable"), value(&memory, "Pss:").unwrap_or("unavailable"),
                value(&memory, "Anonymous:").unwrap_or("unavailable"), value(&memory, "Swap:").unwrap_or("unavailable")).map_err(|e| e.to_string())?;
        }
        Ok(true)
    }

    #[cfg(test)]
    mod tests {
        use super::value;

        #[test]
        fn parses_kernel_memory_fields_without_confusing_pss_and_rss() {
            let data = "Rss:  2048 kB\nPss:  1024 kB\nPss_Dirty: 512 kB\nSwap: 0 kB\n";
            assert_eq!(value(data, "Pss:"), Some("1024"));
            assert_eq!(value(data, "Rss:"), Some("2048"));
            assert_eq!(value(data, "Anonymous:"), None);
        }
    }
}

#[tauri::command]
pub(crate) fn memory_profile(sample: UiSample) -> Result<bool, String> {
    #[cfg(target_os = "linux")]
    { linux::sample(sample) }
    #[cfg(not(target_os = "linux"))]
    { let _ = sample; Ok(false) }
}
