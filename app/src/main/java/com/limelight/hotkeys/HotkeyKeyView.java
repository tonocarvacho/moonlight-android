package com.limelight.hotkeys;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

/** One translucent hotkey. Drawing only; HotkeyOverlay decides what it sends. */
@SuppressLint("ViewConstructor")
final class HotkeyKeyView extends View {
    interface Listener {
        void onKeyDown(HotkeyKeyView view);
        void onKeyUp(HotkeyKeyView view, boolean inside);
    }

    enum ModState { OFF, ONESHOT, LOCKED }

    static final int FILL = 0xA016171F;
    static final int STROKE = 0x30F8F8F2;
    static final int TEXT = 0xF2F8F8F2;
    static final int TEXT_DARK = 0xFF0B0B10;
    /** Sticky modifier colour (Dracula purple). */
    static final int MOD = 0xFFBD93F9;

    final HotkeyLayout.Key key;
    private final int accent;
    private final Listener listener;
    private final float dp;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private boolean pressed;
    private ModState modState = ModState.OFF;
    private boolean showHint;
    private float fittedLabelSize;

    HotkeyKeyView(Context context, HotkeyLayout.Key key, int accent, Listener listener) {
        super(context);
        this.key = key;
        this.accent = accent;
        this.listener = listener;
        this.dp = context.getResources().getDisplayMetrics().density;

        stroke.setStyle(Paint.Style.STROKE);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        hintPaint.setTextAlign(Paint.Align.CENTER);
        hintPaint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));

        setFocusable(false);
        setHapticFeedbackEnabled(true);
        setContentDescription(key.label);
    }

    void setModState(ModState state) {
        if (state != modState) {
            modState = state;
            invalidate();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fitText(w, h);
    }

    private void fitText(int w, int h) {
        float sp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1, getResources().getDisplayMetrics());
        float maxWidth = w - 6 * dp;

        // Hint below the label when there is room for both lines
        showHint = key.hint != null && h >= 30 * dp;
        float hintSize = 0;
        if (showHint) {
            hintSize = Math.min(8.5f * sp, h * 0.24f);
            hintPaint.setTextSize(hintSize);
            while (hintSize > 6.5f * sp && hintPaint.measureText(key.hint) > maxWidth) {
                hintSize -= 0.5f * sp;
                hintPaint.setTextSize(hintSize);
            }
            showHint = hintPaint.measureText(key.hint) <= maxWidth;
        }

        float labelSize = Math.min(14 * sp, h * (showHint ? 0.40f : 0.48f));
        labelPaint.setTextSize(labelSize);
        while (labelSize > 7 * sp && labelPaint.measureText(key.label) > maxWidth) {
            labelSize -= 0.5f * sp;
            labelPaint.setTextSize(labelSize);
        }
        fittedLabelSize = labelSize;
    }

    /** Largest label size that fits this key (set on layout). */
    float fittedLabelSize() {
        return fittedLabelSize;
    }

    /** Rows use one label size for all their keys, so neighbours match. */
    void setLabelSize(float size) {
        if (size != labelPaint.getTextSize()) {
            labelPaint.setTextSize(size);
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = dp;
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);
        float radius = Math.min(9 * dp, rect.height() / 3);

        int fillColor = FILL;
        int strokeColor = STROKE;
        float strokeWidth = dp;
        int labelColor = TEXT;
        int hintColor = withAlpha(accent, 0xC0);

        boolean locked = modState == ModState.LOCKED;
        if (pressed || locked) {
            int base = key.type == HotkeyLayout.Type.MODIFIER ? MOD : accent;
            fillColor = withAlpha(base, pressed ? 0xF0 : 0xD0);
            strokeColor = withAlpha(base, 0xFF);
            labelColor = TEXT_DARK;
            hintColor = withAlpha(TEXT_DARK, 0xA0);
        }
        else if (modState == ModState.ONESHOT) {
            strokeColor = MOD;
            strokeWidth = 2 * dp;
            labelColor = MOD;
        }

        fill.setColor(fillColor);
        canvas.drawRoundRect(rect, radius, radius, fill);
        stroke.setColor(strokeColor);
        stroke.setStrokeWidth(strokeWidth);
        canvas.drawRoundRect(rect, radius, radius, stroke);

        // Sticky modifiers: one dot = next key only, bar = locked
        if (modState == ModState.ONESHOT) {
            fill.setColor(MOD);
            canvas.drawCircle(rect.right - 5 * dp, rect.top + 5 * dp, 2.2f * dp, fill);
        }
        else if (locked) {
            fill.setColor(TEXT_DARK);
            float bw = Math.min(14 * dp, rect.width() / 3);
            canvas.drawRoundRect(rect.centerX() - bw / 2, rect.bottom - 5 * dp,
                    rect.centerX() + bw / 2, rect.bottom - 3 * dp, dp, dp, fill);
        }

        labelPaint.setColor(labelColor);
        Paint.FontMetrics lm = labelPaint.getFontMetrics();
        float labelHeight = lm.descent - lm.ascent;
        if (showHint) {
            hintPaint.setColor(hintColor);
            Paint.FontMetrics hm = hintPaint.getFontMetrics();
            float hintHeight = hm.descent - hm.ascent;
            float top = rect.centerY() - (labelHeight + hintHeight * 0.9f) / 2;
            canvas.drawText(key.label, rect.centerX(), top - lm.ascent, labelPaint);
            canvas.drawText(key.hint, rect.centerX(), top + labelHeight + hintHeight * 0.9f - hm.descent, hintPaint);
        }
        else {
            canvas.drawText(key.label, rect.centerX(), rect.centerY() - (lm.ascent + lm.descent) / 2, labelPaint);
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressed = true;
                invalidate();
                listener.onKeyDown(this);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (pressed) {
                    pressed = false;
                    invalidate();
                    boolean inside = event.getActionMasked() == MotionEvent.ACTION_UP &&
                            event.getX() >= 0 && event.getX() <= getWidth() &&
                            event.getY() >= 0 && event.getY() <= getHeight();
                    listener.onKeyUp(this, inside);
                }
                return true;
            default:
                return true;
        }
    }

    /** Finger lifted elsewhere (view removed while held): behave like a cancel. */
    void cancelPress() {
        if (pressed) {
            pressed = false;
            invalidate();
            listener.onKeyUp(this, false);
        }
    }
}
