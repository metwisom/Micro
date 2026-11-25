import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { getCurrentWindow, availableMonitors, currentMonitor, PhysicalSize, PhysicalPosition } from "@tauri-apps/api/window";
import { enable as autoEnable, disable as autoDisable, isEnabled as autoIsEnabled } from "@tauri-apps/plugin-autostart";
import { isPermissionGranted, requestPermission, sendNotification } from "@tauri-apps/plugin-notification";
import { register as registerShortcut, unregister as unregisterShortcut } from "@tauri-apps/plugin-global-shortcut";
import {
  deriveStatus, isOnScreen, latencyEstimateMs, nextReconnectDelay, reduceAuth, sanitizePhoneName,
  shouldFollowIp,
} from "./state.js";

const DEFAULT_PORT = 50005;
const DEFAULT_HOTKEY = "CmdOrControl+Shift+M";

// --- Localization ------------------------------------------------------------
let lang = "en";
const TR = {
  en: {
    source_label: "Phone source", connect: "Connect", connect_ip: "Connect by IP…", host_label: "Phone IP address",
    disconnect: "Disconnect", searching: "Searching for microphone…", settings: "Settings",
    language: "Language", output: "Audio output (device)", refresh: "Refresh list",
    denoise: "Noise suppression (RNNoise)", volume: "Volume", volume_hint: "(signal boost)",
    vad: "VAD gate", vad_hint: "(0 = off, 0.6–0.9 = hard)",
    buffer: "Buffer (10 ms frames, lower = less ping)", autostart: "Launch at login",
    hotkey: "Mute hotkey", hotkey_ok: "Active", hotkey_off: "No hotkey", hotkey_err: "Could not register this shortcut — it may be taken by another app",
    buffer_live: "Applies to the next connection — disconnect and connect again to change it",
    footer: "Open Micro on the phone and tap Start — it appears as a button above. Output auto-selects VB-Cable (CABLE Input).",
    st_connecting: "Connecting…", st_streaming: "Streaming", st_waiting_phone: "Waiting for phone…",
    st_reconnecting: "Reconnecting…", st_lost: "Connection lost — waiting for the phone…",
    st_enter_code: "Enter the code shown on the phone",
    st_waiting_approve: "Waiting for approval on the phone…", st_denied: "Device rejected by the phone",
    st_busy: "The phone is busy with another computer", st_muted: "Muted",
    ping_rtt: "network round-trip", latency_est: "estimated mic→virtual-mic latency",
    err_no_output: "No output device — pick one in settings",
    err_no_ip: "Enter the phone IP", err_prefix: "Error: ",
    reset: "Reset app", tray_show: "Show", tray_quit: "Quit",
    mute: "Mute", unmute: "Unmute", notif_connected: "Connected", notif_disconnected: "Disconnected",
    enter_code: "Enter the code shown on the phone", ok: "OK", bad_code: "Wrong code — try the new one on the phone",
    device_fp: "Device fingerprint",
    test: "Monitor in headphones",
    reset_confirm: "Reset the app? This deletes settings and this device's identity, as if launched for the first time.",
  },
  ru: {
    source_label: "Источник — телефон", connect: "Подключить", connect_ip: "Подключение по IP…", host_label: "IP-адрес телефона",
    disconnect: "Отключить", searching: "Поиск микрофона…", settings: "Настройки",
    language: "Язык", output: "Вывод звука (устройство)", refresh: "Обновить список",
    denoise: "Шумоподавление (RNNoise)", volume: "Громкость", volume_hint: "(усиление сигнала)",
    vad: "VAD-гейт", vad_hint: "(0 = выкл, 0.6–0.9 = сильный гейт)",
    buffer: "Буфер (кадров по 10 мс, меньше = задержка ниже)", autostart: "Запуск при входе",
    hotkey: "Горячая клавиша mute", hotkey_ok: "Работает", hotkey_off: "Без горячей клавиши", hotkey_err: "Не удалось зарегистрировать сочетание — возможно, его занимает другое приложение",
    buffer_live: "Применяется при следующем подключении — отключитесь и подключитесь снова",
    footer: "Откройте Micro на телефоне и нажмите Start — он появится кнопкой выше. Вывод автоматически ставится на VB-Cable (CABLE Input).",
    st_connecting: "Подключение…", st_streaming: "Поток идёт", st_waiting_phone: "Ожидание телефона…",
    st_reconnecting: "Переподключение…", st_lost: "Связь потеряна — ожидание телефона…",
    st_enter_code: "Введите код, показанный на телефоне",
    st_waiting_approve: "Ожидание одобрения на телефоне…", st_denied: "Телефон отклонил это устройство",
    st_busy: "Телефон занят другим компьютером", st_muted: "Звук отключён",
    ping_rtt: "сетевой пинг (туда-обратно)", latency_est: "оценка задержки мик→вирт-мик",
    err_no_output: "Нет устройства вывода — выберите в настройках",
    err_no_ip: "Введите IP телефона", err_prefix: "Ошибка: ",
    reset: "Сбросить приложение", tray_show: "Показать", tray_quit: "Выход",
    mute: "Заглушить", unmute: "Включить звук", notif_connected: "Подключено", notif_disconnected: "Отключено",
    enter_code: "Введите код, показанный на телефоне", ok: "OK", bad_code: "Неверный код — введите новый с телефона",
    device_fp: "Отпечаток устройства",
    test: "Прослушать в наушниках",
    reset_confirm: "Сбросить приложение? Удалит настройки и идентификатор этого устройства — как при первом запуске.",
  },
};
const t = (k) => (TR[lang] && TR[lang][k]) || TR.en[k] || k;
function detectLang() {
  for (const l of navigator.languages || [navigator.language || "en"]) {
    const c = String(l).toLowerCase().split("-")[0];
    if (TR[c]) return c;
  }
  return "en";
}
function loadLang() {
  const v = store.get("lang");
  return TR[v] ? v : detectLang();
}
function applyI18n() {
  document.documentElement.lang = lang;
  document.querySelectorAll("[data-i18n]").forEach((el) => (el.textContent = t(el.dataset.i18n)));
  document.querySelectorAll("[data-i18n-title]").forEach((el) => (el.title = t(el.dataset.i18nTitle)));
  document.querySelectorAll("[data-i18n-aria]").forEach((el) => el.setAttribute("aria-label", t(el.dataset.i18nAria)));
  renderSources(true);
  syncTray();
  // Two strings are produced in JS rather than from a [data-i18n] node, so a language switch would
  // otherwise leave them in the previous language: the hotkey hint and the connected status line.
  // Only the *text* is re-rendered — calling applyHotkey here would re-enter registration, and at
  // startup applyI18n runs before the saved shortcut has been restored.
  renderHotkeyMsg();
  if (running) updateStatus();
}

const $ = (id) => document.getElementById(id);

// localStorage can throw (blocked site data, private profiles) — never let a preference kill init.
const store = {
  get(k) { try { return localStorage.getItem(k); } catch (_) { return null; } },
  set(k, v) { try { localStorage.setItem(k, v); } catch (_) {} },
  remove(k) { try { localStorage.removeItem(k); } catch (_) {} },
  clear() { try { localStorage.clear(); } catch (_) {} },
};

// --- Inline icons (constant markup, currentColor) ------------------------------
const svg = (p) => `<svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor" aria-hidden="true">${p}</svg>`;
const ICON_MIC = svg('<path d="M12 14a3 3 0 0 0 3-3V5a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3zm5-3a5 5 0 0 1-10 0H5a7 7 0 0 0 6 6.92V21h2v-3.08A7 7 0 0 0 19 11h-2z"/>');
const ICON_MIC_OFF = svg('<path d="M19 11h-1.7a5.4 5.4 0 0 1-.44 2.05l1.24 1.23A6.9 6.9 0 0 0 19 11zM15 11.16V5a3 3 0 0 0-6 .18l6 5.98zM4.27 3 3 4.27l6 6V11a3 3 0 0 0 3 3 2.9 2.9 0 0 0 .66-.08l1.66 1.66A5.5 5.5 0 0 1 12 16a5 5 0 0 1-5-5H5a7 7 0 0 0 6 6.92V21h2v-3.28a6.8 6.8 0 0 0 2.55-.9L19.73 21 21 19.73 4.27 3z"/>');
const ICON_HEADPHONES = svg('<path d="M12 3a9 9 0 0 0-9 9v7a2 2 0 0 0 2 2h2a1 1 0 0 0 1-1v-6a1 1 0 0 0-1-1H5v-1a7 7 0 0 1 14 0v1h-2a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2a2 2 0 0 0 2-2v-7a9 9 0 0 0-9-9z"/>');
const ICON_POWER = svg('<path d="M13 3h-2v10h2V3zm4.83 2.17-1.42 1.42a7 7 0 1 1-8.83 0L6.17 5.17a9 9 0 1 0 11.66 0z"/>');

// --- Accent (system) ---------------------------------------------------------
function setAccent(hex) {
  const c = (hex || "").replace(/[^0-9a-fA-F]/g, "").slice(0, 6);
  if (c.length !== 6) return;
  const r = parseInt(c.slice(0, 2), 16), g = parseInt(c.slice(2, 4), 16), b = parseInt(c.slice(4, 6), 16);
  // WCAG relative luminance: linearise each channel out of sRGB first. The YIQ luma this used to
  // compute (0.299/0.587/0.114 on gamma-encoded bytes) is a different quantity, and treating it as
  // luminance left saturated greens and cyans well under 3:1 while the code believed they passed.
  const lin = (v) => { const x = v / 255; return x <= 0.04045 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); };
  const lum = 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
  const shade = (p) => {
    const f = (v) => Math.max(0, Math.min(255, Math.round(p < 0 ? v * (1 + p) : v + (255 - v) * p)));
    return `rgb(${f(r)}, ${f(g)}, ${f(b)})`;
  };
  // The accent is also used as *text* on the page (see `--accent-on-bg` in styles.css), and the
  // system accent is arbitrary, so push it to a luminance that clears 4.5:1 against the page ground
  // rather than trusting it. Dark ground is #202020 (L 0.0144) -> the text needs L >= 0.34; light
  // ground is #f3f3f3 (L 0.8879) -> the text needs L <= 0.20.
  const darkUi = !window.matchMedia || window.matchMedia("(prefers-color-scheme: dark)").matches;
  const FLOOR = 0.34, CEIL = 0.20; // relative-luminance targets for dark / light ground
  const onBg = darkUi
    ? (lum < FLOOR ? shade((FLOOR - lum) / (1 - lum)) : "#" + c)
    : (lum > CEIL ? shade(-(lum - CEIL) / lum) : "#" + c);
  const s = document.documentElement.style;
  s.setProperty("--accent", "#" + c);
  s.setProperty("--accent-on-bg", onBg);
  // Text ON the accent: pick whichever of black/white actually contrasts better with it. The old
  // fixed 0.6 cut on YIQ luma chose white for mid accents, leaving the primary button near 2:1.
  const onWhite = 1.05 / (lum + 0.05);
  const onBlack = (lum + 0.05) / 0.05;
  s.setProperty("--accent-fg", onBlack >= onWhite ? "#000000" : "#ffffff");
  s.setProperty("--accent-press", shade(-0.15));
  s.setProperty("--accent-hover", shade(lum > 0.4 ? -0.1 : 0.14));
}

// --- State -------------------------------------------------------------------
let running = false, connecting = false;
let target = null;          // { host, port } we are connected to
let connectedId = null;     // phone id (discovery) of the current target, if known
let statusTimer = null, sweepTimer = null, lastPcm = 0;
let authState = null;       // "code" | "bad" | "pending" | "denied" | "busy" | null
let session = "none";       // "none" | "up" | "lost"  (from the backend "session" event)
let statusText = "", statusCls = "", statusTitle = "", statusStats = "";
let lastStats = null;
let muted = false, wasStreaming = false, triedAuto = false;
let monitoring = false, audioOutOk = true;
let reconnectTimer = null, reconnectDelay = 1000; // backoff for auto-reconnect after a setup error
let pendingNetError = false; // a net-error that arrived while connect() was still resolving
let connectP = null;         // in-flight connect() invoke, awaited by stop() so the two never interleave
let trayState = "";
let notifyOk = false;
let hotkey = "";            // currently registered global shortcut ("" = none)
let sourcesSig = "";        // last rendered source-list signature (skip identical re-renders)
const profileCounts = { vuEvents: 0, statsEvents: 0, layoutEvents: 0 };
const phones = new Map();   // id(phone) || ip -> { ip, port, name, id, seen }
const ipSeen = new Map();   // ip -> last discovery reply from that address

function setTrayState(s) {
  if (s === trayState) return;
  trayState = s;
  invoke("set_tray_icon", { state: s }).catch(() => {});
}
async function notify(body) {
  if (!notifyOk) return;
  try { sendNotification({ title: "Micro", body }); } catch (_) {}
}
function toggleMute() {
  if (!running) return;
  applyMute(!muted);
  invoke("set_mute", { on: muted }).catch(() => {}); // backend enforces it locally + tells the phone
}

// Apply mute locally (used by the button and by remote sync from the server).
// Updates the mute button in place — no full re-render, so the device row doesn't flicker.
function applyMute(on) {
  muted = on;
  if (!muted) lastPcm = Date.now(); // grace window so unmute doesn't flash "waiting" before audio resumes
  setTrayState(muted ? "muted" : session === "up" ? "active" : "idle");
  const b = $("muteBtn");
  if (b) {
    b.className = "mute-btn icon-btn" + (muted ? " on" : "");
    b.innerHTML = muted ? ICON_MIC_OFF : ICON_MIC;
    b.title = muted ? t("unmute") : t("mute");
    b.setAttribute("aria-label", b.title);
    b.setAttribute("aria-pressed", muted ? "true" : "false");
  } else {
    renderSources(true);
  }
}

// Monitor: also play the same audio to the system default output (headphones) via a second
// native cpal stream — hear exactly what goes into the virtual cable.
function toggleMonitor() {
  if (!running) return;
  monitoring = !monitoring;
  invoke("set_monitor", { on: monitoring, device: null }).catch(() => {}); // null = system default device
  renderSources(true);
}
function readGain() {
  const v = parseFloat($("gain").value);
  return isFinite(v) ? Math.max(0, Math.min(4, v)) : 1.5; // ceiling matches the slider (max=4)
}
function readBuffer() {
  // Mirror `max="40"` from index.html. The HTML attribute only styles a typed value as invalid; it
  // does not clamp it, and an out-of-range prefill reaches the audio engine as permanent silence.
  const v = Math.max(1, Math.min(40, parseInt($("buffer").value, 10) || 3));
  if ($("buffer").value !== String(v)) $("buffer").value = String(v); // show what was applied
  return v;
}

// Re-establish the session after the net thread failed to start (bind/socket/opus-init failure —
// the only way it dies), with exponential backoff that resets once audio flows again. Cancelled by
// stop(). A phone that vanishes mid-session is handled inside the backend (HELO keeps going).
function scheduleReconnect() {
  if (!running || !target) return;
  clearTimeout(reconnectTimer);
  setTrayState(muted ? "muted" : "idle");
  reconnectTimer = setTimeout(doReconnect, reconnectDelay);
  reconnectDelay = nextReconnectDelay(reconnectDelay); // pure, and unit-tested in state.js
  updateStatus();
}
async function doReconnect() {
  reconnectTimer = null;
  if (!running || !target) return;
  // A fresh backend Output/session: mirror that in the UI state BEFORE the invoke (as start() does),
  // so nothing the new Output emits early is overwritten.
  session = "none";
  lastPcm = 0;
  audioOutOk = true;
  muted = false;
  try {
    const device = $("device").value || null;
    connectP = invoke("connect", { host: target.host, port: target.port, device, buffer: readBuffer(), gain: readGain() });
    await connectP;
    connectP = null;
    if (!running) { invoke("disconnect").catch(() => {}); return; } // Stop was clicked meanwhile
    if (monitoring) invoke("set_monitor", { on: true, device: null }).catch(() => {});
    renderSources(true);
  } catch (_) {
    connectP = null;
    scheduleReconnect(); // spawning the net thread failed again → keep retrying with backoff
  }
}

// The prefill is chosen when the session opens, so editing it mid-call silently does nothing.
// Rather than let the user drag it and hear no change, disable it and say why in visible text —
// a title on a disabled input is not reliably shown, and never at all to a keyboard user.
function syncBufferEnabled() {
  const el = $("buffer");
  const hint = $("bufferHint");
  if (!el) return;
  el.disabled = running || connecting;
  if (hint) {
    hint.textContent = el.disabled ? t("buffer_live") : "";
    hint.hidden = !el.disabled;
  }
}

// VAD gate is derived from RNNoise — meaningless when denoise is off.
function syncVadEnabled() {
  const on = $("denoise").checked;
  $("vad").disabled = !on;
  $("vadRow").style.opacity = on ? "" : "0.45";
}

function setStatus(text, cls, title, stats) {
  statusText = text;
  statusCls = cls || "";
  statusTitle = title || "";
  // Rendered next to the status but *outside* the live region: kbps/loss/ping change every second,
  // and announcing them on a polite live region means a screen reader reads the whole line again
  // once per second, forever, drowning out everything else the user is doing.
  statusStats = stats || "";
  updateActiveStatus();
}
// The status lives in the active device row (a live region) while connected; otherwise (errors
// before a session, e.g. an empty IP) it is shown on its own live-region line under the list.
function updateActiveStatus() {
  const el = document.querySelector(".source-btn.active .btn-status");
  const line = $("status");
  const stats = document.querySelector(".source-btn.active .btn-stats");
  if (stats) {
    stats.textContent = statusStats;
    stats.title = statusTitle;
  }
  if (el) {
    el.textContent = statusText;
    el.className = "btn-status" + (statusCls ? " " + statusCls : "");
    el.title = statusTitle;
    line.textContent = "";
    line.className = "status-line";
  } else {
    line.textContent = running ? "" : statusText;
    line.className = "status-line" + (statusCls ? " " + statusCls : "");
  }
}

// --- Sources (with TTL) ------------------------------------------------------
function addFound(ip, port, name, id) {
  // Key by the phone's identity so Wi-Fi + USB-tether IPs collapse to one entry (latest IP wins).
  const key = id || ip;
  const ex = phones.get(key);
  // While connected to this phone keep the IP actually in use (don't flip to the other interface).
  const useIp = running && target && ex && ex.ip === target.host ? target.host : ip;
  // The name comes from an unauthenticated discovery reply, so it is attacker-controlled; the
  // sanitizer lives in state.js, where it is unit-tested.
  const safeName = sanitizePhoneName(name);
  phones.set(key, { ip: useIp, port, name: safeName, id, seen: Date.now() });
  ipSeen.set(ip, Date.now());
  renderSources();
}
function phoneName(ip) {
  for (const p of phones.values()) if (p.ip === ip) return p.name;
  return ip;
}
function sweepPhones() {
  const now = Date.now();
  let changed = false;
  for (const [key, info] of phones) {
    if (now - info.seen > 15000 && !(running && target && target.host === info.ip)) {
      phones.delete(key);
      changed = true;
    }
  }
  for (const [ip, seen] of ipSeen) if (now - seen > 60000) ipSeen.delete(ip);
  if (changed) renderSources();
}

// Rebuilds the list only when something that is rendered actually changed (the discovery reply
// arrives every ~5 s per phone; the status text is updated in place, not part of the signature).
function renderSources(force) {
  const box = $("sources");
  if (!box) return;

  const entries = new Map(phones);
  if (running && target && ![...entries.values()].some((p) => p.ip === target.host)) {
    entries.set(target.host, { ip: target.host, port: target.port, name: target.host });
  }
  // `muted` is deliberately not part of the signature: applyMute patches the button in place.
  const sig = JSON.stringify([
    [...entries.values()].map((p) => [p.ip, p.port, p.name]),
    running, connecting, target && target.host, monitoring, lang,
  ]);
  if (!force && sig === sourcesSig) return;
  sourcesSig = sig;

  // Rebuilding replaces every node, which sends focus back to <body> — a keyboard or screen-reader
  // user tabbing through the list loses their place on every state change. Remember where focus was
  // and put it back on the equivalent node afterwards.
  const focusedId = document.activeElement && box.contains(document.activeElement)
    ? document.activeElement.id || document.activeElement.dataset.focusKey || ""
    : "";

  box.innerHTML = "";
  if (entries.size === 0 && !running) {
    const ph = document.createElement("div");
    ph.className = "searching";
    ph.textContent = t("searching");
    box.appendChild(ph);
  }

  for (const [, info] of entries) {
    const ip = info.ip;
    const active = running && target && target.host === ip;
    if (active) {
      const row = document.createElement("div");
      row.className = "source-btn active";
      const col = document.createElement("div");
      col.className = "src-info";
      const n = document.createElement("b"); n.textContent = info.name;
      const sub = document.createElement("div"); sub.className = "src-sub";
      const i = document.createElement("span"); i.textContent = ip;
      const st = document.createElement("span");
      st.className = "btn-status" + (statusCls ? " " + statusCls : "");
      st.setAttribute("role", "status");
      st.setAttribute("aria-live", "polite");
      st.textContent = statusText;
      st.title = statusTitle;
      // Telemetry lives outside the live region; see setStatus.
      // Outside the live region, but NOT aria-hidden: a screen-reader user must still be able to
      // read the connection quality on demand — they just should not have it announced at them
      // once a second.
      const stx = document.createElement("span");
      stx.className = "btn-stats";
      stx.textContent = statusStats;
      stx.title = statusTitle;
      sub.append(i, st, stx);
      col.append(n, sub);
      const muteBtn = document.createElement("button");
      muteBtn.id = "muteBtn";
      muteBtn.className = "mute-btn icon-btn" + (muted ? " on" : "");
      muteBtn.innerHTML = muted ? ICON_MIC_OFF : ICON_MIC;
      muteBtn.title = muted ? t("unmute") : t("mute");
      muteBtn.setAttribute("aria-label", muteBtn.title);
      muteBtn.setAttribute("aria-pressed", muted ? "true" : "false");
      muteBtn.addEventListener("click", toggleMute);
      const testBtn = document.createElement("button");
      testBtn.className = "mute-btn icon-btn test-btn" + (monitoring ? " on" : "");
      testBtn.innerHTML = ICON_HEADPHONES;
      testBtn.title = t("test");
      testBtn.setAttribute("aria-label", testBtn.title);
      testBtn.setAttribute("aria-pressed", monitoring ? "true" : "false");
      testBtn.addEventListener("click", toggleMonitor);
      const stopBtn = document.createElement("button");
      stopBtn.className = "stop-btn icon-btn";
      stopBtn.innerHTML = ICON_POWER;
      stopBtn.title = t("disconnect");
      stopBtn.setAttribute("aria-label", stopBtn.title);
      stopBtn.addEventListener("click", stop);
      muteBtn.dataset.focusKey = "mute";
      testBtn.dataset.focusKey = "monitor";
      stopBtn.dataset.focusKey = "stop";
      const btns = document.createElement("div");
      btns.className = "active-btns";
      btns.append(muteBtn, testBtn, stopBtn);
      row.append(col, btns);
      box.appendChild(row);
    } else {
      const b = document.createElement("button");
      b.className = "source-btn";
      b.dataset.focusKey = "phone:" + (info.id || ip);
      b.disabled = running || connecting;
      const n = document.createElement("b"); n.textContent = info.name;
      const i = document.createElement("span"); i.textContent = ip;
      b.append(n, i);
      b.addEventListener("click", () => connectTo(ip, info.port || DEFAULT_PORT, info.id));
      box.appendChild(b);
    }
  }

  if (running) {
    // Already connected — no point offering "connect by IP".
    $("manual").classList.add("hidden");
  } else {
    const manualBtn = document.createElement("button");
    manualBtn.className = "source-btn manual-btn";
    manualBtn.dataset.focusKey = "manual";
    manualBtn.textContent = t("connect_ip");
    manualBtn.disabled = connecting;
    manualBtn.addEventListener("click", () => {
      const m = $("manual");
      m.classList.toggle("hidden");
      if (!m.classList.contains("hidden")) $("host").focus();
    });
    box.appendChild(manualBtn);
  }
  if (focusedId) {
    const again = box.querySelector(`#${CSS.escape(focusedId)}`) ||
      box.querySelector(`[data-focus-key="${CSS.escape(focusedId)}"]`);
    if (again && !again.disabled) again.focus();
  }
  syncBufferEnabled();
  updateActiveStatus();
}

function connectTo(host, port, id) {
  if (running || connecting) return;
  start(host, port || DEFAULT_PORT, id || null);
}

// --- Output device -----------------------------------------------------------
async function listOutputs() {
  // Native device list from Rust (cpal). Device id == device name.
  let names = [];
  try { names = await invoke("list_outputs"); } catch (_) {}
  const sel = $("device");
  const prev = sel.value || store.get("outputDeviceId");
  sel.innerHTML = "";
  for (const name of names) {
    const o = document.createElement("option");
    o.value = name;
    o.textContent = name;
    sel.appendChild(o);
  }
  if (prev && names.includes(prev)) {
    sel.value = prev;
  } else {
    const vb =
      names.find((n) => /cable input/i.test(n)) ||
      names.find((n) => /vb[- ]?audio/i.test(n)) ||
      names.find((n) => n.trim() === "Micro"); // Linux virtual sink
    if (vb) sel.value = vb;
  }
}

// --- Start / stop ------------------------------------------------------------
function hideCodePrompt() {
  $("codePrompt").classList.add("hidden");
  $("fpRow").classList.add("hidden");
  $("code").value = "";
}

async function start(host, port, id) {
  if (running || connecting) return;
  host = (host || $("host").value || "").trim();
  if (!host) { setStatus(t("err_no_ip"), "err"); return; }
  port = port || DEFAULT_PORT;
  connecting = true;
  renderSources(true);

  try {
    await invoke("set_denoise", { on: $("denoise").checked });
    await invoke("set_vad", { gate: parseFloat($("vad").value) || 0 });
    await invoke("set_code", { code: "" }); // cleared; set only if the phone asks to pair
    await invoke("set_mute", { on: false }); // UI and backend start every connection unmuted
    hideCodePrompt();

    // Session state is reset BEFORE the backend starts so no early event is dropped.
    target = { host, port };
    connectedId = id || null;
    authState = null;
    session = "none";
    lastPcm = 0;
    lastStats = null;
    muted = false; // the backend re-syncs from the phone if it is muted there
    monitoring = false;
    audioOutOk = true;
    wasStreaming = false;
    pendingNetError = false;

    // Native cpal output (device by name, prefill in 10 ms frames, gain).
    const device = $("device").value || null;
    connectP = invoke("connect", { host, port, device, buffer: readBuffer(), gain: readGain() });
    await connectP;
    connectP = null;

    running = true;
    clearTimeout(reconnectTimer); reconnectTimer = null;
    reconnectDelay = 1000;
    store.set("lastHost", host);
    $("manual").classList.add("hidden");
    $("vu").classList.remove("hidden");
    setStatus(t("st_connecting"));
    setTrayState("idle");
    renderSources(true);
    syncTray();
    statusTimer = setInterval(updateStatus, 500);
    if (pendingNetError) { pendingNetError = false; scheduleReconnect(); }
  } catch (e) {
    console.error(e);
    connectP = null;
    try { await invoke("disconnect"); } catch (_) {}
    running = false;
    target = null;
    connectedId = null;
    setStatus(t("err_prefix") + (e && e.message ? e.message : e), "err");
  } finally {
    connecting = false;
    renderSources(true);
  }
}

async function stop() {
  if (!running) return;
  running = false;
  monitoring = false;
  // A manual disconnect is final for this launch: the phone is still on the network, so auto-connect
  // must not undo the click a moment later. It resumes on the next app start.
  triedAuto = true;
  clearTimeout(reconnectTimer); reconnectTimer = null;
  reconnectDelay = 1000;
  pendingNetError = false;
  clearInterval(statusTimer); statusTimer = null;
  if (connectP) { try { await connectP; } catch (_) {} } // never interleave with an in-flight connect
  try { await invoke("disconnect"); } catch (_) {} // also stops the cpal streams
  target = null;
  connectedId = null;
  lastStats = null;
  muted = false;
  invoke("set_mute", { on: false }).catch(() => {});
  session = "none";
  authState = null;
  $("vu").classList.add("hidden");
  $("vubar").style.width = "0%";
  hideCodePrompt();
  invoke("set_code", { code: "" }).catch(() => {});
  if (wasStreaming) notify(t("notif_disconnected"));
  wasStreaming = false;
  setStatus("");
  setTrayState("idle");
  renderSources(true);
  syncTray();
}

function updateStatus() {
  if (!running) return;
  const now = Date.now();
  const d = deriveStatus({ audioOutOk, reconnecting: !!reconnectTimer, authState, muted, session, lastPcm, now });
  let text = t(d.key), title = "", stats = "";
  if (d.streaming) {
    reconnectDelay = 1000; // audio is flowing → reset the reconnect backoff
    if (lastStats) {
      const e2e = latencyEstimateMs(lastStats.ping, readBuffer());
      // No leading separator: this renders as its own line under the status, not inline with it.
      stats = `${lastStats.kbps} kbps · ${lastStats.loss}% · ${lastStats.ping} ms ⇄ · ~${e2e} ms`;
      title = `${lastStats.ping} ms ${t("ping_rtt")} · ~${e2e} ms ${t("latency_est")}`;
    }
    if (!wasStreaming) {
      wasStreaming = true;
      notify(t("notif_connected") + " — " + phoneName(target.host));
    }
  }
  setStatus(text, d.cls, title, stats);
  setTrayState(d.tray);
}

// --- Tray (localized + dynamic) ----------------------------------------------
function syncTray() {
  const connectedName = running && target ? phoneName(target.host) : null;
  invoke("update_tray", {
    show: t("tray_show"),
    quit: t("tray_quit"),
    disconnect: running ? t("disconnect") : null,
    status: connectedName,
  }).catch(() => {});
}

// --- Global mute hotkey ------------------------------------------------------
// An empty value means "no hotkey" and is persisted as such; a failed registration keeps the last
// working shortcut (field and store stay consistent with what is actually registered).
/** Re-render the hotkey hint from the *registered* shortcut. Text only — never re-registers. */
function renderHotkeyMsg() {
  const msg = $("hotkeyMsg");
  if (!msg) return;
  msg.textContent = hotkey ? t("hotkey_ok") : t("hotkey_off");
  msg.className = "hint" + (hotkey ? " ok" : "");
}

async function applyHotkey(value) {
  const want = (value || "").trim();
  if (want === hotkey) {
    renderHotkeyMsg();
    return;
  }
  const msg = $("hotkeyMsg");
  if (!want) {
    if (hotkey) { try { await unregisterShortcut(hotkey); } catch (_) {} }
    hotkey = "";
    store.set("hotkey", "");
    msg.textContent = t("hotkey_off");
    msg.className = "hint";
    return;
  }
  try {
    await registerShortcut(want, (e) => { if (!e || e.state === "Pressed") toggleMute(); });
    if (hotkey) { try { await unregisterShortcut(hotkey); } catch (_) {} }
    hotkey = want;
    store.set("hotkey", want);
    msg.textContent = t("hotkey_ok");
    msg.className = "hint ok";
  } catch (e) {
    console.warn("shortcut", e);
    $("hotkey").value = hotkey;
    msg.textContent = t("hotkey_err");
    msg.className = "hint err";
  }
}

// --- Window geometry ---------------------------------------------------------
// Physical pixels, so restoring never depends on the DPI of whichever monitor is current.
const WIN_POS_KEY = "winPosPhys";
const MIN_WINDOW_HEIGHT = 200;

// Keep the outer window within the current monitor's work area, including its title bar.
async function windowBounds() {
  const win = getCurrentWindow();
  const monitor = await currentMonitor();
  if (!monitor) return null;
  const [inner, outer] = await Promise.all([win.innerSize(), win.outerSize()]);
  const frame = Math.max(0, outer.height - inner.height);
  const maxHeight = Math.max(1, monitor.workArea.size.height - frame);
  await win.setMaxSize(new PhysicalSize(Math.round(500 * monitor.scaleFactor), maxHeight));
  return { monitor, frame, maxHeight };
}

// Content changes resize the window; manual resizing remains possible between content changes.
async function syncHeight() {
  const measured = Math.ceil(document.querySelector(".app")?.offsetHeight || 0);
  if (!measured) return;
  try {
    const win = getCurrentWindow();
    const bounds = await windowBounds();
    if (!bounds) return;
    const { monitor, frame, maxHeight } = bounds;
    const height = Math.min(maxHeight, Math.max(MIN_WINDOW_HEIGHT * monitor.scaleFactor, measured * monitor.scaleFactor));
    const pos = await win.outerPosition();
    const top = monitor.workArea.position.y;
    const bottom = top + monitor.workArea.size.height;
    const y = Math.max(top, Math.min(pos.y, bottom - height - frame));
    if (y !== pos.y) await win.setPosition(new PhysicalPosition(pos.x, Math.round(y)));
    await win.setSize(new PhysicalSize(Math.round(500 * monitor.scaleFactor), Math.round(height)));
  } catch (e) {
    console.warn("window geometry", e);
  }
}

async function restorePosition() {
  const raw = store.get(WIN_POS_KEY);
  if (!raw) return;
  try {
    const p = JSON.parse(raw);
    // Only restore onto a monitor that exists right now. A stale position (undocked screen, or the
    // (-32000,-32000) Windows parks minimized windows at) would put the window out of reach.
    const monitors = await availableMonitors();
    if (!isOnScreen(p, monitors)) {
      console.warn("stored window position is off-screen — letting the OS place the window", p);
      store.remove(WIN_POS_KEY);
      return;
    }
    await getCurrentWindow().setPosition(new PhysicalPosition(p.x, p.y));
  } catch (e) {
    console.warn("setPosition", e);
    store.remove(WIN_POS_KEY);
  }
}

// --- Wire up -----------------------------------------------------------------
window.addEventListener("DOMContentLoaded", async () => {
  lang = loadLang();
  $("lang").value = lang;
  applyI18n();
  $("lang").addEventListener("change", () => { lang = $("lang").value; store.set("lang", lang); applyI18n(); });

  // Restore saved settings (output device is restored in listOutputs).
  const sd = store.get("denoise"); if (sd !== null) $("denoise").checked = sd === "1";
  const sg = store.get("gain"); if (sg !== null) $("gain").value = sg;
  const sv = store.get("vad"); if (sv !== null) $("vad").value = sv;
  const sb = store.get("buffer"); if (sb !== null) $("buffer").value = sb;
  $("hotkey").value = store.get("hotkey") ?? DEFAULT_HOTKEY;
  $("gainval").textContent = Math.round(readGain() * 100) + "%";
  $("vadval").textContent = (parseFloat($("vad").value) || 0).toFixed(2);

  $("manualConnect").addEventListener("click", () => start($("host").value.trim(), DEFAULT_PORT, null));
  $("host").addEventListener("keydown", (e) => { if (e.key === "Enter") $("manualConnect").click(); });
  const submitCode = () => {
    const code = $("code").value.trim();
    if (!code || authState === "pending") return; // already confirmed; the phone is deciding
    invoke("set_code", { code }).catch(() => {});
    $("codePrompt").classList.add("hidden");
    authState = null;
    setStatus(t("st_connecting"));
  };
  $("codeOk").addEventListener("click", submitCode);
  $("code").addEventListener("keydown", (e) => { if (e.key === "Enter") submitCode(); });
  $("refresh").addEventListener("click", listOutputs);
  syncVadEnabled();
  $("denoise").addEventListener("change", () => {
    store.set("denoise", $("denoise").checked ? "1" : "0");
    invoke("set_denoise", { on: $("denoise").checked }).catch(() => {});
    syncVadEnabled();
  });
  $("vad").addEventListener("input", () => {
    store.set("vad", $("vad").value);
    $("vadval").textContent = (parseFloat($("vad").value) || 0).toFixed(2);
    invoke("set_vad", { gate: parseFloat($("vad").value) || 0 }).catch(() => {});
  });
  $("gain").addEventListener("input", () => {
    const g = readGain();
    store.set("gain", $("gain").value);
    $("gainval").textContent = Math.round(g * 100) + "%";
    invoke("set_gain", { gain: g }).catch(() => {}); // live, native
  });
  $("buffer").addEventListener("change", () => { readBuffer(); store.set("buffer", $("buffer").value); });
  $("device").addEventListener("change", () => {
    store.set("outputDeviceId", $("device").value);
    if (running) invoke("set_output_device", { device: $("device").value || null }).catch(() => {});
  });
  $("hotkey").addEventListener("change", () => applyHotkey($("hotkey").value));
  $("reset").addEventListener("click", () => {
    if (!confirm(t("reset_confirm"))) return;
    store.clear();
    invoke("reset").catch(() => {});
  });

  // Autostart toggle.
  try { $("autostart").checked = await autoIsEnabled(); } catch (_) {}
  $("autostart").addEventListener("change", async () => {
    try { $("autostart").checked ? await autoEnable() : await autoDisable(); } catch (e) { console.warn(e); }
  });

  // Backend events — registered together (one round-trip each, in parallel) before anything that
  // could emit, so nothing is missed at startup. Session-scoped events gate on `target`, which is
  // set before the connect() invoke, so nothing that happens during the invoke is dropped either.
  await Promise.all([
    listen("vu", (e) => {
      profileCounts.vuEvents++;
      if (!running) return;
      if (e.payload.active) lastPcm = Date.now();
      // sqrt curve: full scale at peak=1 (no early clip), quiet signals stay visible.
      const peak = Math.min(1, Math.max(0, e.payload.peak || 0));
      $("vubar").style.width = Math.round(Math.sqrt(peak) * 100) + "%";
    }),
    listen("audio-output", (e) => { audioOutOk = !!e.payload; }),
    listen("stats", (e) => {
      profileCounts.statsEvents++;
      lastStats = { kbps: e.payload.kbps, loss: (e.payload.loss * 100).toFixed(1), ping: e.payload.ping };
    }),
    listen("session", (e) => {
      // "up": a session key was adopted; "lost": nothing decrypted for 5 s (phone gone/restarted/forgot us).
      if (!target) return;
      session = e.payload === "up" ? "up" : "lost";
      if (session === "up") { authState = null; reconnectDelay = 1000; hideCodePrompt(); }
      else lastPcm = 0;
    }),
    listen("auth", (e) => {
      if (!target) return;
      authState = reduceAuth(authState, e.payload);
      if (authState === "pending") hideCodePrompt(); // code accepted here; the phone decides now
    }),
    // SAS pairing: the phone revealed its key → ask the user for the code shown on the phone. The
    // backend emits this once per commit→reveal round; a wrong code starts a new round (sas-bad).
    // It can never arrive while the backend is still awaiting Allow (it stops sending PC then), so a
    // sas-needed always supersedes "pending" — e.g. after the 20 s PK timeout restarted the round.
    listen("sas-needed", (e) => {
      if (!target) return;
      const prompt = $("codePrompt");
      const fp = $("fpName"), fpRow = $("fpRow");
      if (e && e.payload) { fp.textContent = String(e.payload); fpRow.classList.remove("hidden"); }
      if (prompt.classList.contains("hidden")) {
        prompt.classList.remove("hidden");
        $("code").focus();
      }
      if (authState !== "bad") authState = "code";
    }),
    listen("sas-bad", () => {
      if (!target) return;
      $("code").value = "";
      authState = "bad"; // sticky "wrong code" until the user types again
      $("codePrompt").classList.remove("hidden");
      $("code").focus();
      updateStatus();
    }),
    listen("device-found", (e) => {
      const d = e.payload;
      addFound(d.ip, d.port, d.name, d.id);
      if (!running && !connecting && !triedAuto && d.ip === store.get("lastHost")) {
        triedAuto = true;
        connectTo(d.ip, d.port, d.id);
      }
      // The phone moved to another IP (DHCP / interface change) while our session is dead → follow it.
      const now = Date.now();
      if (target && shouldFollowIp({
        running, session, connectedId, foundId: d.id, foundIp: d.ip, targetHost: target.host,
        targetLastSeen: ipSeen.get(target.host), now, reconnecting: !!reconnectTimer,
      })) {
        const ex = phones.get(d.id || d.ip);
        if (ex) ex.ip = d.ip; // keep the row's name; drop the stale address
        target = { host: d.ip, port: d.port };
        store.set("lastHost", d.ip);
        renderSources(true);
        doReconnect();
      }
    }),
    listen("net-error", (e) => {
      // Net thread could not start (bind/socket/opus-init failure). Retry with backoff instead of
      // dropping the user back to the device list; stop() still fully tears down on purpose.
      if (!target) return;
      console.warn("net-error:", e.payload);
      if (!running) pendingNetError = true; // connect() is still resolving → start() will schedule it
      else scheduleReconnect();
    }),
    listen("remote-mute", (e) => { if (running) applyMute(!!e.payload); }),
    listen("tray-action", (e) => { if (e.payload === "disconnect") stop(); }),
  ]);

  // System accent (Windows); theme follows the OS via CSS.
  invoke("get_accent").then((a) => a && setAccent(a)).catch(() => {});

  await listOutputs();
  invoke("start_discovery").catch(() => {});
  syncTray();

  // Geometry + housekeeping.
  await restorePosition();
  const win = getCurrentWindow();
  let posTimer = null;
  win.onMoved(({ payload }) => {
    clearTimeout(posTimer);
    // Minimizing fires onMoved with the (-32000,-32000) parking spot, and hiding to the tray can
    // report junk too — persist a position only while the window is really on a monitor.
    posTimer = setTimeout(async () => {
      try {
        if (await win.isMinimized()) return;
        await windowBounds();
        const pos = { x: payload.x, y: payload.y };
        if (!isOnScreen(pos, await availableMonitors())) return;
        store.set(WIN_POS_KEY, JSON.stringify(pos));
      } catch (_) {}
    }, 250);
  }).catch(() => {});
  // One-off cleanup of the pre-1.0 logical-pixel key (it could hold a minimized window's position).
  store.remove("winPos");
  new ResizeObserver(() => { profileCounts.layoutEvents++; syncHeight(); }).observe(document.querySelector(".app"));
  syncHeight();
  sweepTimer = setInterval(sweepPhones, 5000);
  const profile = () => invoke("memory_profile", { sample: {
    ...profileCounts, domNodes: document.querySelectorAll("*").length, hidden: document.hidden, running
  } });
  profile().then((enabled) => {
    if (enabled) {
      const timer = setInterval(() => profile().catch((e) => { clearInterval(timer); console.error("memory profile:", e); }), 30000);
    }
  }).catch((e) => console.error("memory profile:", e));

  // Non-critical extras last: they may pop OS dialogs and must not delay discovery.
  applyHotkey($("hotkey").value);
  try {
    notifyOk = await isPermissionGranted();
    if (!notifyOk) notifyOk = (await requestPermission()) === "granted";
  } catch (_) {}
});
