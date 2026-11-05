import createLullabae from "./lullabae.js";

const WINDOW = 2048;
const HOP = 1024;
const STRINGS = ["", "e", "B", "G", "D", "A", "E"];
const PHRASE = [
  [64, 0], [64, 14], [67, -18], [64, 0],
  [69, 8], [67, 0], [62, -12], [60, 0],
];

const statusEl = document.getElementById("status");
const noteEl = document.getElementById("note");
const detailEl = document.getElementById("detail");
const marker = document.getElementById("marker");
const scoreEl = document.getElementById("score");
const a4Label = document.getElementById("aref");
const a4Input = document.getElementById("a4");
const lullabyBtn = document.getElementById("lullaby");
const listenBtn = document.getElementById("listen");
const saveBtn = document.getElementById("save");

const mod = await createLullabae();
const lbInit = mod.cwrap("lb_init", null, ["number", "number"]);
const lbReset = mod.cwrap("lb_reset", null, ["number"]);
const lbTuning = mod.cwrap("lb_set_tuning", null, ["number"]);
const lbDetect = mod.cwrap("lb_detect", "number", ["number", "number"]);
const lbPush = mod.cwrap("lb_push", "string", ["number", "number", "number"]);
const lbDescribe = mod.cwrap("lb_describe", "string", ["number", "number"]);
const lbMidi = mod.cwrap("lb_midi", "number", ["number", "number"]);

const samplePtr = mod._malloc(WINDOW * 4);
let a4 = 440;
let sampleRate = 48000;
let readyRate = 0;
let primed = 0;
let heldHz = NaN;
let mode = "ready";
const windowSamples = new Float32Array(WINDOW);

let audioCtx = null;
let micStream = null;
let micNode = null;
let worklet = null;
let lullabySrc = null;

function heapSamples() {
  return new Float32Array(mod.HEAPF32.buffer, samplePtr, WINDOW);
}

function beginSession(rate) {
  sampleRate = rate;
  if (readyRate !== rate) {
    lbInit(rate, a4);
    readyRate = rate;
  } else {
    lbReset(a4);
  }
  primed = 0;
  windowSamples.fill(0);
  heldHz = NaN;
  scoreEl.replaceChildren();
  const empty = document.createElement("div");
  empty.id = "empty";
  empty.textContent = "hum a melody, or tap a beat";
  scoreEl.appendChild(empty);
  saveBtn.disabled = true;
  showLive(0, 0);
}

function onHop(hop) {
  windowSamples.copyWithin(0, hop.length);
  windowSamples.set(hop, WINDOW - hop.length);
  primed += hop.length;
  if (primed < WINDOW) {
    return;
  }
  let energy = 0;
  for (let i = 0; i < hop.length; i++) {
    energy += hop[i] * hop[i];
  }
  const rms = Math.sqrt(energy / hop.length);
  heapSamples().set(windowSamples);
  const hz = lbDetect(samplePtr, WINDOW);
  const text = lbPush(hz, rms, hop.length / sampleRate);
  showLive(hz, rms);
  if (!text) {
    return;
  }
  for (const line of text.trim().split("\n")) {
    accept(line);
  }
}

function showLive(hz, rms) {
  const voiced = hz > 0 && rms >= 0.02;
  if (!voiced) {
    heldHz = NaN;
    noteEl.textContent = "—";
    detailEl.textContent = mode === "listen" ? "hum a note" : "";
    placeMarker(0, false);
    statusEl.textContent = mode === "listen" ? "Quiet" : mode === "lullaby" ? "Lullaby" : "Ready";
    return;
  }
  if (!Number.isFinite(heldHz) || Math.abs(hz - heldHz) >= 0.35) {
    heldHz = hz;
  }
  const described = lbDescribe(heldHz, a4);
  const parts = described ? described.split(" ") : [];
  const name = parts[0] || "—";
  const cents = parts.length > 1 ? Number(parts[1]) : 0;
  noteEl.textContent = name;
  const bias = cents === 0 ? "in tune" : cents > 0 ? `${cents} cents sharp` : `${Math.abs(cents)} cents flat`;
  detailEl.textContent = `${bias} · ${heldHz.toFixed(1)} Hz`;
  placeMarker(cents, true);
  statusEl.textContent = mode === "listen" ? "Listening" : "Lullaby";
}

function placeMarker(cents, voiced) {
  const clamped = Math.max(-50, Math.min(50, cents));
  marker.style.left = `${((clamped + 50) / 100) * 100}%`;
  marker.style.background = voiced && Math.abs(cents) > 10 ? "#E3C28A" : "#8FCBB0";
}

function accept(line) {
  const parts = line.split(" ");
  const empty = document.getElementById("empty");
  if (empty) {
    empty.remove();
  }
  const card = document.createElement("div");
  card.className = "card";
  if (parts[0] === "T") {
    card.style.minWidth = "4.5rem";
    const name = document.createElement("b");
    name.textContent = "tap";
    card.appendChild(name);
  } else if (parts[0] === "N" && parts.length >= 6) {
    const cents = Number(parts[2]);
    const duration = Number(parts[3]);
    const extra = Math.min(duration, 1600) / 800 * 36;
    card.style.minWidth = `${78 + extra}px`;
    const name = document.createElement("b");
    name.textContent = parts[1];
    const cent = document.createElement("div");
    cent.className = "cents";
    if (Math.abs(cents) >= 8) {
      cent.textContent = (cents > 0 ? "+" : "−") + Math.abs(cents);
    }
    const tab = document.createElement("div");
    tab.className = "tab";
    const stringNum = Number(parts[4]);
    tab.textContent = `${stringNum >= 1 && stringNum <= 6 ? STRINGS[stringNum] : ""}  ${parts[5]}`;
    card.append(name, cent, tab);
  } else {
    return;
  }
  scoreEl.appendChild(card);
  scoreEl.scrollLeft = scoreEl.scrollWidth;
  saveBtn.disabled = false;
}

function renderPhrase(rate) {
  const noteSamples = Math.round(0.75 * rate);
  const fade = Math.max(1, Math.round(rate / 100));
  const tone = noteSamples * PHRASE.length;
  const tail = Math.round(0.25 * rate);
  const out = new Float32Array(tone + tail);
  let phase = 0;
  for (let i = 0; i < tone; i++) {
    const at = i % noteSamples;
    const step = PHRASE[(i / noteSamples) | 0];
    const hz = a4 * 2 ** ((step[0] - 69) / 12 + step[1] / 1200);
    let gain = 1;
    if (at < fade) {
      gain = at / fade;
    } else if (noteSamples - at < fade) {
      gain = (noteSamples - at) / fade;
    }
    phase += (2 * Math.PI * hz) / rate;
    out[i] = 0.22 * gain * Math.sin(phase);
  }
  return out;
}

function feedAll(samples) {
  for (let i = 0; i + HOP <= samples.length; i += HOP) {
    onHop(samples.subarray(i, i + HOP));
  }
}

function stopMic() {
  if (micStream) {
    for (const track of micStream.getTracks()) {
      track.stop();
    }
  }
  micStream = null;
  if (micNode) {
    micNode.disconnect();
  }
  micNode = null;
  if (worklet) {
    worklet.disconnect();
  }
  worklet = null;
  if (lullabySrc) {
    try {
      lullabySrc.stop();
    } catch {
      /* already ended */
    }
  }
  lullabySrc = null;
}

function mark(which) {
  lullabyBtn.classList.toggle("on", which === "lullaby");
  listenBtn.classList.toggle("on", which === "listen");
}

async function playLullaby() {
  stopMic();
  mode = "lullaby";
  mark("lullaby");
  if (!audioCtx) {
    audioCtx = new AudioContext();
  }
  beginSession(audioCtx.sampleRate);
  const samples = renderPhrase(sampleRate);
  feedAll(samples);
  await audioCtx.resume();
  const buffer = audioCtx.createBuffer(1, samples.length, sampleRate);
  buffer.copyToChannel(samples, 0);
  lullabySrc = audioCtx.createBufferSource();
  lullabySrc.buffer = buffer;
  lullabySrc.connect(audioCtx.destination);
  lullabySrc.start();
}

async function listen() {
  stopMic();
  mode = "listen";
  mark("listen");
  statusEl.textContent = "Listening";
  if (!audioCtx) {
    audioCtx = new AudioContext();
  }
  await audioCtx.resume();
  beginSession(audioCtx.sampleRate);
  let stream;
  try {
    stream = await navigator.mediaDevices.getUserMedia({
      audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false },
    });
  } catch {
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    } catch {
      statusEl.textContent = "Microphone permission needed";
      mode = "ready";
      mark("");
      return;
    }
  }
  micStream = stream;
  await audioCtx.audioWorklet.addModule("capture.js");
  worklet = new AudioWorkletNode(audioCtx, "capture");
  worklet.port.onmessage = (event) => onHop(event.data);
  micNode = audioCtx.createMediaStreamSource(stream);
  micNode.connect(worklet);
}

function downloadMidi() {
  const cap = 65536;
  const ptr = mod._malloc(cap);
  const n = lbMidi(ptr, cap);
  if (n < 0) {
    mod._free(ptr);
    return;
  }
  const bytes = new Uint8Array(mod.HEAPU8.subarray(ptr, ptr + n));
  mod._free(ptr);
  const blob = new Blob([bytes], { type: "audio/midi" });
  const link = document.createElement("a");
  link.href = URL.createObjectURL(blob);
  link.download = "lullabae.mid";
  link.click();
  URL.revokeObjectURL(link.href);
}

a4Input.addEventListener("input", () => {
  a4 = Number(a4Input.value);
  a4Label.textContent = `A = ${a4} Hz`;
  if (readyRate !== 0) {
    lbTuning(a4);
  }
});
lullabyBtn.addEventListener("click", () => {
  playLullaby().catch((err) => {
    statusEl.textContent = err && err.message ? err.message : "Could not play";
  });
});
listenBtn.addEventListener("click", () => {
  listen().catch((err) => {
    statusEl.textContent = err && err.message ? err.message : "Could not listen";
  });
});
saveBtn.addEventListener("click", downloadMidi);

if (new URLSearchParams(location.search).has("demo")) {
  mode = "lullaby";
  mark("lullaby");
  beginSession(48000);
  feedAll(renderPhrase(48000));
}

if ("serviceWorker" in navigator && location.protocol.startsWith("http")) {
  navigator.serviceWorker.register("sw.js").catch(() => {});
}
