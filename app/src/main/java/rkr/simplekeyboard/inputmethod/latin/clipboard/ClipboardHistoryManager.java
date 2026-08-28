package rkr.simplekeyboard.inputmethod.latin.clipboard;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ClipboardHistoryManager implements ClipboardManager.OnPrimaryClipChangedListener {
    private static final String TAG = "ClipboardHistoryManager";
    private final Context mContext;
    private final ClipboardManager mClipboardManager;
    private final ClipboardDatabase mDatabase;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private String mLastText = null;
    private boolean mIsListening = false;

    public ClipboardHistoryManager(Context context) {
        mContext = context;
        mDatabase = new ClipboardDatabase(context);
        mClipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
    }

    public void start() {
        if (mClipboardManager != null && !mIsListening) {
            try {
                mClipboardManager.addPrimaryClipChangedListener(this);
                mIsListening = true;
                updateCurrentClip();
            } catch (Exception e) {
                Log.w(TAG, "Failed to start ClipboardHistoryManager listener", e);
            }
        }
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

    private long getRetentionMinutes() {
        android.content.SharedPreferences prefs = rkr.simplekeyboard.inputmethod.compat.PreferenceManagerCompat.getDeviceSharedPreferences(mContext);
        String val = prefs.getString(rkr.simplekeyboard.inputmethod.latin.settings.Settings.PREF_CLIPBOARD_RETENTION_TIME, "1440");
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            return 1440L;
        }
    }

    @Override
    public void onPrimaryClipChanged() {
        if (mClipboardManager == null) return;

        try {
            if (!mClipboardManager.hasPrimaryClip()) return;
            ClipData clip = mClipboardManager.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence text = clip.getItemAt(0).getText();
                if (!TextUtils.isEmpty(text)) {
                    final String currentText = text.toString();
                    if (!currentText.equals(mLastText)) {
                        mLastText = currentText;
                        final long retentionMinutes = getRetentionMinutes();
                        mExecutor.execute(() -> {
                            mDatabase.deleteExpiredClips(retentionMinutes);
                            mDatabase.insertClip(currentText);
                        });
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Error handling clipboard change", e);
        }
    }
}
