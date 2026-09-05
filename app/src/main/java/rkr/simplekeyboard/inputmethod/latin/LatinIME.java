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

import androidx.appcompat.app.AlertDialog;
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

import rkr.simplekeyboard.inputmethod.compat.BuildCompatUtils;
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
import rkr.simplekeyboard.inputmethod.latin.common.StringUtils;
import rkr.simplekeyboard.inputmethod.latin.emoji.EmojiPalettesView;
import rkr.simplekeyboard.inputmethod.latin.dict.PrefixDictionary;
import rkr.simplekeyboard.inputmethod.latin.dict.binary.BinaryTrieDictionary;
import rkr.simplekeyboard.inputmethod.latin.dict.decoder.BeamSearchDecoder;
import rkr.simplekeyboard.inputmethod.latin.dict.neural.MicroTransformerModel;
import rkr.simplekeyboard.inputmethod.latin.dict.user.UserBigramEntry;
import rkr.simplekeyboard.inputmethod.latin.dict.user.UserDictionaryEntry;
import rkr.simplekeyboard.inputmethod.latin.dict.user.UserDictionaryManager;
import java.io.InputStream;
import java.nio.ByteBuffer;
import rkr.simplekeyboard.inputmethod.latin.common.Constants;
import rkr.simplekeyboard.inputmethod.latin.common.StringUtils;
import rkr.simplekeyboard.inputmethod.latin.define.DebugFlags;
import rkr.simplekeyboard.inputmethod.latin.inputlogic.InputLogic;
import rkr.simplekeyboard.inputmethod.latin.settings.Settings;
import rkr.simplekeyboard.inputmethod.latin.settings.SettingsActivity;
import rkr.simplekeyboard.inputmethod.latin.settings.SettingsValues;
import rkr.simplekeyboard.inputmethod.latin.topbar.TopBarListener;
import rkr.simplekeyboard.inputmethod.latin.topbar.TopBarView;
import rkr.simplekeyboard.inputmethod.latin.utils.ApplicationUtils;
import rkr.simplekeyboard.inputmethod.latin.utils.DialogUtils;
import rkr.simplekeyboard.inputmethod.latin.utils.LeakGuardHandlerWrapper;
import rkr.simplekeyboard.inputmethod.latin.utils.ResourceUtils;
import rkr.simplekeyboard.inputmethod.latin.utils.ViewLayoutUtils;
import rkr.simplekeyboard.inputmethod.latin.utils.ViewUtils;

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
    private java.util.Set<String> mLoadedLanguages = null;
    private int mDictLoadGeneration = 0;

    public final rkr.simplekeyboard.inputmethod.latin.dict.spatial.SpatialTouchModel mSpatialTouchModel = new rkr.simplekeyboard.inputmethod.latin.dict.spatial.SpatialTouchModel();
    public BinaryTrieDictionary mBinaryTrieDictionary;
    public BeamSearchDecoder mBeamSearchDecoder;
    private MicroTransformerModel mTransformerModel;

    private final java.util.concurrent.atomic.AtomicLong mSuggestionSeq = new java.util.concurrent.atomic.AtomicLong(0);
    private volatile CharSequence mPendingAutoCorrection = null;
    private volatile String mPendingAutoCorrectionWord = null;

    private String mOriginalTypedWordBeforeAutocorrect = null;
    private String mAutocorrectedWord = null;
    private String mRevertedWord = null;
    private boolean mCanRevertAutocorrect = false;
    private int mLastInlineFieldId = 0;

    private final java.util.List<CharSequence> mScratchSuggestions = new java.util.ArrayList<>(4);
    private final java.util.List<CharSequence> mScratchMerged = new java.util.ArrayList<>(3);
    private final java.util.Set<String> mScratchDeduplicationSet = new java.util.HashSet<>();

    RichInputMethodManager mRichImm;
    final KeyboardSwitcher mKeyboardSwitcher;
    private AudioAndHapticFeedbackManager mFeedbackManager;
    private UserDictionaryManager mUserDictionaryManager;
    private rkr.simplekeyboard.inputmethod.latin.dict.CustomDictionaryManager mCustomDictionaryManager;

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
                switcher.requestUpdatingShiftState();
                latinIme.updateSuggestions();
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
        if (BuildCompatUtils.isAtLeastSV2()) {
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
        RichInputMethodManager.init(this);
        mRichImm = RichInputMethodManager.getInstance();
        mRichImm.setSubtypeChangeHandler(this);
        KeyboardSwitcher.init(this);
        AudioAndHapticFeedbackManager.init(this);
        mFeedbackManager = AudioAndHapticFeedbackManager.getInstance();
        mUserDictionaryManager = UserDictionaryManager.getInstance(this);
        mCustomDictionaryManager = rkr.simplekeyboard.inputmethod.latin.dict.CustomDictionaryManager.getInstance();
        super.onCreate();

        mClipboardHistoryManager = new ClipboardHistoryManager(this);
        mClipboardHistoryManager.setOnScreenshotChangeListener(this::updateSuggestions);
        mClipboardHistoryManager.setOnPrimaryClipChangeListener(this::updateSuggestions);
        try {
            mClipboardHistoryManager.start();
        } catch (Throwable t) {
            Log.w(TAG, "Failed to start ClipboardHistoryManager", t);
        }

        // TODO: Resolve mutual dependencies of {@link #loadSettings()} and
        // {@link #resetDictionaryFacilitatorIfNecessary()}.
        loadSettings();

        // Register to receive ringer mode change and user unlock.
        final IntentFilter filter = new IntentFilter();
        filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        registerReceiver(mRingerModeChangeReceiver, filter);

        mUserDictionaryManager.addListener(mUserDictListener);
    }

    private final UserDictionaryManager.UserDictionaryListener mUserDictListener =
            new UserDictionaryManager.UserDictionaryListener() {
        @Override
        public void onWordAdded(final UserDictionaryEntry entry) {
            if (entry != null && entry.word != null) {
                mPrefixDictionary.insert(entry.word, entry.frequency);
                mHandler.post(LatinIME.this::updateSuggestions);
            }
        }

        @Override
        public void onWordRemoved(final String word, final long id) {
            if (word != null) {
                mPrefixDictionary.removeWord(word);
                mHandler.post(LatinIME.this::updateSuggestions);
            }
        }

        @Override
        public void onAllLearnedWordsCleared() {
            mPrefixDictionary.clearLearnedWords();
            mHandler.post(LatinIME.this::updateSuggestions);
        }

        @Override
        public void onWordBlocked(final String word) {
            if (word != null) {
                mPrefixDictionary.blockWord(word);
                mHandler.post(LatinIME.this::updateSuggestions);
            }
        }

        @Override
        public void onWordUnblocked(final String word, final long id) {
            if (word != null) {
                mPrefixDictionary.unblockWord(word);
                mHandler.post(LatinIME.this::updateSuggestions);
            }
        }

        @Override
        public void onAllBlockedWordsCleared() {
            mPrefixDictionary.clearBlockedWords();
            mHandler.post(LatinIME.this::updateSuggestions);
        }
    };

    private void loadSettings() {
        mLocale = mRichImm.getCurrentSubtype().getLocaleObject();
        final EditorInfo editorInfo = getCurrentInputEditorInfo();
        final InputAttributes inputAttributes = new InputAttributes(editorInfo, isFullscreenMode());
        mSettings.loadSettings(inputAttributes);
        rkr.simplekeyboard.inputmethod.keyboard.KeyboardLayoutSet.clearKeyboardCache();
        final SettingsValues currentSettingsValues = mSettings.getCurrent();
        if (mFeedbackManager != null) {
            mFeedbackManager.onSettingsChanged(currentSettingsValues);
        } else {
            AudioAndHapticFeedbackManager.getInstance().onSettingsChanged(currentSettingsValues);
        }
        mPrefixDictionary.setAutoCorrectionThreshold(currentSettingsValues.mAutoCorrectionThreshold);
        loadDictionaryForLocale(mLocale);
    }

    @Override
    public void onDestroy() {
        if (mUserDictionaryManager != null) {
            mUserDictionaryManager.removeListener(mUserDictListener);
        } else {
            UserDictionaryManager.getInstance(this).removeListener(mUserDictListener);
        }
        if (mRichImm != null) {
            mRichImm.setSubtypeChangeHandler(null);
        }
        if (mClipboardHistoryView != null) {
            mClipboardHistoryView.shutdownExecutor();
        }
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
        return !onEvaluateInputViewShown() && mKeyboardSwitcher.isImeSuppressedByHardwareKeyboard(
                mSettings.getCurrent(), mKeyboardSwitcher.getKeyboardSwitchState());
    }

    @Override
    public boolean onEvaluateInputViewShown() {
        final boolean useOnScreen = super.onEvaluateInputViewShown();
        if (!BuildCompatUtils.isAtLeastBaklava()) {
            return useOnScreen;
        } else {
            return useOnScreen || mSettings.getCurrent().mUseOnScreen;
        }
    }

    @Override
    public void onConfigurationChanged(final Configuration conf) {
        mDisplayContext = createOrGetDisplayContext();
        loadSettings();
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
                        final String cleanWord = StringUtils.stripEnclosingQuotes(text);
                        if (cleanWord.length() > 0) {
                            final String[] context = getEffectivePreviousWords();
                            recordCommittedWord(cleanWord, context[0], context[1]);
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
                    public void onSuggestionLongClicked(CharSequence text) {
                        if (mInputView == null) {
                            Log.w(TAG, "onSuggestionLongClicked: mInputView is null");
                            return;
                        }
                        final String cleanWord = StringUtils.stripEnclosingQuotes(text);
                        if (cleanWord.isEmpty()) {
                            return;
                        }
                        showForgetWordDialog(cleanWord);
                    }

                    @Override
                    public void onClipboardSuggestionClicked(String fullClipText) {
                        commitClipboardText(fullClipText, false);
                    }

                    @Override
                    public void onScreenshotSuggestionClicked(String imageUri) {
                        commitClipboardImage(imageUri, false);
                    }
                });
            }

            if (mClipboardHistoryView != null) {
                mClipboardHistoryView.setListener(new ClipboardHistoryView.ClipboardHistoryListener() {
                    @Override
                    public void onPasteText(CharSequence text) {
                        commitClipboardText(text, true);
                    }

                    @Override
                    public void onPasteImage(String imageUri) {
                        commitClipboardImage(imageUri, true);
                    }

                    @Override
                    public void onCloseClipboard() {
                        hideClipboardHistory();
                    }

                    @Override
                    public void onSearchStateChanged(boolean isSearching) {
                        final View keyboardView = mKeyboardSwitcher.getMainKeyboardView();
                        if (isSearching) {
                            if (keyboardView != null) {
                                keyboardView.setVisibility(View.VISIBLE);
                            }
                            if (mClipboardHistoryView != null) {
                                mClipboardHistoryView.setTargetHeight(ViewUtils.dpToPx(LatinIME.this, 170));
                            }
                        } else {
                            if (keyboardView != null) {
                                keyboardView.setVisibility(View.GONE);
                            }
                            final int topBarHeight = (mTopBarView != null) ? mTopBarView.getHeight() : 0;
                            final int keyboardHeight = (keyboardView != null) ? keyboardView.getHeight() : 0;
                            final int totalHeight = topBarHeight + keyboardHeight;
                            if (mClipboardHistoryView != null) {
                                mClipboardHistoryView.setTargetHeight(totalHeight > 0 ? totalHeight : ViewUtils.dpToPx(LatinIME.this, 250));
                            }
                        }
                        if (mInputView != null) {
                            mInputView.requestLayout();
                        }
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
                        mInputLogic.sendDownUpKeyEvent(KeyEvent.KEYCODE_DEL);
                    }

                    @Override
                    public void onCloseEmoji() {
                        hideEmojiView();
                    }
                });
            }
        }
    }

    private void commitClipboardText(final CharSequence text, final boolean closeHistory) {
        if (text != null && text.length() > 0) {
            mInputLogic.mConnection.commitText(text, 1);
            if (mClipboardHistoryManager != null) {
                mClipboardHistoryManager.markLatestClipUsed();
            }
            if (closeHistory) {
                hideClipboardHistory();
            }
            updateSuggestions();
        }
    }

    private void commitClipboardImage(final String uri, final boolean closeHistory) {
        if (uri != null && !uri.isEmpty()) {
            onImageSelected(uri);
            if (mClipboardHistoryManager != null) {
                mClipboardHistoryManager.markLatestScreenshotUsed();
            }
            if (closeHistory) {
                hideClipboardHistory();
            }
            updateSuggestions();
        }
    }

    private void showSecondaryView(final View secondaryView, final Runnable prepareAction) {
        if (secondaryView == null || mInputView == null) {
            Log.w(TAG, "showSecondaryView: secondaryView or mInputView is null");
            return;
        }
        if (prepareAction != null) {
            prepareAction.run();
        }
        final int topBarHeight = (mTopBarView != null && mTopBarView.getVisibility() == View.VISIBLE) ? mTopBarView.getHeight() : 0;
        final View visibleKeyboardView = mKeyboardSwitcher.getVisibleKeyboardView();
        final int keyboardHeight = (visibleKeyboardView != null && visibleKeyboardView.getVisibility() == View.VISIBLE) ? visibleKeyboardView.getHeight() : 0;
        final int totalHeight = topBarHeight + keyboardHeight;
        if (totalHeight > 0) {
            if (secondaryView instanceof ClipboardHistoryView) {
                ((ClipboardHistoryView) secondaryView).setTargetHeight(totalHeight);
            } else if (secondaryView instanceof EmojiPalettesView) {
                ((EmojiPalettesView) secondaryView).setTargetHeight(totalHeight);
            }
        }

        if (mTopBarView != null) {
            mTopBarView.setVisibility(View.GONE);
        }
        if (visibleKeyboardView != null) {
            visibleKeyboardView.setVisibility(View.GONE);
        }

        secondaryView.setVisibility(View.VISIBLE);
        mInputView.requestLayout();
    }

    private void hideSecondaryView(final View secondaryView) {
        if (secondaryView != null && secondaryView.getVisibility() == View.VISIBLE) {
            secondaryView.setVisibility(View.GONE);
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

    public void showClipboardHistory() {
        showSecondaryView(mClipboardHistoryView, () -> {
            hideEmojiView();
            if (mClipboardHistoryManager != null) {
                mClipboardHistoryManager.updateCurrentClip();
                mClipboardHistoryView.setClipboardHistoryManager(mClipboardHistoryManager);
            }
            mClipboardHistoryView.reloadClips();
        });
    }

    public void hideClipboardHistory() {
        if (mClipboardHistoryView != null && mClipboardHistoryView.isSearchActive()) {
            mClipboardHistoryView.closeSearchWithoutReload();
        }
        hideSecondaryView(mClipboardHistoryView);
    }

    public void showEmojiView() {
        showSecondaryView(mEmojiPalettesView, () -> {
            hideClipboardHistory();
            mEmojiPalettesView.reloadRecentEmojis();
        });
    }

    public boolean isEmojiViewShowing() {
        return mEmojiPalettesView != null && mEmojiPalettesView.getVisibility() == View.VISIBLE;
    }

    public boolean isClipboardHistoryShowing() {
        return mClipboardHistoryView != null && mClipboardHistoryView.getVisibility() == View.VISIBLE;
    }

    public void hideEmojiView() {
        hideSecondaryView(mEmojiPalettesView);
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
        hideEmojiView();
        hideClipboardHistory();
        mCanRevertAutocorrect = false;
        mRevertedWord = null;
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

    private void resetInputViewUiState() {
        mCanRevertAutocorrect = false;
        mOriginalTypedWordBeforeAutocorrect = null;
        mAutocorrectedWord = null;
        mRevertedWord = null;
        if (mTopBarView != null) {
            mTopBarView.closeToolTray();
            if (mTopBarView.isExternalViewActive()) {
                mTopBarView.setExternalView(null);
            }
        }
        mKeyboardSwitcher.updateKeyboardTheme();
    }

    private void logEditorInfoDebug(final EditorInfo editorInfo, final boolean restarting) {
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
        final boolean externalActive = mTopBarView != null && mTopBarView.isExternalViewActive();
        Log.i(TAG, "Starting input. Cursor position = "
                + editorInfo.initialSelStart + "," + editorInfo.initialSelEnd
                + " Restarting = " + restarting
                + " fieldId = " + editorInfo.fieldId
                + " lastInlineFieldId = " + mLastInlineFieldId
                + " externalActive = " + externalActive);
    }

    private boolean validateAndLogEditorInfo(final EditorInfo editorInfo, final boolean restarting) {
        if (editorInfo == null) {
            Log.e(TAG, "Null EditorInfo in onStartInputView()");
            if (DebugFlags.DEBUG_ENABLED) {
                throw new NullPointerException("Null EditorInfo in onStartInputView()");
            }
            return false;
        }
        logEditorInfoDebug(editorInfo, restarting);
        return true;
    }

    private void initializeInputLogicForEditor(final EditorInfo editorInfo, final boolean restarting) {
        if (!isImeSuppressedByHardwareKeyboard()) {
            mInputLogic.startInput();
            mInputLogic.mConnection.reloadTextCache(editorInfo, restarting);
        }
    }

    private void setupKeyboardForNewTextField(final EditorInfo editorInfo,
            final MainKeyboardView mainKeyboardView) {
        hideEmojiView();
        hideClipboardHistory();
        loadSettings();
        final SettingsValues refreshedSettingsValues = mSettings.getCurrent();
        mainKeyboardView.closing();
        mKeyboardSwitcher.loadKeyboard(editorInfo, refreshedSettingsValues,
                getCurrentAutoCapsState(), getCurrentRecapitalizeState());
    }

    private void updateKeyboardForEditor(final EditorInfo editorInfo, final boolean restarting,
            final MainKeyboardView mainKeyboardView) {
        final SettingsValues currentSettingsValues = mSettings.getCurrent();
        final boolean isDifferentTextField = !restarting || !currentSettingsValues.isSameInputType(editorInfo);
        if (isDifferentTextField) {
            setupKeyboardForNewTextField(editorInfo, mainKeyboardView);
        } else if (restarting) {
            mKeyboardSwitcher.requestUpdatingShiftState();
        }
    }

    private void postStartInputView() {
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

    void onStartInputViewInternal(final EditorInfo editorInfo, final boolean restarting) {
        super.onStartInputView(editorInfo, restarting);
        resetInputViewUiState();

        if (!validateAndLogEditorInfo(editorInfo, restarting)) {
            return;
        }

        final MainKeyboardView mainKeyboardView = mKeyboardSwitcher.getMainKeyboardView();
        if (mainKeyboardView == null) {
            Log.e(TAG, "onStartInputViewInternal: mainKeyboardView is null");
            return;
        }

        updateFullscreenMode();
        initializeInputLogicForEditor(editorInfo, restarting);
        updateKeyboardForEditor(editorInfo, restarting, mainKeyboardView);
        postStartInputView();
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
        mRevertedWord = null;
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
            // Return false when empty so the framework handles it appropriately
            // without causing unnecessary layout rebuilds or focus loops.
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
        mScratchSuggestions.clear();
        mScratchMerged.clear();
        mScratchDeduplicationSet.clear();
        if (mClipboardHistoryManager != null) {
            mClipboardHistoryManager.deallocateMemory();
        }
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

            mKeyboardSwitcher.requestUpdatingShiftState();
            updateSuggestions();
        }
    }

    private void clearLoadedDictionariesIfNeeded() {
        if (mLoadedLocale != null || mLoadedLanguages != null) {
            mLoadedLocale = null;
            mLoadedLanguages = null;
            mBinaryTrieDictionary = null;
            mBeamSearchDecoder = null;
            if (mTransformerModel != null) {
                mTransformerModel.unload();
            }
            mTransformerModel = null;
            mPrefixDictionary.clear();
        }
    }

    private boolean checkAndHandleSuggestionsDisabled() {
        final SettingsValues settingsValues = mSettings.getCurrent();
        if (settingsValues != null && !settingsValues.mShowSuggestions) {
            clearLoadedDictionariesIfNeeded();
            return true;
        }
        return false;
    }

    private void collectLanguagesFromSubtypes(final java.util.Set<String> enabledLangs) {
        final java.util.Set<rkr.simplekeyboard.inputmethod.latin.Subtype> subtypes =
                mRichImm.getEnabledSubtypes(false);
        if (subtypes == null) {
            return;
        }
        for (final rkr.simplekeyboard.inputmethod.latin.Subtype st : subtypes) {
            final String l = st.getLocale();
            if (l != null && l.length() >= 2) {
                enabledLangs.add(l.substring(0, 2).toLowerCase());
            }
        }
    }

    private java.util.Set<String> resolveEnabledLanguages(final String currentLang) {
        final java.util.Set<String> enabledLangs = new java.util.LinkedHashSet<>();
        if (mRichImm != null) {
            collectLanguagesFromSubtypes(enabledLangs);
        }
        if (enabledLangs.isEmpty()) {
            enabledLangs.add(currentLang);
        }
        return enabledLangs;
    }

    private boolean isDictionaryAlreadyLoaded(final Locale currentLocale,
            final java.util.Set<String> enabledLangs) {
        if (currentLocale == null || !currentLocale.equals(mLoadedLocale)
                || !enabledLangs.equals(mLoadedLanguages)) {
            return false;
        }
        final String currentLang = currentLocale.getLanguage();
        final rkr.simplekeyboard.inputmethod.latin.dict.CustomDictionaryManager customDictMgr = mCustomDictionaryManager != null
                ? mCustomDictionaryManager
                : rkr.simplekeyboard.inputmethod.latin.dict.CustomDictionaryManager.getInstance();
        final java.io.File customDictFile = customDictMgr.getCustomDictionaryFile(this, currentLang);
        final boolean hasCustomDict = customDictFile != null;
        final boolean hasBinaryDict = mBinaryTrieDictionary != null;
        final java.io.File transformerFile = customDictMgr.getTransformerModelFile(this, currentLang);
        final boolean hasTransformerFile = transformerFile != null;
        final boolean hasTransformerModel = mTransformerModel != null;
        return hasCustomDict == hasBinaryDict && hasTransformerFile == hasTransformerModel;
    }

    private boolean isExecutorAvailable() {
        return !mDictExecutor.isShutdown() && !mDictExecutor.isTerminated();
    }

    private void applyLoadedDictionary(final BinaryTrieDictionary binaryDict,
            final BeamSearchDecoder decoder, final MicroTransformerModel transformerModel,
            final int loadGeneration) {
        if (loadGeneration != mDictLoadGeneration) {
            if (transformerModel != null) {
                transformerModel.unload();
            }
            return;
        }
        if (mTransformerModel != null && mTransformerModel != transformerModel) {
            mTransformerModel.unload();
        }
        mBinaryTrieDictionary = binaryDict;
        mBeamSearchDecoder = decoder;
        mTransformerModel = transformerModel;
        mPrefixDictionary.setBinaryDictionary(binaryDict);
        mPrefixDictionary.setTransformerModel(transformerModel);
        final SettingsValues currentSettings = mSettings.getCurrent();
        if (currentSettings != null) {
            mPrefixDictionary.setAutoCorrectionThreshold(currentSettings.mAutoCorrectionThreshold);
        }
        updateSuggestions();
    }

    private void postDictionaryLoadResult(final BinaryTrieDictionary binaryDict,
            final BeamSearchDecoder decoder, final MicroTransformerModel transformerModel,
            final int loadGeneration) {
        mHandler.post(() -> applyLoadedDictionary(binaryDict, decoder, transformerModel, loadGeneration));
    }

    private void loadDictionaryTask(final String currentLang, final int loadGeneration) {
        BinaryTrieDictionary newBinaryDict = null;
        BeamSearchDecoder newDecoder = null;
        MicroTransformerModel newTransformerModel = null;
        final rkr.simplekeyboard.inputmethod.latin.dict.CustomDictionaryManager customDictMgr = mCustomDictionaryManager != null
                ? mCustomDictionaryManager
                : rkr.simplekeyboard.inputmethod.latin.dict.CustomDictionaryManager.getInstance();
        final java.io.File customDictFile = customDictMgr.getCustomDictionaryFile(this, currentLang);
        if (customDictFile != null) {
            try (java.io.FileInputStream fis = new java.io.FileInputStream(customDictFile);
                 java.nio.channels.FileChannel channel = fis.getChannel()) {
                final ByteBuffer buffer = channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, customDictFile.length());
                newBinaryDict = new BinaryTrieDictionary(buffer);
                newDecoder = new BeamSearchDecoder(newBinaryDict, mSpatialTouchModel);
                Log.i(TAG, "Loaded custom dictionary for " + currentLang + ": " + customDictFile.getAbsolutePath());
            } catch (Exception e) {
                Log.w(TAG, "Could not load custom dictionary for " + currentLang, e);
            }
        }
        // Cargar modelo transformer si existe
        final File transformerFile = customDictMgr.getTransformerModelFile(this, currentLang);
        if (transformerFile != null) {
            final MicroTransformerModel trf = new MicroTransformerModel();
            if (trf.loadModel(transformerFile)) {
                newTransformerModel = trf;
                Log.i(TAG, "Loaded Micro-Transformer model: " + transformerFile.getName()
                        + " (vocab=" + trf.getVocabSize() + ", dim=" + trf.getModelDim() + ")");
            } else {
                Log.w(TAG, "Failed to load transformer model: " + transformerFile.getName());
            }
        }
        try {
            final UserDictionaryManager manager = mUserDictionaryManager != null
                    ? mUserDictionaryManager
                    : UserDictionaryManager.getInstance(this);
            manager.applyDecay(System.currentTimeMillis(), UserDictionaryManager.DEFAULT_DECAY_INTERVAL_MILLIS, UserDictionaryManager.DEFAULT_DECAY_STEP);
            final java.util.List<UserDictionaryEntry> blocked = manager.getBlockedWords();
            for (final UserDictionaryEntry b : blocked) {
                mPrefixDictionary.blockWord(b.word);
            }
            final java.util.List<UserDictionaryEntry> learned = manager.getLearnedWords();
            for (final UserDictionaryEntry l : learned) {
                mPrefixDictionary.insert(l.word, l.frequency);
            }
            final java.util.List<UserBigramEntry> bigrams = manager.getBigrams();
            for (final UserBigramEntry bg : bigrams) {
                mPrefixDictionary.loadBigram(bg.prevWord, bg.word, bg.frequency);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not hydrate user dictionary in loadDictionaryTask", e);
        }
        postDictionaryLoadResult(newBinaryDict, newDecoder, newTransformerModel, loadGeneration);
    }

    private void executeDictionaryLoad(final String currentLang, final int loadGeneration) {
        try {
            mDictExecutor.execute(() -> loadDictionaryTask(currentLang, loadGeneration));
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            Log.w(TAG, "Executor rejected", ignored);
            // Executor shutting down or terminated
        }
    }

    private void loadDictionaryForLocale(final Locale currentLocale) {
        if (checkAndHandleSuggestionsDisabled()) {
            return;
        }

        final String currentLang = (currentLocale != null) ? currentLocale.getLanguage() : "es";
        final java.util.Set<String> enabledLangs = resolveEnabledLanguages(currentLang);

        if (isDictionaryAlreadyLoaded(currentLocale, enabledLangs) || !isExecutorAvailable()) {
            return;
        }

        mLoadedLocale = currentLocale;
        mLoadedLanguages = new java.util.HashSet<>(enabledLangs);
        final int loadGeneration = ++mDictLoadGeneration;
        executeDictionaryLoad(currentLang, loadGeneration);
    }

    public void updateSuggestions() {
        if (mTopBarView != null && mTopBarView.isExternalViewActive()) {
            return;
        }
        if (isSuggestionsDisabled()) {
            return;
        }
        final String wordAtCursor = mInputLogic.mConnection.getWordAtCursor();
        final String word = !isWordEmpty(wordAtCursor) ? wordAtCursor : mInputLogic.mConnection.getWordBeforeCursor();
        final String[] context = getEffectivePreviousWords();
        final String w1 = context[0];
        final String w2 = context[1];

        if (isWordEmpty(word)) {
            mPendingAutoCorrection = null;
            mPendingAutoCorrectionWord = null;
            displayEmptyWordSuggestions(w1, w2);
            return;
        }

        final long seq = mSuggestionSeq.incrementAndGet();
        mDictExecutor.execute(() -> {
            if (seq != mSuggestionSeq.get()) {
                return;
            }
            displayComposingSuggestions(word, w1, w2, seq);
        });
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
        final SettingsValues currentSettings = mSettings.getCurrent();
        if (currentSettings == null || currentSettings.mInputAttributes == null) {
            return false;
        }
        if (currentSettings.mInputAttributes.mIsPasswordField) {
            return true;
        }
        if (currentSettings.mInputAttributes.mShouldShowSuggestions) {
            return false;
        }
        if (currentSettings.mInputAttributes.mIsUrlOrEmailField && currentSettings.mSuggestionsInUrls) {
            return false;
        }
        return true;
    }

    private void displayEmptyWordSuggestions(final String w1, final String w2) {
        if (displayClipboardChipIfAvailable()) {
            return;
        }
        if (w2 == null || w2.trim().isEmpty()) {
            if (mTopBarView != null) {
                mTopBarView.setSuggestions(null, -1);
            }
            return;
        }
        final long seq = mSuggestionSeq.incrementAndGet();
        mDictExecutor.execute(() -> {
            if (seq != mSuggestionSeq.get()) {
                return;
            }
            final java.util.List<CharSequence> nextWordPredictions = mPrefixDictionary.getNextWordPredictions(w1, w2, 3);
            if (seq == mSuggestionSeq.get() && mTopBarView != null) {
                mTopBarView.post(() -> {
                    if (seq == mSuggestionSeq.get() && mTopBarView != null) {
                        if (nextWordPredictions != null && !nextWordPredictions.isEmpty()) {
                            mTopBarView.setSuggestions(nextWordPredictions, -1);
                        } else {
                            mTopBarView.setSuggestions(null, -1);
                        }
                    }
                });
            }
        });
    }

    private boolean displayClipboardChipIfAvailable() {
        if (mClipboardHistoryManager == null || !mSettings.getCurrent().mClipboardEnabled) {
            return false;
        }

        final ClipboardHistoryManager.ScreenshotInfo screenshotInfo =
                mSettings.getCurrent().mSuggestScreenshots
                        ? mClipboardHistoryManager.getRecentScreenshotForSuggestion()
                        : null;

        final String recentClip =
                mSettings.getCurrent().mClipboardSuggestionsEnabled
                        ? mClipboardHistoryManager.getRecentClipForSuggestion()
                        : null;

        if (screenshotInfo != null && recentClip != null) {
            final long clipTime = mClipboardHistoryManager.getLastTextTime();
            final long screenshotTime = screenshotInfo.dateAdded;

            // If the clipboard text is newer or equal to the screenshot, show text chip
            if (clipTime >= screenshotTime) {
                mTopBarView.setClipboardSuggestion(recentClip);
                return true;
            } else {
                final Bitmap thumb = mClipboardHistoryManager.getScreenshotThumbnail(screenshotInfo);
                final String uriString = screenshotInfo.fullPath != null ? screenshotInfo.fullPath : screenshotInfo.uri.toString();
                mTopBarView.setScreenshotSuggestion(uriString, thumb);
                return true;
            }
        } else if (screenshotInfo != null) {
            final Bitmap thumb = mClipboardHistoryManager.getScreenshotThumbnail(screenshotInfo);
            final String uriString = screenshotInfo.fullPath != null ? screenshotInfo.fullPath : screenshotInfo.uri.toString();
            mTopBarView.setScreenshotSuggestion(uriString, thumb);
            return true;
        } else if (recentClip != null) {
            mTopBarView.setClipboardSuggestion(recentClip);
            return true;
        }

        return false;
    }

    public void onImageSelected(final String imageUri) {
        if (imageUri == null || imageUri.trim().isEmpty()) {
            Log.w(TAG, "onImageSelected: imageUri is null or empty");
            return;
        }
        final EditorInfo editorInfo = getCurrentInputEditorInfo();
        if (editorInfo == null) {
            Log.w(TAG, "onImageSelected: editorInfo is null");
            return;
        }

        try {
            Uri contentUri;
            if (imageUri.startsWith("content://")) {
                contentUri = Uri.parse(imageUri);
            } else {
                final File file = new File(imageUri);
                if (!file.exists()) {
                    Log.w(TAG, "onImageSelected: file does not exist: " + imageUri);
                    return;
                }
                final Context context = PreferenceManagerCompat.getDeviceContext(this);
                contentUri = FileProvider.getUriForFile(context, getPackageName() + ".fileprovider", file);
            }

            final String mimeType = getContentResolver().getType(contentUri);
            final String finalMimeType = mimeType != null ? mimeType : "image/png";

            final InputContentInfoCompat inputContentInfoCompat = new InputContentInfoCompat(
                    contentUri,
                    new ClipDescription("Clipboard Image", new String[]{finalMimeType}),
                    null);

            final android.view.inputmethod.InputConnection ic = getCurrentInputConnection();
            if (ic == null) {
                Log.w(TAG, "onImageSelected: ic is null");
                return;
            }

            int flags = 0;
            if (BuildCompatUtils.isAtLeastNMR1()) {
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

    private void appendSuggestions(final java.util.List<CharSequence> source) {
        if (source == null) {
            return;
        }
        for (final CharSequence s : source) {
            if (mScratchMerged.size() >= 3) {
                break;
            }
            if (mScratchDeduplicationSet.add(s.toString().toLowerCase(java.util.Locale.US))) {
                mScratchMerged.add(s);
            }
        }
    }

    private java.util.List<CharSequence> getSuggestionsForWord(final String word, final String w1, final String w2) {
        mScratchMerged.clear();
        mScratchDeduplicationSet.clear();

        if (mPrefixDictionary != null) {
            appendSuggestions(mPrefixDictionary.getSuggestions(word, 3, w1, w2));
        }
        if (mBeamSearchDecoder != null && mScratchMerged.size() < 3) {
            appendSuggestions(mBeamSearchDecoder.getSuggestions(word, 3, w2));
        }
        return mScratchMerged;
    }

    private CharSequence getDecoderBestCorrection(final String word, final String prevWord) {
        if (mBeamSearchDecoder != null) {
            return mBeamSearchDecoder.getBestCorrection(word, -30.0f, prevWord);
        }
        return null;
    }

    private CharSequence resolveBestCorrection(final String word, final String w1, final String w2) {
        if (!mSettings.getCurrent().mAutoCorrectionEnabled || isWordEmpty(word)) {
            return null;
        }
        if (mPrefixDictionary != null) {
            final CharSequence exactNorm = mPrefixDictionary.getExactNormalizedCorrection(word);
            if (exactNorm != null) {
                return exactNorm;
            }
        }
        final CharSequence decoderCorrection = getDecoderBestCorrection(word, w2);
        if (decoderCorrection != null) {
            return decoderCorrection;
        }
        if (mPrefixDictionary != null) {
            return mPrefixDictionary.getBestCorrection(word, w1, w2);
        }
        return null;
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

    private void appendMatchingSuggestions(final java.util.List<CharSequence> suggestions,
            final java.util.List<CharSequence> matches, final String word) {
        if (matches != null) {
            for (CharSequence m : matches) {
                if (suggestions.size() >= 3) break;
                if (!m.toString().equalsIgnoreCase(word) && !StringUtils.containsIgnoreCase(suggestions, m)) {
                    suggestions.add(m);
                }
            }
        }
    }

    private void displayComposingSuggestions(final String word, final String w1, final String w2, final long seq) {
        mScratchSuggestions.clear();
        mScratchSuggestions.add("\"" + word + "\"");

        final java.util.List<CharSequence> matches = getSuggestionsForWord(word, w1, w2);
        final CharSequence bestCorrection = resolveBestCorrection(word, w1, w2);

        final int boldIndex;
        if (bestCorrection != null) {
            appendCorrectionAndCandidates(mScratchSuggestions, word, w1, w2, bestCorrection, matches);
            boldIndex = 1;
            mPendingAutoCorrection = bestCorrection;
            mPendingAutoCorrectionWord = word;
        } else {
            appendMatchingSuggestions(mScratchSuggestions, matches, word);
            boldIndex = -1;
            mPendingAutoCorrection = null;
            mPendingAutoCorrectionWord = null;
        }

        if (seq == mSuggestionSeq.get() && mTopBarView != null) {
            mTopBarView.post(() -> {
                if (seq == mSuggestionSeq.get() && mTopBarView != null) {
                    mTopBarView.setSuggestions(mScratchSuggestions, boldIndex);
                }
            });
        }
    }

    @Override
    public void hideWindow() {
        hideEmojiView();
        hideClipboardHistory();
        mKeyboardSwitcher.onHideWindow();

        if (TRACE) Debug.stopMethodTracing();
        dismissOptionDialog();
        super.hideWindow();
    }

    private static boolean isViewVisible(final View view) {
        return view != null && view.getVisibility() == View.VISIBLE;
    }

    private View getVisibleKeyboardViewOrNull() {
        if (mInputView == null) {
            return null;
        }
        return mKeyboardSwitcher.getVisibleKeyboardView();
    }

    private boolean isAnyOverlayVisible() {
        return isViewVisible(mClipboardHistoryView) || isViewVisible(mEmojiPalettesView);
    }

    private boolean isImeContentHiddenByHardware(final boolean isOverlayVisible, final boolean isKeyboardShown) {
        return !isOverlayVisible && !isKeyboardShown && isImeSuppressedByHardwareKeyboard();
    }

    private int computeKeyboardWithTopBarHeight(final View visibleKeyboardView) {
        final View topBar = mInputView.findViewById(rkr.simplekeyboard.inputmethod.R.id.top_bar_view);
        final int topBarHeight = isViewVisible(topBar) ? topBar.getHeight() : 0;
        return visibleKeyboardView.getHeight() + topBarHeight;
    }

    private int computeVisibleViewHeight(final View visibleKeyboardView) {
        if (isViewVisible(mClipboardHistoryView)) {
            if (isClipboardSearchActive()) {
                return mClipboardHistoryView.getHeight() + computeKeyboardWithTopBarHeight(visibleKeyboardView);
            }
            return mClipboardHistoryView.getHeight();
        }
        if (isViewVisible(mEmojiPalettesView)) {
            return mEmojiPalettesView.getHeight();
        }
        return computeKeyboardWithTopBarHeight(visibleKeyboardView);
    }

    private boolean shouldExtendTouchToTop(final boolean isOverlayVisible) {
        return !isOverlayVisible && mKeyboardSwitcher.isShowingMoreKeysPanel();
    }

    private void updateTouchableInsetsRegion(final InputMethodService.Insets outInsets,
            final boolean isOverlayVisible, final int visibleTopY, final int inputHeight) {
        final int touchTop = shouldExtendTouchToTop(isOverlayVisible) ? 0 : visibleTopY;
        outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION;
        outInsets.touchableRegion.set(0, touchTop, mInputView.getWidth(),
                inputHeight + EXTENDED_TOUCHABLE_REGION_HEIGHT);
    }

    private void applyInsets(final InputMethodService.Insets outInsets, final int topInsets) {
        outInsets.contentTopInsets = topInsets;
        outInsets.visibleTopInsets = topInsets;
        if (mInsetsUpdater != null) {
            mInsetsUpdater.setInsets(outInsets);
        }
    }

    @Override
    public void onComputeInsets(final InputMethodService.Insets outInsets) {
        super.onComputeInsets(outInsets);
        final View visibleKeyboardView = getVisibleKeyboardViewOrNull();
        if (visibleKeyboardView == null) {
            return;
        }
        final int inputHeight = mInputView.getHeight();
        final boolean isOverlayVisible = isAnyOverlayVisible();
        final boolean isKeyboardShown = visibleKeyboardView.isShown();

        if (isImeContentHiddenByHardware(isOverlayVisible, isKeyboardShown)) {
            applyInsets(outInsets, inputHeight);
            return;
        }

        final int visibleHeight = computeVisibleViewHeight(visibleKeyboardView);
        final int visibleTopY = Math.max(0, inputHeight - visibleHeight);

        if (isOverlayVisible || isKeyboardShown) {
            updateTouchableInsetsRegion(outInsets, isOverlayVisible, visibleTopY, inputHeight);
        }

        applyInsets(outInsets, visibleTopY);
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
        if (mSettings != null && mSettings.getCurrent() != null && mSettings.getCurrent().mDisableLandscapeFullscreen) {
            return false;
        }
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
    public int getMaxWidth() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            final android.view.WindowManager wm = (android.view.WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                return wm.getCurrentWindowMetrics().getBounds().width();
            }
        }
        final android.view.Window window = getWindow().getWindow();
        if (window != null && window.getDecorView() != null && window.getDecorView().getWidth() > 0) {
            return window.getDecorView().getWidth();
        }
        return getResources().getDisplayMetrics().widthPixels;
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

    public int getCurrentAutoCapsState() {
        return mInputLogic.getCurrentAutoCapsState(mSettings.getCurrent(),
                mRichImm.getCurrentSubtype().getKeyboardLayoutSet());
    }

    public int getCurrentRecapitalizeState() {
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

    private void dismissOptionDialog() {
        if (mOptionsDialog != null) {
            if (mOptionsDialog.isShowing()) {
                mOptionsDialog.dismiss();
            }
            mOptionsDialog = null;
        }
    }

    private void showForgetWordDialog(final String word) {
        if (mInputView == null) {
            Log.w(TAG, "showForgetWordDialog: mInputView or windowToken is null");
            return;
        }
        final android.os.IBinder windowToken = mInputView.getWindowToken();
        if (windowToken == null) {
            Log.w(TAG, "showForgetWordDialog: mInputView or windowToken is null");
            return;
        }

        dismissOptionDialog();

        final AlertDialog.Builder builder = DialogUtils.createMaterialDialogBuilder(this);
        builder.setTitle(R.string.forget_word_title);
        builder.setMessage(getString(R.string.forget_word_message, word));
        builder.setPositiveButton(android.R.string.ok, (dialog, which) -> {
            mPrefixDictionary.blockWord(word);
            if (isExecutorAvailable()) {
                mDictExecutor.execute(() -> {
                    if (mUserDictionaryManager != null) {
                        mUserDictionaryManager.blockWord(word);
                    } else {
                        UserDictionaryManager.getInstance(this).blockWord(word);
                    }
                });
            }
            updateSuggestions();
            dialog.dismiss();
        });
        builder.setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.dismiss());

        final AlertDialog dialog = builder.create();
        mOptionsDialog = dialog;
        DialogUtils.setupAndShowDialog(dialog, windowToken);
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
            if (steps < 0) {
                mInputLogic.sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT, -steps);
            } else if (steps > 0) {
                mInputLogic.sendDownUpKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT, steps);
            }
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
            if (steps < 0) {
                mInputLogic.sendDownUpKeyEvent(KeyEvent.KEYCODE_DEL, -steps);
            }
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
        return codePoint == Constants.CODE_SPACE || StringUtils.isPunctuationOrSymbol(codePoint);
    }

    // This method is public for testability of LatinIME, but also in the future it should
    // completely replace #onCodeInput.
    public void onEvent(final Event event) {
        closeToolTrayIfOpen();
        if (mTopBarView != null && mTopBarView.isExternalViewActive()) {
            mTopBarView.setExternalView(null);
        }

        if (isClipboardSearchActive()) {
            if (isBackspaceEvent(event)) {
                mClipboardHistoryView.deleteSearchChar();
                return;
            }
            if (event.isFunctionalKeyEvent()) {
                if (event.mKeyCode == Constants.CODE_SHIFT
                        || event.mKeyCode == Constants.CODE_SYMBOL_SHIFT
                        || event.mKeyCode == Constants.CODE_SWITCH_ALPHA_SYMBOL
                        || event.mKeyCode == Constants.CODE_CAPSLOCK) {
                    mKeyboardSwitcher.onEvent(event);
                }
                return;
            }
            if (event.mCodePoint > 0) {
                if (event.mCodePoint == Constants.CODE_ENTER) {
                    return;
                }
                mClipboardHistoryView.appendSearchText(StringUtils.newSingleCodePointString(event.mCodePoint));
                mKeyboardSwitcher.onEvent(event);
            }
            return;
        }

        if (handleBackspaceRevert(event)) {
            return;
        }
        handleAutoCorrect(event);

        final InputTransaction completeInputTransaction =
                mInputLogic.onCodeInput(mSettings.getCurrent(), event);
        updateStateAfterInputTransaction(completeInputTransaction);
        mKeyboardSwitcher.onEvent(event);
        updateSuggestions();
    }

    private void closeToolTrayIfOpen() {
        if (mTopBarView != null && mTopBarView.isToolTrayOpen()) {
            mTopBarView.closeToolTray();
        }
    }

    private boolean handleBackspaceRevert(final Event event) {
        if (!isBackspaceEvent(event)) {
            if (isLetterOrDigitKey(event)) {
                mCanRevertAutocorrect = false;
                mRevertedWord = null;
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

    private static boolean isLetterOrDigitKey(final Event event) {
        return !event.isFunctionalKeyEvent() && Character.isLetterOrDigit(event.mCodePoint);
    }

    private boolean isAutocorrectRevertible() {
        return mCanRevertAutocorrect && mAutocorrectedWord != null && mOriginalTypedWordBeforeAutocorrect != null;
    }

    private boolean tryExecuteBackspaceRevert(final Event event) {
        if (!isAutocorrectRevertible()) {
            return false;
        }
        final String textBefore = mInputLogic.mConnection.getTextBeforeCursor(mAutocorrectedWord.length() + 4, 0);
        if (textBefore == null) {
            return false;
        }
        if (textBefore.endsWith(mAutocorrectedWord + " ")) {
            executeBackspaceRevert(event, 1);
            return true;
        } else if (textBefore.endsWith(mAutocorrectedWord)) {
            executeBackspaceRevert(event, 0);
            return true;
        } else if (textBefore.length() > mAutocorrectedWord.length()) {
            final int wordIndex = textBefore.lastIndexOf(mAutocorrectedWord);
            if (wordIndex >= 0) {
                final int suffixLen = textBefore.length() - (wordIndex + mAutocorrectedWord.length());
                if (suffixLen > 0 && suffixLen <= 2) {
                    final String suffix = textBefore.substring(wordIndex + mAutocorrectedWord.length());
                    if (textBefore.endsWith(mAutocorrectedWord + suffix)) {
                        executeBackspaceRevert(event, suffixLen);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void executeBackspaceRevert(final Event event, final int trailingCharsToDelete) {
        final String revertedWord = mOriginalTypedWordBeforeAutocorrect;
        mInputLogic.mConnection.deleteTextBeforeCursor(mAutocorrectedWord.length() + trailingCharsToDelete);
        mInputLogic.mConnection.commitText(revertedWord, 1);

        mCanRevertAutocorrect = false;
        mOriginalTypedWordBeforeAutocorrect = null;
        mAutocorrectedWord = null;
        mRevertedWord = revertedWord;
        mKeyboardSwitcher.onEvent(event);
        updateSuggestions();
    }

    private boolean isAutoCorrectTriggerEvent(final Event event) {
        if (event.isFunctionalKeyEvent()) {
            return event.mKeyCode == Constants.CODE_ENTER;
        }
        final int codePoint = event.mCodePoint;
        return codePoint == Constants.CODE_SPACE
                || codePoint == '\n'
                || mSettings.getCurrent().isWordSeparator(codePoint)
                || StringUtils.isPunctuationOrSymbol(codePoint);
    }

    private boolean shouldPerformAutoCorrection(final String word) {
        if (!mSettings.getCurrent().mAutoCorrectionEnabled || shouldSuppressSuggestions()) {
            return false;
        }
        return !isWordEmpty(word);
    }

    private String applyAutoCorrection(final String word, final String w1, final String w2) {
        if (!shouldPerformAutoCorrection(word)) {
            mCanRevertAutocorrect = false;
            return word;
        }
        if (mRevertedWord != null && mRevertedWord.equals(word)) {
            mCanRevertAutocorrect = false;
            return word;
        }
        mRevertedWord = null;

        final CharSequence correction;
        if (mPendingAutoCorrection != null && word.equals(mPendingAutoCorrectionWord)) {
            correction = mPendingAutoCorrection;
        } else {
            correction = resolveBestCorrection(word, w1, w2);
        }
        mPendingAutoCorrection = null;
        mPendingAutoCorrectionWord = null;

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
        final int firstSpace = cleanCommitted.indexOf(' ');
        if (firstSpace >= 0) {
            String prev = w2;
            String prevPrev = w1;
            int start = 0;
            final int len = cleanCommitted.length();
            while (start < len) {
                while (start < len && Character.isWhitespace(cleanCommitted.charAt(start))) {
                    start++;
                }
                if (start >= len) {
                    break;
                }
                int end = start;
                while (end < len && !Character.isWhitespace(cleanCommitted.charAt(end))) {
                    end++;
                }
                final String p = cleanCommitted.substring(start, end);
                learnNgramAsync(prevPrev, prev, p);
                prevPrev = prev;
                prev = p;
                start = end;
            }
            return;
        }
        learnNgramAsync(w1, w2, cleanCommitted);
    }

    private void handleAutoCorrect(final Event event) {
        if (!isAutoCorrectTriggerEvent(event)) {
            return;
        }
        final String word = mInputLogic.mConnection.getWordBeforeCursor();
        final String[] context = getEffectivePreviousWords();
        final String w1 = context[0];
        final String w2 = context[1];
        final String committedWord = applyAutoCorrection(word, w1, w2);
        recordCommittedWord(committedWord, w1, w2);
    }

    private String[] getEffectivePreviousWords() {
        return mInputLogic.mConnection.getTwoPreviousWordsBeforeCursor();
    }

    private static boolean isWordEmpty(final String word) {
        return rkr.simplekeyboard.inputmethod.latin.common.StringUtils.isBlank(word);
    }

    private void learnNgramAsync(final String w1, final String w2, final String word) {
        if (isWordEmpty(word)) {
            return;
        }
        final SettingsValues settings = mSettings.getCurrent();
        if (settings != null && settings.mInputAttributes != null && settings.mInputAttributes.mNoPersonalizedLearning) {
            return;
        }
        if (settings != null && !settings.mAutoLearnEnabled) {
            return;
        }
        final String cleanWord = word.trim();
        final String canonicalWord = (!rkr.simplekeyboard.inputmethod.latin.common.StringUtils.hasInternalUpperCase(cleanWord)
                && Character.isUpperCase(cleanWord.charAt(0)))
                ? cleanWord.toLowerCase(java.util.Locale.ROOT)
                : cleanWord;
        mDictExecutor.execute(() -> {
            mPrefixDictionary.insert(canonicalWord, PrefixDictionary.BASE_LEARNED_FREQUENCY);
            final UserDictionaryManager manager = mUserDictionaryManager != null
                    ? mUserDictionaryManager
                    : UserDictionaryManager.getInstance(this);
            manager.addWord(canonicalWord, PrefixDictionary.BASE_LEARNED_FREQUENCY);
            if (!isWordEmpty(w2)) {
                final String cleanW2 = w2.trim();
                mPrefixDictionary.setBigram(cleanW2, canonicalWord, PrefixDictionary.BASE_LEARNED_FREQUENCY);
                final int bigramFreq = mPrefixDictionary.getBigramFrequency(cleanW2, canonicalWord);
                manager.addBigram(cleanW2, canonicalWord, bigramFreq);
                if (!isWordEmpty(w1)) {
                    mPrefixDictionary.setTrigram(w1.trim(), cleanW2, canonicalWord, PrefixDictionary.BASE_LEARNED_FREQUENCY);
                }
            }
        });
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
        if (isClipboardSearchActive()) {
            if (rawText != null && !rawText.isEmpty()) {
                mClipboardHistoryView.appendSearchText(rawText);
            }
            return;
        }
        // TODO: have the keyboard pass the correct key code when we need it.
        final Event event = Event.createSoftwareTextEvent(rawText, Constants.CODE_OUTPUT_TEXT);
        final InputTransaction completeInputTransaction =
                mInputLogic.onTextInput(mSettings.getCurrent(), event);
        updateStateAfterInputTransaction(completeInputTransaction);
        mKeyboardSwitcher.onEvent(event);
    }

    // Called from PointerTracker through the KeyboardActionListener interface
    @Override
    public void onFinishSlidingInput() {
        // User finished sliding input.
        mKeyboardSwitcher.onFinishSlidingInput();
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
            mKeyboardSwitcher.requestUpdatingShiftState();
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
        final AudioAndHapticFeedbackManager feedbackManager = mFeedbackManager;
        if (repeatCount == 0) {
            // TODO: Reconsider how to perform haptic feedback when repeating key.
            feedbackManager.performHapticFeedback(keyboardView);
        }
        feedbackManager.performAudioFeedback(code);
    }

    private void hapticTickFeedback() {
        final AudioAndHapticFeedbackManager feedbackManager = mFeedbackManager;
        feedbackManager.performTickFeedback();
    }

    // Callback of the {@link KeyboardActionListener}. This is called when a key is depressed;
    // release matching call is {@link #onPressKey(int,boolean)} below.
    @Override
    public void onPressKey(final int primaryCode, final int repeatCount,
            final boolean isSinglePointer) {
        mKeyboardSwitcher.onPressKey(primaryCode, isSinglePointer);
        hapticAndAudioFeedback(primaryCode, repeatCount);
    }

    // Callback of the {@link KeyboardActionListener}. This is called when a key is released;
    // press matching call is {@link #onPressKey(int,int,boolean)} above.
    @Override
    public void onReleaseKey(final int primaryCode, final boolean withSliding) {
        mKeyboardSwitcher.onReleaseKey(primaryCode, withSliding);
    }

    // receive ringer mode change and user unlock.
    private final BroadcastReceiver mRingerModeChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            final String action = intent.getAction();
            if (AudioManager.RINGER_MODE_CHANGED_ACTION.equals(action)) {
                if (mFeedbackManager != null) {
                    mFeedbackManager.onRingerModeChanged();
                } else {
                    AudioAndHapticFeedbackManager.getInstance().onRingerModeChanged();
                }
            } else if (Intent.ACTION_USER_UNLOCKED.equals(action)) {
                if (mClipboardHistoryManager != null) {
                    try {
                        mClipboardHistoryManager.start();
                    } catch (Throwable ignored) {
                        Log.w(TAG, "Unexpected error", ignored);
                    }
                }
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

    public boolean isClipboardSearchActive() {
        return mClipboardHistoryView != null && mClipboardHistoryView.getVisibility() == View.VISIBLE && mClipboardHistoryView.isSearchActive();
    }

    @Override
    public boolean onKeyDown(final int keyCode, final KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN) {
            if (mClipboardHistoryView != null && mClipboardHistoryView.getVisibility() == View.VISIBLE) {
                if (mClipboardHistoryView.isSearchActive()) {
                    mClipboardHistoryView.closeSearch();
                    return true;
                }
                hideClipboardHistory();
                return true;
            }
            if (mEmojiPalettesView != null && mEmojiPalettesView.getVisibility() == View.VISIBLE) {
                hideEmojiView();
                return true;
            }
        }
        if (isClipboardSearchActive() && event.getAction() == KeyEvent.ACTION_DOWN) {
            if (keyCode == KeyEvent.KEYCODE_DEL) {
                mClipboardHistoryView.deleteSearchChar();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_SPACE) {
                mClipboardHistoryView.appendSearchText(" ");
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                return true;
            }
            if (KeyEvent.isModifierKey(keyCode)) {
                return super.onKeyDown(keyCode, event);
            }
            int unicodeChar = event.getUnicodeChar();
            if (unicodeChar > 0 && !Character.isISOControl(unicodeChar)) {
                mClipboardHistoryView.appendSearchText(StringUtils.newSingleCodePointString(unicodeChar));
                return true;
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(final int keyCode, final KeyEvent event) {
        if (isClipboardSearchActive()) {
            if (KeyEvent.isModifierKey(keyCode)) {
                return super.onKeyUp(keyCode, event);
            }
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onKeyMultiple(final int keyCode, final int count, final KeyEvent event) {
        if (isClipboardSearchActive()) {
            if (event.getCharacters() != null) {
                mClipboardHistoryView.appendSearchText(event.getCharacters());
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DEL) {
                for (int i = 0; i < count; i++) {
                    mClipboardHistoryView.deleteSearchChar();
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_SPACE) {
                for (int i = 0; i < count; i++) {
                    mClipboardHistoryView.appendSearchText(" ");
                }
                return true;
            }
            int unicodeChar = event.getUnicodeChar();
            if (unicodeChar > 0 && !Character.isISOControl(unicodeChar)) {
                final String s = StringUtils.newSingleCodePointString(unicodeChar);
                for (int i = 0; i < count; i++) {
                    mClipboardHistoryView.appendSearchText(s);
                }
                return true;
            }
            return true;
        }
        return super.onKeyMultiple(keyCode, count, event);
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
        if (BuildCompatUtils.isAtLeastR()) {
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
