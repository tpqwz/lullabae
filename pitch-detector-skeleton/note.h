#pragma once

#include <cmath>
#include <cstdio>

// Nearest equal-tempered note for a frequency in Hz.
// a4 is the tuning pitch, MIDI 69. It defaults to 440 Hz.
// midi is -1 when hz is not a pitch in the MIDI range. name is empty in that case.
struct Note {
    int midi;
    int cents;
    char name[8];
};

inline Note noteFromHz(float hz, float a4 = 440.0f) {
    Note n;
    n.midi = -1;
    n.cents = 0;
    n.name[0] = '\0';

    if (!(hz > 0.0f) || !std::isfinite(hz) || !(a4 > 0.0f) || !std::isfinite(a4)) {
        return n;
    }

    // Position on the MIDI scale, then the nearest key and the cents off it.
    const double semitone = 69.0 + 12.0 * std::log2(static_cast<double>(hz) / static_cast<double>(a4));
    const long midi = std::lround(semitone);
    if (midi < 0 || midi > 127) {
        return n;
    }

    n.midi = static_cast<int>(midi);
    n.cents = static_cast<int>(std::lround((semitone - static_cast<double>(midi)) * 100.0));

    static const char* names[12] = {
        "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"
    };
    std::snprintf(n.name, sizeof n.name, "%s%d",
                  names[n.midi % 12], n.midi / 12 - 1);
    return n;
}
