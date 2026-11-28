package com.limelight.hotkeys;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.zoom.StreamZoom;

import java.util.ArrayDeque;

/**
 * Debug builds only: the hotkey overlay over a fake 16:9 stream, with every
 * key packet shown on screen and logged (tag "HotkeyPreview"). For trying
 * layouts without a PC (turns to portrait like a real stream). The fake
 * stream is a SurfaceView like the real one, with the two-finger zoom
 * (Docs/zoom.md); a one-finger tap logs the video point under it. Like Game,
 * landscape hides the keys while the on-screen controller is on (⚙ switch).
 *   adb shell am start -n com.tonycgames.moonlight.debug/com.limelight.hotkeys.HotkeyPreviewActivity
 */
public class HotkeyPreviewActivity extends Activity {
    private static final String TAG = "HotkeyPreview";
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private TextView log;
    private HotkeyOverlay overlay;
    private boolean controllerShown;
    private final SharedPreferences.OnSharedPreferenceChangeListener controllerSwitch = (prefs, key) -> {
        if (LandscapeController.PREF.equals(key)) {
            updateController();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        // Like Game: touches go to a background view behind the stream
        View background = new View(this);
        root.addView(background, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        View stream = new FakeStream(this);
        root.addView(stream, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        log = new TextView(this);
        log.setTextColor(0xFFF8F8F2);
        log.setTextSize(14);
        log.setGravity(Gravity.CENTER);
        log.setText("Tap a hotkey");
        root.addView(log, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(root);

        overlay = HotkeyOverlay.create(this, root, stream, 1920, 1080, this::onKey, () -> {
            onLine("toggle Android keyboard");
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).toggleSoftInput(0, 0);
        });

        PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener(controllerSwitch);
        updateController();

        StreamZoom zoom = StreamZoom.create(this, stream, () -> onLine("zoom: touches cancelled"));
        background.setOnTouchListener(new View.OnTouchListener() {
            @SuppressLint("ClickableViewAccessibility")
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (zoom != null && zoom.onTouchEvent(v, event)) {
                    return true;
                }
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    // Same mapping as absolute touch in Game
                    float x = StreamZoom.toVideoX(stream, event.getX() - stream.getX());
                    float y = StreamZoom.toVideoY(stream, event.getY() - stream.getY());
                    x = Math.min(Math.max(x, 0), stream.getWidth());
                    y = Math.min(Math.max(y, 0), stream.getHeight());
                    onLine(String.format(java.util.Locale.ROOT, "tap at video %.0f%%, %.0f%%  (zoom %.2fx)",
                            100 * x / stream.getWidth(), 100 * y / stream.getHeight(), stream.getScaleX()));
                }
                else if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) {
                    onLine("finger " + event.getPointerCount() + " down (Moonlight)");
                }
                else if (event.getActionMasked() == MotionEvent.ACTION_MOVE && event.getPointerCount() == 2) {
                    onLine("two-finger move (Moonlight scroll)");
                }
                return true;
            }
        });
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateController();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(controllerSwitch);
    }

    /** Game's rule (updateTouchOverlays): landscape + controller on = no keys. */
    private void updateController() {
        if (overlay == null) {
            return;
        }
        boolean shown = getResources().getConfiguration().orientation != Configuration.ORIENTATION_PORTRAIT &&
                LandscapeController.isOn(this);
        overlay.setYielded(shown);
        if (shown != controllerShown) {
            controllerShown = shown;
            onLine(shown ? "on-screen controller shown, hotkeys hidden" : "hotkeys shown");
        }
    }

    private void onKey(short keyMap, byte direction, byte modifiers) {
        StringBuilder mods = new StringBuilder();
        for (int i = 0; i < HotkeyLayout.MODS.length; i++) {
            if ((modifiers & HotkeyLayout.MODS[i]) != 0) {
                mods.append(HotkeyLayout.MOD_NAMES[i]).append('+');
            }
        }
        onLine((direction == KeyboardPacket.KEY_DOWN ? "down " : "up   ") +
                HotkeyLayout.vkName(keyMap & 0xFF) + (mods.length() > 0 ? "   [" + mods.substring(0, mods.length() - 1) + "]" : ""));
    }

    private void onLine(String line) {
        Log.i(TAG, line);
        lines.addLast(line);
        while (lines.size() > 10) {
            lines.removeFirst();
        }
        log.setText(String.join("\n", lines));
    }

    /** Stand-in for StreamView: a 16:9 SurfaceView, letterboxed like a real stream. */
    private static final class FakeStream extends SurfaceView implements SurfaceHolder.Callback {
        FakeStream(Context context) {
            super(context);
            getHolder().setFixedSize(1920, 1080);
            getHolder().addCallback(this);
        }

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            // A grid with labelled cells, to see where the zoom is
            Canvas c = holder.lockCanvas();
            if (c == null) {
                return;
            }
            c.drawColor(0xFF282A36);
            Paint line = new Paint();
            line.setColor(0xFF6272A4);
            line.setStrokeWidth(3);
            Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
            text.setColor(0xFFF8F8F2);
            text.setTextSize(40);
            for (int i = 0; i <= 8; i++) {
                c.drawLine(i * 240, 0, i * 240, 1080, line);
            }
            for (int j = 0; j <= 6; j++) {
                c.drawLine(0, j * 180, 1920, j * 180, line);
            }
            for (int i = 0; i < 8; i++) {
                for (int j = 0; j < 6; j++) {
                    c.drawText((char) ('A' + i) + "" + (j + 1), i * 240 + 90, j * 180 + 105, text);
                }
            }
            Paint border = new Paint();
            border.setColor(0xFFFF5555);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(12);
            c.drawRect(6, 6, 1914, 1074, border);
            holder.unlockCanvasAndPost(c);
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int w = MeasureSpec.getSize(widthSpec);
            int h = MeasureSpec.getSize(heightSpec);
            if (w * 9 > h * 16) {
                w = h * 16 / 9;
            }
            else {
                h = w * 9 / 16;
            }
            setMeasuredDimension(w, h);
        }
    }
}
