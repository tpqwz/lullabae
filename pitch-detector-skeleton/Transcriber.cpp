#include "Transcriber.h"
#include "note.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace {

const double kAttackSec = 0.08;
const double kMinNoteSec = 0.10;
const double kTapGapSec = 0.40;
const float kNoteFloor = 0.008f;
const float kTapRms = 0.15f;
const int kStickCents = 75;

// Highest string that can play this midi inside the first five frets,
// otherwise the smallest fret that is still on the neck.
void placeOnGuitar(int midi, int& string, int& fret) {
    static const int openMidi[6] = {64, 59, 55, 50, 45, 40};
    string = 0;
    fret = 0;
    int bestFret = 99;
    for (int i = 0; i < 6; ++i) {
        int candidate = midi - openMidi[i];
        if (candidate < 0 || candidate > 15) {
            continue;
        }
        if (candidate <= 5) {
            string = i + 1;
            fret = candidate;
            return;
        }
        if (candidate < bestFret) {
            bestFret = candidate;
            string = i + 1;
            fret = candidate;
        }
    }
}

} // namespace

Transcriber::Transcriber() {
    reset(440.0f);
}

void Transcriber::setTuning(float a4) {
    if (a4 > 0.0f) {
        a4_ = a4;
    }
}

void Transcriber::reset(float a4) {
    a4_ = a4 > 0.0f ? a4 : 440.0f;
    clockSec_ = 0;
    active_ = -1;
    activeSec_ = 0;
    activeStart_ = 0;
    centsSum_ = 0;
    centsN_ = 0;
    pending_ = -1;
    pendingSec_ = 0;
    challenger_ = -1;
    challengerSec_ = 0;
    sinceTap_ = 1;
    noise_ = 0.01;
    restSec_ = 0;
    tapHold_ = false;
    tapHoldSec_ = 0;
    string_ = 0;
    fret_ = 0;
    name_[0] = '\0';
    phraseN_ = 0;
}

namespace {

int pitchCents(const TranscriptEvent& event) {
    return event.midi * 100 + event.cents;
}

// Join a scoop into the note it lands on. A real step, a trill, and
// detached repeats of the same note stay separate.
int foldPhrase(TranscriptEvent* ev, int n) {
    for (int i = 0; i + 1 < n;) {
        if (ev[i].tap || ev[i + 1].tap) {
            ++i;
            continue;
        }
        int apart = std::abs(pitchCents(ev[i]) - pitchCents(ev[i + 1]));
        int left = ev[i].durationMs;
        int right = ev[i + 1].durationMs;
        int drop = -1;
        if (apart <= 50 && std::min(left, right) <= 120
                && std::max(left, right) >= std::min(left, right) + 80) {
            drop = left <= right ? i : i + 1;
        }
        if (drop < 0) {
            ++i;
            continue;
        }
        int keep = drop == i ? i + 1 : i;
        ev[keep].durationMs += ev[drop].durationMs;
        for (int j = drop; j + 1 < n; ++j) {
            ev[j] = ev[j + 1];
        }
        --n;
    }
    for (int i = 0; i + 1 < n;) {
        if (ev[i].tap || ev[i + 1].tap) {
            ++i;
            continue;
        }
        int apart = std::abs(pitchCents(ev[i]) - pitchCents(ev[i + 1]));
        int left = ev[i].durationMs;
        int right = ev[i + 1].durationMs;
        if (apart <= 150 && left + 80 < right && left <= 300) {
            ev[i + 1].durationMs += ev[i].durationMs;
            for (int j = i; j + 1 < n; ++j) {
                ev[j] = ev[j + 1];
            }
            --n;
            continue;
        }
        ++i;
    }
    // A short note sitting an octave or more under the tune on both sides
    // is the other part. The line keeps the tune.
    for (int i = 1; i + 1 < n;) {
        if (ev[i].tap || ev[i - 1].tap || ev[i + 1].tap) {
            ++i;
            continue;
        }
        const int mid = pitchCents(ev[i]);
        const int left = pitchCents(ev[i - 1]);
        const int right = pitchCents(ev[i + 1]);
        if (ev[i].durationMs <= 280
                && left - mid >= 1000 && right - mid >= 1000
                && std::abs(left - right) <= 1200) {
            for (int j = i; j + 1 < n; ++j) {
                ev[j] = ev[j + 1];
            }
            --n;
            continue;
        }
        ++i;
    }
    return n;
}

} // namespace

void Transcriber::queue(const TranscriptEvent& ev) {
    if (phraseN_ >= 48) {
        return;
    }
    phrase_[phraseN_++] = ev;
}

int Transcriber::finish(int written, TranscriptEvent* out, int maxOut, int midi, double hopSec) {
    if (midi >= 0) {
        restSec_ = 0;
    } else if (!tapHold_) {
        restSec_ += hopSec;
    }
    if (phraseN_ >= 2) {
        phraseN_ = foldPhrase(phrase_, phraseN_);
    }
    // Hold the last two notes so a bass poke can be judged against both neighbors.
    // A breath, or a tap, lets them go.
    bool breath = active_ < 0 && !tapHold_ && restSec_ >= 0.45;
    bool releaseLast = breath || (phraseN_ > 0 && phrase_[phraseN_ - 1].tap);
    int keep = 0;
    if (!releaseLast && phraseN_ > 0) {
        keep = phraseN_ >= 2 ? 2 : phraseN_;
    }
    int n = phraseN_ - keep;
    if (n > 0 && written < maxOut) {
        if (written + n > maxOut) {
            n = maxOut - written;
        }
        for (int i = 0; i < n; ++i) {
            out[written + i] = phrase_[i];
        }
        for (int j = 0; j < phraseN_ - n; ++j) {
            phrase_[j] = phrase_[n + j];
        }
        phraseN_ -= n;
        written += n;
        if (phraseN_ == 0) {
            restSec_ = 0;
        }
    }
    return written;
}

int Transcriber::flush(TranscriptEvent* out, int maxOut) {
    if (out == nullptr || maxOut <= 0) {
        return 0;
    }
    if (tapHold_ && phraseN_ < 48) {
        TranscriptEvent event{};
        event.tap = 1;
        event.midi = -1;
        event.durationMs = static_cast<int>(std::lround(tapHoldSec_ * 1000.0));
        event.startMs = static_cast<int>(std::lround((clockSec_ - tapHoldSec_) * 1000.0));
        if (event.startMs < 0) {
            event.startMs = 0;
        }
        queue(event);
        tapHold_ = false;
        tapHoldSec_ = 0;
        sinceTap_ = 0;
    }
    if (active_ >= 0 && activeSec_ >= kMinNoteSec && phraseN_ < 48) {
        TranscriptEvent event{};
        event.tap = 0;
        event.midi = active_;
        event.cents = centsN_ > 0 ? static_cast<int>(std::lround(centsSum_ / centsN_)) : 0;
        event.durationMs = static_cast<int>(std::lround(activeSec_ * 1000.0));
        event.startMs = static_cast<int>(std::lround(activeStart_ * 1000.0));
        if (event.startMs < 0) {
            event.startMs = 0;
        }
        event.guitarString = string_;
        event.fret = fret_;
        std::memcpy(event.name, name_, sizeof event.name);
        queue(event);
    }
    active_ = -1;
    activeSec_ = 0;
    centsSum_ = 0;
    centsN_ = 0;
    pending_ = -1;
    pendingSec_ = 0;
    challenger_ = -1;
    challengerSec_ = 0;
    name_[0] = '\0';
    string_ = 0;
    fret_ = 0;
    if (phraseN_ >= 2) {
        phraseN_ = foldPhrase(phrase_, phraseN_);
    }
    int n = phraseN_;
    if (n > maxOut) {
        n = maxOut;
    }
    for (int i = 0; i < n; ++i) {
        out[i] = phrase_[i];
    }
    phraseN_ = 0;
    restSec_ = 0;
    return n;
}

int Transcriber::push(float hz, float rms, double hopSec, TranscriptEvent* out, int maxOut) {
    if (hopSec <= 0.0 || out == nullptr || maxOut <= 0) {
        return 0;
    }

    clockSec_ += hopSec;
    sinceTap_ += hopSec;

    int midi = -1;
    int cents = 0;
    char name[8] = {};
    int string = 0;
    int fret = 0;
    // Starting a note takes twice the room's floor, so a quiet room can still
    // carry a soft hum. Staying in a note uses only the small absolute floor:
    // the note itself pulls the room estimate up, and must not silence itself.
    double startGate = noise_ * 2.0;
    if (startGate < kNoteFloor) {
        startGate = kNoteFloor;
    }
    bool singing = hz > 0.0f && rms >= kNoteFloor && (active_ >= 0 || rms >= startGate);
    if (singing) {
        Note note = noteFromHz(hz, a4_);
        if (note.midi >= 0) {
            // A slow wobble across the rounding line is still this note.
            // A real step of a semitone is about 100 cents, past this margin.
            // A step that does not stay for the attack is chatter, not a new note.
            int held = active_ >= 0 ? active_ : pending_;
            if (held >= 0 && note.midi != held) {
                int fromHeld = (note.midi - held) * 100 + note.cents;
                bool close = fromHeld > -kStickCents && fromHeld < kStickCents;
                if (!close) {
                    if (challenger_ != note.midi) {
                        challenger_ = note.midi;
                        challengerSec_ = 0;
                    }
                    challengerSec_ += hopSec;
                    // A flare at the 2nd, 3rd, or 4th harmonic of the note
                    // being sung has to stay. A real step does not.
                    double need = kAttackSec;
                    const float heldHz = a4_ * static_cast<float>(std::pow(2.0, (held - 69) / 12.0));
                    double ratio = static_cast<double>(hz) / static_cast<double>(heldHz);
                    if (ratio < 1.0) {
                        ratio = 1.0 / ratio;
                    }
                    for (int multiple = 2; multiple <= 4; ++multiple) {
                        const double cents = std::abs(1200.0 * std::log2(ratio / multiple));
                        if (cents < 60.0) {
                            need = 0.30;
                            break;
                        }
                    }
                    close = challengerSec_ < need;
                } else {
                    challenger_ = -1;
                    challengerSec_ = 0;
                }
                if (close) {
                    float heldHz = a4_ * static_cast<float>(std::pow(2.0, (held - 69) / 12.0));
                    Note named = noteFromHz(heldHz, a4_);
                    note.midi = held;
                    // A leap that has not stayed does not get a vote on the pitch.
                    note.cents = (fromHeld > -kStickCents && fromHeld < kStickCents) ? fromHeld : 0;
                    std::memcpy(note.name, named.name, sizeof note.name);
                }
            } else {
                challenger_ = -1;
                challengerSec_ = 0;
            }
            midi = note.midi;
            cents = note.cents;
            std::memcpy(name, note.name, sizeof name);
            placeOnGuitar(midi, string, fret);
        }
    }

    int written = 0;
    // A hole inside a note is not a beat. A tap is a jump up from a quiet floor
    // that ends without becoming a note. The loud hop at the start of a hum
    // is held until the next frame decides.
    double floor = noise_;
    bool tapHit = midi < 0 && active_ < 0 && rms >= kTapRms && rms > floor * 3.0 + 0.05
            && (tapHold_ || sinceTap_ >= kTapGapSec);
    if (tapHit) {
        if (!tapHold_) {
            tapHold_ = true;
            tapHoldSec_ = 0;
        }
        tapHoldSec_ += hopSec;
    } else if (tapHold_ && midi < 0 && phraseN_ < 48) {
        // The thump ended and did not become a note. A single pitched hop on
        // the decay of a click is not a note, so the beat still counts.
        TranscriptEvent event;
        event.tap = 1;
        event.midi = -1;
        event.cents = 0;
        event.durationMs = static_cast<int>(std::lround(tapHoldSec_ * 1000.0));
        event.startMs = static_cast<int>(std::lround((clockSec_ - tapHoldSec_) * 1000.0));
        if (event.startMs < 0) {
            event.startMs = 0;
        }
        event.guitarString = 0;
        event.fret = 0;
        event.name[0] = '\0';
        queue(event);
        sinceTap_ = 0;
        tapHold_ = false;
        tapHoldSec_ = 0;
    }

    if (static_cast<double>(rms) < noise_) {
        noise_ = noise_ * 0.85 + static_cast<double>(rms) * 0.15;
    } else if (midi < 0) {
        // Only an unpitched thump lifts the floor. A note must not chase it.
        noise_ = noise_ * 0.97 + static_cast<double>(rms) * 0.03;
    }

    if (midi == active_) {
        // A leap that has not stayed is still this note, but it must not
        // stretch the note or pull its pitch. A scoop and a bass poke rely
        // on the length of the note they interrupt.
        bool leap = challenger_ >= 0 && challengerSec_ < kAttackSec;
        pending_ = active_;
        pendingSec_ = 0;
        if (!leap) {
            activeSec_ += hopSec;
            if (midi >= 0) {
                centsSum_ += cents;
                centsN_ += 1;
                std::memcpy(name_, name, sizeof name_);
                string_ = string;
                fret_ = fret;
            }
        }
        return finish(written, out, maxOut, midi, hopSec);
    }

    if (midi != pending_) {
        pending_ = midi;
        pendingSec_ = 0;
    }
    pendingSec_ += hopSec;
    if (pendingSec_ < kAttackSec) {
        return finish(written, out, maxOut, midi, hopSec);
    }

    if (active_ >= 0 && activeSec_ >= kMinNoteSec && phraseN_ < 48) {
        TranscriptEvent event;
        event.tap = 0;
        event.midi = active_;
        event.cents = centsN_ > 0 ? static_cast<int>(std::lround(centsSum_ / centsN_)) : 0;
        event.durationMs = static_cast<int>(std::lround(activeSec_ * 1000.0));
        event.startMs = static_cast<int>(std::lround(activeStart_ * 1000.0));
        if (event.startMs < 0) {
            event.startMs = 0;
        }
        event.guitarString = string_;
        event.fret = fret_;
        std::memcpy(event.name, name_, sizeof event.name);
        queue(event);
    }

    active_ = midi;
    activeSec_ = pendingSec_;
    if (active_ >= 0) {
        // The thump grew into a note.
        tapHold_ = false;
        tapHoldSec_ = 0;
    }
    if (midi >= 0) {
        activeStart_ = clockSec_ - pendingSec_;
        centsSum_ = cents;
        centsN_ = 1;
        std::memcpy(name_, name, sizeof name_);
        string_ = string;
        fret_ = fret;
    } else {
        centsSum_ = 0;
        centsN_ = 0;
        name_[0] = '\0';
        string_ = 0;
        fret_ = 0;
    }
    pending_ = midi;
    pendingSec_ = 0;
    return finish(written, out, maxOut, midi, hopSec);
}
