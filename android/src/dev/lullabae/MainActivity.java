package dev.lullabae;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
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
    private TextView midiButton;
    private TextView takesButton;
    private View scoreScroll;
    private ScrollView takesScroll;
    private LinearLayout takesList;
    private WaveView waveView;

    private boolean listening;
    private boolean replaying;
    private boolean wantMic;
    private boolean showWave;
    private boolean playingMidi;
    private boolean showTakes;
    private boolean micTake;
    private boolean dirty;
    private boolean restoring;
    private String openLabel;
    private final ArrayList<Tone> tones = new ArrayList<>();
    private final ArrayList<Hit> taps = new ArrayList<>();
    private Uri midiUri;
    private MediaPlayer midiPlayer;
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
        midiButton = findViewById(R.id.midi);
        takesButton = findViewById(R.id.takes_button);
        scoreScroll = findViewById(R.id.score_scroll);
        takesScroll = findViewById(R.id.takes_scroll);
        takesList = findViewById(R.id.takes);
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
        midiButton.setOnClickListener(v -> playMidi());
        midiButton.setOnLongClickListener(v -> {
            shareMidi();
            return true;
        });
        takesButton.setOnClickListener(v -> {
            showTakes = !showTakes;
            if (showTakes) {
                fillTakes();
            }
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
            // Leaving the app ended the previous take. This listen is a new one.
            midiUri = null;
            openLabel = null;
            dirty = false;
            micTake = true;
            showTakes = false;
            listening = true;
            if (!engine.startMic()) {
                wantMic = false;
                listening = false;
                statusView.setText("Microphone unavailable");
            }
        } else {
            listening = false;
            replaying = false;
            statusView.setText(openLabel != null ? openLabel : "Ready");
        }
        styleButtons();
    }

    @Override
    protected void onPause() {
        boolean wasListening = listening;
        endListen();
        if (wasListening) {
            wantMic = false;
        }
        stopMidi();
        engine.stop();
        super.onPause();
    }

    private void startDemo() {
        stopMidi();
        endListen();
        wantMic = false;
        listening = false;
        replaying = false;
        micTake = false;
        midiUri = null;
        openLabel = null;
        dirty = false;
        engine.startDemo();
        styleButtons();
    }

    private void playReplay() {
        stopMidi();
        endListen();
        wantMic = false;
        listening = false;
        if (tones.isEmpty()) {
            Log.i(TAG, "replay empty");
            styleButtons();
            return;
        }
        int n = tones.size();
        int[] midi = new int[n];
        int[] cents = new int[n];
        int[] startMs = new int[n];
        int[] durationMs = new int[n];
        int origin = Integer.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            if (tones.get(i).startMs < origin) {
                origin = tones.get(i).startMs;
            }
        }
        for (int i = 0; i < n; i++) {
            Tone tone = tones.get(i);
            midi[i] = tone.midi;
            cents[i] = tone.cents;
            startMs[i] = Math.max(0, tone.startMs - origin);
            durationMs[i] = tone.durationMs;
        }
        replaying = true;
        styleButtons();
        engine.replay(midi, cents, startMs, durationMs);
    }

    /** The note still sounding is part of the take, then the take is saved. */
    private void endListen() {
        if (listening) {
            engine.captureTail();
            listening = false;
        }
        seal();
    }

    private void requestListen() {
        stopMidi();
        endListen();
        midiUri = null;
        openLabel = null;
        dirty = false;
        micTake = true;
        showTakes = false;
        wantMic = true;
        replaying = false;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQUEST);
            return;
        }
        listening = true;
        styleButtons();
        if (!engine.startMic()) {
            wantMic = false;
            listening = false;
            statusView.setText("Microphone unavailable");
            styleButtons();
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
        if (!restoring) {
            dirty = true;
            Log.i(TAG, "wrote " + name + " string " + stringNum + " fret " + fret + " " + durationMs + "ms");
        }
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
        dirty = true;
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
    public void onTap(int durationMs, int startMs) {
        Hit hit = new Hit();
        hit.startMs = startMs;
        hit.durationMs = durationMs;
        taps.add(hit);
        if (taps.size() > 512) {
            taps.remove(0);
        }
        score.addTap();
        if (!restoring) {
            dirty = true;
            Log.i(TAG, "wrote tap");
        }
    }

    @Override
    public void onScoreCleared() {
        tones.clear();
        taps.clear();
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
        paint(demoButton, !listening && !replaying && !playingMidi);
        paint(listenButton, listening);
        paint(replayButton, replaying);
        paint(waveButton, showWave);
        paint(midiButton, playingMidi);
        paint(takesButton, showTakes);
        replayButton.setAlpha(tones.isEmpty() ? 0.45f : 1f);
        midiButton.setAlpha(tones.isEmpty() && taps.isEmpty() ? 0.45f : 1f);
        waveView.setVisibility(showWave && listening ? View.VISIBLE : View.GONE);
        scoreScroll.setVisibility(showTakes ? View.GONE : View.VISIBLE);
        takesScroll.setVisibility(showTakes ? View.VISIBLE : View.GONE);
    }

    private static final class Tone {
        int midi;
        int cents;
        int startMs;
        int durationMs;
    }

    private static final class Hit {
        int startMs;
        int durationMs;
    }

    /** The notes on screen, first event at time zero. */
    private byte[] takeBytes() {
        int notes = tones.size();
        int hits = taps.size();
        int n = notes + hits;
        int[] midi = new int[n];
        int[] cents = new int[n];
        int[] startMs = new int[n];
        int[] durationMs = new int[n];
        int[] tap = new int[n];
        int origin = Integer.MAX_VALUE;
        for (int i = 0; i < notes; i++) {
            Tone tone = tones.get(i);
            midi[i] = tone.midi;
            cents[i] = tone.cents;
            startMs[i] = tone.startMs;
            durationMs[i] = tone.durationMs;
            if (tone.startMs < origin) {
                origin = tone.startMs;
            }
        }
        for (int i = 0; i < hits; i++) {
            Hit hit = taps.get(i);
            int at = notes + i;
            startMs[at] = hit.startMs;
            durationMs[at] = hit.durationMs;
            tap[at] = 1;
            if (hit.startMs < origin) {
                origin = hit.startMs;
            }
        }
        if (origin == Integer.MAX_VALUE) {
            origin = 0;
        }
        for (int i = 0; i < n; i++) {
            startMs[i] = Math.max(0, startMs[i] - origin);
        }
        return Pitch.writeMidi(midi, cents, startMs, durationMs, tap);
    }

    /** Write this mic take once. Later edits rewrite that same file. */
    private boolean seal() {
        if (!dirty) {
            return midiUri != null;
        }
        if (tones.isEmpty() && taps.isEmpty()) {
            dirty = false;
            return false;
        }
        if (midiUri == null && !micTake) {
            dirty = false;
            return false;
        }
        byte[] bytes = takeBytes();
        Uri uri = midiUri;
        String created = null;
        if (uri == null) {
            created = takeName();
            uri = insertTake(created);
        }
        if (bytes == null || uri == null || !writeTake(uri, bytes)) {
            Log.i(TAG, "midi not written");
            return false;
        }
        midiUri = uri;
        if (created != null) {
            openLabel = takeLabel(created);
        }
        dirty = false;
        Log.i(TAG, "saved " + openLabel);
        if (showTakes) {
            fillTakes();
        }
        return true;
    }

    /** Play the saved take. The mic stays off so the speaker is not heard again. */
    private void playMidi() {
        stopMidi();
        endListen();
        wantMic = false;
        listening = false;
        replaying = false;
        if (midiUri == null) {
            Log.i(TAG, "midi empty");
            statusView.setText(openLabel != null ? openLabel : "Ready");
            styleButtons();
            return;
        }
        MediaPlayer player = new MediaPlayer();
        try {
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            player.setDataSource(this, midiUri);
            player.setOnCompletionListener(p -> finishMidi(p));
            player.setOnErrorListener((p, what, extra) -> {
                Log.e(TAG, "midi play " + what + " " + extra);
                finishMidi(p);
                return true;
            });
            player.prepare();
            midiPlayer = player;
            playingMidi = true;
            statusView.setText("MIDI");
            styleButtons();
            player.start();
            Log.i(TAG, "midi play");
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "midi play", e);
            if (midiPlayer != null) {
                stopMidi();
            } else {
                player.release();
            }
            statusView.setText("Ready");
            styleButtons();
        }
    }

    private void finishMidi(MediaPlayer player) {
        if (player != midiPlayer) {
            return;
        }
        stopMidi();
        if (!listening && !replaying) {
            statusView.setText("Ready");
        }
        styleButtons();
    }

    private void stopMidi() {
        playingMidi = false;
        MediaPlayer player = midiPlayer;
        midiPlayer = null;
        if (player == null) {
            return;
        }
        player.setOnCompletionListener(null);
        player.setOnErrorListener(null);
        try {
            player.stop();
        } catch (IllegalStateException ignored) {
            // Released from a state that was never started.
        }
        player.release();
    }

    private void shareMidi() {
        seal();
        if (midiUri == null) {
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("audio/midi");
        send.putExtra(Intent.EXTRA_STREAM, midiUri);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "MIDI"));
    }

    // shortcut: each take is a Downloads file via MediaStore, needs Android 10.
    private boolean writeTake(Uri uri, byte[] bytes) {
        if (Build.VERSION.SDK_INT < 29) {
            Log.i(TAG, "midi needs Android 10");
            return false;
        }
        try {
            return writeUri(uri, bytes);
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "midi", e);
            return false;
        }
    }

    private boolean writeUri(Uri uri, byte[] bytes) throws IOException {
        if (uri == null) {
            return false;
        }
        try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) {
                return false;
            }
            out.write(bytes);
            return true;
        } catch (FileNotFoundException e) {
            if (uri.equals(midiUri)) {
                midiUri = null;
            }
            throw e;
        }
    }

    private static String takeName() {
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(new Date());
        return "lullabae " + stamp + ".mid";
    }

    private static String takeLabel(String name) {
        String label = name;
        if (label.endsWith(".mid")) {
            label = label.substring(0, label.length() - 4);
        }
        if (label.startsWith("lullabae ")) {
            label = label.substring("lullabae ".length());
        }
        return label;
    }

    private Uri insertTake(String name) {
        if (Build.VERSION.SDK_INT < 29 || name == null) {
            return null;
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "audio/midi");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        return getContentResolver().insert(
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
    }

    private void fillTakes() {
        takesList.removeAllViews();
        ArrayList<Take> takes = listTakes();
        if (takes.isEmpty()) {
            TextView empty = takeRow("No takes yet", false);
            takesList.addView(empty);
            return;
        }
        for (Take take : takes) {
            boolean open = take.uri.equals(midiUri);
            TextView row = takeRow(take.label, open);
            row.setOnClickListener(v -> openTake(take));
            row.setOnLongClickListener(v -> {
                confirmDelete(take);
                return true;
            });
            takesList.addView(row);
        }
    }

    private TextView takeRow(String label, boolean open) {
        TextView row = new TextView(this);
        int pad = (int) (14 * getResources().getDisplayMetrics().density);
        row.setPadding(0, pad, 0, pad);
        row.setText(label);
        row.setTextColor(open ? 0xFF8FCBB0 : 0xFFF4EFE6);
        row.setTextSize(18);
        return row;
    }

    private ArrayList<Take> listTakes() {
        ArrayList<Take> takes = new ArrayList<>();
        if (Build.VERSION.SDK_INT < 29) {
            return takes;
        }
        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String[] cols = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME};
        try (Cursor cursor = getContentResolver().query(
                collection,
                cols,
                MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?",
                new String[]{"lullabae %.mid"},
                MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
            if (cursor == null) {
                return takes;
            }
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            while (cursor.moveToNext()) {
                Take take = new Take();
                take.uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol));
                take.label = takeLabel(cursor.getString(nameCol));
                takes.add(take);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "list takes", e);
        }
        return takes;
    }

    private void openTake(Take take) {
        endListen();
        byte[] bytes = readFile(take.uri);
        String text = bytes == null ? null : Pitch.readMidi(bytes);
        if (text == null) {
            Log.i(TAG, "take unreadable");
            return;
        }
        stopMidi();
        wantMic = false;
        listening = false;
        replaying = false;
        micTake = false;
        engine.stop();
        tones.clear();
        taps.clear();
        score.clear();
        midiUri = take.uri;
        openLabel = take.label;
        dirty = false;
        restoring = true;
        try {
            if (!text.isEmpty()) {
                for (String line : text.split("\n")) {
                    restoreLine(line);
                }
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "open take", e);
        }
        restoring = false;
        showTakes = false;
        statusView.setText(take.label);
        styleButtons();
        Log.i(TAG, "opened " + take.label);
    }

    private void restoreLine(String line) {
        String[] parts = line.split(" ");
        if (parts.length == 5 && "N".equals(parts[0])) {
            int midi = Integer.parseInt(parts[1]);
            int cents = Integer.parseInt(parts[2]);
            int durationMs = Integer.parseInt(parts[3]);
            int startMs = Integer.parseInt(parts[4]);
            int[] placed = placeOnGuitar(midi);
            onNote(noteName(midi), cents, durationMs, placed[0], placed[1], startMs, midi);
        } else if (parts.length == 3 && "T".equals(parts[0])) {
            onTap(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        }
    }

    private byte[] readFile(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int total = 0;
            int n;
            while ((n = in.read(buf)) >= 0) {
                total += n;
                if (total > 1000000) {
                    return null;
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "read take", e);
            return null;
        }
    }

    private void confirmDelete(Take take) {
        new AlertDialog.Builder(this)
                .setMessage("Delete " + take.label + "?")
                .setPositiveButton("Delete", (dialog, which) -> deleteTake(take))
                .setNegativeButton("Keep", null)
                .show();
    }

    private void deleteTake(Take take) {
        try {
            getContentResolver().delete(take.uri, null, null);
        } catch (RuntimeException e) {
            Log.e(TAG, "delete take", e);
            return;
        }
        if (take.uri.equals(midiUri)) {
            stopMidi();
            midiUri = null;
            openLabel = null;
            dirty = false;
            tones.clear();
            taps.clear();
            score.clear();
            statusView.setText("Ready");
        }
        fillTakes();
        styleButtons();
        Log.i(TAG, "deleted " + take.label);
    }

    private static final class Take {
        Uri uri;
        String label;
    }

    private void paint(View view, boolean selected) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setCornerRadius(28 * getResources().getDisplayMetrics().density);
        drawable.setColor(selected ? 0xFF24382F : 0xFF1A1E26);
        view.setBackground(drawable);
    }
}
