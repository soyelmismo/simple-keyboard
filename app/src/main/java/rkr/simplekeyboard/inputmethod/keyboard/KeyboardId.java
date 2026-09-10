/*
 * Copyright (C) 2015 The Android Open Source Project
 * Copyright (C) 2024 wittmane
 * Copyright (C) 2024 Raimondas Rimkus
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

package rkr.simplekeyboard.inputmethod.keyboard;

import android.text.InputType;
import android.text.TextUtils;
import android.util.SparseArray;
import android.view.inputmethod.EditorInfo;

import java.util.Arrays;
import java.util.Locale;

import rkr.simplekeyboard.inputmethod.compat.EditorInfoCompatUtils;
import rkr.simplekeyboard.inputmethod.latin.Subtype;
import rkr.simplekeyboard.inputmethod.latin.utils.InputTypeUtils;

/**
 * Unique identifier for each keyboard type.
 */
public final class KeyboardId {
    public static final int MODE_TEXT = 0;
    public static final int MODE_URL = 1;
    public static final int MODE_EMAIL = 2;
    public static final int MODE_IM = 3;
    public static final int MODE_PHONE = 4;
    public static final int MODE_NUMBER = 5;
    public static final int MODE_DATE = 6;
    public static final int MODE_TIME = 7;
    public static final int MODE_DATETIME = 8;

    public static final int ELEMENT_ALPHABET = 0;
    public static final int ELEMENT_ALPHABET_MANUAL_SHIFTED = 1;
    public static final int ELEMENT_ALPHABET_AUTOMATIC_SHIFTED = 2;
    public static final int ELEMENT_ALPHABET_SHIFT_LOCKED = 3;
    public static final int ELEMENT_SYMBOLS = 5;
    public static final int ELEMENT_SYMBOLS_SHIFTED = 6;
    public static final int ELEMENT_PHONE = 7;
    public static final int ELEMENT_PHONE_SYMBOLS = 8;
    public static final int ELEMENT_NUMBER = 9;

    private static final SparseArray<String> ELEMENT_ID_TO_NAME = new SparseArray<>();
    private static final SparseArray<String> MODE_TO_NAME = new SparseArray<>();

    static {
        ELEMENT_ID_TO_NAME.put(ELEMENT_ALPHABET, "alphabet");
        ELEMENT_ID_TO_NAME.put(ELEMENT_ALPHABET_MANUAL_SHIFTED, "alphabetManualShifted");
        ELEMENT_ID_TO_NAME.put(ELEMENT_ALPHABET_AUTOMATIC_SHIFTED, "alphabetAutomaticShifted");
        ELEMENT_ID_TO_NAME.put(ELEMENT_ALPHABET_SHIFT_LOCKED, "alphabetShiftLocked");
        ELEMENT_ID_TO_NAME.put(ELEMENT_SYMBOLS, "symbols");
        ELEMENT_ID_TO_NAME.put(ELEMENT_SYMBOLS_SHIFTED, "symbolsShifted");
        ELEMENT_ID_TO_NAME.put(ELEMENT_PHONE, "phone");
        ELEMENT_ID_TO_NAME.put(ELEMENT_PHONE_SYMBOLS, "phoneSymbols");
        ELEMENT_ID_TO_NAME.put(ELEMENT_NUMBER, "number");

        MODE_TO_NAME.put(MODE_TEXT, "text");
        MODE_TO_NAME.put(MODE_URL, "url");
        MODE_TO_NAME.put(MODE_EMAIL, "email");
        MODE_TO_NAME.put(MODE_IM, "im");
        MODE_TO_NAME.put(MODE_PHONE, "phone");
        MODE_TO_NAME.put(MODE_NUMBER, "number");
        MODE_TO_NAME.put(MODE_DATE, "date");
        MODE_TO_NAME.put(MODE_TIME, "time");
        MODE_TO_NAME.put(MODE_DATETIME, "datetime");
    }

    public final Subtype mSubtype;
    public final int mThemeId;
    public final int mWidth;
    public final int mHeight;
    public final int mBottomOffset;
    public final int mMode;
    public final int mElementId;
    public final EditorInfo mEditorInfo;
    public final boolean mClobberSettingsKey;
    public final boolean mLanguageSwitchKeyEnabled;
    public final String mCustomActionLabel;
    public final boolean mShowMoreKeys;
    public final boolean mShowNumberRow;
    public final boolean mShowTopBar;

    private final int mHashCode;

    public KeyboardId(final int elementId, final KeyboardLayoutSet.Params params) {
        mSubtype = params.mSubtype;
        mThemeId = params.mKeyboardThemeId;
        mWidth = params.mKeyboardWidth;
        mHeight = params.mKeyboardHeight;
        mBottomOffset = params.mKeyboardBottomOffset;
        mMode = params.mMode;
        mElementId = elementId;
        mEditorInfo = params.mEditorInfo;
        mClobberSettingsKey = params.mNoSettingsKey;
        mLanguageSwitchKeyEnabled = params.mLanguageSwitchKeyEnabled;
        mCustomActionLabel = (mEditorInfo.actionLabel != null)
                ? mEditorInfo.actionLabel.toString() : null;
        mShowMoreKeys = params.mShowMoreKeys;
        mShowNumberRow = params.mShowNumberRow;
        mShowTopBar = params.mShowTopBar;

        mHashCode = computeHashCode(this);
    }

    private static int computeHashCode(final KeyboardId id) {
        return Arrays.hashCode(new Object[] {
                id.mElementId,
                id.mMode,
                id.mWidth,
                id.mHeight,
                id.mBottomOffset,
                id.passwordInput(),
                id.mClobberSettingsKey,
                id.mLanguageSwitchKeyEnabled,
                id.isMultiLine(),
                id.imeAction(),
                id.mCustomActionLabel,
                id.navigateNext(),
                id.navigatePrevious(),
                id.mSubtype,
                id.mThemeId,
                id.mShowNumberRow,
                id.mShowMoreKeys,
                id.mShowTopBar
        });
    }

    private boolean equals(final KeyboardId other) {
        if (other == this)
            return true;
        if (other == null)
            return false;
        return equalsLayout(other) && equalsSettings(other);
    }

    private boolean equalsLayout(final KeyboardId other) {
        return equalsDimensions(other) && equalsModes(other);
    }

    private boolean equalsDimensions(final KeyboardId other) {
        return other.mWidth == mWidth
                && other.mHeight == mHeight
                && other.mBottomOffset == mBottomOffset;
    }

    private boolean equalsModes(final KeyboardId other) {
        return other.mElementId == mElementId
                && other.mMode == mMode
                && other.mThemeId == mThemeId
                && other.mSubtype.equals(mSubtype);
    }

    private boolean equalsSettings(final KeyboardId other) {
        return equalsEditorSettings(other) && equalsKeySettings(other);
    }

    private boolean equalsEditorSettings(final KeyboardId other) {
        return other.passwordInput() == passwordInput()
                && other.isMultiLine() == isMultiLine()
                && other.imeAction() == imeAction()
                && TextUtils.equals(other.mCustomActionLabel, mCustomActionLabel);
    }

    private boolean equalsKeySettings(final KeyboardId other) {
        return equalsNavigation(other) && equalsKeyFlags(other);
    }

    private boolean equalsNavigation(final KeyboardId other) {
        return other.navigateNext() == navigateNext()
                && other.navigatePrevious() == navigatePrevious();
    }

    private boolean equalsKeyFlags(final KeyboardId other) {
        return other.mClobberSettingsKey == mClobberSettingsKey
                && other.mLanguageSwitchKeyEnabled == mLanguageSwitchKeyEnabled
                && other.mShowNumberRow == mShowNumberRow
                && other.mShowMoreKeys == mShowMoreKeys
                && other.mShowTopBar == mShowTopBar;
    }

    private static boolean isAlphabetKeyboard(final int elementId) {
        return elementId < ELEMENT_SYMBOLS;
    }

    public boolean isAlphabetKeyboard() {
        return isAlphabetKeyboard(mElementId);
    }

    public boolean navigateNext() {
        return (mEditorInfo.imeOptions & EditorInfo.IME_FLAG_NAVIGATE_NEXT) != 0
                || imeAction() == EditorInfo.IME_ACTION_NEXT;
    }

    public boolean navigatePrevious() {
        return (mEditorInfo.imeOptions & EditorInfo.IME_FLAG_NAVIGATE_PREVIOUS) != 0
                || imeAction() == EditorInfo.IME_ACTION_PREVIOUS;
    }

    public boolean passwordInput() {
        final int inputType = mEditorInfo.inputType;
        return InputTypeUtils.isPasswordInputType(inputType)
                || InputTypeUtils.isVisiblePasswordInputType(inputType);
    }

    public boolean isMultiLine() {
        return (mEditorInfo.inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
    }

    public int imeAction() {
        return InputTypeUtils.getImeOptionsActionIdFromEditorInfo(mEditorInfo);
    }

    public Locale getLocale() {
        return mSubtype.getLocaleObject();
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof KeyboardId && equals((KeyboardId) other);
    }

    @Override
    public int hashCode() {
        return mHashCode;
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "[%s %s:%s %dx%d +%d %s %s%s %s]",
                elementIdToName(mElementId),
                mSubtype.getLocale(),
                mSubtype.getKeyboardLayoutSet(),
                mWidth, mHeight, mBottomOffset,
                modeName(mMode),
                actionName(imeAction()),
                getFlagsString(),
                KeyboardTheme.getKeyboardThemeName(mThemeId)
        );
    }

    private String getFlagsString() {
        final StringBuilder sb = new StringBuilder();
        appendFlag(sb, navigateNext(), " navigateNext");
        appendFlag(sb, navigatePrevious(), " navigatePrevious");
        appendFlag(sb, mClobberSettingsKey, " clobberSettingsKey");
        appendFlag(sb, passwordInput(), " passwordInput");
        appendFlag(sb, mLanguageSwitchKeyEnabled, " languageSwitchKeyEnabled");
        appendFlag(sb, isMultiLine(), " isMultiLine");
        return sb.toString();
    }

    private static void appendFlag(final StringBuilder sb, final boolean condition, final String flagName) {
        if (condition) {
            sb.append(flagName);
        }
    }

    public static boolean equivalentEditorInfoForKeyboard(final EditorInfo a, final EditorInfo b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return hasSameEditorOptions(a, b);
    }

    private static boolean hasSameEditorOptions(final EditorInfo a, final EditorInfo b) {
        return a.inputType == b.inputType
                && a.imeOptions == b.imeOptions
                && TextUtils.equals(a.privateImeOptions, b.privateImeOptions);
    }

    public static String elementIdToName(final int elementId) {
        return ELEMENT_ID_TO_NAME.get(elementId);
    }

    public static String modeName(final int mode) {
        return MODE_TO_NAME.get(mode);
    }

    public static String actionName(final int actionId) {
        return (actionId == InputTypeUtils.IME_ACTION_CUSTOM_LABEL) ? "actionCustomLabel"
                : EditorInfoCompatUtils.imeActionName(actionId);
    }
}
