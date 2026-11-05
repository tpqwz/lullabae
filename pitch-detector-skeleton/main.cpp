// Simple test / demo for PitchDetector
// Compile: g++ -std=c++17 -O2 -o pitchtest main.cpp PitchDetector.cpp Transcriber.cpp midi.cpp replay.cpp -lm
// Run:     ./pitchtest

#include "PitchDetector.h"
#include "Transcriber.h"
#include "midi.h"
#include "replay.h"
#include "note.h"

#include <iostream>
#include <vector>
#include <cmath>
#include <cstdlib>
#include <cstring>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

static int g_failed = 0;

static void expectNote(float hz, int midi, const char* name, int cents, float a4 = 440.0f)
{
    Note n = noteFromHz(hz, a4);
    if (n.midi != midi || n.cents != cents || std::strcmp(n.name, name) != 0) {
        std::cout << "FAIL: " << hz << " Hz at A4=" << a4
                  << " -> midi " << n.midi
                  << " \"" << n.name << "\" " << n.cents
                  << "c, expected midi " << midi << " \"" << name
                  << "\" " << cents << "c\n";
        g_failed = 1;
    }
}

static void expectNoNote(float hz, float a4 = 440.0f)
{
    Note n = noteFromHz(hz, a4);
    if (n.midi != -1 || n.cents != 0 || n.name[0] != '\0') {
        std::cout << "FAIL: " << hz << " Hz should not be a note, got midi "
                  << n.midi << " \"" << n.name << "\" " << n.cents << "c\n";
        g_failed = 1;
    }
}

static void expectMix(PitchDetector& detector, double sampleRate, int bufferSize,
                      float freq, float fundamental, float harmonic, const char* name)
{
    std::vector<float> samples(bufferSize);
    for (int i = 0; i < bufferSize; ++i) {
        double t = i / sampleRate;
        samples[i] = fundamental * std::sin(2.0 * M_PI * freq * t)
                + harmonic * std::sin(2.0 * M_PI * (freq * 2.0) * t);
    }
    float hz = detector.detect(samples);
    Note n = noteFromHz(hz);
    if (std::strcmp(n.name, name) != 0 || std::abs(n.cents) > 10) {
        std::cout << "FAIL: sine " << freq << " Hz detected " << hz
                  << " Hz as \"" << n.name << "\" " << n.cents
                  << "c, expected " << name << " within 10c\n";
        g_failed = 1;
    }
}

// Two simultaneous tones that are not harmonics of each other.
static void expectTwoPitches(PitchDetector& detector, double sampleRate, int bufferSize,
                             float lowHz, float lowAmp, float highHz, float highAmp,
                             const char* name)
{
    std::vector<float> samples(bufferSize);
    for (int i = 0; i < bufferSize; ++i) {
        double t = i / sampleRate;
        samples[i] = lowAmp * std::sin(2.0 * M_PI * lowHz * t)
                + highAmp * std::sin(2.0 * M_PI * highHz * t);
    }
    float hz = detector.detect(samples);
    Note n = noteFromHz(hz);
    if (std::strcmp(n.name, name) != 0 || std::abs(n.cents) > 40) {
        std::cout << "FAIL: melody over " << lowHz << " Hz detected " << hz
                  << " Hz as \"" << n.name << "\" " << n.cents
                  << "c, expected " << name << "\n";
        g_failed = 1;
    }
}

static void expectSine(PitchDetector& detector, double sampleRate, int bufferSize,
                       float freq, const char* name)
{
    expectMix(detector, sampleRate, bufferSize, freq, 0.5f, 0.0f, name);
}

static void expectWeakFundamental(PitchDetector& detector, double sampleRate, int bufferSize,
                                  float freq, const char* name)
{
    expectMix(detector, sampleRate, bufferSize, freq, 0.05f, 0.25f, name);
}

// Fundamental, octave, and a much louder twelfth. Still the fundamental.
static void expectThirdHarmonic(PitchDetector& detector, double sampleRate, int bufferSize,
                                float freq, const char* name)
{
    std::vector<float> samples(bufferSize);
    for (int i = 0; i < bufferSize; ++i) {
        double t = i / sampleRate;
        samples[i] = 0.05f * std::sin(2.0 * M_PI * freq * t)
                + 0.04f * std::sin(2.0 * M_PI * (freq * 2.0) * t)
                + 0.22f * std::sin(2.0 * M_PI * (freq * 3.0) * t);
    }
    float hz = detector.detect(samples);
    Note n = noteFromHz(hz);
    if (std::strcmp(n.name, name) != 0 || std::abs(n.cents) > 10) {
        std::cout << "FAIL: third " << freq << " Hz detected " << hz
                  << " Hz as \"" << n.name << "\" " << n.cents
                  << "c, expected " << name << " within 10c\n";
        g_failed = 1;
    }
}

int main()
{
    const double sampleRate = 44100.0;
    const int bufferSize = 2048;
    PitchDetector detector(sampleRate, bufferSize, 0.15f);

    // Generate a pure sine wave at 440 Hz (A4)
    const float targetFreq = 440.0f;
    std::vector<float> samples(bufferSize);
    for (int i = 0; i < bufferSize; ++i) {
        samples[i] = 0.5f * std::sin(2.0 * M_PI * targetFreq * i / sampleRate);
    }

    float detected = detector.detect(samples);

    Note heard = noteFromHz(detected);
    std::cout << "Target frequency:  " << targetFreq << " Hz\n";
    std::cout << "Detected frequency: " << detected << " Hz\n";
    std::cout << "Note: " << (heard.midi < 0 ? "---" : heard.name)
              << " " << heard.cents << "c\n";

    if (detected > 0.0f) {
        float cents = 1200.0f * std::log2(detected / targetFreq);
        std::cout << "Error: " << cents << " cents\n";
    } else {
        std::cout << "No pitch detected\n";
    }

    // Quick second test at 220 Hz
    const float target2 = 220.0f;
    for (int i = 0; i < bufferSize; ++i) {
        samples[i] = 0.5f * std::sin(2.0 * M_PI * target2 * i / sampleRate);
    }
    float detected2 = detector.detect(samples);
    std::cout << "\nTarget frequency:  " << target2 << " Hz\n";
    std::cout << "Detected frequency: " << detected2 << " Hz\n";

    // Worked by hand from A4 = 440 Hz, equal temperament.
    // 442.5488940699 is 10 cents sharp of A4.
    // 257.1309946835 is 30 cents flat of C4 (261.6255653006 Hz).
    // 460.8094140411 is 20 cents flat of A#4.
    expectNote(440.0f, 69, "A4", 0);
    expectNote(220.0f, 57, "A3", 0);
    expectNote(880.0f, 81, "A5", 0);
    expectNote(261.6255653006f, 60, "C4", 0);
    expectNote(442.5488940699f, 69, "A4", 10);
    expectNote(257.1309946835f, 60, "C4", -30);
    expectNote(460.8094140411f, 70, "A#4", -20);
    // Same naming with a different concert pitch. 440 Hz is 32 cents sharp of A=432.
    expectNote(432.0f, 69, "A4", 0, 432.0f);
    expectNote(440.0f, 69, "A4", 32, 432.0f);
    expectNote(415.0f, 69, "A4", 0, 415.0f);
    expectNote(434.5025634768f, 69, "A4", 10, 432.0f);
    expectNoNote(0.0f);
    expectNoNote(-12.0f);
    expectNoNote(20000.0f);
    expectNoNote(440.0f, 0.0f);

    expectSine(detector, sampleRate, bufferSize, 110.0f, "A2");
    expectSine(detector, sampleRate, bufferSize, 220.0f, "A3");
    expectSine(detector, sampleRate, bufferSize, 261.6255653006f, "C4");
    expectSine(detector, sampleRate, bufferSize, 329.6275569129f, "E4");
    expectSine(detector, sampleRate, bufferSize, 440.0f, "A4");
    expectSine(detector, sampleRate, bufferSize, 466.1637615181f, "A#4");
    expectSine(detector, sampleRate, bufferSize, 880.0f, "A5");
    // A low tone whose second harmonic is louder than the fundamental.
    // 0.05 at the fundamental, 0.25 at the octave. Still that low note.
    expectWeakFundamental(detector, sampleRate, bufferSize, 82.4068892282f, "E2");
    expectWeakFundamental(detector, sampleRate, bufferSize, 110.0f, "A2");
    expectThirdHarmonic(detector, sampleRate, bufferSize, 97.998859f, "G2");
    // A high flute note can show a faint dip three times too low. It stays the high note.
    expectTwoPitches(detector, sampleRate, bufferSize,
                     109.2f, 0.04f, 327.6f, 0.40f, "E4");
    // A louder bass under a tune that is not its harmonic. The tune is the note.
    expectTwoPitches(detector, sampleRate, bufferSize,
                     92.4986056779f, 0.30f, 391.9954359817f, 0.12f, "G4");
    expectTwoPitches(detector, sampleRate, bufferSize,
                     110.0f, 0.25f, 261.6255653006f, 0.15f, "C4");

    // Half a second of E4, then a rest, is one open high-e note.
    // A loud hit with no pitch is a tap. A 40 ms blip is not a note.
    {
        const double hop = 0.02;
        auto feed = [&](Transcriber& t, float hz, float rms, double seconds,
                        TranscriptEvent* ev, int n, int max) {
            int frames = static_cast<int>(seconds / hop + 0.5);
            for (int i = 0; i < frames && n < max; ++i) {
                n += t.push(hz, rms, hop, ev + n, max - n);
            }
            return n;
        };
        TranscriptEvent ev[8];
        Transcriber t;
        t.reset(440.0f);
        int n = feed(t, 329.6275569129f, 0.12f, 0.50, ev, 0, 8);
        n = feed(t, 0.0f, 0.0f, 0.55, ev, n, 8);
        if (n != 1 || ev[0].tap != 0 || std::strcmp(ev[0].name, "E4") != 0
                || ev[0].guitarString != 1 || ev[0].fret != 0
                || ev[0].durationMs < 450 || ev[0].durationMs > 550) {
            std::cout << "FAIL: E4 phrase -> " << n << " events";
            if (n > 0) {
                std::cout << " first " << ev[0].name << " tap " << ev[0].tap
                          << " string " << ev[0].guitarString << " fret " << ev[0].fret
                          << " " << ev[0].durationMs << "ms";
            }
            std::cout << "\n";
            g_failed = 1;
        }

        t.reset(440.0f);
        n = feed(t, 329.6275569129f, 0.12f, 0.40, ev, 0, 8);
        n = feed(t, 391.9954359817f, 0.12f, 0.40, ev, n, 8);
        n = feed(t, 0.0f, 0.0f, 0.55, ev, n, 8);
        if (n != 2 || std::strcmp(ev[0].name, "E4") != 0 || std::strcmp(ev[1].name, "G4") != 0
                || ev[1].guitarString != 1 || ev[1].fret != 3) {
            std::cout << "FAIL: E4 then G4 -> " << n << " events\n";
            g_failed = 1;
        }

        t.reset(440.0f);
        n = feed(t, 261.6255653006f, 0.12f, 0.30, ev, 0, 8);
        n = feed(t, 0.0f, 0.0f, 0.55, ev, n, 8);
        if (n != 1 || ev[0].guitarString != 2 || ev[0].fret != 1 || std::strcmp(ev[0].name, "C4") != 0) {
            std::cout << "FAIL: C4 tab -> string " << (n > 0 ? ev[0].guitarString : -1)
                      << " fret " << (n > 0 ? ev[0].fret : -1) << "\n";
            g_failed = 1;
        }

        t.reset(440.0f);
        n = feed(t, 82.4068892282f, 0.12f, 0.30, ev, 0, 8);
        n = feed(t, 0.0f, 0.0f, 0.55, ev, n, 8);
        if (n != 1 || ev[0].guitarString != 6 || ev[0].fret != 0) {
            std::cout << "FAIL: E2 tab -> string " << (n > 0 ? ev[0].guitarString : -1)
                      << " fret " << (n > 0 ? ev[0].fret : -1) << "\n";
            g_failed = 1;
        }

        t.reset(440.0f);
        n = feed(t, 0.0f, 0.01f, 0.20, ev, 0, 8);
        n = feed(t, 0.0f, 0.45f, 0.04, ev, n, 8);
        n = feed(t, 0.0f, 0.01f, 0.20, ev, n, 8);
        if (n != 1 || ev[0].tap != 1) {
            std::cout << "FAIL: lap tap -> " << n << " events\n";
            g_failed = 1;
        }

        // One loud hop and then a pitch is the start of a note, not a beat.
        t.reset(440.0f);
        n = feed(t, 0.0f, 0.45f, 0.02, ev, 0, 8);
        n = feed(t, 329.6275569129f, 0.12f, 0.40, ev, n, 8);
        n = feed(t, 0.0f, 0.0f, 0.55, ev, n, 8);
        int onsetTaps = 0;
        for (int i = 0; i < n; ++i) {
            if (ev[i].tap) {
                onsetTaps += 1;
            }
        }
        if (n != 1 || onsetTaps != 0 || std::strcmp(ev[0].name, "E4") != 0) {
            std::cout << "FAIL: note onset -> " << n << " events, " << onsetTaps << " taps\n";
            g_failed = 1;
        }

        t.reset(440.0f);
        n = feed(t, 329.6275569129f, 0.12f, 0.04, ev, 0, 8);
        n = feed(t, 0.0f, 0.0f, 0.55, ev, n, 8);
        if (n != 0) {
            std::cout << "FAIL: 40 ms blip produced " << n << " events\n";
            g_failed = 1;
        }

        // A short hole in the middle of a note is not a lap tap.
        t.reset(440.0f);
        n = feed(t, 329.6275569129f, 0.16f, 0.30, ev, 0, 8);
        n = feed(t, 0.0f, 0.20f, 0.04, ev, n, 8);
        n = feed(t, 329.6275569129f, 0.16f, 0.30, ev, n, 8);
        n = feed(t, 0.0f, 0.0f, 0.55, ev, n, 8);
        int taps = 0;
        for (int i = 0; i < n; ++i) {
            if (ev[i].tap) {
                taps += 1;
            }
        }
        if (n != 1 || taps != 0 || std::strcmp(ev[0].name, "E4") != 0) {
            std::cout << "FAIL: pitch hole -> " << n << " events, " << taps << " taps\n";
            g_failed = 1;
        }

        // A quiet room, then a soft E4 under the old fixed 0.02 gate.
        t.reset(440.0f);
        n = feed(t, 0.0f, 0.004f, 0.50, ev, 0, 8);
        n = feed(t, 329.6275569129f, 0.012f, 0.40, ev, n, 8);
        n = feed(t, 0.0f, 0.0f, 0.55, ev, n, 8);
        if (n != 1 || ev[0].tap != 0 || std::strcmp(ev[0].name, "E4") != 0) {
            std::cout << "FAIL: soft hum -> " << n << " events";
            if (n > 0) {
                std::cout << " first " << ev[0].name << " tap " << ev[0].tap;
            }
            std::cout << "\n";
            g_failed = 1;
        }

        // Just over the starting gate, with no quiet lead-in. The note's own
        // level must not raise the floor and cancel the attack.
        t.reset(440.0f);
        n = feed(t, 329.6275569129f, 0.021f, 0.50, ev, 0, 8);
        n = feed(t, 0.0f, 0.0f, 0.55, ev, n, 8);
        if (n != 1 || ev[0].tap != 0 || std::strcmp(ev[0].name, "E4") != 0
                || ev[0].durationMs < 450 || ev[0].durationMs > 550) {
            std::cout << "FAIL: near-gate hum -> " << n << " events";
            if (n > 0) {
                std::cout << " first " << ev[0].name << " " << ev[0].durationMs << "ms";
            }
            std::cout << "\n";
            g_failed = 1;
        }

        // A slow wobble that crosses the E4/F4 rounding line is still one note.
        // Center is 40 cents sharp of E4, swing is ±30 cents, twice a second.
        t.reset(440.0f);
        n = 0;
        for (int i = 0; i < 60; ++i) {
            double wobble = 40.0 + 30.0 * std::sin(2.0 * M_PI * 2.0 * (i * hop));
            float hz = 329.6275569129f * static_cast<float>(std::pow(2.0, wobble / 1200.0));
            n += t.push(hz, 0.12f, hop, ev + n, 8 - n);
        }
        n = feed(t, 0.0f, 0.0f, 0.55, ev, n, 8);
        if (n != 1 || ev[0].tap != 0 || std::strcmp(ev[0].name, "E4") != 0
                || ev[0].cents < 25 || ev[0].cents > 55) {
            std::cout << "FAIL: boundary wobble -> " << n << " events";
            if (n > 0) {
                std::cout << " first " << ev[0].name << " " << ev[0].cents << "c";
            }
            std::cout << "\n";
            g_failed = 1;
        }

        // A real step to the next semitone is still a new note.
        t.reset(440.0f);
        n = feed(t, 329.6275569129f, 0.12f, 0.35, ev, 0, 8);
        n = feed(t, 349.2282314330f, 0.12f, 0.35, ev, n, 8);
        n = feed(t, 0.0f, 0.0f, 0.55, ev, n, 8);
        if (n != 2 || std::strcmp(ev[0].name, "E4") != 0 || std::strcmp(ev[1].name, "F4") != 0) {
            std::cout << "FAIL: E4 then F4 -> " << n << " events\n";
            g_failed = 1;
        }

        // A scoop from the semitone below into D4 is the D, not two cards.
        t.reset(440.0f);
        n = feed(t, 277.1826309769f, 0.12f, 0.28, ev, 0, 8);
        n = feed(t, 293.6647679174f, 0.12f, 0.45, ev, n, 8);
        n = feed(t, 0.0f, 0.0f, 0.55, ev, n, 8);
        if (n != 1 || ev[0].tap != 0 || std::strcmp(ev[0].name, "D4") != 0) {
            std::cout << "FAIL: scoop into D4 -> " << n << " events";
            if (n > 0) {
                std::cout << " first " << ev[0].name;
            }
            std::cout << "\n";
            g_failed = 1;
        }

        // Four detached E4s stay four notes.
        t.reset(440.0f);
        n = 0;
        for (int i = 0; i < 4; ++i) {
            n = feed(t, 329.6275569129f, 0.12f, 0.16, ev, n, 8);
            n = feed(t, 0.0f, 0.0f, 0.10, ev, n, 8);
        }
        n = feed(t, 0.0f, 0.0f, 0.55, ev, n, 8);
        int e4s = 0;
        for (int i = 0; i < n; ++i) {
            if (!ev[i].tap && std::strcmp(ev[i].name, "E4") == 0) {
                e4s += 1;
            }
        }
        if (e4s != 4) {
            std::cout << "FAIL: staccato E4 -> " << e4s << " notes (" << n << " events)\n";
            g_failed = 1;
        }

        // A short bass note between two notes of the tune is the other part.
        t.reset(440.0f);
        n = feed(t, 261.6255653006f, 0.12f, 0.35, ev, 0, 8);
        n = feed(t, 97.998859f, 0.18f, 0.18, ev, n, 8);
        n = feed(t, 261.6255653006f, 0.12f, 0.35, ev, n, 8);
        n = feed(t, 0.0f, 0.0f, 0.55, ev, n, 8);
        int bass = 0;
        int melody = 0;
        for (int i = 0; i < n; ++i) {
            if (ev[i].tap) {
                continue;
            }
            if (ev[i].midi <= 45) {
                bass += 1;
            }
            if (std::strcmp(ev[i].name, "C4") == 0) {
                melody += 1;
            }
        }
        if (bass != 0 || melody < 1) {
            std::cout << "FAIL: bass under the tune -> " << n << " events, bass " << bass
                      << " melody " << melody << "\n";
            g_failed = 1;
        }
    }

    // One C4 quarter note, from a standing start, is this exact file.
    {
        TranscriptEvent ev{};
        ev.midi = 60;
        ev.durationMs = 500;
        std::strcpy(ev.name, "C4");
        int start = 0;
        unsigned char out[64];
        int n = writeMidi(&ev, &start, 1, out, 64);
        static const unsigned char expect[] = {
            0x4D, 0x54, 0x68, 0x64, 0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00, 0x01, 0x01, 0xE0,
            0x4D, 0x54, 0x72, 0x6B, 0x00, 0x00, 0x00, 0x14,
            0x00, 0xFF, 0x51, 0x03, 0x07, 0xA1, 0x20,
            0x00, 0x90, 0x3C, 0x64,
            0x83, 0x60, 0x80, 0x3C, 0x00,
            0x00, 0xFF, 0x2F, 0x00,
        };
        if (n != (int)sizeof expect || std::memcmp(out, expect, sizeof expect) != 0) {
            std::cout << "FAIL: C4 quarter -> " << n << " bytes\n";
            g_failed = 1;
        }
    }

    // A tap is a side stick on channel 10, not a pitched note.
    {
        TranscriptEvent ev{};
        ev.tap = 1;
        ev.durationMs = 120;
        int start = 0;
        unsigned char out[64];
        int n = writeMidi(&ev, &start, 1, out, 64);
        static const unsigned char expect[] = {
            0x4D, 0x54, 0x68, 0x64, 0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00, 0x01, 0x01, 0xE0,
            0x4D, 0x54, 0x72, 0x6B, 0x00, 0x00, 0x00, 0x13,
            0x00, 0xFF, 0x51, 0x03, 0x07, 0xA1, 0x20,
            0x00, 0x99, 0x25, 0x64,
            0x73, 0x89, 0x25, 0x00,
            0x00, 0xFF, 0x2F, 0x00,
        };
        if (n != (int)sizeof expect || std::memcmp(out, expect, sizeof expect) != 0) {
            std::cout << "FAIL: tap -> " << n << " bytes\n";
            g_failed = 1;
        }
    }

    // Nothing heard is still a file a player can open.
    {
        unsigned char out[64];
        int n = writeMidi(nullptr, nullptr, 0, out, 64);
        static const unsigned char expect[] = {
            0x4D, 0x54, 0x68, 0x64, 0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00, 0x01, 0x01, 0xE0,
            0x4D, 0x54, 0x72, 0x6B, 0x00, 0x00, 0x00, 0x0B,
            0x00, 0xFF, 0x51, 0x03, 0x07, 0xA1, 0x20,
            0x00, 0xFF, 0x2F, 0x00,
        };
        if (n != (int)sizeof expect || std::memcmp(out, expect, sizeof expect) != 0) {
            std::cout << "FAIL: empty take -> " << n << " bytes\n";
            g_failed = 1;
        }
    }

    // A note that starts first is written first, even if the tap was stored earlier.
    {
        TranscriptEvent ev[2]{};
        ev[0].tap = 1;
        ev[0].durationMs = 120;
        ev[1].midi = 60;
        ev[1].durationMs = 500;
        int starts[2] = {1000, 0};
        unsigned char out[128];
        int n = writeMidi(ev, starts, 2, out, 128);
        int noteOn = -1;
        int tapOn = -1;
        for (int i = 0; i + 2 < n; ++i) {
            if (out[i] == 0x90 && noteOn < 0) {
                noteOn = i;
            }
            if (out[i] == 0x99 && tapOn < 0) {
                tapOn = i;
            }
        }
        if (n < 0 || noteOn < 0 || tapOn < 0 || noteOn > tapOn) {
            std::cout << "FAIL: note should precede a later tap (" << noteOn << ", " << tapOn << ")\n";
            g_failed = 1;
        }
    }

    {
        TranscriptEvent ev{};
        ev.midi = 60;
        ev.durationMs = 500;
        int start = 0;
        unsigned char out[8];
        if (writeMidi(&ev, &start, 1, out, 8) != -1) {
            std::cout << "FAIL: a short buffer should be refused\n";
            g_failed = 1;
        }
    }

    // Replay sings the written notes, and stays quiet in the rests.
    {
        ReplayTone tones[2]{};
        tones[0].midi = 64;
        tones[0].cents = 0;
        tones[0].startMs = 0;
        tones[0].durationMs = 500;
        tones[1].midi = 67;
        tones[1].cents = 0;
        tones[1].startMs = 800;
        tones[1].durationMs = 400;
        float e4 = replayHzAt(tones, 2, 440.0f, 100);
        float gap = replayHzAt(tones, 2, 440.0f, 500);
        float rest = replayHzAt(tones, 2, 440.0f, 600);
        float g4 = replayHzAt(tones, 2, 440.0f, 800);
        if (std::fabs(e4 - 329.6275569129f) > 0.05f || gap != 0.0f || rest != 0.0f
                || std::fabs(g4 - 391.9954359817f) > 0.05f) {
            std::cout << "FAIL: replay phrase " << e4 << " " << gap << " " << rest << " " << g4 << "\n";
            g_failed = 1;
        }
        tones[0].cents = 14;
        float sharp = replayHzAt(tones, 1, 440.0f, 10);
        if (std::fabs(sharp - 332.3039688238f) > 0.05f) {
            std::cout << "FAIL: replay E4 +14c -> " << sharp << "\n";
            g_failed = 1;
        }
        tones[0].cents = 0;
        float low = replayHzAt(tones, 1, 432.0f, 10);
        if (std::fabs(low - 323.6343286054f) > 0.05f) {
            std::cout << "FAIL: replay E4 at A=432 -> " << low << "\n";
            g_failed = 1;
        }
        // A note that starts later replaces one still sounding.
        tones[0].durationMs = 500;
        tones[1].startMs = 400;
        tones[1].durationMs = 400;
        float over = replayHzAt(tones, 2, 440.0f, 450);
        if (std::fabs(over - 391.9954359817f) > 0.05f) {
            std::cout << "FAIL: overlapping replay -> " << over << "\n";
            g_failed = 1;
        }
        if (replayHzAt(nullptr, 0, 440.0f, 0) != 0.0f) {
            std::cout << "FAIL: empty replay should be silent\n";
            g_failed = 1;
        }
    }

    if (g_failed == 0) {
        std::cout << "\nAll checks passed\n";
    }
    return g_failed;
}
