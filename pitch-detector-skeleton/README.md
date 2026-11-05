# Minimal YIN Pitch Detector (C++)

Self-contained monophonic pitch detector using the YIN algorithm.
No external dependencies beyond the C++ standard library.

## Files

- `PitchDetector.h` / `PitchDetector.cpp` — the detector
- `main.cpp` — simple sine-wave test
- This README

## Compile & Test (Linux / macOS)

```bash
g++ -std=c++17 -O2 -o pitchtest main.cpp PitchDetector.cpp Transcriber.cpp midi.cpp replay.cpp -lm
./pitchtest
```

You should see 440 Hz reported as A4, and the checks at the end should pass.

`note.h` turns a frequency into the nearest equal-tempered note and the cents off it. The tuning pitch defaults to A4 = 440 Hz. Pass another frequency as the second argument to `noteFromHz` — 432 and 415 are the usual other choices.

Live microphone display, mono 16-bit little-endian PCM on stdin:

```bash
g++ -std=c++17 -O2 -o hum hum.cpp PitchDetector.cpp -lm
arecord -f S16_LE -r 48000 -c 1 -t raw | ./hum
```

Each analysis window prints a line such as `A4 +3c 440.8Hz`, or `---` when no pitch is found. Pass a sample rate if the stream is not 48000, and a tuning pitch if A is not 440: `./hum 48000 432`.

## Integration Notes

### Flutter FFI
1. Compile the C++ into a shared library (`.so` / `.dylib` / `.dll`).
2. Use `dart:ffi` to call `detect`.
3. Feed microphone buffers from a package such as `record` or a custom platform channel that gives you raw PCM floats.

### Android
The emulator app in `../android` links this detector. A hummed note is written down when it ends, with a guitar fret under it. A loud hit with no pitch is written as a tap. The lullaby feeds that same path so the line appears without a microphone. Listen uses the mic. The slider sets the tuning pitch from 400 to 480 Hz.

```bash
../android/run.sh
```

That builds `android/build/lullabae.apk`, installs it on the running emulator, and launches it.

### Browser

The same core compiles to WebAssembly. The page listens in the browser, writes finished notes with a fret, treats a pitchless hit as a tap, and downloads a MIDI file. Audio is not uploaded.

```bash
../web/build.sh
python3 ../web/serve.py
```

Open `http://127.0.0.1:8731`. The second open works without a network once the service worker has cached the page.

### iOS (AVAudioEngine)
- Same idea: pass float buffers from the audio tap into `detect`.

## Limitations of this skeleton

- Pure YIN (not pYIN). Works well for clean monophonic humming/singing.
- Notes and taps are segmented in `Transcriber`. One pitch at a time, so a chord is not inferred.
- Threshold and frequency range are simple; tune for your use case.
- Not optimized with SIMD or FFT-based autocorrelation (fine for a first version).

## Next steps you can take with Grok CLI

1. Note quantizer (Hz → MIDI note + cents) — `noteFromHz` in `note.h`.
2. Add a ring buffer so the audio thread only pushes samples and the analysis runs elsewhere.
3. Wrap it in a Flutter FFI plugin.
4. Add basic transcription (onset + duration + quantized pitch).

This is intentionally minimal so you can extend it easily.
