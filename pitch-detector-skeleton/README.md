# Minimal YIN Pitch Detector (C++)

Self-contained monophonic pitch detector using the YIN algorithm.
No external dependencies beyond the C++ standard library.

## Files

- `PitchDetector.h` / `PitchDetector.cpp` — the detector
- `main.cpp` — simple sine-wave test
- This README

## Compile & Test (Linux / macOS)

```bash
g++ -std=c++17 -O2 -o pitchtest main.cpp PitchDetector.cpp -lm
./pitchtest
```

You should see detected frequencies very close to 440 Hz and 220 Hz.

## Integration Notes

### Flutter FFI
1. Compile the C++ into a shared library (`.so` / `.dylib` / `.dll`).
2. Use `dart:ffi` to call `detect`.
3. Feed microphone buffers from a package such as `record` or a custom platform channel that gives you raw PCM floats.

### Android (Oboe + JNI)
- Call `detect` from the Oboe audio callback (or a dedicated analysis thread).
- Keep the `PitchDetector` instance alive; it reuses internal buffers.

### iOS (AVAudioEngine)
- Same idea: pass float buffers from the audio tap into `detect`.

## Limitations of this skeleton

- Pure YIN (not pYIN). Works well for clean monophonic humming/singing.
- No onset detection or note segmentation yet (add those next for transcription).
- Threshold and frequency range are simple; tune for your use case.
- Not optimized with SIMD or FFT-based autocorrelation (fine for a first version).

## Next steps you can take with Grok CLI

1. Add a simple note quantizer (Hz → MIDI note + cents).
2. Add a ring buffer so the audio thread only pushes samples and the analysis runs elsewhere.
3. Wrap it in a Flutter FFI plugin.
4. Add basic transcription (onset + duration + quantized pitch).

This is intentionally minimal so you can extend it easily.
