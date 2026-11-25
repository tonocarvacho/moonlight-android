# Hotkey overlay

Fork feature: translucent desktop hotkeys on the left and right edges of the
stream, built for the Fedora/AwesomeWM/tmux/nvim setup in `~/.dotfiles`.
When the stream is letterboxed (16:9 video on the S24's wider screen) the
keys sit in the black bars. Otherwise they float over the video.

Settings → **Hotkey Overlay**: on/off (default on) and opacity (default 80%).

## Using it

- **Keys** send their shortcut while your finger is down. Holding a single
  shortcut, like an arrow key, auto-repeats on the PC.
- **Pages**: each side has a title with dots. Tap it for the next page, or
  swipe it left/right. The footer row (modifiers, Esc/Tab/Enter) stays on
  every page.
- **Hide a side**: hold its title. A slim tab stays at the edge; tap it to
  bring the side back. Pages and hidden sides are remembered.
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

## Default layout

`app/src/main/assets/hotkeys/default.json`, taken from `~/.dotfiles`:

| Side / page | Keys |
| --- | --- |
| Left · Desk (awesome) | Apps (rofi, Ctrl+Space), tags 1–4 (Super+U/I/O/P), tag 5 (Super+2*), Spotify (Super+Y), last/next window, fullscreen, close |
| Left · Move | move window to tag 1–4, master/swap, float, other screen, wider/thinner, layout, area screenshot |
| Left · Nav | arrows, PgUp/PgDn, Home/End, Backspace/Delete, Ctrl+C, Ctrl+D |
| Left footer | Super, Ctrl, Alt, Shift (sticky) |
| Right · Tmux | new/close tab (Alt+T/W), tabs 1–5 (prefix u/i/o/p/j), last tab (Alt+J), session picker (Alt+E), last session (Alt+K), files (Ctrl+P), yazi (Alt+B) |
| Right · Pane | sessions 1–5 (prefix 1–5), copy mode, split, select/break/kill pane, bare prefix (Alt+R) |
| Right · Vim | save, quit all (␣o), alt/prev buffer, half-page up/down, undo/redo, delete buffer, no highlight, `:` and `/` |
| Right footer | Esc, Tab, ⌨ keyboard, Enter |

\* rc.lua binds tags with `"#" .. i + 6`. Lua evaluates that as keycode
`i + 6`, so tag 5 is on Super+2 (X keycode 11), not Super+5.

## Custom layout without rebuilding

Put a `hotkeys.json` in the app's files folder. It replaces the built-in
layout on the next stream. If it has an error, a toast says what is wrong and
the built-in layout is used.

```sh
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
S24-sized AVD is 1080x2340 at 450 dpi).

### Format

```json
{
  "left":  { "accent": "#8be9fd", "pages": [ { "name": "Desk", "rows": [ [KEY, KEY], [KEY] ] } ],
             "footer": [ [KEY, KEY] ] },
  "right": { ... }
}
```

Each row's keys share the column width (optional `"weight"`, default 1).
The page area is as tall as the side's longest page. Both sides use the
same key height, which fits the side with the most rows (pages + footer)
to the screen. Eight rows per side fit well on the S24.

A KEY is one of:

| Key | Meaning |
| --- | --- |
| `{"label": "1", "hint": "tag", "keys": "Super+U"}` | One chord, held while touched. `hint` is optional (by default it shows the chord, e.g. `❖U`). Without a `label` the chord is the label. |
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

- `HotkeyLayout`: JSON model/parser, key-name → Windows VK table.
- `HostKeyboard`: sends key packets. Modifiers are reference-counted, so a
  sticky Super, a finger holding Super and a `Super+U` key give the host
  one press and one release.
- `HotkeyOverlay`: sticky-modifier state machine, sizing to the letterbox
  bars, display-cutout and gesture-exclusion insets, saved page/hidden state.
- `HotkeyColumn` / `HotkeyKeyView`: the views (custom-drawn, Dracula colours).
- Keys avoid the display cutout's safe inset. On phones with a punch-hole
  camera on that edge in landscape, one column therefore overlaps the video
  slightly.
- The overlay sends packets through `HotkeyOverlay.KeySink`: Game passes
  `conn.sendKeyboardInput`, and the preview passes a logger.

Upstream files touched (keep these when merging): `Game.java` (create the
overlay, hide it in PiP, release keys in `onStop`, add its modifiers in
`getModifierState()`, `onExternalKeyUp` after a keyboard key-up),
`res/xml/preferences.xml` (the Hotkey Overlay category). Strings are in
`res/values/strings_fork.xml`.

Tested on an emulator: packet order for chords, one-shot/locked modifiers,
sequences with a locked modifier, page switching, hiding, and saved state.
