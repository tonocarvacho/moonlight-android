package com.limelight.hotkeys;

import android.content.Context;

import com.limelight.nvstream.input.KeyboardPacket;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Hotkey overlay layout: one ordered list of buttons, each in one of
 * {@link #SECTIONS} sections (or hidden, section 0). A section is a block of
 * keys the user can move anywhere on screen; its buttons fill it bottom-up,
 * {@code cols} keys per row. Parsed from JSON (assets/hotkeys/default.json,
 * or the user's hotkeys.json written by the in-stream editor). Format:
 * Docs/hotkeys.md.
 */
final class HotkeyLayout {
    static final String ASSET = "hotkeys/default.json";
    static final int VERSION = 3;
    static final int SECTIONS = 5;
    static final int HIDDEN = 0;
    static final int DEFAULT_COLS = 2;
    static final int MAX_COLS = 6;
    /** Rows per column of version 2 files on the S24 in landscape (default key height). */
    private static final int V2_ROWS = 8;

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
        /** Takes a whole column row instead of half. */
        final boolean wide;
        /** KEYS: chords sent in order. One chord = held while the finger is down. */
        final Combo[] steps;
        /** MODIFIER: index into MODS. */
        final int mod;

        Key(Type type, String label, String hint, boolean wide, Combo[] steps, int mod) {
            this.type = type;
            this.label = label;
            this.hint = hint;
            this.wide = wide;
            this.steps = steps;
            this.mod = mod;
        }
    }

    /**
     * One entry of the list. {@code spec} is the button's JSON as written
     * (label, hint, keys/mod/action, wide), kept so it saves back unchanged.
     */
    static final class Button {
        final String id;
        /** 1..SECTIONS, or HIDDEN. */
        final int section;
        final JSONObject spec;
        final Key key;

        Button(String id, int section, JSONObject spec) throws JSONException {
            this.id = id;
            this.section = section;
            this.spec = spec;
            this.key = parseKey(spec);
        }

        Button inSection(int value) {
            try {
                return new Button(id, value, spec);
            } catch (JSONException e) {
                throw new IllegalStateException(e); // spec already parsed once
            }
        }

        /** The chord text for the editor, e.g. "Alt+R U" ("" for modifiers/actions). */
        String keysText() {
            return spec.optString("keys", "");
        }
    }

    final List<Button> buttons;
    /** Keys per row of each section, index 0 = section 1. */
    final int[] cols;
    /** Built-in button ids this list already knows (so deleted ones stay deleted). */
    final Set<String> seen;
    /** In {@link #seen}: every built-in button counts as known (converted version 1 files). */
    private static final String SEEN_ALL = "*";

    HotkeyLayout(List<Button> buttons, int[] cols, Set<String> seen) {
        this.buttons = buttons;
        this.cols = cols;
        this.seen = seen;
    }

    HotkeyLayout withButtons(List<Button> newButtons) {
        return new HotkeyLayout(newButtons, cols, seen);
    }

    HotkeyLayout withCols(int section, int value) {
        int[] c = cols.clone();
        c[section - 1] = Math.max(1, Math.min(MAX_COLS, value));
        return new HotkeyLayout(buttons, c, seen);
    }

    int colsOf(int section) {
        return cols[section - 1];
    }

    private static int[] defaultCols() {
        int[] c = new int[SECTIONS];
        Arrays.fill(c, DEFAULT_COLS);
        return c;
    }

    int indexOf(String id) {
        for (int i = 0; i < buttons.size(); i++) {
            if (buttons.get(i).id.equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /** The buttons of one section (HIDDEN: the hidden ones), in fill order. */
    List<Button> inSection(int section) {
        List<Button> out = new ArrayList<>();
        for (Button b : buttons) {
            if (b.section == section) {
                out.add(b);
            }
        }
        return out;
    }

    /**
     * Moves a button to {@code section}, before the {@code index}-th button
     * already there (index past the end = last). The others keep their order.
     */
    HotkeyLayout move(Button moved, int section, int index) {
        List<Button> list = new ArrayList<>(buttons);
        if (!list.remove(moved)) {
            return this;
        }
        int at = -1, seenInSection = 0, lastInSection = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).section == section) {
                if (seenInSection++ == index) {
                    at = i;
                    break;
                }
                lastInSection = i;
            }
        }
        if (at < 0) {
            at = lastInSection >= 0 ? lastInSection + 1 : list.size();
        }
        list.add(at, moved.section == section ? moved : moved.inSection(section));
        return withButtons(list);
    }

    /**
     * Rows of a section, bottom row first: each row is one wide button or up
     * to {@code cols} buttons. A button alone before a wide one keeps its row.
     */
    static List<List<Button>> rows(List<Button> buttons, int cols) {
        List<List<Button>> rows = new ArrayList<>();
        for (Button b : buttons) {
            List<Button> last = rows.isEmpty() ? null : rows.get(rows.size() - 1);
            if (!b.key.wide && last != null && last.size() < cols && !last.get(0).key.wide) {
                last.add(b);
            }
            else {
                List<Button> row = new ArrayList<>();
                row.add(b);
                rows.add(row);
            }
        }
        return rows;
    }

    /** Built-in buttons added in an app update show up at the end of the user's list. */
    static HotkeyLayout merge(HotkeyLayout user, HotkeyLayout defaults) {
        List<Button> buttons = new ArrayList<>(user.buttons);
        Set<String> ids = new HashSet<>();
        for (Button b : buttons) {
            ids.add(b.id);
        }
        boolean seenAll = user.seen.contains(SEEN_ALL);
        Set<String> seen = new LinkedHashSet<>(user.seen);
        seen.remove(SEEN_ALL);
        for (Button b : defaults.buttons) {
            if (!seenAll && !ids.contains(b.id) && !user.seen.contains(b.id)) {
                buttons.add(b);
            }
            seen.add(b.id);
        }
        return new HotkeyLayout(buttons, user.cols, seen);
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
        if (!root.has("buttons") && (root.has("left") || root.has("right"))) {
            return parseLegacy(root);
        }

        boolean v2 = root.optInt("version", VERSION) < 3;
        JSONArray arr = root.getJSONArray("buttons");
        List<Button> buttons = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.getJSONObject(i);
            String id = obj.optString("id", "");
            if (id.isEmpty() || !ids.add(id)) {
                throw new JSONException("button " + (i + 1) + " needs a unique \"id\": " + obj);
            }
            int section;
            if (v2) {
                section = obj.optBoolean("show", true) ? 1 : HIDDEN;
            }
            else {
                section = obj.optInt("section", HIDDEN);
                if (section < HIDDEN || section > SECTIONS) {
                    throw new JSONException("\"section\" must be 0 (hidden) to " + SECTIONS + ": " + obj);
                }
            }
            buttons.add(new Button(id, section, specOf(obj)));
        }

        int[] cols = defaultCols();
        JSONArray sections = root.optJSONArray("sections");
        for (int i = 0; sections != null && i < sections.length() && i < SECTIONS; i++) {
            int c = sections.getJSONObject(i).optInt("cols", DEFAULT_COLS);
            cols[i] = Math.max(1, Math.min(MAX_COLS, c));
        }
        if (v2) {
            buttons = flowIntoSections(buttons);
        }

        Set<String> seen = new LinkedHashSet<>();
        JSONArray seenArr = root.optJSONArray("seen");
        if (seenArr != null) {
            for (int i = 0; i < seenArr.length(); i++) {
                seen.add(seenArr.getString(i));
            }
        }
        else {
            seen.addAll(ids);
        }
        return new HotkeyLayout(buttons, cols, seen);
    }

    /**
     * Version 2 had one list of shown buttons filling the right column, then
     * the left, then inner ones. They go into sections 1, 2, ... the way they
     * filled the S24 in landscape, so the keys stay where they were.
     */
    private static List<Button> flowIntoSections(List<Button> buttons) {
        List<Button> out = new ArrayList<>();
        List<Button> current = new ArrayList<>();
        int section = 1;
        for (Button b : buttons) {
            if (b.section == HIDDEN) {
                out.add(b);
                continue;
            }
            current.add(b);
            if (section < SECTIONS && rows(current, DEFAULT_COLS).size() > V2_ROWS) {
                current.clear();
                current.add(b);
                section++;
            }
            out.add(b.inSection(section));
        }
        return out;
    }

    /** The button's own fields, without the list bookkeeping (id, section, show). */
    private static JSONObject specOf(JSONObject obj) throws JSONException {
        JSONObject spec = new JSONObject(obj.toString());
        spec.remove("id");
        spec.remove("show");
        spec.remove("section");
        return spec;
    }

    String toJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("_doc", "Hotkey overlay layout, written by the in-stream editor. Format: Docs/hotkeys.md");
            root.put("version", VERSION);
            JSONArray sections = new JSONArray();
            for (int c : cols) {
                sections.put(new JSONObject().put("cols", c));
            }
            root.put("sections", sections);
            JSONArray arr = new JSONArray();
            for (Button b : buttons) {
                JSONObject obj = new JSONObject();
                obj.put("id", b.id);
                obj.put("section", b.section);
                for (Iterator<String> it = b.spec.keys(); it.hasNext(); ) {
                    String k = it.next();
                    obj.put(k, b.spec.get(k));
                }
                arr.put(obj);
            }
            root.put("buttons", arr);
            root.put("seen", new JSONArray(seen));
            return root.toString(1);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Version 1 files (left/right sides with pages and a footer). Converted so
     * they look as before: the right side's footer and first page go in
     * section 1, the left side's in section 2 (bottom-up); the other pages
     * are kept but hidden.
     */
    private static HotkeyLayout parseLegacy(JSONObject root) throws JSONException {
        List<Button> shown = new ArrayList<>();
        List<Button> hidden = new ArrayList<>();
        for (String sideName : new String[]{"right", "left"}) {
            JSONObject side = root.optJSONObject(sideName);
            if (side == null) {
                continue;
            }
            JSONArray pages = side.optJSONArray("pages");
            List<JSONArray> bottomUp = new ArrayList<>();
            addRowsBottomUp(bottomUp, side.optJSONArray("footer"));
            if (pages != null && pages.length() > 0) {
                addRowsBottomUp(bottomUp, pages.getJSONObject(0).optJSONArray("rows"));
            }
            int section = sideName.equals("right") ? 1 : 2;
            addLegacyRows(bottomUp, section, shown, hidden, sideName);
            for (int p = 1; pages != null && p < pages.length(); p++) {
                List<JSONArray> rows = new ArrayList<>();
                JSONArray arr = pages.getJSONObject(p).optJSONArray("rows");
                for (int r = 0; arr != null && r < arr.length(); r++) {
                    rows.add(arr.getJSONArray(r));
                }
                addLegacyRows(rows, HIDDEN, shown, hidden, sideName);
            }
        }
        List<Button> all = new ArrayList<>(shown);
        all.addAll(hidden);
        // Its buttons have their own ids: don't add the built-in ones next to them
        Set<String> seen = new LinkedHashSet<>();
        seen.add(SEEN_ALL);
        return new HotkeyLayout(all, defaultCols(), seen);
    }

    private static void addRowsBottomUp(List<JSONArray> out, JSONArray rows) throws JSONException {
        for (int r = rows != null ? rows.length() - 1 : -1; r >= 0; r--) {
            out.add(rows.getJSONArray(r));
        }
    }

    private static void addLegacyRows(List<JSONArray> rows, int section, List<Button> shown,
                                      List<Button> hidden, String side) throws JSONException {
        for (JSONArray row : rows) {
            for (int k = 0; k < row.length(); k++) {
                JSONObject spec = new JSONObject(row.getJSONObject(k).toString());
                spec.remove("weight");
                if (row.length() == 1) {
                    spec.put("wide", true);
                }
                List<Button> out = section != HIDDEN ? shown : hidden;
                out.add(new Button(side + "_" + (shown.size() + hidden.size()), section, spec));
            }
        }
    }

    static Key parseKey(JSONObject obj) throws JSONException {
        boolean wide = obj.optBoolean("wide", false);
        String label = emptyToNull(obj.optString("label", null));
        String hint = obj.optString("hint", null);

        if (obj.has("mod")) {
            int mod = modIndex(obj.getString("mod"));
            if (mod < 0) {
                throw new JSONException("unknown modifier: " + obj.getString("mod"));
            }
            return new Key(Type.MODIFIER, label != null ? label : MOD_NAMES[mod], emptyToNull(hint), wide, null, mod);
        }

        if (obj.has("action")) {
            String action = obj.getString("action");
            if (!action.equals("keyboard")) {
                throw new JSONException("unknown action: " + action);
            }
            return new Key(Type.KEYBOARD, label != null ? label : "⌨", emptyToNull(hint), wide, null, -1);
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
        return new Key(Type.KEYS, label, emptyToNull(hint), wide, steps, -1);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
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
    /** One name per key, for the editor's key list. */
    static final List<String> KEY_NAMES = new ArrayList<>();

    private static void vk(int code, String display, String... names) {
        for (String n : names) {
            VK.put(n.toLowerCase(Locale.ROOT), code);
        }
        VK_NAMES.put(code, display);
        KEY_NAMES.add(names[0]);
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
