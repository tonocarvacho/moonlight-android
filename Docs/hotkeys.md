# Hotkey overlay

Fork feature: translucent desktop hotkeys over the stream, built for the
Fedora/AwesomeWM/tmux/nvim setup in `~/.dotfiles`. In landscape the keys sit
in the letterbox bars (16:9 video on the S24's wider screen); in portrait the
stream takes one half of the screen and the keys the other.

Settings → **Hotkey Overlay**: on/off (default on), opacity (default 80%),
**Screen rotation** (default "Turn with the phone"; also "Follow the phone's
auto-rotate setting" or "Landscape only"). Rotation needs the overlay on
(without it the stream stays landscape, like upstream).

### Hotkeys or on-screen controller

The fork turns upstream's on-screen controller ("Show on-screen controls")
**on by default** and switches with the phone:

- **portrait**: the hotkeys, no controller (upstream's controller is
  landscape-only);
- **landscape**: only the on-screen controller, no hotkeys.

⚙ → **Landscape: on-screen controller** switches it during the stream (it
is the same setting as Settings → On-screen Controls). Off: no controller,
and the hotkeys show in landscape too. To reach ⚙ while the controller is
on, turn the phone to portrait. With the hotkey overlay off in Settings,
the controller works as upstream (landscape, always shown).

## Using it

- **Keys** send their shortcut while your finger is down. Holding a single
  shortcut, like an arrow key, auto-repeats on the PC.
- **Five sections.** Every button is in one of five sections or hidden.
  A section is a block of keys you can put anywhere on screen; each has its
  own colour (1 green, 2 cyan, 3 purple, 4 orange, 5 pink: key hints, the
  editor and arrange mode use it). By default section 1 is the right
  letterbox bar, 2 the left one, 3–5 are empty (their default spots are
  further in, over the video). A section fills **from the bottom up**, two
  keys per row by default (1–6, set per section); a *wide* key takes a
  whole row (a key alone before a wide one, or at the end, also fills its
  row). Keys that don't fit the screen height aren't shown (the editor and
  arrange mode say so). Empty sections take no room and no touches.
- **⚙** (toolbar on top of section 1) opens the editor, **▾** hides all
  keys. A slim tab stays at the right edge; tap it to bring them back.
- **Sticky modifiers** (Super, Ctrl, Alt, Shift):
  - tap: applies to the next key only (outline + dot),
  - double tap: locked (filled + bar) until tapped again,
  - hold: held while the finger is down, so you can chord with another
    finger.
  A latched modifier is really held on the PC, so it also works with the
  phone keyboard and with touch/mouse input. For example, lock Super and
  drag to move a window.
- Multi-step keys (tmux `prefix` + key) are always sent without latched
  modifiers, so a locked Shift doesn't break them.
- **⌨** opens the Android keyboard (same as the three-finger tap).

### Portrait

Turn the phone: the stream is fitted to the screen width at the top and the
keys fill the bottom half: sections 1–3 side by side on the S24 (right,
left, middle), 4 and 5 stacked on top of 1 and 2. The editor's "Portrait: keys above the stream" swaps the halves.
▾ hides the keys and centres the stream. In absolute-touch mode the empty
part of the key half ignores touches (no accidental clicks); in trackpad
mode (Moonlight's default) it works as a touchpad.

### Editor (⚙)

The **section board** shows each section as a card drawn like it looks on
screen (top row on top, same keys per row, same colour), plus a **Hidden**
card with every hidden button. Built-in extras start hidden: digits 0–9
and the old Move / Nav / Pane / Vim pages.

- **Hold a key and drag it** (a short hold, then move): drop it on the
  left or right half of another key to go before / after it, on a
  section's free space to go on top of that section, or on Hidden. The
  white bar shows where it lands; the board scrolls near its edges.
- **Tap a key** to edit it: label, small text, keys (e.g. `Ctrl+Shift+T`;
  chips insert modifiers and key names), wide, and its **section** (Hidden,
  1–5). The dialog checks the keys as you type ("Sends ^⇧T"). Moving it to
  another section puts it on top there. **Delete** removes it.
- On a card: **＋** (filled, section colour) adds a new hotkey to that
  section, **− n +** sets its keys per row. The card's info line says how
  many rows fit on this screen; rows past that are below a red "no room
  on screen" line.
- **+ New hotkey** adds one to section 1. **Reset buttons to default**
  brings back the built-in sections and keys.
- Key height (30–72 dp, default 42), opacity, keys above/below the stream in
  portrait, **Landscape: on-screen controller** (above), **Reset section
  positions**.
- **✥ Arrange on screen**: every section gets an outline and a ⠿ grip in
  its colour; empty sections show as dashed "empty" boxes. Drag a key to
  any spot in any section (same rules as the board), or onto "Drop here to
  hide". Drag a ⠿ grip to move the whole section (saved per orientation;
  "Reset spots" puts them back). A grip says "+N off screen" when keys of
  that section don't fit. **⚙ Keys** goes to the editor. Keys don't send
  anything while arranging.

Everything is saved at once to `hotkeys.json` (below) and the overlay updates
live. Section positions, key height, the portrait swap and the hidden state
are in the `hotkey_overlay` shared preferences.

## Default layout

`app/src/main/assets/hotkeys/default.json`, taken from `~/.dotfiles`. The
shown keys reproduce the old two-sided look in landscape:

| Section (bottom → top) | Keys |
| --- | --- |
| 1 (right) | ⌨ kbd / ⏎ Enter, Esc / Tab, then tmux: files (Ctrl+P) / yazi (Alt+B), session picker (Alt+E) / last session (Alt+K), tab 5 (prefix j) / last tab (Alt+J), tabs 3/4, tabs 1/2 (prefix u/i/o/p), new/close tab (Alt+T/W) |
| 2 (left) | Alt / Shift, Super / Ctrl (sticky), fullscreen / close, last/next window, tag 5 (Super+2*) / Spotify (Super+Y), tags 3/4, tags 1/2 (Super+U/I/O/P), Apps (rofi, Ctrl+Space, wide) |
| 3–5 | empty |
| Hidden | digits 0–9; Move: move window to tag 1–4, master/swap, float, other screen, wider/thinner, layout, area screenshot; Nav: arrows, PgUp/PgDn, Home/End, Backspace/Delete, Ctrl+C/D; Pane: sessions 1–5 (prefix 1–5), copy mode, split, select/break/kill pane, bare prefix (Alt+R); Vim: save, quit all (␣o), alt/prev buffer, half-page up/down, undo/redo, delete buffer, no highlight, `:` and `/` |

\* rc.lua binds tags with `"#" .. i + 6`. Lua evaluates that as keycode
`i + 6`, so tag 5 is on Super+2 (X keycode 11), not Super+5.

Built-in buttons added to `default.json` later appear on top of their
default section in an existing user list (or hidden, as in the default). Deleted built-ins
stay deleted: the file remembers which built-in ids it has `seen`.

## hotkeys.json

The editor writes `hotkeys.json` in the app's files folder; it replaces the
built-in list. It can also be pulled, edited and pushed by hand. If it has an
error, a toast says what is wrong and the built-in layout is used.

```sh
adb pull /sdcard/Android/data/com.tonycgames.moonlight.unofficial/files/hotkeys.json
adb push my-hotkeys.json \
  /sdcard/Android/data/com.tonycgames.moonlight.unofficial/files/hotkeys.json
# debug build: com.tonycgames.moonlight.debug
```

To change the default for everyone, edit `default.json` and push.

### Preview without a PC (debug builds)

Debug builds include `HotkeyPreviewActivity` (`app/src/debug`). It shows the
overlay over a fake 16:9 stream and lists every key packet on screen and in
logcat (tag `HotkeyPreview`):

```sh
adb shell am start -n com.tonycgames.moonlight.debug/com.limelight.hotkeys.HotkeyPreviewActivity
adb logcat -s HotkeyPreview
```

The fake stream is a real SurfaceView with a labelled grid and the
[stream zoom](zoom.md) wired up like Game, so pinch/pan can be tested there too.

Use it to check a layout or to test overlay changes on an emulator (an
S24-sized AVD is 1080x2340 at 450 dpi; AVD `s24_hotkeys`). It turns with the
device like a real stream: `adb emu rotate` switches portrait/landscape. It
follows the controller rule too (no controller drawn: in landscape with
the switch on the keys hide and the log says "on-screen controller shown").

### Format

```json
{
  "version": 3,
  "sections": [{"cols": 2}, {"cols": 2}, {"cols": 3}, {"cols": 2}, {"cols": 2}],
  "buttons": [
    {"id": "esc", "section": 1, "label": "Esc", "hint": "", "keys": "Escape"},
    {"id": "wm_apps", "section": 2, "label": "Apps", "hint": "rofi", "keys": "Ctrl+Space", "wide": true},
    {"id": "num_0", "section": 0, "label": "0", "keys": "0"}
  ],
  "seen": ["esc", "wm_apps", "num_0"]
}
```

`section`: 1–5, or 0 = hidden (the default). Within a section, list order
= fill order (first = bottom row, left). `sections[i].cols` = keys per row
of section i+1 (1–6, default 2; the list is optional). Every button needs
a unique `id`; `wide` defaults to false. `seen` is optional (default: the
ids in the file). Older files still load:

- version 2 (one list with `show`): shown buttons go into sections 1, 2, …
  8 rows each, the way they filled the S24 in landscape, so keys stay put;
- version 1 (`left`/`right` sides with `pages` and a `footer`): the right
  side's footer and first page go in section 1, the left side's in
  section 2, the other pages hidden.

A button is one of:

| Button | Meaning |
| --- | --- |
| `{"label": "1", "hint": "tag", "keys": "Super+U"}` | One chord, held while touched. `hint` is optional (by default it shows the chord, e.g. `❖U`; `""` = none). Without a `label` the chord is the label. |
| `{"label": "1", "keys": "Alt+R U"}` | Chords separated by spaces are sent one after another (tmux prefix, then key). |
| `{"mod": "Super"}` | Sticky modifier: `Super`/`Meta`/`Win`, `Ctrl`, `Alt`, `Shift`. |
| `{"action": "keyboard"}` | Toggle the Android keyboard. |

Key names (case-insensitive): `A`–`Z`, `0`–`9`, `F1`–`F24`, `Escape`,
`Tab`, `Enter`, `Space`, `Backspace`, `Delete`, `Insert`, `Home`, `End`,
`PageUp`, `PageDown`, `Up`, `Down`, `Left`, `Right`, `PrintScreen`, `Menu`,
`CapsLock`, `Semicolon`, `Equals`, `Comma`, `Minus`, `Period`, `Slash`,
`Grave`, `LeftBracket`, `Backslash`, `RightBracket`, `Quote`, `VolumeUp`,
`VolumeDown`, `Mute`, `PlayPause`, `NextTrack`, `PrevTrack`, and the
modifier names on their own (`Alt` = a bare Alt press). Keys are US-layout
positions, like the rest of Moonlight.

## Code

Package `com.limelight.hotkeys`, all fork-only:

- `HotkeyLayout`: JSON model (ordered `Button` list with sections, keys per
  row, `seen` ids), `move()` (to a section and position), `rows()` (the
  bottom-up fill of one section), parser and writer, version 1/2
  conversion, merge with new built-ins, key-name → Windows VK table.
- `HostKeyboard`: sends key packets. Modifiers are reference-counted, so a
  sticky Super, a finger holding Super and a `Super+U` key give the host
  one press and one release.
- `HotkeyOverlay`: sticky-modifier state machine; layout: default column
  slots (landscape: letterbox bars then inward; portrait: even grid in the
  key half, extra sections stacked), one view per section (empty ones are
  invisible and pass touches through), rows that fit, stream placement in
  portrait, display-cutout and gesture-exclusion insets; saves
  `hotkeys.json`; section offsets (`off_l<k>` / `off_p<k>`, k = section − 1).
- `HotkeyColumn` (+ `Toolbar`, `Handle`) / `HotkeyKeyView`: the views
  (custom-drawn, Dracula colours). A `HotkeyColumn` is one section.
- `HotkeyEditor`: the ⚙ dialog and the hotkey dialog (views built in code,
  theme `HotkeyEditorTheme` in `res/values/styles_fork.xml`).
- `HotkeySectionBoard`: the editor's custom-drawn section board (cards,
  hold-and-drag between sections, keys-per-row and add buttons,
  auto-scroll).
- `HotkeyArrangeLayer`: full-screen layer for arrange mode (key drop at any
  spot, section drag, hide drop zone).
- `StreamRotation`: the orientation Game requests (Settings → Screen
  rotation).
- `LandscapeController`: the on-screen controller setting (upstream's
  `checkbox_show_onscreen_controls`) for the editor switch, and the
  one-time fork default (on, flag `fork_osc_default_on`) applied in PcView
  and Game. `HotkeyOverlay.setYielded()` hides the keys while the
  controller is shown.
- Keys avoid the display cutout's safe inset. On phones with a punch-hole
  camera on that edge in landscape, one column therefore overlaps the video
  slightly.
- The overlay sends packets through `HotkeyOverlay.KeySink`: Game passes
  `conn.sendKeyboardInput`, and the preview passes a logger.
- In portrait the overlay sets the stream view's layout params (width ×
  width/aspect, top or bottom) and restores Game's own params in landscape
  and picture-in-picture.

Upstream files touched (keep these when merging): `Game.java` (create the
overlay with the stream size, hide it in PiP, release keys in `onStop`, add
its modifiers in `getModifierState()`, `onExternalKeyUp` after a keyboard
key-up, `StreamRotation.requested()` instead of the fixed landscape in
`setPreferredOrientationForCurrentDisplay()`, `updateTouchOverlays()` (controller
in landscape, hotkeys in portrait; after a config change) and
`setOnscreenController()` (a preference listener creates / removes the
controller when ⚙ switches it), `LandscapeController.applyDefault()` before
reading the preferences), `PcView.java` (`applyDefault()` in `onCreate`), `res/xml/preferences.xml`
(the Hotkey Overlay category). Strings are in `res/values/strings_fork.xml`.

Tested on the S24-sized emulator (preview activity): landscape and portrait
sections (stacking in portrait), keys above/below, board drag between
sections, into an empty section and out of Hidden, reorder with
auto-scroll, keys per row, the dialog's section picker, arrange-mode key
drop / section drag / off-screen count, version 1 and 2 file conversion.
