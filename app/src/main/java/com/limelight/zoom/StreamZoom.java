package com.limelight.zoom;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.preference.PreferenceManager;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;

/**
 * Two-finger pinch zoom and pan of the stream, like a video player
 * (Docs/zoom.md). The zoom is a scale about a pivot set on the stream view
 * itself, so the stream view's box (its layout bounds) is the viewport:
 * the zoomed video always fills it and is masked outside it.
 *
 * Gestures, once a second finger is down:
 * - the fingers move apart / together: zoom (and pan with them),
 * - both move the same way: pan when zoomed in; at 1x it is left to
 *   Moonlight (two-finger scroll),
 * - no movement (two-finger tap) or a third finger: left to Moonlight.
 * Until that is decided, moves are held back from Moonlight so a pinch
 * doesn't scroll the PC first. Pinching back to about 1x snaps to 1x.
 *
 * Absolute touch maps fingers through the zoom with {@link #toVideoX} /
 * {@link #toVideoY}; trackpad mode is unaffected (deltas keep their speed).
 */
public final class StreamZoom {
    public static final String PREF = "checkbox_stream_zoom";
    private static final float MAX_SCALE = 5f;
    private static final float SNAP_TO_ONE = 1.08f;

    private enum State {
        IDLE,      // fewer than two fingers
        PENDING,   // two fingers, gesture not decided yet
        ZOOMING,   // our gesture: consume everything until all fingers are up
        DONE,      // our gesture ended (a finger lifted): consume the rest
        PASS,      // Moonlight's gesture until all fingers are up
    }

    private final View stream;
    private final Mask mask;
    private final Runnable cancelTouches;
    private final float slop;

    private State state = State.IDLE;
    private int id0, id1;
    private float startFocusX, startFocusY, startSpan;
    private float lastFocusX, lastFocusY, lastSpan;

    private float scale = 1f;
    private float pivotX, pivotY;
    private ValueAnimator snap;

    /**
     * Returns null when turned off in settings or before Android 8 (SurfaceView
     * scaling and clipOutRect). cancelTouches cancels Moonlight's touch
     * contexts when a zoom gesture takes over.
     */
    public static StreamZoom create(Context context, View stream, Runnable cancelTouches) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                !PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF, true)) {
            return null;
        }
        return new StreamZoom(context, stream, cancelTouches);
    }

    private StreamZoom(Context context, View stream, Runnable cancelTouches) {
        this.stream = stream;
        this.cancelTouches = cancelTouches;
        this.slop = ViewConfiguration.get(context).getScaledTouchSlop();

        // The mask sits right above the stream: below the hotkeys, on-screen
        // controls and overlays, which are added after it.
        ViewGroup parent = (ViewGroup) stream.getParent();
        mask = new Mask(context);
        mask.setVisibility(View.GONE);
        parent.addView(mask, parent.indexOfChild(stream) + 1,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        stream.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int w = r - l, h = b - t, ow = or - ol, oh = ob - ot;
            if (ow > 0 && oh > 0 && (w != ow || h != oh)) {
                // Same spot of the video after a resize (rotation, keys shown/hidden)
                pivotX = pivotX * w / ow;
                pivotY = pivotY * h / oh;
            }
            clampPivot();
            apply();
            mask.setHole(l - mask.getLeft(), t - mask.getTop(), r - mask.getLeft(), b - mask.getTop());
        });
    }

    /** Back to 1x (picture-in-picture). */
    public void reset() {
        stopSnap();
        scale = 1f;
        apply();
    }

    /**
     * A finger event for the stream, in the coordinates of view (the
     * full-screen background touch view). Returns true if it was consumed.
     */
    public boolean onTouchEvent(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                state = State.IDLE;
                return false;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (state == State.ZOOMING || state == State.DONE) {
                    return true;
                }
                if (state == State.IDLE && event.getPointerCount() == 2) {
                    id0 = event.getPointerId(0);
                    id1 = event.getPointerId(1);
                    startSpan = span(event, 0, 1);
                    startFocusX = (event.getX(0) + event.getX(1)) / 2;
                    startFocusY = (event.getY(0) + event.getY(1)) / 2;
                    state = State.PENDING;
                }
                else {
                    // A third finger (keyboard tap) is Moonlight's
                    state = State.PASS;
                }
                return false;

            case MotionEvent.ACTION_MOVE:
                if (state == State.ZOOMING) {
                    if (!track(view, event)) {
                        endGesture();
                    }
                    return true;
                }
                if (state == State.DONE) {
                    return true;
                }
                if (state == State.PENDING) {
                    int i0 = event.findPointerIndex(id0), i1 = event.findPointerIndex(id1);
                    if (i0 < 0 || i1 < 0) {
                        state = State.PASS;
                        return false;
                    }
                    float focusX = (event.getX(i0) + event.getX(i1)) / 2;
                    float focusY = (event.getY(i0) + event.getY(i1)) / 2;
                    boolean pinched = Math.abs(span(event, i0, i1) - startSpan) > slop * 1.5f;
                    boolean moved = Math.hypot(focusX - startFocusX, focusY - startFocusY) > slop;
                    if (pinched || (moved && scale > 1f)) {
                        startGesture(view, event);
                        return true;
                    }
                    if (moved) {
                        // Two-finger scroll at 1x; Moonlight catches up on this move
                        state = State.PASS;
                        return false;
                    }
                    // Not decided yet: don't let Moonlight scroll
                    return true;
                }
                return false;

            case MotionEvent.ACTION_POINTER_UP:
                if (state == State.ZOOMING) {
                    int id = event.getPointerId(event.getActionIndex());
                    if (id == id0 || id == id1) {
                        endGesture();
                    }
                    return true;
                }
                if (state == State.DONE) {
                    return true;
                }
                if (state == State.PENDING) {
                    // Two-finger tap (right click in trackpad mode)
                    state = State.IDLE;
                }
                return false;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean ours = state == State.ZOOMING || state == State.DONE;
                if (state == State.ZOOMING) {
                    endGesture();
                }
                state = State.IDLE;
                return ours;

            default:
                return state == State.ZOOMING || state == State.DONE;
        }
    }

    private void startGesture(View view, MotionEvent event) {
        stopSnap();
        cancelTouches.run();
        state = State.ZOOMING;
        // Start from the touch-down positions, so the video stays under the fingers
        lastSpan = startSpan;
        lastFocusX = startFocusX;
        lastFocusY = startFocusY;
        track(view, event);
    }

    /** Follows the two fingers; false when one of them is gone. */
    private boolean track(View view, MotionEvent event) {
        int i0 = event.findPointerIndex(id0), i1 = event.findPointerIndex(id1);
        if (i0 < 0 || i1 < 0) {
            return false;
        }
        float span = span(event, i0, i1);
        float focusX = (event.getX(i0) + event.getX(i1)) / 2;
        float focusY = (event.getY(i0) + event.getY(i1)) / 2;

        // The stream box's origin in view's coordinates
        float originX = stream.getLeft() - view.getLeft();
        float originY = stream.getTop() - view.getTop();

        float newScale = scale;
        if (lastSpan > 0 && span > 0) {
            newScale = Math.max(1f, Math.min(MAX_SCALE, scale * span / lastSpan));
        }

        // Keep the video point that was under the last focus under the new one:
        // screen = origin + pivot + (point - pivot) * scale
        float videoX = pivotX + (lastFocusX - originX - pivotX) / scale;
        float videoY = pivotY + (lastFocusY - originY - pivotY) / scale;
        if (newScale > 1f) {
            pivotX = (focusX - originX - videoX * newScale) / (1f - newScale);
            pivotY = (focusY - originY - videoY * newScale) / (1f - newScale);
        }
        scale = newScale;
        clampPivot();
        apply();

        lastSpan = span;
        lastFocusX = focusX;
        lastFocusY = focusY;
        return true;
    }

    private void endGesture() {
        state = State.DONE;
        if (scale > 1f && scale < SNAP_TO_ONE) {
            snap = ValueAnimator.ofFloat(scale, 1f);
            snap.setDuration(150);
            snap.setInterpolator(new DecelerateInterpolator());
            snap.addUpdateListener(a -> {
                scale = (float) a.getAnimatedValue();
                apply();
            });
            snap.start();
        }
    }

    private void stopSnap() {
        if (snap != null) {
            snap.cancel();
            snap = null;
        }
    }

    /** A pivot inside the box keeps the zoomed video covering all of it. */
    private void clampPivot() {
        pivotX = Math.max(0, Math.min(stream.getWidth(), pivotX));
        pivotY = Math.max(0, Math.min(stream.getHeight(), pivotY));
    }

    private void apply() {
        stream.setPivotX(pivotX);
        stream.setPivotY(pivotY);
        stream.setScaleX(scale);
        stream.setScaleY(scale);
        mask.setVisibility(scale > 1f ? View.VISIBLE : View.GONE);
    }

    private static float span(MotionEvent event, int i0, int i1) {
        return (float) Math.hypot(event.getX(i0) - event.getX(i1), event.getY(i0) - event.getY(i1));
    }

    /**
     * Maps x in the stream view's unzoomed box (0..width, already clamped to
     * it) to the video x shown there, through the view's scale and pivot.
     */
    public static float toVideoX(View stream, float x) {
        float s = stream.getScaleX();
        return s == 1f ? x : stream.getPivotX() + (x - stream.getPivotX() - stream.getTranslationX()) / s;
    }

    /** Like {@link #toVideoX}, for y. */
    public static float toVideoY(View stream, float y) {
        float s = stream.getScaleY();
        return s == 1f ? y : stream.getPivotY() + (y - stream.getPivotY() - stream.getTranslationY()) / s;
    }

    /** Black outside the stream box, so the zoomed video stays inside it. Takes no touches. */
    @SuppressLint("ViewConstructor")
    private static final class Mask extends View {
        private final Rect hole = new Rect();

        Mask(Context context) {
            super(context);
            setWillNotDraw(false);
        }

        void setHole(int l, int t, int r, int b) {
            hole.set(l, t, r, b);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.save();
            canvas.clipOutRect(hole);
            canvas.drawColor(Color.BLACK);
            canvas.restore();
        }
    }
}
