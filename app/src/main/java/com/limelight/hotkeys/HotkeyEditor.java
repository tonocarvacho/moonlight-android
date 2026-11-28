package com.limelight.hotkeys;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.R;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The overlay's ⚙ screen: the section board (drag keys between the five
 * sections and Hidden, keys per row, add and edit hotkeys), key size,
 * opacity, and the portrait arrangement. Every change is saved to
 * hotkeys.json and shown on the overlay right away.
 */
final class HotkeyEditor extends Dialog {
    private static final int BG = 0xFF22232E;
    private static final int CARD = 0xFF2D2F3D;
    private static final int TEXT = 0xFFF8F8F2;
    private static final int DIM = 0xFF9EA0B5;
    private static final int PURPLE = 0xFFBD93F9;
    private static final int GREEN = 0xFF50FA7B;
    private static final int CYAN = 0xFF8BE9FD;
    private static final int RED = 0xFFFF5555;

    private final Activity activity;
    private final HotkeyOverlay overlay;
    /** The dialog's themed context (purple accents), for its views. */
    private final Context ctx;
    private final float dp;

    private HotkeySectionBoard board;

    HotkeyEditor(Activity activity, HotkeyOverlay overlay) {
        super(activity, R.style.HotkeyEditorTheme);
        this.activity = activity;
        this.overlay = overlay;
        this.ctx = getContext();
        this.dp = activity.getResources().getDisplayMetrics().density;
        setContentView(buildContent());
        setOnDismissListener(d -> overlay.onEditorClosed());
    }

    private int px(float dps) {
        return Math.round(dps * dp);
    }

    // ---- Screen ----

    private View buildContent() {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(px(12), px(8), px(12), 0);

        LinearLayout header = new LinearLayout(ctx);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Hotkeys", 20, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button arrange = button("✥ Arrange on screen", PURPLE);
        arrange.setOnClickListener(v -> {
            dismiss();
            overlay.startArranging();
        });
        header.addView(arrange);
        Button close = button("✓ Done", GREEN);
        close.setOnClickListener(v -> dismiss());
        LinearLayout.LayoutParams closeLp = wrap();
        closeLp.leftMargin = px(8);
        header.addView(close, closeLp);
        root.addView(header);

        View options = buildOptions();
        View buttons = buildButtonsSection();

        boolean wide = activity.getResources().getConfiguration().screenWidthDp >= 600;
        if (wide) {
            // Landscape: options on the left, the button list on the right
            LinearLayout body = new LinearLayout(ctx);
            ScrollView left = new ScrollView(ctx);
            left.addView(options);
            body.addView(left, new LinearLayout.LayoutParams(px(290), ViewGroup.LayoutParams.MATCH_PARENT));
            ScrollView right = new ScrollView(ctx);
            right.addView(buttons);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
            rlp.leftMargin = px(12);
            body.addView(right, rlp);
            root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
        else {
            LinearLayout col = new LinearLayout(ctx);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(options);
            col.addView(buttons);
            ScrollView scroll = new ScrollView(ctx);
            scroll.addView(col);
            root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }
        return root;
    }

    private View buildOptions() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, px(8), 0, px(8));

        // Key size
        final TextView sizeLabel = text("", 14, TEXT);
        box.addView(sizeLabel, matchWrap());
        SeekBar size = new SeekBar(ctx);
        size.setMax(HotkeyOverlay.MAX_KEY_HEIGHT_DP - HotkeyOverlay.MIN_KEY_HEIGHT_DP);
        size.setProgress(overlay.keyHeightDp() - HotkeyOverlay.MIN_KEY_HEIGHT_DP);
        sizeLabel.setText("Key height: " + overlay.keyHeightDp() + " dp");
        size.setOnSeekBarChangeListener(new SeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                int value = HotkeyOverlay.MIN_KEY_HEIGHT_DP + progress;
                sizeLabel.setText("Key height: " + value + " dp");
                if (fromUser) {
                    overlay.setKeyHeightDp(value);
                    board.refresh();
                }
            }
        });
        box.addView(size, matchWrap());

        // Opacity
        final TextView opacityLabel = text("", 14, TEXT);
        box.addView(opacityLabel, topMargin(matchWrap(), 8));
        SeekBar opacity = new SeekBar(ctx);
        opacity.setMax(80);
        opacity.setProgress(overlay.opacityPercent() - 20);
        opacityLabel.setText("Opacity: " + overlay.opacityPercent() + "%");
        opacity.setOnSeekBarChangeListener(new SeekBarListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                int value = 20 + progress;
                opacityLabel.setText("Opacity: " + value + "%");
                if (fromUser) {
                    overlay.setOpacityPercent(value);
                }
            }
        });
        box.addView(opacity, matchWrap());

        // Portrait: which half has the keys
        Switch above = new Switch(ctx);
        above.setText("Portrait: keys above the stream");
        above.setTextColor(TEXT);
        above.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        above.setChecked(overlay.keysAbove());
        above.setOnCheckedChangeListener((b, checked) -> {
            overlay.setKeysAbove(checked);
            board.refresh();
        });
        box.addView(above, topMargin(matchWrap(), 12));
        box.addView(text("Turn the phone to portrait: the stream goes in one half, the keys in the other. " +
                "▾ on the keys hides them; the stream then uses the whole screen.", 12, DIM), matchWrap());

        // Landscape: on-screen controller or hotkeys
        Switch controller = new Switch(ctx);
        controller.setText("Landscape: on-screen controller");
        controller.setTextColor(TEXT);
        controller.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        controller.setChecked(overlay.controllerInLandscape());
        controller.setOnCheckedChangeListener((b, checked) -> overlay.setControllerInLandscape(checked));
        box.addView(controller, topMargin(matchWrap(), 12));
        box.addView(text("On: landscape shows only the game controller, portrait the hotkeys. " +
                "Off: the hotkeys show in landscape too.", 12, DIM), matchWrap());

        Button resetPos = button("Reset section positions", CYAN);
        resetPos.setOnClickListener(v -> {
            overlay.resetPositions();
            Toast.makeText(activity, "Sections are back in their default spots", Toast.LENGTH_SHORT).show();
        });
        box.addView(resetPos, topMargin(matchWrap(), 16));

        Button resetAll = button("Reset buttons to default", RED);
        resetAll.setOnClickListener(v -> new AlertDialog.Builder(activity, R.style.HotkeyDialogTheme)
                .setTitle("Reset buttons?")
                .setMessage("Your sections, order, keys per row and own hotkeys are replaced by the built-in list.")
                .setPositiveButton("Reset", (d, w) -> {
                    overlay.resetLayout();
                    board.refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show());
        box.addView(resetAll, topMargin(matchWrap(), 8));
        return box;
    }

    private View buildButtonsSection() {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, px(8), 0, px(24));

        LinearLayout head = new LinearLayout(ctx);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text("Sections", 16, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button add = button("+ New hotkey", GREEN);
        add.setOnClickListener(v -> editButton(null, 1));
        head.addView(add);
        box.addView(head, matchWrap());

        box.addView(text("Hold a key and drag it: to another spot, another section or Hidden. Tap a key to edit it. " +
                "− / + = keys per row, ＋ = new key in that section. Colours match the keys on screen; " +
                "move the sections themselves with ✥ Arrange on screen.", 12, DIM), topMargin(matchWrap(), 4));

        board = new HotkeySectionBoard(ctx, overlay, new HotkeySectionBoard.Listener() {
            @Override
            public void onEdit(HotkeyLayout.Button button) {
                editButton(button.id, button.section);
            }

            @Override
            public void onAdd(int section) {
                editButton(null, section);
            }

            @Override
            public void onMove(HotkeyLayout.Button button, int section, int index) {
                overlay.setLayout(overlay.currentLayout().move(button, section, index));
                board.refresh();
            }

            @Override
            public void onCols(int section, int cols) {
                overlay.setLayout(overlay.currentLayout().withCols(section, cols));
                board.refresh();
            }
        });
        box.addView(board, topMargin(matchWrap(), 10));
        return box;
    }

    private static String describeAction(HotkeyLayout.Button b) {
        switch (b.key.type) {
            case MODIFIER:
                return "sticky " + HotkeyLayout.MOD_NAMES[b.key.mod] + " · tap = next key, double tap = lock";
            case KEYBOARD:
                return "opens the phone keyboard";
            default:
                return b.keysText() + (b.key.wide ? "  · wide" : "");
        }
    }

    // ---- New / edit hotkey ----

    /** id == null: new hotkey, in {@code section}. */
    private void editButton(final String id, int section) {
        final HotkeyLayout layout = overlay.currentLayout();
        final int index = id != null ? layout.indexOf(id) : -1;
        final HotkeyLayout.Button old = index >= 0 ? layout.buttons.get(index) : null;
        final boolean keysType = old == null || old.key.type == HotkeyLayout.Type.KEYS;

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(20), px(8), px(20), 0);

        final EditText label = field(keysType ? "Label, e.g. Term (empty = show the keys)" : "Label (empty = default)");
        final EditText hint = field(keysType ? "Small text under it (empty = show the keys)" : "Small text under it (optional)");
        final EditText keys = field("Keys, e.g. Ctrl+Shift+T");
        keys.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        final TextView status = text("", 13, DIM);
        final CheckBox wide = new CheckBox(ctx);
        wide.setText("Wide (a whole row of its section)");
        final int[] chosenSection = {old != null ? old.section : section};
        View sectionPicker = sectionPicker(chosenSection);

        if (old != null) {
            label.setText(old.spec.optString("label", ""));
            hint.setText(old.spec.optString("hint", ""));
            keys.setText(old.keysText());
            wide.setChecked(old.key.wide);
        }

        box.addView(label, matchWrap());
        box.addView(hint, matchWrap());
        if (keysType) {
            box.addView(keys, matchWrap());
            box.addView(chips(keys), topMargin(matchWrap(), 4));
            box.addView(text("Hold the modifiers with +. A space sends chords one after another: " +
                    "\"Alt+R U\" = tmux prefix, then U.", 12, DIM), topMargin(matchWrap(), 6));
        }
        else {
            box.addView(text(describeAction(old), 13, DIM), topMargin(matchWrap(), 6));
        }
        box.addView(status, topMargin(matchWrap(), 8));
        box.addView(wide, topMargin(matchWrap(), 4));
        box.addView(text("Section", 13, DIM), topMargin(matchWrap(), 8));
        box.addView(sectionPicker, topMargin(matchWrap(), 4));

        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(box);

        AlertDialog.Builder builder = new AlertDialog.Builder(activity, R.style.HotkeyDialogTheme)
                .setTitle(old == null ? "New hotkey" : "Edit " + old.key.label)
                .setView(scroll)
                .setPositiveButton("Save", null)
                .setNegativeButton(android.R.string.cancel, null);
        if (old != null) {
            builder.setNeutralButton("Delete", (d, w) -> {
                List<HotkeyLayout.Button> buttons = new ArrayList<>(overlay.currentLayout().buttons);
                buttons.remove(old);
                overlay.setLayout(overlay.currentLayout().withButtons(buttons));
                board.refresh();
                Toast.makeText(activity, "Deleted " + old.key.label + " (Reset brings back built-in keys)",
                        Toast.LENGTH_LONG).show();
            });
        }
        final AlertDialog dialog = builder.create();

        final Runnable validate = () -> {
            HotkeyLayout.Button b = buildButton(old, id, label, hint, keys, wide, chosenSection[0]);
            Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (b == null) {
                status.setTextColor(RED);
                status.setText(lastError);
                if (save != null) save.setEnabled(false);
            }
            else {
                status.setTextColor(GREEN);
                status.setText(b.key.type == HotkeyLayout.Type.KEYS ? "Sends " + HotkeyLayout.describe(b.key.steps)
                        + "   ·   shows \"" + b.key.label + "\"" : "");
                if (save != null) save.setEnabled(true);
            }
        };
        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { validate.run(); }
        };
        label.addTextChangedListener(watcher);
        hint.addTextChangedListener(watcher);
        keys.addTextChangedListener(watcher);

        dialog.setOnShowListener(d -> {
            validate.run();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                HotkeyLayout.Button b = buildButton(old, id, label, hint, keys, wide, chosenSection[0]);
                if (b == null) {
                    return;
                }
                HotkeyLayout current = overlay.currentLayout();
                List<HotkeyLayout.Button> buttons = new ArrayList<>(current.buttons);
                int at = old != null ? buttons.indexOf(old) : -1;
                if (at >= 0) {
                    buttons.set(at, b);
                }
                else {
                    buttons.add(b);
                }
                HotkeyLayout updated = current.withButtons(buttons);
                if (old == null || old.section != b.section) {
                    // New, or moved to another section: it goes on top of that section
                    updated = updated.move(b, b.section, Integer.MAX_VALUE);
                }
                overlay.setLayout(updated);
                board.refresh();
                dialog.dismiss();
            });
        });
        dialog.show();
        if (old == null || keysType) {
            (old == null ? label : keys).requestFocus();
        }
    }

    private String lastError = "";

    /** The edited button, or null (and lastError) when it isn't valid yet. */
    private HotkeyLayout.Button buildButton(HotkeyLayout.Button old, String id, EditText label, EditText hint,
                                            EditText keys, CheckBox wide, int section) {
        try {
            JSONObject spec = old != null ? new JSONObject(old.spec.toString()) : new JSONObject();
            String l = label.getText().toString().trim();
            String h = hint.getText().toString().trim();
            spec.remove("label");
            if (!l.isEmpty()) spec.put("label", l);
            boolean hadNoHint = old != null && "".equals(old.spec.optString("hint", null));
            spec.remove("hint");
            if (!h.isEmpty()) spec.put("hint", h);
            else if (hadNoHint) spec.put("hint", "");
            spec.remove("wide");
            if (wide.isChecked()) spec.put("wide", true);
            if (old == null || old.key.type == HotkeyLayout.Type.KEYS) {
                String k = keys.getText().toString().trim();
                if (k.isEmpty()) {
                    lastError = "Type the keys to send";
                    return null;
                }
                spec.put("keys", k);
            }
            String newId = id != null ? id : "custom_" + System.currentTimeMillis();
            return new HotkeyLayout.Button(newId, section, spec);
        } catch (JSONException e) {
            lastError = e.getMessage();
            return null;
        }
    }

    /** Hidden, 1 … 5 in the section colours; the chosen one is filled. */
    private View sectionPicker(final int[] chosen) {
        LinearLayout row = new LinearLayout(ctx);
        final List<Button> chips = new ArrayList<>();
        final Runnable paint = () -> {
            for (int s = 0; s < chips.size(); s++) {
                int color = HotkeyOverlay.sectionColor(s);
                boolean on = chosen[0] == s;
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(px(16));
                bg.setColor(on ? color : CARD);
                bg.setStroke(px(1), color);
                chips.get(s).setBackground(bg);
                chips.get(s).setTextColor(on ? HotkeyKeyView.TEXT_DARK : color);
            }
        };
        for (int s = 0; s <= HotkeyLayout.SECTIONS; s++) {
            final int section = s;
            Button chip = chip(s == HotkeyLayout.HIDDEN ? "Hidden" : String.valueOf(s));
            chip.setMinWidth(px(36));
            chip.setMinimumWidth(px(36));
            chip.setPadding(px(10), 0, px(10), 0);
            chip.setOnClickListener(v -> {
                chosen[0] = section;
                paint.run();
            });
            chips.add(chip);
            row.addView(chip);
        }
        paint.run();
        HorizontalScrollView scroll = new HorizontalScrollView(ctx);
        scroll.addView(row);
        return scroll;
    }

    /** Quick inserts for the keys field: modifiers and the full key-name list. */
    private View chips(final EditText keys) {
        LinearLayout row = new LinearLayout(ctx);
        String[] mods = {"Super+", "Ctrl+", "Alt+", "Shift+", "then ␣"};
        for (final String m : mods) {
            Button chip = chip(m);
            chip.setOnClickListener(v -> insert(keys, m.equals("then ␣") ? " " : m));
            row.addView(chip);
        }
        Button names = chip("Key names…");
        names.setOnClickListener(v -> {
            final String[] items = HotkeyLayout.KEY_NAMES.toArray(new String[0]);
            new AlertDialog.Builder(activity, R.style.HotkeyDialogTheme)
                    .setTitle("Insert key")
                    .setItems(items, (d, which) -> insert(keys, items[which]))
                    .show();
        });
        row.addView(names);
        HorizontalScrollView scroll = new HorizontalScrollView(ctx);
        scroll.addView(row);
        return scroll;
    }

    private static void insert(EditText field, String s) {
        int start = Math.max(field.getSelectionStart(), 0);
        int end = Math.max(field.getSelectionEnd(), 0);
        field.getText().replace(Math.min(start, end), Math.max(start, end), s);
    }

    // ---- Small view helpers ----

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private Button button(String s, int color) {
        Button b = new Button(ctx);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(color);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(px(18));
        bg.setStroke(px(1), (color & 0x00FFFFFF) | 0x80000000);
        b.setBackground(bg);
        b.setMinHeight(px(38));
        b.setMinimumHeight(px(38));
        b.setPadding(px(14), 0, px(14), 0);
        return b;
    }

    private Button chip(String s) {
        Button b = button(s, PURPLE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setMinHeight(px(32));
        b.setMinimumHeight(px(32));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        LinearLayout.LayoutParams lp = wrap();
        lp.rightMargin = px(6);
        b.setLayoutParams(lp);
        return b;
    }

    private EditText field(String hint) {
        EditText e = new EditText(ctx);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        // Keep the dialog (and its "Sends ..." check) visible above the keyboard in landscape
        e.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        return e;
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams topMargin(LinearLayout.LayoutParams lp, int dps) {
        lp.topMargin = px(dps);
        return lp;
    }

    private abstract static class SeekBarListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
        @Override public void onStopTrackingTouch(SeekBar seekBar) {}
    }
}
