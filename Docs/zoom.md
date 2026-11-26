# Stream zoom (pinch and pan)

Fork feature: zoom into the stream with two fingers and move it around,
like zooming a video in a video player. Settings → Input → **Pinch to zoom
the stream** (on by default). Needs Android 8+.

## Using it

- **Pinch out**: zoom in (up to 5x) around your fingers. **Pinch in**: zoom
  out. Below about 1.08x it snaps back to 1x.
- **Two-finger drag while zoomed in**: move the zoomed stream.
- The zoomed stream uses the **whole screen**: it grows into the black
  bars and the key area, with the keys staying on top. Along a direction
  where it is bigger than the screen it always fills the screen (no black
  gaps); where it is smaller (e.g. its height in portrait at 2x) you can
  drag it anywhere on screen. At 1x it is back in its own box.
- Rotating the phone, or showing/hiding the hotkeys, keeps the zoom and
  the spot you're looking at. Picture-in-picture resets it to 1x.

What still goes to the PC:

| Gesture | At 1x | Zoomed in |
| --- | --- | --- |
| One finger (tap, move, drag) | as before | as before; in absolute-touch mode it clicks the point under your finger (off the video: its nearest edge) |
| Two-finger tap (right click in trackpad mode) | as before | as before |
| Two fingers moving the same way | scroll, as before | moves the zoomed stream (no scroll) |
| Two fingers pinching | zoom | zoom |
| Three-finger tap (keyboard) | as before | as before |

In trackpad mode the cursor speed doesn't change with the zoom.

## How it works

`com.limelight.zoom.StreamZoom` (fork-only):

- The zoom is `scaleX/Y` with a pivot on the `StreamView` itself, and no
  translation, so Game's `streamView.getX()` offsets stay right. Since
  Android 7, a SurfaceView's video follows its view transform in sync, so
  no decoder or surface size changes. The video isn't clipped to the view's
  box: it shows anywhere on the (transparent) Game window, under the
  hotkeys and overlays.
- Viewport = the stream's parent (the whole window). The pivot is clamped
  per axis via the on-screen shift `pivot × (1 − scale)`: if the scaled
  video is bigger than the screen it must cover it, otherwise stay inside
  it. Re-clamped when the stream or the window is resized (rotation,
  keys shown/hidden); a resize keeps the pivot at the same spot of the video.
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
  screen coordinates. `StreamZoom.toVideoX/Y` maps a point relative to the
  stream's position (`x − getX()`, which may be outside the box) to the
  video point shown there. It runs before the usual clamp to 0..width, so
  a touch off the video hits its nearest edge. Trackpad deltas aren't mapped.
- The portrait key area's absolute-touch blocker (`HotkeyOverlay`) lets a
  touch through when it lands on the zoomed video (`StreamZoom.isOverVideo`).

Upstream files touched (keep when merging):
- `Game.java`: create `streamZoom` after the hotkey overlay; call
  `streamZoom.onTouchEvent()` at the top of the finger branch of
  `handleMotionEvent()` (after the on-screen-controller config check);
  `reset()` when entering PiP; `toVideoX/Y` before the clamp in
  `getStreamViewRelativeNormalizedXY()` and `updateMousePosition()` for
  events that don't come from the stream view itself.
- `AbsoluteTouchContext.updatePosition()`: `toVideoX/Y` before the clamp.
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

Tested on the S24 AVD: pinch in/out in portrait and landscape, the video
growing into the bars / key area, dragging it around the screen, clamping
at the screen edges, zoom kept across rotation, snap back to 1x, the tap
mapping (also on video outside its box), two-finger scroll and two-finger
tap at 1x still going to Moonlight.
