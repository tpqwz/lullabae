package dev.lullabae;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** Cents gauge. Flat is left, sharp is right, and the center is in tune. */
public class GaugeView extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint marker = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int cents;
    private boolean active;

    public GaugeView(Context context) {
        super(context);
        init();
    }

    public GaugeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        tick.setStrokeCap(Paint.Cap.ROUND);
        label.setTextAlign(Paint.Align.CENTER);
    }

    public void setCents(int cents, boolean active) {
        int clamped = Math.max(-50, Math.min(50, cents));
        if (clamped == this.cents && active == this.active) {
            return;
        }
        this.cents = clamped;
        this.active = active;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float density = getResources().getDisplayMetrics().density;
        float width = getWidth();
        float height = getHeight();
        float pad = 8 * density;
        float left = pad;
        float right = width - pad;
        float centerY = height * 0.38f;
        float trackHeight = 6 * density;

        track.setColor(0xFF2A3140);
        canvas.drawRoundRect(left, centerY - trackHeight / 2f, right, centerY + trackHeight / 2f,
                trackHeight, trackHeight, track);

        for (int mark = -50; mark <= 50; mark += 10) {
            float x = left + (mark + 50) / 100f * (right - left);
            float half = (mark == 0 ? 18 : 9) * density;
            tick.setColor(mark == 0 ? 0xFFF4EFE6 : 0xFF5C6574);
            tick.setStrokeWidth((mark == 0 ? 2.5f : 1.5f) * density);
            canvas.drawLine(x, centerY - half, x, centerY + half, tick);
        }

        float x = left + (cents + 50) / 100f * (right - left);
        if (!active) {
            marker.setColor(0xFF3C4454);
        } else if (Math.abs(cents) <= 10) {
            marker.setColor(0xFF8FCBB0);
        } else {
            marker.setColor(0xFFE3C28A);
        }
        canvas.drawCircle(x, centerY, 11 * density, marker);

        label.setColor(0xFF8B93A1);
        label.setTextSize(12 * density);
        float baseline = height - 14 * density;
        label.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("flat", left, baseline, label);
        label.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("in tune", (left + right) / 2f, baseline, label);
        label.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("sharp", right, baseline, label);
    }
}
