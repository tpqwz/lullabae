#include "replay.h"

#include <cmath>

float replayHzAt(const ReplayTone* tones, int count, float a4, int timeMs) {
    if (tones == nullptr || count <= 0 || !(a4 > 0.0f) || !std::isfinite(a4)) {
        return 0.0f;
    }
    int chosen = -1;
    for (int i = 0; i < count; ++i) {
        const ReplayTone& tone = tones[i];
        if (tone.midi < 0 || tone.midi > 127 || tone.durationMs <= 0) {
            continue;
        }
        if (timeMs < tone.startMs || timeMs >= tone.startMs + tone.durationMs) {
            continue;
        }
        if (chosen < 0 || tone.startMs >= tones[chosen].startMs) {
            chosen = i;
        }
    }
    if (chosen < 0) {
        return 0.0f;
    }
    const double semis = (tones[chosen].midi - 69) + (tones[chosen].cents / 100.0);
    return static_cast<float>(static_cast<double>(a4) * std::pow(2.0, semis / 12.0));
}
