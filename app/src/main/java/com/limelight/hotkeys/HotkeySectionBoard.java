package com.limelight.hotkeys;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.ScrollView;

import java.util.ArrayList;
import java.util.List;

/**
 * The editor's section board: one card per section, drawn like the keys
 * look on screen (top row at the top, same keys per row, same colour), and
 * a card with the hidden buttons. Hold a key and drag it to reorder it
 * within its section, to another section or to Hidden; tap a key to edit
 * it; a card's − / + change its keys per row and ＋ adds a hotkey to it.
 * Lives in a ScrollView, which it scrolls while a key is dragged near its
 * edges.
 */
@SuppressLint("ViewConstructor")
final class HotkeySectionBoard extends View {
    interface Listener {
        void onEdit(HotkeyLayout.Button button);
        void onAdd(int section);
        void onMove(HotkeyLayout.Button button, int section, int index);
        void onCols(int section, int cols);
    }

    private static final int CARD = 0xFF2D2F3D;
    private static final int CHIP = 0xFF3A3C4E;
    private static final int TEXT = 0xFFF8F8F2;
    private static final int DIM = 0xFF9EA0B5;
    private static final int RED = 0xFFFF5555;
    private static final long LONG_PRESS_MS = 250;

    private final HotkeyOverlay overlay;
    private final Listener listener;
    private final float dp;
    private final int touchSlop;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint hint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final DashPathEffect dash;
    private final RectF tmp = new RectF();

    private static final class Chip {
        final HotkeyLayout.Button button;
        final RectF rect;
        /** In a row that doesn't fit on screen. */
        final boolean overflow;

        Chip(HotkeyLayout.Button button, RectF rect, boolean overflow) {
            this.button = button;
            this.rect = rect;
            this.overflow = overflow;
        }
    }

    private static final class Card {
        final int section;
        final RectF rect = new RectF();
        final RectF minus = new RectF();
        final RectF plus = new RectF();
        final RectF add = new RectF();
        final RectF keys = new RectF();
        final List<Chip> chips = new ArrayList<>();
        String info = "";
        /** y of the "doesn't fit" line, or NaN. */
        float overflowLine = Float.NaN;
        int count;

        Card(int section) {
            this.section = section;
        }
    }

    private final List<Card> cards = new ArrayList<>();
    private int laidOutWidth = -1;

    // Touch
    private float downX, downY, x, y, rawX, rawY;
    private Chip pressedChip;
    private RectF pressedButton;
    private Card pressedCard;
    private boolean dragging;
    private HotkeyLayout.Button dragged;
    private Card targetCard;
    private int targetIndex;
    private Chip targetChip;
    private boolean targetAfter;

    private final Runnable startDrag = this::startDrag;
    private final Runnable autoScroll = new Runnable() {
        @Override
        public void run() {
            ScrollView scroll = scrollParent();
            if (!dragging || scroll == null) {
                return;
            }
            int[] loc = new int[2];
            scroll.getLocationOnScreen(loc);
            float edge = 64 * dp;
            int step = 0;
            if (rawY < loc[1] + edge) step = -Math.round(12 * dp);
            else if (rawY > loc[1] + scroll.getHeight() - edge) step = Math.round(12 * dp);
            if (step != 0) {
                scroll.scrollBy(0, step);
                updateFromRaw();
            }
            postOnAnimation(this);
        }
    };

    HotkeySectionBoard(Context context, HotkeyOverlay overlay, Listener listener) {
        super(context);
        this.overlay = overlay;
        this.listener = listener;
        this.dp = context.getResources().getDisplayMetrics().density;
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        float sp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1, context.getResources().getDisplayMetrics());
        dash = new DashPathEffect(new float[]{5 * dp, 4 * dp}, 0);
        stroke.setStyle(Paint.Style.STROKE);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setTextSize(13 * sp);
        hint.setTextAlign(Paint.Align.CENTER);
        hint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        hint.setTextSize(9.5f * sp);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(14 * sp);
        small.setTextSize(11 * sp);
        setHapticFeedbackEnabled(true);
    }

    /** The layout changed: lay the cards out again. */
    void refresh() {
        laidOutWidth = -1;
        requestLayout();
        invalidate();
    }

    // ---- Layout ----

    private int px(float dps) {
        return Math.round(dps * dp);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        if (width != laidOutWidth) {
            build(width);
        }
        float bottom = 0;
        for (Card c : cards) {
            bottom = Math.max(bottom, c.rect.bottom);
        }
        setMeasuredDimension(width, (int) Math.ceil(bottom + 4 * dp));
    }

    private void build(int width) {
        laidOutWidth = width;
        cards.clear();
        HotkeyLayout layout = overlay.currentLayout();
        float pad = 8 * dp, gap = 8 * dp, chipW = 68 * dp, chipH = 40 * dp, headH = 48 * dp;

        // Section cards flow left to right, wrapping
        float cx = 0, cy = 0, lineBottom = 0;
        for (int section = 1; section <= HotkeyLayout.SECTIONS; section++) {
            int cols = layout.colsOf(section);
            float w = Math.min(width, Math.max(156 * dp, 2 * pad + cols * chipW));
            List<List<HotkeyLayout.Button>> rows = HotkeyLayout.rows(layout.inSection(section), cols);
            int fit = overlay.sectionRows(section);
            float h = headH + Math.max(1, rows.size()) * chipH + pad + (rows.size() > fit ? 16 * dp : 0);
            if (cx > 0 && cx + w > width) {
                cx = 0;
                cy = lineBottom + gap;
            }
            Card card = new Card(section);
            card.rect.set(cx, cy, cx + w, cy + h);
            float bs = 26 * dp;
            card.plus.set(card.rect.right - pad - bs, cy + pad, card.rect.right - pad, cy + pad + bs);
            card.minus.set(card.plus.left - 20 * dp - bs, cy + pad, card.plus.left - 20 * dp, cy + pad + bs);
            card.add.set(card.minus.left - 6 * dp - bs, cy + pad, card.minus.left - 6 * dp, cy + pad + bs);
            card.keys.set(cx + pad, cy + headH, cx + w - pad, card.rect.bottom - pad);
            int count = 0;
            for (List<HotkeyLayout.Button> row : rows) count += row.size();
            card.count = count;
            card.info = count == 0 ? "empty: not on screen" : count + (count == 1 ? " key" : " keys") + " · "
                    + Math.min(rows.size(), fit) + "/" + fit + " rows";

            // Top row on top, like on screen; rows past the screen's room go above a red line
            float y = card.keys.top;
            int n = rows.size();
            for (int r = n - 1; r >= 0; r--) {
                if (r == fit - 1 && n > fit) {
                    card.overflowLine = y + 8 * dp;
                    y += 16 * dp;
                }
                List<HotkeyLayout.Button> row = rows.get(r);
                float kw = card.keys.width() / row.size();
                for (int i = 0; i < row.size(); i++) {
                    RectF rect = new RectF(card.keys.left + i * kw, y, card.keys.left + (i + 1) * kw, y + chipH);
                    card.chips.add(new Chip(row.get(i), rect, r >= fit));
                }
                y += chipH;
            }
            // Fill order (bottom row first) for drop indexes
            sortFillOrder(card.chips, rows);
            cards.add(card);
            cx += w + gap;
            lineBottom = Math.max(lineBottom, card.rect.bottom);
        }

        // Hidden buttons: reading order
        List<HotkeyLayout.Button> hiddenButtons = layout.inSection(HotkeyLayout.HIDDEN);
        int perLine = Math.max(1, (int) ((width - 2 * pad) / chipW));
        float kw = (width - 2 * pad) / perLine;
        int lines = Math.max(1, (hiddenButtons.size() + perLine - 1) / perLine);
        float top = lineBottom + 2 * gap;
        Card hidden = new Card(HotkeyLayout.HIDDEN);
        hidden.rect.set(0, top, width, top + 36 * dp + lines * chipH + pad);
        hidden.keys.set(pad, top + 36 * dp, width - pad, hidden.rect.bottom - pad);
        hidden.count = hiddenButtons.size();
        hidden.info = "drop keys here to hide them";
        for (int i = 0; i < hiddenButtons.size(); i++) {
            float l = hidden.keys.left + (i % perLine) * kw;
            float t = hidden.keys.top + (i / perLine) * chipH;
            hidden.chips.add(new Chip(hiddenButtons.get(i), new RectF(l, t, l + kw, t + chipH), false));
        }
        cards.add(hidden);
    }

    /** Chips were added top row first; drop indexes need fill order (bottom row first). */
    private static void sortFillOrder(List<Chip> chips, List<List<HotkeyLayout.Button>> rows) {
        List<Chip> ordered = new ArrayList<>(chips.size());
        for (List<HotkeyLayout.Button> row : rows) {
            for (HotkeyLayout.Button b : row) {
                for (Chip c : chips) {
                    if (c.button == b) {
                        ordered.add(c);
                        break;
                    }
                }
            }
        }
        chips.clear();
        chips.addAll(ordered);
    }

    // ---- Drawing ----

    @Override
    protected void onDraw(Canvas canvas) {
        for (Card card : cards) {
            drawCard(canvas, card);
        }
        if (dragging && targetCard != null) {
            drawDropMark(canvas);
        }
        if (dragging && dragged != null) {
            float w = 72 * dp, h = 42 * dp;
            tmp.set(x - w / 2, y - h - 28 * dp, x + w / 2, y - 28 * dp);
            int color = HotkeyOverlay.sectionColor(targetCard != null ? targetCard.section : dragged.section);
            fill.setColor(0xF044475A);
            canvas.drawRoundRect(tmp, 9 * dp, 9 * dp, fill);
            stroke.setPathEffect(null);
            stroke.setStrokeWidth(2 * dp);
            stroke.setColor(color);
            canvas.drawRoundRect(tmp, 9 * dp, 9 * dp, stroke);
            drawChipText(canvas, dragged, tmp, TEXT);
        }
    }

    private void drawCard(Canvas canvas, Card card) {
        boolean isHidden = card.section == HotkeyLayout.HIDDEN;
        int color = HotkeyOverlay.sectionColor(card.section);
        boolean target = dragging && targetCard == card;
        float r = 12 * dp;

        fill.setColor(target ? blend(CARD, color, 0.18f) : CARD);
        canvas.drawRoundRect(card.rect, r, r, fill);
        stroke.setPathEffect(isHidden ? dash : null);
        stroke.setStrokeWidth((target ? 2.5f : 1.2f) * dp);
        stroke.setColor(target ? color : withAlpha(color, 0x90));
        tmp.set(card.rect);
        tmp.inset(dp, dp);
        canvas.drawRoundRect(tmp, r, r, stroke);

        // Header: dot + name, info line; − cols + and ＋ on sections
        float pad = 8 * dp;
        float ty = card.rect.top + pad + 18 * dp;
        float tx = card.rect.left + pad + 2 * dp;
        fill.setColor(color);
        canvas.drawCircle(tx + 5 * dp, ty - 5 * dp, 5 * dp, fill);
        title.setColor(TEXT);
        String name = isHidden ? "Hidden · " + card.count : String.valueOf(card.section);
        canvas.drawText(name, tx + 15 * dp, ty, title);
        small.setColor(DIM);
        small.setTextAlign(Paint.Align.LEFT);
        String info = TextUtils.ellipsize(card.info, small, card.rect.width() - 2 * pad, TextUtils.TruncateAt.END).toString();
        if (isHidden) {
            canvas.drawText(info, tx + 15 * dp + title.measureText(name) + 10 * dp, ty, small);
        }
        else {
            canvas.drawText(info, tx, card.rect.top + 42 * dp, small);
            // ＋ (new key here) filled in the section colour; − n + = keys per row
            drawPill(canvas, card.add, "＋", pressedButton == card.add ? TEXT : color, HotkeyKeyView.TEXT_DARK);
            drawPill(canvas, card.minus, "−", pressedButton == card.minus ? 0xFF55586E : CHIP, TEXT);
            drawPill(canvas, card.plus, "+", pressedButton == card.plus ? 0xFF55586E : CHIP, TEXT);
            title.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(String.valueOf(overlay.currentLayout().colsOf(card.section)),
                    (card.minus.right + card.plus.left) / 2, ty, title);
            title.setTextAlign(Paint.Align.LEFT);
        }

        if (card.chips.isEmpty()) {
            small.setTextAlign(Paint.Align.CENTER);
            small.setColor(withAlpha(color, 0xC0));
            float cy = isHidden ? card.keys.centerY() : card.keys.top + 20 * dp;
            canvas.drawText(isHidden ? "nothing hidden" : "drop keys here", card.keys.centerX(), cy + 4 * dp, small);
        }
        for (Chip chip : card.chips) {
            drawChip(canvas, chip, isHidden ? HotkeyOverlay.HIDDEN_COLOR : color);
        }
        if (!Float.isNaN(card.overflowLine)) {
            stroke.setPathEffect(dash);
            stroke.setStrokeWidth(1.5f * dp);
            stroke.setColor(RED);
            canvas.drawLine(card.keys.left, card.overflowLine, card.keys.right, card.overflowLine, stroke);
            small.setTextAlign(Paint.Align.CENTER);
            small.setColor(RED);
            fill.setColor(CARD);
            String s = "↑ no room on screen";
            float w = small.measureText(s) + 8 * dp;
            canvas.drawRect(card.keys.centerX() - w / 2, card.overflowLine - 7 * dp,
                    card.keys.centerX() + w / 2, card.overflowLine + 7 * dp, fill);
            canvas.drawText(s, card.keys.centerX(), card.overflowLine + 4 * dp, small);
        }
    }

    private void drawChip(Canvas canvas, Chip chip, int color) {
        tmp.set(chip.rect);
        tmp.inset(2 * dp, 2 * dp);
        boolean isDragged = dragging && chip.button == dragged;
        boolean pressed = !dragging && pressedChip == chip;
        fill.setColor(pressed ? blend(CHIP, color, 0.35f) : CHIP);
        fill.setAlpha(isDragged ? 0x50 : 0xFF);
        canvas.drawRoundRect(tmp, 8 * dp, 8 * dp, fill);
        fill.setAlpha(0xFF);
        stroke.setPathEffect(chip.overflow ? dash : null);
        stroke.setStrokeWidth(dp);
        stroke.setColor(chip.overflow ? RED : withAlpha(color, isDragged ? 0x40 : 0x90));
        canvas.drawRoundRect(tmp, 8 * dp, 8 * dp, stroke);
        if (!isDragged) {
            int text = chip.button.key.type == HotkeyLayout.Type.MODIFIER ? HotkeyKeyView.MOD : TEXT;
            drawChipText(canvas, chip.button, tmp, chip.overflow ? withAlpha(text, 0x90) : text);
        }
    }

    private void drawChipText(Canvas canvas, HotkeyLayout.Button b, RectF r, int color) {
        float maxW = r.width() - 6 * dp;
        String l = TextUtils.ellipsize(b.key.label, label, maxW, TextUtils.TruncateAt.END).toString();
        label.setColor(color);
        String h = b.key.hint != null ? TextUtils.ellipsize(b.key.hint, hint, maxW, TextUtils.TruncateAt.END).toString() : null;
        Paint.FontMetrics lm = label.getFontMetrics();
        if (h != null && !h.isEmpty()) {
            hint.setColor(DIM);
            Paint.FontMetrics hm = hint.getFontMetrics();
            float total = (lm.descent - lm.ascent) + (hm.descent - hm.ascent) * 0.85f;
            float top = r.centerY() - total / 2;
            canvas.drawText(l, r.centerX(), top - lm.ascent, label);
            canvas.drawText(h, r.centerX(), top + total - hm.descent, hint);
        }
        else {
            canvas.drawText(l, r.centerX(), r.centerY() - (lm.ascent + lm.descent) / 2, label);
        }
    }

    private void drawPill(Canvas canvas, RectF r, String s, int background, int color) {
        fill.setColor(background);
        canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, fill);
        label.setColor(color);
        Paint.FontMetrics fm = label.getFontMetrics();
        canvas.drawText(s, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, label);
    }

    /** Bar where the dragged key lands: beside a key, or at the section's top / the end. */
    private void drawDropMark(Canvas canvas) {
        stroke.setPathEffect(null);
        stroke.setStrokeWidth(3 * dp);
        stroke.setColor(TEXT);
        if (targetChip != null) {
            float lx = targetAfter ? targetChip.rect.right : targetChip.rect.left;
            canvas.drawLine(lx, targetChip.rect.top + 3 * dp, lx, targetChip.rect.bottom - 3 * dp, stroke);
            return;
        }
        RectF keys = targetCard.keys;
        Chip last = null;
        for (Chip c : targetCard.chips) {
            if (c.button != dragged) last = c;
        }
        if (targetCard.section == HotkeyLayout.HIDDEN && last != null) {
            canvas.drawLine(last.rect.right, last.rect.top + 3 * dp, last.rect.right, last.rect.bottom - 3 * dp, stroke);
        }
        else {
            // New top row of the section (or its first key)
            float ly = keys.top + (last == null ? 20 * dp : 0);
            canvas.drawLine(keys.left + 6 * dp, ly, keys.right - 6 * dp, ly, stroke);
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    private static int blend(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    // ---- Touch ----

    private ScrollView scrollParent() {
        ViewParent p = getParent();
        while (p != null && !(p instanceof ScrollView)) {
            p = p.getParent();
        }
        return (ScrollView) p;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        x = e.getX();
        y = e.getY();
        rawX = e.getRawX();
        rawY = e.getRawY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                downY = y;
                pressedChip = null;
                pressedButton = null;
                pressedCard = null;
                for (Card card : cards) {
                    if (card.section != HotkeyLayout.HIDDEN) {
                        for (RectF b : new RectF[]{card.add, card.minus, card.plus}) {
                            tmp.set(b);
                            tmp.inset(-4 * dp, -4 * dp);
                            if (tmp.contains(x, y)) {
                                pressedButton = b;
                                pressedCard = card;
                            }
                        }
                    }
                    for (Chip chip : card.chips) {
                        if (chip.rect.contains(x, y)) {
                            pressedChip = chip;
                        }
                    }
                }
                if (pressedChip != null) {
                    postDelayed(startDrag, LONG_PRESS_MS);
                }
                invalidate();
                return pressedChip != null || pressedButton != null;
            case MotionEvent.ACTION_MOVE:
                if (dragging) {
                    findTarget();
                    invalidate();
                }
                else if (Math.hypot(x - downX, y - downY) > touchSlop) {
                    // A scroll, not a press
                    removeCallbacks(startDrag);
                    pressedChip = null;
                    pressedButton = null;
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(startDrag);
                if (dragging) {
                    findTarget();
                    finishDrag(true);
                }
                else if (pressedChip != null) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    listener.onEdit(pressedChip.button);
                }
                else if (pressedButton != null && pressedButton.contains(x, y)) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    int section = pressedCard.section;
                    int cols = overlay.currentLayout().colsOf(section);
                    if (pressedButton == pressedCard.add) listener.onAdd(section);
                    else if (pressedButton == pressedCard.minus) listener.onCols(section, cols - 1);
                    else listener.onCols(section, cols + 1);
                }
                pressedChip = null;
                pressedButton = null;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(startDrag);
                if (dragging) {
                    finishDrag(false);
                }
                pressedChip = null;
                pressedButton = null;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private void startDrag() {
        if (pressedChip == null) {
            return;
        }
        dragging = true;
        dragged = pressedChip.button;
        pressedChip = null;
        getParent().requestDisallowInterceptTouchEvent(true);
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        findTarget();
        postOnAnimation(autoScroll);
        invalidate();
    }

    private void finishDrag(boolean commit) {
        removeCallbacks(autoScroll);
        HotkeyLayout.Button b = dragged;
        Card card = targetCard;
        int index = targetIndex;
        dragging = false;
        dragged = null;
        targetCard = null;
        targetChip = null;
        if (commit && card != null) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            listener.onMove(b, card.section, index);
        }
        invalidate();
    }

    /** Scrolled under a still finger: board coordinates from the screen ones. */
    private void updateFromRaw() {
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        x = rawX - loc[0];
        y = rawY - loc[1];
        findTarget();
        invalidate();
    }

    /** The card under the finger and the spot in it (left/right half of a key = before/after). */
    private void findTarget() {
        Card oldCard = targetCard;
        int oldIndex = targetIndex;
        targetCard = null;
        targetChip = null;
        for (Card card : cards) {
            tmp.set(card.rect);
            tmp.inset(-4 * dp, -4 * dp);
            if (!tmp.contains(x, y)) {
                continue;
            }
            targetCard = card;
            targetIndex = Integer.MAX_VALUE;
            int index = 0;
            for (Chip chip : card.chips) {
                if (chip.button == dragged) {
                    if (chip.rect.contains(x, y)) {
                        targetCard = null; // over its own spot: stays put
                        break;
                    }
                    continue; // indexes count the section without the dragged key
                }
                if (chip.rect.contains(x, y)) {
                    targetChip = chip;
                    targetAfter = x > chip.rect.centerX();
                    targetIndex = index + (targetAfter ? 1 : 0);
                    break;
                }
                index++;
            }
            break;
        }
        if (targetCard != oldCard || targetIndex != oldIndex) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
    }
}
