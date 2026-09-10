/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Adversarial tests for the partial-redraw bookkeeping in {@link KeyboardView}:
 *
 *  - {@code invalidateKey(Key)} must add the key to {@code mInvalidatedKeys} (the set that
 *    drives which keys are re-rendered, and the union that drives the partial offscreen
 *    blit), must dedupe by identity, and must be a no-op for null.
 *  - {@code mInvalidateAllKeys} must short-circuit {@code invalidateKey} (no partial work).
 *  - The dirty-rect accumulator must be reset between frames, and only when the keyboard
 *    is actually attached.
 *
 * The {@link android.view.View} superclass cannot be constructed on the JVM-only test
 * classpath (its constructor reads attributes via {@code Context.obtainStyledAttributes}
 * which returns {@code null} on the stub). We use {@code sun.misc.Unsafe} to allocate the
 * {@code KeyboardView} without running its constructor and inject the minimum state needed
 * for the bookkeeping methods under test.
 */
package rkr.simplekeyboard.inputmethod.keyboard;

import android.graphics.Canvas;
import android.graphics.Rect;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashSet;

import rkr.simplekeyboard.inputmethod.keyboard.internal.KeyboardParams;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(JUnit4.class)
public class KeyboardViewPartialRedrawAdversarialTest {

    private static Object theUnsafe() throws Exception {
        final Class<?> uc = Class.forName("sun.misc.Unsafe");
        final Field f = uc.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return f.get(null);
    }

    @SuppressWarnings("restriction")
    private static <T> T allocate(final Class<T> cls) {
        try {
            final Class<?> uc = Class.forName("sun.misc.Unsafe");
            final Object u = theUnsafe();
            final Method m = uc.getMethod("allocateInstance", Class.class);
            return cls.cast(m.invoke(u, cls));
        } catch (Throwable t) {
            throw new RuntimeException("allocate " + cls, t);
        }
    }

    private static void setField(final Object target, final String name, final Object value) {
        try {
            Class<?> c = target.getClass();
            while (c != null) {
                try {
                    final Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    // Use Unsafe.putObject for final fields: in JDK 17+ Field.set silently
                    // no-ops on final fields (the JVM has cached the original reference for
                    // JIT purposes). Unsafe bypasses that cache.
                    final int mods = f.getModifiers();
                    if (java.lang.reflect.Modifier.isFinal(mods)) {
                        final Class<?> uc = Class.forName("sun.misc.Unsafe");
                        final Field uf = uc.getDeclaredField("theUnsafe");
                        uf.setAccessible(true);
                        final Object u = uf.get(null);
                        final Method off = uc.getMethod("objectFieldOffset", Field.class);
                        final Method put = uc.getMethod("putObject",
                                Object.class, long.class, Object.class);
                        final long offset = (long) off.invoke(u, f);
                        put.invoke(u, target, offset, value);
                        return;
                    }
                    f.set(target, value);
                    return;
                } catch (NoSuchFieldException ignored) {
                    c = c.getSuperclass();
                }
            }
            throw new NoSuchFieldException(name);
        } catch (Throwable t) {
            throw new RuntimeException("inject " + name, t);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T getField(final Object target, final String name) {
        try {
            Class<?> c = target.getClass();
            while (c != null) {
                try {
                    final Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    final int mods = f.getModifiers();
                    if (java.lang.reflect.Modifier.isFinal(mods)) {
                        // Read the live reference via Unsafe to bypass the JIT's cached value.
                        final Class<?> uc = Class.forName("sun.misc.Unsafe");
                        final Field uf = uc.getDeclaredField("theUnsafe");
                        uf.setAccessible(true);
                        final Object u = uf.get(null);
                        final Method off = uc.getMethod("objectFieldOffset", Field.class);
                        final Method get = uc.getMethod("getObject",
                                Object.class, long.class);
                        final long offset = (long) off.invoke(u, f);
                        return (T) get.invoke(u, target, offset);
                    }
                    return (T) f.get(target);
                } catch (NoSuchFieldException ignored) {
                    c = c.getSuperclass();
                }
            }
            throw new NoSuchFieldException(name);
        } catch (Throwable t) {
            throw new RuntimeException("read " + name, t);
        }
    }

    private KeyboardView view;
    private Key k1;
    private Key k2;

    @Before
    public void setUp() {
        // Allocate a KeyboardView without running its constructor. The View superclass is left
        // in an uninitialized state but that's OK for the bookkeeping methods under test, which
        // only touch HashSet/Rect/Paint fields we inject here.
        view = allocate(KeyboardView.class);
        // Inject fresh field state the constructor would have created.
        setField(view, "mInvalidatedKeys", new HashSet<>());
        setField(view, "mClipRect", new Rect());
        setField(view, "mDirtyRect", new Rect());
        setField(view, "mSrcRect", new Rect());
        setField(view, "mDstRect", new Rect());
        setField(view, "mDirtyRectValid", Boolean.FALSE);
        // Inject the key-background padding rect with all-zero values (no expansion).
        setField(view, "mKeyBackgroundPadding", new Rect(0, 0, 0, 0));
        // Real synthetic keys with known geometry so we can assert dirty-rect bounds.
        k1 = makeKey("a", 0, 0, 40, 50);
        k2 = makeKey("b", 50, 60, 40, 50);
    }

    private static Key makeKey(final String label, final int x, final int y, final int w,
            final int h) {
        // Use the public (label, iconId, code, outputText, hintLabel, labelFlags,
        // backgroundType, x, y, w, h, lPad, rPad, tPad, bPad) constructor.
        final int bgType = 0; // normal key background
        final Key k = new Key(label, 0 /* iconId */, 0 /* code */, label /* outputText */,
                null /* hintLabel */, 0 /* labelFlags */, bgType,
                x, y, w, h, 0f, 0f, 0f, 0f);
        return k;
    }

    // ---------------------------------------------------------------------------------------------
    // B.1 - Pressing one key adds exactly that key to mInvalidatedKeys
    // ---------------------------------------------------------------------------------------------

    @Test
    public void invalidateKeyAddsExactlyThatKey() {
        view.invalidateKey(k1);
        final Collection<?> invalidated = getField(view, "mInvalidatedKeys");
        assertEquals(1, invalidated.size());
        assertTrue("k1 must be the only invalidated key", invalidated.contains(k1));
    }

    @Test
    public void invalidateKeyForNeighbouringKeysAccumulatesBoth() {
        view.invalidateKey(k1);
        view.invalidateKey(k2);
        final Collection<?> invalidated = getField(view, "mInvalidatedKeys");
        assertEquals(2, invalidated.size());
        assertTrue(invalidated.contains(k1));
        assertTrue(invalidated.contains(k2));
    }

    @Test
    public void invalidatingSameKeyTwiceDoesNotDuplicateSet() {
        view.invalidateKey(k1);
        view.invalidateKey(k1);
        final Collection<?> invalidated = getField(view, "mInvalidatedKeys");
        assertEquals("HashSet must dedup", 1, invalidated.size());
    }

    /**
     * Adversarial: invalidateKey accumulates the SAME key multiple times without ever letting
     * {@code drawKeys} run. When the set-based redraw finally executes, that single key must
     * appear exactly once in the bookkeeping set (i.e., the Set must dedupe by identity), so
     * the dirty rect union isn't recomputed multiple times and the per-key draw path isn't
     * run multiple times for the same key.
     *
     * <p>The optimization relies on HashSet/LinkedHashSet-style identity. A buggy
     * implementation that switches to a List would silently drop this property and cause
     * excess drawing. This test pins the dedup behaviour via the {@code mInvalidatedKeys}
     * collection size after repeated invalidation calls.
     */
    @Test
    public void invalidateKeyIsIdempotentAcrossManyCalls() {
        for (int i = 0; i < 50; i++) {
            view.invalidateKey(k1);
        }
        final Collection<?> invalidated = getField(view, "mInvalidatedKeys");
        assertEquals("invalidateKey must dedupe by identity; saw " + invalidated.size(),
                1, invalidated.size());
    }

    /**
     * Adversarial: invalidateKey does not corrupt the dirty-rect accumulator when called
     * before any frame has been drawn (mDirtyRectValid==false is the contract for "dirty
     * rect not yet armed"). After a single invalidation the set must contain the key but
     * the dirty-rect validity flag must remain FALSE: invalidation just enqueues, it does
     * not paint.
     */
    @Test
    public void invalidateKeyDoesNotArmDirtyRect() {
        view.invalidateKey(k1);
        final Collection<?> invalidated = getField(view, "mInvalidatedKeys");
        assertEquals(1, invalidated.size());
        final Boolean valid = getField(view, "mDirtyRectValid");
        assertFalse("invalidateKey must NOT mark the dirty-rect accumulator as valid "
                + "(that's the draw path's job)", valid);
    }

    // ---------------------------------------------------------------------------------------------
    // B.3 - mInvalidateAllKeys short-circuits invalidateKey
    // ---------------------------------------------------------------------------------------------

    @Test
    public void invalidateAllKeysShortCircuitsInvalidateKey() {
        view.invalidateAllKeys();
        // After invalidateAllKeys, mInvalidateAllKeys=true. invalidateKey must early-return
        // (per the implementation: "if (mInvalidateAllKeys || key == null) return;").
        view.invalidateKey(k1);
        final Collection<?> invalidated = getField(view, "mInvalidatedKeys");
        assertTrue("invalidateKey must be a no-op when invalidateAllKeys is set",
                invalidated.isEmpty());
    }

    @Test
    public void invalidateAllKeysClearsExistingSet() {
        view.invalidateKey(k1);
        view.invalidateAllKeys();
        final Collection<?> invalidated = getField(view, "mInvalidatedKeys");
        assertTrue("invalidateAllKeys must clear the per-key set", invalidated.isEmpty());
    }

    // ---------------------------------------------------------------------------------------------
    // B.4 - mDirtyRect is reset between frames
    // ---------------------------------------------------------------------------------------------

    /**
     * The dirty accumulator is reset at the start of {@code onDrawKeyboard} and again at the
     * end of the same method — but only when the keyboard is non-null. With a null keyboard
     * the method early-returns without resetting; document that contract.
     */
    @Test
    public void mDirtyRectIsResetBetweenFramesWhenKeyboardAttached() throws Exception {
        // Assign Rect public fields directly (Rect.set is not mocked).
        final Rect injected = getField(view, "mDirtyRect");
        injected.left = 0; injected.top = 0; injected.right = 100; injected.bottom = 100;
        setField(view, "mDirtyRectValid", Boolean.TRUE);
        final Keyboard kb = makeEmptyKeyboard();
        setField(view, "mKeyboard", kb);
        final Method m = KeyboardView.class.getDeclaredMethod("onDrawKeyboard", Canvas.class);
        m.setAccessible(true);
        m.invoke(view, new Canvas());
        final Boolean valid = getField(view, "mDirtyRectValid");
        assertFalse("dirty-rect accumulator must be reset by the end of a successful frame",
                valid);
    }

    /**
     * Contract pin: with {@code mKeyboard == null} {@code onDrawKeyboard} returns BEFORE the
     * reset, leaving the previous frame's accumulator state untouched. This is the desired
     * behavior — there is nothing to draw — but we pin it explicitly so a future refactor
     * cannot accidentally introduce off-frame bookkeeping drift.
     */
    @Test
    public void mDirtyRectUnchangedWhenNoKeyboardAttached() throws Exception {
        // mDirtyRect is a final Rect allocated in the field initializer. Unsafe.allocateInstance
        // doesn't run the initializer, so we mutate the Rect's contents in place. The android.jar
        // stub marks Rect.set() as "not mocked", so we assign the public fields directly.
        final Rect injected = getField(view, "mDirtyRect");
        injected.left = 0;
        injected.top = 0;
        injected.right = 100;
        injected.bottom = 100;
        setField(view, "mDirtyRectValid", Boolean.TRUE);
        // mKeyboard stays null.
        final Method m = KeyboardView.class.getDeclaredMethod("onDrawKeyboard", Canvas.class);
        m.setAccessible(true);
        // Pass a real Canvas() instance so drawAllKeys/drawKeys don't NPE on canvas=null. Even
        // though mKeyboard is null, onDrawKeyboard early-returns BEFORE reaching drawKeys.
        m.invoke(view, new Canvas());
        final Boolean valid = getField(view, "mDirtyRectValid");
        assertTrue("early return must not reset accumulator", valid);
        final Rect dirty = getField(view, "mDirtyRect");
        assertEquals("dirty rect must be untouched", 100, dirty.right);
    }

    /** Build a Keyboard whose getSortedKeys() returns an empty list, with no real drawables. */
    private static Keyboard makeEmptyKeyboard() {
        final KeyboardParams params = new KeyboardParams();
        // Avoid ArithmeticException in ProximityInfo: gridWidth/gridHeight must be > 0.
        params.mGridWidth = 1;
        params.mGridHeight = 1;
        // Override getSortedKeys() to return an empty list so the drawKeys path runs but
        // iterates zero keys.
        return new Keyboard(params) {
            @Override
            public java.util.List<Key> getSortedKeys() {
                return java.util.Collections.emptyList();
            }
        };
    }

    /**
     * Drawing on an empty (no-keys) keyboard must leave both the per-key set AND the
     * dirty-rect accumulator cleared. To force the {@code drawAllKeys} path on an
     * empty keyboard we deliberately leave {@code mInvalidatedKeys} empty: the implementation
     * treats "empty dirty set" as "redraw every key", which on a zero-key keyboard is a
     * no-op iteration followed by the bookkeeping reset.
     */
    @Test
    public void drawingOnEmptyKeyboardClearsAccumulator() throws Exception {
        final Rect dirty = getField(view, "mDirtyRect");
        dirty.left = 10; dirty.top = 10; dirty.right = 50; dirty.bottom = 50;
        setField(view, "mDirtyRectValid", Boolean.TRUE);
        setField(view, "mInvalidateAllKeys", Boolean.FALSE);
        // mInvalidatedKeys starts empty in setUp -> drawKeys takes the "drawAllKeys" branch.
        setField(view, "mKeyboard", makeEmptyKeyboard());
        final Method m = KeyboardView.class.getDeclaredMethod("onDrawKeyboard", Canvas.class);
        m.setAccessible(true);
        m.invoke(view, new Canvas());
        assertTrue("per-key set must remain empty after the frame",
                ((Collection<?>) getField(view, "mInvalidatedKeys")).isEmpty());
        assertFalse("dirty-rect accumulator must reset", getField(view, "mDirtyRectValid"));
    }

    /**
     * Adversarial: invalidateKey with a null key MUST NOT throw. The implementation guards
     * with {@code if (key == null) return;}. If a refactor removes that guard, this test
     * surfaces the NullPointerException that would otherwise happen deep in the draw path.
     */
    @Test
    public void invalidateKeyNullDoesNotThrow() {
        try {
            view.invalidateKey(null);
        } catch (Throwable t) {
            fail("invalidateKey(null) must be a no-op, got: " + t);
        }
        // And the bookkeeping must not have grown.
        final Collection<?> invalidated = getField(view, "mInvalidatedKeys");
        assertTrue("null key must not be added to the invalidated set",
                invalidated.isEmpty());
    }
}
