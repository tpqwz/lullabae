#include "midi.h"

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <vector>

namespace {

const int kTicksPerQuarter = 480;
const int kMsPerQuarter = 500;

int msToTick(int ms) {
    if (ms < 0) {
        ms = 0;
    }
    return static_cast<int>((static_cast<long long>(ms) * kTicksPerQuarter) / kMsPerQuarter);
}

struct Ev {
    int tick;
    int ord;
    unsigned char data[8];
    int len;
};

void put(std::vector<Ev>& msgs, int tick, int ord, const unsigned char* data, int len) {
    Ev ev{};
    ev.tick = tick;
    ev.ord = ord;
    ev.len = len;
    std::memcpy(ev.data, data, static_cast<size_t>(len));
    msgs.push_back(ev);
}

void writeVlq(std::vector<unsigned char>& out, unsigned value) {
    unsigned buffer = value & 0x7fu;
    while ((value >>= 7) != 0) {
        buffer <<= 8;
        buffer |= 0x80u | (value & 0x7fu);
    }
    while (true) {
        out.push_back(static_cast<unsigned char>(buffer & 0xffu));
        if ((buffer & 0x80u) != 0) {
            buffer >>= 8;
        } else {
            break;
        }
    }
}

void pushNote(std::vector<Ev>& msgs, int onTick, int offTick, unsigned char status, int key) {
    unsigned char on[3] = {status, static_cast<unsigned char>(key), 100};
    unsigned char off[3] = {
        static_cast<unsigned char>(status - 0x10),
        static_cast<unsigned char>(key),
        0,
    };
    put(msgs, onTick, 2, on, 3);
    put(msgs, offTick, 1, off, 3);
}

} // namespace

int writeMidi(const TranscriptEvent* events, const int* startMs, int count,
              unsigned char* out, int maxOut) {
    if (count < 0 || out == nullptr || maxOut < 0) {
        return -1;
    }
    if (count > 0 && (events == nullptr || startMs == nullptr)) {
        return -1;
    }

    std::vector<Ev> msgs;
    const unsigned char tempo[6] = {0xFF, 0x51, 0x03, 0x07, 0xA1, 0x20};
    put(msgs, 0, 0, tempo, 6);

    int lastTick = 0;
    for (int i = 0; i < count; ++i) {
        int start = startMs[i] < 0 ? 0 : startMs[i];
        int dur = events[i].durationMs > 0 ? events[i].durationMs : 1;
        int onTick = msToTick(start);
        int offTick = msToTick(start + dur);
        if (offTick <= onTick) {
            offTick = onTick + 1;
        }
        if (events[i].tap) {
            pushNote(msgs, onTick, offTick, 0x99, 37);
        } else if (events[i].midi >= 0 && events[i].midi <= 127) {
            pushNote(msgs, onTick, offTick, 0x90, events[i].midi);
        } else {
            continue;
        }
        if (offTick > lastTick) {
            lastTick = offTick;
        }
    }

    const unsigned char end[3] = {0xFF, 0x2F, 0x00};
    put(msgs, lastTick, 3, end, 3);

    std::sort(msgs.begin(), msgs.end(), [](const Ev& a, const Ev& b) {
        if (a.tick != b.tick) {
            return a.tick < b.tick;
        }
        return a.ord < b.ord;
    });

    std::vector<unsigned char> track;
    int prev = 0;
    for (const Ev& ev : msgs) {
        writeVlq(track, static_cast<unsigned>(ev.tick - prev));
        track.insert(track.end(), ev.data, ev.data + ev.len);
        prev = ev.tick;
    }

    std::vector<unsigned char> file;
    file.insert(file.end(), {'M', 'T', 'h', 'd'});
    file.insert(file.end(), {0, 0, 0, 6, 0, 0, 0, 1, 0x01, 0xE0});
    file.insert(file.end(), {'M', 'T', 'r', 'k'});
    unsigned len = static_cast<unsigned>(track.size());
    file.push_back(static_cast<unsigned char>((len >> 24) & 0xff));
    file.push_back(static_cast<unsigned char>((len >> 16) & 0xff));
    file.push_back(static_cast<unsigned char>((len >> 8) & 0xff));
    file.push_back(static_cast<unsigned char>(len & 0xff));
    file.insert(file.end(), track.begin(), track.end());

    if (static_cast<int>(file.size()) > maxOut) {
        return -1;
    }
    std::memcpy(out, file.data(), file.size());
    return static_cast<int>(file.size());
}
