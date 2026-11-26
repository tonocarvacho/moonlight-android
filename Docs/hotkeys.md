# Hotkey overlay

Fork feature: translucent desktop hotkeys over the stream, built for the
Fedora/AwesomeWM/tmux/nvim setup in `~/.dotfiles`. In landscape the keys sit
in the letterbox bars (16:9 video on the S24's wider screen); in portrait the
stream takes one half of the screen and the keys the other.

Settings → **Hotkey Overlay**: on/off (default on), opacity (default 80%),
**Screen rotation** (default "Turn with the phone"; also "Follow the phone's
auto-rotate setting" or "Landscape only"). Rotation needs the overlay on and
the on-screen controller off (upstream's controller is landscape-only).

## Using it

- **Keys** send their shortcut while your finger is down. Holding a single
  shortcut, like an arrow key, auto-repeats on the PC.
- **One list, filled bottom-up.** All buttons are one ordered list; each is
  shown or hidden. Shown buttons fill the **right column from the bottom
  up**, then the **left column**, then inner columns (over the video in
  landscape). Two keys per row; a *wide* key takes a whole row (a key alone
  before a wide one, or at the end, also fills its row).
- **⚙** (toolbar above the right column) opens the editor, **▾** hides all
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
keys fill the bottom half (3 columns on the S24, filled right, left, then
middle). The editor's "Portrait: keys above the stream" swaps the halves.
▾ hides the keys and centres the stream. In absolute-touch mode the empty
part of the key half ignores touches (no accidental clicks); in trackpad
mode (Moonlight's default) it works as a touchpad.

### Editor (⚙)

- **☑** shows/hides a button. Built-in extras start hidden: digits 0–9 and
  the old Move / Nav / Pane / Vim pages.
- **≡** drag to reorder. The top of the list is the bottom-right key. Each
  row's badge says where it lands now: `R1` right column, `L1` left, `R2`
  / `L2` inner ones, `Mid` (portrait), or `no room`.
- **+ New hotkey** / tap a row: label, small text, keys (e.g.
  `Ctrl+Shift+T`; chips insert modifiers and key names), wide, shown. The
  dialog checks the keys as you type ("Sends ^⇧T"). New keys go right after
  the last shown one. **Delete** removes a key; **Reset buttons to default**
  brings back the built-in list.
- Key height (30–72 dp, default 42), opacity, keys above/below the stream in
  portrait, reset column positions.
- **✥ Arrange on screen**: drag a key onto another to swap them, or above a
  column to put it on top of that column, or onto "Drop here to hide". Drag
  a column's ⠿ grip to move the whole column (saved per orientation;
  "Reset positions" puts them back). Keys don't send anything while
  arranging.

Everything is saved at once to `hotkeys.json` (below) and the overlay updates
live. Column positions, key height, the portrait swap and the hidden state
are in the `hotkey_overlay` shared preferences.

## Default layout

`app/src/main/assets/hotkeys/default.json`, taken from `~/.dotfiles`. The
shown keys reproduce the old two-sided look in landscape:

| Column (bottom → top) | Keys |
| --- | --- |
| Right | ⌨ kbd / ⏎ Enter, Esc / Tab, then tmux: files (Ctrl+P) / yazi (Alt+B), session picker (Alt+E) / last session (Alt+K), tab 5 (prefix j) / last tab (Alt+J), tabs 3/4, tabs 1/2 (prefix u/i/o/p), new/close tab (Alt+T/W) |
| Left | Alt / Shift, Super / Ctrl (sticky), fullscreen / close, last/next window, tag 5 (Super+2*) / Spotify (Super+Y), tags 3/4, tags 1/2 (Super+U/I/O/P), Apps (rofi, Ctrl+Space, wide) |
| Hidden | digits 0–9; Move: move window to tag 1–4, master/swap, float, other screen, wider/thinner, layout, area screenshot; Nav: arrows, PgUp/PgDn, Home/End, Backspace/Delete, Ctrl+C/D; Pane: sessions 1–5 (prefix 1–5), copy mode, split, select/break/kill pane, bare prefix (Alt+R); Vim: save, quit all (␣o), alt/prev buffer, half-page up/down, undo/redo, delete buffer, no highlight, `:` and `/` |

\* rc.lua binds tags with `"#" .. i + 6`. Lua evaluates that as keycode
`i + 6`, so tag 5 is on Super+2 (X keycode 11), not Super+5.

Built-in buttons added to `default.json` later appear at the end of an
existing user list (shown or hidden as in the default). Deleted built-ins
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

Use it to check a layout or to test overlay changes on an emulator (an
S24-sized AVD is 1080x2340 at 450 dpi; AVD `s24_hotkeys`). It turns with the
device like a real stream: `adb emu rotate` switches portrait/landscape.

### Format

```json
{
  "version": 2,
  "buttons": [
    {"id": "esc", "show": true, "label": "Esc", "hint": "", "keys": "Escape"},
    {"id": "wm_apps", "show": true, "label": "Apps", "hint": "rofi", "keys": "Ctrl+Space", "wide": true}
  ],
  "seen": ["esc", "wm_apps"]
}
```

Order = fill order (first = bottom-right). Every button needs a unique `id`;
`show` defaults to true, `wide` to false. `seen` is optional (default: the
ids in the file). Old version 1 files (`left`/`right` sides with `pages` and
a `footer`) still load: each side's footer and first page are shown
bottom-up, the other pages hidden.

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

- `HotkeyLayout`: JSON model (ordered `Button` list, `seen` ids), parser and
  writer, version 1 conversion, merge with new built-ins, key-name → Windows
  VK table.
- `HostKeyboard`: sends key packets. Modifiers are reference-counted, so a
  sticky Super, a finger holding Super and a `Super+U` key give the host
  one press and one release.
- `HotkeyOverlay`: sticky-modifier state machine; layout: column slots
  (landscape: letterbox bars then inward; portrait: even grid in the key
  half), the bottom-up fill, stream placement in portrait, display-cutout and
  gesture-exclusion insets; saves `hotkeys.json`; column offsets.
- `HotkeyColumn` (+ `Toolbar`, `Handle`) / `HotkeyKeyView`: the views
  (custom-drawn, Dracula colours).
- `HotkeyEditor`: the ⚙ dialog, its drag-to-reorder list and the hotkey
  dialog (views built in code, theme `HotkeyEditorTheme` in
  `res/values/styles_fork.xml`).
- `HotkeyArrangeLayer`: full-screen layer for arrange mode (key swap, column
  drag, hide drop zone).
- `StreamRotation`: the orientation Game requests (Settings → Screen
  rotation).
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
`setPreferredOrientationForCurrentDisplay()`), `res/xml/preferences.xml`
(the Hotkey Overlay category). Strings are in `res/values/strings_fork.xml`.

Tested on the S24-sized emulator (preview activity): landscape and portrait
fill, keys above/below, hide/show, sticky modifiers, editor reorder/toggle/new
hotkey, arrange-mode swap and column drag, version 1 file conversion.
