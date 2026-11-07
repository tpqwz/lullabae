#pragma once

#include <vector>
#include <cmath>
#include <algorithm>
#include <limits>

/**
 * Minimal monophonic pitch detector using the YIN algorithm.
 * Suitable as a starting point for humming / pitch trainer apps.
 *
 * Usage:
 *   PitchDetector detector(44100.0, 2048);
 *   float freq = detector.detect(samples, numSamples);
 *   if (freq > 0) { ... }
 */
class PitchDetector {
public:
    /**
     * @param sampleRate   Audio sample rate in Hz (e.g. 44100 or 48000)
     * @param bufferSize   Analysis window size (power of 2 recommended, 1024–4096)
     * @param threshold    YIN threshold. 0.35 still hears a hum in room noise.
     */
    PitchDetector(double sampleRate,
                  int bufferSize = 2048,
                  float threshold = 0.35f);

    /**
     * Detect fundamental frequency from a buffer of mono float samples in [-1, 1].
     * @return Frequency in Hz, or 0 if no reliable pitch found.
     */
    float detect(const float* samples, int numSamples);

    // Convenience overload
    float detect(const std::vector<float>& samples) {
        return detect(samples.data(), static_cast<int>(samples.size()));
    }

    void setSampleRate(double sampleRate) { sampleRate_ = sampleRate; }
    void setThreshold(float threshold) { threshold_ = threshold; }

    double getSampleRate() const { return sampleRate_; }
    int getBufferSize() const { return bufferSize_; }

private:
    double sampleRate_;
    int bufferSize_;
    float threshold_;

    // Reused so detect() does not allocate on the audio thread.
    std::vector<float> yinBuffer_;
    std::vector<float> upper_;

    float analyze(const float* samples, int numSamples);

    void computeDifferenceFunction(const float* samples, int n);
    void cumulativeMeanNormalizedDifference();
    int absoluteThreshold(const float* samples, int n) const;
    float parabolicInterpolation(int tau) const;
};
