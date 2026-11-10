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

// GM pitch wheel: 8192 is the written note, ±4096 is one semitone, full scale ±2.
void pushBend(std::vector<Ev>& msgs, int tick, int cents) {
    long long wheel = 8192 + (static_cast<long long>(cents) * 4096) / 100;
    if (wheel < 0) {
        wheel = 0;
    }
    if (wheel > 16383) {
        wheel = 16383;
    }
    unsigned char msg[3] = {
        0xE0,
        static_cast<unsigned char>(wheel & 0x7f),
        static_cast<unsigned char>((wheel >> 7) & 0x7f),
    };
    put(msgs, tick, 2, msg, 3);
}

void pushNote(std::vector<Ev>& msgs, int onTick, int offTick, unsigned char status, int key) {
    unsigned char on[3] = {status, static_cast<unsigned char>(key), 100};
    unsigned char off[3] = {
        static_cast<unsigned char>(status - 0x10),
        static_cast<unsigned char>(key),
        0,
    };
    put(msgs, onTick, 3, on, 3);
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
    bool bent = false;
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
            if (events[i].cents != 0) {
                pushBend(msgs, onTick, events[i].cents);
                bent = true;
            } else if (bent) {
                pushBend(msgs, onTick, 0);
                bent = false;
            }
            pushNote(msgs, onTick, offTick, 0x90, events[i].midi);
        } else {
            continue;
        }
        if (offTick > lastTick) {
            lastTick = offTick;
        }
    }

    const unsigned char end[3] = {0xFF, 0x2F, 0x00};
    put(msgs, lastTick, 4, end, 3);

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

namespace {

int readVlq(const unsigned char* in, int len, int& pos, int& value) {
    value = 0;
    for (int k = 0; k < 4; ++k) {
        if (pos >= len) {
            return -1;
        }
        unsigned char byte = in[pos++];
        value = (value << 7) | (byte & 0x7f);
        if ((byte & 0x80) == 0) {
            return 0;
        }
    }
    return -1;
}

int tickToMs(int tick) {
    if (tick < 0) {
        tick = 0;
    }
    return static_cast<int>((static_cast<long long>(tick) * kMsPerQuarter + kTicksPerQuarter / 2)
                            / kTicksPerQuarter);
}

} // namespace

int readMidi(const unsigned char* in, int len, TranscriptEvent* events, int* startMs, int maxOut) {
    if (in == nullptr || len < 0 || events == nullptr || startMs == nullptr || maxOut < 0) {
        return -1;
    }
    int track = -1;
    for (int i = 0; i + 8 <= len; ++i) {
        if (in[i] == 'M' && in[i + 1] == 'T' && in[i + 2] == 'r' && in[i + 3] == 'k') {
            track = i + 8;
            break;
        }
    }
    if (track < 0) {
        return -1;
    }

    bool sounding[128] = {};
    int soundingAt[128] = {};
    int soundingCents[128] = {};
    int tapAt = -1;
    int bendCents = 0;
    int tick = 0;
    int count = 0;
    int pos = track;
    while (pos < len && count <= maxOut) {
        int delta = 0;
        if (readVlq(in, len, pos, delta) != 0) {
            break;
        }
        tick += delta;
        if (pos >= len) {
            break;
        }
        unsigned char status = in[pos];
        if ((status & 0x80) == 0) {
            break;
        }
        if (status == 0xFF) {
            pos += 1;
            if (pos >= len) {
                break;
            }
            unsigned char type = in[pos++];
            int metalen = 0;
            if (readVlq(in, len, pos, metalen) != 0 || pos + metalen > len) {
                break;
            }
            pos += metalen;
            if (type == 0x2F) {
                break;
            }
            continue;
        }
        int dataBytes = ((status & 0xF0) == 0xC0 || (status & 0xF0) == 0xD0) ? 1 : 2;
        if (pos + 1 + dataBytes > len) {
            break;
        }
        unsigned char d1 = in[pos + 1];
        unsigned char d2 = dataBytes == 2 ? in[pos + 2] : 0;
        pos += 1 + dataBytes;
        if (status == 0xE0) {
            int wheel = d1 | (d2 << 7);
            bendCents = static_cast<int>((static_cast<long long>(wheel - 8192) * 100) / 4096);
        } else if (status == 0x90 && d2 > 0 && d1 < 128) {
            sounding[d1] = true;
            soundingAt[d1] = tick;
            soundingCents[d1] = bendCents;
        } else if ((status == 0x80 || (status == 0x90 && d2 == 0)) && d1 < 128 && sounding[d1]) {
            if (count < maxOut) {
                TranscriptEvent& ev = events[count];
                ev = TranscriptEvent{};
                ev.midi = d1;
                ev.cents = soundingCents[d1];
                int start = tickToMs(soundingAt[d1]);
                int end = tickToMs(tick);
                ev.durationMs = end > start ? end - start : 1;
                startMs[count] = start;
                count += 1;
            }
            sounding[d1] = false;
        } else if (status == 0x99 && d1 == 37 && d2 > 0) {
            tapAt = tick;
        } else if ((status == 0x89 || (status == 0x99 && d2 == 0)) && d1 == 37 && tapAt >= 0) {
            if (count < maxOut) {
                TranscriptEvent& ev = events[count];
                ev = TranscriptEvent{};
                ev.tap = 1;
                ev.midi = -1;
                int start = tickToMs(tapAt);
                int end = tickToMs(tick);
                ev.durationMs = end > start ? end - start : 1;
                startMs[count] = start;
                count += 1;
            }
            tapAt = -1;
        }
    }
    return count;
}
