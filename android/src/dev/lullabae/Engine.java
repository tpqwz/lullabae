package dev.lullabae;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Plays a short detuned phrase, or reads the microphone, and runs both
 * through the native pitch detector. Analysis stays off the UI thread.
 */
public final class Engine {
    public interface Listener {
        void onFrame(String status, String name, int cents, float hz);

        void onNote(String name, int cents, int durationMs, int stringNum, int fret, int startMs, int midi);

        void onTap();

        void onScoreCleared();

        void onReplayFinished();
    }

    private static final String TAG = "Lullabae";
    private static final int WINDOW = 2048;
    private static final int HOP = 1024;
    // midi, cents, milliseconds. A midi below 0 is a rest between tunes.
    private static final Tune[] TUNES = {
            new Tune("Lullaby", new int[][]{
                    {64, 0, 750},
                    {64, 14, 750},
                    {67, -18, 750},
                    {64, 0, 750},
                    {69, 8, 750},
                    {67, 0, 750},
                    {62, -12, 750},
                    {60, 0, 750},
                    {-1, 0, 700},
            }),
            new Tune("Twinkle", new int[][]{
                    {60, 0, 400}, {60, 0, 400}, {67, 0, 400}, {67, 0, 400},
                    {69, 0, 400}, {69, 0, 400}, {67, 0, 800},
                    {65, 0, 400}, {65, 0, 400}, {64, 0, 400}, {64, 0, 400},
                    {62, 0, 400}, {62, 0, 400}, {60, 0, 800},
                    {67, 0, 400}, {67, 0, 400}, {65, 0, 400}, {65, 0, 400},
                    {64, 0, 400}, {64, 0, 400}, {62, 0, 800},
                    {67, 0, 400}, {67, 0, 400}, {65, 0, 400}, {65, 0, 400},
                    {64, 0, 400}, {64, 0, 400}, {62, 0, 800},
                    {60, 0, 400}, {60, 0, 400}, {67, 0, 400}, {67, 0, 400},
                    {69, 0, 400}, {69, 0, 400}, {67, 0, 800},
                    {65, 0, 400}, {65, 0, 400}, {64, 0, 400}, {64, 0, 400},
                    {62, 0, 400}, {62, 0, 400}, {60, 0, 800},
                    {-1, 0, 700},
            }),
            new Tune("Amazing Grace", new int[][]{
                    {67, 0, 500}, {67, 0, 500}, {72, 0, 500}, {76, 0, 500},
                    {74, 0, 500}, {72, 0, 500}, {69, 0, 500}, {67, 0, 500},
                    {64, 0, 500}, {67, 0, 500}, {67, 0, 500}, {69, 0, 500}, {67, 0, 1000},
                    {67, 0, 500}, {72, 0, 500}, {76, 0, 500}, {79, 0, 500},
                    {76, 0, 500}, {74, 0, 500}, {72, 0, 500},
                    {69, 0, 500}, {67, 0, 500}, {64, 0, 500},
                    {67, 0, 500}, {67, 0, 500}, {69, 0, 500}, {67, 0, 1000},
                    {-1, 0, 700},
            }),
    };

    private static final class Tune {
        final String name;
        final int[][] notes;

        Tune(String name, int[][] notes) {
            this.name = name;
            this.notes = notes;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final float[] window = new float[WINDOW];

    private Listener listener;
    private Thread thread;
    private volatile boolean running;

    private AudioTrack track;
    private AudioRecord record;

    private int sampleRate = 48000;
    private volatile float referenceHz = 440f;
    private volatile String songName = "Lullaby";
    private int noteSamples = 48000;
    private int songAt;
    private int noteAt;
    private int produced;
    private boolean joined;
    private double phase;
    private double heardSec;
    private float shownHz;
    private int[] replayMidi = new int[0];
    private int[] replayCents = new int[0];
    private int[] replayStart = new int[0];
    private int[] replayDuration = new int[0];

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Concert A, from 400 to 480 Hz. The lullaby and the cents reading both follow it. */
    public void setReferenceHz(float hz) {
        if (hz >= 400f && hz <= 480f) {
            referenceHz = hz;
        }
    }

    public void startDemo() {
        stop();
        sampleRate = outputRate();
        songAt = 0;
        noteAt = 0;
        songName = TUNES[0].name;
        noteSamples = noteLength(TUNES[0].notes[0]);
        openTrack(sampleRate);
        Pitch.init(sampleRate);
        Pitch.resetTranscript(referenceHz);
        heardSec = 0;
        postClear();
        synchronized (lock) {
            running = true;
            thread = new Thread(this::demoLoop, "lullabae-audio");
            thread.start();
        }
    }

    /** @return false when the microphone could not be opened */
    public boolean startMic() {
        stop();
        int[] rate = new int[1];
        AudioRecord rec = openMic(rate);
        if (rec == null) {
            post("Microphone unavailable", "", 0, 0f);
            return false;
        }
        record = rec;
        sampleRate = rate[0];
        Pitch.init(sampleRate);
        Pitch.resetTranscript(referenceHz);
        heardSec = 0;
        postClear();
        try {
            record.startRecording();
        } catch (RuntimeException e) {
            Log.e(TAG, "startRecording", e);
            releaseAudio();
            post("Microphone unavailable", "", 0, 0f);
            return false;
        }
        synchronized (lock) {
            running = true;
            thread = new Thread(this::micLoop, "lullabae-audio");
            thread.start();
        }
        return true;
    }

    /** Sing the written notes back. Does not clear the score. */
    public void replay(int[] midi, int[] cents, int[] startMs, int[] durationMs) {
        stop();
        int n = midi == null ? 0 : midi.length;
        if (cents == null || startMs == null || durationMs == null) {
            n = 0;
        }
        if (n > 0) {
            n = Math.min(n, Math.min(cents.length, Math.min(startMs.length, durationMs.length)));
        }
        replayMidi = new int[n];
        replayCents = new int[n];
        replayStart = new int[n];
        replayDuration = new int[n];
        if (n > 0) {
            System.arraycopy(midi, 0, replayMidi, 0, n);
            System.arraycopy(cents, 0, replayCents, 0, n);
            System.arraycopy(startMs, 0, replayStart, 0, n);
            System.arraycopy(durationMs, 0, replayDuration, 0, n);
        }
        sampleRate = outputRate();
        openTrack(sampleRate);
        synchronized (lock) {
            running = true;
            thread = new Thread(this::replayLoop, "lullabae-audio");
            thread.start();
        }
    }

    public void stop() {
        Thread t;
        synchronized (lock) {
            running = false;
            t = thread;
            thread = null;
        }
        AudioRecord rec = record;
        if (rec != null) {
            try {
                rec.stop();
            } catch (RuntimeException ignored) {
                // read() unblocks on a failed stop too
            }
        }
        if (t != null) {
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        releaseAudio();
    }

    private void demoLoop() {
        songAt = 0;
        noteAt = 0;
        songName = TUNES[0].name;
        produced = 0;
        noteSamples = noteLength(TUNES[0].notes[0]);
        phase = 0;
        Log.i(TAG, "song " + songName);
        float[] hop = new float[HOP];
        short[] pcm = new short[HOP];
        int primed = 0;
        while (primed < WINDOW && running) {
            fill(hop, pcm);
            push(hop, HOP);
            primed += HOP;
        }
        publish(false);

        while (running) {
            long start = System.nanoTime();
            int at = produced;
            fill(hop, pcm);
            push(hop, HOP);
            writePcm(pcm);
            hear(hop, HOP);
            // Hold the last steady reading across the splice between notes.
            if (at >= WINDOW && at < noteSamples - HOP) {
                publish(false);
            }
            pace(start);
        }
    }

    private void micLoop() {
        for (int i = 0; i < window.length; i++) {
            window[i] = 0f;
        }
        short[] raw = new short[HOP];
        float[] hop = new float[HOP];
        int filled = 0;
        while (running) {
            AudioRecord rec = record;
            if (rec == null) {
                break;
            }
            int n = rec.read(raw, 0, HOP);
            if (n <= 0) {
                if (!running) {
                    break;
                }
                continue;
            }
            float energy = 0f;
            for (int i = 0; i < n; i++) {
                hop[i] = raw[i] / 32768f;
                energy += hop[i] * hop[i];
            }
            push(hop, n);
            filled = Math.min(WINDOW, filled + n);
            float rms = (float) Math.sqrt(energy / n);
            if (filled >= WINDOW) {
                hear(hop, n);
            }
            if (filled < WINDOW || rms < 0.01f) {
                post("Quiet", "", 0, 0f);
                continue;
            }
            publish(true);
        }
    }

    private int noteLength(int[] note) {
        int ms = note.length > 2 ? note[2] : 750;
        return Math.max(HOP, sampleRate * ms / 1000);
    }

    private int noteMidi(int song, int index) {
        return TUNES[song].notes[index][0];
    }

    private int upcomingMidi() {
        int song = songAt;
        int index = noteAt + 1;
        if (index >= TUNES[song].notes.length) {
            song = (songAt + 1) % TUNES.length;
            index = 0;
        }
        return noteMidi(song, index);
    }

    private void nextNote() {
        int prevMidi = noteMidi(songAt, noteAt);
        int[][] notes = TUNES[songAt].notes;
        noteAt++;
        if (noteAt >= notes.length) {
            songAt = (songAt + 1) % TUNES.length;
            noteAt = 0;
            songName = TUNES[songAt].name;
            Log.i(TAG, "song " + songName);
        }
        int midi = noteMidi(songAt, noteAt);
        joined = prevMidi >= 0 && midi >= 0;
        produced = 0;
        noteSamples = noteLength(TUNES[songAt].notes[noteAt]);
    }

    private void fill(float[] hop, short[] pcm) {
        float a4 = referenceHz;
        for (int i = 0; i < hop.length; i++) {
            if (produced >= noteSamples) {
                nextNote();
            }
            int[] note = TUNES[songAt].notes[noteAt];
            int midi = note[0];
            int cents = note[1];
            if (midi < 0) {
                hop[i] = 0f;
                pcm[i] = 0;
                produced++;
                continue;
            }
            double hz = a4 * Math.pow(2.0, (midi - 69) / 12.0 + cents / 1200.0);
            phase += 2.0 * Math.PI * hz / sampleRate;
            if (phase > 2.0 * Math.PI) {
                phase -= 2.0 * Math.PI;
            }
            float gain = 1f;
            int fade = sampleRate / 100;
            // Fade only at a rest. A dip between two tones thumps on speakers.
            if (!joined && produced < fade) {
                gain = produced / (float) fade;
            } else if (upcomingMidi() < 0 && noteSamples - produced < fade) {
                gain = (noteSamples - produced) / (float) fade;
            }
            float sample = (float) (0.22 * gain * Math.sin(phase));
            hop[i] = sample;
            pcm[i] = (short) (sample * 32767);
            produced++;
        }
    }

    private void push(float[] hop, int n) {
        if (n >= WINDOW) {
            System.arraycopy(hop, n - WINDOW, window, 0, WINDOW);
            return;
        }
        System.arraycopy(window, n, window, 0, WINDOW - n);
        System.arraycopy(hop, 0, window, WINDOW - n, n);
    }

    private void hear(float[] hop, int n) {
        float energy = 0f;
        for (int i = 0; i < n; i++) {
            energy += hop[i] * hop[i];
        }
        float rms = (float) Math.sqrt(energy / Math.max(1, n));
        float hz = Pitch.detect(window);
        shownHz = hz;
        float hopSec = (float) (n / (double) sampleRate);
        String raw = Pitch.takeTranscript(hz, rms, hopSec, referenceHz);
        heardSec += hopSec;
        if (raw == null || raw.isEmpty()) {
            return;
        }
        for (String line : raw.split("\n")) {
            deliver(line, heardSec);
        }
    }

    private void deliver(String line, double endSec) {
        String[] parts = line.split(" ");
        if (parts.length == 7 && "N".equals(parts[0])) {
            String name = parts[1];
            int cents = Integer.parseInt(parts[2]);
            int durationMs = Integer.parseInt(parts[3]);
            int stringNum = Integer.parseInt(parts[4]);
            int fret = Integer.parseInt(parts[5]);
            int midi = Integer.parseInt(parts[6]);
            int startMs = Math.max(0, (int) Math.round(endSec * 1000.0) - durationMs);
            main.post(() -> {
                Listener l = listener;
                if (l != null) {
                    l.onNote(name, cents, durationMs, stringNum, fret, startMs, midi);
                }
            });
        } else if (parts.length >= 1 && "T".equals(parts[0])) {
            main.post(() -> {
                Listener l = listener;
                if (l != null) {
                    l.onTap();
                }
            });
        }
    }

    private void postClear() {
        main.post(() -> {
            Listener l = listener;
            if (l != null) {
                l.onScoreCleared();
            }
        });
    }

    private void publish(boolean listening) {
        float hz = shownHz;
        float a4 = referenceHz;
        String name = Pitch.noteName(hz, a4);
        int cents = (name == null || name.isEmpty()) ? 0 : Pitch.cents(hz, a4);
        if (name == null) {
            name = "";
        }
        post(listening ? "Listening" : songName, name, cents, hz);
    }

    private void post(String status, String name, int cents, float hz) {
        Listener l = listener;
        if (l == null) {
            return;
        }
        main.post(() -> l.onFrame(status, name, cents, hz));
    }

    private void replayLoop() {
        int endMs = 0;
        for (int i = 0; i < replayStart.length; i++) {
            endMs = Math.max(endMs, replayStart[i] + Math.max(replayDuration[i], 0));
        }
        int total = (int) ((endMs + 200L) * sampleRate / 1000L);
        phase = 0;
        float level = 0f;
        short[] pcm = new short[HOP];
        int produced = 0;
        Log.i(TAG, "replay " + replayMidi.length + " notes");
        while (running && produced < total) {
            long start = System.nanoTime();
            float shownHz = 0f;
            for (int i = 0; i < HOP; i++) {
                int timeMs = (int) ((produced + i) * 1000L / sampleRate);
                float hz = Pitch.replayHz(replayMidi, replayCents, replayStart, replayDuration,
                        referenceHz, timeMs);
                if (i == 0) {
                    shownHz = hz;
                }
                float target = 0f;
                if (hz > 0f) {
                    phase += 2.0 * Math.PI * hz / sampleRate;
                    if (phase > 2.0 * Math.PI) {
                        phase -= 2.0 * Math.PI;
                    }
                    target = (float) Math.sin(phase);
                }
                level += (target - level) * 0.2f;
                pcm[i] = (short) (0.22f * level * 32767);
            }
            writePcm(pcm);
            String name = "";
            int cents = 0;
            if (shownHz > 0f) {
                name = Pitch.noteName(shownHz, referenceHz);
                if (name == null) {
                    name = "";
                }
                cents = name.isEmpty() ? 0 : Pitch.cents(shownHz, referenceHz);
            }
            post("Replay", name, cents, shownHz);
            produced += HOP;
            pace(start);
        }
        boolean natural = running;
        running = false;
        releaseAudio();
        Log.i(TAG, natural ? "replay done" : "replay stopped");
        if (natural) {
            post("Ready", "", 0, 0f);
            main.post(() -> {
                Listener l = listener;
                if (l != null) {
                    l.onReplayFinished();
                }
            });
        }
    }

    private void pace(long startNs) {
        long hopNs = (long) (HOP * 1_000_000_000.0 / sampleRate);
        long remain = hopNs - (System.nanoTime() - startNs);
        if (remain > 1_000_000L) {
            try {
                Thread.sleep(remain / 1_000_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void writePcm(short[] pcm) {
        AudioTrack t = track;
        if (t == null) {
            return;
        }
        t.write(pcm, 0, pcm.length);
    }

    private static int outputRate() {
        int min = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        return min > 0 ? 48000 : 44100;
    }

    private void openTrack(int rate) {
        int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            Log.e(TAG, "no output at " + rate);
            return;
        }
        // A fifth of a second of audio, so a slow pitch frame does not starve the speaker.
        int bytes = Math.max(min, rate / 5 * 2);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(bytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            track = null;
            Log.e(TAG, "AudioTrack failed");
            return;
        }
        track.play();
    }

    private static AudioRecord openMic(int[] rateOut) {
        int[] rates = {48000, 44100, 16000};
        int[] sources = {
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
        };
        for (int source : sources) {
            for (int rate : rates) {
                int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (min <= 0) {
                    continue;
                }
                try {
                    AudioRecord rec = new AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, Math.max(min, rate / 5));
                    if (rec.getState() == AudioRecord.STATE_INITIALIZED) {
                        rateOut[0] = rate;
                        return rec;
                    }
                    rec.release();
                } catch (RuntimeException e) {
                    Log.e(TAG, "mic " + rate, e);
                }
            }
        }
        return null;
    }

    private void releaseAudio() {
        if (track != null) {
            try {
                track.pause();
                track.flush();
                track.release();
            } catch (RuntimeException ignored) {
            }
            track = null;
        }
        if (record != null) {
            try {
                record.release();
            } catch (RuntimeException ignored) {
            }
            record = null;
        }
    }
}
