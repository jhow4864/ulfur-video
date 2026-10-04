package com.jamh.videoplayer;

import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;

import java.lang.reflect.Field;
import java.util.function.BooleanSupplier;

/** Helpers for activities that finish setting up on a worker thread (async intent resolution). */
final class TestUtil {

    private TestUtil() {
    }

    static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    /** Runs the main looper until {@code done} is true (or the timeout passes); returns the final state. */
    static boolean await(BooleanSupplier done, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            idle();
            if (done.getAsBoolean()) return true;
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        idle();
        return done.getAsBoolean();
    }

    static Object field(Object target, String name) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** PlayerActivity resolves the intent on a worker thread before creating the player. */
    static void awaitPlayer(PlayerActivity a) {
        if (!await(() -> field(a, "player") != null, 5000)) {
            throw new AssertionError("player was not created");
        }
    }
}
