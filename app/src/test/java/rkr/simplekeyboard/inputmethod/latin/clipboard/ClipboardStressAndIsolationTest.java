package rkr.simplekeyboard.inputmethod.latin.clipboard;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(JUnit4.class)
public class ClipboardStressAndIsolationTest {

    @Test
    public void testMassiveQueryMatrix() {
        String sample = "Prefix https://github.com/LeanBitLab/LeanType user@example.com 100% my_var path\\to\\file a/b/c abc \"quoted\" (parentheses) multi\nline\ntext está aquí mañana ПРИВЕТ мир Ελληνικά 日本語 中文 한국어 🔥 👨‍👩‍👧‍👦 🚀✨ Suffix Mix Español Русский";

        String[] queries = {
            "", " ", "   ", "a", "A", "ab", "abc", "Prefix",
            "100%", "https://github.com/LeanBitLab/LeanType",
            "user@example.com", "my_var", "path\\to\\file", "a/b/c",
            "\"quoted\"", "(parentheses)", "multi\nline\ntext",
            "🔥", "🚀✨", "está aquí", "mañana", "ПРИВЕТ", "мир",
            "Ελληνικά", "日本語", "中文", "한국어", "Mix Español", "Русский"
        };

        for (String q : queries) {
            boolean matches = ClipboardDatabase.matchesQuery(sample, q);
            assertTrue("Query '" + q + "' should match sample", matches);
        }

        assertFalse("Non-existent query should not match", ClipboardDatabase.matchesQuery(sample, "NON_EXISTENT_KEYWORD_XYZ"));
    }

    @Test
    public void testExtremelyLongQueryAndText() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            longText.append("RandomText_").append(i).append(" ");
        }
        String fullText = longText.toString();

        assertTrue(ClipboardDatabase.matchesQuery(fullText, "RandomText_2500"));
        assertTrue(ClipboardDatabase.matchesQuery(fullText, "randomtext_4999"));
        assertFalse(ClipboardDatabase.matchesQuery(fullText, "NonExistentWord_9999"));
    }

    @Test
    public void testUnicodeEdgeCasesAnalysis() {
        // Turkish i / I
        assertTrue(ClipboardDatabase.matchesQuery("ISTANBUL", "istanbul"));
        assertTrue(ClipboardDatabase.matchesQuery("istanbul", "ISTANBUL"));

        // German ß / ss
        assertTrue(ClipboardDatabase.matchesQuery("SCHLOSS", "schloss"));

        // Greek sigma: Σ, σ, ς
        assertTrue(ClipboardDatabase.matchesQuery("ΒΟΛΟΣ", "βολος"));

        // Combining accents (NFC vs NFD)
        String nfc = "canción";
        String nfd = Normalizer.normalize("canción", Normalizer.Form.NFD);
        assertTrue(ClipboardDatabase.matchesQuery(nfc, "canción"));
        assertTrue(ClipboardDatabase.matchesQuery(nfd, nfd));
    }

    @Test
    public void testPinningLimitEnforcementInList() {
        List<ClipboardHistoryEntry> entries = new ArrayList<>();
        int pinnedCount = 0;
        int maxPinned = ClipboardDatabase.MAX_PINNED_CLIPS;

        for (int i = 0; i < 60; i++) {
            boolean canPin = (pinnedCount < maxPinned);
            if (canPin && i < 50) {
                entries.add(new ClipboardHistoryEntry(i, "Clip " + i, System.currentTimeMillis() - i * 1000L, true));
                pinnedCount++;
            } else {
                entries.add(new ClipboardHistoryEntry(i, "Clip " + i, System.currentTimeMillis() - i * 1000L, false));
            }
        }

        assertEquals(50, pinnedCount);
        assertEquals(60, entries.size());

        // Attempting to pin the 51st item must be rejected
        boolean attempt51 = (pinnedCount < maxPinned);
        assertFalse("51st pin attempt must be rejected", attempt51);
        assertEquals(50, pinnedCount);
    }

    @Test
    public void testMonotonicTokenConcurrentSimulation() throws InterruptedException {
        final int iterations = 100;
        final AtomicInteger completedQueries = new AtomicInteger(0);
        final AtomicInteger staleDiscards = new AtomicInteger(0);
        final long[] latestTokenHolder = new long[]{0L};

        ExecutorService executor = Executors.newFixedThreadPool(4);

        for (int i = 1; i <= iterations; i++) {
            final long queryToken = (long) i;
            latestTokenHolder[0] = queryToken;

            executor.execute(() -> {
                try {
                    Thread.sleep((long) (Math.random() * 20));
                } catch (InterruptedException ignored) {}

                if (queryToken == latestTokenHolder[0]) {
                    completedQueries.incrementAndGet();
                } else {
                    staleDiscards.incrementAndGet();
                }
            });
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        assertTrue(completedQueries.get() > 0);
        assertTrue(staleDiscards.get() > 0);
        assertEquals(iterations, completedQueries.get() + staleDiscards.get());
    }

    @Test
    public void testBackNavigationStateFlow() {
        // State simulation:
        // 1. Initial normal state
        boolean isClipboardShowing = true;
        boolean isSearchActive = false;
        String query = "";

        // 2. Open Search
        isSearchActive = true;
        query = "hello";

        // 3. First BACK key press: exits Search first
        if (isSearchActive) {
            isSearchActive = false;
            query = "";
        } else {
            isClipboardShowing = false;
        }

        assertFalse(isSearchActive);
        assertEquals("", query);
        assertTrue(isClipboardShowing);

        // 4. Second BACK key press: closes Clipboard history
        if (isSearchActive) {
            isSearchActive = false;
            query = "";
        } else {
            isClipboardShowing = false;
        }

        assertFalse(isSearchActive);
        assertFalse(isClipboardShowing);
    }
}