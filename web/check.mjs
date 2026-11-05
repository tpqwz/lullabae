import createLullabae from "./lullabae.js";

const mod = await createLullabae();
const init = mod.cwrap("lb_init", null, ["number", "number"]);
const reset = mod.cwrap("lb_reset", null, ["number"]);
const detect = mod.cwrap("lb_detect", "number", ["number", "number"]);
const push = mod.cwrap("lb_push", "string", ["number", "number", "number"]);
const midi = mod.cwrap("lb_midi", "number", ["number", "number"]);

const rate = 48000;
const windowSize = 2048;
const hop = 1024 / rate;
let failed = 0;

function fail(message) {
  console.log("FAIL:", message);
  failed = 1;
}

init(rate, 440);
const ptr = mod._malloc(windowSize * 4);
const samples = new Float32Array(mod.HEAPF32.buffer, ptr, windowSize);
for (let i = 0; i < windowSize; i++) {
  samples[i] = 0.5 * Math.sin((2 * Math.PI * 440 * i) / rate);
}
const hz = detect(ptr, windowSize);
mod._free(ptr);
if (Math.abs(hz - 440) > 1) {
  fail(`440 Hz sine detected as ${hz}`);
}

const e4 = 329.6275569129;
let text = "";
for (let t = 0; t < 0.5; t += hop) {
  text += push(e4, 0.12, hop);
}
for (let t = 0; t < 0.55; t += hop) {
  text += push(0, 0, hop);
}
if (!text.includes("N E4 ") || !text.includes(" 1 0 64")) {
  fail(`E4 phrase -> ${JSON.stringify(text)}`);
}

const cap = 4096;
const midiPtr = mod._malloc(cap);
const n = midi(midiPtr, cap);
const bytes = Array.from(mod.HEAPU8.subarray(midiPtr, midiPtr + Math.max(n, 0)));
mod._free(midiPtr);
if (n < 14 || bytes[0] !== 0x4d || bytes[1] !== 0x54 || bytes[2] !== 0x68 || bytes[3] !== 0x64) {
  fail(`midi header ${n}`);
}
const on = bytes.findIndex((b, i) => b === 0x90 && bytes[i + 1] === 64);
if (on < 0) {
  fail("midi file has no E4");
}

reset(440);
let tap = "";
for (let t = 0; t < 0.2; t += hop) {
  tap += push(0, 0.01, hop);
}
tap += push(0, 0.45, 0.04);
for (let t = 0; t < 0.2; t += hop) {
  tap += push(0, 0.01, hop);
}
if (!tap.includes("T ")) {
  fail(`lap tap -> ${JSON.stringify(tap)}`);
}

if (failed) {
  process.exit(1);
}
console.log("web checks passed");
