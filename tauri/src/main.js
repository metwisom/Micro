import { invoke, Channel } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { getCurrentWindow, LogicalSize, LogicalPosition } from "@tauri-apps/api/window";
import { enable as autoEnable, disable as autoDisable, isEnabled as autoIsEnabled } from "@tauri-apps/plugin-autostart";
import { isPermissionGranted, requestPermission, sendNotification } from "@tauri-apps/plugin-notification";
import { register as registerShortcut } from "@tauri-apps/plugin-global-shortcut";

// --- Localization ------------------------------------------------------------
let lang = "en";
const TR = {
  en: {
    source_label: "Phone source", connect: "Connect", connect_ip: "Connect by IP…",
    disconnect: "Disconnect", searching: "Searching for microphone…", settings: "Settings",
    language: "Language", output: "Audio output (device)", refresh: "Refresh list",
    denoise: "Noise suppression (RNNoise)", volume: "Volume", volume_hint: "(signal boost)",
    vad: "VAD gate", vad_hint: "(0 = off, 0.6–0.9 = hard)",
    buffer: "Buffer (10 ms frames, lower = less ping)", autostart: "Launch at login",
    footer: "Open Micro on the phone and tap Start — it appears as a button above. Output auto-selects VB-Cable (CABLE Input).",
    st_connecting: "Connecting…", st_streaming: "Streaming", st_waiting_phone: "Waiting for phone…",
    st_waiting_approve: "Waiting for approval on the phone…", st_denied: "Device rejected by the phone", st_muted: "Muted",
    ping_rtt: "network round-trip", latency_est: "estimated mic→virtual-mic latency",
    err_no_ip: "Enter the phone IP", err_prefix: "Error: ",
    denoise_failed: "RNNoise didn't load — audio without suppression",
    reset: "Reset app", tray_show: "Show", tray_quit: "Quit",
    mute: "Mute", unmute: "Muted", notif_connected: "Connected", notif_disconnected: "Disconnected",
    enter_code: "Enter the code shown on the phone", ok: "OK", bad_code: "Wrong code — try the new one on the phone",
    test: "Monitor in headphones",
    reset_confirm: "Reset the app? This deletes settings and this device's identity, as if launched for the first time.",
  },
  ru: {
    source_label: "Источник — телефон", connect: "Подключить", connect_ip: "Подключение по IP…",
    disconnect: "Отключить", searching: "Поиск микрофона…", settings: "Настройки",
    language: "Язык", output: "Вывод звука (устройство)", refresh: "Обновить список",
    denoise: "Шумоподавление (RNNoise)", volume: "Громкость", volume_hint: "(усиление сигнала)",
    vad: "VAD-гейт", vad_hint: "(0 = выкл, 0.6–0.9 = жёстко)",
    buffer: "Буфер (кадров по 10мс, меньше = ниже пинг)", autostart: "Запуск при входе",
    footer: "На телефоне открой Micro и нажми Start — он появится кнопкой выше. Вывод авто-ставится на VB-Cable (CABLE Input).",
    st_connecting: "Подключение…", st_streaming: "Поток идёт", st_waiting_phone: "Ожидание телефона…",
    st_waiting_approve: "Ожидание одобрения на телефоне…", st_denied: "Телефон отклонил это устройство", st_muted: "Звук отключён",
    ping_rtt: "сетевой пинг (туда-обратно)", latency_est: "оценка задержки мик→вирт-мик",
    err_no_ip: "Введите IP телефона", err_prefix: "Ошибка: ",
    denoise_failed: "RNNoise не загрузился — звук без шумодава",
    reset: "Сбросить приложение", tray_show: "Показать", tray_quit: "Выход",
    mute: "Заглушить", unmute: "Без звука", notif_connected: "Подключено", notif_disconnected: "Отключено",
    enter_code: "Введите код, показанный на телефоне", ok: "OK", bad_code: "Неверный код — введите новый с телефона",
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
  const v = localStorage.getItem("lang");
  return TR[v] ? v : detectLang();
}
function applyI18n() {
  document.documentElement.lang = lang;
  document.querySelectorAll("[data-i18n]").forEach((el) => (el.textContent = t(el.dataset.i18n)));
  document.querySelectorAll("[data-i18n-title]").forEach((el) => (el.title = t(el.dataset.i18nTitle)));
  renderSources();
  syncTray();
}

const $ = (id) => document.getElementById(id);

// --- Inline icons (currentColor) ---------------------------------------------
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
  const lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255;
  const shade = (p) => {
    const f = (v) => Math.max(0, Math.min(255, Math.round(p < 0 ? v * (1 + p) : v + (255 - v) * p)));
    return `rgb(${f(r)}, ${f(g)}, ${f(b)})`;
  };
  const s = document.documentElement.style;
  s.setProperty("--accent", "#" + c);
  s.setProperty("--accent-fg", lum > 0.6 ? "#000000" : "#ffffff");
  s.setProperty("--accent-press", shade(-0.15));
  s.setProperty("--accent-hover", shade(lum > 0.6 ? -0.1 : 0.14));
}

// --- State -------------------------------------------------------------------
let running = false;
let audioCtx = null, worklet = null, gainNode = null, limiter = null, streamDest = null, sinkAudio = null;
let target = null, statusTimer = null, sweepTimer = null, lastPcm = 0, authState = null;
let statusText = "", statusCls = "", statusTitle = "";
let lastStats = null;
let muted = false, analyser = null, vuRAF = 0, wasStreaming = false, triedAuto = false;
let monitoring = false, monitorDest = null, monitorAudio = null;
let audioChannel = null;
let trayState = "";
let notifyOk = false;
const phones = new Map(); // ip -> { port, name, seen }

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
  invoke("set_mute", { on: muted }); // tell the server to stop/resume transmission
}

// Apply mute locally (used by the button and by remote sync from the server).
// Updates the mute button in place — no full re-render, so the device row doesn't flicker.
function applyMute(on) {
  muted = on;
  if (!muted) lastPcm = Date.now(); // grace window so unmute doesn't flash "waiting" before audio resumes
  if (gainNode) gainNode.gain.value = muted ? 0 : readGain();
  setTrayState(muted ? "muted" : "active");
  const b = $("muteBtn");
  if (b) {
    b.className = "mute-btn icon-btn" + (muted ? " on" : "");
    b.innerHTML = muted ? ICON_MIC_OFF : ICON_MIC;
    b.title = muted ? t("unmute") : t("mute");
    b.setAttribute("aria-label", b.title);
  } else {
    renderSources();
  }
}

// Monitor: also play the (post gain+limiter) audio to the default output (headphones), so you
// can hear exactly what goes into the virtual cable.
function toggleMonitor() {
  if (!running || !audioCtx || !limiter) return;
  monitoring = !monitoring;
  if (monitoring) {
    monitorDest = audioCtx.createMediaStreamDestination();
    limiter.connect(monitorDest);
    monitorAudio = new Audio();
    monitorAudio.srcObject = monitorDest.stream;
    if (monitorAudio.setSinkId) monitorAudio.setSinkId("default").catch(() => {});
    monitorAudio.play().catch(() => {});
  } else {
    stopMonitor();
  }
  renderSources();
}
function stopMonitor() {
  try { limiter && monitorDest && limiter.disconnect(monitorDest); } catch (_) {}
  try { monitorAudio && monitorAudio.pause(); } catch (_) {}
  monitorDest = null;
  monitorAudio = null;
  monitoring = false;
}

function readGain() {
  const v = parseFloat($("gain").value);
  return isFinite(v) ? Math.max(0, Math.min(8, v)) : 1.5;
}

function setStatus(text, cls, title) {
  statusText = text;
  statusCls = cls || "";
  statusTitle = title || "";
  updateActiveStatus();
}
function updateActiveStatus() {
  const el = document.querySelector(".source-btn.active .btn-status");
  if (el) {
    el.textContent = statusText;
    el.className = "btn-status" + (statusCls ? " " + statusCls : "");
    el.title = statusTitle;
  }
}

// --- Sources (with TTL) ------------------------------------------------------
function addFound(ip, port, name) {
  phones.set(ip, { port, name, seen: Date.now() });
  renderSources();
}
function sweepPhones() {
  const now = Date.now();
  let changed = false;
  for (const [ip, info] of phones) {
    if (now - info.seen > 15000 && !(running && target && target.host === ip)) {
      phones.delete(ip);
      changed = true;
    }
  }
  if (changed) renderSources();
}

function renderSources() {
  const box = $("sources");
  if (!box) return;
  box.innerHTML = "";

  const entries = new Map(phones);
  if (running && target && !entries.has(target.host)) {
    entries.set(target.host, { port: target.port, name: target.host });
  }
  if (entries.size === 0 && !running) {
    const ph = document.createElement("div");
    ph.className = "searching";
    ph.textContent = t("searching");
    box.appendChild(ph);
  }

  for (const [ip, info] of entries) {
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
      st.textContent = statusText;
      sub.append(i, st);
      col.append(n, sub);
      const muteBtn = document.createElement("button");
      muteBtn.id = "muteBtn";
      muteBtn.className = "mute-btn icon-btn" + (muted ? " on" : "");
      muteBtn.innerHTML = muted ? ICON_MIC_OFF : ICON_MIC;
      muteBtn.title = muted ? t("unmute") : t("mute");
      muteBtn.setAttribute("aria-label", muteBtn.title);
      muteBtn.addEventListener("click", toggleMute);
      const testBtn = document.createElement("button");
      testBtn.className = "mute-btn icon-btn test-btn" + (monitoring ? " on" : "");
      testBtn.innerHTML = ICON_HEADPHONES;
      testBtn.title = t("test");
      testBtn.setAttribute("aria-label", testBtn.title);
      testBtn.addEventListener("click", toggleMonitor);
      const stopBtn = document.createElement("button");
      stopBtn.className = "stop-btn icon-btn";
      stopBtn.innerHTML = ICON_POWER;
      stopBtn.title = t("disconnect");
      stopBtn.setAttribute("aria-label", stopBtn.title);
      stopBtn.addEventListener("click", stop);
      const btns = document.createElement("div");
      btns.className = "active-btns";
      btns.append(muteBtn, testBtn, stopBtn);
      row.append(col, btns);
      box.appendChild(row);
    } else {
      const b = document.createElement("button");
      b.className = "source-btn";
      b.disabled = running;
      const n = document.createElement("b"); n.textContent = info.name;
      const i = document.createElement("span"); i.textContent = ip;
      b.append(n, i);
      b.addEventListener("click", () => connectTo(ip, info.port || 50005));
      box.appendChild(b);
    }
  }

  if (running) {
    // Already connected — no point offering "connect by IP".
    $("manual").classList.add("hidden");
    return;
  }
  const manualBtn = document.createElement("button");
  manualBtn.className = "source-btn manual-btn";
  manualBtn.textContent = t("connect_ip");
  manualBtn.addEventListener("click", () => {
    const m = $("manual");
    m.classList.toggle("hidden");
    if (!m.classList.contains("hidden")) $("host").focus();
  });
  box.appendChild(manualBtn);
}

function connectTo(host, port) {
  if (running) return;
  start(host, port || 50005);
}

// --- Output device -----------------------------------------------------------
async function listOutputs() {
  try {
    const s = await navigator.mediaDevices.getUserMedia({ audio: true });
    s.getTracks().forEach((tr) => tr.stop());
  } catch (_) {}
  const devs = await navigator.mediaDevices.enumerateDevices();
  const outs = devs.filter((d) => d.kind === "audiooutput");
  const sel = $("device");
  const prev = sel.value || localStorage.getItem("outputDeviceId");
  sel.innerHTML = "";
  for (const d of outs) {
    const o = document.createElement("option");
    o.value = d.deviceId;
    o.textContent = d.label || `Output (${d.deviceId.slice(0, 8)})`;
    sel.appendChild(o);
  }
  if (prev && outs.some((d) => d.deviceId === prev)) {
    sel.value = prev;
  } else {
    const vb =
      outs.find((d) => /cable input/i.test(d.label)) ||
      outs.find((d) => /vb[- ]?audio/i.test(d.label)) ||
      outs.find((d) => (d.label || "").trim() === "Micro"); // Linux virtual sink
    if (vb) sel.value = vb.deviceId;
  }
}

// --- Start / stop ------------------------------------------------------------
async function start(host, port) {
  if (running) return;
  host = (host || $("host").value || "").trim();
  if (!host) { setStatus(t("err_no_ip"), "err"); return; }
  port = port || 50005;

  try {
    await invoke("set_denoise", { on: $("denoise").checked });
    await invoke("set_vad", { gate: parseFloat($("vad").value) || 0 });
    await invoke("set_code", { code: "" }); // cleared; set only if the phone asks to pair
    $("codePrompt").classList.add("hidden");

    audioCtx = new AudioContext({ latencyHint: "interactive", sampleRate: 48000 });
    await audioCtx.audioWorklet.addModule("/pcm-worklet.js");
    const prefill = Math.max(1, parseInt($("buffer").value, 10) || 3);
    worklet = new AudioWorkletNode(audioCtx, "pcm-player", {
      numberOfInputs: 0, numberOfOutputs: 1, outputChannelCount: [1], processorOptions: { prefill },
    });
    gainNode = audioCtx.createGain();
    gainNode.gain.value = readGain();
    // Soft limiter to stop clipping when the gain is cranked.
    limiter = audioCtx.createDynamicsCompressor();
    limiter.threshold.value = -2;
    limiter.knee.value = 0;
    limiter.ratio.value = 20;
    limiter.attack.value = 0.003;
    limiter.release.value = 0.1;
    worklet.connect(gainNode).connect(limiter);
    analyser = audioCtx.createAnalyser();
    analyser.fftSize = 256;
    gainNode.connect(analyser);

    const devId = $("device").value;
    if (devId && typeof audioCtx.setSinkId === "function") {
      limiter.connect(audioCtx.destination);
      try { await audioCtx.setSinkId(devId); } catch (e) { console.warn("setSinkId", e); }
    } else {
      streamDest = audioCtx.createMediaStreamDestination();
      limiter.connect(streamDest);
      sinkAudio = new Audio();
      sinkAudio.srcObject = streamDest.stream;
      if (devId && sinkAudio.setSinkId) { try { await sinkAudio.setSinkId(devId); } catch (_) {} }
      await sinkAudio.play();
    }
    if (audioCtx.state === "suspended") await audioCtx.resume();

    target = { host, port };
    authState = null;
    lastPcm = 0;
    lastStats = null;
    muted = false;
    invoke("set_mute", { on: false }); // clear any stale server-side mute
    monitoring = false;
    wasStreaming = false;

    // Binary audio stream from Rust (raw f32 ArrayBuffer per frame — no JSON).
    audioChannel = new Channel();
    audioChannel.onmessage = (msg) => {
      if (!worklet) return;
      const buf = msg instanceof ArrayBuffer ? msg : msg.buffer;
      if (!buf) return;
      lastPcm = Date.now();
      worklet.port.postMessage(new Float32Array(buf), [buf]);
    };
    await invoke("connect", { host, port, audio: audioChannel });

    running = true;
    localStorage.setItem("lastHost", host);
    $("manual").classList.add("hidden");
    $("vu").classList.remove("hidden");
    renderSources();
    setStatus(t("st_connecting"));
    setTrayState("idle");
    syncTray();
    drawVU();
    statusTimer = setInterval(updateStatus, 500);
  } catch (e) {
    console.error(e);
    setStatus(t("err_prefix") + (e && e.message ? e.message : e), "err");
    try { await invoke("disconnect"); } catch (_) {}
    running = false;
    try { audioCtx && audioCtx.close(); } catch (_) {}
    audioCtx = worklet = gainNode = limiter = streamDest = sinkAudio = target = null;
  }
}

async function stop() {
  if (!running) return;
  running = false;
  stopMonitor();
  cancelAnimationFrame(vuRAF);
  clearInterval(statusTimer); statusTimer = null;
  try { await invoke("disconnect"); } catch (_) {}
  try { sinkAudio && sinkAudio.pause(); } catch (_) {}
  try { audioCtx && audioCtx.close(); } catch (_) {}
  audioCtx = worklet = gainNode = limiter = analyser = streamDest = sinkAudio = target = null;
  audioChannel = null;
  lastStats = null;
  muted = false;
  $("vu").classList.add("hidden");
  $("vubar").style.width = "0%";
  $("codePrompt").classList.add("hidden");
  $("code").value = "";
  invoke("set_code", { code: "" }).catch(() => {});
  if (wasStreaming) notify(t("notif_disconnected"));
  wasStreaming = false;
  setTrayState("idle");
  renderSources();
  syncTray();
}

function drawVU() {
  if (!running || !analyser) return;
  const buf = new Float32Array(analyser.fftSize);
  analyser.getFloatTimeDomainData(buf);
  let sum = 0;
  for (let i = 0; i < buf.length; i++) sum += buf[i] * buf[i];
  const rms = Math.sqrt(sum / buf.length);
  const pct = Math.min(100, Math.round(rms * 320)); // ~ -30dB..0 → 0..100
  $("vubar").style.width = pct + "%";
  vuRAF = requestAnimationFrame(drawVU);
}

function updateStatus() {
  if (!running) return;
  if (muted) {
    // Muted → server stops sending, so there's no PCM. Stay "muted", don't fall to "waiting".
    authState = null;
    setStatus(t("st_muted"), "ok");
    setTrayState("muted");
    $("codePrompt").classList.add("hidden");
    return;
  }
  const dt = Date.now() - lastPcm;
  if (lastPcm && dt < 1500) {
    authState = null; // session is up; don't fall back to "waiting for approval"
    let extra = "", title = "";
    if (lastStats) {
      const prefill = parseInt($("buffer").value, 10) || 3;
      // End-to-end estimate: one-way network + jitter buffer + ~10 ms capture/codec.
      const e2e = Math.round(lastStats.ping / 2 + prefill * 10 + 10);
      extra = ` · ${lastStats.kbps} kbps · ${lastStats.loss}% · ${lastStats.ping} ms ⇄ · ~${e2e} ms`;
      title = `${lastStats.ping} ms ${t("ping_rtt")} · ~${e2e} ms ${t("latency_est")}`;
    }
    setStatus(t("st_streaming") + extra, "ok", title);
    setTrayState(muted ? "muted" : "active");
    $("codePrompt").classList.add("hidden");
    if (!wasStreaming) {
      wasStreaming = true;
      notify(t("notif_connected") + " — " + (phones.get(target.host)?.name || target.host));
    }
  } else if (authState === "pending") { setStatus(t("st_waiting_approve")); setTrayState("idle"); }
  else if (authState === "denied") { setStatus(t("st_denied"), "err"); setTrayState("idle"); }
  else { setStatus(t("st_waiting_phone")); setTrayState("idle"); }
}

// --- Tray (localized + dynamic) ----------------------------------------------
function syncTray() {
  const connectedName = running && target ? (phones.get(target.host)?.name || target.host) : null;
  invoke("update_tray", {
    show: t("tray_show"),
    quit: t("tray_quit"),
    disconnect: running ? t("disconnect") : null,
    status: connectedName,
  }).catch(() => {});
}

// --- Window geometry ---------------------------------------------------------
function syncHeight() {
  const app = document.querySelector(".app");
  if (!app) return;
  const h = Math.min(1000, Math.ceil(app.offsetHeight));
  getCurrentWindow().setSize(new LogicalSize(500, h)).catch(() => {});
}
async function restorePosition() {
  try {
    const p = JSON.parse(localStorage.getItem("winPos") || "null");
    if (p && Number.isFinite(p.x) && Number.isFinite(p.y)) {
      await getCurrentWindow().setPosition(new LogicalPosition(p.x, p.y));
    }
  } catch (_) {}
}

// --- Wire up -----------------------------------------------------------------
window.addEventListener("DOMContentLoaded", async () => {
  lang = loadLang();
  $("lang").value = lang;
  applyI18n();
  $("lang").addEventListener("change", () => { lang = $("lang").value; localStorage.setItem("lang", lang); applyI18n(); });

  // Restore saved settings (output device is restored in listOutputs).
  const sd = localStorage.getItem("denoise"); if (sd !== null) $("denoise").checked = sd === "1";
  const sg = localStorage.getItem("gain"); if (sg !== null) $("gain").value = sg;
  const sv = localStorage.getItem("vad"); if (sv !== null) $("vad").value = sv;
  const sb = localStorage.getItem("buffer"); if (sb !== null) $("buffer").value = sb;
  $("gainval").textContent = Math.round(readGain() * 100) + "%";
  $("vadval").textContent = (parseFloat($("vad").value) || 0).toFixed(2);

  $("manualConnect").addEventListener("click", () => start($("host").value.trim(), 50005));
  $("host").addEventListener("keydown", (e) => { if (e.key === "Enter") $("manualConnect").click(); });
  const submitCode = () => {
    const code = $("code").value.trim();
    if (!code) return;
    invoke("set_code", { code });
    $("codePrompt").classList.add("hidden");
    setStatus(t("st_connecting"));
  };
  $("codeOk").addEventListener("click", submitCode);
  $("code").addEventListener("keydown", (e) => { if (e.key === "Enter") submitCode(); });
  $("refresh").addEventListener("click", listOutputs);
  $("device").addEventListener("change", () => localStorage.setItem("outputDeviceId", $("device").value));
  $("denoise").addEventListener("change", () => {
    localStorage.setItem("denoise", $("denoise").checked ? "1" : "0");
    invoke("set_denoise", { on: $("denoise").checked });
  });
  $("vad").addEventListener("input", () => {
    localStorage.setItem("vad", $("vad").value);
    $("vadval").textContent = (parseFloat($("vad").value) || 0).toFixed(2);
    invoke("set_vad", { gate: parseFloat($("vad").value) || 0 });
  });
  $("gain").addEventListener("input", () => {
    const g = readGain();
    localStorage.setItem("gain", $("gain").value);
    $("gainval").textContent = Math.round(g * 100) + "%";
    if (gainNode) gainNode.gain.value = g;
  });
  $("buffer").addEventListener("change", () => localStorage.setItem("buffer", $("buffer").value));
  $("reset").addEventListener("click", () => {
    if (!confirm(t("reset_confirm"))) return;
    try { localStorage.clear(); } catch (_) {}
    invoke("reset");
  });

  // Autostart toggle.
  try { $("autostart").checked = await autoIsEnabled(); } catch (_) {}
  $("autostart").addEventListener("change", async () => {
    try { $("autostart").checked ? await autoEnable() : await autoDisable(); } catch (e) { console.warn(e); }
  });

  navigator.mediaDevices.addEventListener("devicechange", listOutputs);

  // Backend events.
  await listen("stats", (e) => {
    lastStats = { kbps: e.payload.kbps, loss: (e.payload.loss * 100).toFixed(1), ping: e.payload.ping };
  });
  await listen("auth", (e) => {
    if (String(e.payload).startsWith("AUTH:DENIED")) {
      authState = "denied";
      setStatus(t("st_denied"), "err");
    }
  });
  // SAS pairing: server revealed its key → ask the user to enter the code shown on the phone.
  await listen("sas-needed", () => {
    if (!running) return;
    authState = "pending";
    setStatus(t("st_waiting_approve"));
    $("codePrompt").classList.remove("hidden");
    $("code").focus();
  });
  await listen("sas-bad", () => {
    setStatus(t("bad_code"), "err");
    $("code").value = "";
    if (running) { $("codePrompt").classList.remove("hidden"); $("code").focus(); }
  });
  await listen("device-found", (e) => {
    const d = e.payload;
    addFound(d.ip, d.port, d.name);
    if (!running && !triedAuto && d.ip === localStorage.getItem("lastHost")) {
      triedAuto = true;
      connectTo(d.ip, d.port);
    }
  });
  await listen("net-error", (e) => setStatus(t("err_prefix") + e.payload, "err"));
  await listen("remote-mute", (e) => { if (running) applyMute(!!e.payload); });
  await listen("tray-action", (e) => { if (e.payload === "disconnect") stop(); });

  // Notifications.
  try {
    notifyOk = await isPermissionGranted();
    if (!notifyOk) notifyOk = (await requestPermission()) === "granted";
  } catch (_) {}

  // Global hotkey: Ctrl+Shift+M toggles mute.
  try {
    await registerShortcut("CmdOrControl+Shift+M", (e) => {
      if (!e || e.state === "Pressed") toggleMute();
    });
  } catch (e) { console.warn("shortcut", e); }

  // System accent (Windows); theme follows the OS via CSS.
  invoke("get_accent").then((a) => a && setAccent(a)).catch(() => {});

  await listOutputs();
  await invoke("start_discovery").catch(() => {});
  syncTray();

  // Geometry + housekeeping.
  await restorePosition();
  const win = getCurrentWindow();
  win.onMoved(({ payload }) => {
    const f = window.devicePixelRatio || 1;
    localStorage.setItem("winPos", JSON.stringify({ x: Math.round(payload.x / f), y: Math.round(payload.y / f) }));
  });
  new ResizeObserver(() => syncHeight()).observe(document.querySelector(".app"));
  syncHeight();
  sweepTimer = setInterval(sweepPhones, 5000);
});
