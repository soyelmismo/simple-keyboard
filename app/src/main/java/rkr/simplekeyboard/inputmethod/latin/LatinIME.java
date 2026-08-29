/*
 * Copyright (C) 2008 The Android Open Source Project
 * Copyright (C) 2025 Raimondas Rimkus
 * Copyright (C) 2021 wittmane
 * Copyright (C) 2021 Maarten Trompper
 * Copyright (C) 2019 Micha LaQua
 * Copyright (C) 2019 Emmanuel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rkr.simplekeyboard.inputmethod.latin;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.inputmethodservice.InputMethodService;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.IBinder;
import android.os.Message;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InlineSuggestion;
import android.view.inputmethod.InlineSuggestionsRequest;
import android.view.inputmethod.InlineSuggestionsResponse;
import android.content.ClipDescription;
import android.graphics.Bitmap;
import android.net.Uri;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.content.FileProvider;
import androidx.core.view.inputmethod.InputConnectionCompat;
import androidx.core.view.inputmethod.InputContentInfoCompat;
import rkr.simplekeyboard.inputmethod.R;

import java.io.File;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import rkr.simplekeyboard.inputmethod.compat.EditorInfoCompatUtils;
import rkr.simplekeyboard.inputmethod.compat.PreferenceManagerCompat;
import rkr.simplekeyboard.inputmethod.event.Event;
import rkr.simplekeyboard.inputmethod.event.InputTransaction;
import rkr.simplekeyboard.inputmethod.keyboard.Keyboard;
import rkr.simplekeyboard.inputmethod.keyboard.KeyboardActionListener;
import rkr.simplekeyboard.inputmethod.keyboard.KeyboardId;
import rkr.simplekeyboard.inputmethod.keyboard.KeyboardSwitcher;
import rkr.simplekeyboard.inputmethod.keyboard.MainKeyboardView;
import rkr.simplekeyboard.inputmethod.latin.clipboard.ClipboardHistoryManager;
import rkr.simplekeyboard.inputmethod.latin.clipboard.ClipboardHistoryView;
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiPalettesView;
import rkr.simplekeyboard.inputmethod.latin.dict.PrefixDictionary;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import rkr.simplekeyboard.inputmethod.latin.common.Constants;
import rkr.simplekeyboard.inputmethod.latin.define.DebugFlags;
import rkr.simplekeyboard.inputmethod.latin.inputlogic.InputLogic;
import rkr.simplekeyboard.inputmethod.latin.settings.Settings;
import rkr.simplekeyboard.inputmethod.latin.settings.SettingsActivity;
import rkr.simplekeyboard.inputmethod.latin.settings.SettingsValues;
import rkr.simplekeyboard.inputmethod.latin.topbar.TopBarListener;
import rkr.simplekeyboard.inputmethod.latin.topbar.TopBarView;
import rkr.simplekeyboard.inputmethod.latin.utils.ApplicationUtils;
import rkr.simplekeyboard.inputmethod.latin.utils.LeakGuardHandlerWrapper;
import rkr.simplekeyboard.inputmethod.latin.utils.ResourceUtils;
import rkr.simplekeyboard.inputmethod.latin.utils.ViewLayoutUtils;

/**
 * Input method implementation for Qwerty'ish keyboard.
 */
public class LatinIME extends InputMethodService implements KeyboardActionListener,
        RichInputMethodManager.SubtypeChangedListener {
    static final String TAG = LatinIME.class.getSimpleName();
    private static final boolean TRACE = false;

    private static final int EXTENDED_TOUCHABLE_REGION_HEIGHT = 100;
    private static final int PERIOD_FOR_AUDIO_AND_HAPTIC_FEEDBACK_IN_KEY_REPEAT = 2;
    private static final int PENDING_IMS_CALLBACK_DURATION_MILLIS = 800;
    static final long DELAY_DEALLOCATE_MEMORY_MILLIS = TimeUnit.SECONDS.toMillis(10);

    final Settings mSettings;
    private Locale mLocale;
    final InputLogic mInputLogic = new InputLogic(this /* LatinIME */);

    // TODO: Move these {@link View}s to {@link KeyboardSwitcher}.
    private View mInputView;
    private TopBarView mTopBarView;
    private ClipboardHistoryView mClipboardHistoryView;
    private EmojiPalettesView mEmojiPalettesView;
    private ClipboardHistoryManager mClipboardHistoryManager;
    private final PrefixDictionary mPrefixDictionary = new PrefixDictionary();
    private final java.util.concurrent.ExecutorService mDictExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private Locale mLoadedLocale;

    public final rkr.simplekeyboard.inputmethod.latin.dict.spatial.SpatialTouchModel mSpatialTouchModel = new rkr.simplekeyboard.inputmethod.latin.dict.spatial.SpatialTouchModel();
    public rkr.simplekeyboard.inputmethod.latin.dict.binary.BinaryTrieDictionary mBinaryTrieDictionary;
    public rkr.simplekeyboard.inputmethod.latin.dict.decoder.BeamSearchDecoder mBeamSearchDecoder;

    private String mOriginalTypedWordBeforeAutocorrect = null;
    private String mAutocorrectedWord = null;
    private rkr.simplekeyboard.inputmethod.latin.dict.ContactsDictionary mContactsDictionary;
    private boolean mCanRevertAutocorrect = false;
    private int mLastInlineFieldId = 0;

    private RichInputMethodManager mRichImm;
    final KeyboardSwitcher mKeyboardSwitcher;

    private AlertDialog mOptionsDialog;

    public final UIHandler mHandler = new UIHandler(this);

    public static final class UIHandler extends LeakGuardHandlerWrapper<LatinIME> {
        private static final int MSG_UPDATE_SHIFT_STATE = 0;
        private static final int MSG_PENDING_IMS_CALLBACK = 1;
        private static final int MSG_DEALLOCATE_MEMORY = 9;

        public UIHandler(final LatinIME ownerInstance) {
            super(ownerInstance);
        }

        @Override
        public void handleMessage(final Message msg) {
            final LatinIME latinIme = getOwnerInstance();
            if (latinIme == null) {
                return;
            }
            final KeyboardSwitcher switcher = latinIme.mKeyboardSwitcher;
            switch (msg.what) {
            case MSG_UPDATE_SHIFT_STATE:
                switcher.requestUpdatingShiftState(latinIme.getCurrentAutoCapsState(),
                        latinIme.getCurrentRecapitalizeState());
                break;
            case MSG_DEALLOCATE_MEMORY:
                latinIme.deallocateMemory();
                break;
            }
        }

        public void postUpdateShiftState() {
            removeMessages(MSG_UPDATE_SHIFT_STATE);
            sendMessage(obtainMessage(MSG_UPDATE_SHIFT_STATE));
        }

        public void postDeallocateMemory() {
            sendMessageDelayed(obtainMessage(MSG_DEALLOCATE_MEMORY),
                    DELAY_DEALLOCATE_MEMORY_MILLIS);
        }

        public void cancelDeallocateMemory() {
            removeMessages(MSG_DEALLOCATE_MEMORY);
        }

        public boolean hasPendingDeallocateMemory() {
            return hasMessages(MSG_DEALLOCATE_MEMORY);
        }

        // Working variables for the following methods.
        private boolean mIsOrientationChanging;
        private boolean mPendingSuccessiveImsCallback;
        private boolean mHasPendingStartInput;
        private boolean mHasPendingFinishInputView;
        private boolean mHasPendingFinishInput;
        private EditorInfo mAppliedEditorInfo;

        private void resetPendingImsCallback() {
            mHasPendingFinishInputView = false;
            mHasPendingFinishInput = false;
            mHasPendingStartInput = false;
        }

        private void executePendingImsCallback(final LatinIME latinIme, final EditorInfo editorInfo,
                boolean restarting) {
            if (mHasPendingFinishInputView) {
                latinIme.onFinishInputViewInternal(mHasPendingFinishInput);
            }
            if (mHasPendingFinishInput) {
                latinIme.onFinishInputInternal();
            }
            if (mHasPendingStartInput) {
                latinIme.onStartInputInternal(editorInfo, restarting);
            }
            resetPendingImsCallback();
        }

        public void onStartInput(final EditorInfo editorInfo, final boolean restarting) {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)) {
                // Typically this is the second onStartInput after orientation changed.
                mHasPendingStartInput = true;
            } else {
                if (mIsOrientationChanging && restarting) {
                    // This is the first onStartInput after orientation changed.
                    mIsOrientationChanging = false;
                    mPendingSuccessiveImsCallback = true;
                }
                final LatinIME latinIme = getOwnerInstance();
                if (latinIme != null) {
                    executePendingImsCallback(latinIme, editorInfo, restarting);
                    latinIme.onStartInputInternal(editorInfo, restarting);
                }
            }
        }

        public void onStartInputView(final EditorInfo editorInfo, final boolean restarting) {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)
                    && KeyboardId.equivalentEditorInfoForKeyboard(editorInfo, mAppliedEditorInfo)) {
                // Typically this is the second onStartInputView after orientation changed.
                resetPendingImsCallback();
            } else {
                if (mPendingSuccessiveImsCallback) {
                    // This is the first onStartInputView after orientation changed.
                    mPendingSuccessiveImsCallback = false;
                    resetPendingImsCallback();
                    sendMessageDelayed(obtainMessage(MSG_PENDING_IMS_CALLBACK),
                            PENDING_IMS_CALLBACK_DURATION_MILLIS);
                }
                final LatinIME latinIme = getOwnerInstance();
                if (latinIme != null) {
                    executePendingImsCallback(latinIme, editorInfo, restarting);
                    latinIme.onStartInputViewInternal(editorInfo, restarting);
                    mAppliedEditorInfo = editorInfo;
                }
                cancelDeallocateMemory();
            }
        }

        public void onFinishInputView(final boolean finishingInput) {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)) {
                // Typically this is the first onFinishInputView after orientation changed.
                mHasPendingFinishInputView = true;
            } else {
                final LatinIME latinIme = getOwnerInstance();
                if (latinIme != null) {
                    latinIme.onFinishInputViewInternal(finishingInput);
                    mAppliedEditorInfo = null;
                }
                if (!hasPendingDeallocateMemory()) {
                    postDeallocateMemory();
                }
            }
        }

        public void onFinishInput() {
            if (hasMessages(MSG_PENDING_IMS_CALLBACK)) {
                // Typically this is the first onFinishInput after orientation changed.
                mHasPendingFinishInput = true;
            } else {
                final LatinIME latinIme = getOwnerInstance();
                if (latinIme != null) {
                    executePendingImsCallback(latinIme, null, false);
                    latinIme.onFinishInputInternal();
                }
            }
        }
    }

    public LatinIME() {
        super();
        mSettings = Settings.getInstance();
        mKeyboardSwitcher = KeyboardSwitcher.getInstance();
    }

    private Context mDisplayContext;

    private Context createOrGetDisplayContext() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) {
            return this;
        }
        final WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (wm != null && wm.getDefaultDisplay() != null) {
            return createDisplayContext(wm.getDefaultDisplay());
        }
        return this;
    }

    @Override
    public void onInitializeInterface() {
        super.onInitializeInterface();
        mDisplayContext = createOrGetDisplayContext();
    }

    @Override
    public void onCreate() {
        mDisplayContext = createOrGetDisplayContext();
        Settings.init(this);
        DebugFlags.init(PreferenceManagerCompat.getDeviceSharedPreferences(this));
        RichInputMethodManager.init(this);
        mRichImm = RichInputMethodManager.getInstance();
        mRichImm.setSubtypeChangeHandler(this);
        KeyboardSwitcher.init(this);
        AudioAndHapticFeedbackManager.init(this);
        super.onCreate();

        mClipboardHistoryManager = new ClipboardHistoryManager(this);
        mClipboardHistoryManager.setOnScreenshotChangeListener(this::updateSuggestions);
        mClipboardHistoryManager.start();

        mContactsDictionary = new rkr.simplekeyboard.inputmethod.latin.dict.ContactsDictionary(this);

        // TODO: Resolve mutual dependencies of {@link #loadSettings()} and
        // {@link #resetDictionaryFacilitatorIfNecessary()}.
        loadSettings();
        loadContactsIfEnabled();

        // Register to receive ringer mode change.
        final IntentFilter filter = new IntentFilter();
        filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        registerReceiver(mRingerModeChangeReceiver, filter);
    }

    private void loadContactsIfEnabled() {
        if (mContactsDictionary != null && mSettings.getCurrent() != null && mSettings.getCurrent().mUseContacts) {
            mContactsDictionary.loadAsync(mDictExecutor, this::updateSuggestions);
        }
    }

    private void loadSettings() {
        mLocale = mRichImm.getCurrentSubtype().getLocaleObject();
        final EditorInfo editorInfo = getCurrentInputEditorInfo();
        final InputAttributes inputAttributes = new InputAttributes(editorInfo, isFullscreenMode());
        mSettings.loadSettings(inputAttributes);
        rkr.simplekeyboard.inputmethod.keyboard.KeyboardLayoutSet.clearKeyboardCache();
        final SettingsValues currentSettingsValues = mSettings.getCurrent();
        AudioAndHapticFeedbackManager.getInstance().onSettingsChanged(currentSettingsValues);
        mPrefixDictionary.setAutoCorrectionThreshold(currentSettingsValues.mAutoCorrectionThreshold);
        loadDictionaryForLocale(mLocale);
    }

    @Override
    public void onDestroy() {
        if (mClipboardHistoryManager != null) {
            mClipboardHistoryManager.close();
            mClipboardHistoryManager = null;
        }
        mDictExecutor.shutdownNow();
        mSettings.onDestroy();
        unregisterReceiver(mRingerModeChangeReceiver);
        super.onDestroy();
    }

    private boolean isImeSuppressedByHardwareKeyboard() {
        final KeyboardSwitcher switcher = KeyboardSwitcher.getInstance();
        return !onEvaluateInputViewShown() && switcher.isImeSuppressedByHardwareKeyboard(
                mSettings.getCurrent(), switcher.getKeyboardSwitchState());
    }

    @Override
    public boolean onEvaluateInputViewShown() {
        final boolean useOnScreen = super.onEvaluateInputViewShown();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
            return useOnScreen;
        } else {
            return useOnScreen || mSettings.getCurrent().mUseOnScreen;
        }
    }

    @Override
    public void onConfigurationChanged(final Configuration conf) {
        mDisplayContext = createOrGetDisplayContext();
        SettingsValues settingsValues = mSettings.getCurrent();
        if (settingsValues.mHasHardwareKeyboard != Settings.readHasHardwareKeyboard(conf)) {
            // If the state of having a hardware keyboard changed, then we want to reload the
            // settings to adjust for that.
            // TODO: we should probably do this unconditionally here, rather than only when we
            // have a change in hardware keyboard configuration.
            loadSettings();
        }

        mKeyboardSwitcher.onConfigurationChanged();

        super.onConfigurationChanged(conf);
    }

    @Override
    public View onCreateInputView() {
        return mKeyboardSwitcher.onCreateInputView();
    }

    private rkr.simplekeyboard.inputmethod.latin.utils.InsetsOutlineProvider mInsetsUpdater;

    @Override
    public void setInputView(final View view) {
        super.setInputView(view);
        mInputView = view;
        mInsetsUpdater = new rkr.simplekeyboard.inputmethod.latin.utils.InsetsOutlineProvider(view);
        updateSoftInputWindowLayoutParameters();
        view.requestApplyInsets();

        if (mInputView != null) {
            mTopBarView = mInputView.findViewById(rkr.simplekeyboard.inputmethod.R.id.top_bar_view);
            mClipboardHistoryView = mInputView.findViewById(rkr.simplekeyboard.inputmethod.R.id.clipboard_history_view);
            mEmojiPalettesView = mInputView.findViewById(rkr.simplekeyboard.inputmethod.R.id.emoji_palettes_view);

            if (mTopBarView != null) {
                mTopBarView.setListener(new TopBarListener() {
                    @Override
                    public void onSettingsClicked() {
                        launchSettings();
                    }

                    @Override
                    public void onLanguageClicked() {
                        switchToNextSubtype();
                    }

                    @Override
                    public void onClipboardClicked() {
                        showClipboardHistory();
                    }

                    @Override
                    public void onEmojiClicked() {
                        showEmojiView();
                    }

                    @Override
                    public void onSuggestionClicked(CharSequence text) {
                        String cleanWord = "";
                        if (text != null && text.length() > 0) {
                            cleanWord = text.toString().trim();
                            if (cleanWord.startsWith("\"") && cleanWord.endsWith("\"") && cleanWord.length() >= 2) {
                                cleanWord = cleanWord.substring(1, cleanWord.length() - 1).trim();
                            }
                            if (cleanWord.length() > 0) {
                                final String[] context = getEffectivePreviousWords();
                                recordCommittedWord(cleanWord, context[0], context[1]);
                            }
                        }
                        final String currentWord = mInputLogic.mConnection.getWordBeforeCursor();
                        if (currentWord == null || currentWord.isEmpty()) {
                            mInputLogic.mConnection.commitText(cleanWord + " ", 1);
                        } else {
                            mInputLogic.mConnection.commitSuggestion(text);
                        }
                        updateSuggestions();
                    }

                    @Override
                    public void onClipboardSuggestionClicked(String fullClipText) {
                        if (fullClipText != null && !fullClipText.isEmpty()) {
                            mInputLogic.mConnection.commitText(fullClipText, 1);
                            if (mClipboardHistoryManager != null) {
                                mClipboardHistoryManager.markLatestClipUsed();
                            }
                            updateSuggestions();
                        }
                    }

                    @Override
                    public void onScreenshotSuggestionClicked(String imageUri) {
                        if (imageUri != null && !imageUri.isEmpty()) {
                            onImageSelected(imageUri);
                            if (mClipboardHistoryManager != null) {
                                mClipboardHistoryManager.markLatestScreenshotUsed();
                            }
                            updateSuggestions();
                        }
                    }
                });
            }

            if (mClipboardHistoryView != null) {
                mClipboardHistoryView.setListener(new ClipboardHistoryView.ClipboardHistoryListener() {
                    @Override
                    public void onPasteText(CharSequence text) {
                        mInputLogic.mConnection.commitText(text, 1);
                        if (mClipboardHistoryManager != null) {
                            mClipboardHistoryManager.markLatestClipUsed();
                        }
                        hideClipboardHistory();
                        updateSuggestions();
                    }

                    @Override
                    public void onPasteImage(String imageUri) {
                        if (imageUri != null && !imageUri.isEmpty()) {
                            onImageSelected(imageUri);
                            if (mClipboardHistoryManager != null) {
                                mClipboardHistoryManager.markLatestScreenshotUsed();
                            }
                            hideClipboardHistory();
                            updateSuggestions();
                        }
                    }

                    @Override
                    public void onCloseClipboard() {
                        hideClipboardHistory();
                    }
                });
            }

            if (mEmojiPalettesView != null) {
                mEmojiPalettesView.setListener(new EmojiPalettesView.EmojiListener() {
                    @Override
                    public void onSelectEmoji(String emoji) {
                        mInputLogic.mConnection.commitText(emoji, 1);
                        updateSuggestions();
                    }

                    @Override
                    public void onDeleteEmoji() {
                        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
                    }

                    @Override
                    public void onCloseEmoji() {
                        hideEmojiView();
                    }
                });
            }
        }
    }

    public void showClipboardHistory() {
        if (mClipboardHistoryView == null || mInputView == null) {
            return;
        }
        hideEmojiView();
        if (mClipboardHistoryManager != null) {
            mClipboardHistoryManager.updateCurrentClip();
            mClipboardHistoryView.setDatabase(mClipboardHistoryManager.getDatabase());
        }
        final int topBarHeight = (mTopBarView != null && mTopBarView.getVisibility() == View.VISIBLE) ? mTopBarView.getHeight() : 0;
        final View visibleKeyboardView = mKeyboardSwitcher.getVisibleKeyboardView();
        final int keyboardHeight = (visibleKeyboardView != null && visibleKeyboardView.getVisibility() == View.VISIBLE) ? visibleKeyboardView.getHeight() : 0;
        final int totalHeight = topBarHeight + keyboardHeight;
        if (totalHeight > 0) {
            mClipboardHistoryView.setTargetHeight(totalHeight);
        }

        if (mTopBarView != null) {
            mTopBarView.setVisibility(View.GONE);
        }
        if (visibleKeyboardView != null) {
            visibleKeyboardView.setVisibility(View.GONE);
        }

        mClipboardHistoryView.reloadClips();
        mClipboardHistoryView.setVisibility(View.VISIBLE);
        mInputView.requestLayout();
    }

    public void hideClipboardHistory() {
        if (mClipboardHistoryView != null && mClipboardHistoryView.getVisibility() == View.VISIBLE) {
            mClipboardHistoryView.setVisibility(View.GONE);
            if (mTopBarView != null) {
                mTopBarView.setVisibility(View.VISIBLE);
                mTopBarView.setMode(TopBarView.MODE_NORMAL);
            }
            final View keyboardView = mKeyboardSwitcher.getMainKeyboardView();
            if (keyboardView != null) {
                keyboardView.setVisibility(View.VISIBLE);
            }
            if (mInputView != null) {
                mInputView.requestLayout();
            }
        }
    }

    public void showEmojiView() {
        if (mEmojiPalettesView == null || mInputView == null) {
            return;
        }
        hideClipboardHistory();
        final int topBarHeight = (mTopBarView != null && mTopBarView.getVisibility() == View.VISIBLE) ? mTopBarView.getHeight() : 0;
        final View visibleKeyboardView = mKeyboardSwitcher.getVisibleKeyboardView();
        final int keyboardHeight = (visibleKeyboardView != null && visibleKeyboardView.getVisibility() == View.VISIBLE) ? visibleKeyboardView.getHeight() : 0;
        final int totalHeight = topBarHeight + keyboardHeight;
        if (totalHeight > 0) {
            mEmojiPalettesView.setTargetHeight(totalHeight);
        }

        if (mTopBarView != null) {
            mTopBarView.setVisibility(View.GONE);
        }
        if (visibleKeyboardView != null) {
            visibleKeyboardView.setVisibility(View.GONE);
        }

        mEmojiPalettesView.setVisibility(View.VISIBLE);
        mInputView.requestLayout();
    }

    public void hideEmojiView() {
        if (mEmojiPalettesView != null && mEmojiPalettesView.getVisibility() == View.VISIBLE) {
            mEmojiPalettesView.setVisibility(View.GONE);
            if (mTopBarView != null) {
                mTopBarView.setVisibility(View.VISIBLE);
                mTopBarView.setMode(TopBarView.MODE_NORMAL);
            }
            final View keyboardView = mKeyboardSwitcher.getMainKeyboardView();
            if (keyboardView != null) {
                keyboardView.setVisibility(View.VISIBLE);
            }
            if (mInputView != null) {
                mInputView.requestLayout();
            }
        }
    }

    @Override
    public void setCandidatesView(final View view) {
        // To ensure that CandidatesView will never be set.
    }

    @Override
    public void onStartInput(final EditorInfo editorInfo, final boolean restarting) {
        mHandler.onStartInput(editorInfo, restarting);
    }

    @Override
    public void onStartInputView(final EditorInfo editorInfo, final boolean restarting) {
        mHandler.onStartInputView(editorInfo, restarting);
    }

    @Override
    public void onFinishInputView(final boolean finishingInput) {
        mCanRevertAutocorrect = false;
        mInputLogic.clearCaches();
        mRichImm.resetSubtypeCycleOrder();
        mHandler.onFinishInputView(finishingInput);
    }

    @Override
    public void onFinishInput() {
        mHandler.onFinishInput();
    }

    @Override
    public void onCurrentSubtypeChanged() {
        mInputLogic.onSubtypeChanged();
        loadKeyboard();
        loadDictionaryForLocale(mLocale);
    }

    void onStartInputInternal(final EditorInfo editorInfo, final boolean restarting) {
        super.onStartInput(editorInfo, restarting);
    }

    void onStartInputViewInternal(final EditorInfo editorInfo, final boolean restarting) {
        super.onStartInputView(editorInfo, restarting);

        // Switch to the null consumer to handle cases leading to early exit below, for which we
        // also wouldn't be consuming gesture data.
        mCanRevertAutocorrect = false;
        mOriginalTypedWordBeforeAutocorrect = null;
        mAutocorrectedWord = null;
        if (mTopBarView != null) {
            mTopBarView.closeToolTray();
            if (mTopBarView.isExternalViewActive()) {
                mTopBarView.setExternalView(null);
            }
        }
        final KeyboardSwitcher switcher = mKeyboardSwitcher;
        switcher.updateKeyboardTheme();
        final MainKeyboardView mainKeyboardView = switcher.getMainKeyboardView();
        // If we are starting input in a different text field from before, we'll have to reload
        // settings, so currentSettingsValues can't be final.
        SettingsValues currentSettingsValues = mSettings.getCurrent();

        if (editorInfo == null) {
            Log.e(TAG, "Null EditorInfo in onStartInputView()");
            if (DebugFlags.DEBUG_ENABLED) {
                throw new NullPointerException("Null EditorInfo in onStartInputView()");
            }
            return;
        }
        if (DebugFlags.DEBUG_ENABLED) {
            Log.d(TAG, "onStartInputView: editorInfo:"
                    + String.format("inputType=0x%08x imeOptions=0x%08x",
                            editorInfo.inputType, editorInfo.imeOptions));
            Log.d(TAG, "All caps = "
                    + ((editorInfo.inputType & InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) != 0)
                    + ", sentence caps = "
                    + ((editorInfo.inputType & InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0)
                    + ", word caps = "
                    + ((editorInfo.inputType & InputType.TYPE_TEXT_FLAG_CAP_WORDS) != 0));
        }
        Log.i(TAG, "Starting input. Cursor position = "
                + editorInfo.initialSelStart + "," + editorInfo.initialSelEnd
                + " Restarting = " + restarting
                + " fieldId = " + editorInfo.fieldId
                + " lastInlineFieldId = " + mLastInlineFieldId
                + " externalActive = " + (mTopBarView != null && mTopBarView.isExternalViewActive()));

        // In landscape mode, this method gets called without the input view being created.
        if (mainKeyboardView == null) {
            return;
        }

        final boolean inputTypeChanged = !currentSettingsValues.isSameInputType(editorInfo);
        final boolean isDifferentTextField = !restarting || inputTypeChanged;

        // The EditorInfo might have a flag that affects fullscreen mode.
        // Note: This call should be done by InputMethodService?
        updateFullscreenMode();

        // ALERT: settings have not been reloaded and there is a chance they may be stale.
        // In the practice, if it is, we should have gotten onConfigurationChanged so it should
        // be fine, but this is horribly confusing and must be fixed AS SOON AS POSSIBLE.

        // In some cases the input connection has not been reset yet and we can't access it. In
        // this case we will need to call loadKeyboard() later, when it's accessible, so that we
        // can go into the correct mode, so we need to do some housekeeping here.
        if (!isImeSuppressedByHardwareKeyboard()) {
            // The app calling setText() has the effect of clearing the composing
            // span, so we should reset our state unconditionally, even if restarting is true.
            // We also tell the input logic about the combining rules for the current subtype, so
            // it can adjust its combiners if needed.
            mInputLogic.startInput();

            // Some applications call onStartInputView without updating EditorInfo. In these cases
            // selection will be incorrect.
            mInputLogic.mConnection.reloadTextCache(editorInfo, restarting);
        }

        if (isDifferentTextField) {
            loadSettings();
            currentSettingsValues = mSettings.getCurrent();
            mainKeyboardView.closing();
            switcher.loadKeyboard(editorInfo, currentSettingsValues, getCurrentAutoCapsState(),
                    getCurrentRecapitalizeState());
        } else if (restarting) {
            switcher.requestUpdatingShiftState(getCurrentAutoCapsState(), getCurrentRecapitalizeState());
        }

        if (mClipboardHistoryManager != null) {
            mClipboardHistoryManager.updateCurrentClip();
        }

        if (mTopBarView != null) {
            mTopBarView.setLanguageButtonVisible(shouldShowLanguageSwitchKey());
            updateSuggestions();
        }

        if (TRACE) Debug.startMethodTracing("/data/trace/latinime");

        hideClipboardHistory();
        hideEmojiView();
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
        if (isInputViewShown()) {
            setNavigationBarColor();
            if (mClipboardHistoryManager != null) {
                mClipboardHistoryManager.updateCurrentClip();
            }
            if (mTopBarView != null) {
                updateSuggestions();
            }
        }
    }

    @Override
    public void onWindowHidden() {
        super.onWindowHidden();
        hideClipboardHistory();
        hideEmojiView();
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView != null) {
            mainKeyboardView.closing();
        }
    }

    void onFinishInputInternal() {
        super.onFinishInput();

        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView != null) {
            mainKeyboardView.closing();
        }
    }

    void onFinishInputViewInternal(final boolean finishingInput) {
        mCanRevertAutocorrect = false;
        mOriginalTypedWordBeforeAutocorrect = null;
        mAutocorrectedWord = null;
        hideClipboardHistory();
        hideEmojiView();
        if (mTopBarView != null && mTopBarView.isExternalViewActive()) {
            mTopBarView.setExternalView(null);
        }
        super.onFinishInputView(finishingInput);
    }

    @Override
    @RequiresApi(api = Build.VERSION_CODES.R)
    public InlineSuggestionsRequest onCreateInlineSuggestionsRequest(@NonNull Bundle uiExtras) {
        Log.i(TAG, "onCreateInlineSuggestionsRequest called");
        return rkr.simplekeyboard.inputmethod.latin.utils.InlineAutofillUtils.createInlineSuggestionRequest(
                mDisplayContext != null ? mDisplayContext : this);
    }

    @Override
    @RequiresApi(api = Build.VERSION_CODES.R)
    public boolean onInlineSuggestionsResponse(InlineSuggestionsResponse response) {
        Log.i(TAG, "onInlineSuggestionsResponse called");
        final java.util.List<InlineSuggestion> inlineSuggestions = response.getInlineSuggestions();
        if (inlineSuggestions == null || inlineSuggestions.isEmpty()) {
            Log.i(TAG, "onInlineSuggestionsResponse: empty suggestions (returning false)");
            // Match LeanType: just return false when empty. The framework handles this
            // appropriately without falling into the infinite focus loop because we
            // didn't modify the keyboard layout/view hierarchy here.
            return false;
        }
        Log.i(TAG, "onInlineSuggestionsResponse: received " + inlineSuggestions.size() + " suggestions");
        final EditorInfo editorInfo = getCurrentInputEditorInfo();
        if (editorInfo != null) {
            mLastInlineFieldId = editorInfo.fieldId;
        }
        final View inlineView = rkr.simplekeyboard.inputmethod.latin.utils.InlineAutofillUtils.createView(
                inlineSuggestions, mDisplayContext != null ? mDisplayContext : this);
        if (mTopBarView != null) {
            mTopBarView.setExternalView(inlineView);
        }
        return true;
    }

    @Override
    public void onTrimMemory(final int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            mHandler.cancelDeallocateMemory();
            deallocateMemory();
        }
    }

    protected void deallocateMemory() {
        if (mClipboardHistoryView != null) {
            mClipboardHistoryView.deallocateMemory();
        }
        if (mEmojiPalettesView != null) {
            mEmojiPalettesView.deallocateMemory();
        }
        mKeyboardSwitcher.deallocateMemory();
    }

    @Override
    public void onUpdateSelection(final int oldSelStart, final int oldSelEnd,
            final int newSelStart, final int newSelEnd,
            final int composingSpanStart, final int composingSpanEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                composingSpanStart, composingSpanEnd);
        final MainKeyboardView keyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (keyboardView != null && keyboardView.isInCursorMove()) {
            return;
        }

        Log.i(TAG, "Update Selection. Cursor position = " + newSelStart + "," + newSelEnd);

        mInputLogic.onUpdateSelection(newSelStart, newSelEnd);
        if (isInputViewShown()) {
            mInputLogic.reloadTextCache();

            mKeyboardSwitcher.requestUpdatingShiftState(getCurrentAutoCapsState(),
                    getCurrentRecapitalizeState());
        }
    }

    private void loadDictionaryForLocale(final Locale currentLocale) {
        if (currentLocale != null && currentLocale.equals(mLoadedLocale) && mPrefixDictionary.getWordCount() > 0) {
            return;
        }
        mLoadedLocale = currentLocale;
        final String currentLang = (currentLocale != null) ? currentLocale.getLanguage() : "es";

        loadBinaryDictionary(currentLang);

        final java.util.Set<String> enabledLangs = new java.util.LinkedHashSet<>();
        if (mRichImm != null) {
            final java.util.Set<rkr.simplekeyboard.inputmethod.latin.Subtype> subtypes =
                    mRichImm.getEnabledSubtypes(false);
            if (subtypes != null) {
                for (rkr.simplekeyboard.inputmethod.latin.Subtype st : subtypes) {
                    final String l = st.getLocale();
                    if (l != null && l.length() >= 2) {
                        enabledLangs.add(l.substring(0, 2).toLowerCase());
                    }
                }
            }
        }
        if (enabledLangs.isEmpty()) {
            enabledLangs.add(currentLang);
        }

        mDictExecutor.execute(() -> {
            final PrefixDictionary newDict = new PrefixDictionary();
            final SettingsValues settingsValues = mSettings.getCurrent();
            if (settingsValues != null) {
                newDict.setAutoCorrectionThreshold(settingsValues.mAutoCorrectionThreshold);
            }
            // 1. Load secondary enabled languages first (with 85% relative frequency)
            for (String lang : enabledLangs) {
                if (!lang.equals(currentLang)) {
                    loadSingleLanguageDictionaryInto(newDict, lang, 0.85f);
                }
            }

            // 2. Load primary active language with 100% frequency
            loadSingleLanguageDictionaryInto(newDict, currentLang, 1.0f);

            mHandler.post(() -> {
                mPrefixDictionary.copyFrom(newDict);
                updateSuggestions();
            });
        });
    }

    private void loadBinaryDictionary(final String lang) {
        final String assetName = "es".equals(lang) ? "dict_es.bin" : "dict_en.bin";
        try {
            java.io.InputStream is = getAssets().open(assetName);
            byte[] bytes = new byte[is.available()];
            is.read(bytes);
            is.close();
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
            mBinaryTrieDictionary = new rkr.simplekeyboard.inputmethod.latin.dict.binary.BinaryTrieDictionary(buffer);
            mBeamSearchDecoder = new rkr.simplekeyboard.inputmethod.latin.dict.decoder.BeamSearchDecoder(mBinaryTrieDictionary, mSpatialTouchModel);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void loadSingleLanguageDictionaryInto(final PrefixDictionary targetDict, final String lang, final float weightFactor) {
        final String assetName = "es".equals(lang) ? "dict_es.txt" : "dict_en.txt";
        boolean loaded = false;
        try (InputStream is = getAssets().open(assetName);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                final int sep = line.indexOf(' ');
                if (sep > 0) {
                    final String word = line.substring(0, sep);
                    try {
                        int freq = Integer.parseInt(line.substring(sep + 1));
                        int adjustedFreq = (int) (freq * weightFactor);
                        targetDict.insert(word, Math.max(1, adjustedFreq));
                    } catch (NumberFormatException e) {
                        targetDict.insert(word, 1);
                    }
                } else {
                    targetDict.insert(line, 1);
                }
            }
            loaded = true;
        } catch (Exception e) {
            Log.w(TAG, "Could not load dictionary asset: " + assetName, e);
        }
        if (!loaded) {
            final String[] defaultWords = "es".equals(lang)
                ? new String[]{"que", "de", "no", "a", "la", "el", "es", "y", "en", "lo", "un", "por", "qué", "me", "una", "te", "los", "se", "con", "para", "mi", "está", "si", "bien", "pero", "yo", "eso", "las", "sí", "hola", "teclado", "gracias", "tiempo", "donde", "cuando", "hacer", "todo", "puede", "ahora", "mucho", "nuevo", "día", "vida", "casa", "mundo"}
                : new String[]{"the", "be", "to", "of", "and", "a", "in", "that", "have", "i", "it", "for", "not", "on", "with", "he", "as", "you", "do", "at", "this", "but", "his", "by", "from", "they", "we", "say", "her", "she", "or", "an", "will", "my", "one", "all", "would", "there", "their", "what", "so", "up", "out", "if", "about", "who", "get", "which", "go", "me", "when", "make", "can", "like", "time", "no", "just", "him", "know", "take", "people", "into", "year", "your", "good", "some", "could", "them", "see", "other", "than", "then", "now", "look", "only", "come", "its", "over", "think", "also", "back", "after", "use", "two", "how", "our", "work", "first", "well", "way", "even", "new", "want", "because", "any", "these", "give", "day", "most", "us", "keyboard", "simple", "great", "need", "feel", "high", "place", "thing", "things", "case", "call", "hand", "right", "world"};
            int freq = defaultWords.length * 10;
            for (String w : defaultWords) {
                targetDict.insert(w, (int) (freq-- * weightFactor));
            }
        }
    }

    public void updateSuggestions() {
        if (mTopBarView != null && mTopBarView.isExternalViewActive()) {
            return;
        }
        if (isSuggestionsDisabled()) {
            return;
        }
        final String word = mInputLogic.mConnection.getWordBeforeCursor();
        final String[] context = getEffectivePreviousWords();
        final String w1 = context[0];
        final String w2 = context[1];

        if (isWordEmpty(word)) {
            displayEmptyWordSuggestions(w1, w2);
            return;
        }

        displayComposingSuggestions(word, w1, w2);
    }

    private boolean isSuggestionsDisabled() {
        if (mTopBarView == null || !isInputViewShown()) {
            return true;
        }
        if (!mSettings.getCurrent().mShowSuggestions || shouldSuppressSuggestions()) {
            mTopBarView.setSuggestions(null, -1);
            return true;
        }
        return false;
    }

    private boolean shouldSuppressSuggestions() {
        final EditorInfo editorInfo = getCurrentInputEditorInfo();
        if (editorInfo == null) {
            return false;
        }
        return !new InputAttributes(editorInfo, isFullscreenMode()).mShouldShowSuggestions;
    }

    private void displayEmptyWordSuggestions(final String w1, final String w2) {
        if (displayClipboardChipIfAvailable()) {
            return;
        }
        if (displayNextWordPredictionsIfAvailable(w1, w2)) {
            return;
        }
        mTopBarView.setSuggestions(null, -1);
    }

    private boolean displayClipboardChipIfAvailable() {
        if (mClipboardHistoryManager == null) {
            return false;
        }
        if (mSettings.getCurrent().mSuggestScreenshots) {
            final ClipboardHistoryManager.ScreenshotInfo screenshotInfo =
                    mClipboardHistoryManager.getRecentScreenshotForSuggestion();
            if (screenshotInfo != null) {
                final Bitmap thumb = mClipboardHistoryManager.getScreenshotThumbnail(screenshotInfo);
                final String uriString = screenshotInfo.fullPath != null ? screenshotInfo.fullPath : screenshotInfo.uri.toString();
                mTopBarView.setScreenshotSuggestion(uriString, thumb);
                return true;
            }
        }
        if (!mSettings.getCurrent().mClipboardSuggestionsEnabled) {
            return false;
        }
        final String recentClip = mClipboardHistoryManager.getRecentClipForSuggestion();
        if (recentClip != null) {
            mTopBarView.setClipboardSuggestion(recentClip);
            return true;
        }
        return false;
    }

    public void onImageSelected(final String imageUri) {
        final EditorInfo editorInfo = getCurrentInputEditorInfo();
        if (editorInfo == null) {
            return;
        }

        Uri contentUri;
        if (imageUri.startsWith("content://")) {
            contentUri = Uri.parse(imageUri);
        } else {
            final File file = new File(imageUri);
            if (!file.exists()) return;
            contentUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
        }

        try {
            final String mimeType = getContentResolver().getType(contentUri);
            final String finalMimeType = mimeType != null ? mimeType : "image/png";

            final InputContentInfoCompat inputContentInfoCompat = new InputContentInfoCompat(
                    contentUri,
                    new ClipDescription("Clipboard Image", new String[]{finalMimeType}),
                    null);

            final android.view.inputmethod.InputConnection ic = getCurrentInputConnection();
            if (ic == null) {
                return;
            }

            int flags = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                flags = InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION;
            }

            boolean inserted = false;
            try {
                inserted = InputConnectionCompat.commitContent(
                        ic, editorInfo, inputContentInfoCompat, flags, null);
            } catch (Exception e) {
                Log.e(TAG, "Failed to commit content", e);
            }

            if (!inserted) {
                Toast.makeText(this, R.string.image_pasting_not_supported, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to paste image", e);
        }
    }

    private boolean displayNextWordPredictionsIfAvailable(final String w1, final String w2) {
        if (isWordEmpty(w2)) {
            return false;
        }
        final java.util.List<CharSequence> predictions = new java.util.ArrayList<>(3);
        final java.util.Set<String> added = new java.util.HashSet<>();

        if (mContactsDictionary != null && mSettings.getCurrent().mUseContacts) {
            final java.util.List<CharSequence> contactPreds = mContactsDictionary.getNextWordPredictions(w1, w2, 1);
            if (contactPreds != null) {
                for (CharSequence cp : contactPreds) {
                    if (added.add(cp.toString().toLowerCase())) {
                        predictions.add(cp);
                    }
                }
            }
        }

        final java.util.List<CharSequence> nextWordPredictions = mPrefixDictionary.getNextWordPredictions(w1, w2, 3);
        if (nextWordPredictions != null) {
            for (CharSequence np : nextWordPredictions) {
                if (predictions.size() >= 3) break;
                if (added.add(np.toString().toLowerCase())) {
                    predictions.add(np);
                }
            }
        }

        if (!predictions.isEmpty()) {
            mTopBarView.setSuggestions(predictions, 0);
            return true;
        }
        return false;
    }

    private java.util.List<CharSequence> getSuggestionsForWord(final String word, final String w1, final String w2) {
        final java.util.List<CharSequence> merged = new java.util.ArrayList<>(3);
        final java.util.Set<String> added = new java.util.HashSet<>();

        if (mContactsDictionary != null && mSettings.getCurrent().mUseContacts) {
            final java.util.List<CharSequence> contactMatches = mContactsDictionary.getSuggestions(word, 2, w1, w2);
            if (contactMatches != null) {
                for (CharSequence c : contactMatches) {
                    if (added.add(c.toString().toLowerCase())) {
                        merged.add(c);
                    }
                }
            }
        }

        if (mBeamSearchDecoder != null) {
            final java.util.List<CharSequence> matches = mBeamSearchDecoder.getSuggestions(word, 3, w2);
            if (matches != null && !matches.isEmpty()) {
                for (CharSequence m : matches) {
                    if (merged.size() >= 3) break;
                    if (added.add(m.toString().toLowerCase())) {
                        merged.add(m);
                    }
                }
                if (!merged.isEmpty()) return merged;
            }
        }
        final java.util.List<CharSequence> dictMatches = mPrefixDictionary.getSuggestions(word, 3, w1, w2);
        if (dictMatches != null) {
            for (CharSequence d : dictMatches) {
                if (merged.size() >= 3) break;
                if (added.add(d.toString().toLowerCase())) {
                    merged.add(d);
                }
            }
        }
        return merged;
    }

    private CharSequence getDecoderBestCorrection(final String word, final String prevWord) {
        if (mBeamSearchDecoder != null) {
            return mBeamSearchDecoder.getBestCorrection(word, 0.5f, prevWord);
        }
        return null;
    }

    private CharSequence resolveBestCorrection(final String word, final String w1, final String w2, final boolean hasMatches) {
        if (!mSettings.getCurrent().mAutoCorrectionEnabled) {
            return null;
        }
        final CharSequence decoderCorrection = getDecoderBestCorrection(word, w2);
        if (decoderCorrection != null) {
            return decoderCorrection;
        }
        final CharSequence exactNorm = mPrefixDictionary.getExactNormalizedCorrection(word);
        if (exactNorm != null) {
            return exactNorm;
        }
        return hasMatches ? null : mPrefixDictionary.getBestCorrection(word, w1, w2);
    }

    private void appendCorrectionAndCandidates(final java.util.List<CharSequence> suggestions,
            final String word, final String w1, final String w2, final CharSequence bestCorrection,
            final java.util.List<CharSequence> matches) {
        suggestions.add(bestCorrection);
        final java.util.List<CharSequence> candidates = matches.isEmpty()
                ? mPrefixDictionary.getFuzzySuggestions(word, 3, w1, w2) : matches;
        final String bestStr = bestCorrection.toString();
        for (CharSequence s : candidates) {
            if (suggestions.size() >= 3) {
                break;
            }
            if (!s.toString().equalsIgnoreCase(bestStr)) {
                suggestions.add(s);
            }
        }
    }

    private int appendMatchingSuggestions(final java.util.List<CharSequence> suggestions,
            final java.util.List<CharSequence> matches) {
        if (matches.isEmpty()) {
            return -1;
        }
        suggestions.add(matches.get(0));
        if (matches.size() > 1) {
            suggestions.add(matches.get(1));
        }
        return 1;
    }

    private void displayComposingSuggestions(final String word, final String w1, final String w2) {
        final java.util.List<CharSequence> suggestions = new java.util.ArrayList<>();
        suggestions.add("\"" + word + "\"");

        final java.util.List<CharSequence> matches = getSuggestionsForWord(word, w1, w2);
        final CharSequence bestCorrection = resolveBestCorrection(word, w1, w2, !matches.isEmpty());

        final int boldIndex;
        if (bestCorrection != null) {
            appendCorrectionAndCandidates(suggestions, word, w1, w2, bestCorrection, matches);
            boldIndex = 1;
        } else {
            boldIndex = appendMatchingSuggestions(suggestions, matches);
        }

        mTopBarView.setSuggestions(suggestions, boldIndex);
    }

    @Override
    public void hideWindow() {
        mKeyboardSwitcher.onHideWindow();

        if (TRACE) Debug.stopMethodTracing();
        if (isShowingOptionDialog()) {
            mOptionsDialog.dismiss();
            mOptionsDialog = null;
        }
        super.hideWindow();
    }

    @Override
    public void onComputeInsets(final InputMethodService.Insets outInsets) {
        super.onComputeInsets(outInsets);
        if (mInputView == null) {
            return;
        }
        final View visibleKeyboardView = mKeyboardSwitcher.getVisibleKeyboardView();
        if (visibleKeyboardView == null) {
            return;
        }
        final int inputHeight = mInputView.getHeight();

        final boolean isClipboardVisible = (mClipboardHistoryView != null && mClipboardHistoryView.getVisibility() == View.VISIBLE);
        final boolean isEmojiVisible = (mEmojiPalettesView != null && mEmojiPalettesView.getVisibility() == View.VISIBLE);
        final boolean isOverlayVisible = isClipboardVisible || isEmojiVisible;
        final boolean isKeyboardShown = visibleKeyboardView.isShown();

        if (!isOverlayVisible && isImeSuppressedByHardwareKeyboard() && !isKeyboardShown) {
            outInsets.contentTopInsets = inputHeight;
            outInsets.visibleTopInsets = inputHeight;
            return;
        }

        final int visibleHeight;
        if (isClipboardVisible) {
            visibleHeight = mClipboardHistoryView.getHeight();
        } else if (isEmojiVisible) {
            visibleHeight = mEmojiPalettesView.getHeight();
        } else {
            final View topBar = mInputView.findViewById(rkr.simplekeyboard.inputmethod.R.id.top_bar_view);
            final int topBarHeight = (topBar != null && topBar.getVisibility() == View.VISIBLE) ? topBar.getHeight() : 0;
            visibleHeight = visibleKeyboardView.getHeight() + topBarHeight;
        }

        final int visibleTopY = Math.max(0, inputHeight - visibleHeight);

        if (isOverlayVisible || isKeyboardShown) {
            final int touchTop = (!isOverlayVisible && mKeyboardSwitcher.isShowingMoreKeysPanel()) ? 0 : visibleTopY;
            outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION;
            outInsets.touchableRegion.set(0, touchTop, mInputView.getWidth(), inputHeight + EXTENDED_TOUCHABLE_REGION_HEIGHT);
        }

        outInsets.contentTopInsets = visibleTopY;
        outInsets.visibleTopInsets = visibleTopY;
        if (mInsetsUpdater != null) {
            mInsetsUpdater.setInsets(outInsets);
        }
    }

    @Override
    public boolean onShowInputRequested(final int flags, final boolean configChange) {
        if (isImeSuppressedByHardwareKeyboard()) {
            return true;
        }
        return super.onShowInputRequested(flags, configChange);
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        if (isImeSuppressedByHardwareKeyboard()) {
            // If there is a hardware keyboard, disable full screen mode.
            return false;
        }
        // Reread resource value here, because this method is called by the framework as needed.
        final boolean isFullscreenModeAllowed = Settings.readUseFullscreenMode(getResources());
        if (super.onEvaluateFullscreenMode() && isFullscreenModeAllowed) {
            // TODO: Remove this hack. Actually we should not really assume NO_EXTRACT_UI
            // implies NO_FULLSCREEN. However, the framework mistakenly does.  i.e. NO_EXTRACT_UI
            // without NO_FULLSCREEN doesn't work as expected. Because of this we need this
            // hack for now.  Let's get rid of this once the framework gets fixed.
            final EditorInfo ei = getCurrentInputEditorInfo();
            return !(ei != null && ((ei.imeOptions & EditorInfo.IME_FLAG_NO_EXTRACT_UI) != 0));
        }
        return false;
    }

    @Override
    public void updateFullscreenMode() {
        super.updateFullscreenMode();
        updateSoftInputWindowLayoutParameters();
    }

    private void updateSoftInputWindowLayoutParameters() {
        // Override layout parameters to expand {@link SoftInputWindow} to the entire screen.
        // See {@link InputMethodService#setinputView(View)} and
        // {@link SoftInputWindow#updateWidthHeight(WindowManager.LayoutParams)}.
        final Window window = getWindow().getWindow();
        ViewLayoutUtils.updateLayoutHeightOf(window, LayoutParams.MATCH_PARENT);
        if (mInputView != null) {
            // In non-fullscreen mode, {@link InputView} and its parent inputArea should expand to
            // the entire screen and be placed at the bottom of {@link SoftInputWindow}.
            // In fullscreen mode, these shouldn't expand to the entire screen and should be
            // coexistent with {@link #mExtractedArea} above.
            // See {@link InputMethodService#setInputView(View) and
            // com.android.internal.R.layout.input_method.xml.
            final int layoutHeight = isFullscreenMode()
                    ? LayoutParams.WRAP_CONTENT : LayoutParams.MATCH_PARENT;
            final View inputArea = window.findViewById(android.R.id.inputArea);
            ViewLayoutUtils.updateLayoutHeightOf(inputArea, layoutHeight);
            ViewLayoutUtils.updateLayoutGravityOf(inputArea, Gravity.BOTTOM);
            ViewLayoutUtils.updateLayoutHeightOf(mInputView, layoutHeight);
        }
    }

    int getCurrentAutoCapsState() {
        return mInputLogic.getCurrentAutoCapsState(mSettings.getCurrent(),
                mRichImm.getCurrentSubtype().getKeyboardLayoutSet());
    }

    int getCurrentRecapitalizeState() {
        return mInputLogic.getCurrentRecapitalizeState();
    }

    @Override
    public boolean onCustomRequest(final int requestCode) {
        switch (requestCode) {
            case Constants.CUSTOM_CODE_SHOW_INPUT_METHOD_PICKER:
                return showInputMethodPicker();
        }
        return false;
    }

    private boolean showInputMethodPicker() {
        if (isShowingOptionDialog()) {
            return false;
        }
        mOptionsDialog = mRichImm.showSubtypePicker(this,
                mKeyboardSwitcher.getMainKeyboardView().getWindowToken(), this);
        return mOptionsDialog != null;
    }

    public Locale getCurrentLayoutLocale() {
        return mLocale;
    }

    @Override
    public void onMoveCursorPointer(int steps) {
        if (mInputLogic.mConnection.hasCursorPosition()) {
            if (TextUtils.getLayoutDirectionFromLocale(getCurrentLayoutLocale()) == View.LAYOUT_DIRECTION_RTL)
                steps = -steps;

            steps = mInputLogic.mConnection.getUnicodeSteps(steps, true);
            if (steps == 0) {
                return;
            }
            final int end = mInputLogic.mConnection.getExpectedSelectionEnd() + steps;
            final int start = mInputLogic.mConnection.hasSelection() ? mInputLogic.mConnection.getExpectedSelectionStart() : end;
            mInputLogic.mConnection.setSelection(start, end);
            hapticTickFeedback();
        } else {
            for (; steps < 0; steps++)
                mInputLogic.sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT);
            for (; steps > 0; steps--)
                mInputLogic.sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT);
            hapticTickFeedback();
        }
    }

    @Override
    public void onMoveDeletePointer(int steps) {
        if (mInputLogic.mConnection.hasCursorPosition()) {
            steps = mInputLogic.mConnection.getUnicodeSteps(steps, false);
            if (steps == 0) {
                return;
            }
            final int end = mInputLogic.mConnection.getExpectedSelectionEnd();
            final int start = mInputLogic.mConnection.getExpectedSelectionStart() + steps;
            mInputLogic.mConnection.setSelection(start, end);
            hapticTickFeedback();
        } else {
            for (; steps < 0; steps++)
                mInputLogic.sendDownUpKeyEvent(KeyEvent.KEYCODE_DEL);
            hapticTickFeedback();
        }
    }

    @Override
    public void onUpWithDeletePointerActive() {
        if (mInputLogic.mConnection.hasSelection())
            mInputLogic.mConnection.deleteSelectedText();
    }

    @Override
    public void onUpWithSpacePointerActive() {
        mInputLogic.reloadTextCache();
    }

    private boolean isShowingOptionDialog() {
        return mOptionsDialog != null && mOptionsDialog.isShowing();
    }

    public void switchToNextSubtype() {
        final IBinder token = getWindow().getWindow().getAttributes().token;
        mRichImm.switchToNextInputMethod(token, !shouldSwitchToOtherInputMethods(token));
    }

    // TODO: Instead of checking for alphabetic keyboard here, separate keycodes for
    // alphabetic shift and shift while in symbol layout and get rid of this method.
    private int getCodePointForKeyboard(final int codePoint) {
        if (Constants.CODE_SHIFT == codePoint) {
            final Keyboard currentKeyboard = mKeyboardSwitcher.getKeyboard();
            if (null != currentKeyboard && currentKeyboard.mId.isAlphabetKeyboard()) {
                return codePoint;
            }
            return Constants.CODE_SYMBOL_SHIFT;
        }
        return codePoint;
    }

    // Implementation of {@link KeyboardActionListener}.
    @Override
    public void onCodeInput(final int codePoint, final int x, final int y,
            final boolean isKeyRepeat) {
        feedBeamSearchDecoder(codePoint, x, y);
        final Event event = createSoftwareKeypressEvent(getCodePointForKeyboard(codePoint), x, y, isKeyRepeat);
        onEvent(event);
    }

    private void feedBeamSearchDecoder(final int codePoint, final int x, final int y) {
        if (mBeamSearchDecoder == null) {
            return;
        }
        if (Character.isLetter(codePoint)) {
            mBeamSearchDecoder.onTouch(x, y, (char) codePoint);
        } else if (codePoint == Constants.CODE_DELETE) {
            mBeamSearchDecoder.onBackspace();
        } else if (isDecoderResetKey(codePoint)) {
            mBeamSearchDecoder.reset();
        }
    }

    private static boolean isDecoderResetKey(final int codePoint) {
        return codePoint == Constants.CODE_SPACE || (!Character.isLetterOrDigit(codePoint) && codePoint > 32);
    }

    // This method is public for testability of LatinIME, but also in the future it should
    // completely replace #onCodeInput.
    public void onEvent(final Event event) {
        closeToolTrayIfOpen();
        if (mTopBarView != null && mTopBarView.isExternalViewActive()) {
            mTopBarView.setExternalView(null);
        }

        if (handleBackspaceRevert(event)) {
            return;
        }
        handleSpaceAutoCorrect(event);

        final InputTransaction completeInputTransaction =
                mInputLogic.onCodeInput(mSettings.getCurrent(), event);
        updateStateAfterInputTransaction(completeInputTransaction);
        mKeyboardSwitcher.onEvent(event, getCurrentAutoCapsState(), getCurrentRecapitalizeState());
        updateSuggestions();
    }

    private void closeToolTrayIfOpen() {
        if (mTopBarView != null && mTopBarView.isToolTrayOpen()) {
            mTopBarView.closeToolTray();
        }
    }

    private boolean handleBackspaceRevert(final Event event) {
        if (!isBackspaceEvent(event)) {
            if (isNonSpaceNormalKey(event)) {
                mCanRevertAutocorrect = false;
            }
            return false;
        }

        if (tryExecuteBackspaceRevert(event)) {
            return true;
        }
        mCanRevertAutocorrect = false;
        return false;
    }

    private static boolean isBackspaceEvent(final Event event) {
        return event.isFunctionalKeyEvent() && event.mKeyCode == Constants.CODE_DELETE;
    }

    private static boolean isNonSpaceNormalKey(final Event event) {
        return !event.isFunctionalKeyEvent() && event.mCodePoint != Constants.CODE_SPACE;
    }

    private boolean isAutocorrectRevertible() {
        return mCanRevertAutocorrect && mAutocorrectedWord != null && mOriginalTypedWordBeforeAutocorrect != null;
    }

    private boolean tryExecuteBackspaceRevert(final Event event) {
        if (!isAutocorrectRevertible()) {
            return false;
        }
        final String textBefore = mInputLogic.mConnection.getTextBeforeCursor(mAutocorrectedWord.length() + 2, 0);
        if (textBefore == null || !textBefore.endsWith(mAutocorrectedWord + " ")) {
            return false;
        }
        executeBackspaceRevert(event);
        return true;
    }

    private void executeBackspaceRevert(final Event event) {
        mInputLogic.mConnection.deleteTextBeforeCursor(mAutocorrectedWord.length() + 1);
        mInputLogic.mConnection.commitText(mOriginalTypedWordBeforeAutocorrect, 1);

        final String[] context = getEffectivePreviousWords();
        recordCommittedWord(mOriginalTypedWordBeforeAutocorrect, context[0], context[1]);

        mCanRevertAutocorrect = false;
        mOriginalTypedWordBeforeAutocorrect = null;
        mAutocorrectedWord = null;
        mKeyboardSwitcher.onEvent(event, getCurrentAutoCapsState(), getCurrentRecapitalizeState());
        updateSuggestions();
    }

    private static boolean isSpaceEvent(final Event event) {
        return !event.isFunctionalKeyEvent() && event.mCodePoint == Constants.CODE_SPACE;
    }

    private boolean shouldPerformAutoCorrection(final String word) {
        if (!mSettings.getCurrent().mAutoCorrectionEnabled || shouldSuppressSuggestions()) {
            return false;
        }
        return !isWordEmpty(word);
    }

    private CharSequence getBestCorrection(final String word, final String w1, final String w2) {
        final CharSequence decoderCorrection = getDecoderBestCorrection(word, w2);
        if (decoderCorrection != null) {
            return decoderCorrection;
        }
        return mPrefixDictionary.getBestCorrection(word, w1, w2);
    }

    private String applySpaceAutoCorrection(final String word, final String w1, final String w2) {
        if (!shouldPerformAutoCorrection(word)) {
            mCanRevertAutocorrect = false;
            return word;
        }
        final CharSequence correction = getBestCorrection(word, w1, w2);
        if (correction != null) {
            mInputLogic.mConnection.deleteTextBeforeCursor(word.length());
            mInputLogic.mConnection.commitText(correction, 1);
            mOriginalTypedWordBeforeAutocorrect = word;
            mAutocorrectedWord = correction.toString();
            mCanRevertAutocorrect = true;
            return mAutocorrectedWord;
        }
        mCanRevertAutocorrect = false;
        return word;
    }

    private void recordCommittedWord(final String committedWord, final String w1, final String w2) {
        if (isWordEmpty(committedWord)) {
            return;
        }
        final String cleanCommitted = committedWord.trim();
        learnNgramAsync(w1, w2, cleanCommitted);
    }

    private void handleSpaceAutoCorrect(final Event event) {
        if (!isSpaceEvent(event)) {
            return;
        }
        final String word = mInputLogic.mConnection.getWordBeforeCursor();
        final String[] context = getEffectivePreviousWords();
        final String w1 = context[0];
        final String w2 = context[1];
        final String committedWord = applySpaceAutoCorrection(word, w1, w2);
        recordCommittedWord(committedWord, w1, w2);
    }

    private String[] getEffectivePreviousWords() {
        return mInputLogic.mConnection.getTwoPreviousWordsBeforeCursor();
    }

    private String getEffectivePreviousWord() {
        return mInputLogic.mConnection.getPreviousWordBeforeCursor();
    }

    private static boolean isWordEmpty(final String word) {
        return word == null || word.trim().isEmpty();
    }

    private void learnNgramAsync(final String w1, final String w2, final String word) {
        if (isWordEmpty(word)) {
            return;
        }
        final String cleanWord = word.trim();
        mPrefixDictionary.insert(cleanWord, PrefixDictionary.BASE_LEARNED_FREQUENCY);
        if (!isWordEmpty(w2)) {
            mPrefixDictionary.setBigram(w2.trim(), cleanWord, PrefixDictionary.BASE_LEARNED_FREQUENCY);
            if (!isWordEmpty(w1)) {
                mPrefixDictionary.setTrigram(w1.trim(), w2.trim(), cleanWord, PrefixDictionary.BASE_LEARNED_FREQUENCY);
            }
        }
    }

    public static String applyCasing(final String typed, final String suggestion) {
        return PrefixDictionary.applyCasing(typed, suggestion);
    }

    // A helper method to split the code point and the key code. Ultimately, they should not be
    // squashed into the same variable, and this method should be removed.
    // public for testing, as we don't want to copy the same logic into test code
    public static Event createSoftwareKeypressEvent(final int keyCodeOrCodePoint, final int x, final int y, final boolean isKeyRepeat) {
        final int keyCode;
        final int codePoint;
        if (keyCodeOrCodePoint <= 0) {
            keyCode = keyCodeOrCodePoint;
            codePoint = Event.NOT_A_CODE_POINT;
        } else {
            keyCode = Event.NOT_A_KEY_CODE;
            codePoint = keyCodeOrCodePoint;
        }
        return Event.createSoftwareKeypressEvent(codePoint, keyCode, x, y, isKeyRepeat);
    }

    // Called from PointerTracker through the KeyboardActionListener interface
    @Override
    public void onTextInput(final String rawText) {
        // TODO: have the keyboard pass the correct key code when we need it.
        final Event event = Event.createSoftwareTextEvent(rawText, Constants.CODE_OUTPUT_TEXT);
        final InputTransaction completeInputTransaction =
                mInputLogic.onTextInput(mSettings.getCurrent(), event);
        updateStateAfterInputTransaction(completeInputTransaction);
        mKeyboardSwitcher.onEvent(event, getCurrentAutoCapsState(), getCurrentRecapitalizeState());
    }

    // Called from PointerTracker through the KeyboardActionListener interface
    @Override
    public void onFinishSlidingInput() {
        // User finished sliding input.
        mKeyboardSwitcher.onFinishSlidingInput(getCurrentAutoCapsState(),
                getCurrentRecapitalizeState());
    }

    private void loadKeyboard() {
        // Since we are switching languages, the most urgent thing is to let the keyboard graphics
        // update. LoadKeyboard does that, but we need to wait for buffer flip for it to be on
        // the screen. Anything we do right now will delay this, so wait until the next frame
        // before we do the rest, like reopening dictionaries and updating suggestions. So we
        // post a message.
        loadSettings();
        if (mKeyboardSwitcher.getMainKeyboardView() != null) {
            // Reload keyboard because the current language has been changed.
            mKeyboardSwitcher.loadKeyboard(getCurrentInputEditorInfo(), mSettings.getCurrent(),
                    getCurrentAutoCapsState(), getCurrentRecapitalizeState());
        }
    }

    /**
     * After an input transaction has been executed, some state must be updated. This includes
     * the shift state of the keyboard and suggestions. This method looks at the finished
     * inputTransaction to find out what is necessary and updates the state accordingly.
     * @param inputTransaction The transaction that has been executed.
     */
    private void updateStateAfterInputTransaction(final InputTransaction inputTransaction) {
        switch (inputTransaction.getRequiredShiftUpdate()) {
        case InputTransaction.SHIFT_UPDATE_LATER:
            mHandler.postUpdateShiftState();
            break;
        case InputTransaction.SHIFT_UPDATE_NOW:
            mKeyboardSwitcher.requestUpdatingShiftState(getCurrentAutoCapsState(),
                    getCurrentRecapitalizeState());
            break;
        default: // SHIFT_NO_UPDATE
        }
    }

    private void hapticAndAudioFeedback(final int code, final int repeatCount) {
        final MainKeyboardView keyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (keyboardView != null && keyboardView.isInDraggingFinger()) {
            // No need to feedback while finger is dragging.
            return;
        }
        if (repeatCount > 0) {
            if (code == Constants.CODE_DELETE && !mInputLogic.mConnection.canDeleteCharacters()) {
                // No need to feedback when repeat delete key will have no effect.
                return;
            }
            // TODO: Use event time that the last feedback has been generated instead of relying on
            // a repeat count to thin out feedback.
            if (repeatCount % PERIOD_FOR_AUDIO_AND_HAPTIC_FEEDBACK_IN_KEY_REPEAT == 0) {
                return;
            }
        }
        final AudioAndHapticFeedbackManager feedbackManager = AudioAndHapticFeedbackManager.getInstance();
        if (repeatCount == 0) {
            // TODO: Reconsider how to perform haptic feedback when repeating key.
            feedbackManager.performHapticFeedback(keyboardView);
        }
        feedbackManager.performAudioFeedback(code);
    }

    private void hapticTickFeedback() {
        final AudioAndHapticFeedbackManager feedbackManager = AudioAndHapticFeedbackManager.getInstance();
        feedbackManager.performTickFeedback();
    }

    // Callback of the {@link KeyboardActionListener}. This is called when a key is depressed;
    // release matching call is {@link #onReleaseKey(int,boolean)} below.
    @Override
    public void onPressKey(final int primaryCode, final int repeatCount,
            final boolean isSinglePointer) {
        mKeyboardSwitcher.onPressKey(primaryCode, isSinglePointer, getCurrentAutoCapsState(),
                getCurrentRecapitalizeState());
        hapticAndAudioFeedback(primaryCode, repeatCount);
    }

    // Callback of the {@link KeyboardActionListener}. This is called when a key is released;
    // press matching call is {@link #onPressKey(int,int,boolean)} above.
    @Override
    public void onReleaseKey(final int primaryCode, final boolean withSliding) {
        mKeyboardSwitcher.onReleaseKey(primaryCode, withSliding, getCurrentAutoCapsState(),
                getCurrentRecapitalizeState());
    }

    // receive ringer mode change.
    private final BroadcastReceiver mRingerModeChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            final String action = intent.getAction();
            if (action.equals(AudioManager.RINGER_MODE_CHANGED_ACTION)) {
                AudioAndHapticFeedbackManager.getInstance().onRingerModeChanged();
            }
        }
    };

    public void launchSettings() {
        requestHideSelf(0);
        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView != null) {
            mainKeyboardView.closing();
        }
        final Intent intent = new Intent();
        intent.setClass(LatinIME.this, SettingsActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);
    }

    @Override
    public boolean onKeyDown(final int keyCode, final KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN) {
            if (mClipboardHistoryView != null && mClipboardHistoryView.getVisibility() == View.VISIBLE) {
                hideClipboardHistory();
                return true;
            }
            if (mEmojiPalettesView != null && mEmojiPalettesView.getVisibility() == View.VISIBLE) {
                hideEmojiView();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void dump(final FileDescriptor fd, final PrintWriter fout, final String[] args) {
        super.dump(fd, fout, args);

        final Printer p = new PrintWriterPrinter(fout);
        p.println("LatinIME state :");
        p.println("  VersionCode = " + ApplicationUtils.getVersionCode(this));
        p.println("  VersionName = " + ApplicationUtils.getVersionName(this));
        final Keyboard keyboard = mKeyboardSwitcher.getKeyboard();
        final int keyboardMode = keyboard != null ? keyboard.mId.mMode : -1;
        p.println("  Keyboard mode = " + keyboardMode);
    }

    public boolean shouldSwitchToOtherInputMethods(final IBinder token) {
        // TODO: Revisit here to reorganize the settings. Probably we can/should use different
        // strategy once the implementation of
        // {@link InputMethodManager#shouldOfferSwitchingToNextInputMethod} is defined well.
        if (!mSettings.getCurrent().mImeSwitchEnabled) {
            return false;
        }
        return mRichImm.shouldOfferSwitchingToOtherInputMethods(token);
    }

    public boolean shouldShowLanguageSwitchKey() {
        if (mSettings.getCurrent().isLanguageSwitchKeyDisabled()) {
            return false;
        }
        if (mRichImm.hasMultipleEnabledSubtypes()) {
            return true;
        }

        final IBinder token = getWindow().getWindow().getAttributes().token;
        if (token == null) {
            return false;
        }
        return shouldSwitchToOtherInputMethods(token);
    }

    private void setNavigationBarColor() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            final Window window = getWindow().getWindow();
            if (window == null) {
                return;
            }
            final int keyboardColor = Settings.readKeyboardDefaultColor(this);
            window.setNavigationBarColor(keyboardColor);
            window.setNavigationBarContrastEnforced(false);
            final int flag = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            if (ResourceUtils.isBrightColor(keyboardColor)) {
                window.getInsetsController().setSystemBarsAppearance(flag, flag);
            } else {
                window.getInsetsController().setSystemBarsAppearance(0, flag);
            }
        }
    }
}
