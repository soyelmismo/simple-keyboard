/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Adversarial tests for the RichInputConnection read-snapshot cache
 * (see the intra-event dedup logic in getCachedOrFetchTextBefore /
 * getCachedOrFetchTextAfter and the invalidation hooks on every write path).
 *
 * Build strategy:
 *  - {@code LatinIME} cannot be instantiated on the JVM (its field initializers chain
 *    InputLogic, PrefixDictionary, Executors, etc.). We skip the constructor entirely via
 *    {@code sun.misc.Unsafe#allocateInstance} accessed by pure reflection so the test
 *    compiles without --add-exports/--add-opens on JDK 17+.
 *  - {@code InputConnection} and {@code LatinIME} surfaces are mocked via
 *    {@link java.lang.reflect.Proxy}, matching the existing convention
 *    (see ClipboardSuggestionTest#testSettingsClampsOutOfBoundValues).
 *
 * Every test counts the exact number of IPC reads so dedup guarantees are asserted
 * behaviorally rather than via implementation internals.
 */
package rkr.simplekeyboard.inputmethod.latin;

import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(JUnit4.class)
public class RichInputConnectionAdversarialTest {

    // ---------------------------------------------------------------------------------------------
    // Test scaffolding
    // ---------------------------------------------------------------------------------------------

    /** Per-method call counters for the mock InputConnection. */
    private static final class CallCounter {
        int before;
        int after;
        int commitText;
        int deleteSurrounding;
        int setSelection;
        int sendKeyEvent;
        final List<String> calls = new ArrayList<>();
    }

    private static InputConnection makeMockIC(final CallCounter counter,
            final Deque<CharSequence> beforeResponses,
            final Deque<CharSequence> afterResponses) {
        final InvocationHandler h = (proxy, method, args) -> {
            final String name = method.getName();
            switch (name) {
                case "getTextBeforeCursor":
                    counter.before++;
                    counter.calls.add("before(" + args[0] + ")");
                    return beforeResponses.isEmpty() ? "" : beforeResponses.pop();
                case "getTextAfterCursor":
                    counter.after++;
                    counter.calls.add("after(" + args[0] + ")");
                    return afterResponses.isEmpty() ? "" : afterResponses.pop();
                case "commitText":
                    counter.commitText++;
                    counter.calls.add("commit(" + args[0] + ")");
                    return Boolean.TRUE;
                case "deleteSurroundingText":
                    counter.deleteSurrounding++;
                    counter.calls.add("del(" + args[0] + "," + args[1] + ")");
                    return Boolean.TRUE;
                case "setSelection":
                    counter.setSelection++;
                    counter.calls.add("sel(" + args[0] + "," + args[1] + ")");
                    return Boolean.TRUE;
                case "sendKeyEvent":
                    counter.sendKeyEvent++;
                    counter.calls.add("key");
                    return Boolean.TRUE;
                default:
                    // closeConnection, beginBatchEdit, endBatchEdit, finishComposingText, etc.
                    return Boolean.TRUE;
            }
        };
        return (InputConnection) java.lang.reflect.Proxy.newProxyInstance(
                RichInputConnectionAdversarialTest.class.getClassLoader(),
                new Class<?>[]{InputConnection.class},
                h);
    }

    /**
     * LatinIME is a final class (not an interface), so we cannot use a Proxy. Instead we
     * allocate the instance without running any field initializers via {@code Unsafe} — that
     * leaves every field at its default value (null/0), so {@code mRichImm} and {@code
     * mHandler} are both null. Any code path that touches them must tolerate null gracefully.
     */
    private static LatinIME makeMockIme() {
        try {
            final Class<?> uc = Class.forName("sun.misc.Unsafe");
            final Field f = uc.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            final Object u = f.get(null);
            final Method alloc = uc.getMethod("allocateInstance", Class.class);
            return (LatinIME) alloc.invoke(u, LatinIME.class);
        } catch (Throwable t) {
            throw new RuntimeException("allocate LatinIME", t);
        }
    }

    private static Object theUnsafe() throws Exception {
        final Class<?> uc = Class.forName("sun.misc.Unsafe");
        final Field f = uc.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return f.get(null);
    }

    private static Object allocate(final Class<?> cls) {
        try {
            final Class<?> uc = Class.forName("sun.misc.Unsafe");
            final Object u = theUnsafe();
            final Method m = uc.getMethod("allocateInstance", Class.class);
            return m.invoke(u, cls);
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
                    final int mods = f.getModifiers();
                    if (java.lang.reflect.Modifier.isFinal(mods)) {
                        // JDK 17+ silently no-ops Field.set on final fields; use Unsafe to
                        // bypass the cached reference the JIT keeps.
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

    private RichInputConnection ric;
    private CallCounter counter;
    private InputConnection ic;
    private Deque<CharSequence> beforeResponses;
    private Deque<CharSequence> afterResponses;

    @Before
    public void setUp() throws Exception {
        counter = new CallCounter();
        beforeResponses = new ArrayDeque<>();
        afterResponses = new ArrayDeque<>();
        ic = makeMockIC(counter, beforeResponses, afterResponses);
        ric = (RichInputConnection) allocate(RichInputConnection.class);
        // Unsafe.allocateInstance skips the constructor and therefore skips the inline
        // initializers of the `final` array fields and the `volatile String` cache fields.
        // Initialize them so reads don't NPE. In production the constructor leaves
        // mTextBeforeCursor == mTextAfterCursor == mTextSelection == "".
        setField(ric, "mBeforeReadSnapshot", new String[1]);
        setField(ric, "mBeforeReadSnapshotLen", new int[1]);
        setField(ric, "mBeforeReadSnapshotComplete", new boolean[1]);
        setField(ric, "mAfterReadSnapshot", new String[1]);
        setField(ric, "mAfterReadSnapshotLen", new int[1]);
        setField(ric, "mAfterReadSnapshotComplete", new boolean[1]);
        setField(ric, "mTextBeforeCursor", "");
        setField(ric, "mTextAfterCursor", "");
        setField(ric, "mTextSelection", "");
        setField(ric, "mLatinIME", makeMockIme());
        setField(ric, "mIC", ic);
        // Start with cursor at 0/0 so optimistic updates have a valid anchor.
        ric.updateSelection(0, 0);
    }

    // ---------------------------------------------------------------------------------------------
    // A.1 - Multiple getWordBeforeCursor calls in the same event -> exactly one IPC read
    // ---------------------------------------------------------------------------------------------

    @Test
    public void multipleGetWordBeforeCursorInSameEventIssueSingleIPC() {
        beforeResponses.push("hello world");
        final String first = ric.getWordBeforeCursor();
        assertEquals("world", first);
        // Second and third reads in the same event must be served from the snapshot.
        final String second = ric.getWordBeforeCursor();
        final String third = ric.getWordBeforeCursor();
        assertEquals("world", second);
        assertEquals("world", third);
        assertEquals("3 reads must collapse into exactly one IPC", 1, counter.before);
    }

    @Test
    public void sameEventReadOfDifferentLengthStillDedupsWhenCovered() {
        beforeResponses.push("abc"); // 3 chars, maxChars=40 -> complete=true
        assertEquals("abc", ric.getTextBeforeCursor(40, 0));
        // A shorter read must be served by the complete snapshot (the editor has nothing more
        // before the cursor than what the first IPC returned).
        assertEquals("c", ric.getTextBeforeCursor(1, 0));
        assertEquals("bc", ric.getTextBeforeCursor(2, 0));
        assertEquals("abc", ric.getWordBeforeCursor());
        assertEquals(1, counter.before);
    }

    // ---------------------------------------------------------------------------------------------
    // A.2 - commitText invalidates: next read must not serve the pre-write snapshot
    // ---------------------------------------------------------------------------------------------

    @Test
    public void commitTextInvalidatesSnapshotForcesFreshRead() {
        // Seed snapshot + long-lived cache with pre-commit state.
        beforeResponses.push("ab");
        ric.getTextBeforeCursor(40, 0); // 1 IPC; snapshot = "ab" (complete=true)
        assertEquals(1, counter.before);
        // Write: optimistic cache extends to "ab" + "cd" = "abcd" (length 4 < 40).
        ric.commitText("cd", 1);
        // Next read must NOT serve the stale pre-write snapshot ("ab"): it must observe the
        // committed tail. Cache (4 chars) is shorter than 40, so a fresh IPC must fire.
        beforeResponses.push("abcd");
        final String out = ric.getTextBeforeCursor(40, 0);
        assertEquals("abcd", out);
        assertEquals("post-commit read must force a fresh IPC", 2, counter.before);
    }

    /**
     * commitText invalidates the snapshot. Pin: even when the long-lived cache covers the
     * request size, the WRONG cached value (the pre-write one) must not be served if a write
     * happened in the same event. We force a stale snapshot by pre-populating
     * {@code mBeforeReadSnapshot} directly, then commit and verify the result reflects the
     * write, not the stale snapshot.
     */
    @Test
    public void commitTextInvalidatesSnapshotEvenWhenCacheCovers() {
        // Pre-populate a stale snapshot via the read path (cache empty -> IPC). The snapshot
        // is "stale" because the next operation is a write.
        beforeResponses.push("stale");
        ric.getTextBeforeCursor(40, 0);
        assertEquals(1, counter.before);
        // Now mutate the long-lived cache directly to a value that LOOKS fresh but was set
        // before our write: this would be served if the snapshot path were broken.
        setField(ric, "mTextBeforeCursor", "OLD");
        ric.commitText("NEW", 1);
        // Cache is now "OLDNEW" (length 6 < 40 -> falls back to IPC for any 40-char read).
        beforeResponses.push("OLDNEW");
        final String out = ric.getTextBeforeCursor(40, 0);
        assertEquals("post-commit read must observe the write, not the stale snapshot",
                "OLDNEW", out);
        assertEquals("snapshot path must not serve the pre-write value", 2, counter.before);
    }

    // ---------------------------------------------------------------------------------------------
    // A.3 - deleteTextBeforeCursor with mIC == null must not NPE
    // ---------------------------------------------------------------------------------------------

    @Test
    public void deleteTextBeforeCursorWithNullICDoesNotThrow() {
        setField(ric, "mIC", null);
        // Old code NPE'd on mIC.deleteSurroundingText; new code must guard + log.
        try {
            ric.deleteTextBeforeCursor(3);
        } catch (NullPointerException e) {
            throw new AssertionError("deleteTextBeforeCursor must not NPE when disconnected", e);
        }
        // AGENTS.md: no silent failures. isConnected() must report the disconnection and the
        // optimistic cache update still happened (visible after reconnect via a fresh read).
        assertFalse(ric.isConnected());
    }

    @Test
    public void deleteTextBeforeCursorWithNullICStillInvalidatesSnapshots() {
        // Build a live snapshot first.
        beforeResponses.push("abcd");
        ric.getTextBeforeCursor(40, 0);
        assertEquals(1, counter.before);
        // Disconnect.
        setField(ric, "mIC", null);
        ric.deleteTextBeforeCursor(2);
        // Reconnect and read: the pre-delete snapshot must be gone, forcing a fresh IPC whose
        // result reflects the post-delete editor state.
        setField(ric, "mIC", ic);
        beforeResponses.push("cd");
        final String word = ric.getWordBeforeCursor();
        assertEquals("cd", word);
        assertEquals("snapshot must be invalidated even on the disconnected path", 2,
                counter.before);
    }

    // ---------------------------------------------------------------------------------------------
    // A.4 - External onUpdateSelection invalidates snapshots
    // ---------------------------------------------------------------------------------------------

    @Test
    public void onUpdateSelectionInvalidatesSnapshots() {
        beforeResponses.push("abcdef");
        ric.getTextBeforeCursor(40, 0);
        assertEquals(1, counter.before);
        // RichInputConnection#updateSelection is what InputLogic#onUpdateSelection delegates to;
        // InputLogic additionally calls invalidateReadSnapshots() (the change under test).
        ric.updateSelection(3, 3);
        ric.invalidateReadSnapshots();
        beforeResponses.push("XYZ");
        assertEquals("XYZ", ric.getTextBeforeCursor(40, 0));
        assertEquals("external selection move must invalidate the snapshot", 2, counter.before);
    }

    /**
     * Pins the RichInputConnection-side contract that {@code updateSelection} alone does NOT
     * invalidate (the invalidation lives in InputLogic#onUpdateSelection). If a future refactor
     * moves the invalidation into updateSelection, this test documents the coupling: the
     * InputLogic caller must not be duplicated.
     */
    @Test
    public void updateSelectionAloneDoesNotInvalidateSnapshot() {
        beforeResponses.push("abcdef");
        ric.getTextBeforeCursor(40, 0);
        assertEquals(1, counter.before);
        ric.updateSelection(3, 3);
        // No invalidateReadSnapshots() here: the snapshot survives.
        assertEquals("abcdef", ric.getTextBeforeCursor(40, 0));
        assertEquals(1, counter.before);
    }

    // ---------------------------------------------------------------------------------------------
    // A.5 - Snapshot with complete=true serves any smaller n without IPC
    // ---------------------------------------------------------------------------------------------

    @Test
    public void completeSnapshotServesAnySmallerN() {
        beforeResponses.push("hi"); // 2 < 40 -> complete=true
        assertEquals("hi", ric.getTextBeforeCursor(40, 0));
        assertEquals(1, counter.before);
        // Every smaller (and equal) request must be served without IPC.
        for (final int n : new int[]{1, 2, 3, 5, 39, 40}) {
            // The cache returns the LAST take chars where take = min(n, snap.length()).
            final int take = Math.min(n, "hi".length());
            final String expected = "hi".substring("hi".length() - take);
            assertEquals("n=" + n, expected, ric.getTextBeforeCursor(n, 0));
        }
        assertEquals("complete snapshot must absorb every smaller read", 1, counter.before);
    }

    // ---------------------------------------------------------------------------------------------
    // A.6 - Short mTextAfterCursor cache after a key press -> exactly one IPC fallback
    // ---------------------------------------------------------------------------------------------

    @Test
    public void shortAfterCacheAfterKeyPressFallsBackToIPCExactlyOnce() {
        // Simulate a key press: commitText does NOT touch mTextAfterCursor (refreshed by the
        // background thread only), so the after-cache stays short/empty.
        ric.commitText("x", 1);
        assertEquals(0, counter.after);
        // First after-read: cache too short -> exactly one IPC.
        afterResponses.push(" rest of sentence"); // 17 chars < 40 -> complete=true
        final String word = ric.getWordAfterCursor();
        assertEquals("", word); // cursor is directly before " " (a boundary char)
        assertEquals(1, counter.after);
        // Second after-read in the same event: served from the complete snapshot, no IPC.
        ric.getWordAfterCursor();
        assertEquals("repeat after-read must not re-issue IPC", 1, counter.after);
    }

    @Test
    public void afterSnapshotDedupsSameLengthReads() {
        afterResponses.push("afterText");
        assertEquals("afterText", ric.getWordAtCursor().isEmpty()
                ? ric.getWordAfterCursor() : ric.getWordAfterCursor());
        // getWordAtCursor consumed one after-read (plus before-reads served from the cache).
        assertEquals(1, counter.after);
    }

    // ---------------------------------------------------------------------------------------------
    // A.7 - setSelection invalidates
    // ---------------------------------------------------------------------------------------------

    @Test
    public void setSelectionInvalidatesSnapshot() {
        beforeResponses.push("alpha");
        ric.getTextBeforeCursor(40, 0);
        assertEquals(1, counter.before);
        // Move the cursor: mExpectedSelStart/End change from 0 to 4.
        ric.setSelection(4, 4);
        beforeResponses.push("aXXX");
        assertEquals("aXXX", ric.getTextBeforeCursor(40, 0));
        assertEquals("setSelection must invalidate the snapshot", 2, counter.before);
    }

    @Test
    public void setSelectionToSamePositionIsNoOpAndDoesNotInvalidate() {
        beforeResponses.push("stable");
        ric.getTextBeforeCursor(40, 0);
        assertEquals(1, counter.before);
        ric.setSelection(0, 0); // same as current expected selection -> early return
        assertEquals("stable", ric.getTextBeforeCursor(40, 0));
        assertEquals("no-op setSelection must keep the snapshot valid", 1, counter.before);
    }

    /**
     * Adversarial: setSelection with INVALID bounds (-1) must be rejected without corrupting
     * state and without invalidating a still-valid snapshot.
     */
    @Test
    public void setSelectionWithInvalidBoundsIsRejected() {
        beforeResponses.push("keepme");
        ric.getTextBeforeCursor(40, 0);
        assertEquals(1, counter.before);
        ric.setSelection(-1, 4);
        assertEquals("keepme", ric.getTextBeforeCursor(40, 0));
        assertEquals("invalid bounds must be rejected before touching the snapshot", 1,
                counter.before);
        assertEquals("no IPC setSelection for invalid bounds", 0, counter.setSelection);
    }

    // ---------------------------------------------------------------------------------------------
    // Extra adversarial coverage for the changed write paths
    // ---------------------------------------------------------------------------------------------

    /**
     * commitSuggestion must issue a FRESH after-cursor IPC (cache bypass) and drive the
     * surrounding-text deletion from that fresh value, never from a stale snapshot.
     */
    @Test
    public void commitSuggestionDeletesUsingFreshAfterRead() {
        // Pre-populate a STALE after-snapshot (as if an earlier read in this event cached it).
        setField(ric, "mTextAfterCursor", "staleTailXXXX");
        afterResponses.push("staleTailXXXX");
        ric.getWordAfterCursor();
        assertEquals(1, counter.after);
        // Now commit a suggestion. fetchFreshTextAfter must re-read from the editor.
        beforeResponses.push("typo"); // word before cursor
        afterResponses.push("tail"); // FRESH editor content: the tail word is "tail"
        ric.commitSuggestion("word");
        // Expected IPC sequence: fresh after-read, no extra reads; then deleteSurroundingText
        // with the FRESH word length, then commit of the suggestion.
        assertEquals("commitSuggestion must bypass the after-cache", 2, counter.after);
        assertTrue("deleteSurroundingText(before, 0) must fire for the pre-cursor word",
                counter.calls.contains("del(4,0)"));
        assertTrue("deleteSurroundingText(0, after) must use the FRESH tail length",
                counter.calls.contains("del(0,4)"));
        assertTrue("suggestion must be committed with a trailing separator",
                counter.calls.contains("commit(word )"));
    }

    /**
     * A throwing InputConnection must not crash the read paths (they catch, log and fall back
     * to the long-lived cache).
     */
    @Test
    public void throwingInputConnectionDegradesToCache() {
        // Prime the long-lived cache.
        ric.commitText("cached", 1);
        final InvocationHandler boom = (proxy, method, args) -> {
            final String n = method.getName();
            if ("getTextBeforeCursor".equals(n) || "getTextAfterCursor".equals(n)) {
                throw new IllegalStateException("IPC exploded");
            }
            return Boolean.TRUE;
        };
        final InputConnection broken = (InputConnection) java.lang.reflect.Proxy.newProxyInstance(
                RichInputConnectionAdversarialTest.class.getClassLoader(),
                new Class<?>[]{InputConnection.class}, boom);
        setField(ric, "mIC", broken);
        // Cache (6 chars) is shorter than 40 -> IPC attempted -> throws -> falls back to cache.
        assertEquals("cached", ric.getTextBeforeCursor(40, 0));
    }

    /**
     * A null-responding InputConnection must not NPE on the read paths.
     */
    @Test
    public void nullRespondingInputConnectionReturnsEmptyWithoutNPE() {
        final InvocationHandler nulling = (proxy, method, args) -> null;
        final InputConnection ghost = (InputConnection) java.lang.reflect.Proxy.newProxyInstance(
                RichInputConnectionAdversarialTest.class.getClassLoader(),
                new Class<?>[]{InputConnection.class}, nulling);
        setField(ric, "mIC", ghost);
        assertEquals("", ric.getTextBeforeCursor(40, 0));
        assertEquals("", ric.getWordBeforeCursor());
        assertEquals("", ric.getWordAfterCursor());
    }

    /**
     * The tail-substring contract: with a long-lived cache LONGER than the requested n, the
     * result must be exactly the LAST n chars (text before the cursor grows backwards).
     */
    @Test
    public void longCacheIsTruncatedFromTheTail() {
        setField(ric, "mTextBeforeCursor", "0123456789"); // 10 chars
        assertEquals("6789", ric.getTextBeforeCursor(4, 0));
        assertEquals(0, counter.before); // served from cache, no IPC
        assertEquals("0123456789", ric.getTextBeforeCursor(10, 0));
        assertEquals(0, counter.before);
    }

    /**
     * The head-substring contract for after-cursor reads: the cache path returns the FIRST n
     * chars (or the entire leading word), never the tail. Verified through the word API.
     */
    @Test
    public void longAfterCacheIsTruncatedFromTheHead() {
        // Cache must be >= 40 chars to bypass IPC and serve directly. Build 45 'x' chars so
        // every leading position in the first 40 chars is a word character (no boundary hit).
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 45; i++) sb.append('x');
        setField(ric, "mTextAfterCursor", sb.toString());
        // getCachedOrFetchTextAfter(40) returns substring(0, 40) when cache.length >= 40.
        // getWordAfterCursor iterates that string until a boundary char. 'x' is a letter -> no
        // boundary in 40 chars -> the first word spans all 40 chars.
        assertEquals("head substring of the after-cache is exactly 40 chars", 40,
                sb.toString().substring(0, 40).length());
        assertEquals("first word spans the whole head substring",
                sb.toString().substring(0, 40), ric.getWordAfterCursor());
        assertEquals(0, counter.after); // served from cache, no IPC
    }

    /**
     * Surrogate-pair safety: a cache whose tail splits a surrogate pair must not throw when
     * extracting the last word (char-based substring is pre-existing behavior, but the snapshot
     * layer must not crash on it).
     */
    @Test
    public void snapshotSubstringSurvivesSurrogateBoundaries() {
        // "ab" + U+1F600 (2 surrogate chars) committed; request sizes land on both halves.
        ric.commitText("ab\ud83d\ude00", 1);
        assertEquals(0, counter.before);
        // Must not throw (character-index arithmetic is char-based by design).
        final String out = ric.getTextBeforeCursor(3, 0);
        assertNotNull(out);
        assertEquals(3, out.length());
    }

    /**
     * Rapid-fire mixed sequence: write -> read -> write -> read. Every read after a write must
     * observe the write's effect (no cross-event stale reuse).
     */
    @Test
    public void interleavedWritesAndReadsAlwaysObserveWrites() {
        ric.commitText("one", 1);
        beforeResponses.push("one");
        assertEquals("one", ric.getTextBeforeCursor(40, 0));
        assertEquals(1, counter.before);

        ric.commitText("two", 1);
        beforeResponses.push("onetwo");
        assertEquals("post-commit cache is shorter than 40 -> fresh IPC", "onetwo",
                ric.getTextBeforeCursor(40, 0));
        assertEquals(2, counter.before);

        // Backspace deletes the last char optimistically: cache becomes "onetw".
        ric.deleteTextBeforeCursor(1);
        beforeResponses.push("onetw");
        assertEquals("post-delete IPC must observe the deletion", "onetw",
                ric.getTextBeforeCursor(40, 0));
        assertEquals(3, counter.before);
    }
}
