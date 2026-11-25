package com.limelight.hotkeys;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.limelight.LimeLog;
import com.limelight.R;

import org.json.JSONException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;

/**
 * Translucent desktop hotkeys on the left and right edges of the stream
 * (fork feature, Docs/hotkeys.md). Keys sit in the letterbox bars when the
 * stream leaves room, otherwise they float over the video.
 *
 * Modifier keys are sticky: tap = applies to the next key, double tap =
 * locked, tap again = off, hold = held only while the finger is down. A
 * latched modifier is really pressed on the host, so it also combines with
 * the soft keyboard and with mouse/touch input (Super + drag moves windows).
 */
public final class HotkeyOverlay implements HotkeyColumn.Listener {
    public interface Host {
        void toggleKeyboard();
    }

    /** Where key packets go: the stream connection (or a log, in the debug preview). */
    public interface KeySink {
        void sendKey(short keyMap, byte direction, byte modifiers);
    }

    public static final String PREF_ENABLED = "checkbox_hotkey_overlay";
    public static final String PREF_OPACITY = "seekbar_hotkey_opacity";
    static final String USER_FILE = "hotkeys.json";
    private static final String STATE_PREFS = "hotkey_overlay";
    private static final long DOUBLE_TAP_MS = 400;

    private final FrameLayout parent;
    private final View streamView;
    private final Host host;
    private final HostKeyboard keyboard;
    private final SharedPreferences state;
    private final float dp;

    private final HotkeyColumn[] columns = new HotkeyColumn[2];
    private final HotkeyColumn.Handle[] handles = new HotkeyColumn.Handle[2];
    private final boolean[] collapsed = new boolean[2];
    private boolean hidden;
    private int[] lastGeometry;

    // Sticky modifier state, indexed like HotkeyLayout.MODS
    private final int modCount = HotkeyLayout.MODS.length;
    private final HotkeyKeyView.ModState[] sticky = new HotkeyKeyView.ModState[modCount];
    private final HotkeyKeyView.ModState[] stateAtDown = new HotkeyKeyView.ModState[modCount];
    private final int[] fingers = new int[modCount];
    private final boolean[] usedWhileHeld = new boolean[modCount];
    private final long[] lastTap = new long[modCount];

    /** Returns null when the overlay is turned off in settings. */
    public static HotkeyOverlay create(Activity activity, FrameLayout parent, View streamView,
                                       KeySink sink, Host host) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        if (!prefs.getBoolean(PREF_ENABLED, true)) {
            return null;
        }
        HotkeyLayout layout = loadLayout(activity);
        if (layout == null) {
            return null;
        }
        float opacity = prefs.getInt(PREF_OPACITY, 80) / 100f;
        return new HotkeyOverlay(activity, parent, streamView, sink, host, layout, opacity);
    }

    /** The user's hotkeys.json (app files folder) if present and valid, else the built-in layout. */
    private static HotkeyLayout loadLayout(Activity activity) {
        File dir = activity.getExternalFilesDir(null);
        File user = dir != null ? new File(dir, USER_FILE) : null;
        if (user != null && user.isFile()) {
            try (InputStream in = new FileInputStream(user)) {
                return HotkeyLayout.parse(HotkeyLayout.readAll(in));
            } catch (IOException | JSONException e) {
                LimeLog.warning("Bad " + user + ": " + e.getMessage());
                Toast.makeText(activity, activity.getString(R.string.hotkeys_bad_file, e.getMessage()),
                        Toast.LENGTH_LONG).show();
            }
        }
        try {
            return HotkeyLayout.parse(HotkeyLayout.readAsset(activity));
        } catch (IOException | JSONException e) {
            LimeLog.severe("Built-in hotkey layout failed to load: " + e);
            return null;
        }
    }

    private HotkeyOverlay(Activity activity, FrameLayout parent, View streamView, KeySink sink,
                          Host host, HotkeyLayout layout, float opacity) {
        this.parent = parent;
        this.streamView = streamView;
        this.host = host;
        this.keyboard = new HostKeyboard(sink);
        this.state = activity.getSharedPreferences(STATE_PREFS, 0);
        this.dp = activity.getResources().getDisplayMetrics().density;
        Arrays.fill(sticky, HotkeyKeyView.ModState.OFF);

        HotkeyLayout.Side[] sides = {layout.left, layout.right};
        for (int i = 0; i < 2; i++) {
            if (sides[i].isEmpty()) {
                continue;
            }
            final int side = i;
            columns[i] = new HotkeyColumn(activity, sides[i], state.getInt(pageKey(i), 0), this);
            columns[i].setAlpha(opacity);
            handles[i] = new HotkeyColumn.Handle(activity, sides[i].accent, i == 0);
            handles[i].setAlpha(opacity);
            handles[i].setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                setCollapsed(side, false);
            });
            collapsed[i] = state.getBoolean(collapsedKey(i), false);

            parent.addView(columns[i], new FrameLayout.LayoutParams(0, 0));
            parent.addView(handles[i], new FrameLayout.LayoutParams(0, 0));
        }

        View.OnLayoutChangeListener relayout = (v, l, t, r, b, ol, ot, or, ob) -> parent.post(this::layout);
        parent.addOnLayoutChangeListener(relayout);
        streamView.addOnLayoutChangeListener(relayout);

        applyVisibility();
        refreshModViews();

        if (!state.getBoolean("hint_shown", false)) {
            state.edit().putBoolean("hint_shown", true).apply();
            Toast.makeText(activity, R.string.hotkeys_first_hint, Toast.LENGTH_LONG).show();
        }
    }

    private static String pageKey(int side) {
        return side == 0 ? "page_left" : "page_right";
    }

    private static String collapsedKey(int side) {
        return side == 0 ? "collapsed_left" : "collapsed_right";
    }

    // ---- Game hooks ----

    /** Modifier flags the overlay holds on the host, for keys Game sends itself. */
    public int getModifierFlags() {
        return keyboard.heldMask();
    }

    /** A soft/physical keyboard key was released: one-shot modifiers are used up. */
    public void onExternalKeyUp(int androidKeyCode) {
        if (KeyEvent.isModifierKey(androidKeyCode)) {
            return;
        }
        markModsUsed();
        consumeOneShots();
    }

    /** Hide during picture-in-picture. */
    public void hide() {
        hidden = true;
        releaseAll();
        applyVisibility();
    }

    public void show() {
        hidden = false;
        applyVisibility();
    }

    /** Lets go of every key and modifier, so nothing stays stuck on the host. */
    public void releaseAll() {
        for (HotkeyColumn c : columns) {
            if (c != null) {
                c.cancelPresses();
            }
        }
        Arrays.fill(sticky, HotkeyKeyView.ModState.OFF);
        Arrays.fill(fingers, 0);
        keyboard.releaseAll();
        refreshModViews();
    }

    // ---- Key handling ----

    @Override
    public void onKeyDown(HotkeyKeyView view) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        HotkeyLayout.Key key = view.key;
        switch (key.type) {
            case MODIFIER: {
                int i = key.mod;
                if (fingers[i]++ == 0) {
                    stateAtDown[i] = sticky[i];
                    usedWhileHeld[i] = false;
                }
                keyboard.acquire(HotkeyLayout.MODS[i]);
                refreshModViews();
                break;
            }
            case KEYS:
                markModsUsed();
                if (key.steps.length == 1) {
                    // Held for as long as the finger is down, so the host auto-repeats
                    HotkeyLayout.Combo combo = key.steps[0];
                    keyboard.acquire(combo.mods);
                    keyboard.key(combo.vk, true);
                }
                else {
                    // Sequences (tmux prefix + key) are sent as typed, without latched modifiers
                    consumeOneShots();
                    keyboard.tapClean(key.steps);
                }
                break;
            case KEYBOARD:
                break;
        }
    }

    @Override
    public void onKeyUp(HotkeyKeyView view, boolean inside) {
        HotkeyLayout.Key key = view.key;
        switch (key.type) {
            case MODIFIER: {
                int i = key.mod;
                if (fingers[i] == 0) {
                    break; // already reset by releaseAll()
                }
                if (--fingers[i] == 0 && !usedWhileHeld[i]) {
                    onModifierTapped(i);
                }
                keyboard.release(HotkeyLayout.MODS[i]);
                refreshModViews();
                break;
            }
            case KEYS:
                if (key.steps.length == 1) {
                    HotkeyLayout.Combo combo = key.steps[0];
                    keyboard.key(combo.vk, false);
                    keyboard.release(combo.mods);
                    consumeOneShots();
                }
                break;
            case KEYBOARD:
                if (inside) {
                    host.toggleKeyboard();
                }
                break;
        }
    }

    /** off -> next key only -> (double tap) locked -> off */
    private void onModifierTapped(int i) {
        long now = SystemClock.uptimeMillis();
        int bit = HotkeyLayout.MODS[i];
        switch (stateAtDown[i]) {
            case OFF:
                sticky[i] = HotkeyKeyView.ModState.ONESHOT;
                keyboard.acquire(bit);
                break;
            case ONESHOT:
                if (sticky[i] != HotkeyKeyView.ModState.ONESHOT) {
                    break; // used up meanwhile
                }
                if (now - lastTap[i] < DOUBLE_TAP_MS) {
                    sticky[i] = HotkeyKeyView.ModState.LOCKED;
                }
                else {
                    sticky[i] = HotkeyKeyView.ModState.OFF;
                    keyboard.release(bit);
                }
                break;
            case LOCKED:
                sticky[i] = HotkeyKeyView.ModState.OFF;
                keyboard.release(bit);
                break;
        }
        lastTap[i] = now;
    }

    private void markModsUsed() {
        for (int i = 0; i < modCount; i++) {
            if (fingers[i] > 0) {
                usedWhileHeld[i] = true;
            }
        }
    }

    private void consumeOneShots() {
        boolean changed = false;
        for (int i = 0; i < modCount; i++) {
            if (sticky[i] == HotkeyKeyView.ModState.ONESHOT) {
                sticky[i] = HotkeyKeyView.ModState.OFF;
                keyboard.release(HotkeyLayout.MODS[i]);
                changed = true;
            }
        }
        if (changed) {
            refreshModViews();
        }
    }

    private void refreshModViews() {
        for (HotkeyColumn c : columns) {
            if (c == null) {
                continue;
            }
            for (HotkeyKeyView v : c.keyViews()) {
                if (v.key.type == HotkeyLayout.Type.MODIFIER) {
                    v.setModState(sticky[v.key.mod]);
                }
            }
        }
    }

    // ---- Columns ----

    @Override
    public void onPageChanged(HotkeyColumn column, int page) {
        state.edit().putInt(pageKey(column == columns[0] ? 0 : 1), page).apply();
        refreshModViews();
    }

    @Override
    public void onCollapseRequested(HotkeyColumn column) {
        setCollapsed(column == columns[0] ? 0 : 1, true);
    }

    private void setCollapsed(int side, boolean value) {
        if (value) {
            // Hidden keys must not stay latched where they can't be seen
            releaseAll();
        }
        collapsed[side] = value;
        state.edit().putBoolean(collapsedKey(side), value).apply();
        applyVisibility();
    }

    private void applyVisibility() {
        for (int i = 0; i < 2; i++) {
            if (columns[i] == null) {
                continue;
            }
            columns[i].setVisibility(!hidden && !collapsed[i] ? View.VISIBLE : View.GONE);
            handles[i].setVisibility(!hidden && collapsed[i] ? View.VISIBLE : View.GONE);
        }
    }

    private int px(float dps) {
        return Math.round(dps * dp);
    }

    /** Sizes the columns to the letterbox bars and the screen height. */
    private void layout() {
        int width = parent.getWidth();
        int height = parent.getHeight();
        if (width == 0 || height == 0) {
            return;
        }

        int safeL = 0, safeT = 0, safeR = 0, safeB = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowInsets insets = parent.getRootWindowInsets();
            DisplayCutout cutout = insets != null ? insets.getDisplayCutout() : null;
            if (cutout != null) {
                safeL = cutout.getSafeInsetLeft();
                safeT = cutout.getSafeInsetTop();
                safeR = cutout.getSafeInsetRight();
                safeB = cutout.getSafeInsetBottom();
            }
        }

        int leftBar = Math.max(0, streamView.getLeft() - safeL);
        int rightBar = Math.max(0, width - streamView.getRight() - safeR);
        int margin = px(5);
        int colWidth = Math.max(px(60), Math.min(px(100), Math.min(leftBar, rightBar) - 2 * margin));

        int gap = px(4);
        int availHeight = height - safeT - safeB - 2 * px(8);
        int keyHeight = px(54);
        for (HotkeyColumn c : columns) {
            if (c == null) {
                continue;
            }
            int rows = c.side.maxPageRows() + c.side.footer.size();
            int fixed = px(HotkeyColumn.headerHeightDp(c.side)) + 2 * gap;
            if (rows > 0) {
                keyHeight = Math.min(keyHeight, (availHeight - fixed) / rows);
            }
        }
        keyHeight = Math.max(px(28), keyHeight);

        // Our own layout changes trigger this again: stop once nothing moved
        int[] geometry = {colWidth, keyHeight, leftBar, rightBar, safeL, safeT, safeR, safeB};
        if (Arrays.equals(geometry, lastGeometry)) {
            return;
        }
        lastGeometry = geometry;

        int[] bars = {leftBar, rightBar};
        int[] safeSide = {safeL, safeR};
        for (int i = 0; i < 2; i++) {
            HotkeyColumn c = columns[i];
            if (c == null) {
                continue;
            }
            int rows = c.side.maxPageRows() + c.side.footer.size();
            int colHeight = px(HotkeyColumn.headerHeightDp(c.side)) + rows * keyHeight +
                    (c.side.footer.isEmpty() || c.side.pages.isEmpty() ? 0 : 2 * gap);
            c.setGeometry(keyHeight, gap);

            int edge = safeSide[i] + Math.max(margin, (bars[i] - colWidth) / 2);
            int gravity = (i == 0 ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(colWidth, colHeight, gravity);
            lp.topMargin = safeT;
            lp.bottomMargin = safeB;
            if (i == 0) lp.leftMargin = edge; else lp.rightMargin = edge;
            c.setLayoutParams(lp);

            FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(px(22), px(72), gravity);
            if (i == 0) hlp.leftMargin = safeSide[i]; else hlp.rightMargin = safeSide[i];
            handles[i].setLayoutParams(hlp);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Keep Android's edge-swipe gestures from stealing key presses
                c.setSystemGestureExclusionRects(Collections.singletonList(new Rect(0, 0, colWidth, colHeight)));
                handles[i].setSystemGestureExclusionRects(Collections.singletonList(new Rect(0, 0, px(22), px(72))));
            }
        }
    }
}
