#pragma once

// One finished event. tap is 1 for a beat with no pitch, 0 for a note.
// guitarString is 1 for the high e through 6 for the low E. fret is 0 at the nut.
struct TranscriptEvent {
    int tap;
    int midi;
    int cents;
    int durationMs;
    int startMs;
    int guitarString;
    int fret;
    char name[8];
};

// Turns a stream of pitch frames into notes and taps.
// A note is emitted once it has ended. A tap is a loud hit with no pitch.
class Transcriber {
public:
    Transcriber();

    void reset(float a4);

    // Change the tuning pitch without wiping the note in progress.
    void setTuning(float a4);

    // hopSec is how much time this frame covers. Returns how many events were written.
    int push(float hz, float rms, double hopSec, TranscriptEvent* out, int maxOut);

    // The note still sounding, and any notes held for a breath, are the rest of the take.
    int flush(TranscriptEvent* out, int maxOut);

private:
    float a4_;
    double clockSec_;
    int active_;
    double activeSec_;
    double activeStart_;
    double centsSum_;
    int centsN_;
    int pending_;
    double pendingSec_;
    int challenger_;
    double challengerSec_;
    double sinceTap_;
    double noise_;
    double restSec_;
    bool tapHold_;
    double tapHoldSec_;
    int string_;
    int fret_;
    char name_[8];
    TranscriptEvent phrase_[48];
    int phraseN_;

    void queue(const TranscriptEvent& ev);
    int finish(int written, TranscriptEvent* out, int maxOut, int midi, double hopSec);
};
