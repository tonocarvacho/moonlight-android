package com.limelight.hotkeys;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * One side of the overlay: a header naming the current page (tap = next
 * page, swipe = previous/next, hold = hide this side), the page's keys, and
 * a footer that stays on every page.
 */
@SuppressLint("ViewConstructor")
final class HotkeyColumn extends LinearLayout {
    interface Listener extends HotkeyKeyView.Listener {
        void onPageChanged(HotkeyColumn column, int page);
        void onCollapseRequested(HotkeyColumn column);
    }

    final HotkeyLayout.Side side;
    private final Listener listener;
    private final float dp;

    private final HeaderView header;
    private final LinearLayout pageRows;
    private final LinearLayout footerRows;
    private final List<HotkeyKeyView> footerKeys = new ArrayList<>();
    private final List<HotkeyKeyView> pageKeys = new ArrayList<>();

    private int page;
    private int keyHeight;
    private int gap;

    HotkeyColumn(Context context, HotkeyLayout.Side side, int page, Listener listener) {
        super(context);
        this.side = side;
        this.listener = listener;
        this.dp = context.getResources().getDisplayMetrics().density;
        this.page = side.pages.isEmpty() ? 0 : Math.max(0, Math.min(page, side.pages.size() - 1));

        setOrientation(VERTICAL);
        setMotionEventSplittingEnabled(true);

        header = new HeaderView(context);
        pageRows = new LinearLayout(context);
        pageRows.setOrientation(VERTICAL);
        pageRows.setMotionEventSplittingEnabled(true);
        footerRows = new LinearLayout(context);
        footerRows.setOrientation(VERTICAL);
        footerRows.setMotionEventSplittingEnabled(true);

        if (side.pages.size() > 0) {
            addView(header);
        }
        addView(pageRows);
        addView(footerRows);

        buildRows(footerRows, side.footer, footerKeys);
        buildRows(pageRows, currentRows(), pageKeys);
    }

    static int headerHeightDp(HotkeyLayout.Side side) {
        return side.pages.isEmpty() ? 0 : 24;
    }

    int getPage() {
        return page;
    }

    /** Every key view currently shown (used to refresh modifier state). */
    List<HotkeyKeyView> keyViews() {
        List<HotkeyKeyView> all = new ArrayList<>(pageKeys);
        all.addAll(footerKeys);
        return all;
    }

    void setGeometry(int keyHeight, int gap) {
        if (keyHeight == this.keyHeight && gap == this.gap) {
            return;
        }
        this.keyHeight = keyHeight;
        this.gap = gap;

        header.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT,
                Math.round(headerHeightDp(side) * dp)));
        // Page area always reserves the tallest page, so the footer never moves
        pageRows.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT,
                side.maxPageRows() * keyHeight));
        LayoutParams footerParams = new LayoutParams(LayoutParams.MATCH_PARENT,
                side.footer.size() * keyHeight);
        footerParams.topMargin = side.footer.isEmpty() || side.pages.isEmpty() ? 0 : gap * 2;
        footerRows.setLayoutParams(footerParams);

        applyRowGeometry(pageRows);
        applyRowGeometry(footerRows);
    }

    private List<List<HotkeyLayout.Key>> currentRows() {
        return side.pages.isEmpty() ? new ArrayList<>() : side.pages.get(page).rows;
    }

    void setPage(int newPage) {
        int count = side.pages.size();
        if (count < 2) {
            return;
        }
        newPage = ((newPage % count) + count) % count;
        if (newPage == page) {
            return;
        }
        for (HotkeyKeyView v : pageKeys) {
            v.cancelPress();
        }
        page = newPage;
        buildRows(pageRows, currentRows(), pageKeys);
        applyRowGeometry(pageRows);
        header.invalidate();
        listener.onPageChanged(this, page);
    }

    void cancelPresses() {
        for (HotkeyKeyView v : keyViews()) {
            v.cancelPress();
        }
    }

    private void buildRows(LinearLayout container, List<List<HotkeyLayout.Key>> rows, List<HotkeyKeyView> out) {
        container.removeAllViews();
        out.clear();
        for (List<HotkeyLayout.Key> row : rows) {
            LinearLayout rowView = new Row(getContext());
            for (HotkeyLayout.Key key : row) {
                HotkeyKeyView keyView = new HotkeyKeyView(getContext(), key, side.accent, listener);
                rowView.addView(keyView, new LayoutParams(0, LayoutParams.MATCH_PARENT, key.weight));
                out.add(keyView);
            }
            container.addView(rowView);
        }
    }

    private void applyRowGeometry(LinearLayout container) {
        int half = gap / 2;
        for (int i = 0; i < container.getChildCount(); i++) {
            LinearLayout rowView = (LinearLayout) container.getChildAt(i);
            rowView.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, keyHeight));
            for (int j = 0; j < rowView.getChildCount(); j++) {
                View keyView = rowView.getChildAt(j);
                LayoutParams lp = (LayoutParams) keyView.getLayoutParams();
                lp.setMargins(half, half, half, half);
                keyView.setLayoutParams(lp);
            }
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

    /** Page title + one dot per page. */
    private final class HeaderView extends View {
        private final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF pill = new RectF();
        private final GestureDetector gestures;

        HeaderView(Context context) {
            super(context);
            title.setTextAlign(Paint.Align.CENTER);
            title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
            title.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10,
                    context.getResources().getDisplayMetrics()));
            title.setLetterSpacing(0.12f);
            setHapticFeedbackEnabled(true);

            gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(MotionEvent e) {
                    return true;
                }

                @Override
                public boolean onSingleTapUp(MotionEvent e) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                    setPage(page + 1);
                    return true;
                }

                @Override
                public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                    if (Math.abs(vx) > Math.abs(vy)) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                        setPage(page + (vx < 0 ? 1 : -1));
                        return true;
                    }
                    return false;
                }

                @Override
                public void onLongPress(MotionEvent e) {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    listener.onCollapseRequested(HotkeyColumn.this);
                }
            });
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (side.pages.isEmpty()) {
                return;
            }
            float inset = dp;
            pill.set(inset, inset, getWidth() - inset, getHeight() - inset);
            dot.setColor(0x8016171F);
            canvas.drawRoundRect(pill, pill.height() / 2, pill.height() / 2, dot);

            int count = side.pages.size();
            String name = side.pages.get(page).name.toUpperCase(java.util.Locale.ROOT);
            title.setColor(side.accent);
            Paint.FontMetrics fm = title.getFontMetrics();
            float dotsHeight = count > 1 ? 5 * dp : 0;
            float textHeight = fm.descent - fm.ascent;
            float top = (getHeight() - textHeight - dotsHeight) / 2;
            canvas.drawText(name, getWidth() / 2f, top - fm.ascent, title);

            if (count > 1) {
                float r = 1.6f * dp;
                float spacing = 6 * dp;
                float startX = getWidth() / 2f - (count - 1) * spacing / 2;
                float y = top + textHeight + 1.5f * dp;
                for (int i = 0; i < count; i++) {
                    dot.setColor(i == page ? side.accent : 0x66F8F8F2);
                    canvas.drawCircle(startX + i * spacing, y, r, dot);
                }
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            return gestures.onTouchEvent(event);
        }
    }

    /** Slim tab left at the screen edge while a side is hidden; tap to bring it back. */
    static final class Handle extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final boolean leftEdge;
        private final float dp;

        Handle(Context context, int accent, boolean leftEdge) {
            super(context);
            this.leftEdge = leftEdge;
            this.dp = context.getResources().getDisplayMetrics().density;
            paint.setColor((accent & 0x00FFFFFF) | 0xA0000000);
            setHapticFeedbackEnabled(true);
            setContentDescription("Show hotkeys");
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = 5 * dp;
            float h = Math.min(getHeight() - 8 * dp, 52 * dp);
            float x = leftEdge ? 3 * dp : getWidth() - 3 * dp - w;
            float y = (getHeight() - h) / 2;
            rect.set(x, y, x + w, y + h);
            canvas.drawRoundRect(rect, w / 2, w / 2, paint);
        }
    }
}
