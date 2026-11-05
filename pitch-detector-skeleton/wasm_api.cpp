// Browser entry. The page copies mic hops in, and copies MIDI bytes out.
// Nothing here touches the network.

#include "PitchDetector.h"
#include "Transcriber.h"
#include "midi.h"
#include "note.h"

#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

namespace {

PitchDetector* detector = nullptr;
Transcriber transcriber;
double clockSec = 0;
std::vector<TranscriptEvent> taken;
std::vector<int> starts;
std::string last;

} // namespace

extern "C" {

void lb_init(double sampleRate, float a4) {
    delete detector;
    detector = new PitchDetector(sampleRate > 0 ? sampleRate : 48000.0, 2048, 0.15f);
    transcriber.reset(a4 > 0 ? a4 : 440.0f);
    clockSec = 0;
    taken.clear();
    starts.clear();
    last.clear();
}

void lb_reset(float a4) {
    transcriber.reset(a4 > 0 ? a4 : 440.0f);
    clockSec = 0;
    taken.clear();
    starts.clear();
    last.clear();
}

void lb_set_tuning(float a4) {
    transcriber.setTuning(a4);
}

float lb_detect(const float* samples, int n) {
    if (detector == nullptr || samples == nullptr || n <= 0) {
        return 0;
    }
    return detector->detect(samples, n);
}

// "N name cents duration string fret midi" or "T duration". Empty if nothing closed.
const char* lb_push(float hz, float rms, double hopSec) {
    last.clear();
    if (hopSec <= 0.0) {
        return last.c_str();
    }
    TranscriptEvent ev[4];
    int n = transcriber.push(hz, rms, hopSec, ev, 4);
    clockSec += hopSec;
    int nowMs = static_cast<int>(std::lround(clockSec * 1000.0));
    for (int i = 0; i < n; ++i) {
        int start = nowMs - ev[i].durationMs;
        if (start < 0) {
            start = 0;
        }
        taken.push_back(ev[i]);
        starts.push_back(start);
        char buf[80];
        if (ev[i].tap) {
            std::snprintf(buf, sizeof buf, "T %d\n", ev[i].durationMs);
        } else {
            std::snprintf(buf, sizeof buf, "N %s %d %d %d %d %d\n",
                          ev[i].name, ev[i].cents, ev[i].durationMs,
                          ev[i].guitarString, ev[i].fret, ev[i].midi);
        }
        last += buf;
    }
    if (taken.size() > 512) {
        int drop = static_cast<int>(taken.size()) - 512;
        taken.erase(taken.begin(), taken.begin() + drop);
        starts.erase(starts.begin(), starts.begin() + drop);
    }
    return last.c_str();
}

const char* lb_describe(float hz, float a4) {
    static char buf[16];
    Note note = noteFromHz(hz, a4);
    if (note.midi < 0) {
        buf[0] = '\0';
        return buf;
    }
    std::snprintf(buf, sizeof buf, "%s %d", note.name, note.cents);
    return buf;
}

int lb_midi(unsigned char* out, int maxOut) {
    if (taken.empty()) {
        return writeMidi(nullptr, nullptr, 0, out, maxOut);
    }
    return writeMidi(taken.data(), starts.data(), static_cast<int>(taken.size()), out, maxOut);
}

} // extern "C"
