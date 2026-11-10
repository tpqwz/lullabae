#include <jni.h>

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <mutex>

#include "PitchDetector.h"
#include "Transcriber.h"
#include "midi.h"
#include "note.h"
#include "replay.h"

#include <string>
#include <vector>

// One detector for the process. The audio thread is the only caller.
static PitchDetector* g_detector = nullptr;
static Transcriber g_transcriber;
static std::mutex g_transcriptLock;

extern "C" JNIEXPORT void JNICALL
Java_dev_lullabae_Pitch_init(JNIEnv*, jclass, jdouble sample_rate) {
    delete g_detector;
    g_detector = new PitchDetector(sample_rate, 2048);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_dev_lullabae_Pitch_detect(JNIEnv* env, jclass, jfloatArray samples) {
    if (g_detector == nullptr || samples == nullptr) {
        return 0.0f;
    }
    jsize n = env->GetArrayLength(samples);
    jfloat* data = env->GetFloatArrayElements(samples, nullptr);
    float hz = g_detector->detect(data, n);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    return hz;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_lullabae_Pitch_noteName(JNIEnv* env, jclass, jfloat hz, jfloat a4) {
    Note note = noteFromHz(hz, a4);
    return env->NewStringUTF(note.name);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_lullabae_Pitch_cents(JNIEnv*, jclass, jfloat hz, jfloat a4) {
    return noteFromHz(hz, a4).cents;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_lullabae_Pitch_resetTranscript(JNIEnv*, jclass, jfloat a4) {
    std::lock_guard<std::mutex> lock(g_transcriptLock);
    g_transcriber.reset(a4);
}

// Finished events from this hop, one per line:
// "N E4 0 500 1 0 64 1200" or "T 20 800". Empty when nothing closed.
extern "C" JNIEXPORT jstring JNICALL
Java_dev_lullabae_Pitch_takeTranscript(JNIEnv* env, jclass, jfloat hz, jfloat rms, jfloat hop_sec, jfloat a4) {
    std::lock_guard<std::mutex> lock(g_transcriptLock);
    g_transcriber.setTuning(a4);
    TranscriptEvent events[4];
    int count = g_transcriber.push(hz, rms, hop_sec, events, 4);
    char text[256];
    text[0] = '\0';
    size_t used = 0;
    for (int i = 0; i < count; ++i) {
        char line[64];
        int wrote;
        if (events[i].tap) {
            wrote = std::snprintf(line, sizeof line, "T %d %d\n", events[i].durationMs, events[i].startMs);
        } else {
            wrote = std::snprintf(line, sizeof line, "N %s %d %d %d %d %d %d\n",
                                  events[i].name, events[i].cents, events[i].durationMs,
                                  events[i].guitarString, events[i].fret, events[i].midi,
                                  events[i].startMs);
        }
        if (wrote < 0 || used + static_cast<size_t>(wrote) >= sizeof text) {
            break;
        }
        std::memcpy(text + used, line, static_cast<size_t>(wrote));
        used += static_cast<size_t>(wrote);
        text[used] = '\0';
    }
    return env->NewStringUTF(text);
}

// Notes still held when listening stops. Same lines as takeTranscript.
extern "C" JNIEXPORT jstring JNICALL
Java_dev_lullabae_Pitch_flushTranscript(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(g_transcriptLock);
    TranscriptEvent events[48];
    int count = g_transcriber.flush(events, 48);
    char text[48 * 64];
    text[0] = '\0';
    size_t used = 0;
    for (int i = 0; i < count; ++i) {
        char line[64];
        int wrote;
        if (events[i].tap) {
            wrote = std::snprintf(line, sizeof line, "T %d %d\n", events[i].durationMs, events[i].startMs);
        } else {
            wrote = std::snprintf(line, sizeof line, "N %s %d %d %d %d %d %d\n",
                                  events[i].name, events[i].cents, events[i].durationMs,
                                  events[i].guitarString, events[i].fret, events[i].midi,
                                  events[i].startMs);
        }
        if (wrote < 0 || used + static_cast<size_t>(wrote) >= sizeof text) {
            break;
        }
        std::memcpy(text + used, line, static_cast<size_t>(wrote));
        used += static_cast<size_t>(wrote);
        text[used] = '\0';
    }
    return env->NewStringUTF(text);
}

// Frequency of the take at timeMs, or 0 in a rest. Arrays are parallel.
extern "C" JNIEXPORT jfloat JNICALL
Java_dev_lullabae_Pitch_replayHz(JNIEnv* env, jclass, jintArray midi, jintArray cents,
                                 jintArray start, jintArray duration, jfloat a4, jint time_ms) {
    if (midi == nullptr || cents == nullptr || start == nullptr || duration == nullptr) {
        return 0.0f;
    }
    jsize n = env->GetArrayLength(midi);
    n = std::min(n, env->GetArrayLength(cents));
    n = std::min(n, env->GetArrayLength(start));
    n = std::min(n, env->GetArrayLength(duration));
    if (n <= 0) {
        return 0.0f;
    }
    if (n > 512) {
        n = 512;
    }
    ReplayTone tones[512];
    jint* midiData = env->GetIntArrayElements(midi, nullptr);
    jint* centData = env->GetIntArrayElements(cents, nullptr);
    jint* startData = env->GetIntArrayElements(start, nullptr);
    jint* durData = env->GetIntArrayElements(duration, nullptr);
    for (jsize i = 0; i < n; ++i) {
        tones[i].midi = midiData[i];
        tones[i].cents = centData[i];
        tones[i].startMs = startData[i];
        tones[i].durationMs = durData[i];
    }
    env->ReleaseIntArrayElements(midi, midiData, JNI_ABORT);
    env->ReleaseIntArrayElements(cents, centData, JNI_ABORT);
    env->ReleaseIntArrayElements(start, startData, JNI_ABORT);
    env->ReleaseIntArrayElements(duration, durData, JNI_ABORT);
    return replayHzAt(tones, n, a4, time_ms);
}

// One take as a MIDI file. tap[i] is 1 for a beat. Empty arrays are an empty file.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_lullabae_Pitch_writeMidi(JNIEnv* env, jclass, jintArray midi, jintArray cents,
                                  jintArray start, jintArray duration, jintArray tap) {
    jsize n = 0;
    if (midi != nullptr && cents != nullptr && start != nullptr && duration != nullptr && tap != nullptr) {
        n = env->GetArrayLength(midi);
        n = std::min(n, env->GetArrayLength(cents));
        n = std::min(n, env->GetArrayLength(start));
        n = std::min(n, env->GetArrayLength(duration));
        n = std::min(n, env->GetArrayLength(tap));
    }
    if (n > 1024) {
        n = 1024;
    }
    std::vector<TranscriptEvent> events(static_cast<size_t>(n));
    std::vector<int> starts(static_cast<size_t>(n));
    if (n > 0) {
        jint* midiData = env->GetIntArrayElements(midi, nullptr);
        jint* centData = env->GetIntArrayElements(cents, nullptr);
        jint* startData = env->GetIntArrayElements(start, nullptr);
        jint* durData = env->GetIntArrayElements(duration, nullptr);
        jint* tapData = env->GetIntArrayElements(tap, nullptr);
        if (midiData == nullptr || centData == nullptr || startData == nullptr || durData == nullptr
                || tapData == nullptr) {
            if (midiData != nullptr) env->ReleaseIntArrayElements(midi, midiData, JNI_ABORT);
            if (centData != nullptr) env->ReleaseIntArrayElements(cents, centData, JNI_ABORT);
            if (startData != nullptr) env->ReleaseIntArrayElements(start, startData, JNI_ABORT);
            if (durData != nullptr) env->ReleaseIntArrayElements(duration, durData, JNI_ABORT);
            if (tapData != nullptr) env->ReleaseIntArrayElements(tap, tapData, JNI_ABORT);
            return nullptr;
        }
        for (jsize i = 0; i < n; ++i) {
            events[static_cast<size_t>(i)].tap = tapData[i] != 0 ? 1 : 0;
            events[static_cast<size_t>(i)].midi = midiData[i];
            events[static_cast<size_t>(i)].cents = centData[i];
            events[static_cast<size_t>(i)].durationMs = durData[i];
            starts[static_cast<size_t>(i)] = startData[i];
        }
        env->ReleaseIntArrayElements(midi, midiData, JNI_ABORT);
        env->ReleaseIntArrayElements(cents, centData, JNI_ABORT);
        env->ReleaseIntArrayElements(start, startData, JNI_ABORT);
        env->ReleaseIntArrayElements(duration, durData, JNI_ABORT);
        env->ReleaseIntArrayElements(tap, tapData, JNI_ABORT);
    }
    int maxOut = 64 + static_cast<int>(n) * 32;
    std::vector<unsigned char> out(static_cast<size_t>(maxOut));
    int wrote = writeMidi(n == 0 ? nullptr : events.data(), n == 0 ? nullptr : starts.data(),
                          static_cast<int>(n), out.data(), maxOut);
    if (wrote < 0) {
        return nullptr;
    }
    jbyteArray bytes = env->NewByteArray(wrote);
    if (bytes == nullptr) {
        return nullptr;
    }
    env->SetByteArrayRegion(bytes, 0, wrote, reinterpret_cast<const jbyte*>(out.data()));
    return bytes;
}

// "N midi cents duration start" or "T duration start". Empty when the file has no notes.
extern "C" JNIEXPORT jstring JNICALL
Java_dev_lullabae_Pitch_readMidi(JNIEnv* env, jclass, jbyteArray file) {
    if (file == nullptr) {
        return nullptr;
    }
    jsize n = env->GetArrayLength(file);
    if (n <= 0) {
        return env->NewStringUTF("");
    }
    std::vector<unsigned char> bytes(static_cast<size_t>(n));
    env->GetByteArrayRegion(file, 0, n, reinterpret_cast<jbyte*>(bytes.data()));
    TranscriptEvent events[1024];
    int starts[1024];
    int count = readMidi(bytes.data(), static_cast<int>(n), events, starts, 1024);
    if (count < 0) {
        return nullptr;
    }
    std::string text;
    for (int i = 0; i < count; ++i) {
        char line[64];
        int wrote;
        if (events[i].tap) {
            wrote = std::snprintf(line, sizeof line, "T %d %d\n", events[i].durationMs, starts[i]);
        } else {
            wrote = std::snprintf(line, sizeof line, "N %d %d %d %d\n",
                                  events[i].midi, events[i].cents, events[i].durationMs, starts[i]);
        }
        if (wrote > 0) {
            text.append(line);
        }
    }
    return env->NewStringUTF(text.c_str());
}
