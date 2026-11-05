// Live pitch display. Reads mono 16-bit little-endian PCM on stdin.
//
//   arecord -f S16_LE -r 48000 -c 1 -t raw | ./hum
//   ./hum 44100 < recording.raw
//   ./hum 48000 432          # concert A at 432 Hz

#include "PitchDetector.h"
#include "note.h"

#include <cstdint>
#include <cstdio>
#include <cstdlib>

int main(int argc, char** argv)
{
    double sampleRate = 48000.0;
    float a4 = 440.0f;
    if (argc > 1) {
        char* end = nullptr;
        sampleRate = std::strtod(argv[1], &end);
        if (end == argv[1] || sampleRate < 8000.0 || sampleRate > 192000.0) {
            std::fprintf(stderr, "usage: hum [sample-rate] [a4-hz]\nreads mono s16le PCM on stdin\n");
            return 2;
        }
    }
    if (argc > 2) {
        char* end = nullptr;
        a4 = std::strtof(argv[2], &end);
        if (end == argv[2] || !(a4 > 0.0f) || a4 > 20000.0f) {
            std::fprintf(stderr, "usage: hum [sample-rate] [a4-hz]\nreads mono s16le PCM on stdin\n");
            return 2;
        }
    }

    const int bufferSize = 2048;
    PitchDetector detector(sampleRate, bufferSize, 0.15f);

    std::int16_t raw[2048];
    float samples[2048];

    while (std::fread(raw, sizeof(std::int16_t), bufferSize, stdin) == static_cast<size_t>(bufferSize)) {
        for (int i = 0; i < bufferSize; ++i) {
            samples[i] = static_cast<float>(raw[i]) / 32768.0f;
        }

        float hz = detector.detect(samples, bufferSize);
        Note note = noteFromHz(hz, a4);
        if (note.midi < 0) {
            std::printf("---\n");
        } else {
            std::printf("%s %+dc %.1fHz\n", note.name, note.cents, hz);
        }
        std::fflush(stdout);
    }

    return 0;
}
