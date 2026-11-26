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
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;

import org.json.JSONException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Translucent desktop hotkeys over the stream (fork feature, Docs/hotkeys.md).
 *
 * The shown buttons fill columns bottom-up: the right column first, then the
 * left one, then inner columns. In landscape the edge columns sit in the
 * letterbox bars; in portrait the stream moves to the top half and the
 * columns fill the bottom half (or the other way round). The toolbar's ⚙
 * opens {@link HotkeyEditor} (show/hide, order, new hotkeys) and ▾ hides the
 * keys. In arrange mode keys and columns are dragged on screen.
 *
 * Modifier keys are sticky: tap = applies to the next key, double tap =
 * locked, tap again = off, hold = held only while the finger is down. A
 * latched modifier is really pressed on the host, so it also combines with
 * the soft keyboard and with mouse/touch input (Super + drag moves windows).
 */
public final class HotkeyOverlay implements HotkeyKeyView.Listener, HotkeyColumn.Toolbar.Listener {
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
    private static final String KEY_HEIGHT = "key_height_dp";
    private static final String KEYS_ABOVE = "portrait_keys_above";
    private static final String COLLAPSED = "collapsed";
    static final int DEFAULT_KEY_HEIGHT_DP = 42;
    static final int MIN_KEY_HEIGHT_DP = 30;
    static final int MAX_KEY_HEIGHT_DP = 72;
    private static final long DOUBLE_TAP_MS = 400;
    /** Accents: right-hand columns green, left-hand cyan (Dracula). */
    private static final int ACCENT_RIGHT = 0xFF50FA7B;
    private static final int ACCENT_LEFT = 0xFF8BE9FD;

    private final Activity activity;
    private final FrameLayout parent;
    private final View streamView;
    private final ViewGroup.LayoutParams streamParams;
    private final double streamAspect;
    private final boolean trackpad;
    private final Host host;
    private final HostKeyboard keyboard;
    private final SharedPreferences state;
    private final SharedPreferences prefs;
    private final File userFile;
    private final HotkeyLayout defaults;
    private final float dp;

    private HotkeyLayout layout;
    private final HotkeyColumn.Toolbar toolbar;
    private final HotkeyColumn.Handle handle;
    private final View blocker;
    private final List<HotkeyColumn> columns = new ArrayList<>();
    private final List<Slot> slots = new ArrayList<>();
    private final Map<String, Integer> placement = new HashMap<>();
    private HotkeyArrangeLayer arrangeLayer;
    private HotkeyEditor editor;
    private boolean hidden;
    private boolean collapsed;
    private boolean portrait;
    private int[] lastGeometry;
    private int layoutVersion;

    // Sticky modifier state, indexed like HotkeyLayout.MODS
    private final int modCount = HotkeyLayout.MODS.length;
    private final HotkeyKeyView.ModState[] sticky = new HotkeyKeyView.ModState[modCount];
    private final HotkeyKeyView.ModState[] stateAtDown = new HotkeyKeyView.ModState[modCount];
    private final int[] fingers = new int[modCount];
    private final boolean[] usedWhileHeld = new boolean[modCount];
    private final long[] lastTap = new long[modCount];

    /** One column position: where it is and how many rows fit. */
    static final class Slot {
        final int x, top, bottom, width, rows;

        Slot(int x, int top, int bottom, int width, int rows) {
            this.x = x;
            this.top = top;
            this.bottom = bottom;
            this.width = width;
            this.rows = rows;
        }
    }

    /**
     * Returns null when the overlay is turned off in settings.
     * streamWidth/streamHeight: the stream resolution, for sizing it in portrait.
     */
    public static HotkeyOverlay create(Activity activity, FrameLayout parent, View streamView,
                                       int streamWidth, int streamHeight, KeySink sink, Host host) {
        if (!isEnabled(activity)) {
            return null;
        }
        HotkeyLayout defaults;
        try {
            defaults = HotkeyLayout.parse(HotkeyLayout.readAsset(activity));
        } catch (IOException | JSONException e) {
            LimeLog.severe("Built-in hotkey layout failed to load: " + e);
            return null;
        }
        return new HotkeyOverlay(activity, parent, streamView, streamWidth, streamHeight, sink, host, defaults);
    }

    static boolean isEnabled(android.content.Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_ENABLED, true);
    }

    private HotkeyOverlay(Activity activity, FrameLayout parent, View streamView, int streamWidth,
                          int streamHeight, KeySink sink, Host host, HotkeyLayout defaults) {
        this.activity = activity;
        this.parent = parent;
        this.streamView = streamView;
        this.streamParams = streamView.getLayoutParams();
        this.streamAspect = streamWidth > 0 && streamHeight > 0 ? (double) streamWidth / streamHeight : 16.0 / 9;
        this.trackpad = PreferenceConfiguration.readPreferences(activity).touchscreenTrackpad;
        this.host = host;
        this.keyboard = new HostKeyboard(sink);
        this.state = activity.getSharedPreferences(STATE_PREFS, 0);
        this.prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        this.dp = activity.getResources().getDisplayMetrics().density;
        this.defaults = defaults;
        File dir = activity.getExternalFilesDir(null);
        this.userFile = dir != null ? new File(dir, USER_FILE) : null;
        this.layout = loadLayout();
        Arrays.fill(sticky, HotkeyKeyView.ModState.OFF);

        collapsed = state.getBoolean(COLLAPSED, false);

        // In portrait (keys below the stream) the empty part of the key area
        // swallows touches in absolute-touch mode, so a missed key can't click
        // the PC. In trackpad mode it works as a touchpad, like the bars.
        blocker = new View(activity);
        blocker.setOnTouchListener((v, e) -> true);
        parent.addView(blocker, new FrameLayout.LayoutParams(0, 0));

        toolbar = new HotkeyColumn.Toolbar(activity, ACCENT_RIGHT, this);
        handle = new HotkeyColumn.Handle(activity, ACCENT_RIGHT);
        handle.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            setCollapsed(false);
        });
        parent.addView(handle, new FrameLayout.LayoutParams(0, 0));
        applyOpacity();

        View.OnLayoutChangeListener relayout = (v, l, t, r, b, ol, ot, or, ob) -> parent.post(this::layout);
        parent.addOnLayoutChangeListener(relayout);
        streamView.addOnLayoutChangeListener(relayout);

        applyVisibility();

        if (!state.getBoolean("hint2_shown", false)) {
            state.edit().putBoolean("hint2_shown", true).apply();
            Toast.makeText(activity, R.string.hotkeys_first_hint, Toast.LENGTH_LONG).show();
        }
    }

    /** The user's hotkeys.json (app files folder) if present and valid, else the built-in layout. */
    private HotkeyLayout loadLayout() {
        if (userFile != null && userFile.isFile()) {
            try (InputStream in = new FileInputStream(userFile)) {
                return HotkeyLayout.merge(HotkeyLayout.parse(HotkeyLayout.readAll(in)), defaults);
            } catch (IOException | JSONException e) {
                LimeLog.warning("Bad " + userFile + ": " + e.getMessage());
                Toast.makeText(activity, activity.getString(R.string.hotkeys_bad_file, e.getMessage()),
                        Toast.LENGTH_LONG).show();
            }
        }
        return defaults;
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
        if (arrangeLayer != null) {
            stopArranging();
        }
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
            c.cancelPresses();
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
                // A cancelled press (key rebuilt, finger slid off) is not a tap
                if (--fingers[i] == 0 && !usedWhileHeld[i] && inside) {
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
            for (HotkeyKeyView v : c.keyViews()) {
                if (v.key.type == HotkeyLayout.Type.MODIFIER) {
                    v.setModState(sticky[v.key.mod]);
                }
            }
        }
    }

    /** A latched modifier whose key is no longer on screen is let go. */
    private void releaseUnseenMods() {
        boolean[] visible = new boolean[modCount];
        for (HotkeyColumn c : columns) {
            for (HotkeyKeyView v : c.keyViews()) {
                if (v.key.type == HotkeyLayout.Type.MODIFIER) {
                    visible[v.key.mod] = true;
                }
            }
        }
        for (int i = 0; i < modCount; i++) {
            if (!visible[i] && sticky[i] != HotkeyKeyView.ModState.OFF) {
                sticky[i] = HotkeyKeyView.ModState.OFF;
                keyboard.release(HotkeyLayout.MODS[i]);
            }
        }
    }

    // ---- Toolbar ----

    @Override
    public void onSettings() {
        if (editor != null && editor.isShowing()) {
            return;
        }
        releaseAll();
        editor = new HotkeyEditor(activity, this);
        editor.show();
    }

    @Override
    public void onHide() {
        setCollapsed(true);
    }

    private void setCollapsed(boolean value) {
        if (value) {
            // Hidden keys must not stay latched where they can't be seen
            releaseAll();
        }
        collapsed = value;
        state.edit().putBoolean(COLLAPSED, value).apply();
        applyVisibility();
    }

    private void applyVisibility() {
        boolean keys = !hidden && !collapsed;
        for (HotkeyColumn c : columns) {
            c.setVisibility(keys ? View.VISIBLE : View.GONE);
        }
        handle.setVisibility(!hidden && collapsed ? View.VISIBLE : View.GONE);
        blocker.setVisibility(keys && portrait && !trackpad ? View.VISIBLE : View.GONE);
        lastGeometry = null;
        parent.post(this::layout);
    }

    private void applyOpacity() {
        float opacity = prefs.getInt(PREF_OPACITY, 80) / 100f;
        for (HotkeyColumn c : columns) {
            c.setAlpha(opacity);
        }
        handle.setAlpha(opacity);
    }

    // ---- Editor API ----

    HotkeyLayout currentLayout() {
        return layout;
    }

    HotkeyLayout defaults() {
        return defaults;
    }

    /** Saves the new list to hotkeys.json and shows it. */
    void setLayout(HotkeyLayout newLayout) {
        layout = newLayout;
        save();
        relayoutNow();
    }

    /** Back to the built-in list (deletes hotkeys.json). */
    void resetLayout() {
        if (userFile != null && userFile.exists() && !userFile.delete()) {
            LimeLog.warning("Couldn't delete " + userFile);
        }
        layout = defaults;
        relayoutNow();
    }

    private void save() {
        if (userFile == null) {
            Toast.makeText(activity, "Storage unavailable: hotkeys not saved", Toast.LENGTH_LONG).show();
            return;
        }
        File tmp = new File(userFile.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(layout.toJson().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            LimeLog.warning("Couldn't save hotkeys: " + e);
            Toast.makeText(activity, "Couldn't save hotkeys: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        if (!tmp.renameTo(userFile)) {
            LimeLog.warning("Couldn't replace " + userFile);
        }
    }

    /**
     * Where each shown button landed: column number (0 = right, 1 = left,
     * 2 = right inner, ...), or -1 when it doesn't fit on screen.
     */
    Map<String, Integer> placement() {
        return placement;
    }

    /** Keys that fit (two per row); sidesOnly: just the two edge columns. */
    int columnCapacity(boolean sidesOnly) {
        int cells = 0;
        for (int k = 0; k < slots.size() && (!sidesOnly || k < 2); k++) {
            cells += 2 * slots.get(k).rows;
        }
        return cells;
    }

    boolean isPortrait() {
        return portrait;
    }

    int keyHeightDp() {
        return state.getInt(KEY_HEIGHT, DEFAULT_KEY_HEIGHT_DP);
    }

    void setKeyHeightDp(int value) {
        state.edit().putInt(KEY_HEIGHT, value).apply();
        relayoutNow();
    }

    boolean keysAbove() {
        return state.getBoolean(KEYS_ABOVE, false);
    }

    void setKeysAbove(boolean value) {
        state.edit().putBoolean(KEYS_ABOVE, value).apply();
        relayoutNow();
    }

    int opacityPercent() {
        return prefs.getInt(PREF_OPACITY, 80);
    }

    void setOpacityPercent(int value) {
        prefs.edit().putInt(PREF_OPACITY, value).apply();
        applyOpacity();
    }

    void resetPositions() {
        SharedPreferences.Editor e = state.edit();
        for (String k : state.getAll().keySet()) {
            if (k.startsWith("off_")) {
                e.remove(k);
            }
        }
        e.apply();
        relayoutNow();
    }

    void onEditorClosed() {
        editor = null;
    }

    // ---- Arrange mode (drag keys to reorder, drag columns to move) ----

    void startArranging() {
        if (arrangeLayer != null || hidden) {
            return;
        }
        releaseAll();
        if (collapsed) {
            setCollapsed(false);
        }
        arrangeLayer = new HotkeyArrangeLayer(activity, this);
        parent.addView(arrangeLayer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    void stopArranging() {
        if (arrangeLayer != null) {
            parent.removeView(arrangeLayer);
            arrangeLayer = null;
        }
    }

    List<HotkeyColumn> columns() {
        return columns;
    }

    /**
     * Arrange-mode drop: swaps the two buttons, so nothing else moves; with
     * {@code after}, the button goes right after {@code target} instead
     * (dropped above a column's top key).
     */
    void moveButton(HotkeyLayout.Button moved, HotkeyLayout.Button target, boolean after) {
        List<HotkeyLayout.Button> list = new ArrayList<>(layout.buttons);
        int from = list.indexOf(moved);
        int to = list.indexOf(target);
        if (from < 0 || to < 0 || moved == target) {
            return;
        }
        if (after) {
            list.remove(from);
            list.add(list.indexOf(target) + 1, moved);
        }
        else {
            list.set(from, target);
            list.set(to, moved);
        }
        setLayout(new HotkeyLayout(list, layout.seen));
    }

    void hideButton(HotkeyLayout.Button button) {
        List<HotkeyLayout.Button> list = new ArrayList<>(layout.buttons);
        int i = list.indexOf(button);
        if (i >= 0) {
            list.set(i, button.withShow(false));
            setLayout(new HotkeyLayout(list, layout.seen));
        }
    }

    /** Column drag: dx/dy (px) from its default spot. Saved per orientation. */
    void setColumnOffset(int column, float dx, float dy, boolean commit) {
        HotkeyColumn c = column < columns.size() ? columns.get(column) : null;
        if (c == null) {
            return;
        }
        float[] clamped = clampOffset(c, dx, dy);
        c.setTranslationX(clamped[0]);
        c.setTranslationY(clamped[1]);
        if (commit) {
            state.edit().putString(offsetKey(column), clamped[0] / dp + "," + clamped[1] / dp).apply();
            lastGeometry = null;
            layout();
        }
    }

    private float[] clampOffset(HotkeyColumn c, float dx, float dy) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) c.getLayoutParams();
        float x = Math.max(0, Math.min(lp.leftMargin + dx, parent.getWidth() - lp.width));
        float y = Math.max(0, Math.min(lp.topMargin + dy, parent.getHeight() - lp.height));
        return new float[]{x - lp.leftMargin, y - lp.topMargin};
    }

    private String offsetKey(int column) {
        return "off_" + (portrait ? "p" : "l") + column;
    }

    private float[] savedOffset(int column) {
        String s = state.getString(offsetKey(column), null);
        if (s != null) {
            String[] parts = s.split(",");
            try {
                return new float[]{Float.parseFloat(parts[0]) * dp, Float.parseFloat(parts[1]) * dp};
            } catch (RuntimeException ignored) {
                // corrupt value: default position
            }
        }
        return new float[]{0, 0};
    }

    // ---- Layout ----

    private int px(float dps) {
        return Math.round(dps * dp);
    }

    private void relayoutNow() {
        layoutVersion++;
        lastGeometry = null;
        layout();
        if (arrangeLayer != null) {
            arrangeLayer.invalidate();
        }
    }

    /** Places the stream (portrait) and fills the columns. */
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

        boolean nowPortrait = height > width;
        boolean keysShown = !hidden && !collapsed;
        boolean above = keysAbove();
        int keyHeight = px(Math.max(MIN_KEY_HEIGHT_DP, Math.min(MAX_KEY_HEIGHT_DP, keyHeightDp())));
        int margin = px(6);
        int gap = px(4);
        int toolbarHeight = px(HotkeyColumn.TOOLBAR_DP) + gap / 2;

        // Portrait: the stream is fitted to the width, at the top (or bottom).
        // Otherwise it keeps Game's own layout (centered, letterboxed).
        int streamHeight = (int) Math.ceil(width / streamAspect);
        boolean placeStream = nowPortrait && !hidden && streamHeight < height;
        if (placeStream) {
            int gravity = !keysShown ? Gravity.CENTER : (above ? Gravity.BOTTOM : Gravity.TOP) | Gravity.CENTER_HORIZONTAL;
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(width, streamHeight, gravity);
            if (keysShown) {
                if (above) lp.bottomMargin = safeB; else lp.topMargin = safeT;
            }
            if (!sameParams(streamView.getLayoutParams(), lp)) {
                streamView.setLayoutParams(lp);
            }
        }
        else if (streamView.getLayoutParams() != streamParams) {
            streamView.setLayoutParams(streamParams);
        }

        int leftBar = Math.max(0, streamView.getLeft() - safeL);
        int rightBar = Math.max(0, width - streamView.getRight() - safeR);

        // Our own layout changes trigger this again: stop once nothing moved
        int[] geometry = {width, height, safeL, safeT, safeR, safeB, nowPortrait ? 1 : 0, keysShown ? 1 : 0,
                above ? 1 : 0, keyHeight, placeStream ? 0 : leftBar, placeStream ? 0 : rightBar, layoutVersion};
        if (Arrays.equals(geometry, lastGeometry)) {
            return;
        }
        lastGeometry = geometry;
        portrait = nowPortrait;

        computeSlots(width, height, safeL, safeT, safeR, safeB, placeStream ? streamHeight : -1, above,
                leftBar, rightBar, keyHeight, margin, gap, toolbarHeight);
        rebuildColumns(keyHeight, gap, toolbarHeight);

        // Handle for the hidden keys: right edge
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(px(22), px(72),
                Gravity.RIGHT | (portrait ? Gravity.BOTTOM : Gravity.CENTER_VERTICAL));
        hlp.rightMargin = safeR;
        hlp.bottomMargin = portrait ? safeB + px(96) : 0;
        handle.setLayoutParams(hlp);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            handle.setSystemGestureExclusionRects(Collections.singletonList(new Rect(0, 0, px(22), px(72))));
        }

        // Touch catcher over the key area (portrait, absolute touch only)
        if (portrait && placeStream) {
            int top = above ? 0 : safeT + streamHeight;
            int bottom = above ? height - safeB - streamHeight : height;
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(width, Math.max(0, bottom - top), Gravity.TOP | Gravity.LEFT);
            blp.topMargin = top;
            blocker.setLayoutParams(blp);
        }
        blocker.setVisibility(keysShown && portrait && placeStream && !trackpad ? View.VISIBLE : View.GONE);
        if (arrangeLayer != null) {
            arrangeLayer.bringToFront();
            arrangeLayer.invalidate();
        }
    }

    private static boolean sameParams(ViewGroup.LayoutParams a, FrameLayout.LayoutParams b) {
        if (!(a instanceof FrameLayout.LayoutParams)) {
            return false;
        }
        FrameLayout.LayoutParams f = (FrameLayout.LayoutParams) a;
        return f.width == b.width && f.height == b.height && f.gravity == b.gravity &&
                f.topMargin == b.topMargin && f.bottomMargin == b.bottomMargin;
    }

    /**
     * Column positions in fill order. Landscape: right bar, left bar, then
     * inward. Portrait: an even grid across the key area, filled rightmost,
     * leftmost, then inward.
     */
    private void computeSlots(int width, int height, int safeL, int safeT, int safeR, int safeB,
                              int streamHeight, boolean above, int leftBar, int rightBar,
                              int keyHeight, int margin, int gap, int toolbarHeight) {
        slots.clear();
        int top, bottom;
        List<int[]> xs = new ArrayList<>(); // {x, width}
        if (streamHeight >= 0) {
            top = above ? safeT + margin : safeT + streamHeight + margin;
            bottom = above ? height - safeB - streamHeight - margin : height - safeB - margin;
            int avail = width - safeL - safeR - 2 * margin;
            int n = Math.max(2, Math.min(6, Math.round(avail / (float) px(125))));
            int colWidth = (avail - (n - 1) * 2 * gap) / n;
            for (int k = 0; k < n; k++) {
                int physical = k % 2 == 0 ? n - 1 - k / 2 : k / 2;
                xs.add(new int[]{safeL + margin + physical * (colWidth + 2 * gap), colWidth});
            }
        }
        else {
            top = safeT + margin;
            bottom = height - safeB - margin;
            int colWidth = Math.max(px(60), Math.min(px(100), Math.min(leftBar, rightBar) - 2 * margin));
            int rightX = width - safeR - Math.max(margin, (rightBar - colWidth) / 2) - colWidth;
            int leftX = safeL + Math.max(margin, (leftBar - colWidth) / 2);
            for (int k = 0; k < 12; k++) {
                int step = (k / 2) * (colWidth + 2 * gap);
                int x = k % 2 == 0 ? rightX - step : leftX + step;
                boolean overlaps = x < safeL || x + colWidth > width - safeR;
                for (int[] other : xs) {
                    overlaps |= x < other[0] + other[1] + gap && other[0] < x + colWidth + gap;
                }
                if (overlaps) {
                    break;
                }
                xs.add(new int[]{x, colWidth});
            }
        }
        for (int k = 0; k < xs.size(); k++) {
            int avail = bottom - top - (k == 0 ? toolbarHeight : 0);
            slots.add(new Slot(xs.get(k)[0], top, bottom, xs.get(k)[1], Math.max(0, avail / keyHeight)));
        }
    }

    /** Flows the shown buttons into the slots and recreates the column views. */
    private void rebuildColumns(int keyHeight, int gap, int toolbarHeight) {
        for (HotkeyColumn c : columns) {
            c.cancelPresses();
            parent.removeView(c);
        }
        columns.clear();
        placement.clear();

        // Rows per slot, bottom-up; a row is one wide button or up to two
        List<List<List<HotkeyLayout.Button>>> filled = new ArrayList<>();
        for (int k = 0; k < slots.size(); k++) {
            filled.add(new ArrayList<>());
        }
        int slot = 0;
        for (HotkeyLayout.Button b : layout.shown()) {
            boolean placed = false;
            while (slot < slots.size()) {
                List<List<HotkeyLayout.Button>> rows = filled.get(slot);
                List<HotkeyLayout.Button> last = rows.isEmpty() ? null : rows.get(rows.size() - 1);
                if (!b.key.wide && last != null && last.size() == 1 && !last.get(0).key.wide) {
                    last.add(b);
                    placed = true;
                }
                else if (rows.size() < slots.get(slot).rows) {
                    List<HotkeyLayout.Button> row = new ArrayList<>();
                    row.add(b);
                    rows.add(row);
                    placed = true;
                }
                if (placed) {
                    break;
                }
                slot++;
            }
            placement.put(b.id, placed ? slot : -1);
        }

        float opacity = prefs.getInt(PREF_OPACITY, 80) / 100f;
        boolean keysShown = !hidden && !collapsed;
        for (int k = 0; k < slots.size(); k++) {
            List<List<HotkeyLayout.Button>> rows = filled.get(k);
            if (k > 0 && rows.isEmpty()) {
                break; // later slots are empty too
            }
            Slot s = slots.get(k);
            boolean rightHand = s.x + s.width / 2 > parent.getWidth() / 2;
            HotkeyColumn c = new HotkeyColumn(activity, rightHand ? ACCENT_RIGHT : ACCENT_LEFT, this);
            c.build(rows, k == 0 ? toolbar : null, keyHeight, gap);
            int h = rows.size() * keyHeight + (k == 0 ? toolbarHeight : 0);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(s.width, h, Gravity.TOP | Gravity.LEFT);
            lp.leftMargin = s.x;
            lp.topMargin = s.bottom - h;
            c.setLayoutParams(lp);
            c.setAlpha(opacity);
            c.setVisibility(keysShown ? View.VISIBLE : View.GONE);
            parent.addView(c);
            columns.add(c);

            float[] off = savedOffset(k);
            float[] clamped = clampOffset(c, off[0], off[1]);
            c.setTranslationX(clamped[0]);
            c.setTranslationY(clamped[1]);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Keep Android's edge-swipe gestures from stealing key presses
                c.setSystemGestureExclusionRects(Collections.singletonList(new Rect(0, 0, s.width, h)));
            }
        }
        releaseUnseenMods();
        refreshModViews();
    }

    /** Name of a column for the editor: right, left, then inner ones. */
    String columnName(int column) {
        if (column < 0) {
            return null;
        }
        if (column >= slots.size()) {
            return "?";
        }
        Slot s = slots.get(column);
        boolean rightHand = s.x + s.width / 2 > parent.getWidth() / 2;
        int nth = column / 2 + 1;
        if (portrait && slots.size() % 2 == 1 && column == slots.size() - 1) {
            return "Mid";
        }
        return (rightHand ? "R" : "L") + nth;
    }

    View parentView() {
        return parent;
    }
}
