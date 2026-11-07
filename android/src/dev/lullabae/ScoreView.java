package dev.lullabae;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;

import java.util.ArrayList;

/** Notes and taps already heard, left to right. A fret sits under each note. */
public class ScoreView extends View {
    /** A tap on a written note asks for that note to move by one semitone. */
    public interface Editor {
        void onShift(int noteOrdinal, int steps);
    }
    private static final String[] STRINGS = {"", "e", "B", "G", "D", "A", "E"};

    private static final class Item {
        boolean tap;
        String name;
        int cents;
        int durationMs;
        int stringNum;
        int fret;
    }

    private final ArrayList<Item> items = new ArrayList<>();
    private Editor editor;
    private float downX;
    private float downY;
    private final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint namePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tabPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centsPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public ScoreView(Context context) {
        super(context);
        init();
    }

    public ScoreView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        card.setColor(0xFF1A1E26);
        namePaint.setColor(0xFFF4EFE6);
        tabPaint.setColor(0xFF8FCBB0);
        centsPaint.setColor(0xFFE3C28A);
        emptyPaint.setColor(0xFF6E7684);
        namePaint.setTextAlign(Paint.Align.CENTER);
        tabPaint.setTextAlign(Paint.Align.CENTER);
        centsPaint.setTextAlign(Paint.Align.CENTER);
        emptyPaint.setTextAlign(Paint.Align.CENTER);
        setClickable(true);
    }

    public void setEditor(Editor editor) {
        this.editor = editor;
    }

    public void clear() {
        items.clear();
        requestLayout();
        invalidate();
    }

    public void addNote(String name, int cents, int durationMs, int stringNum, int fret) {
        Item item = new Item();
        item.name = name;
        item.cents = cents;
        item.durationMs = durationMs;
        item.stringNum = stringNum;
        item.fret = fret;
        append(item);
    }

    public void addTap() {
        Item item = new Item();
        item.tap = true;
        item.name = "tap";
        item.durationMs = 120;
        append(item);
    }

    private void append(Item item) {
        items.add(item);
        if (items.size() > 48) {
            items.remove(0);
        }
        requestLayout();
        invalidate();
        post(() -> {
            if (getParent() instanceof HorizontalScrollView) {
                ((HorizontalScrollView) getParent()).fullScroll(View.FOCUS_RIGHT);
            }
        });
    }

    public int noteCount() {
        int count = 0;
        for (Item item : items) {
            if (!item.tap) {
                count++;
            }
        }
        return count;
    }

    /** Replace the written name on one note card. noteOrdinal counts notes, not taps. */
    public void replaceNote(int noteOrdinal, String name, int cents, int stringNum, int fret) {
        int seen = 0;
        for (Item item : items) {
            if (item.tap) {
                continue;
            }
            if (seen == noteOrdinal) {
                item.name = name;
                item.cents = cents;
                item.stringNum = stringNum;
                item.fret = fret;
                invalidate();
                return;
            }
            seen++;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (items.isEmpty()) {
            return super.onTouchEvent(event);
        }
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            downY = event.getY();
            return true;
        }
        if (event.getAction() != MotionEvent.ACTION_UP) {
            return true;
        }
        float density = getResources().getDisplayMetrics().density;
        float dx = event.getX() - downX;
        float dy = event.getY() - downY;
        if (dx * dx + dy * dy > 24 * density * 24 * density) {
            return true;
        }
        shiftAt(event.getX(), density);
        return true;
    }

    private void shiftAt(float x, float density) {
        if (editor == null) {
            return;
        }
        float cursor = 8 * density;
        float top = 8 * density;
        float cardHeight = Math.min(getHeight() - 16 * density, 116 * density);
        int noteOrdinal = 0;
        for (Item item : items) {
            float width = cellWidth(item, density);
            if (x >= cursor && x < cursor + width) {
                if (item.tap) {
                    return;
                }
                int steps = x < cursor + width / 2f ? -1 : 1;
                editor.onShift(noteOrdinal, steps);
                return;
            }
            if (!item.tap) {
                noteOrdinal++;
            }
            cursor += width + 8 * density;
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float density = getResources().getDisplayMetrics().density;
        float width = 12 * density;
        for (Item item : items) {
            width += cellWidth(item, density) + 8 * density;
        }
        int height = MeasureSpec.getSize(heightMeasureSpec);
        if (height <= 0) {
            height = (int) (128 * density);
        }
        int content = (int) width;
        int mode = MeasureSpec.getMode(widthMeasureSpec);
        int spec = MeasureSpec.getSize(widthMeasureSpec);
        int measured = content;
        if (mode == MeasureSpec.EXACTLY) {
            measured = spec;
        } else if (mode == MeasureSpec.AT_MOST) {
            measured = Math.min(content, spec);
        }
        setMeasuredDimension(Math.max(measured, 1), height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float density = getResources().getDisplayMetrics().density;
        namePaint.setTextSize(22 * density);
        tabPaint.setTextSize(15 * density);
        centsPaint.setTextSize(12 * density);
        emptyPaint.setTextSize(15 * density);

        if (items.isEmpty()) {
            canvas.drawText("hum a melody, or tap a beat", getWidth() / 2f, getHeight() / 2f, emptyPaint);
            return;
        }

        float x = 8 * density;
        float top = 8 * density;
        float cardHeight = Math.min(getHeight() - 16 * density, 116 * density);
        for (Item item : items) {
            float width = cellWidth(item, density);
            rect.set(x, top, x + width, top + cardHeight);
            canvas.drawRoundRect(rect, 16 * density, 16 * density, card);
            float center = x + width / 2f;
            if (item.tap) {
                canvas.drawText("tap", center, top + cardHeight * 0.58f, namePaint);
            } else {
                canvas.drawText(item.name, center, top + cardHeight * 0.40f, namePaint);
                if (Math.abs(item.cents) >= 8) {
                    String bias = (item.cents > 0 ? "+" : "−") + Math.abs(item.cents);
                    canvas.drawText(bias, center, top + cardHeight * 0.62f, centsPaint);
                }
                String tab = stringName(item.stringNum) + "  " + item.fret;
                canvas.drawText(tab, center, top + cardHeight * 0.84f, tabPaint);
            }
            x += width + 8 * density;
        }
    }

    private static float cellWidth(Item item, float density) {
        if (item.tap) {
            return 72 * density;
        }
        float extra = Math.min(item.durationMs, 1600) / 800f * 36f;
        return (78 + extra) * density;
    }

    private static String stringName(int stringNum) {
        if (stringNum < 1 || stringNum > 6) {
            return "";
        }
        return STRINGS[stringNum];
    }
}
