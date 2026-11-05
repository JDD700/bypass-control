package com.pooh.passcontrol;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/**
 * Range slider drawn on Canvas. Dual-thumb mode serves the 24h window
 * control; single-thumb mode (setSingleMode) serves the v6 battery hold
 * level - one thumb, fill from the left edge to the thumb, valA == valB.
 *
 * Dual-thumb modes:
 *  - linear (battery %): thumb A is the low value, thumb B the high value, and the
 *    dragged thumb is clamped so A <= B - minGap at all times (thumbs never cross).
 *  - circular (24h clock): the value domain wraps (span = max - min, e.g. 0..1440).
 *    Thumbs may sit in any order - A = window start, B = window end, and start may be
 *    numerically greater than end for overnight windows. Only a minimum circular
 *    separation of minGap is enforced, so a window can never collapse to zero length.
 *
 * Values snap to step. Dragging grabs the nearest thumb. With wrapFill the filled
 * segment runs from A forward (clockwise) to B, drawn as two segments when it wraps
 * past the right edge; otherwise the fill is simply A..B.
 */
public class RangeSliderView extends View {
    public interface Formatter { String format(int value); }
    public interface Listener {
        void onChanged(int a, int b);
        void onChangeFinished(int a, int b);
    }

    private static final int COL_TRACK = 0xFF232A35;
    private static final int COL_FILL  = 0xFF2DD4BF;
    private static final int COL_TEXT  = 0xFF9AA4B2;
    private static final int COL_THUMB = 0xFFE8ECF1;

    private int min = 0, max = 100, step = 1, minGap = 1;
    private int valA = 0, valB = 100;
    private boolean circular = false;
    private boolean wrapFill = false;
    private boolean single = false;
    private Formatter formatter = new Formatter() {
        @Override public String format(int v) { return String.valueOf(v); }
    };
    private Listener listener;
    private int[] tickValues;
    private String[] tickLabels;
    private int active = -1; // 0 = thumb A, 1 = thumb B, -1 = idle

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public RangeSliderView(Context c) {
        super(c);
        trackPaint.setColor(COL_TRACK);
        fillPaint.setColor(COL_FILL);
        thumbPaint.setColor(COL_THUMB);
        ringPaint.setColor(COL_FILL);
        ringPaint.setStyle(Paint.Style.STROKE);
        textPaint.setColor(COL_TEXT);
        textPaint.setTextSize(dp(10));
        bubblePaint.setColor(COL_FILL);
        bubblePaint.setTypeface(Typeface.DEFAULT_BOLD);
        bubblePaint.setTextSize(dp(15));
    }

    public void configure(int min, int max, int step, int minGap, boolean circular, boolean wrapFill) {
        this.min = min;
        this.max = max;
        this.step = step;
        this.minGap = minGap;
        this.circular = circular;
        this.wrapFill = wrapFill;
    }

    public void setFormatter(Formatter f) { if (f != null) formatter = f; }
    public void setListener(Listener l) { listener = l; }

    /** Single-thumb mode: ONE value, fill runs from the left edge to the thumb.
     *  valA == valB at all times; the Listener reports (v, v). */
    public void setSingleMode(boolean s) { single = s; }
    public void setTicks(int[] values, String[] labels) { tickValues = values; tickLabels = labels; }

    public void setValues(int a, int b) {
        valA = a;
        valB = b;
        invalidate();
    }
    public int getA() { return valA; }
    public int getB() { return valB; }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    private float padX() { return dp(16); }
    private float trackY() { return dp(30); }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = (int) dp(tickLabels != null ? 76 : 58);
        setMeasuredDimension(w, h);
    }

    private float xFor(int v) {
        float w = getWidth() - 2 * padX();
        return padX() + w * (v - min) / (float) (max - min);
    }

    private int valueFor(float x) {
        float w = getWidth() - 2 * padX();
        float frac = (x - padX()) / w;
        int raw = min + Math.round(frac * (max - min));
        int snapped = min + Math.round((raw - min) / (float) step) * step;
        if (snapped < min) snapped = min;
        if (snapped > max) snapped = max;
        return snapped;
    }

    @Override protected void onDraw(Canvas canvas) {
        float y = trackY();
        float left = padX();
        float right = getWidth() - padX();
        float trackH = dp(6);
        float r = trackH / 2;

        canvas.drawRoundRect(new RectF(left, y - r, right, y + r), r, r, trackPaint);

        float xa = xFor(valA);
        float xb = xFor(valB);
        if (single) {
            seg(canvas, left, xa, y, trackH);
        } else if (wrapFill) {
            if (valA <= valB) {
                seg(canvas, xa, xb, y, trackH);
            } else {
                seg(canvas, xa, right, y, trackH);
                seg(canvas, left, xb, y, trackH);
            }
        } else {
            seg(canvas, Math.min(xa, xb), Math.max(xa, xb), y, trackH);
        }

        if (tickValues != null && tickLabels != null) {
            textPaint.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < tickValues.length && i < tickLabels.length; i++) {
                float tx = xFor(tickValues[i]);
                canvas.drawRect(tx - dp(0.75f), y + dp(8), tx + dp(0.75f), y + dp(13), trackPaint);
                canvas.drawText(tickLabels[i], tx, y + dp(28), textPaint);
            }
        }

        drawThumb(canvas, xa, y, active == 0);
        if (!single) drawThumb(canvas, xb, y, active == 1);

        if (active >= 0) {
            String s = formatter.format(active == 0 ? valA : valB);
            float tw = bubblePaint.measureText(s);
            float cx = active == 0 ? xa : xb;
            if (cx - tw / 2 < 0) cx = tw / 2;
            if (cx + tw / 2 > getWidth()) cx = getWidth() - tw / 2;
            canvas.drawText(s, cx - tw / 2, y - dp(20), bubblePaint);
        }
    }

    private void seg(Canvas c, float x1, float x2, float y, float h) {
        if (x2 > x1) {
            float r = h / 2;
            c.drawRoundRect(new RectF(x1, y - r, x2, y + r), r, r, fillPaint);
        }
    }

    private void drawThumb(Canvas c, float x, float y, boolean isActive) {
        float rad = dp(isActive ? 13 : 11);
        ringPaint.setStrokeWidth(dp(3));
        c.drawCircle(x, y, rad, thumbPaint);
        c.drawCircle(x, y, rad, ringPaint);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float x = e.getX();
                float da = Math.abs(x - xFor(valA));
                float db = Math.abs(x - xFor(valB));
                active = single ? 0 : (da <= db ? 0 : 1);
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                applyDrag(x);
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (active >= 0) applyDrag(e.getX());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (active >= 0 && listener != null) listener.onChangeFinished(valA, valB);
                active = -1;
                performClick();
                invalidate();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    private void applyDrag(float x) {
        int v = valueFor(x);
        if (single) {
            if (v != valA) {
                valA = v;
                valB = v;
                if (listener != null) listener.onChanged(valA, valB);
            }
            invalidate();
            return;
        }
        int other = active == 0 ? valB : valA;
        if (circular) {
            int span = max - min;
            int d = Math.abs(v - other);
            int circ = Math.min(d, span - d);
            if (circ < minGap) {
                // Too close to the other thumb (either side of it, circularly):
                // stick to the nearest legal position, minGap away from it.
                int up = other + minGap;
                if (up > max) up -= span;
                int down = other - minGap;
                if (down < min) down += span;
                v = Math.abs(v - up) <= Math.abs(v - down) ? up : down;
            }
        } else {
            if (active == 0) v = Math.min(v, other - minGap);
            else v = Math.max(v, other + minGap);
            if (v < min) v = min;
            if (v > max) v = max;
        }
        boolean changed = false;
        if (active == 0 && v != valA) { valA = v; changed = true; }
        if (active == 1 && v != valB) { valB = v; changed = true; }
        if (changed && listener != null) listener.onChanged(valA, valB);
        invalidate();
    }
}
