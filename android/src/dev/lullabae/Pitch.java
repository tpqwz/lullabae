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
     * "N E4 0 500 1 0 64 1200" for a note (name, cents, ms, string, fret, midi, start),
     * "T 20 800" for a tap. Empty when nothing closed on this hop.
     */
    public static native String takeTranscript(float hz, float rms, float hopSec, float a4);

    /** Notes still held when listening stops. Same lines as takeTranscript. */
    public static native String flushTranscript();

    /** Frequency of a take at timeMs, or 0 during a rest. Parallel arrays, capped at 512. */
    public static native float replayHz(int[] midi, int[] cents, int[] startMs, int[] durationMs,
                                         float a4, int timeMs);

    /**
     * The take as a MIDI file. tap[i] is 1 for a beat and 0 for a note.
     * Empty arrays are still a file. Null when the take cannot be written.
     */
    public static native byte[] writeMidi(int[] midi, int[] cents, int[] startMs, int[] durationMs,
                                           int[] tap);

    /**
     * Notes in a take file. "N midi cents duration start" or "T duration start".
     * Empty when the file has no notes. Null when it is not a take.
     */
    public static native String readMidi(byte[] file);
}
