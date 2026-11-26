package com.limelight.hotkeys;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.limelight.nvstream.input.KeyboardPacket;

import java.util.ArrayDeque;

/**
 * Debug builds only: the hotkey overlay over a fake 16:9 stream, with every
 * key packet shown on screen and logged (tag "HotkeyPreview"). For trying
 * layouts without a PC (turns to portrait like a real stream):
 *   adb shell am start -n com.tonycgames.moonlight.debug/com.limelight.hotkeys.HotkeyPreviewActivity
 */
public class HotkeyPreviewActivity extends Activity {
    private static final String TAG = "HotkeyPreview";
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private TextView log;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
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

        HotkeyOverlay.create(this, root, stream, 1920, 1080, this::onKey, () -> {
            onLine("toggle Android keyboard");
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).toggleSoftInput(0, 0);
        });
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

    /** Stand-in for StreamView: a 16:9 box, letterboxed like a real stream. */
    private static final class FakeStream extends View {
        FakeStream(Context context) {
            super(context);
            setBackgroundColor(0xFF282A36);
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
