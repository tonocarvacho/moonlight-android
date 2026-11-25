package com.limelight.hotkeys;

import android.content.Context;
import android.graphics.Color;

import com.limelight.nvstream.input.KeyboardPacket;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Hotkey overlay layout, parsed from JSON (assets/hotkeys/default.json, or a
 * user file). Format: Docs/hotkeys.md.
 */
final class HotkeyLayout {
    static final String ASSET = "hotkeys/default.json";

    /** Modifier bits, as sent in keyboard packets. Index order = MODS. */
    static final int[] MODS = {
            KeyboardPacket.MODIFIER_META, KeyboardPacket.MODIFIER_CTRL,
            KeyboardPacket.MODIFIER_ALT, KeyboardPacket.MODIFIER_SHIFT,
    };
    static final String[] MOD_NAMES = {"Super", "Ctrl", "Alt", "Shift"};
    private static final String[] MOD_SYMBOLS = {"❖", "^", "⌥", "⇧"};

    /** One chord: modifiers held while one key is pressed. */
    static final class Combo {
        final int mods;
        final int vk;

        Combo(int mods, int vk) {
            this.mods = mods;
            this.vk = vk;
        }
    }

    enum Type { KEYS, MODIFIER, KEYBOARD }

    static final class Key {
        final Type type;
        final String label;
        final String hint;
        final float weight;
        /** KEYS: chords sent in order. One chord = held while the finger is down. */
        final Combo[] steps;
        /** MODIFIER: index into MODS. */
        final int mod;

        Key(Type type, String label, String hint, float weight, Combo[] steps, int mod) {
            this.type = type;
            this.label = label;
            this.hint = hint;
            this.weight = weight;
            this.steps = steps;
            this.mod = mod;
        }
    }

    static final class Page {
        final String name;
        final List<List<Key>> rows;

        Page(String name, List<List<Key>> rows) {
            this.name = name;
            this.rows = rows;
        }
    }

    static final class Side {
        final int accent;
        final List<Page> pages;
        final List<List<Key>> footer;

        Side(int accent, List<Page> pages, List<List<Key>> footer) {
            this.accent = accent;
            this.pages = pages;
            this.footer = footer;
        }

        int maxPageRows() {
            int max = 0;
            for (Page p : pages) {
                max = Math.max(max, p.rows.size());
            }
            return max;
        }

        boolean isEmpty() {
            return pages.isEmpty() && footer.isEmpty();
        }
    }

    final Side left;
    final Side right;

    private HotkeyLayout(Side left, Side right) {
        this.left = left;
        this.right = right;
    }

    static String readAsset(Context context) throws IOException {
        try (InputStream in = context.getAssets().open(ASSET)) {
            return readAll(in);
        }
    }

    static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    static HotkeyLayout parse(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        return new HotkeyLayout(
                parseSide(root.optJSONObject("left"), 0xFF8BE9FD),
                parseSide(root.optJSONObject("right"), 0xFF50FA7B));
    }

    private static Side parseSide(JSONObject obj, int defaultAccent) throws JSONException {
        if (obj == null) {
            return new Side(defaultAccent, Collections.emptyList(), Collections.emptyList());
        }

        int accent = defaultAccent;
        String accentStr = obj.optString("accent", null);
        if (accentStr != null) {
            try {
                accent = Color.parseColor(accentStr);
            } catch (IllegalArgumentException e) {
                throw new JSONException("bad accent color: " + accentStr);
            }
        }

        List<Page> pages = new ArrayList<>();
        JSONArray pagesArr = obj.optJSONArray("pages");
        if (pagesArr != null) {
            for (int i = 0; i < pagesArr.length(); i++) {
                JSONObject page = pagesArr.getJSONObject(i);
                pages.add(new Page(page.optString("name", ""), parseRows(page.optJSONArray("rows"))));
            }
        }
        return new Side(accent, pages, parseRows(obj.optJSONArray("footer")));
    }

    private static List<List<Key>> parseRows(JSONArray rowsArr) throws JSONException {
        List<List<Key>> rows = new ArrayList<>();
        if (rowsArr == null) {
            return rows;
        }
        for (int i = 0; i < rowsArr.length(); i++) {
            JSONArray rowArr = rowsArr.getJSONArray(i);
            List<Key> row = new ArrayList<>();
            for (int j = 0; j < rowArr.length(); j++) {
                row.add(parseKey(rowArr.getJSONObject(j)));
            }
            if (!row.isEmpty()) {
                rows.add(row);
            }
        }
        return rows;
    }

    private static Key parseKey(JSONObject obj) throws JSONException {
        float weight = (float) obj.optDouble("weight", 1.0);
        String label = obj.optString("label", null);
        String hint = obj.optString("hint", null);

        if (obj.has("mod")) {
            int mod = modIndex(obj.getString("mod"));
            if (mod < 0) {
                throw new JSONException("unknown modifier: " + obj.getString("mod"));
            }
            return new Key(Type.MODIFIER, label != null ? label : MOD_NAMES[mod], hint, weight, null, mod);
        }

        if (obj.has("action")) {
            String action = obj.getString("action");
            if (!action.equals("keyboard")) {
                throw new JSONException("unknown action: " + action);
            }
            return new Key(Type.KEYBOARD, label != null ? label : "⌨", hint, weight, null, -1);
        }

        String keys = obj.optString("keys", null);
        if (keys == null || keys.trim().isEmpty()) {
            throw new JSONException("key needs \"keys\", \"mod\" or \"action\": " + obj);
        }
        Combo[] steps = parseSteps(keys);
        if (label == null) {
            // No label: show the chord itself, e.g. "❖U"
            label = describe(steps);
        }
        else if (hint == null) {
            hint = describe(steps);
        }
        if (hint != null && hint.isEmpty()) {
            hint = null;
        }
        return new Key(Type.KEYS, label, hint, weight, steps, -1);
    }

    /** "Super+U" or "Alt+R U" (space separates chords sent one after another). */
    static Combo[] parseSteps(String keys) throws JSONException {
        String[] parts = keys.trim().split("\\s+");
        Combo[] steps = new Combo[parts.length];
        for (int i = 0; i < parts.length; i++) {
            steps[i] = parseCombo(parts[i]);
        }
        return steps;
    }

    private static Combo parseCombo(String combo) throws JSONException {
        // Split on '+' but allow the key itself to be "+" ("Ctrl++")
        List<String> tokens = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < combo.length(); i++) {
            if (combo.charAt(i) == '+' && i > start) {
                tokens.add(combo.substring(start, i));
                start = i + 1;
            }
        }
        tokens.add(combo.substring(start));

        int mods = 0;
        for (int i = 0; i < tokens.size() - 1; i++) {
            int mod = modIndex(tokens.get(i));
            if (mod < 0) {
                throw new JSONException("unknown modifier '" + tokens.get(i) + "' in " + combo);
            }
            mods |= MODS[mod];
        }

        String keyName = tokens.get(tokens.size() - 1);
        Integer vk = VK.get(keyName.toLowerCase(Locale.ROOT));
        if (vk == null) {
            // A bare modifier ("Alt") is a valid chord too
            int mod = modIndex(keyName);
            if (mod >= 0) {
                return new Combo(mods, MOD_VK[mod]);
            }
            throw new JSONException("unknown key '" + keyName + "' in " + combo);
        }
        return new Combo(mods, vk);
    }

    static int modIndex(String name) {
        switch (name.toLowerCase(Locale.ROOT)) {
            case "super": case "meta": case "win": case "cmd": case "mod4":
                return 0;
            case "ctrl": case "control":
                return 1;
            case "alt": case "mod1":
                return 2;
            case "shift":
                return 3;
            default:
                return -1;
        }
    }

    /** Short hint for a chord list, e.g. "❖U" or "⌥R U". */
    static String describe(Combo[] steps) {
        StringBuilder sb = new StringBuilder();
        for (Combo c : steps) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            for (int m = 0; m < MODS.length; m++) {
                if ((c.mods & MODS[m]) != 0) {
                    sb.append(MOD_SYMBOLS[m]);
                }
            }
            String name = VK_NAMES.get(c.vk);
            sb.append(name != null ? name : "?");
        }
        return sb.toString();
    }

    /** Display name of a virtual-key code, e.g. "U" or "Super". */
    static String vkName(int vk) {
        for (int m = 0; m < MOD_VK.length; m++) {
            if (MOD_VK[m] == vk) {
                return MOD_NAMES[m];
            }
        }
        String name = VK_NAMES.get(vk);
        return name != null ? name : String.format(Locale.ROOT, "0x%02X", vk);
    }

    /** Left-side Windows virtual-key codes of the modifiers, in MODS order. */
    static final int[] MOD_VK = {0x5B, 0xA2, 0xA4, 0xA0};

    private static final Map<String, Integer> VK = new HashMap<>();
    private static final Map<Integer, String> VK_NAMES = new HashMap<>();

    private static void vk(int code, String display, String... names) {
        for (String n : names) {
            VK.put(n.toLowerCase(Locale.ROOT), code);
        }
        VK_NAMES.put(code, display);
    }

    static {
        for (char c = 'A'; c <= 'Z'; c++) {
            vk(c, String.valueOf(c), String.valueOf(c));
        }
        for (char c = '0'; c <= '9'; c++) {
            vk(c, String.valueOf(c), String.valueOf(c));
        }
        for (int i = 1; i <= 24; i++) {
            vk(0x70 + i - 1, "F" + i, "F" + i);
        }
        vk(0x1B, "Esc", "Escape", "Esc");
        vk(0x09, "⇥", "Tab");
        vk(0x0D, "⏎", "Enter", "Return");
        vk(0x20, "␣", "Space");
        vk(0x08, "⌫", "Backspace", "BS");
        vk(0x2E, "Del", "Delete", "Del");
        vk(0x2D, "Ins", "Insert", "Ins");
        vk(0x24, "Home", "Home");
        vk(0x23, "End", "End");
        vk(0x21, "PgUp", "PageUp", "PgUp");
        vk(0x22, "PgDn", "PageDown", "PgDn");
        vk(0x26, "↑", "Up");
        vk(0x28, "↓", "Down");
        vk(0x25, "←", "Left");
        vk(0x27, "→", "Right");
        vk(0x2C, "PrtSc", "PrintScreen", "Print");
        vk(0x5D, "Menu", "Menu");
        vk(0x14, "Caps", "CapsLock");
        vk(0xBA, ";", "Semicolon", ";");
        vk(0xBB, "=", "Equals", "Equal", "=");
        vk(0xBC, ",", "Comma", ",");
        vk(0xBD, "-", "Minus", "-");
        vk(0xBE, ".", "Period", "Dot", ".");
        vk(0xBF, "/", "Slash", "/");
        vk(0xC0, "`", "Grave", "Backquote", "`");
        vk(0xDB, "[", "LeftBracket", "[");
        vk(0xDC, "\\", "Backslash", "\\");
        vk(0xDD, "]", "RightBracket", "]");
        vk(0xDE, "'", "Quote", "Apostrophe", "'");
        vk(0xAF, "Vol+", "VolumeUp");
        vk(0xAE, "Vol-", "VolumeDown");
        vk(0xAD, "Mute", "Mute");
        vk(0xB3, "⏯", "PlayPause");
        vk(0xB0, "⏭", "NextTrack");
        vk(0xB1, "⏮", "PrevTrack");
    }
}
