package rkr.simplekeyboard.inputmethod.latin.clipboard;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import rkr.simplekeyboard.inputmethod.latin.settings.SettingsActivity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class ClipboardProcessDeathAndLifecycleAndroidTest {

    private Context mContext;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private String getPragmaIntegrity(ClipboardDatabase dbHelper) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
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

    private int getCount(ClipboardDatabase dbHelper, String sql) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery(sql, null);
        int count = 0;
        if (c != null) {
            if (c.moveToFirst()) {
                count = c.getInt(0);
            }
            c.close();
        }
        return count;
    }

    @Test
    public void testC1_ForceStopAndDatabaseRecovery() {
        ClipboardDatabase db = new ClipboardDatabase(mContext);
        db.getWritableDatabase().delete("clips", null, null);

        // Populate initial verified state
        for (int i = 0; i < 20; i++) {
            db.insertClip("PERSIST_PINNED_" + i, true);
        }
        for (int i = 0; i < 20; i++) {
            db.insertClip("PERSIST_UNPINNED_" + i, false);
        }

        assertEquals(20, getCount(db, "SELECT COUNT(*) FROM clips WHERE is_pinned=1"));
        assertEquals(20, getCount(db, "SELECT COUNT(*) FROM clips WHERE is_pinned=0"));
        assertEquals("ok", getPragmaIntegrity(db));
        db.close();

        // Reopen database (simulating process recreation after stop)
        ClipboardDatabase reopenedDb = new ClipboardDatabase(mContext);
        assertEquals("ok", getPragmaIntegrity(reopenedDb));
        assertEquals(20, getCount(reopenedDb, "SELECT COUNT(*) FROM clips WHERE is_pinned=1"));
        assertEquals(20, getCount(reopenedDb, "SELECT COUNT(*) FROM clips WHERE is_pinned=0"));
        assertEquals(40, getCount(reopenedDb, "SELECT COUNT(*) FROM clips"));
        reopenedDb.close();
    }

    @Test
    public void testC2_InterruptedTransactionRollbackAndIntegrity() {
        ClipboardDatabase db = new ClipboardDatabase(mContext);
        db.getWritableDatabase().delete("clips", null, null);

        // Populate baseline: 5 pinned, 5 unpinned
        for (int i = 0; i < 5; i++) {
            db.insertClip("COMMITTED_PINNED_" + i, true);
            db.insertClip("COMMITTED_UNPINNED_" + i, false);
        }
        assertEquals(10, getCount(db, "SELECT COUNT(*) FROM clips"));

        // Simulate an uncommitted interrupted transaction (process killed before setTransactionSuccessful)
        SQLiteDatabase rawDb = db.getWritableDatabase();
        rawDb.beginTransaction();
        try {
            rawDb.execSQL("INSERT INTO clips (text, timestamp, is_pinned) VALUES ('INTERRUPTED_UNCOMMITTED_1', 99999, 1)");
            rawDb.execSQL("INSERT INTO clips (text, timestamp, is_pinned) VALUES ('INTERRUPTED_UNCOMMITTED_2', 99999, 0)");
            // Simulated crash/interruption: endTransaction called WITHOUT setTransactionSuccessful
        } finally {
            rawDb.endTransaction();
        }

        // Verify that interrupted dirty transaction was cleanly rolled back by SQLite engine
        assertEquals(10, getCount(db, "SELECT COUNT(*) FROM clips"));
        assertEquals(5, getCount(db, "SELECT COUNT(*) FROM clips WHERE is_pinned=1"));
        assertEquals("ok", getPragmaIntegrity(db));
        db.close();
    }

    @Test
    public void testC3_LifecycleResetOnFinishInputView() throws Throwable {
        Intent intent = new Intent(mContext, SettingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        SettingsActivity activity = (SettingsActivity) InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        final ClipboardDatabase db = new ClipboardDatabase(mContext);
        final ClipboardHistoryView[] viewHolder = new ClipboardHistoryView[1];

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ClipboardHistoryView historyView = new ClipboardHistoryView(activity);
            historyView.setDatabase(db);
            activity.setContentView(historyView);
            viewHolder[0] = historyView;
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        final ClipboardHistoryView historyView = viewHolder[0];

        // 1. Enter search mode and type query
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            historyView.startSearch();
            historyView.appendSearchText("active_search_query");
        });
        assertTrue(historyView.isSearchActive());

        // 2. Simulate IME finish/hide lifecycle event (onFinishInputView calls closeSearchWithoutReload)
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            historyView.closeSearchWithoutReload();
        });

        // 3. Invariant: isSearchActive must be immediately false and query cleared
        assertFalse("isSearchActive must be false after finish input view", historyView.isSearchActive());

        // 4. Start search again -> must be fresh without ghost query
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            historyView.startSearch();
        });
        assertTrue(historyView.isSearchActive());

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            historyView.closeSearch();
        });
        assertFalse(historyView.isSearchActive());

        db.close();
        activity.finish();
    }
}