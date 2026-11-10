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

        void onTap(int durationMs, int startMs);

        void onScoreCleared();

        void onReplayFinished();

        /** Latest microphone shape, only while the trace is switched on. */
        void onWave(float[] points);
    }

    private static final String TAG = "Lullabae";
    private static final int WINDOW = 2048;
    private static final int HOP = 1024;
    // midi, cents, milliseconds. A midi below 0 is a rest between tunes.
    private static final Tune[] TUNES = {
            // Top line of the Menuet in D, WeissSW 92.9, sounding pitch, one pass.
            new Tune("Weiss Menuet", new int[][]{
                    {66, 0, 250}, {67, 0, 250}, {69, 0, 500}, {62, 0, 500}, {61, 0, 1000}, {61, 0, 250},
                    {62, 0, 250}, {62, 0, 250}, {64, 0, 500}, {64, 0, 250}, {69, 0, 250}, {67, 0, 250},
                    {66, 0, 250}, {67, 0, 250}, {66, 0, 250}, {64, 0, 250}, {62, 0, 500}, {-1, 0, 250},
                    {62, 0, 250}, {71, 0, 250}, {62, 0, 250}, {71, 0, 250}, {62, 0, 250}, {-1, 0, 250},
                    {62, 0, 250}, {69, 0, 250}, {62, 0, 250}, {69, 0, 250}, {62, 0, 250}, {-1, 0, 250},
                    {67, 0, 250}, {71, 0, 250}, {69, 0, 250}, {67, 0, 250}, {66, 0, 250}, {64, 0, 1000},
                    {64, 0, 500}, {-1, 0, 250}, {64, 0, 500}, {64, 0, 250}, {64, 0, 250}, {64, 0, 250},
                    {61, 0, 500}, {59, 0, 250}, {61, 0, 250}, {57, 0, 500}, {-1, 0, 250}, {66, 0, 250},
                    {59, 0, 250}, {62, 0, 250}, {61, 0, 250}, {59, 0, 250}, {58, 0, 500}, {56, 0, 250},
                    {58, 0, 250}, {54, 0, 500}, {-1, 0, 250}, {66, 0, 250}, {59, 0, 250}, {66, 0, 250},
                    {59, 0, 250}, {66, 0, 250}, {-1, 0, 250}, {64, 0, 250}, {58, 0, 250}, {64, 0, 250},
                    {58, 0, 250}, {64, 0, 250}, {62, 0, 1000}, {58, 0, 500}, {59, 0, 1500}, {-1, 0, 250},
                    {62, 0, 250}, {57, 0, 250}, {62, 0, 250}, {62, 0, 250}, {62, 0, 250}, {59, 0, 250},
                    {61, 0, 250}, {62, 0, 250}, {59, 0, 250}, {64, 0, 500}, {-1, 0, 250}, {64, 0, 250},
                    {59, 0, 250}, {64, 0, 250}, {64, 0, 250}, {64, 0, 250}, {61, 0, 250}, {62, 0, 250},
                    {64, 0, 250}, {61, 0, 250}, {67, 0, 500}, {69, 0, 500}, {57, 0, 250}, {59, 0, 250},
                    {61, 0, 250}, {57, 0, 250}, {67, 0, 500}, {57, 0, 250}, {59, 0, 250}, {61, 0, 250},
                    {57, 0, 250}, {66, 0, 500}, {62, 0, 500}, {61, 0, 500}, {62, 0, 1000}, {62, 0, 500},
                    {-1, 0, 700},
            }),
            // Top line of the Bourree in B-flat, WeissSW 23.3, through the first repeat.
            new Tune("Weiss Bourree", new int[][]{
                    {65, 0, 400}, {65, 0, 400}, {58, 0, 400}, {58, 0, 400}, {60, 0, 400}, {62, 0, 400},
                    {60, 0, 200}, {62, 0, 200}, {58, 0, 400}, {65, 0, 400}, {65, 0, 400}, {58, 0, 200},
                    {57, 0, 200}, {58, 0, 400}, {60, 0, 400}, {62, 0, 400}, {60, 0, 200}, {62, 0, 200},
                    {58, 0, 400}, {65, 0, 400}, {67, 0, 400}, {60, 0, 200}, {59, 0, 200}, {60, 0, 400},
                    {62, 0, 200}, {63, 0, 200}, {65, 0, 400}, {58, 0, 200}, {57, 0, 200}, {58, 0, 400},
                    {60, 0, 200}, {62, 0, 200}, {63, 0, 400}, {57, 0, 400}, {58, 0, 400}, {62, 0, 400},
                    {60, 0, 1200}, {60, 0, 400}, {58, 0, 400}, {55, 0, 200}, {57, 0, 200}, {58, 0, 400},
                    {53, 0, 400}, {67, 0, 800}, {60, 0, 400}, {58, 0, 400}, {58, 0, 400}, {55, 0, 200},
                    {57, 0, 200}, {58, 0, 400}, {53, 0, 400}, {67, 0, 800}, {60, 0, 400}, {58, 0, 400},
                    {57, 0, 400}, {55, 0, 200}, {57, 0, 200}, {58, 0, 800}, {60, 0, 400}, {58, 0, 200},
                    {60, 0, 200}, {62, 0, 800}, {64, 0, 400}, {62, 0, 200}, {64, 0, 200}, {65, 0, 800},
                    {67, 0, 400}, {70, 0, 200}, {69, 0, 200}, {67, 0, 400}, {60, 0, 400}, {70, 0, 400},
                    {67, 0, 200}, {69, 0, 200}, {70, 0, 400}, {67, 0, 400}, {64, 0, 400}, {67, 0, 400},
                    {64, 0, 400}, {60, 0, 400}, {58, 0, 400}, {55, 0, 200}, {57, 0, 200}, {58, 0, 400},
                    {55, 0, 400}, {52, 0, 800}, {65, 0, 800}, {-1, 0, 400}, {67, 0, 400}, {64, 0, 800},
                    {65, 0, 400}, {53, 0, 800}, {60, 0, 400},
                    {-1, 0, 700},
            }),
            // Galloping bowing, then the singing theme, of the horse-race song.
            new Tune("Horse Race", new int[][]{
{74, 0, 240}, {69, 0, 80}, {72, 0, 320}, {74, 0, 240}, {69, 0, 80}, {72, 0, 320},
                    {74, 0, 240}, {69, 0, 80}, {72, 0, 320}, {74, 0, 240}, {69, 0, 80}, {72, 0, 320},
                    {74, 0, 80}, {72, 0, 80}, {69, 0, 80}, {72, 0, 80}, {74, 0, 80}, {72, 0, 80},
                    {69, 0, 80}, {72, 0, 80}, {74, 0, 80}, {72, 0, 80}, {69, 0, 80}, {72, 0, 80},
                    {74, 0, 80}, {72, 0, 80}, {69, 0, 80}, {72, 0, 80}, {74, 0, 160}, {72, 0, 80},
                    {74, 0, 80}, {74, 0, 160}, {72, 0, 80}, {74, 0, 80}, {74, 0, 160}, {72, 0, 80},
                    {74, 0, 80}, {74, 0, 160}, {72, 0, 80}, {74, 0, 80}, {74, 0, 160}, {72, 0, 80},
                    {74, 0, 80}, {74, 0, 160}, {72, 0, 80}, {74, 0, 80}, {74, 0, 160}, {72, 0, 80},
                    {74, 0, 80}, {74, 0, 160}, {72, 0, 80}, {74, 0, 80}, {74, 0, 160}, {69, 0, 160},
                    {65, 0, 160}, {62, 0, 160}, {69, 0, 160}, {74, 0, 160}, {72, 0, 160}, {69, 0, 160},
                    {67, 0, 80}, {69, 0, 80}, {67, 0, 80}, {65, 0, 80}, {67, 0, 80}, {69, 0, 80},
                    {67, 0, 80}, {65, 0, 80}, {67, 0, 80}, {69, 0, 80}, {67, 0, 80}, {65, 0, 80},
                    {67, 0, 80}, {69, 0, 80}, {67, 0, 80}, {65, 0, 80}, {-1, 0, 400},
                    {72, 0, 200}, {74, 0, 200}, {77, 0, 400}, {74, 0, 800},
                    {69, 0, 400}, {74, 0, 300}, {77, 0, 100}, {72, 0, 400}, {72, 0, 200}, {69, 0, 200},
                    {67, 0, 200}, {69, 0, 200}, {74, 0, 200}, {72, 0, 200}, {69, 0, 800},
                    {72, 0, 400}, {74, 0, 300}, {77, 0, 100}, {65, 0, 600}, {62, 0, 200},
                    {67, 0, 200}, {69, 0, 200}, {74, 0, 200}, {72, 0, 200}, {69, 0, 400}, {69, 0, 200}, {67, 0, 200},
                    {65, 0, 300}, {67, 0, 100}, {69, 0, 200}, {72, 0, 200}, {74, 0, 400}, {74, 0, 400},
                    {67, 0, 200}, {69, 0, 200}, {77, 0, 400}, {74, 0, 800},
                    {72, 0, 200}, {74, 0, 200}, {77, 0, 400}, {74, 0, 800},
                    {69, 0, 400}, {74, 0, 300}, {77, 0, 100}, {72, 0, 400}, {72, 0, 200}, {69, 0, 200},
                    {67, 0, 200}, {69, 0, 200}, {74, 0, 200}, {72, 0, 200}, {69, 0, 800},
                    {72, 0, 400}, {74, 0, 300}, {77, 0, 100}, {65, 0, 600}, {62, 0, 200},
                    {67, 0, 200}, {69, 0, 200}, {74, 0, 200}, {72, 0, 200}, {69, 0, 400}, {69, 0, 200}, {67, 0, 200},
                    {65, 0, 300}, {67, 0, 100}, {69, 0, 200}, {72, 0, 200}, {74, 0, 400}, {74, 0, 400},
                    {67, 0, 200}, {69, 0, 200}, {77, 0, 400}, {74, 0, 800},
                    {-1, 0, 700},
            }),

            // Opening of the Fantasia in B-flat, Vienna ms., top line.
            new Tune("Weiss Fantasie", new int[][]{
{65, 0, 800}, {67, 0, 100}, {65, 0, 100}, {63, 0, 100}, {62, 0, 100}, {67, 0, 100},
                    {65, 0, 100}, {63, 0, 100}, {62, 0, 100}, {60, 0, 100}, {58, 0, 100}, {60, 0, 100},
                    {62, 0, 100}, {63, 0, 100}, {65, 0, 100}, {65, 0, 400}, {65, 0, 400}, {65, 0, 400},
                    {65, 0, 400}, {62, 0, 400}, {62, 0, 400}, {62, 0, 400}, {62, 0, 400}, {67, 0, 400},
                    {67, 0, 400}, {67, 0, 400}, {67, 0, 400}, {65, 0, 400}, {65, 0, 400}, {65, 0, 400},
                    {65, 0, 400}, {63, 0, 400}, {63, 0, 400}, {63, 0, 400}, {65, 0, 400}, {62, 0, 400},
                    {65, 0, 400}, {62, 0, 400}, {63, 0, 400}, {63, 0, 400}, {62, 0, 400}, {62, 0, 400},
                    {62, 0, 400}, {62, 0, 400}, {62, 0, 400}, {62, 0, 400}, {62, 0, 400}, {62, 0, 400},
                    {62, 0, 400}, {62, 0, 400}, {62, 0, 400}, {60, 0, 400}, {60, 0, 400}, {60, 0, 400},
                    {60, 0, 400}, {60, 0, 400}, {60, 0, 400}, {60, 0, 400}, {60, 0, 400}, {58, 0, 400},
                    {58, 0, 400}, {58, 0, 400}, {58, 0, 400},
                    {-1, 0, 700},
            }),
            // Opening ground of the G minor ciacona, top voice, four times.
            new Tune("Weiss Ciacona", new int[][]{
{62, 0, 520}, {60, 0, 520}, {62, 0, 520}, {62, 0, 520}, {59, 0, 520}, {59, 0, 520},
                    {60, 0, 520}, {57, 0, 520}, {57, 0, 520}, {62, 0, 520}, {60, 0, 520}, {62, 0, 520},
                    {62, 0, 520}, {59, 0, 520}, {59, 0, 520}, {60, 0, 520}, {57, 0, 520}, {57, 0, 520},
                    {62, 0, 520}, {60, 0, 520}, {62, 0, 520}, {62, 0, 520}, {59, 0, 520}, {59, 0, 520},
                    {60, 0, 520}, {57, 0, 520}, {57, 0, 520}, {62, 0, 520}, {60, 0, 520}, {62, 0, 520},
                    {62, 0, 520}, {59, 0, 520}, {59, 0, 520}, {60, 0, 520}, {57, 0, 520}, {57, 0, 520},
                    {57, 0, 260}, {60, 0, 260}, {62, 0, 260},
                    {-1, 0, 700},
            }),
            // Qinghai folk song, the tune called Hua'er and the Youth.
            new Tune("Hua'er", new int[][]{
{76, 0, 240}, {81, 0, 240}, {76, 0, 120}, {79, 0, 120}, {76, 0, 120}, {74, 0, 120},
                    {76, 0, 120}, {74, 0, 120}, {72, 0, 120}, {71, 0, 120}, {69, 0, 480}, {81, 0, 240},
                    {81, 0, 120}, {83, 0, 120}, {81, 0, 120}, {79, 0, 120}, {76, 0, 120}, {79, 0, 120},
                    {81, 0, 960}, {81, 0, 240}, {81, 0, 120}, {83, 0, 120}, {81, 0, 120}, {79, 0, 120},
                    {76, 0, 120}, {79, 0, 120}, {81, 0, 960},
                    {-1, 0, 700},
            }),
            // The school-song standard Su Wu the Shepherd.
            new Tune("Su Wu", new int[][]{
{67, 0, 480}, {60, 0, 480}, {62, 0, 240}, {67, 0, 240}, {65, 0, 240}, {62, 0, 240},
                    {60, 0, 960}, {72, 0, 240}, {74, 0, 240}, {72, 0, 240}, {69, 0, 240}, {67, 0, 960},
                    {65, 0, 240}, {62, 0, 240}, {65, 0, 240}, {69, 0, 240}, {67, 0, 960}, {72, 0, 240},
                    {69, 0, 240}, {67, 0, 480}, {65, 0, 240}, {69, 0, 240}, {67, 0, 480}, {62, 0, 240},
                    {67, 0, 240}, {65, 0, 240}, {62, 0, 240}, {60, 0, 960}, {62, 0, 240}, {62, 0, 240},
                    {62, 0, 240}, {69, 0, 240}, {67, 0, 720}, {64, 0, 240}, {62, 0, 240}, {60, 0, 240},
                    {57, 0, 240}, {55, 0, 120}, {57, 0, 120}, {60, 0, 960}, {67, 0, 240}, {67, 0, 240},
                    {69, 0, 240}, {72, 0, 240}, {64, 0, 960}, {67, 0, 240}, {64, 0, 240}, {62, 0, 120},
                    {64, 0, 120}, {67, 0, 240}, {60, 0, 960}, {60, 0, 240}, {62, 0, 240}, {65, 0, 240},
                    {65, 0, 240}, {62, 0, 240}, {65, 0, 240}, {62, 0, 240}, {60, 0, 240}, {57, 0, 240},
                    {60, 0, 240}, {62, 0, 240}, {65, 0, 240}, {60, 0, 960},
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
    private volatile String songName = "Weiss Menuet";
    private int noteSamples = 48000;
    private int songAt;
    private int noteAt;
    private int produced;
    private boolean joined;
    private double phase;
    private float shownHz;
    private int quietHops;
    private int voicedHops;
    private int harmHops;
    private float displayHz;
    private volatile boolean showWave;
    private int[] replayMidi = new int[0];
    private int[] replayCents = new int[0];
    private int[] replayStart = new int[0];
    private int[] replayDuration = new int[0];

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Draw the microphone only when this is on. It stays off until asked. */
    public void setShowWave(boolean show) {
        showWave = show;
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
        quietHops = 0;
        voicedHops = 0;
        harmHops = 0;
        displayHz = 0f;
        post("Listening", "", 0, 0f);
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
                if (showWave) {
                    postWave();
                }
            }
            // Room noise used to blank the screen every hop. A hum that wobbles
            // across a semitone never kept one name, so the note stayed blank.
            // Show the pitch once it is present, and ignore a short harmonic flare.
            boolean loud = filled >= WINDOW && rms >= 0.006f && shownHz > 0f;
            if (!loud) {
                voicedHops = 0;
                harmHops = 0;
                quietHops++;
                if (quietHops == 8) {
                    displayHz = 0f;
                    post("Listening", "", 0, 0f);
                }
                continue;
            }
            quietHops = 0;
            if (displayHz > 0f && harmonicOf(shownHz, displayHz)) {
                harmHops++;
                if (harmHops < 4) {
                    continue;
                }
            }
            harmHops = 0;
            if (displayHz <= 0f || !withinCents(shownHz, displayHz, 110f)) {
                displayHz = shownHz;
                voicedHops = 1;
            } else {
                displayHz = displayHz * 0.8f + shownHz * 0.2f;
                voicedHops++;
            }
            if (voicedHops >= 4) {
                String name = Pitch.noteName(displayHz, referenceHz);
                if (name != null && !name.isEmpty()) {
                    post("Listening", name, Pitch.cents(displayHz, referenceHz), displayHz);
                }
            }
        }
    }

    private static boolean withinCents(float a, float b, float cents) {
        if (a <= 0f || b <= 0f) {
            return false;
        }
        float gap = Math.abs(1200f * (float) (Math.log(a / b) / Math.log(2.0)));
        return gap < cents;
    }

    /** True when one pitch is the 2nd, 3rd, or 4th harmonic of the other. */
    private static boolean harmonicOf(float a, float b) {
        if (a <= 0f || b <= 0f) {
            return false;
        }
        float ratio = a > b ? a / b : b / a;
        for (int multiple = 2; multiple <= 4; multiple++) {
            if (withinCents(ratio, multiple, 60f)) {
                return true;
            }
        }
        return false;
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
        if (raw == null || raw.isEmpty()) {
            return;
        }
        for (String line : raw.split("\n")) {
            deliver(line);
        }
    }

    /** Stop the mic and hand the note still sounding to the listener, on this thread. */
    public void captureTail() {
        stop();
        String raw = Pitch.flushTranscript();
        if (raw == null || raw.isEmpty()) {
            return;
        }
        for (String line : raw.split("\n")) {
            apply(line);
        }
    }

    private void deliver(String line) {
        main.post(() -> apply(line));
    }

    private void apply(String line) {
        String[] parts = line.split(" ");
        Listener l = listener;
        if (l == null) {
            return;
        }
        if (parts.length == 8 && "N".equals(parts[0])) {
            String name = parts[1];
            int cents = Integer.parseInt(parts[2]);
            int durationMs = Integer.parseInt(parts[3]);
            int stringNum = Integer.parseInt(parts[4]);
            int fret = Integer.parseInt(parts[5]);
            int midi = Integer.parseInt(parts[6]);
            int startMs = Math.max(0, Integer.parseInt(parts[7]));
            l.onNote(name, cents, durationMs, stringNum, fret, startMs, midi);
        } else if (parts.length >= 3 && "T".equals(parts[0])) {
            int durationMs = Integer.parseInt(parts[1]);
            int startMs = Math.max(0, Integer.parseInt(parts[2]));
            l.onTap(durationMs, startMs);
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

    private void postWave() {
        final int points = 64;
        float[] shape = new float[points];
        int step = WINDOW / points;
        for (int i = 0; i < points; i++) {
            shape[i] = window[i * step];
        }
        Listener l = listener;
        if (l == null) {
            return;
        }
        main.post(() -> l.onWave(shape));
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
