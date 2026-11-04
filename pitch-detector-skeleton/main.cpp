// Simple test / demo for PitchDetector
// Compile: g++ -std=c++17 -O2 -o pitchtest main.cpp PitchDetector.cpp -lm
// Run:     ./pitchtest

#include "PitchDetector.h"

#include <iostream>
#include <vector>
#include <cmath>
#include <cstdlib>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

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

    std::cout << "Target frequency:  " << targetFreq << " Hz\n";
    std::cout << "Detected frequency: " << detected << " Hz\n";

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

    return (detected > 400 && detected < 480) ? 0 : 1;
}
