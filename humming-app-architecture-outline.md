# lullabae — Architecture Outline
## Humming capture / pitch trainer  |  Shared C++ DSP core + native UIs, or JUCE

**Goal**: Real-time microphone capture of humming/melody or rhythm taps → pitch detection → transcription into notes/chords/tabs + live pitch training feedback (cents deviation, exercises).

---

### What is JUCE?

**JUCE** (Jules’ Utility Class Extensions) is a mature, open-source C++ framework built specifically for high-performance audio applications and plugins.

- Created by Julian Storer (from the Tracktion DAW).
- Industry standard: used by companies such as Eventide, Arturia, Ableton-related tools, Fender, etc.
- Excellent low-latency audio device handling, MIDI, DSP modules (FFT, filters, oscillators), audio file I/O.
- Targets desktop (Windows/macOS/Linux) **and mobile** (iOS + Android).
- Can be used for the full app (including its own UI system) or just as a library for the audio engine while you use native UIs.
- Has community modules for pitch detection (YIN, McLeod Pitch Method, etc.).

**When to choose JUCE**: You want to stay mostly in C++, value battle-tested audio infrastructure, and are comfortable with a steeper C++ learning curve. Great for serious music tools.

---

### Recommended Paths

#### Option A: Shared C++ DSP Core + Native UIs (Flexible, “closer to the metal”)
1. **Core library** (pure portable C++):
   - Pitch detection (YIN or pYIN recommended for monophonic humming).
   - Onset detection, note quantization, simple chord/tab heuristics.
   - Thread-safe ring buffer / callback interface.
2. **Android**:
   - UI: Kotlin + Jetpack Compose (or XML).
   - Low-latency audio: Oboe (Google’s recommended library) or AAudio.
   - Call C++ via JNI / NDK.
3. **iOS**:
   - UI: SwiftUI or UIKit.
   - Low-latency audio: AVAudioEngine + Audio Unit / Core Audio.
   - Call C++ via Objective-C++ bridge.
4. **Desktop** (optional): Same C++ core + Qt, native, or JUCE UI.

**Pros**: Best possible latency, native look & feel, reuse of the exact same DSP everywhere.  
**Cons**: More platform-specific glue code for audio I/O and UI.

#### Option B: Full (or mostly) JUCE
- Use JUCE’s `AudioDeviceManager`, `AudioSource`, DSP modules, and pitch detection helpers.
- Build UI either with JUCE’s Component system or embed the engine in a native UI app.
- Projucer or CMake generates Android Studio / Xcode projects.

**Pros**: Single codebase for a large part of the app, mature audio abstractions, plugin potential later.  
**Cons**: JUCE UI is less “native-feeling” than SwiftUI/Compose unless you invest effort; still need platform-specific setup.

---

### Suggested Starting Architecture (Shared C++ path)

```
lullabae/
├── dsp-core/                  # Portable C++ library
│   ├── include/
│   │   ├── PitchDetector.h    # YIN / pYIN interface
│   │   ├── Transcriber.h      # Pitch contour → notes / MIDI
│   │   └── PitchTrainer.h     # Cents deviation, reference tones
│   ├── src/
│   │   ├── PitchDetector.cpp
│   │   └── ...
│   └── CMakeLists.txt
├── android/                   # Kotlin + Compose + Oboe + JNI
├── ios/                       # SwiftUI + AVAudioEngine + ObjC++
└── docs/                      # This outline + API contracts
```

**Key technical pieces**:
- **Pitch algorithm**: YIN (or aubio’s implementation / a simple port). For better accuracy, pYIN.
- **Latency target**: < 50–80 ms end-to-end for comfortable training feedback.
- **Audio thread**: Never allocate, lock, or do heavy work on the real-time callback. Push pitch results to a lock-free queue for the UI thread.
- **Transcription**: Track pitch over time → segment into notes (onset + duration + quantized pitch) → optional simple chord detection / guitar tab mapping.
- **Pitch trainer**: Display current note, cents sharp/flat, visual gauge, reference drone or exercise sequences.

---

### What Can Be Done Here vs. Grok CLI / Local

- **Here (this environment)**: We can generate a clean C++ pitch-detection skeleton (YIN-style), CMake structure, and detailed API notes. Full mobile builds (Android NDK + Oboe, Xcode, JUCE Projucer) are not practical.
- **Recommended next step**: Take this outline + any starter C++ code to **Grok CLI** (or your local machine with Android Studio / Xcode) and continue development there.

---

### Immediate Next Steps

1. Decide: Shared C++ + native UIs **or** JUCE-first.
2. Prototype the pitch detector in isolation (can start with the C++ skeleton).
3. Wire minimal microphone → pitch → display on one platform first (Android with Oboe is often easier to iterate).
4. Add transcription and trainer modes once pitch tracking is solid.

---

*Generated as a starting point for further development with Grok CLI or local tooling.*
