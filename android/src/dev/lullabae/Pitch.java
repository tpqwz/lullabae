package dev.lullabae;

public final class Pitch {
    static {
        System.loadLibrary("lullabae");
    }

    private Pitch() {}

    public static native void init(double sampleRate);

    /** Last 2048 mono samples in [-1, 1]. Returns Hz, or 0 when no pitch is found. */
    public static native float detect(float[] samples);

    /** a4 is the tuning pitch for MIDI 69, in Hz. */
    public static native String noteName(float hz, float a4);

    public static native int cents(float hz, float a4);

    public static native void resetTranscript(float a4);

    /**
     * One hop of audio. Returns finished events, one per line:
     * "N E4 0 500 1 0 64" for a note (name, cents, ms, string, fret, midi),
     * "T 20" for a tap. Empty when nothing closed on this hop.
     */
    public static native String takeTranscript(float hz, float rms, float hopSec, float a4);

    /** Frequency of a take at timeMs, or 0 during a rest. Parallel arrays, capped at 512. */
    public static native float replayHz(int[] midi, int[] cents, int[] startMs, int[] durationMs,
                                         float a4, int timeMs);
}
