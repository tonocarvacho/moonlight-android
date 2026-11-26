# Stream zoom (pinch and pan)

Fork feature: zoom into the stream with two fingers and move it around,
like zooming a video in a video player. Settings → Input → **Pinch to zoom
the stream** (on by default). Needs Android 8+.

## Using it

- **Pinch out**: zoom in (up to 5x) around your fingers. **Pinch in**: zoom
  out. Below about 1.08x it snaps back to 1x.
- **Two-finger drag while zoomed in**: move the zoomed stream. It stops at
  the edges, so the video always fills its box.
- The zoom stays inside the stream's box: the 16:9 area in landscape, the
  stream half in portrait. The hotkeys and bars around it don't change.
- Rotating the phone, or showing/hiding the hotkeys, keeps the zoom and
  the spot you're looking at. Picture-in-picture resets it to 1x.

What still goes to the PC:

| Gesture | At 1x | Zoomed in |
| --- | --- | --- |
| One finger (tap, move, drag) | as before | as before; in absolute-touch mode it clicks the point under your finger |
| Two-finger tap (right click in trackpad mode) | as before | as before |
| Two fingers moving the same way | scroll, as before | moves the zoomed stream (no scroll) |
| Two fingers pinching | zoom | zoom |
| Three-finger tap (keyboard) | as before | as before |

In trackpad mode the cursor speed doesn't change with the zoom.

## How it works

`com.limelight.zoom.StreamZoom` (fork-only):

- The zoom is `scaleX/Y` with a pivot on the `StreamView` itself. Since
  Android 7, a SurfaceView's video follows its view transform in sync, so
  no decoder or surface size changes. The pivot is clamped to the view's
  box, so the zoomed video always covers it.
- A black **mask** view sits right above the stream (below the hotkeys,
  on-screen controls and overlays). It covers everything outside the
  stream's box while zoomed. Game's window is transparent, so without it
  the zoomed video would show through around the box.
- Gesture detection: when a second finger goes down, the moves after it
  are held back from Moonlight's touch handling until the gesture is clear:
  - the finger distance changes by more than 1.5× touch slop: zoom,
  - the midpoint moves more than touch slop: pan if zoomed in, otherwise
    Moonlight's scroll. Moonlight catches up on that move, so no scroll is lost,
  - fingers lift first (tap) or a third finger lands: Moonlight's.
  When zoom/pan takes over, Moonlight's touch contexts are cancelled (no
  stuck buttons), and the rest of the gesture is consumed until all fingers
  are up. The video point under the fingers stays under them.
- Absolute touch: Game's touch events come to the background view in
  screen coordinates. `StreamZoom.toVideoX/Y` maps a point in the stream's
  unzoomed box to the video point shown there. It is used after the usual
  clamp to the box, so touches outside the box hit the visible edge.
  Trackpad deltas aren't mapped.

Upstream files touched (keep when merging):
- `Game.java`: create `streamZoom` after the hotkey overlay; call
  `streamZoom.onTouchEvent()` at the top of the finger branch of
  `handleMotionEvent()` (after the on-screen-controller config check);
  `reset()` when entering PiP; `toVideoX/Y` in
  `getStreamViewRelativeNormalizedXY()` and `updateMousePosition()` for
  events that don't come from the stream view itself.
- `AbsoluteTouchContext.updatePosition()`: `toVideoX/Y` after the clamp.
- `res/xml/preferences.xml`: `checkbox_stream_zoom` in the input category.
  The strings are in `res/values/strings_fork.xml`.

## Testing without a PC

The debug-only `HotkeyPreviewActivity` (see [hotkeys.md](hotkeys.md)) has a
real SurfaceView with a labelled grid (A1–H6, red border) and the zoom
wired up like Game. A one-finger tap logs the video point it maps to.
`adb shell input` can't do multi-touch. On the emulator, use `adb root`
and `sendevent` on `/dev/input/event2` (`virtio_input_multi_touch_1`,
0–32767 on both axes, portrait orientation). Every contact needs
`ABS_MT_PRESSURE`, or it is ignored.

Tested on the S24 AVD: pinch in/out in portrait and landscape, pan,
clamping at the edges, zoom kept across rotation, snap back to 1x, the
tap mapping, two-finger scroll and two-finger tap at 1x still going to
Moonlight.
