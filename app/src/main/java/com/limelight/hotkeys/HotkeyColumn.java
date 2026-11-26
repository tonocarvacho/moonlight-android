package com.limelight.hotkeys;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * One section of the overlay. Rows are given bottom-up (the order buttons
 * fill in) and drawn top to bottom; a row holds one wide key or up to the
 * section's keys per row. Section 1 also carries the toolbar (settings,
 * hide) above its keys.
 */
@SuppressLint("ViewConstructor")
final class HotkeyColumn extends LinearLayout {
    static final int TOOLBAR_DP = 24;

    /** 1..HotkeyLayout.SECTIONS */
    final int section;
    final int accent;
    private final HotkeyKeyView.Listener listener;
    private final List<HotkeyKeyView> keys = new ArrayList<>();
    private final List<HotkeyLayout.Button> buttons = new ArrayList<>();

    HotkeyColumn(Context context, int section, int accent, HotkeyKeyView.Listener listener) {
        super(context);
        this.section = section;
        this.accent = accent;
        this.listener = listener;
        setOrientation(VERTICAL);
        setMotionEventSplittingEnabled(true);
    }

    /** Every key view in this column. */
    List<HotkeyKeyView> keyViews() {
        return keys;
    }

    /** The buttons in this section on screen, in fill order (bottom row first). */
    List<HotkeyLayout.Button> buttons() {
        return buttons;
    }

    void build(List<List<HotkeyLayout.Button>> bottomUp, Toolbar toolbar, int keyHeight, int gap) {
        cancelPresses();
        removeAllViews();
        keys.clear();
        buttons.clear();

        if (toolbar != null) {
            if (toolbar.getParent() != null) {
                ((android.view.ViewGroup) toolbar.getParent()).removeView(toolbar);
            }
            LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT,
                    Math.round(TOOLBAR_DP * getResources().getDisplayMetrics().density));
            lp.bottomMargin = gap / 2;
            addView(toolbar, lp);
        }

        int half = gap / 2;
        List<HotkeyKeyView> rowKeys = new ArrayList<>();
        for (int r = bottomUp.size() - 1; r >= 0; r--) {
            LinearLayout row = new Row(getContext());
            for (HotkeyLayout.Button b : bottomUp.get(r)) {
                HotkeyKeyView view = new HotkeyKeyView(getContext(), b.key, accent, listener);
                LayoutParams lp = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1);
                lp.setMargins(half, half, half, half);
                row.addView(view, lp);
                rowKeys.add(view);
            }
            addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, keyHeight));
        }
        keys.addAll(rowKeys);
        for (List<HotkeyLayout.Button> row : bottomUp) {
            buttons.addAll(row);
        }
    }

    /** The key view showing this button, or null. */
    HotkeyKeyView viewFor(HotkeyLayout.Button button) {
        for (HotkeyKeyView v : keys) {
            if (v.key == button.key) {
                return v;
            }
        }
        return null;
    }

    void cancelPresses() {
        for (HotkeyKeyView v : keys) {
            v.cancelPress();
        }
    }

    /** A row of keys sharing one label size. */
    private static final class Row extends LinearLayout {
        Row(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setMotionEventSplittingEnabled(true);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            float size = Float.MAX_VALUE;
            for (int i = 0; i < getChildCount(); i++) {
                size = Math.min(size, ((HotkeyKeyView) getChildAt(i)).fittedLabelSize());
            }
            for (int i = 0; i < getChildCount(); i++) {
                ((HotkeyKeyView) getChildAt(i)).setLabelSize(size);
            }
        }
    }

    /** Settings (⚙) and hide (▾) buttons, above the first column's keys. */
    static final class Toolbar extends View {
        interface Listener {
            void onSettings();
            void onHide();
        }

        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Listener listener;
        private final int accent;
        private final float dp;
        private int pressedHalf = -1;

        Toolbar(Context context, int accent, Listener listener) {
            super(context);
            this.listener = listener;
            this.accent = accent;
            this.dp = context.getResources().getDisplayMetrics().density;
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            text.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13,
                    context.getResources().getDisplayMetrics()));
            setHapticFeedbackEnabled(true);
            setContentDescription("Hotkey settings");
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float half = getWidth() / 2f;
            String[] glyphs = {"⚙", "▾"};
            for (int i = 0; i < 2; i++) {
                rect.set(i * half + dp, dp, (i + 1) * half - dp, getHeight() - dp);
                bg.setColor(pressedHalf == i ? (accent & 0x00FFFFFF) | 0xC0000000 : 0x9016171F);
                canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, bg);
                text.setColor(pressedHalf == i ? HotkeyKeyView.TEXT_DARK : accent);
                Paint.FontMetrics fm = text.getFontMetrics();
                canvas.drawText(glyphs[i], rect.centerX(), rect.centerY() - (fm.ascent + fm.descent) / 2, text);
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int half = event.getX() < getWidth() / 2f ? 0 : 1;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    pressedHalf = half;
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                    if (pressedHalf == half && event.getY() >= 0 && event.getY() <= getHeight()) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        if (half == 0) listener.onSettings(); else listener.onHide();
                    }
                    // fall through
                case MotionEvent.ACTION_CANCEL:
                    pressedHalf = -1;
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }
    }

    /** Slim tab left at the screen edge while the keys are hidden; tap to bring them back. */
    static final class Handle extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float dp;

        Handle(Context context, int accent) {
            super(context);
            this.dp = context.getResources().getDisplayMetrics().density;
            paint.setColor((accent & 0x00FFFFFF) | 0xA0000000);
            setHapticFeedbackEnabled(true);
            setContentDescription("Show hotkeys");
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = 5 * dp;
            float h = Math.min(getHeight() - 8 * dp, 52 * dp);
            float x = getWidth() - 3 * dp - w;
            float y = (getHeight() - h) / 2;
            rect.set(x, y, x + w, y + h);
            canvas.drawRoundRect(rect, w / 2, w / 2, paint);
        }
    }
}
