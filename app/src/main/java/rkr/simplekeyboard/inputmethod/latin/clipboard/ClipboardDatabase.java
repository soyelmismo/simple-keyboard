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

    private final Context mContext;

    public ClipboardDatabase(Context context) {
        super(rkr.simplekeyboard.inputmethod.compat.PreferenceManagerCompat.getDeviceContext(context), DATABASE_NAME, null, DATABASE_VERSION);
        mContext = rkr.simplekeyboard.inputmethod.compat.PreferenceManagerCompat.getDeviceContext(context);
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

    private boolean isEmptyString(final String str) {
        return str == null || str.trim().isEmpty();
    }

    private boolean isInvalidClipInput(final String text, final String uri) {
        return isEmptyString(text) && isEmptyString(uri);
    }

    private String sanitizeClipText(final String text) {
        if (text != null && text.length() > MAX_TEXT_LENGTH) {
            return text.substring(0, MAX_TEXT_LENGTH);
        }
        return text;
    }

    private String getClipQueryKey(final String text, final String uri) {
        return (uri != null && !uri.isEmpty()) ? uri : text;
    }

    private String getClipWhereClause(final String uri) {
        return (uri != null && !uri.isEmpty()) ? (COL_URI + "=?") : (COL_TEXT + "=?");
    }

    private boolean isClipAlreadyPinned(final SQLiteDatabase db, final String whereClause, final String queryKey) {
        Cursor cursor = null;
        try {
            cursor = db.query(TABLE_NAME, new String[]{COL_PINNED}, whereClause,
                    new String[]{queryKey}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getInt(0) == 1;
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return false;
    }

    private ContentValues buildClipContentValues(final String text, final boolean pinned, final long timestamp, final String uri) {
        final ContentValues values = new ContentValues();
        values.put(COL_TEXT, text != null ? text : "[Screenshot]");
        values.put(COL_TIMESTAMP, timestamp);
        values.put(COL_PINNED, pinned ? 1 : 0);
        if (uri != null) {
            values.put(COL_URI, uri);
        }
        return values;
    }

    private void executeInsertClipTransaction(final SQLiteDatabase db, final String text, final boolean pinned, final long timestamp, final String uri) {
        final String whereClause = getClipWhereClause(uri);
        final String queryKey = getClipQueryKey(text, uri);
        final boolean isPinned = pinned || isClipAlreadyPinned(db, whereClause, queryKey);

        // Remove existing row to refresh timestamp and position
        db.delete(TABLE_NAME, whereClause, new String[]{queryKey});

        final ContentValues values = buildClipContentValues(text, isPinned, timestamp, uri);
        db.insert(TABLE_NAME, null, values);

        cleanupOldClips(db);
        db.setTransactionSuccessful();
    }

    private void safeEndTransaction(final SQLiteDatabase db) {
        if (db != null) {
            try {
                db.endTransaction();
            } catch (Throwable ignored) {}
        }
    }

    public synchronized void insertClip(String text, boolean pinned, long timestamp, String uri) {
        if (isInvalidClipInput(text, uri)) {
            return;
        }
        final String sanitizedText = sanitizeClipText(text);
        final long validTimestamp = timestamp <= 0 ? System.currentTimeMillis() : timestamp;

        SQLiteDatabase db = null;
        try {
            db = getWritableDatabase();
            db.beginTransaction();
            executeInsertClipTransaction(db, sanitizedText, pinned, validTimestamp, uri);
        } catch (Throwable e) {
            Log.e(TAG, "Error inserting clip", e);
        } finally {
            safeEndTransaction(db);
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

    private int getMaxClips() {
        if (mContext == null) {
            return MAX_CLIPS;
        }
        try {
            android.content.SharedPreferences prefs =
                    rkr.simplekeyboard.inputmethod.compat.PreferenceManagerCompat.getDeviceSharedPreferences(mContext);
            return rkr.simplekeyboard.inputmethod.latin.settings.Settings.readClipboardMaxClips(prefs);
        } catch (Throwable ignored) {
            return MAX_CLIPS;
        }
    }

    private void cleanupOldClips(SQLiteDatabase db) {
        try {
            final int maxClips = getMaxClips();
            String subquery = "SELECT " + COL_ID + " FROM " + TABLE_NAME
                    + " WHERE " + COL_PINNED + "=0 ORDER BY " + COL_TIMESTAMP + " DESC LIMIT " + maxClips;
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
                do {
                    ClipboardHistoryEntry entry = ClipboardHistoryEntry.fromCursor(cursor);
                    if (entry != null && matchesQuery(entry.text, query)) {
                        clips.add(entry);
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
