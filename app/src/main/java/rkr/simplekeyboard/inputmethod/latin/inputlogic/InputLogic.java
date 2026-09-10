/*
 * Copyright (C) 2013 The Android Open Source Project
 * Copyright (C) 2025 Raimondas Rimkus
 * Copyright (C) 2025 Camille019
 * Copyright (C) 2023 Md. Rifat Hasan Jihan
 * Copyright (C) 2021 wittmane
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

package rkr.simplekeyboard.inputmethod.latin.inputlogic;

import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;

import rkr.simplekeyboard.inputmethod.event.Event;
import rkr.simplekeyboard.inputmethod.event.InputTransaction;
import rkr.simplekeyboard.inputmethod.latin.LatinIME;
import rkr.simplekeyboard.inputmethod.latin.RichInputConnection;
import rkr.simplekeyboard.inputmethod.latin.common.Constants;
import rkr.simplekeyboard.inputmethod.latin.common.StringUtils;
import rkr.simplekeyboard.inputmethod.latin.settings.SettingsValues;
import rkr.simplekeyboard.inputmethod.latin.utils.InputTypeUtils;
import rkr.simplekeyboard.inputmethod.latin.utils.RecapitalizeStatus;

/**
 * This class manages the input logic.
 */
public final class InputLogic {
    private static final String TAG = InputLogic.class.getSimpleName();

    // TODO : Remove this member when we can.
    final LatinIME mLatinIME;

    // This has package visibility so it can be accessed from InputLogicHandler.
    public final RichInputConnection mConnection;
    private final RecapitalizeStatus mRecapitalizeStatus = new RecapitalizeStatus();
    private static final long DOUBLE_SPACE_PERIOD_TIMEOUT = 800;
    private long mLastSpaceTimestamp = 0;

    /**
     * Create a new instance of the input logic.
     * @param latinIME the instance of the parent LatinIME. We should remove this when we can.
     * dictionary.
     */
    public InputLogic(final LatinIME latinIME) {
        mLatinIME = latinIME;
        mConnection = new RichInputConnection(latinIME);
    }

    /**
     * Initializes the input logic for input in an editor.
     *
     * Call this when input starts or restarts in some editor (typically, in onStartInputView).
     */
    public void startInput() {
        mLastSpaceTimestamp = 0;
        mRecapitalizeStatus.disable(); // Do not perform recapitalize until the cursor is moved once
    }

    public void clearCaches() {
        mLastSpaceTimestamp = 0;
        mConnection.clearCaches();
    }

    /**
     * Call this when the subtype changes.
     */
    public void onSubtypeChanged() {
        startInput();
    }

    /**
     * React to a string input.
     *
     * This is triggered by keys that input many characters at once, like the ".com" key or
     * some additional keys for example.
     *
     * @param settingsValues the current values of the settings.
     * @param event the input event containing the data.
     * @return the complete transaction object
     */
    public InputTransaction onTextInput(final SettingsValues settingsValues, final Event event) {
        mLastSpaceTimestamp = 0;
        final String text = event.getTextToCommit().toString();
        final InputTransaction inputTransaction = new InputTransaction(settingsValues);
        if (text.length() > 0) {
            stripPrecedingSpaceIfNeeded(settingsValues, text.codePointAt(0));
        }
        mConnection.commitText(text, 1);
        // Space state must be updated before calling updateShiftState
        inputTransaction.requireShiftUpdate(InputTransaction.SHIFT_UPDATE_NOW);
        return inputTransaction;
    }

    private void stripPrecedingSpaceIfNeeded(final SettingsValues settingsValues, final int codePoint) {
        if (!settingsValues.mAutoStripPunctuationSpace || !StringUtils.shouldStripPrecedingSpace(codePoint)) {
            return;
        }
        final String textBefore = mConnection.getTextBeforeCursor(2, 0);
        if (textBefore.length() >= 2
                && textBefore.charAt(textBefore.length() - 1) == ' '
                && !Character.isWhitespace(textBefore.charAt(textBefore.length() - 2))) {
            mConnection.deleteTextBeforeCursor(1);
        }
    }

    /**
     * Consider an update to the cursor position. Evaluate whether this update has happened as
     * part of normal typing or whether it was an explicit cursor move by the user. In any case,
     * do the necessary adjustments.
     * @param newSelStart new selection start
     * @param newSelEnd new selection end
     */
    public void onUpdateSelection(final int newSelStart, final int newSelEnd) {
        mConnection.updateSelection(newSelStart, newSelEnd);
        // Selection moved externally: any intra-event IPC snapshot is now at the wrong cursor.
        mConnection.invalidateReadSnapshots();
    }

    public void reloadTextCache() {
        mConnection.reloadTextCache();

        mRecapitalizeStatus.enable();
        mRecapitalizeStatus.stop();
    }

    /**
     * React to a code input. It may be a code point to insert, or a symbolic value that influences
     * the keyboard behavior.
     *
     * Typically, this is called whenever a key is pressed on the software keyboard. This is not
     * the entry point for gesture input; see the onBatchInput* family of functions for this.
     *
     * @param settingsValues the current settings values.
     * @param event the event to handle.
     * @return the complete transaction object
     */
    public InputTransaction onCodeInput(final SettingsValues settingsValues, final Event event) {
        final InputTransaction inputTransaction = new InputTransaction(settingsValues);

        if (event.isFunctionalKeyEvent()) {
            handleFunctionalEvent(event, inputTransaction);
        } else {
            handleNonFunctionalEvent(event, inputTransaction);
        }
        return inputTransaction;
    }

    @FunctionalInterface
    private interface FunctionalEventHandler {
        void handle(InputLogic logic, Event event, InputTransaction inputTransaction);
    }

    private static final SparseArray<FunctionalEventHandler> sFunctionalHandlers = new SparseArray<>();

    static {
        sFunctionalHandlers.put(Constants.CODE_DELETE, (logic, event, tx) -> logic.handleBackspaceEvent(event, tx));
        sFunctionalHandlers.put(Constants.CODE_SHIFT, (logic, event, tx) -> {
            logic.performRecapitalization();
            tx.requireShiftUpdate(InputTransaction.SHIFT_UPDATE_NOW);
        });
        sFunctionalHandlers.put(Constants.CODE_CAPSLOCK, (logic, event, tx) -> {});
        sFunctionalHandlers.put(Constants.CODE_SYMBOL_SHIFT, (logic, event, tx) -> {});
        sFunctionalHandlers.put(Constants.CODE_SWITCH_ALPHA_SYMBOL, (logic, event, tx) -> {});
        sFunctionalHandlers.put(Constants.CODE_SETTINGS, (logic, event, tx) -> logic.onSettingsKeyPressed());
        sFunctionalHandlers.put(Constants.CODE_PASTE, (logic, event, tx) -> {
            if (tx.mSettingsValues.mClipboardEnabled) {
                logic.mLatinIME.showClipboardHistory();
            } else {
                logic.mConnection.pasteClipboard();
            }
        });
        sFunctionalHandlers.put(Constants.CODE_EMOJI, (logic, event, tx) -> logic.mLatinIME.showEmojiView());
        sFunctionalHandlers.put(Constants.CODE_ACTION_NEXT, (logic, event, tx) -> logic.performEditorAction(EditorInfo.IME_ACTION_NEXT));
        sFunctionalHandlers.put(Constants.CODE_ACTION_PREVIOUS, (logic, event, tx) -> logic.performEditorAction(EditorInfo.IME_ACTION_PREVIOUS));
        sFunctionalHandlers.put(Constants.CODE_LANGUAGE_SWITCH, (logic, event, tx) -> logic.handleLanguageSwitchKey());
        sFunctionalHandlers.put(Constants.CODE_SHIFT_ENTER, (logic, event, tx) -> logic.sendDownUpKeyEventWithMeta(KeyEvent.KEYCODE_ENTER, KeyEvent.META_SHIFT_ON));
    }

    /**
     * Handle a functional key event.
     *
     * A functional event is a special key, like delete, shift, emoji, or the settings key.
     * Non-special keys are those that generate a single code point.
     * This includes all letters, digits, punctuation, separators, emoji. It excludes keys that
     * manage keyboard-related stuff like shift, language switch, settings, layout switch, or
     * any key that results in multiple code points like the ".com" key.
     *
     * @param event The event to handle.
     * @param inputTransaction The transaction in progress.
     */
    private void handleFunctionalEvent(final Event event, final InputTransaction inputTransaction) {
        final FunctionalEventHandler handler = sFunctionalHandlers.get(event.mKeyCode);
        if (handler != null) {
            handler.handle(this, event, inputTransaction);
            return;
        }
        throw new RuntimeException("Unknown key code : " + event.mKeyCode);
    }

    /**
     * Handle an event that is not a functional event.
     *
     * These events are generally events that cause input, but in some cases they may do other
     * things like trigger an editor action.
     *
     * @param event The event to handle.
     * @param inputTransaction The transaction in progress.
     */
    private void handleNonFunctionalEvent(final Event event,
            final InputTransaction inputTransaction) {
        switch (event.mCodePoint) {
            case Constants.CODE_SPACE:
                handleSpaceEvent(event, inputTransaction);
                break;
            case Constants.CODE_ENTER:
                mLastSpaceTimestamp = 0;
                final EditorInfo editorInfo = getCurrentInputEditorInfo();
                final int imeOptionsActionId =
                        InputTypeUtils.getImeOptionsActionIdFromEditorInfo(editorInfo);
                if (InputTypeUtils.IME_ACTION_CUSTOM_LABEL == imeOptionsActionId) {
                    // Either we have an actionLabel and we should performEditorAction with
                    // actionId regardless of its value.
                    performEditorAction(editorInfo.actionId);
                } else if (EditorInfo.IME_ACTION_NONE != imeOptionsActionId) {
                    // We didn't have an actionLabel, but we had another action to execute.
                    // EditorInfo.IME_ACTION_NONE explicitly means no action. In contrast,
                    // EditorInfo.IME_ACTION_UNSPECIFIED is the default value for an action, so it
                    // means there should be an action and the app didn't bother to set a specific
                    // code for it - presumably it only handles one. It does not have to be treated
                    // in any specific way: anything that is not IME_ACTION_NONE should be sent to
                    // performEditorAction.
                    performEditorAction(imeOptionsActionId);
                } else {
                    // No action label, and the action from imeOptions is NONE: this is a regular
                    // enter key that should input a carriage return.
                    handleNonSpecialCharacterEvent(event, inputTransaction);
                }
                break;
            default:
                mLastSpaceTimestamp = 0;
                handleNonSpecialCharacterEvent(event, inputTransaction);
                break;
        }
    }

    private void handleSpaceEvent(final Event event, final InputTransaction inputTransaction) {
        final long now = SystemClock.uptimeMillis();
        if (inputTransaction.mSettingsValues.mAutoPeriodEnabled) {
            if (now - mLastSpaceTimestamp < DOUBLE_SPACE_PERIOD_TIMEOUT) {
                final String textBefore = mConnection.getTextBeforeCursor(2, 0);
                if (textBefore.length() >= 2
                        && textBefore.charAt(textBefore.length() - 1) == ' '
                        && Character.isLetterOrDigit(textBefore.charAt(textBefore.length() - 2))) {
                    mConnection.deleteTextBeforeCursor(1);
                    mConnection.commitText(". ", 1);
                    inputTransaction.requireShiftUpdate(InputTransaction.SHIFT_UPDATE_NOW);
                    mLastSpaceTimestamp = 0;
                    return;
                }
            }
        }
        mLastSpaceTimestamp = now;
        handleSeparatorEvent(event, inputTransaction);
    }

    /**
     * Handle inputting a code point to the editor.
     *
     * Non-special keys are those that generate a single code point.
     * This includes all letters, digits, punctuation, separators, emoji. It excludes keys that
     * manage keyboard-related stuff like shift, language switch, settings, layout switch, or
     * any key that results in multiple code points like the ".com" key.
     *
     * @param event The event to handle.
     * @param inputTransaction The transaction in progress.
     */
    private void handleNonSpecialCharacterEvent(final Event event,
            final InputTransaction inputTransaction) {
        final int codePoint = event.mCodePoint;
        if (inputTransaction.mSettingsValues.isWordSeparator(codePoint)
                || Character.getType(codePoint) == Character.OTHER_SYMBOL) {
            handleSeparatorEvent(event, inputTransaction);
        } else {
            handleNonSeparatorEvent(event);
        }
    }

    /**
     * Handle a non-separator.
     * @param event The event to handle.
     */
    private void handleNonSeparatorEvent(final Event event) {
        sendKeyCodePoint(event.mCodePoint);
    }

    /**
     * Handle input of a separator code point.
     * @param event The event to handle.
     * @param inputTransaction The transaction in progress.
     */
    private void handleSeparatorEvent(final Event event, final InputTransaction inputTransaction) {
        final int codePoint = event.mCodePoint;
        stripPrecedingSpaceIfNeeded(inputTransaction.mSettingsValues, codePoint);
        sendKeyCodePoint(codePoint);

        inputTransaction.requireShiftUpdate(InputTransaction.SHIFT_UPDATE_NOW);
    }

    private int resolveBackspaceShiftUpdateKind(final Event event) {
        if (event.isKeyRepeat() && mConnection.getExpectedSelectionStart() > 0) {
            return InputTransaction.SHIFT_UPDATE_LATER;
        }
        return InputTransaction.SHIFT_UPDATE_NOW;
    }

    private void deleteCharacterBeforeCursor() {
        final int codePointBeforeCursor = mConnection.getCodePointBeforeCursor();
        if (codePointBeforeCursor == Constants.NOT_A_CODE) {
            sendDownUpKeyEvent(KeyEvent.KEYCODE_DEL);
            return;
        }
        final int numChars = Character.isSupplementaryCodePoint(codePointBeforeCursor) ? 2 : 1;
        mConnection.deleteTextBeforeCursor(numChars);
    }

    /**
     * Handle a press on the backspace key.
     * @param event The event to handle.
     * @param inputTransaction The transaction in progress.
     */
    private void handleBackspaceEvent(final Event event, final InputTransaction inputTransaction) {
        mLastSpaceTimestamp = 0;
        inputTransaction.requireShiftUpdate(resolveBackspaceShiftUpdateKind(event));

        if (mConnection.hasSelection()) {
            mConnection.deleteSelectedText();
        } else {
            deleteCharacterBeforeCursor();
        }
    }

    /**
     * Handle a press on the language switch key (the "globe key")
     */
    private void handleLanguageSwitchKey() {
        mLatinIME.switchToNextSubtype();
    }

    private boolean canRecapitalize(final int selectionStart, final int selectionEnd) {
        if (!mConnection.hasSelection() || !mRecapitalizeStatus.mIsEnabled()) {
            return false;
        }
        final int numCharsSelected = selectionEnd - selectionStart;
        return numCharsSelected <= Constants.MAX_CHARACTERS_FOR_RECAPITALIZATION;
    }

    private boolean ensureRecapitalizeStarted(final int selectionStart, final int selectionEnd) {
        if (mRecapitalizeStatus.isStarted() && mRecapitalizeStatus.isSetAt(selectionStart, selectionEnd)) {
            return true;
        }
        final CharSequence selectedText = mConnection.getSelectedText();
        if (TextUtils.isEmpty(selectedText)) {
            return false;
        }
        mRecapitalizeStatus.start(selectionStart, selectionEnd, selectedText.toString(), mLatinIME.getCurrentLayoutLocale());
        mRecapitalizeStatus.trim();
        return true;
    }

    private void applyRecapitalization(final int selectionStart, final int selectionEnd) {
        mConnection.beginBatchEdit();
        mConnection.setSelection(selectionStart, selectionStart);
        mRecapitalizeStatus.rotate();
        mConnection.replaceText(selectionStart, selectionEnd, mRecapitalizeStatus.getRecapitalizedString());
        mConnection.setSelection(mRecapitalizeStatus.getNewCursorStart(), mRecapitalizeStatus.getNewCursorEnd());
        mConnection.endBatchEdit();
    }

    /**
     * Performs a recapitalization event.
     */
    private void performRecapitalization() {
        final int selectionStart = mConnection.getExpectedSelectionStart();
        final int selectionEnd = mConnection.getExpectedSelectionEnd();
        if (!canRecapitalize(selectionStart, selectionEnd)) {
            return;
        }
        if (!ensureRecapitalizeStarted(selectionStart, selectionEnd)) {
            return;
        }
        applyRecapitalization(selectionStart, selectionEnd);
    }

    /**
     * Gets the current auto-caps state, factoring in the space state.
     *
     * This method tries its best to do this in the most efficient possible manner. It avoids
     * getting text from the editor if possible at all.
     * This is called from the KeyboardSwitcher (through a trampoline in LatinIME) because it
     * needs to know auto caps state to display the right layout.
     *
     * @param settingsValues the relevant settings values
     * @param layoutSetName the name of the current keyboard layout set
     * @return a caps mode from TextUtils.CAP_MODE_* or Constants.TextUtils.CAP_MODE_OFF.
     */
    public int getCurrentAutoCapsState(final SettingsValues settingsValues,
                                       final String layoutSetName) {
        if (!settingsValues.mAutoCap || !layoutUsesAutoCaps(layoutSetName)) {
            return Constants.TextUtils.CAP_MODE_OFF;
        }

        final EditorInfo ei = getCurrentInputEditorInfo();
        if (ei == null) {
            Log.w(TAG, "getCurrentAutoCapsState: EditorInfo is null");
            return Constants.TextUtils.CAP_MODE_OFF;
        }
        final int inputType = ei.inputType;
        // Warning: this depends on mSpaceState, which may not be the most current value. If
        // mSpaceState gets updated later, whoever called this may need to be told about it.
        return mConnection.getCursorCapsMode(inputType, settingsValues.mSpacingAndPunctuations);
    }

    private boolean layoutUsesAutoCaps(final String layoutSetName) {
        return true;
    }

    public int getCurrentRecapitalizeState() {
        if (!mRecapitalizeStatus.isStarted()
                || !mRecapitalizeStatus.isSetAt(mConnection.getExpectedSelectionStart(),
                        mConnection.getExpectedSelectionEnd())) {
            // Not recapitalizing at the moment
            return RecapitalizeStatus.NOT_A_RECAPITALIZE_MODE;
        }
        return mRecapitalizeStatus.getCurrentMode();
    }

    /**
     * @return the editor info for the current editor
     */
    private EditorInfo getCurrentInputEditorInfo() {
        return mLatinIME.getCurrentInputEditorInfo();
    }

    /**
     * @param actionId the action to perform
     */
    private void performEditorAction(final int actionId) {
        mConnection.performEditorAction(actionId);
    }

    /**
     * Perform the processing specific to inputting TLDs.
     *
     * Some keys input a TLD (specifically, the ".com" key) and this warrants some specific
     * Handle a press on the settings key.
     */
    private void onSettingsKeyPressed() {
        mLatinIME.launchSettings();
    }

    /**
     * Sends a DOWN key event followed by an UP key event to the editor.
     *
     * If possible at all, avoid using this method. It causes all sorts of race conditions with
     * the text view because it goes through a different, asynchronous binder. Also, batch edits
     * are ignored for key events. Use the normal software input methods instead.
     *
     * @param keyCode the key code to send inside the key event.
     */
    public void sendDownUpKeyEvent(final int keyCode) {
        sendDownUpKeyEvent(keyCode, 1);
    }

    public void sendDownUpKeyEvent(final int keyCode, final int repeatCount) {
        for (int i = 0; i < repeatCount; i++) {
            sendDownUpKeyEventWithMeta(keyCode, 0);
        }
    }

    public void sendDownUpKeyEventWithMeta(final int keyCode, final int metaState) {
        final long eventTime = SystemClock.uptimeMillis();
        mConnection.sendKeyEvent(new KeyEvent(eventTime, eventTime,
                KeyEvent.ACTION_DOWN, keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE));
        mConnection.sendKeyEvent(new KeyEvent(SystemClock.uptimeMillis(), eventTime,
                KeyEvent.ACTION_UP, keyCode, 0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE));
    }

    /**
     * Sends a code point to the editor, using the most appropriate method.
     *
     * Normally we send code points with commitText, but there are some cases (where backward
     * compatibility is a concern for example) where we want to use deprecated methods.
     *
     * @param codePoint the code point to send.
     */
    // TODO: replace these two parameters with an InputTransaction
    private void sendKeyCodePoint(final int codePoint) {
        if (codePoint >= '0' && codePoint <= '9') {
            sendDownUpKeyEvent(codePoint - '0' + KeyEvent.KEYCODE_0);
            return;
        }

        final EditorInfo editorInfo = getCurrentInputEditorInfo();
        if (editorInfo == null || editorInfo.inputType == android.text.InputType.TYPE_NULL) {
            mLatinIME.sendKeyChar((char) codePoint);
            return;
        }

        mConnection.commitText(StringUtils.newSingleCodePointString(codePoint), 1);
    }
}
