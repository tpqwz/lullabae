#include <jni.h>

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <mutex>

#include "PitchDetector.h"
#include "Transcriber.h"
#include "note.h"
#include "replay.h"

// One detector for the process. The audio thread is the only caller.
static PitchDetector* g_detector = nullptr;
static Transcriber g_transcriber;
static std::mutex g_transcriptLock;

extern "C" JNIEXPORT void JNICALL
Java_dev_lullabae_Pitch_init(JNIEnv*, jclass, jdouble sample_rate) {
    delete g_detector;
    g_detector = new PitchDetector(sample_rate, 2048, 0.15f);
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
// "N E4 0 500 1 0 64" or "T 20". Empty when nothing closed.
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
            wrote = std::snprintf(line, sizeof line, "T %d\n", events[i].durationMs);
        } else {
            wrote = std::snprintf(line, sizeof line, "N %s %d %d %d %d %d\n",
                                  events[i].name, events[i].cents, events[i].durationMs,
                                  events[i].guitarString, events[i].fret, events[i].midi);
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
