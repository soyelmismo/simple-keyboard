package rkr.simplekeyboard.inputmethod.latin.clipboard;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class ClipboardDatabase extends SQLiteOpenHelper {
    private static final String TAG = "ClipboardDatabase";
    private static final String DATABASE_NAME = "clipboard_history.db";
    private static final int DATABASE_VERSION = 2;
    private static final String TABLE_NAME = "clips";
    private static final String COL_ID = "id";
    private static final String COL_TEXT = "text";
    private static final String COL_TIMESTAMP = "timestamp";
    private static final String COL_PINNED = "is_pinned";
    private static final String COL_URI = "uri";
    public static final int MAX_CLIPS = 50;
    public static final int MAX_PINNED_CLIPS = 50;
    private static final int MAX_TEXT_LENGTH = 50000;

    public ClipboardDatabase(Context context) {
        super(rkr.simplekeyboard.inputmethod.compat.PreferenceManagerCompat.getDeviceContext(context), DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String createTable = "CREATE TABLE " + TABLE_NAME + " (" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_TEXT + " TEXT UNIQUE, " +
                COL_TIMESTAMP + " INTEGER, " +
                COL_PINNED + " INTEGER DEFAULT 0, " +
                COL_URI + " TEXT)";
        db.execSQL(createTable);
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_pinned_time ON " + TABLE_NAME + " (" + COL_PINNED + ", " + COL_TIMESTAMP + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + COL_URI + " TEXT");
            } catch (Exception e) {
                Log.e(TAG, "Error adding uri column on upgrade", e);
            }
        }
    }

    public synchronized void insertClip(String text) {
        insertClip(text, false, System.currentTimeMillis(), null);
    }

    public synchronized void insertClip(String text, boolean pinned) {
        insertClip(text, pinned, System.currentTimeMillis(), null);
    }

    public synchronized void insertClip(String text, boolean pinned, long timestamp) {
        insertClip(text, pinned, timestamp, null);
    }

    public synchronized void insertClip(String text, boolean pinned, long timestamp, String uri) {
        if ((text == null || text.trim().isEmpty()) && (uri == null || uri.trim().isEmpty())) {
            return;
        }
        if (text != null && text.length() > MAX_TEXT_LENGTH) {
            text = text.substring(0, MAX_TEXT_LENGTH);
        }
        if (timestamp <= 0) {
            timestamp = System.currentTimeMillis();
        }
        SQLiteDatabase db = null;
        try {
            db = getWritableDatabase();
            db.beginTransaction();
            // Check if exists and whether it was pinned
            boolean isPinned = pinned;
            String queryKey = (uri != null && !uri.isEmpty()) ? uri : text;
            String whereClause = (uri != null && !uri.isEmpty()) ? (COL_URI + "=?") : (COL_TEXT + "=?");
            Cursor cursor = db.query(TABLE_NAME, new String[]{COL_PINNED}, whereClause,
                    new String[]{queryKey}, null, null, null);
            if (cursor != null) {
                if (cursor.moveToFirst()) {
                    isPinned = isPinned || (cursor.getInt(0) == 1);
                }
                cursor.close();
            }

            // Remove existing row to refresh timestamp and position
            db.delete(TABLE_NAME, whereClause, new String[]{queryKey});

            ContentValues values = new ContentValues();
            values.put(COL_TEXT, text != null ? text : "[Screenshot]");
            values.put(COL_TIMESTAMP, timestamp);
            values.put(COL_PINNED, isPinned ? 1 : 0);
            if (uri != null) {
                values.put(COL_URI, uri);
            }
            db.insert(TABLE_NAME, null, values);

            cleanupOldClips(db);
            db.setTransactionSuccessful();
        } catch (Throwable e) {
            Log.e(TAG, "Error inserting clip", e);
        } finally {
            if (db != null) {
                try {
                    db.endTransaction();
                } catch (Throwable ignored) {}
            }
        }
    }

    private void deleteCachedFileIfPresent(String uri) {
        if (uri != null && uri.startsWith("/")) {
            try {
                File file = new File(uri);
                if (file.exists() && file.isFile()) {
                    file.delete();
                }
            } catch (Exception ignored) {}
        }
    }

    private int deleteClipsAndFiles(final SQLiteDatabase db, final String whereClause, final String[] whereArgs) {
        if (db == null) {
            return 0;
        }
        Cursor c = null;
        try {
            c = db.query(TABLE_NAME, new String[]{COL_URI}, whereClause, whereArgs, null, null, null);
            if (c != null) {
                while (c.moveToNext()) {
                    deleteCachedFileIfPresent(c.getString(0));
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "Error querying clips for deletion", e);
        } finally {
            if (c != null) {
                try {
                    c.close();
                } catch (Throwable ignored) {}
            }
        }
        return db.delete(TABLE_NAME, whereClause, whereArgs);
    }

    public synchronized void deleteExpiredClips(long retentionMinutes) {
        if (retentionMinutes <= 0) {
            return; // Never / Unlimited
        }
        SQLiteDatabase db = null;
        try {
            db = getWritableDatabase();
            db.beginTransaction();
            long cutoffTimestamp = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(retentionMinutes);
            deleteClipsAndFiles(db, COL_PINNED + "=0 AND " + COL_TIMESTAMP + " < ?",
                    new String[]{String.valueOf(cutoffTimestamp)});
            db.setTransactionSuccessful();
        } catch (Throwable e) {
            Log.e(TAG, "Error deleting expired clips", e);
        } finally {
            if (db != null) {
                try {
                    db.endTransaction();
                } catch (Throwable ignored) {}
            }
        }
    }

    private void cleanupOldClips(SQLiteDatabase db) {
        try {
            String subquery = "SELECT " + COL_ID + " FROM " + TABLE_NAME
                    + " WHERE " + COL_PINNED + "=0 ORDER BY " + COL_TIMESTAMP + " DESC LIMIT " + MAX_CLIPS;
            deleteClipsAndFiles(db, COL_PINNED + "=0 AND " + COL_ID + " NOT IN (" + subquery + ")", null);
        } catch (Throwable e) {
            Log.e(TAG, "Error cleaning up old clips", e);
        }
    }

    public synchronized void deleteClip(long id) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            deleteClipsAndFiles(db, COL_ID + "=?", new String[]{String.valueOf(id)});
        } catch (Throwable e) {
            Log.e(TAG, "Error deleting clip", e);
        }
    }

    public synchronized boolean setPinned(long id, boolean isPinned) {
        try {
            SQLiteDatabase db = getWritableDatabase();
            if (isPinned) {
                Cursor countCursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE_NAME + " WHERE " + COL_PINNED + "=1", null);
                int pinnedCount = 0;
                if (countCursor != null) {
                    if (countCursor.moveToFirst()) {
                        pinnedCount = countCursor.getInt(0);
                    }
                    countCursor.close();
                }
                if (pinnedCount >= MAX_PINNED_CLIPS) {
                    return false;
                }
            }
            ContentValues values = new ContentValues();
            values.put(COL_PINNED, isPinned ? 1 : 0);
            db.update(TABLE_NAME, values, COL_ID + "=?", new String[]{String.valueOf(id)});
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "Error setting clip pinned state", e);
            return false;
        }
    }

    public synchronized void clearUnpinned() {
        try {
            SQLiteDatabase db = getWritableDatabase();
            deleteClipsAndFiles(db, COL_PINNED + "=0", null);
        } catch (Throwable e) {
            Log.e(TAG, "Error clearing unpinned clips", e);
        }
    }

    public static boolean matchesQuery(final String text, final String query) {
        if (query == null || query.trim().isEmpty()) {
            return true;
        }
        if (text == null) {
            return false;
        }
        return text.toLowerCase(Locale.getDefault()).contains(query.toLowerCase(Locale.getDefault()));
    }

    public synchronized List<ClipboardHistoryEntry> getClips() {
        return getClips(null);
    }

    public synchronized List<ClipboardHistoryEntry> getClips(final String query) {
        List<ClipboardHistoryEntry> clips = new ArrayList<>();
        Cursor cursor = null;
        try {
            SQLiteDatabase db = getReadableDatabase();
            cursor = db.query(TABLE_NAME, null, null, null, null, null,
                    COL_PINNED + " DESC, " + COL_TIMESTAMP + " DESC");

            if (cursor != null && cursor.moveToFirst()) {
                final boolean filterActive = query != null && !query.trim().isEmpty();
                final String queryLower = filterActive ? query.toLowerCase(Locale.getDefault()) : null;
                do {
                    ClipboardHistoryEntry entry = ClipboardHistoryEntry.fromCursor(cursor);
                    if (entry != null) {
                        if (!filterActive) {
                            clips.add(entry);
                        } else if (entry.text != null && entry.text.toLowerCase(Locale.getDefault()).contains(queryLower)) {
                            clips.add(entry);
                        }
                    }
                } while (cursor.moveToNext());
            }
        } catch (Throwable e) {
            Log.e(TAG, "Error getting clips", e);
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {}
            }
        }
        return clips;
    }
}
