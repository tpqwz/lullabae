package dev.lullabae;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** The microphone waveform. Shown only when the listener asks for it. */
public class WaveView extends View {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axis = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float[] points;

    public WaveView(Context context) {
        super(context);
        init();
    }

    public WaveView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        axis.setStyle(Paint.Style.STROKE);
    }

    public void setSamples(float[] points) {
        this.points = points;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float density = getResources().getDisplayMetrics().density;
        float width = getWidth();
        float height = getHeight();
        float pad = 4 * density;
        float center = height / 2f;
        axis.setColor(0xFF2A3140);
        axis.setStrokeWidth(density);
        canvas.drawLine(pad, center, width - pad, center, axis);
        if (points == null || points.length < 2) {
            return;
        }
        line.setColor(0xFF8FCBB0);
        line.setStrokeWidth(2f * density);
        float span = width - 2f * pad;
        float amp = (height / 2f - pad) * 0.92f;
        float x0 = pad;
        float y0 = center - clamp(points[0]) * amp;
        for (int i = 1; i < points.length; i++) {
            float x1 = pad + span * i / (points.length - 1);
            float y1 = center - clamp(points[i]) * amp;
            canvas.drawLine(x0, y0, x1, y1, line);
            x0 = x1;
            y0 = y1;
        }
    }

    private static float clamp(float sample) {
        if (sample < -1f) {
            return -1f;
        }
        if (sample > 1f) {
            return 1f;
        }
        return sample;
    }
}
