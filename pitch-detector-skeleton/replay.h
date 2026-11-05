#pragma once

// One ascertained note. startMs is when it began, durationMs how long it sounded.
struct ReplayTone {
    int midi;
    int cents;
    int startMs;
    int durationMs;
};

// Frequency at timeMs for this take, or 0 during a rest.
// A later note wins if two overlap. The end of a note is silent.
float replayHzAt(const ReplayTone* tones, int count, float a4, int timeMs);
