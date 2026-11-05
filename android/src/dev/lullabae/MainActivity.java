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

    private boolean listening;
    private boolean replaying;
    private boolean wantMic;
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
        demoButton = findViewById(R.id.demo);
        listenButton = findViewById(R.id.listen);
        replayButton = findViewById(R.id.replay);
        TextView referenceLabel = findViewById(R.id.reference_label);
        SeekBar reference = findViewById(R.id.reference);

        demoButton.setOnClickListener(v -> startDemo());
        listenButton.setOnClickListener(v -> requestListen());
        replayButton.setOnClickListener(v -> playReplay());
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
        replayButton.setAlpha(tones.isEmpty() ? 0.45f : 1f);
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
