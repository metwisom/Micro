import { test } from "node:test";
import assert from "node:assert/strict";
import {
  deriveStatus, isOnScreen, latencyEstimateMs, nextReconnectDelay, PHONE_NAME_MAX,
  reduceAuth, sanitizePhoneName, shouldFollowIp,
} from "../src/state.js";

const base = { audioOutOk: true, reconnecting: false, authState: null, muted: false, session: "none", lastPcm: 0, now: 100000 };

test("output device error wins over everything", () => {
  assert.equal(deriveStatus({ ...base, audioOutOk: false, muted: true, session: "up", lastPcm: 99900 }).key, "err_no_output");
});

test("reconnecting is sticky while the timer is pending, keeps the mute dot", () => {
  const r = deriveStatus({ ...base, reconnecting: true, muted: true });
  assert.deepEqual([r.key, r.tray], ["st_reconnecting", "muted"]);
});

test("auth sub-states render before streaming and keep the mute dot", () => {
  assert.equal(deriveStatus({ ...base, authState: "code" }).key, "st_enter_code");
  assert.equal(deriveStatus({ ...base, authState: "bad" }).key, "bad_code");
  assert.equal(deriveStatus({ ...base, authState: "pending" }).key, "st_waiting_approve");
  assert.equal(deriveStatus({ ...base, authState: "denied" }).cls, "err");
  assert.equal(deriveStatus({ ...base, authState: "busy" }).key, "st_busy");
  assert.equal(deriveStatus({ ...base, authState: "pending", muted: true }).tray, "muted");
});

test("muted never falls back to waiting", () => {
  const r = deriveStatus({ ...base, muted: true, session: "up", lastPcm: 0 });
  assert.deepEqual([r.key, r.tray], ["st_muted", "muted"]);
});

test("streaming needs a live session and fresh audio", () => {
  assert.equal(deriveStatus({ ...base, session: "up", lastPcm: 99000 }).streaming, true);
  assert.equal(deriveStatus({ ...base, session: "up", lastPcm: 98000 }).key, "st_waiting_phone");
  assert.equal(deriveStatus({ ...base, session: "none", lastPcm: 99900 }).key, "st_waiting_phone");
});

test("a lost session is reported as lost, not as waiting, even while muted", () => {
  assert.deepEqual(deriveStatus({ ...base, session: "lost" }), { key: "st_lost", cls: "err", tray: "idle" });
  assert.deepEqual(deriveStatus({ ...base, session: "lost", muted: true }), { key: "st_lost", cls: "err", tray: "muted" });
});

test("latency estimate: half the RTT + buffer + codec", () => {
  assert.equal(latencyEstimateMs(20, 3), 50);
});

test("auth reducer", () => {
  assert.equal(reduceAuth(null, "AUTH:PENDING"), "pending");
  assert.equal(reduceAuth("pending", "AUTH:APPROVED"), null);
  assert.equal(reduceAuth("code", "AUTH:DENIED"), "denied");
  assert.equal(reduceAuth("code", "AUTH:BUSY"), "busy");
  assert.equal(reduceAuth("code", "something else"), "code");
});

// Two 1920x1080 monitors side by side, the secondary to the LEFT of the primary — the real layout
// that produced the (-32000,-32000) bug report.
const monitors = [
  { position: { x: 0, y: 0 }, size: { width: 1920, height: 1080 } },
  { position: { x: -1920, y: 0 }, size: { width: 1920, height: 1080 } },
];

test("a minimized window's parking position is never treated as restorable", () => {
  // Windows moves minimized windows to (-32000,-32000); restoring that hides the app for good.
  assert.equal(isOnScreen({ x: -32000, y: -32000 }, monitors), false);
});

test("positions on either monitor are accepted", () => {
  assert.equal(isOnScreen({ x: 100, y: 100 }, monitors), true);
  assert.equal(isOnScreen({ x: -1800, y: 50 }, monitors), true);
  assert.equal(isOnScreen({ x: 0, y: 0 }, monitors), true);
});

test("positions that would put the title bar out of reach are rejected", () => {
  assert.equal(isOnScreen({ x: 1900, y: 100 }, monitors), false);  // hangs off the right edge
  assert.equal(isOnScreen({ x: 100, y: -40 }, monitors), false);   // title bar above the screen
  assert.equal(isOnScreen({ x: 100, y: 1060 }, monitors), false);  // below the bottom edge
  assert.equal(isOnScreen({ x: -4000, y: 100 }, monitors), false); // monitor that no longer exists
});

test("garbage and missing monitors are rejected, not restored blindly", () => {
  assert.equal(isOnScreen({ x: 100, y: 100 }, []), false);
  assert.equal(isOnScreen(null, monitors), false);
  assert.equal(isOnScreen({ x: NaN, y: 0 }, monitors), false);
  assert.equal(isOnScreen({ x: 100 }, monitors), false);
  assert.equal(isOnScreen({ x: 100, y: 100 }, [{}]), false);
});

test("follow the phone to a new IP only when the old one went silent", () => {
  const c = { running: true, session: "lost", connectedId: "id1", foundId: "id1", foundIp: "10.0.0.5", targetHost: "10.0.0.4", now: 100000, reconnecting: false };
  assert.equal(shouldFollowIp({ ...c, targetLastSeen: 80000 }), true);   // old IP silent for 20 s
  assert.equal(shouldFollowIp({ ...c, targetLastSeen: 95000 }), false);  // dual-homed: old IP still answers
  assert.equal(shouldFollowIp({ ...c, session: "up", targetLastSeen: 0 }), false);
  assert.equal(shouldFollowIp({ ...c, foundId: "other", targetLastSeen: 0 }), false);
  assert.equal(shouldFollowIp({ ...c, foundIp: "10.0.0.4", targetLastSeen: 0 }), false);
  assert.equal(shouldFollowIp({ ...c, reconnecting: true, targetLastSeen: 0 }), false);
});

test("reconnect backoff doubles and is capped", () => {
  assert.equal(nextReconnectDelay(1000), 2000);
  assert.equal(nextReconnectDelay(2000), 4000);
  assert.equal(nextReconnectDelay(8000), 15000); // clamped, not 16000
  assert.equal(nextReconnectDelay(15000), 15000); // stays clamped
  // A zero, negative or garbage delay must never turn the reconnect into a busy loop.
  assert.equal(nextReconnectDelay(0), 2000);
  assert.equal(nextReconnectDelay(-5), 2000);
  assert.equal(nextReconnectDelay(NaN), 2000);
});

test("a discovery name is defanged before it reaches the DOM", () => {
  // Any LAN host can answer the broadcast, so this string is attacker-controlled.
  const bell = String.fromCharCode(7);
  const esc = String.fromCharCode(27);
  assert.equal(sanitizePhoneName("Pixel" + bell + " 6a" + esc), "Pixel  6a ");
  // Ordinary names — digits and non-ASCII included — must survive untouched. (A mangled character
  // class here once ate digits and hex letters from every name on the network.)
  assert.equal(sanitizePhoneName("Pixel 6a 2024"), "Pixel 6a 2024");
  assert.equal(sanitizePhoneName("Телефон 42"), "Телефон 42");
  assert.equal(sanitizePhoneName("abcdef0123"), "abcdef0123");
  // Long names are truncated so they cannot displace the controls in a fixed-width window.
  assert.equal(sanitizePhoneName("A".repeat(300)).length, PHONE_NAME_MAX);
  // Missing / non-string values degrade to empty rather than the string "undefined".
  assert.equal(sanitizePhoneName(undefined), "");
  assert.equal(sanitizePhoneName(null), "");
});

test("a discovery name cannot carry bidi or format controls into the UI", () => {
  const ch = (n) => String.fromCharCode(n);
  // An RTL override reorders everything after it in the device row and the tray tooltip, which is
  // how a hostile entry gets to read like a friendly one.
  assert.equal(sanitizePhoneName("Pixel" + ch(0x202e) + "6a"), "Pixel 6a");
  // Zero-width and soft-hyphen characters let two entries render identically.
  assert.equal(sanitizePhoneName("Pi" + ch(0x200b) + "xel"), "Pi xel");
  assert.equal(sanitizePhoneName("Pi" + ch(0x00ad) + "xel"), "Pi xel");
  assert.equal(sanitizePhoneName("a" + ch(0xfeff) + "b"), "a b");
  assert.equal(sanitizePhoneName("a" + ch(0x2066) + "b"), "a b");
  // C1 controls too.
  assert.equal(sanitizePhoneName("a" + ch(0x0085) + "b"), "a b");
  // Ordinary non-ASCII text is untouched — this must not become a latin-only filter.
  assert.equal(sanitizePhoneName("Телефон Руслана"), "Телефон Руслана");
  assert.equal(sanitizePhoneName("Pixel 6a — 2024"), "Pixel 6a — 2024");
  assert.equal(sanitizePhoneName("日本語の名前"), "日本語の名前");
});
