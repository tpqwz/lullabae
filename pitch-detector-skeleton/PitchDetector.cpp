#include "PitchDetector.h"

#include <cassert>

PitchDetector::PitchDetector(double sampleRate, int bufferSize, float threshold)
    : sampleRate_(sampleRate)
    , bufferSize_(bufferSize)
    , threshold_(threshold)
{
    // YIN needs at least half the buffer for the lag range
    int half = bufferSize_ / 2;
    yinBuffer_.resize(half);
    window_.resize(bufferSize_);
}

float PitchDetector::detect(const float* samples, int numSamples)
{
    if (numSamples < bufferSize_ || samples == nullptr) {
        return 0.0f;
    }

    // Use the most recent bufferSize samples
    const float* input = samples + (numSamples - bufferSize_);

    // Optional simple windowing (Hann) to reduce edge effects
    const float twoPi = 2.0f * static_cast<float>(M_PI);
    for (int i = 0; i < bufferSize_; ++i) {
        float w = 0.5f * (1.0f - std::cos(twoPi * i / (bufferSize_ - 1)));
        window_[i] = input[i] * w;
    }

    computeDifferenceFunction(window_.data(), bufferSize_);
    cumulativeMeanNormalizedDifference();

    int tau = absoluteThreshold();
    if (tau < 0) {
        return 0.0f; // no pitch found
    }

    float betterTau = parabolicInterpolation(tau);
    if (betterTau <= 0.0f) {
        return 0.0f;
    }

    return static_cast<float>(sampleRate_ / betterTau);
}

void PitchDetector::computeDifferenceFunction(const float* samples, int n)
{
    int half = n / 2;
    std::fill(yinBuffer_.begin(), yinBuffer_.end(), 0.0f);

    for (int tau = 1; tau < half; ++tau) {
        float sum = 0.0f;
        for (int i = 0; i < half; ++i) {
            float delta = samples[i] - samples[i + tau];
            sum += delta * delta;
        }
        yinBuffer_[tau] = sum;
    }
}

void PitchDetector::cumulativeMeanNormalizedDifference()
{
    int half = static_cast<int>(yinBuffer_.size());
    yinBuffer_[0] = 1.0f;

    float runningSum = 0.0f;
    for (int tau = 1; tau < half; ++tau) {
        runningSum += yinBuffer_[tau];
        if (runningSum > 0.0f) {
            yinBuffer_[tau] *= static_cast<float>(tau) / runningSum;
        } else {
            yinBuffer_[tau] = 1.0f;
        }
    }
}

int PitchDetector::absoluteThreshold() const
{
    int half = static_cast<int>(yinBuffer_.size());

    // Skip very small tau (high frequencies / noise)
    int minTau = std::max(2, static_cast<int>(sampleRate_ / 2000.0)); // ~2 kHz max
    int maxTau = std::min(half - 1, static_cast<int>(sampleRate_ / 50.0)); // ~50 Hz min

    for (int tau = minTau; tau < maxTau; ++tau) {
        if (yinBuffer_[tau] < threshold_) {
            // Find local minimum
            while (tau + 1 < maxTau && yinBuffer_[tau + 1] < yinBuffer_[tau]) {
                ++tau;
            }
            return tau;
        }
    }
    return -1;
}

float PitchDetector::parabolicInterpolation(int tau) const
{
    if (tau <= 0 || tau >= static_cast<int>(yinBuffer_.size()) - 1) {
        return static_cast<float>(tau);
    }

    float s0 = yinBuffer_[tau - 1];
    float s1 = yinBuffer_[tau];
    float s2 = yinBuffer_[tau + 1];

    float denom = 2.0f * (2.0f * s1 - s2 - s0);
    if (std::abs(denom) < 1e-6f) {
        return static_cast<float>(tau);
    }

    float adjustment = (s2 - s0) / denom;
    return static_cast<float>(tau) + adjustment;
}
