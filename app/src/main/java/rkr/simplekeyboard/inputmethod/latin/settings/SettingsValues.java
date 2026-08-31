/*
 * Copyright (C) 2011 The Android Open Source Project
 * Copyright (C) 2025 Raimondas Rimkus
 * Copyright (C) 2024 wittmane
 * Copyright (C) 2019 Micha LaQua
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

package rkr.simplekeyboard.inputmethod.latin.settings;

import android.content.SharedPreferences;
import android.content.res.Resources;
import android.view.inputmethod.EditorInfo;

import rkr.simplekeyboard.inputmethod.R;
import rkr.simplekeyboard.inputmethod.latin.InputAttributes;

// Non-final for testing via mock library.
public class SettingsValues {
    public static final float DEFAULT_SIZE_SCALE = 1.0f; // 100%

    // From resources:
    public final SpacingAndPunctuations mSpacingAndPunctuations;
    // From configuration:
    public final boolean mHasHardwareKeyboard;
    // From preferences, in the same order as xml/prefs.xml:
    public final boolean mAutoCap;
    public final boolean mAutoPeriodEnabled;
    public final boolean mVibrateOn;
    public final boolean mSoundOn;
    public final boolean mKeyPreviewPopupOn;
    public final boolean mUseOnScreen;
    public final boolean mShowsLanguageSwitchKey;
    public final boolean mShowLanguageOnSpacebar;
    public final boolean mImeSwitchEnabled;
    public final int mKeyLongpressTimeout;
    public final boolean mShowSpecialChars;
    public final boolean mShowNumberRow;
    public final boolean mSpaceSwipeEnabled;
    public final boolean mDeleteSwipeEnabled;
    public final float mSwipeSensitivity;
    public final boolean mDisableLandscapeFullscreen;
    public final boolean mShowSuggestions;
    public final boolean mSuggestionsInUrls;
    public final boolean mClipboardEnabled;
    public final boolean mClipboardSuggestionsEnabled;
    public final boolean mSuggestScreenshots;
    public final boolean mAutoLearnEnabled;
    public final boolean mAutoCorrectionEnabled;
    public final float mAutoCorrectionThreshold;

    // From the input box
    public final InputAttributes mInputAttributes;

    // Deduced settings
    public final float mKeypressSoundVolume;
    public final int mKeyPreviewPopupDismissDelay;
    public final int mKeyRepeatStartTimeout;
    public final int mKeyRepeatInterval;
    public final int mVibrationDuration;

    // Debug settings
    public final float mKeyboardHeightScale;

    public final int mBottomOffsetPortrait;
    public final int mBottomOffsetLandscape;
    public final int mClipboardMaxClips;

    // Package-private constructor for unit testing
    public SettingsValues(final int bottomOffsetPortrait, final int bottomOffsetLandscape) {
        mSpacingAndPunctuations = null;
        mInputAttributes = null;
        mHasHardwareKeyboard = false;
        mAutoCap = true;
        mAutoPeriodEnabled = false;
        mVibrateOn = true;
        mSoundOn = false;
        mKeyPreviewPopupOn = true;
        mUseOnScreen = false;
        mShowsLanguageSwitchKey = true;
        mShowLanguageOnSpacebar = true;
        mImeSwitchEnabled = false;
        mKeyLongpressTimeout = 300;
        mKeypressSoundVolume = 0.5f;
        mKeyPreviewPopupDismissDelay = 53;
        mKeyRepeatStartTimeout = 400;
        mKeyRepeatInterval = 50;
        mVibrationDuration = 0;
        mKeyboardHeightScale = DEFAULT_SIZE_SCALE;
        mBottomOffsetPortrait = bottomOffsetPortrait;
        mBottomOffsetLandscape = bottomOffsetLandscape;
        mClipboardMaxClips = 50;
        mShowSpecialChars = true;
        mShowNumberRow = false;
        mSpaceSwipeEnabled = false;
        mDeleteSwipeEnabled = false;
        mSwipeSensitivity = 1.0f;
        mDisableLandscapeFullscreen = false;
        mShowSuggestions = true;
        mSuggestionsInUrls = false;
        mClipboardEnabled = true;
        mClipboardSuggestionsEnabled = false;
        mSuggestScreenshots = false;
        mAutoLearnEnabled = true;
        mAutoCorrectionThreshold = 1.0f;
        mAutoCorrectionEnabled = true;
    }

    public SettingsValues(final SharedPreferences prefs, final Resources res,
            final InputAttributes inputAttributes) {
        // Get the resources
        mSpacingAndPunctuations = new SpacingAndPunctuations(res);

        // Store the input attributes
        mInputAttributes = inputAttributes;

        // Get the settings preferences
        mAutoCap = prefs.getBoolean(Settings.PREF_AUTO_CAP, true);
        mAutoPeriodEnabled = prefs.getBoolean(Settings.PREF_AUTO_PERIOD, false);
        mVibrateOn = Settings.readVibrationEnabled(prefs, res);
        mSoundOn = prefs.getBoolean(Settings.PREF_SOUND_ON, res.getBoolean(R.bool.config_default_sound_enabled));
        mKeyPreviewPopupOn = prefs.getBoolean(Settings.PREF_POPUP_ON, res.getBoolean(R.bool.config_default_key_preview_popup));
        mUseOnScreen = prefs.getBoolean(Settings.PREF_USE_ON_SCREEN, false);
        mShowsLanguageSwitchKey = prefs.getBoolean(Settings.PREF_SHOW_LANGUAGE_SWITCH_KEY, true);
        mShowLanguageOnSpacebar = prefs.getBoolean(Settings.PREF_SHOW_LANGUAGE_ON_SPACEBAR, true);
        mImeSwitchEnabled = prefs.getBoolean(Settings.PREF_ENABLE_IME_SWITCH, false);
        mHasHardwareKeyboard = Settings.readHasHardwareKeyboard(res.getConfiguration());

        // Compute other readable settings
        mKeyLongpressTimeout = Settings.readKeyLongpressTimeout(prefs, res);
        mKeypressSoundVolume = Settings.readKeypressSoundVolume(prefs);
        mKeyPreviewPopupDismissDelay = Settings.readKeyPreviewLingerTimeout(prefs, res);
        mKeyRepeatStartTimeout = Settings.readKeyRepeatStartTimeout(prefs, res);
        mKeyRepeatInterval = Settings.readKeyRepeatInterval(prefs, res);
        mVibrationDuration = Settings.readVibrationDuration(prefs);
        mKeyboardHeightScale = Settings.readKeyboardHeight(prefs, DEFAULT_SIZE_SCALE);
        mBottomOffsetPortrait = Settings.readBottomOffsetPortrait(prefs);
        mBottomOffsetLandscape = Settings.readBottomOffsetLandscape(prefs);
        mClipboardMaxClips = Settings.readClipboardMaxClips(prefs);
        mShowSpecialChars = prefs.getBoolean(Settings.PREF_SHOW_SPECIAL_CHARS, true);
        mShowNumberRow = prefs.getBoolean(Settings.PREF_SHOW_NUMBER_ROW, false);
        mSpaceSwipeEnabled = prefs.getBoolean(Settings.PREF_SPACE_SWIPE, false);
        mDeleteSwipeEnabled = prefs.getBoolean(Settings.PREF_DELETE_SWIPE, false);
        mSwipeSensitivity = Settings.readSwipeSensitivity(prefs);
        mDisableLandscapeFullscreen = prefs.getBoolean(Settings.PREF_DISABLE_LANDSCAPE_FULLSCREEN, false);
        mShowSuggestions = prefs.getBoolean(Settings.PREF_SHOW_SUGGESTIONS, true);
        mSuggestionsInUrls = prefs.getBoolean(Settings.PREF_SUGGESTIONS_IN_URLS, false);
        mClipboardEnabled = prefs.getBoolean(Settings.PREF_CLIPBOARD_ENABLED, true);
        mClipboardSuggestionsEnabled = prefs.getBoolean(Settings.PREF_CLIPBOARD_SUGGESTIONS, false);
        mSuggestScreenshots = prefs.getBoolean(Settings.PREF_SUGGEST_SCREENSHOTS, false);
        mAutoLearnEnabled = prefs.getBoolean(Settings.PREF_AUTO_LEARN, true);
        mAutoCorrectionThreshold = Settings.readAutoCorrectionThreshold(prefs);
        mAutoCorrectionEnabled = mAutoCorrectionThreshold > 0.0f;
    }

    public boolean isWordSeparator(final int code) {
        return mSpacingAndPunctuations.isWordSeparator(code);
    }

    public boolean isLanguageSwitchKeyDisabled() {
        return !mShowsLanguageSwitchKey;
    }

    public boolean isSameInputType(final EditorInfo editorInfo) {
        return mInputAttributes.isSameInputType(editorInfo);
    }
}
