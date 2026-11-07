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
    upper_.resize(static_cast<size_t>(bufferSize_));
}

float PitchDetector::detect(const float* samples, int numSamples)
{
    if (numSamples < bufferSize_ || samples == nullptr) {
        return 0.0f;
    }
    const float low = analyze(samples, numSamples);
    // The tune is already the pitch. A second pass is only for a bass note
    // that may be hiding a higher line, and it is too slow to run on every hop.
    if (low >= 200.0f) {
        return low;
    }
    // A bass note under a different tune hides that tune. Hear the upper one
    // when it is not simply a harmonic of the lower one.
    if (static_cast<int>(upper_.size()) < numSamples) {
        upper_.resize(static_cast<size_t>(numSamples));
    }
    // Three poles near 400 Hz, so a loud bass falls under the tune.
    const float rc = 1.0f / (2.0f * 3.14159265f * 400.0f);
    const float dt = 1.0f / static_cast<float>(sampleRate_);
    const float alpha = rc / (rc + dt);
    for (int pole = 0; pole < 3; ++pole) {
        float prevX = 0.0f;
        float prevY = 0.0f;
        for (int i = 0; i < numSamples; ++i) {
            const float x = pole == 0 ? samples[i] : upper_[static_cast<size_t>(i)];
            prevY = alpha * (prevY + x - prevX);
            prevX = x;
            upper_[static_cast<size_t>(i)] = prevY;
        }
    }
    const float high = analyze(upper_.data(), numSamples);
    if (!(high > 0.0f)) {
        return low;
    }
    if (!(low > 0.0f)) {
        return high;
    }
    const double ratio = static_cast<double>(high) / static_cast<double>(low);
    if (ratio < 1.4) {
        return low;
    }
    for (int multiple = 2; multiple <= 3; ++multiple) {
        const double cents = std::abs(1200.0 * std::log2(ratio / multiple));
        if (cents < 60.0) {
            return low;
        }
    }
    return high;
}

float PitchDetector::analyze(const float* samples, int numSamples)
{
    if (numSamples < bufferSize_ || samples == nullptr) {
        return 0.0f;
    }

    // Use the most recent bufferSize samples. YIN assumes a raw periodic
    // waveform; a Hann window makes x[i] and x[i+tau] unequal and the
    // difference function misses low notes.
    const float* input = samples + (numSamples - bufferSize_);

    computeDifferenceFunction(input, bufferSize_);
    cumulativeMeanNormalizedDifference();

    int tau = absoluteThreshold(input, bufferSize_);
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

namespace {

float toneMag(const float* samples, int n, float hz, float sampleRate) {
    const double w = 2.0 * 3.14159265358979323846 * static_cast<double>(hz) / sampleRate;
    double re = 0.0;
    double im = 0.0;
    for (int i = 0; i < n; ++i) {
        const double a = w * i;
        re += static_cast<double>(samples[i]) * std::cos(a);
        im += static_cast<double>(samples[i]) * std::sin(a);
    }
    return static_cast<float>(std::sqrt(re * re + im * im) / n);
}

} // namespace

int PitchDetector::absoluteThreshold(const float* samples, int n) const
{
    int half = static_cast<int>(yinBuffer_.size());

    // Skip very small tau (high frequencies / noise)
    int minTau = std::max(2, static_cast<int>(sampleRate_ / 2000.0)); // ~2 kHz max
    int maxTau = std::min(half - 1, static_cast<int>(sampleRate_ / 50.0)); // ~50 Hz min

    for (int tau = minTau; tau < maxTau; ++tau) {
        if (yinBuffer_[tau] < threshold_) {
            // A one-sample bump on the way into the dip used to stop the search
            // a semitone sharp. Take the deepest point in the next slice of the
            // period, then finish the walk into that valley.
            int reach = tau + std::max(8, tau / 8);
            if (reach >= maxTau) {
                reach = maxTau - 1;
            }
            for (int k = tau + 1; k <= reach; ++k) {
                if (yinBuffer_[k] < yinBuffer_[tau]) {
                    tau = k;
                }
            }
            while (tau + 1 < maxTau && yinBuffer_[tau + 1] < yinBuffer_[tau]) {
                ++tau;
            }
            // A clean note already dips to about zero, so the first period stands.
            // A weak fundamental dips only part way at a harmonic, and much
            // further at the real period, one or two octaves-and-a-fifth down.
            if (yinBuffer_[tau] > 0.005f) {
                int best = -1;
                float bestD = yinBuffer_[tau] * 0.5f;
                for (int mult = 2; mult <= 3; ++mult) {
                    if (tau * mult >= maxTau) {
                        continue;
                    }
                    int alt = tau * mult;
                    int lo = std::max(tau + 1, alt - 8);
                    int hi = std::min(maxTau - 1, alt + 8);
                    for (int k = lo; k <= hi; ++k) {
                        if (yinBuffer_[k] < yinBuffer_[alt]) {
                            alt = k;
                        }
                    }
                    if (yinBuffer_[alt] < bestD) {
                        // A deep dip at a longer period is only the real note
                        // when that lower pitch is actually in the sound.
                        // Otherwise it is a subharmonic of the note already found.
                        const float highHz = static_cast<float>(sampleRate_) / static_cast<float>(tau);
                        const float lowHz = static_cast<float>(sampleRate_) / static_cast<float>(alt);
                        if (toneMag(samples, n, lowHz, static_cast<float>(sampleRate_))
                                >= 0.12f * toneMag(samples, n, highHz, static_cast<float>(sampleRate_))) {
                            best = alt;
                            bestD = yinBuffer_[alt];
                        }
                    }
                }
                if (best >= 0) {
                    return best;
                }
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
