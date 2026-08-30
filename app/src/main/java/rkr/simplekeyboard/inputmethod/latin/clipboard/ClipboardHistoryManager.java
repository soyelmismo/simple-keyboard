package rkr.simplekeyboard.inputmethod.latin.clipboard;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.util.Size;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rkr.simplekeyboard.inputmethod.compat.PreferenceManagerCompat;
import rkr.simplekeyboard.inputmethod.latin.settings.Settings;

public class ClipboardHistoryManager implements ClipboardManager.OnPrimaryClipChangedListener {
    private static final String TAG = "ClipboardHistoryManager";
    public static final long CLIPBOARD_SUGGESTION_TIMEOUT_MS = 15 * 60 * 1000L; // 15 minutes
    public static final long SCREENSHOT_SUGGESTION_TIMEOUT_MS = 15 * 60 * 1000L; // 15 minutes

    public static class ScreenshotInfo {
        public final Uri uri;
        public final String fileName;
        public final String fullPath;
        public final long dateAdded;

        public ScreenshotInfo(Uri uri, String fileName, String fullPath, long dateAdded) {
            this.uri = uri;
            this.fileName = fileName;
            this.fullPath = fullPath;
            this.dateAdded = dateAdded;
        }
    }

    private final Context mContext;
    private final ClipboardManager mClipboardManager;
    private final ClipboardDatabase mDatabase;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private volatile String mLastText = null;
    private volatile long mLastTextTime = 0L;
    private volatile boolean mLastTextUsed = false;
    private boolean mIsListening = false;

    private ContentObserver mScreenshotObserver = null;
    private volatile ScreenshotInfo mCachedScreenshotInfo = null;
    private volatile boolean mLatestScreenshotUsed = false;
    private Runnable mOnScreenshotChangeListener = null;

    public ClipboardHistoryManager(Context context) {
        mContext = context;
        mDatabase = new ClipboardDatabase(context);
        mClipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
    }

    public void setOnScreenshotChangeListener(Runnable listener) {
        mOnScreenshotChangeListener = listener;
    }

    public void start() {
        if (!isHistoryEnabled()) {
            return;
        }
        if (mClipboardManager != null && !mIsListening) {
            try {
                mClipboardManager.addPrimaryClipChangedListener(this);
                mIsListening = true;
                updateCurrentClip();
            } catch (Exception e) {
                Log.w(TAG, "Failed to start ClipboardHistoryManager listener", e);
            }
        }
        registerScreenshotObserver();
        updateLatestScreenshotCache();
    }

    public void stop() {
        if (mClipboardManager != null && mIsListening) {
            try {
                mClipboardManager.removePrimaryClipChangedListener(this);
            } catch (Exception e) {
                Log.w(TAG, "Failed to stop ClipboardHistoryManager listener", e);
            }
            mIsListening = false;
        }
        unregisterScreenshotObserver();
    }

    public void close() {
        stop();
        mExecutor.shutdown();
        mDatabase.close();
    }

    public ClipboardDatabase getDatabase() {
        return mDatabase;
    }

    public void updateCurrentClip() {
        onPrimaryClipChanged();
    }

    public static boolean isSensitiveClip(ClipData clip) {
        if (clip == null) {
            return false;
        }
        ClipDescription description = clip.getDescription();
        if (description == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            PersistableBundle extras = description.getExtras();
            if (extras != null) {
                if (extras.getBoolean("android.content.extra.IS_SENSITIVE", false)
                        || extras.getBoolean("android.content.extra.IS_CONFIDENTIAL", false)
                        || extras.getBoolean("android.content.extra.IS_PASSWORD", false)) {
                    return true;
                }
            }
        }
        return false;
    }

    public String getLatestClipText() {
        if (mClipboardManager != null) {
            try {
                if (mClipboardManager.hasPrimaryClip()) {
                    final ClipData clip = mClipboardManager.getPrimaryClip();
                    if (isSensitiveClip(clip)) {
                        return null;
                    }
                    final String currentText = processPrimaryClip(clip);
                    if (currentText != null) {
                        return currentText;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Error getting latest clip text", e);
            }
        }
        return mLastText;
    }

    private String processPrimaryClip(ClipData clip) {
        if (clip == null || clip.getItemCount() == 0) {
            return null;
        }
        if (isSensitiveClip(clip)) {
            mLastText = null;
            mLastTextTime = 0L;
            mLastTextUsed = true;
            return null;
        }
        ClipData.Item item = clip.getItemAt(0);
        CharSequence text = item != null ? item.coerceToText(mContext) : null;
        if (TextUtils.isEmpty(text)) {
            return null;
        }
        final String currentText = text.toString();
        long clipTimestamp = System.currentTimeMillis();
        try {
            if (clip.getDescription() != null && clip.getDescription().getTimestamp() > 0) {
                clipTimestamp = clip.getDescription().getTimestamp();
            }
        } catch (Throwable ignored) {}

        storeClipTextIfChanged(currentText, clipTimestamp);
        return currentText;
    }

    private void storeClipTextIfChanged(final String currentText, final long clipTimestamp) {
        if (!isHistoryEnabled()) {
            return;
        }
        if (!currentText.equals(mLastText)) {
            mLastText = currentText;
            mLastTextTime = clipTimestamp;
            mLastTextUsed = false;
            final long retentionMinutes = getRetentionMinutes();
            mExecutor.execute(() -> {
                mDatabase.deleteExpiredClips(retentionMinutes);
                if (retentionMinutes <= 0 || (System.currentTimeMillis() - clipTimestamp <= retentionMinutes * 60 * 1000L)) {
                    mDatabase.insertClip(currentText, false, clipTimestamp);
                }
            });
        } else if (mLastTextTime <= 0) {
            mLastTextTime = clipTimestamp;
        }
    }

    public String getRecentClipForSuggestion() {
        if (!isHistoryEnabled()) {
            return null;
        }
        final String clip = getLatestClipText();
        if (clip == null || clip.trim().isEmpty()) {
            return null;
        }
        if (mLastTextUsed) {
            return null;
        }
        final long now = System.currentTimeMillis();
        long maxTimeout = CLIPBOARD_SUGGESTION_TIMEOUT_MS;
        final long retentionMinutes = getRetentionMinutes();
        if (retentionMinutes > 0) {
            maxTimeout = Math.min(maxTimeout, retentionMinutes * 60 * 1000L);
        }
        if (mLastTextTime <= 0 || (now - mLastTextTime > maxTimeout)) {
            return null;
        }
        return clip;
    }

    public void markLatestClipUsed() {
        mLastTextUsed = true;
    }

    public ScreenshotInfo getRecentScreenshotForSuggestion() {
        if (!isHistoryEnabled() || !isScreenshotSuggestionEnabled()) {
            return null;
        }
        final ScreenshotInfo info = mCachedScreenshotInfo;
        if (info == null) {
            updateLatestScreenshotCache();
            return null;
        }
        if (mLatestScreenshotUsed) {
            return null;
        }
        final long now = System.currentTimeMillis();
        if (info.dateAdded <= 0 || (now - info.dateAdded > SCREENSHOT_SUGGESTION_TIMEOUT_MS)) {
            return null;
        }
        return info;
    }

    public void markLatestScreenshotUsed() {
        mLatestScreenshotUsed = true;
    }

    private boolean isScreenshotSuggestionEnabled() {
        android.content.SharedPreferences prefs = PreferenceManagerCompat.getDeviceSharedPreferences(mContext);
        return Settings.readSuggestScreenshotsEnabled(prefs);
    }

    private boolean hasStoragePermission() {
        final String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        return mContext.checkCallingOrSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    public void registerScreenshotObserver() {
        if (mScreenshotObserver != null || !hasStoragePermission()) {
            return;
        }
        try {
            mScreenshotObserver = new ContentObserver(mMainHandler) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    super.onChange(selfChange, uri);
                    updateLatestScreenshotCache();
                }
            };
            mContext.getContentResolver().registerContentObserver(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    true,
                    mScreenshotObserver);
        } catch (Exception e) {
            Log.w(TAG, "Failed to register screenshot observer", e);
        }
    }

    public void unregisterScreenshotObserver() {
        if (mScreenshotObserver != null) {
            try {
                mContext.getContentResolver().unregisterContentObserver(mScreenshotObserver);
            } catch (Exception ignored) {}
            mScreenshotObserver = null;
        }
    }

    public void updateLatestScreenshotCache() {
        if (!isHistoryEnabled() || !hasStoragePermission()) {
            mCachedScreenshotInfo = null;
            return;
        }

        mExecutor.execute(() -> {
            List<String> projectionList = new ArrayList<>();
            projectionList.add(MediaStore.Images.Media._ID);
            projectionList.add(MediaStore.Images.Media.DISPLAY_NAME);
            projectionList.add(MediaStore.Images.Media.DATE_ADDED);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                projectionList.add(MediaStore.Images.Media.RELATIVE_PATH);
            } else {
                projectionList.add(MediaStore.Images.Media.DATA);
            }

            final String sortOrder = MediaStore.Images.Media.DATE_ADDED + " DESC";
            Cursor cursor = null;
            try {
                cursor = mContext.getContentResolver().query(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        projectionList.toArray(new String[0]),
                        null,
                        null,
                        sortOrder
                );

                if (cursor != null) {
                    int count = 0;
                    while (cursor.moveToNext() && count < 10) {
                        count++;
                        int dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED);
                        long dateAdded = cursor.getLong(dateIndex) * 1000L;
                        long diff = System.currentTimeMillis() - dateAdded;

                        if (diff < SCREENSHOT_SUGGESTION_TIMEOUT_MS) {
                            int nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
                            String fileName = cursor.getString(nameIndex);
                            if (fileName == null) fileName = "";

                            String fullPath = "";
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                int relIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH);
                                fullPath = cursor.getString(relIndex);
                            } else {
                                int dataIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
                                fullPath = cursor.getString(dataIndex);
                            }
                            if (fullPath == null) fullPath = "";

                            boolean isScreenshot = isScreenshotPath(fileName, fullPath);
                            if (isScreenshot) {
                                int idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                                long id = cursor.getLong(idIndex);
                                Uri contentUri = ContentUris.withAppendedId(
                                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);

                                String cachedPath = cacheImage(contentUri);
                                String targetPath = cachedPath != null ? cachedPath : contentUri.toString();

                                if (mCachedScreenshotInfo == null || !mCachedScreenshotInfo.uri.equals(contentUri)) {
                                    mCachedScreenshotInfo = new ScreenshotInfo(contentUri, fileName, targetPath, dateAdded);
                                    mLatestScreenshotUsed = false;

                                    final long retentionMinutes = getRetentionMinutes();
                                    mDatabase.deleteExpiredClips(retentionMinutes);
                                    mDatabase.insertClip("[Screenshot]", false, dateAdded, targetPath);

                                    mMainHandler.post(() -> {
                                        if (mOnScreenshotChangeListener != null) {
                                            mOnScreenshotChangeListener.run();
                                        }
                                    });
                                }
                                return;
                            }
                        } else {
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to query recent screenshots", e);
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        });
    }

    private boolean isScreenshotPath(String fileName, String fullPath) {
        String fn = fileName.toLowerCase();
        String fp = fullPath.toLowerCase();
        return fn.contains("screenshot") || fn.contains("screen") || fn.contains("captura")
                || fp.contains("screenshot") || fp.contains("screen") || fp.contains("captura")
                || fp.contains("pictures") || fp.contains("dcim");
    }

    public String cacheImage(Uri uri) {
        try {
            File cacheDir = new File(mContext.getCacheDir(), "clipboard_images");
            if (!cacheDir.exists()) {
                cacheDir.mkdirs();
            }
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(uri.toString().getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            File file = new File(cacheDir, "img_" + sb.toString() + ".jpg");
            if (file.exists() && file.length() > 0) {
                return file.getAbsolutePath();
            }

            try (InputStream in = mContext.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(file)) {
                if (in == null) return null;
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();
                return file.getAbsolutePath();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to cache image", e);
            return null;
        }
    }

    public Bitmap getScreenshotThumbnail(ScreenshotInfo info) {
        if (info == null) return null;
        if (info.fullPath != null && info.fullPath.startsWith("/")) {
            try {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 8;
                return BitmapFactory.decodeFile(info.fullPath, options);
            } catch (Throwable ignored) {}
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.uri != null) {
            try {
                return mContext.getContentResolver().loadThumbnail(info.uri, new Size(120, 120), null);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private boolean isHistoryEnabled() {
        android.content.SharedPreferences prefs = PreferenceManagerCompat.getDeviceSharedPreferences(mContext);
        return Settings.readClipboardHistoryEnabled(prefs);
    }

    private long getRetentionMinutes() {
        android.content.SharedPreferences prefs = PreferenceManagerCompat.getDeviceSharedPreferences(mContext);
        return Settings.readClipboardRetentionMinutes(prefs);
    }

    @Override
    public void onPrimaryClipChanged() {
        if (mClipboardManager == null || !isHistoryEnabled()) return;

        try {
            if (mClipboardManager.hasPrimaryClip()) {
                processPrimaryClip(mClipboardManager.getPrimaryClip());
            }
        } catch (Exception e) {
            Log.w(TAG, "Error handling clipboard change", e);
        }
    }
}
