package rkr.simplekeyboard.inputmethod.latin.clipboard;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class ClipboardDatabaseAndroidTest {

    private Context mContext;
    private ClipboardDatabase mDb;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mDb = new ClipboardDatabase(mContext);
        // Clear all entries before test
        SQLiteDatabase db = mDb.getWritableDatabase();
        db.delete("clips", null, null);
    }

    @After
    public void tearDown() {
        if (mDb != null) {
            mDb.close();
        }
    }

    private int getCount(String query, String[] args) {
        SQLiteDatabase db = mDb.getReadableDatabase();
        Cursor c = db.rawQuery(query, args);
        int count = 0;
        if (c != null) {
            if (c.moveToFirst()) {
                count = c.getInt(0);
            }
            c.close();
        }
        return count;
    }

    private String getPragmaIntegrity() {
        SQLiteDatabase db = mDb.getReadableDatabase();
        Cursor c = db.rawQuery("PRAGMA integrity_check", null);
        String result = "failed";
        if (c != null) {
            if (c.moveToFirst()) {
                result = c.getString(0);
            }
            c.close();
        }
        return result;
    }

    @Test
    public void testBlockA_FullMatrixOnRealDevice() {
        // 1. Initial State & Pragma Integrity
        assertEquals("ok", getPragmaIntegrity());
        assertEquals(0, getCount("SELECT COUNT(*) FROM clips", null));
        assertEquals(0, getCount("SELECT COUNT(*) FROM clips WHERE is_pinned=1", null));

        // 2. Controlled Load: Insert 100 clips
        // Note: insertClip enforces MAX_CLIPS=50 for unpinned clips, so 100 unpinned insertions keep latest 50.
        // To test exactly 100 clips (50 pinned + 50 unpinned), we insert and pin 50 first, then insert 50 unpinned.
        for (int i = 0; i < 50; i++) {
            mDb.insertClip("QA_A_PINNED_" + String.format("%03d", i), true);
        }
        assertEquals(50, getCount("SELECT COUNT(*) FROM clips", null));
        assertEquals(50, getCount("SELECT COUNT(*) FROM clips WHERE is_pinned=1", null));

        for (int i = 0; i < 50; i++) {
            mDb.insertClip("QA_A_UNPINNED_" + String.format("%03d", i), false);
        }
        assertEquals(100, getCount("SELECT COUNT(*) FROM clips", null));
        assertEquals(50, getCount("SELECT COUNT(*) FROM clips WHERE is_pinned=1", null));
        assertEquals(50, getCount("SELECT COUNT(*) FROM clips WHERE is_pinned=0", null));

        // 3. Real Pin Limit: Attempting to pin the 51st clip must return false
        List<ClipboardHistoryEntry> unpinnedClips = mDb.getClips();
        long candidateIdToPin = -1;
        for (ClipboardHistoryEntry entry : unpinnedClips) {
            if (!entry.isPinned) {
                candidateIdToPin = entry.id;
                break;
            }
        }
        assertTrue("Must find an unpinned candidate", candidateIdToPin > 0);

        boolean pin51Result = mDb.setPinned(candidateIdToPin, true);
        assertFalse("Attempt to pin 51st clip MUST return false", pin51Result);

        // Verify count is still exactly 50
        assertEquals(50, getCount("SELECT COUNT(*) FROM clips WHERE is_pinned=1", null));
        assertEquals(100, getCount("SELECT COUNT(*) FROM clips", null));

        // 4. Unpin one and pin the candidate
        long pinnedIdToUnpin = -1;
        for (ClipboardHistoryEntry entry : unpinnedClips) {
            if (entry.isPinned) {
                pinnedIdToUnpin = entry.id;
                break;
            }
        }
        assertTrue("Must find a pinned entry to unpin", pinnedIdToUnpin > 0);
        assertTrue(mDb.setPinned(pinnedIdToUnpin, false));
        assertEquals(49, getCount("SELECT COUNT(*) FROM clips WHERE is_pinned=1", null));

        // Now pinning the candidate must succeed
        assertTrue(mDb.setPinned(candidateIdToPin, true));
        assertEquals(50, getCount("SELECT COUNT(*) FROM clips WHERE is_pinned=1", null));

        // 5. Expiration & Cleanup Interaction
        // Backdate unpinned timestamps to 2 hours ago (120 minutes ago)
        SQLiteDatabase rawDb = mDb.getWritableDatabase();
        long pastTime = System.currentTimeMillis() - (120 * 60 * 1000L);
        rawDb.execSQL("UPDATE clips SET timestamp = " + pastTime + " WHERE is_pinned=0");

        // Execute real cleanup method with 60 minute retention
        mDb.deleteExpiredClips(60);

        // Verify: Pinned clips MUST survive (50), unpinned clips MUST be expired (0)
        assertEquals(50, getCount("SELECT COUNT(*) FROM clips WHERE is_pinned=1", null));
        assertEquals(0, getCount("SELECT COUNT(*) FROM clips WHERE is_pinned=0", null));
        assertEquals(50, getCount("SELECT COUNT(*) FROM clips", null));

        // 6. Integrity check after stress
        assertEquals("ok", getPragmaIntegrity());
    }

    @Test
    public void testBlockA_ConcurrentPinStress() throws InterruptedException {
        // Start with 48 pinned clips and 20 unpinned clips
        for (int i = 0; i < 48; i++) {
            mDb.insertClip("CONCURRENT_PINNED_" + i, true);
        }
        for (int i = 0; i < 20; i++) {
            mDb.insertClip("CONCURRENT_UNPINNED_" + i, false);
        }
        assertEquals(48, getCount("SELECT COUNT(*) FROM clips WHERE is_pinned=1", null));

        List<ClipboardHistoryEntry> allClips = mDb.getClips();
        final List<Long> unpinnedIds = new ArrayList<>();
        for (ClipboardHistoryEntry e : allClips) {
            if (!e.isPinned) {
                unpinnedIds.add(e.id);
            }
        }
        assertTrue(unpinnedIds.size() >= 10);

        // Launch 10 concurrent threads trying to pin simultaneously
        int threadCount = unpinnedIds.size();
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        final AtomicInteger successfulPins = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final long id = unpinnedIds.get(i);
            executor.execute(() -> {
                try {
                    startLatch.await();
                    boolean res = mDb.setPinned(id, true);
                    if (res) {
                        successfulPins.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        // Exactly 2 threads should succeed to reach 50, remaining must fail
        assertEquals(2, successfulPins.get());
        assertEquals(50, getCount("SELECT COUNT(*) FROM clips WHERE is_pinned=1", null));
        assertEquals("ok", getPragmaIntegrity());
    }
}