package dev.lullabae;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity implements Engine.Listener {
    private static final int MIC_REQUEST = 1;
    private static final String TAG = "Lullabae";

    private final Engine engine = new Engine();

    private TextView statusView;
    private TextView noteView;
    private TextView detailView;
    private GaugeView gauge;
    private ScoreView score;
    private TextView demoButton;
    private TextView listenButton;
    private TextView replayButton;
    private TextView waveButton;
    private WaveView waveView;

    private boolean listening;
    private boolean replaying;
    private boolean wantMic;
    private boolean showWave;
    private final ArrayList<Tone> tones = new ArrayList<>();
    private String lastLog = "";
    private float heldHz = Float.NaN;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        statusView = findViewById(R.id.status);
        noteView = findViewById(R.id.note);
        detailView = findViewById(R.id.detail);
        gauge = findViewById(R.id.gauge);
        score = findViewById(R.id.score);
        score.setEditor(this::correct);
        demoButton = findViewById(R.id.demo);
        listenButton = findViewById(R.id.listen);
        replayButton = findViewById(R.id.replay);
        waveButton = findViewById(R.id.wave_toggle);
        waveView = findViewById(R.id.wave);
        TextView referenceLabel = findViewById(R.id.reference_label);
        SeekBar reference = findViewById(R.id.reference);

        demoButton.setOnClickListener(v -> startDemo());
        listenButton.setOnClickListener(v -> requestListen());
        replayButton.setOnClickListener(v -> playReplay());
        waveButton.setOnClickListener(v -> {
            showWave = !showWave;
            engine.setShowWave(showWave);
            styleButtons();
        });
        reference.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                referenceLabel.setText("A = " + progress + " Hz");
                engine.setReferenceHz(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {}

            @Override
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        engine.setListener(this);
        styleButtons();
        applyInsets();
    }

    private void applyInsets() {
        View root = findViewById(R.id.root);
        int baseBottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    baseBottom + insets.getSystemWindowInsetBottom());
            return insets;
        });
        root.requestApplyInsets();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (wantMic && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            listening = true;
            if (!engine.startMic()) {
                startDemo();
                return;
            }
        } else {
            listening = false;
            engine.startDemo();
        }
        styleButtons();
    }

    @Override
    protected void onPause() {
        engine.stop();
        super.onPause();
    }

    private void startDemo() {
        wantMic = false;
        listening = false;
        replaying = false;
        engine.startDemo();
        styleButtons();
    }

    private void playReplay() {
        if (tones.isEmpty()) {
            Log.i(TAG, "replay empty");
            return;
        }
        int n = tones.size();
        int[] midi = new int[n];
        int[] cents = new int[n];
        int[] startMs = new int[n];
        int[] durationMs = new int[n];
        for (int i = 0; i < n; i++) {
            Tone tone = tones.get(i);
            midi[i] = tone.midi;
            cents[i] = tone.cents;
            startMs[i] = tone.startMs;
            durationMs[i] = tone.durationMs;
        }
        wantMic = false;
        listening = false;
        replaying = true;
        styleButtons();
        engine.replay(midi, cents, startMs, durationMs);
    }

    private void requestListen() {
        wantMic = true;
        replaying = false;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQUEST);
            return;
        }
        listening = true;
        styleButtons();
        if (!engine.startMic()) {
            startDemo();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MIC_REQUEST
                && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            requestListen();
        } else if (requestCode == MIC_REQUEST) {
            wantMic = false;
            listening = false;
            statusView.setText("Microphone permission needed");
            styleButtons();
            engine.startDemo();
        }
    }

    @Override
    public void onFrame(String status, String name, int cents, float hz) {
        setText(statusView, status);
        boolean voiced = name != null && !name.isEmpty();
        if (!voiced) {
            heldHz = Float.NaN;
            setText(noteView, "—");
            setText(detailView, listening ? "hum a note" : "");
            gauge.setCents(0, false);
        } else {
            setText(noteView, name);
            setText(detailView, describe(cents, steadyHz(hz)));
            gauge.setCents(cents, true);
        }
        String line = status + " " + (voiced ? name : "—") + " " + cents;
        if (!line.equals(lastLog)) {
            lastLog = line;
            Log.i(TAG, line);
        }
    }

    @Override
    public void onNote(String name, int cents, int durationMs, int stringNum, int fret, int startMs, int midi) {
        boolean first = tones.isEmpty();
        Tone tone = new Tone();
        tone.midi = midi;
        tone.cents = cents;
        tone.startMs = startMs;
        tone.durationMs = durationMs;
        tones.add(tone);
        if (tones.size() > 512) {
            tones.remove(0);
        }
        score.addNote(name, cents, durationMs, stringNum, fret);
        if (first) {
            styleButtons();
        }
        Log.i(TAG, "wrote " + name + " string " + stringNum + " fret " + fret + " " + durationMs + "ms");
    }

    /** The ear wrote this pitch. The correction is the note replay will sing. */
    private void correct(int noteOrdinal, int steps) {
        int index = tones.size() - score.noteCount() + noteOrdinal;
        if (index < 0 || index >= tones.size() || steps == 0) {
            return;
        }
        Tone tone = tones.get(index);
        int midi = tone.midi + steps;
        if (midi < 0 || midi > 127) {
            return;
        }
        String before = noteName(tone.midi);
        tone.midi = midi;
        tone.cents = 0;
        int[] placed = placeOnGuitar(midi);
        String name = noteName(midi);
        score.replaceNote(noteOrdinal, name, 0, placed[0], placed[1]);
        Log.i(TAG, "corrected " + before + " to " + name);
    }

    private static String noteName(int midi) {
        String[] names = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
        int clamped = Math.max(0, Math.min(127, midi));
        return names[clamped % 12] + (clamped / 12 - 1);
    }

    /** Same neck rule as the transcriber: open midi high-e through low-E. */
    private static int[] placeOnGuitar(int midi) {
        int[] openMidi = {64, 59, 55, 50, 45, 40};
        int string = 0;
        int fret = 0;
        int bestFret = 99;
        for (int i = 0; i < openMidi.length; i++) {
            int candidate = midi - openMidi[i];
            if (candidate < 0 || candidate > 15) {
                continue;
            }
            if (candidate <= 5) {
                return new int[]{i + 1, candidate};
            }
            if (candidate < bestFret) {
                bestFret = candidate;
                string = i + 1;
                fret = candidate;
            }
        }
        return new int[]{string, fret};
    }

    @Override
    public void onTap() {
        score.addTap();
        Log.i(TAG, "wrote tap");
    }

    @Override
    public void onScoreCleared() {
        tones.clear();
        score.clear();
        styleButtons();
    }

    @Override
    public void onReplayFinished() {
        replaying = false;
        styleButtons();
    }

    @Override
    public void onWave(float[] points) {
        if (!showWave || !listening) {
            return;
        }
        waveView.setSamples(points);
    }

    /** Keep a tenth-of-a-hertz reading from flickering across a rounding boundary. */
    private float steadyHz(float hz) {
        if (Float.isNaN(heldHz) || Math.abs(hz - heldHz) >= 0.35f) {
            heldHz = hz;
        }
        return heldHz;
    }

    private static void setText(TextView view, String text) {
        if (!text.contentEquals(view.getText())) {
            view.setText(text);
        }
    }

    private static String describe(int cents, float hz) {
        String bias;
        if (cents == 0) {
            bias = "in tune";
        } else {
            int amount = Math.abs(cents);
            bias = amount + (amount == 1 ? " cent " : " cents ") + (cents > 0 ? "sharp" : "flat");
        }
        return bias + "   ·   " + String.format(Locale.US, "%.1f Hz", hz);
    }

    private void styleButtons() {
        paint(demoButton, !listening && !replaying);
        paint(listenButton, listening);
        paint(replayButton, replaying);
        paint(waveButton, showWave);
        replayButton.setAlpha(tones.isEmpty() ? 0.45f : 1f);
        waveView.setVisibility(showWave && listening ? View.VISIBLE : View.GONE);
    }

    private static final class Tone {
        int midi;
        int cents;
        int startMs;
        int durationMs;
    }

    private void paint(View view, boolean selected) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setCornerRadius(28 * getResources().getDisplayMetrics().density);
        drawable.setColor(selected ? 0xFF24382F : 0xFF1A1E26);
        view.setBackground(drawable);
    }
}
