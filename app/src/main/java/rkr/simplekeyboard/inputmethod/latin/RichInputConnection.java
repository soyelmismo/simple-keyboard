/*
 * Copyright (C) 2012 The Android Open Source Project
 * Copyright (C) 2025 Raimondas Rimkus
 * Copyright (C) 2024 wittmane
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

import static android.content.ClipDescription.MIMETYPE_TEXT_HTML;
import static android.content.ClipDescription.MIMETYPE_TEXT_PLAIN;

import android.annotation.TargetApi;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.SurroundingText;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rkr.simplekeyboard.inputmethod.compat.BuildCompatUtils;
import rkr.simplekeyboard.inputmethod.latin.common.Constants;
import rkr.simplekeyboard.inputmethod.latin.common.StringUtils;
import rkr.simplekeyboard.inputmethod.latin.settings.SpacingAndPunctuations;
import rkr.simplekeyboard.inputmethod.latin.utils.CapsModeUtils;

/**
 * Enrichment class for InputConnection to simplify interaction and add functionality.
 *
 * This class serves as a wrapper to be able to simply add hooks to any calls to the underlying
 * InputConnection. It also keeps track of a number of things to avoid having to call upon IPC
 * all the time to find out what text is in the buffer, when we need it to determine caps mode
 * for example.
 */
public final class RichInputConnection {
    private static final String TAG = "RichInputConnection";
    private static final int INVALID_CURSOR_POSITION = -1;

    /**
     * This variable contains an expected value for the selection start position. This is where the
     * cursor or selection start may end up after all the keyboard-triggered updates have passed. We
     * keep this to compare it to the actual selection start to guess whether the move was caused by
     * a keyboard command or not.
     * It's not really the selection start position: the selection start may not be there yet, and
     * in some cases, it may never arrive there.
     */
    private int mExpectedSelStart = INVALID_CURSOR_POSITION; // in chars, not code points
    /**
     * The expected selection end.  Only differs from mExpectedSelStart if a non-empty selection is
     * expected.  The same caveats as mExpectedSelStart apply.
     */
    private int mExpectedSelEnd = INVALID_CURSOR_POSITION; // in chars, not code points
    /**
     * Long-lived cache of the text immediately before the cursor. It is updated synchronously by
     * {@link #commitText} and the optimistic key handlers, and asynchronously by
     * {@link #reloadTextCache} on the background thread. Declared volatile so the background
     * writes are visible to UI-thread readers without locking. String is immutable, and reference
     * assignment is atomic per JLS §17.7, so volatile is sufficient (no torn reads).
     */
    private volatile String mTextBeforeCursor = "";
    /**
     * Long-lived cache of the text after the cursor. Refreshed by the background thread only
     * (no synchronous update on commit). Declared volatile for the same reasons as
     * {@link #mTextBeforeCursor}.
     */
    private volatile String mTextAfterCursor = "";
    private volatile String mTextSelection = "";

    /**
     * Short-lived intra-event read snapshots. They hold the most recent IPC result within the
     * current event so repeated {@code getTextBeforeCursor}/{@code getTextAfterCursor} requests
     * do not re-issue Binder transactions. The snapshots are invalidated explicitly by
     * {@link #invalidateReadSnapshots()} on every write path
     * (commitText, deleteTextBeforeCursor, setSelection, sendKeyEvent that mutates, etc.) so we
     * never serve stale data after a write. Stored in single-element arrays to avoid extra
     * allocations on hot paths.
     */
    private final String[] mBeforeReadSnapshot = new String[1];
    private final int[] mBeforeReadSnapshotLen = new int[1];
    private final boolean[] mBeforeReadSnapshotComplete = new boolean[1];
    private final String[] mAfterReadSnapshot = new String[1];
    private final int[] mAfterReadSnapshotLen = new int[1];
    private final boolean[] mAfterReadSnapshotComplete = new boolean[1];

    private final LatinIME mLatinIME;
    private volatile InputConnection mIC;
    private int mNestLevel;
    private final ExecutorService mBackgroundThread;

    public RichInputConnection(final LatinIME latinIME) {
        mLatinIME = latinIME;
        mIC = null;
        mNestLevel = 0;
        mBackgroundThread = Executors.newSingleThreadExecutor();
    }

    public boolean isConnected() {
        return mIC != null;
    }

    public void beginBatchEdit() {
        if (++mNestLevel == 1) {
            mIC = mLatinIME.getCurrentInputConnection();
            if (isConnected()) {
                try {
                    mIC.beginBatchEdit();
                } catch (Exception ignored) {
                    Log.w(TAG, "InputConnection failed", ignored);
                }
            }
        } else {
            Log.e(TAG, "Nest level too deep : " + mNestLevel);
        }
    }

    public void endBatchEdit() {
        if (mNestLevel <= 0) Log.e(TAG, "Batch edit not in progress!"); // TODO: exception instead
        if (--mNestLevel == 0 && isConnected()) {
            try {
                mIC.endBatchEdit();
            } catch (Exception ignored) {
                Log.w(TAG, "InputConnection failed", ignored);
            }
        }
    }

    public void updateSelection(final int newSelStart, final int newSelEnd) {
        mExpectedSelStart = newSelStart;
        mExpectedSelEnd = newSelEnd;
    }

    @TargetApi(Build.VERSION_CODES.S)
    private void setTextAroundCursor(final SurroundingText textAroundCursor) {
        if (null == textAroundCursor) {
            Log.e(TAG, "Unable get text around cursor.");
            mTextBeforeCursor = "";
            mTextAfterCursor = "";
            mTextSelection = "";
            invalidateReadSnapshots();
            return;
        }
        final CharSequence text = textAroundCursor.getText();
        mTextBeforeCursor = text.subSequence(0, textAroundCursor.getSelectionStart()).toString();
        mTextSelection = text.subSequence(textAroundCursor.getSelectionStart(), textAroundCursor.getSelectionEnd()).toString();
        mTextAfterCursor = text.subSequence(textAroundCursor.getSelectionEnd(), text.length()).toString();
        // Background reload refreshed the long-lived cache; the snapshot, if any, was taken at
        // the prior cursor state and must be discarded.
        invalidateReadSnapshots();
    }

    /**
     * Reload the cached text from the EditorInfo.
     */
    public void reloadTextCache(final EditorInfo editorInfo, final boolean restarting) {
        mIC = mLatinIME.getCurrentInputConnection();
        if (shouldSkipReloadFromEditorInfo(restarting)) {
            return;
        }
        updateSelection(editorInfo.initialSelStart, editorInfo.initialSelEnd);

        if (BuildCompatUtils.isAtLeastS()) {
            final SurroundingText textAroundCursor = editorInfo
                    .getInitialSurroundingText(Constants.EDITOR_CONTENTS_CACHE_SIZE, Constants.EDITOR_CONTENTS_CACHE_SIZE, 0);
            setTextAroundCursor(textAroundCursor);
            mLatinIME.mHandler.postUpdateShiftState();
        } else {
            reloadTextCache();
        }
    }

    private boolean shouldSkipReloadFromEditorInfo(final boolean restarting) {
        // Updated by onUpdateSelection, don't override as editorInfo might be invalid
        // If restarting, onStartInputView was called instead of onUpdateSelection
        return hasCursorPosition() && !restarting;
    }

    /**
     * Reload the cached text from the InputConnection.
     */
    public void reloadTextCache() {
        mIC = mLatinIME.getCurrentInputConnection();
        if (!isConnected()) {
            return;
        }
        // To check if selection changed before text was retrieved
        final int expectedSelStart = mExpectedSelStart;
        final int expectedSelEnd = mExpectedSelEnd;

        mBackgroundThread.execute(() -> {
            if (!isConnected()) {
                return;
            }
            if (BuildCompatUtils.isAtLeastS()) {
                reloadTextCacheForSAndAbove(expectedSelStart, expectedSelEnd);
            } else {
                reloadTextCacheLegacy(expectedSelStart, expectedSelEnd);
            }
        });
    }

    private boolean isSelectionRangeModified(final int expectedSelStart, final int expectedSelEnd) {
        return expectedSelStart != mExpectedSelStart || expectedSelEnd != mExpectedSelEnd;
    }

    @TargetApi(Build.VERSION_CODES.S)
    private void reloadTextCacheForSAndAbove(final int expectedSelStart, final int expectedSelEnd) {
        final SurroundingText textAroundCursor =
                mIC.getSurroundingText(Constants.EDITOR_CONTENTS_CACHE_SIZE, Constants.EDITOR_CONTENTS_CACHE_SIZE, 0);
        if (isSelectionRangeModified(expectedSelStart, expectedSelEnd)) {
            Log.w(TAG, "Selection range modified before thread completion.");
            return;
        }
        setTextAroundCursor(textAroundCursor);
        mLatinIME.mHandler.postUpdateShiftState();
    }

    private void reloadTextCacheLegacy(final int expectedSelStart, final int expectedSelEnd) {
        if (!fetchTextBeforeCursorLegacy(expectedSelStart) || !fetchTextAfterCursorLegacy(expectedSelEnd)) {
            return;
        }
        mLatinIME.mHandler.postUpdateShiftState();
        fetchSelectedTextLegacy(expectedSelStart, expectedSelEnd);
    }

    private boolean fetchTextBeforeCursorLegacy(final int expectedSelStart) {
        final CharSequence textBeforeCursor = mIC.getTextBeforeCursor(Constants.EDITOR_CONTENTS_CACHE_SIZE, 0);
        if (expectedSelStart != mExpectedSelStart) {
            Log.w(TAG, "Selection start modified before thread completion.");
            return false;
        }
        if (null == textBeforeCursor) {
            Log.e(TAG, "Unable get text before cursor.");
            mTextBeforeCursor = "";
            invalidateReadSnapshots();
            return false;
        }
        mTextBeforeCursor = textBeforeCursor.toString();
        invalidateReadSnapshots();
        return true;
    }

    private boolean fetchTextAfterCursorLegacy(final int expectedSelEnd) {
        final CharSequence textAfterCursor = mIC.getTextAfterCursor(Constants.EDITOR_CONTENTS_CACHE_SIZE, 0);
        if (expectedSelEnd != mExpectedSelEnd) {
            Log.w(TAG, "Selection end modified before thread completion.");
            return false;
        }
        if (null == textAfterCursor) {
            Log.e(TAG, "Unable get text after cursor.");
            mTextAfterCursor = "";
        } else {
            mTextAfterCursor = textAfterCursor.toString();
        }
        invalidateReadSnapshots();
        return true;
    }

    private void fetchSelectedTextLegacy(final int expectedSelStart, final int expectedSelEnd) {
        if (!hasSelection()) {
            mTextSelection = "";
            return;
        }
        final CharSequence textSelection = mIC.getSelectedText(0);
        if (isSelectionRangeModified(expectedSelStart, expectedSelEnd)) {
            Log.w(TAG, "Selection range modified before thread completion.");
            return;
        }
        if (null == textSelection) {
            Log.e(TAG, "Unable get text selection.");
            mTextSelection = "";
        } else {
            mTextSelection = textSelection.toString();
        }
    }

    public void clearCaches() {
        Log.i(TAG, "Clearing text caches.");
        mExpectedSelStart = INVALID_CURSOR_POSITION;
        mExpectedSelEnd = INVALID_CURSOR_POSITION;
        mTextBeforeCursor = "";
        mTextSelection = "";
        mTextAfterCursor = "";
        invalidateReadSnapshots();
    }

    private void advanceExpectedSelection(final int delta) {
        if (hasCursorPosition()) {
            mExpectedSelStart += delta;
            mExpectedSelEnd = mExpectedSelStart;
        }
    }

    private static int skipWordCharactersBackwards(final CharSequence text, final int startIndex) {
        int i = startIndex;
        while (i >= 0 && StringUtils.isWordCharacter(text.charAt(i))) {
            i--;
        }
        return i;
    }

    private static int skipSeparatorsBackwards(final CharSequence text, final int startIndex) {
        int i = startIndex;
        while (i >= 0 && !StringUtils.isWordCharacter(text.charAt(i))) {
            i--;
        }
        return i;
    }

    /**
     * Calls {@link InputConnection#commitText(CharSequence, int)}.
     *
     * @param text The text to commit. This may include styles.
     * @param newCursorPosition The new cursor position around the text.
     */
    public void commitText(final CharSequence text, final int newCursorPosition) {
        if (mLatinIME.mRichImm != null) {
            mLatinIME.mRichImm.resetSubtypeCycleOrder();
        }
        mTextBeforeCursor += text;
        // TODO: the following is exceedingly error-prone. Right now when the cursor is in the
        // middle of the composing word mComposingText only holds the part of the composing text
        // that is before the cursor, so this actually works, but it's terribly confusing. Fix this.
        advanceExpectedSelection(text.length());
        // Invalidate any pre-write IPC snapshot; the cache was extended optimistically above so
        // subsequent reads must be derived from mTextBeforeCursor, not from the stale snapshot.
        invalidateReadSnapshots();
        if (isConnected()) {
            final EditorInfo editorInfo = mLatinIME.getCurrentInputEditorInfo();
            if (editorInfo != null && editorInfo.inputType == android.text.InputType.TYPE_NULL) {
                for (int i = 0; i < text.length(); i++) {
                    mLatinIME.sendKeyChar(text.charAt(i));
                }
            } else {
                try {
                    mIC.commitText(text, newCursorPosition);
                } catch (Exception ignored) {
                    Log.w(TAG, "InputConnection failed", ignored);
                }
            }
        }
    }

    public CharSequence getSelectedText() {
        return mTextSelection;
    }

    /**
     * Invalidate the intra-event read snapshots. Called on every write path so subsequent reads
     * in the same event do not reuse pre-write data. Also called from lifecycle hooks
     * (onUpdateSelection, clearCaches, reloadTextCache) so external cursor moves invalidate us.
     */
    public void invalidateReadSnapshots() {
        mBeforeReadSnapshot[0] = null;
        mBeforeReadSnapshotLen[0] = 0;
        mBeforeReadSnapshotComplete[0] = false;
        mAfterReadSnapshot[0] = null;
        mAfterReadSnapshotLen[0] = 0;
        mAfterReadSnapshotComplete[0] = false;
    }

    /**
     * Read up to {@code maxChars} chars before the cursor. Prefers the in-event snapshot, then
     * the long-lived cache (kept fresh by {@link #commitText} and the background reload),
     * falling back to a single Binder IPC when both are too short. Same-length repeated reads
     * within one event are served from the snapshot without IPC.
     */
    private String getCachedOrFetchTextBefore(final int maxChars) {
        final String snap = mBeforeReadSnapshot[0];
        if (snap != null) {
            if (mBeforeReadSnapshotComplete[0] || mBeforeReadSnapshotLen[0] >= maxChars) {
                // IPC returned the tail of pre-cursor text; substring the last maxChars.
                final int take = Math.min(maxChars, snap.length());
                return snap.substring(snap.length() - take);
            }
        }
        final String cached = mTextBeforeCursor;
        if (cached != null && cached.length() >= maxChars) {
            return cached.substring(cached.length() - maxChars);
        }
        if (isConnected()) {
            try {
                final CharSequence cs = mIC.getTextBeforeCursor(maxChars, 0);
                if (cs != null) {
                    final String str = cs.toString();
                    mBeforeReadSnapshot[0] = str;
                    mBeforeReadSnapshotLen[0] = str.length();
                    // If the IPC returned less than requested we know we captured the full
                    // pre-cursor content, so subsequent same-event reads need no further IPC.
                    mBeforeReadSnapshotComplete[0] = str.length() < maxChars;
                    mTextBeforeCursor = str;
                    return str;
                }
            } catch (Exception ignored) {
                Log.w(TAG, "InputConnection failed", ignored);
            }
        }
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        return "";
    }

    /**
     * Read up to {@code maxChars} chars after the cursor. Same dedup strategy as
     * {@link #getCachedOrFetchTextBefore(int)}.
     */
    private String getCachedOrFetchTextAfter(final int maxChars) {
        final String snap = mAfterReadSnapshot[0];
        if (snap != null) {
            if (mAfterReadSnapshotComplete[0] || mAfterReadSnapshotLen[0] >= maxChars) {
                // IPC returned the head of post-cursor text; substring the first maxChars.
                final int take = Math.min(maxChars, snap.length());
                return snap.substring(0, take);
            }
        }
        final String cached = mTextAfterCursor;
        if (cached != null && cached.length() >= maxChars) {
            return cached.substring(0, maxChars);
        }
        if (isConnected()) {
            try {
                final CharSequence cs = mIC.getTextAfterCursor(maxChars, 0);
                if (cs != null) {
                    final String str = cs.toString();
                    mAfterReadSnapshot[0] = str;
                    mAfterReadSnapshotLen[0] = str.length();
                    mAfterReadSnapshotComplete[0] = str.length() < maxChars;
                    mTextAfterCursor = str;
                    return str;
                }
            } catch (Exception ignored) {
                Log.w(TAG, "InputConnection failed", ignored);
            }
        }
        if (cached != null && !cached.isEmpty()) {
            final int len = Math.min(maxChars, cached.length());
            return cached.substring(0, len);
        }
        return "";
    }

    /**
     * Bypasses the cache and always issues a fresh IPC for {@code maxChars} chars after the
     * cursor. Reserved for callers that drive deletion (e.g. {@link #commitSuggestion}) where
     * a stale read would corrupt the editor.
     */
    private String fetchFreshTextAfter(final int maxChars) {
        if (!isConnected()) {
            return "";
        }
        try {
            final CharSequence cs = mIC.getTextAfterCursor(maxChars, 0);
            if (cs != null) {
                final String str = cs.toString();
                mAfterReadSnapshot[0] = str;
                mAfterReadSnapshotLen[0] = str.length();
                mAfterReadSnapshotComplete[0] = str.length() < maxChars;
                mTextAfterCursor = str;
                return str;
            }
        } catch (Exception ignored) {
            Log.w(TAG, "InputConnection failed", ignored);
        }
        return "";
    }

    public String getWordBeforeCursor() {
        final String text = getCachedOrFetchTextBefore(40);
        if (text.isEmpty()) {
            return "";
        }
        final int i = skipWordCharactersBackwards(text, text.length() - 1);
        return text.substring(i + 1);
    }

    public String[] getTwoPreviousWordsBeforeCursor() {
        final String text = getCachedOrFetchTextBefore(100);
        if (text.isEmpty()) {
            return new String[]{"", ""};
        }
        int i = text.length() - 1;
        // Skip current word (if any)
        i = skipWordCharactersBackwards(text, i);
        // Skip whitespace/separators before w2
        i = skipSeparatorsBackwards(text, i);
        if (i < 0) {
            return new String[]{"", ""};
        }
        final int end2 = i + 1;
        i = skipWordCharactersBackwards(text, i);
        final String w2 = text.substring(i + 1, end2);

        // Skip whitespace/separators before w1
        i = skipSeparatorsBackwards(text, i);
        if (i < 0) {
            return new String[]{"", w2};
        }
        final int end1 = i + 1;
        i = skipWordCharactersBackwards(text, i);
        final String w1 = text.substring(i + 1, end1);
        return new String[]{w1, w2};
    }

    public String getTextBeforeCursor(final int n, final int flags) {
        return getCachedOrFetchTextBefore(n);
    }

    private static boolean isWordAfterCursorBoundaryChar(final char c) {
        return Character.isWhitespace(c) || (!Character.isLetter(c) && c != '\'' && c != '-');
    }

    public String getWordAfterCursor() {
        final String text = getCachedOrFetchTextAfter(40);
        if (text.isEmpty()) {
            return "";
        }
        int i = 0;
        while (i < text.length()) {
            if (isWordAfterCursorBoundaryChar(text.charAt(i))) {
                break;
            }
            i++;
        }
        return text.substring(0, i);
    }

    public String getWordAtCursor() {
        final String before = getWordBeforeCursor();
        final String after = getWordAfterCursor();
        if (before.isEmpty() && after.isEmpty()) {
            return "";
        }
        if (before.isEmpty()) {
            return after;
        }
        return before + after;
    }

    public void commitSuggestion(final CharSequence suggestion) {
        final String before = getWordBeforeCursor();
        // For the post-cursor word we bypass the dedup cache: this value drives
        // mIC.deleteSurroundingText(0, after.length()) and a stale read would corrupt the editor.
        String after = "";
        final String freshAfter = fetchFreshTextAfter(40);
        if (!freshAfter.isEmpty()) {
            int i = 0;
            while (i < freshAfter.length()) {
                if (isWordAfterCursorBoundaryChar(freshAfter.charAt(i))) {
                    break;
                }
                i++;
            }
            after = freshAfter.substring(0, i);
        }
        if (!before.isEmpty()) {
            deleteTextBeforeCursor(before.length());
        }
        if (!after.isEmpty() && isConnected()) {
            try {
                mIC.deleteSurroundingText(0, after.length());
            } catch (Exception ignored) {
                Log.w(TAG, "InputConnection failed", ignored);
            }
        }
        commitText(suggestion.toString() + " ", 1);
    }

    public boolean canDeleteCharacters() {
        return mExpectedSelStart > 0;
    }

    /**
     * Gets the caps modes we should be in after this specific string.
     *
     * This returns a bit set of TextUtils#CAP_MODE_*, masked by the inputType argument.
     * This method also supports faking an additional space after the string passed in argument,
     * to support cases where a space will be added automatically, like in phantom space
     * state for example.
     * Note that for English, we are using American typography rules (which are not specific to
     * American English, it's just the most common set of rules for English).
     *
     * @param inputType a mask of the caps modes to test for.
     * @param spacingAndPunctuations the values of the settings to use for locale and separators.
     * @return the caps modes that should be on as a set of bits
     */
    public int getCursorCapsMode(final int inputType, final SpacingAndPunctuations spacingAndPunctuations) {
        mIC = mLatinIME.getCurrentInputConnection();
        if (!isConnected()) {
            return Constants.TextUtils.CAP_MODE_OFF;
        }
        // This never calls InputConnection#getCapsMode - in fact, it's a static method that
        // never blocks or initiates IPC.
        // TODO: don't call #toString() here. Instead, all accesses to
        // mCommittedTextBeforeComposingText should be done on the main thread.
        return CapsModeUtils.getCapsMode(mTextBeforeCursor, inputType,
                spacingAndPunctuations);
    }

    public int getCodePointBeforeCursor() {
        final int length = mTextBeforeCursor.length();
        if (length < 1) return Constants.NOT_A_CODE;
        return Character.codePointBefore(mTextBeforeCursor, length);
    }

    public void replaceText(final int startPosition, final int endPosition, CharSequence text) {
        if (mExpectedSelStart != mExpectedSelEnd) {
            Log.e(TAG, "replaceText called with text range selected");
            invalidateReadSnapshots();
            return;
        }
        if (mExpectedSelStart != startPosition) {
            Log.e(TAG, "replaceText called with range not starting with current cursor position");
            invalidateReadSnapshots();
            return;
        }

        final int numCharsSelected = endPosition - startPosition;
        final String textAfterCursor = mTextAfterCursor;
        if (textAfterCursor.length() < numCharsSelected) {
            Log.e(TAG, "replaceText called with range longer than current text");
            invalidateReadSnapshots();
            return;
        }
        mTextAfterCursor = text + textAfterCursor.substring(numCharsSelected);

        if (mLatinIME.mRichImm != null) {
            mLatinIME.mRichImm.resetSubtypeCycleOrder();
        }

        if (!isConnected()) {
            Log.w(TAG, "replaceText: InputConnection not connected");
            invalidateReadSnapshots();
            return;
        }
        try {
            if (BuildCompatUtils.isAtLeastUpsideDownCake()) {
                mIC.replaceText(startPosition, endPosition, text, 0, null);
            } else {
                mIC.deleteSurroundingText(0, numCharsSelected);
                mIC.commitText(text, 0);
            }
        } catch (Exception ignored) {
            Log.w(TAG, "InputConnection failed", ignored);
        }
        invalidateReadSnapshots();
    }

    public void deleteTextBeforeCursor(final int numChars) {
        String textBeforeCursor = mTextBeforeCursor;
        if (!textBeforeCursor.isEmpty() && textBeforeCursor.length() >= numChars) {
            mTextBeforeCursor = textBeforeCursor.substring(0, textBeforeCursor.length() - numChars);
        }
        if (mExpectedSelStart >= numChars) {
            advanceExpectedSelection(-numChars);
        }
        if (!isConnected()) {
            Log.w(TAG, "deleteTextBeforeCursor: InputConnection not connected");
            invalidateReadSnapshots();
            return;
        }
        try {
            mIC.deleteSurroundingText(numChars, 0);
        } catch (Exception ignored) {
            Log.w(TAG, "InputConnection failed", ignored);
        }
        invalidateReadSnapshots();
    }

    public void deleteSelectedText() {
        if (mExpectedSelStart == mExpectedSelEnd) {
            Log.e(TAG, "deleteSelectedText called with text range not selected");
            return;
        }

        beginBatchEdit();
        final int selectionLength = mExpectedSelEnd - mExpectedSelStart;
        mTextSelection = "";
        setSelection(mExpectedSelStart, mExpectedSelStart);
        if (!isConnected()) {
            Log.w(TAG, "deleteSelectedText: InputConnection not connected");
            endBatchEdit();
            invalidateReadSnapshots();
            return;
        }
        try {
            mIC.deleteSurroundingText(0, selectionLength);
        } catch (Exception ignored) {
            Log.w(TAG, "InputConnection failed", ignored);
        }
        endBatchEdit();
        invalidateReadSnapshots();
    }

    public void performEditorAction(final int actionId) {
        mIC = mLatinIME.getCurrentInputConnection();
        if (isConnected()) {
            try {
                mIC.performEditorAction(actionId);
            } catch (Exception ignored) {
                Log.w(TAG, "InputConnection failed", ignored);
            }
        }
    }

    private static boolean isTextMimeType(final String mimeType) {
        return MIMETYPE_TEXT_PLAIN.equals(mimeType) || MIMETYPE_TEXT_HTML.equals(mimeType);
    }

    private CharSequence extractPrimaryClipText(final ClipboardManager clipboard) {
        if (clipboard == null || !clipboard.hasPrimaryClip()) {
            return null;
        }
        final ClipData clipData = clipboard.getPrimaryClip();
        if (clipData == null || clipData.getItemCount() != 1) {
            return null;
        }
        if (!isTextMimeType(clipData.getDescription().getMimeType(0))) {
            return null;
        }
        return clipData.getItemAt(0).getText();
    }

    public void pasteClipboard() {
        final ClipboardManager clipboard = (ClipboardManager) mLatinIME.getSystemService(Context.CLIPBOARD_SERVICE);
        final CharSequence pasteData = extractPrimaryClipText(clipboard);
        if (pasteData != null && pasteData.length() > 0) {
            mLatinIME.onTextInput(pasteData.toString());
            return;
        }

        if (!isConnected()) {
            Log.w(TAG, "pasteClipboard: InputConnection not connected");
            return;
        }
        try {
            mIC.performContextMenuAction(android.R.id.paste);
        } catch (Exception ignored) {
            Log.w(TAG, "InputConnection failed", ignored);
        }
    }

    private void handleEnterKeyDown() {
        mTextBeforeCursor += "\n";
        advanceExpectedSelection(1);
        invalidateReadSnapshots();
    }

    private void handleUnknownKeyDown(final String characters) {
        if (characters != null) {
            mTextBeforeCursor += characters;
            advanceExpectedSelection(characters.length());
            invalidateReadSnapshots();
        }
    }

    private void handleDeleteKeyDown() {
        if (mTextBeforeCursor != null && !mTextBeforeCursor.isEmpty()) {
            mTextBeforeCursor = mTextBeforeCursor.substring(0, mTextBeforeCursor.length() - 1);
            advanceExpectedSelection(-1);
            invalidateReadSnapshots();
        }
    }

    private void handleDefaultKeyDown(final int unicodeChar) {
        final String text = StringUtils.newSingleCodePointString(unicodeChar);
        mTextBeforeCursor += text;
        advanceExpectedSelection(text.length());
        invalidateReadSnapshots();
    }

    private void handleKeyDownEvent(final KeyEvent keyEvent) {
        switch (keyEvent.getKeyCode()) {
            case KeyEvent.KEYCODE_ENTER:
                handleEnterKeyDown();
                break;
            case KeyEvent.KEYCODE_UNKNOWN:
                handleUnknownKeyDown(keyEvent.getCharacters());
                break;
            case KeyEvent.KEYCODE_DEL:
                handleDeleteKeyDown();
                break;
            default:
                handleDefaultKeyDown(keyEvent.getUnicodeChar());
                break;
        }
    }

    public void sendKeyEvent(final KeyEvent keyEvent) {
        if (mLatinIME.mRichImm != null) {
            mLatinIME.mRichImm.resetSubtypeCycleOrder();
        }
        if (keyEvent.getAction() == KeyEvent.ACTION_DOWN) {
            // This method is only called for enter or backspace when speaking to old applications
            // (target SDK <= 15 (Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1)), or for digits.
            // When talking to new applications we never use this method because it's inherently
            // racy and has unpredictable results, but for backward compatibility we continue
            // sending the key events for only Enter and Backspace because some applications
            // mistakenly catch them to do some stuff.
            handleKeyDownEvent(keyEvent);
        }
        if (isConnected()) {
            try {
                mIC.sendKeyEvent(keyEvent);
            } catch (Exception ignored) {
                Log.w(TAG, "InputConnection failed", ignored);
            }
        }
        invalidateReadSnapshots();
    }

    private static boolean isInvalidSelectionBounds(final int start, final int end) {
        return start < 0 || end < 0 || start > end;
    }

    private static boolean canUpdateTextCachesForSelection(final String textRange, final int textStart,
            final int start, final int end) {
        return textStart >= 0 && start >= textStart && textRange.length() >= end - textStart;
    }

    private void updateCachedTextForSelection(final int start, final int end) {
        final int textStart = mExpectedSelStart - mTextBeforeCursor.length();
        final String textRange = mTextBeforeCursor + mTextSelection + mTextAfterCursor;
        if (canUpdateTextCachesForSelection(textRange, textStart, start, end)) {
            // Parameters might be partially updated by background thread, skip in such case
            mTextBeforeCursor = textRange.substring(0, start - textStart);
            mTextSelection = textRange.substring(start - textStart, end - textStart);
            mTextAfterCursor = textRange.substring(end - textStart);
        }
    }

    /**
     * Set the selection of the text editor.
     *
     * Calls through to {@link InputConnection#setSelection(int, int)}.
     *
     * @param start the character index where the selection should start.
     * @param end the character index where the selection should end.
     * valid when setting the selection or when retrieving the text cache at that point, or
     * invalid arguments were passed.
     */
    public void setSelection(int start, int end) {
        if (isInvalidSelectionBounds(start, end)) {
            return;
        }
        if (mExpectedSelStart == start && mExpectedSelEnd == end) {
            return;
        }

        updateCachedTextForSelection(start, end);

        if (mLatinIME.mRichImm != null) {
            mLatinIME.mRichImm.resetSubtypeCycleOrder();
        }

        mExpectedSelStart = start;
        mExpectedSelEnd = end;
        // Snapshot was taken at the previous cursor position; the selection just moved, so it
        // is stale by definition.
        invalidateReadSnapshots();
        if (isConnected()) {
            try {
                mIC.setSelection(start, end);
            } catch (Exception ignored) {
                Log.w(TAG, "InputConnection failed", ignored);
            }
        }
    }

    public int getExpectedSelectionStart() {
        return mExpectedSelStart;
    }

    public int getExpectedSelectionEnd() {
        return mExpectedSelEnd;
    }

    /**
     * @return whether there is a selection currently active.
     */
    public boolean hasSelection() {
        return mExpectedSelEnd != mExpectedSelStart;
    }

    public boolean hasCursorPosition() {
        return mExpectedSelStart != INVALID_CURSOR_POSITION && mExpectedSelEnd != INVALID_CURSOR_POSITION;
    }

    private static boolean shouldSkipCharLeft(final CharSequence text, final int i) {
        if (i > 1 && text.charAt(i - 1) == '\u200d') {
            return true;
        }
        if (text.charAt(i) == '\u200d') {
            return true;
        }
        return Character.isSurrogate(text.charAt(i)) && !Character.isHighSurrogate(text.charAt(i));
    }

    private static boolean shouldSkipCharRight(final CharSequence text, final int i) {
        if (i < text.length() - 1 && text.charAt(i + 1) == '\u200d') {
            return true;
        }
        if (text.charAt(i) == '\u200d') {
            return true;
        }
        return Character.isHighSurrogate(text.charAt(i));
    }

    private int getUnicodeStepsLeft(int chars, final boolean rightSidePointer) {
        final CharSequence charsBeforeCursor = rightSidePointer && hasSelection()
                ? getSelectedText()
                : mTextBeforeCursor;
        if (TextUtils.isEmpty(charsBeforeCursor)) {
            return chars;
        }
        int steps = 0;
        for (int i = charsBeforeCursor.length() - 1; i >= 0 && chars < 0; i--, steps--) {
            if (shouldSkipCharLeft(charsBeforeCursor, i)) {
                continue;
            }
            chars++;
        }
        return steps;
    }

    private int getUnicodeStepsRight(int chars, final boolean rightSidePointer) {
        final CharSequence charsAfterCursor = !rightSidePointer && hasSelection()
                ? getSelectedText()
                : mTextAfterCursor;
        if (TextUtils.isEmpty(charsAfterCursor)) {
            return chars;
        }
        int steps = 0;
        for (int i = 0; i < charsAfterCursor.length() && chars > 0; i++, steps++) {
            if (shouldSkipCharRight(charsAfterCursor, i)) {
                continue;
            }
            chars--;
        }
        return steps;
    }

    /**
     * Some chars, such as emoji consist of 2 chars (surrogate pairs). We should treat them as one character.
     * Some chars are joined with ZERO WIDTH JOINER (U+200D), pairs need to be counted
     */
    public int getUnicodeSteps(int chars, boolean rightSidePointer) {
        if (chars < 0) {
            return getUnicodeStepsLeft(chars, rightSidePointer);
        }
        if (chars > 0) {
            return getUnicodeStepsRight(chars, rightSidePointer);
        }
        return 0;
    }
}
