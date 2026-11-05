#pragma once

#include "Transcriber.h"

// A type-0 MIDI file of one take. 120 bpm, 480 ticks per quarter.
// A note is channel 1. A tap is a side stick on channel 10.
// startMs[i] is when events[i] began. Returns the byte count, or -1
// when the buffer is too small or an argument is unusable.
int writeMidi(const TranscriptEvent* events, const int* startMs, int count,
              unsigned char* out, int maxOut);
