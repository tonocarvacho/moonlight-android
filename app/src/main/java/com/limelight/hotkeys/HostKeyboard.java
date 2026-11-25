package com.limelight.hotkeys;

import com.limelight.nvstream.input.KeyboardPacket;

/**
 * Sends overlay keys to the host. Modifiers are reference counted, so a
 * sticky Super, a finger holding Super and a "Super+U" key can overlap and
 * the host sees exactly one press and one release.
 */
final class HostKeyboard {
    private static final short KEY_PREFIX = (short) 0x8000;

    private final HotkeyOverlay.KeySink sink;
    private final int[] modCount = new int[HotkeyLayout.MODS.length];

    HostKeyboard(HotkeyOverlay.KeySink sink) {
        this.sink = sink;
    }

    /** Modifier bits currently held on the host by the overlay. */
    int heldMask() {
        int mask = 0;
        for (int i = 0; i < modCount.length; i++) {
            if (modCount[i] > 0) {
                mask |= HotkeyLayout.MODS[i];
            }
        }
        return mask;
    }

    void acquire(int mask) {
        for (int i = 0; i < modCount.length; i++) {
            if ((mask & HotkeyLayout.MODS[i]) != 0 && modCount[i]++ == 0) {
                send(HotkeyLayout.MOD_VK[i], true);
            }
        }
    }

    void release(int mask) {
        for (int i = modCount.length - 1; i >= 0; i--) {
            if ((mask & HotkeyLayout.MODS[i]) != 0 && modCount[i] > 0 && --modCount[i] == 0) {
                send(HotkeyLayout.MOD_VK[i], false);
            }
        }
    }

    void key(int vk, boolean down) {
        send(vk, down);
    }

    /** Down + up of one chord, on top of whatever is held. */
    void tap(HotkeyLayout.Combo combo) {
        acquire(combo.mods);
        send(combo.vk, true);
        send(combo.vk, false);
        release(combo.mods);
    }

    /**
     * Sends chords with no other modifier held (a tmux prefix must arrive as
     * plain Alt+R even while Shift is locked), then restores held modifiers.
     */
    void tapClean(HotkeyLayout.Combo[] steps) {
        int held = heldMask();
        int[] saved = modCount.clone();
        java.util.Arrays.fill(modCount, 0);
        sendMods(held, false);
        for (HotkeyLayout.Combo step : steps) {
            tap(step);
        }
        System.arraycopy(saved, 0, modCount, 0, saved.length);
        sendMods(held, true);
    }

    /** Releases every modifier the overlay holds (stream ending, overlay hidden). */
    void releaseAll() {
        int held = heldMask();
        java.util.Arrays.fill(modCount, 0);
        sendMods(held, false);
    }

    private void sendMods(int mask, boolean down) {
        for (int i = 0; i < modCount.length; i++) {
            if ((mask & HotkeyLayout.MODS[i]) != 0) {
                send(HotkeyLayout.MOD_VK[i], down);
            }
        }
    }

    private void send(int vk, boolean down) {
        sink.sendKey((short) (KEY_PREFIX | vk),
                down ? KeyboardPacket.KEY_DOWN : KeyboardPacket.KEY_UP,
                (byte) heldMask());
    }
}
