package com.limelight.hotkeys;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.List;

/**
 * Full-screen layer shown while arranging the hotkeys. It takes every touch
 * (nothing reaches the keys or the PC): drag a key onto another key to swap
 * them, onto the empty space above a column to put it at that column's
 * top, or onto "Hide"; drag a column by its grip (⠿) to move the column.
 */
@SuppressLint("ViewConstructor")
final class HotkeyArrangeLayer extends View {
    private final HotkeyOverlay overlay;
    private final float dp;

    private final Paint scrim = new Paint();
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();

    private final RectF doneRect = new RectF();
    private final RectF resetRect = new RectF();
    private final RectF hideRect = new RectF();

    // Drag state
    private static final int NONE = 0, KEY = 1, COLUMN = 2;
    private int mode = NONE;
    private int column = -1;
    private HotkeyLayout.Button dragged;
    private float downX, downY, startTx, startTy, x, y;
    private HotkeyLayout.Button target;
    private boolean targetAfter;
    private boolean overHide;

    HotkeyArrangeLayer(Context context, HotkeyOverlay overlay) {
        super(context);
        this.overlay = overlay;
        this.dp = context.getResources().getDisplayMetrics().density;
        float sp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1, context.getResources().getDisplayMetrics());

        scrim.setColor(0x66000000);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(1.5f * dp);
        outline.setPathEffect(new DashPathEffect(new float[]{6 * dp, 4 * dp}, 0));
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setTextSize(13 * sp);
        small.setTextAlign(Paint.Align.CENTER);
        small.setTextSize(11 * sp);
        small.setColor(0xDDF8F8F2);
        setHapticFeedbackEnabled(true);
    }

    // ---- Geometry helpers ----

    /** Bounds of a view in this layer's coordinates (same parent as the columns). */
    private void boundsOf(View v, RectF out) {
        float left = 0, top = 0;
        View cur = v;
        while (cur != null && cur != overlay.parentView()) {
            left += cur.getX();
            top += cur.getY();
            cur = (View) cur.getParent();
        }
        out.set(left, top, left + v.getWidth(), top + v.getHeight());
    }

    private void gripOf(HotkeyColumn c, RectF out) {
        boundsOf(c, out);
        float h = 24 * dp;
        float top = Math.max(0, out.top - h - 3 * dp);
        out.set(out.left, top, out.right, top + h);
    }

    // ---- Drawing ----

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawRect(0, 0, getWidth(), getHeight(), scrim);
        List<HotkeyColumn> columns = overlay.columns();

        for (int i = 0; i < columns.size(); i++) {
            HotkeyColumn c = columns.get(i);
            boundsOf(c, tmp);
            tmp.inset(-2 * dp, -2 * dp);
            outline.setColor(c.accent);
            canvas.drawRoundRect(tmp, 10 * dp, 10 * dp, outline);

            gripOf(c, tmp);
            fill.setColor(mode == COLUMN && column == i ? c.accent : withAlpha(c.accent, 0xB0));
            canvas.drawRoundRect(tmp, tmp.height() / 2, tmp.height() / 2, fill);
            text.setColor(HotkeyKeyView.TEXT_DARK);
            drawCentered(canvas, "⠿ " + overlay.columnName(i), tmp, text);
        }

        // Where the dragged key will go
        if (mode == KEY && target != null) {
            for (HotkeyColumn c : columns) {
                HotkeyKeyView v = c.viewFor(target);
                if (v != null) {
                    boundsOf(v, tmp);
                    outline.setColor(HotkeyKeyView.MOD);
                    outline.setPathEffect(null);
                    outline.setStrokeWidth(3 * dp);
                    if (targetAfter) {
                        // Goes above the column's top key
                        canvas.drawLine(tmp.left, tmp.top - 3 * dp, tmp.right, tmp.top - 3 * dp, outline);
                    }
                    else {
                        canvas.drawRoundRect(tmp, 8 * dp, 8 * dp, outline);
                    }
                    outline.setStrokeWidth(1.5f * dp);
                    outline.setPathEffect(new DashPathEffect(new float[]{6 * dp, 4 * dp}, 0));
                }
            }
        }

        // Top bar: help text, Reset, Done (and Hide while dragging a key)
        float cx = getWidth() / 2f;
        float bh = 34 * dp;
        // Keys above the stream (portrait): the bar goes over the stream, at the bottom
        float barTop = overlay.isPortrait() && overlay.keysAbove() ? getHeight() - bh - 50 * dp : 8 * dp;
        if (mode == KEY) {
            hideRect.set(cx - 90 * dp, barTop, cx + 90 * dp, barTop + bh);
            fill.setColor(overHide ? 0xF0FF5555 : 0xC0FF5555);
            canvas.drawRoundRect(hideRect, bh / 2, bh / 2, fill);
            text.setColor(0xFFF8F8F2);
            drawCentered(canvas, "✕  Drop here to hide", hideRect, text);
        }
        else {
            resetRect.set(cx - 150 * dp, barTop, cx - 6 * dp, barTop + bh);
            doneRect.set(cx + 6 * dp, barTop, cx + 150 * dp, barTop + bh);
            fill.setColor(0xE044475A);
            canvas.drawRoundRect(resetRect, bh / 2, bh / 2, fill);
            fill.setColor(0xF0BD93F9);
            canvas.drawRoundRect(doneRect, bh / 2, bh / 2, fill);
            text.setColor(0xFFF8F8F2);
            drawCentered(canvas, "Reset positions", resetRect, text);
            text.setColor(HotkeyKeyView.TEXT_DARK);
            drawCentered(canvas, "✓  Done", doneRect, text);
            canvas.drawText("Drag a key onto another to swap them, or above a column to put it on top",
                    cx, barTop + bh + 18 * dp, small);
            canvas.drawText("Drag ⠿ to move a column", cx, barTop + bh + 34 * dp, small);
        }

        // The key under the finger, drawn a bit above it so it stays visible
        if (mode == KEY && dragged != null) {
            float w = 64 * dp, h = 40 * dp;
            tmp.set(x - w / 2, y - h - 36 * dp, x + w / 2, y - 36 * dp);
            fill.setColor(0xF044475A);
            canvas.drawRoundRect(tmp, 9 * dp, 9 * dp, fill);
            outline.setColor(HotkeyKeyView.MOD);
            outline.setPathEffect(null);
            canvas.drawRoundRect(tmp, 9 * dp, 9 * dp, outline);
            outline.setPathEffect(new DashPathEffect(new float[]{6 * dp, 4 * dp}, 0));
            text.setColor(0xFFF8F8F2);
            drawCentered(canvas, dragged.key.label, tmp, text);
        }
    }

    private static void drawCentered(Canvas canvas, String s, RectF r, Paint p) {
        Paint.FontMetrics fm = p.getFontMetrics();
        canvas.drawText(s, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, p);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    // ---- Touch ----

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        x = e.getX();
        y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                onDown();
                break;
            case MotionEvent.ACTION_MOVE:
                if (mode == COLUMN) {
                    overlay.setColumnOffset(column, startTx + x - downX, startTy + y - downY, false);
                }
                else if (mode == KEY) {
                    findTarget();
                }
                invalidate();
                break;
            case MotionEvent.ACTION_UP:
                onUp();
                break;
            case MotionEvent.ACTION_CANCEL:
                if (mode == COLUMN) {
                    overlay.setColumnOffset(column, startTx, startTy, true);
                }
                mode = NONE;
                invalidate();
                break;
        }
        return true;
    }

    private void onDown() {
        downX = x;
        downY = y;
        mode = NONE;
        if (doneRect.contains(x, y)) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            overlay.stopArranging();
            return;
        }
        if (resetRect.contains(x, y)) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            overlay.resetPositions();
            invalidate();
            return;
        }
        List<HotkeyColumn> columns = overlay.columns();
        for (int i = 0; i < columns.size(); i++) {
            gripOf(columns.get(i), tmp);
            tmp.inset(0, -6 * dp);
            if (tmp.contains(x, y)) {
                mode = COLUMN;
                column = i;
                startTx = columns.get(i).getTranslationX();
                startTy = columns.get(i).getTranslationY();
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                invalidate();
                return;
            }
        }
        HotkeyLayout.Button hit = buttonAt(x, y);
        if (hit != null) {
            mode = KEY;
            dragged = hit;
            target = null;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            invalidate();
        }
    }

    private void onUp() {
        if (mode == COLUMN) {
            overlay.setColumnOffset(column, startTx + x - downX, startTy + y - downY, true);
        }
        else if (mode == KEY) {
            findTarget();
            if (overHide) {
                overlay.hideButton(dragged);
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            }
            else if (target != null) {
                overlay.moveButton(dragged, target, targetAfter);
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            }
        }
        mode = NONE;
        dragged = null;
        target = null;
        overHide = false;
        invalidate();
    }

    private HotkeyLayout.Button buttonAt(float px, float py) {
        for (HotkeyColumn c : overlay.columns()) {
            for (HotkeyLayout.Button b : c.buttons()) {
                HotkeyKeyView v = c.viewFor(b);
                if (v != null) {
                    boundsOf(v, tmp);
                    if (tmp.contains(px, py)) {
                        return b;
                    }
                }
            }
        }
        return null;
    }

    /** The key under the finger, or the top of a column when above its keys. */
    private void findTarget() {
        overHide = hideRect.contains(x, y);
        target = null;
        targetAfter = false;
        if (overHide) {
            return;
        }
        HotkeyLayout.Button hit = buttonAt(x, y);
        if (hit != null) {
            target = hit == dragged ? null : hit;
            return;
        }
        List<HotkeyColumn> columns = overlay.columns();
        for (int i = 0; i < columns.size(); i++) {
            HotkeyColumn c = columns.get(i);
            boundsOf(c, tmp);
            List<HotkeyLayout.Button> buttons = c.buttons();
            if (x >= tmp.left && x <= tmp.right && y < tmp.top + 24 * dp && !buttons.isEmpty()) {
                HotkeyLayout.Button top = buttons.get(buttons.size() - 1);
                if (top != dragged) {
                    target = top;
                    targetAfter = true;
                }
                return;
            }
        }
    }
}
