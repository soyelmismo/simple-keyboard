/*
 * Copyright (C) 2010 The Android Open Source Project
 * Copyright (C) 2025 Raimondas Rimkus
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

package rkr.simplekeyboard.inputmethod.keyboard;

import android.content.res.Resources;
import android.content.res.TypedArray;
import android.util.Log;
import android.view.MotionEvent;

import java.util.ArrayList;

import rkr.simplekeyboard.inputmethod.R;
import rkr.simplekeyboard.inputmethod.keyboard.internal.BogusMoveEventDetector;
import rkr.simplekeyboard.inputmethod.keyboard.internal.DrawingProxy;
import rkr.simplekeyboard.inputmethod.keyboard.internal.PointerTrackerQueue;
import rkr.simplekeyboard.inputmethod.keyboard.internal.TimerProxy;
import rkr.simplekeyboard.inputmethod.latin.common.Constants;
import rkr.simplekeyboard.inputmethod.latin.common.CoordinateUtils;
import rkr.simplekeyboard.inputmethod.latin.define.DebugFlags;
import rkr.simplekeyboard.inputmethod.latin.settings.Settings;
import rkr.simplekeyboard.inputmethod.latin.settings.SettingsValues;

public final class PointerTracker implements PointerTrackerQueue.Element {
    private static final String TAG = PointerTracker.class.getSimpleName();
    private static final boolean DEBUG_EVENT = false;
    private static final boolean DEBUG_MOVE_EVENT = false;
    private static final boolean DEBUG_LISTENER = false;
    private static boolean DEBUG_MODE = DebugFlags.DEBUG_ENABLED || DEBUG_EVENT;

    static final class PointerTrackerParams {
        public final boolean mKeySelectionByDraggingFinger;
        public final int mTouchNoiseThresholdTime;
        public final int mTouchNoiseThresholdDistance;
        public final int mKeyRepeatStartTimeout;
        public final int mKeyRepeatInterval;
        public final int mLongPressShiftLockTimeout;

        public PointerTrackerParams(final TypedArray mainKeyboardViewAttr) {
            mKeySelectionByDraggingFinger = mainKeyboardViewAttr.getBoolean(
                    R.styleable.MainKeyboardView_keySelectionByDraggingFinger, false);
            mTouchNoiseThresholdTime = mainKeyboardViewAttr.getInt(
                    R.styleable.MainKeyboardView_touchNoiseThresholdTime, 0);
            mTouchNoiseThresholdDistance = mainKeyboardViewAttr.getDimensionPixelSize(
                    R.styleable.MainKeyboardView_touchNoiseThresholdDistance, 0);
            mKeyRepeatStartTimeout = mainKeyboardViewAttr.getInt(
                    R.styleable.MainKeyboardView_keyRepeatStartTimeout, 0);
            mKeyRepeatInterval = mainKeyboardViewAttr.getInt(
                    R.styleable.MainKeyboardView_keyRepeatInterval, 0);
            mLongPressShiftLockTimeout = mainKeyboardViewAttr.getInt(
                    R.styleable.MainKeyboardView_longPressShiftLockTimeout, 0);
        }
    }

    // Parameters for pointer handling.
    private static PointerTrackerParams sParams;
    private static int sPointerStep = (int)(10.0 * Resources.getSystem().getDisplayMetrics().density);

    private static final ArrayList<PointerTracker> sTrackers = new ArrayList<>();
    private static final PointerTrackerQueue sPointerTrackerQueue = new PointerTrackerQueue();

    public final int mPointerId;

    private static DrawingProxy sDrawingProxy;
    private static TimerProxy sTimerProxy;
    private static KeyboardActionListener sListener = KeyboardActionListener.EMPTY_LISTENER;

    // The {@link KeyDetector} is set whenever the down event is processed. Also this is updated
    // when new {@link Keyboard} is set by {@link #setKeyDetector(KeyDetector)}.
    private KeyDetector mKeyDetector = new KeyDetector();
    private Keyboard mKeyboard;
    private final BogusMoveEventDetector mBogusMoveEventDetector = new BogusMoveEventDetector();

    // The position and time at which first down event occurred.
    private int[] mDownCoordinates = CoordinateUtils.newInstance();

    // The current key where this pointer is.
    private Key mCurrentKey = null;
    // The position where the current key was recognized for the first time.
    private int mKeyX;
    private int mKeyY;

    // Last pointer position.
    private int mLastX;
    private int mLastY;
    private int mStartX;
    //private int mStartY;
    private long mStartTime;
    private boolean mCursorMoved = false;
    // Cached per gesture at onDownEventInternal to avoid singleton lookups in hot paths.
    private SettingsValues mGestureSettings;

    // true if keyboard layout has been changed.
    private boolean mKeyboardLayoutHasBeenChanged;

    // true if this pointer is no longer triggering any action because it has been canceled.
    private boolean mIsTrackingForActionDisabled;

    // the more keys panel currently being shown. equals null if no panel is active.
    private MoreKeysPanel mMoreKeysPanel;

    private static final int MULTIPLIER_FOR_LONG_PRESS_TIMEOUT_IN_SLIDING_INPUT = 3;
    // true if this pointer is in the dragging finger mode.
    boolean mIsInDraggingFinger;
    // true if this pointer is sliding from a modifier key and in the sliding key input mode,
    // so that further modifier keys should be ignored.
    boolean mIsInSlidingKeyInput;
    // if not a NOT_A_CODE, the key of this code is repeating
    private int mCurrentRepeatingKeyCode = Constants.NOT_A_CODE;

    // true if dragging finger is allowed.
    private boolean mIsAllowedDraggingFinger;

    private static Settings sSettings;

    // TODO: Add PointerTrackerFactory singleton and move some class static methods into it.
    public static void init(final TypedArray mainKeyboardViewAttr, final TimerProxy timerProxy,
            final DrawingProxy drawingProxy) {
        sParams = new PointerTrackerParams(mainKeyboardViewAttr);

        final Resources res = mainKeyboardViewAttr.getResources();
        BogusMoveEventDetector.init(res);

        sTimerProxy = timerProxy;
        sDrawingProxy = drawingProxy;
        sSettings = Settings.getInstance();
    }

    public static PointerTracker getPointerTracker(final int id) {
        final ArrayList<PointerTracker> trackers = sTrackers;

        // Create pointer trackers until we can get 'id+1'-th tracker, if needed.
        for (int i = trackers.size(); i <= id; i++) {
            final PointerTracker tracker = new PointerTracker(i);
            trackers.add(tracker);
        }

        return trackers.get(id);
    }

    public static boolean isAnyInDraggingFinger() {
        return sPointerTrackerQueue.isAnyInDraggingFinger();
    }

    public static boolean isAnyInCursorMove() {
        return sPointerTrackerQueue.isAnyInCursorMove();
    }

    public static void cancelAllPointerTrackers() {
        sPointerTrackerQueue.cancelAllPointerTrackers();
    }

    public static void setKeyboardActionListener(final KeyboardActionListener listener) {
        sListener = listener;
    }

    public static void setKeyDetector(final KeyDetector keyDetector) {
        final Keyboard keyboard = keyDetector.getKeyboard();
        if (keyboard == null) {
            return;
        }
        final int trackersSize = sTrackers.size();
        for (int i = 0; i < trackersSize; ++i) {
            final PointerTracker tracker = sTrackers.get(i);
            tracker.setKeyDetectorInner(keyDetector);
        }
    }

    public static void setReleasedKeyGraphicsToAllKeys() {
        final int trackersSize = sTrackers.size();
        for (int i = 0; i < trackersSize; ++i) {
            final PointerTracker tracker = sTrackers.get(i);
            tracker.setReleasedKeyGraphics(tracker.getKey(), true /* withAnimation */);
        }
    }

    public static void dismissAllMoreKeysPanels() {
        final int trackersSize = sTrackers.size();
        for (int i = 0; i < trackersSize; ++i) {
            final PointerTracker tracker = sTrackers.get(i);
            tracker.dismissMoreKeysPanel();
        }
    }

    private PointerTracker(final int id) {
        mPointerId = id;
    }

    // Returns true if keyboard has been changed by this callback.
    private boolean callListenerOnPressAndCheckKeyboardLayoutChange(final Key key,
            final int repeatCount) {
        // While gesture input is going on, this method should be a no-operation. But when gesture
        // input has been canceled, <code>sInGesture</code> and <code>mIsDetectingGesture</code>
        // are set to false. To keep this method is a no-operation,
        // <code>mIsTrackingForActionDisabled</code> should also be taken account of.
        final boolean ignoreModifierKey = shouldIgnoreModifierKey(key);
        if (DEBUG_LISTENER) {
            logPressListener(key, ignoreModifierKey, repeatCount);
        }
        if (ignoreModifierKey) {
            return false;
        }
        sListener.onPressKey(key.getCode(), repeatCount, getActivePointerTrackerCount() == 1);
        final boolean keyboardLayoutHasBeenChanged = mKeyboardLayoutHasBeenChanged;
        mKeyboardLayoutHasBeenChanged = false;
        sTimerProxy.startTypingStateTimer(key);
        return keyboardLayoutHasBeenChanged;
    }

    private boolean shouldIgnoreModifierKey(final Key key) {
        return mIsInDraggingFinger && key != null && key.isModifier();
    }

    private void logPressListener(final Key key, final boolean ignoreModifierKey, final int repeatCount) {
        Log.d(TAG, String.format("[%d] onPress    : %s%s%s", mPointerId,
                (key == null ? "none" : Constants.printableCode(key.getCode())),
                ignoreModifierKey ? " ignoreModifier" : "",
                repeatCount > 0 ? " repeatCount=" + repeatCount : ""));
    }

    // Note that we need primaryCode argument because the keyboard may in shifted state and the
    // primaryCode is different from {@link Key#mKeyCode}.
    private void callListenerOnCodeInput(final Key key, final int primaryCode, final int x,
            final int y, final boolean isKeyRepeat) {
        if (shouldIgnoreModifierKey(key)) {
            return;
        }
        final int code = resolveEffectiveCode(key, primaryCode);
        if (DEBUG_LISTENER) {
            logCodeInputListener(key, code, primaryCode, x, y);
        }
        dispatchCodeInput(key, code, x, y, isKeyRepeat);
    }

    private void logCodeInputListener(final Key key, final int code, final int primaryCode,
            final int x, final int y) {
        final boolean altersCode = code != primaryCode;
        final String output = code == Constants.CODE_OUTPUT_TEXT
                ? key.getOutputText() : Constants.printableCode(code);
        Log.d(TAG, String.format("[%d] onCodeInput: %4d %4d %s%s", mPointerId, x, y,
                output, altersCode ? " altersCode" : ""));
    }

    private int resolveEffectiveCode(final Key key, final int primaryCode) {
        if (key.altCodeWhileTyping() && sTimerProxy.isTypingState()) {
            return key.getAltCode();
        }
        return primaryCode;
    }

    private void dispatchCodeInput(final Key key, final int code, final int x, final int y,
            final boolean isKeyRepeat) {
        if (code == Constants.CODE_OUTPUT_TEXT) {
            sListener.onTextInput(key.getOutputText());
        } else if (code != Constants.CODE_UNSPECIFIED) {
            sListener.onCodeInput(code, x, y, isKeyRepeat);
        }
    }

    // Note that we need primaryCode argument because the keyboard may be in shifted state and the
    // primaryCode is different from {@link Key#mKeyCode}.
    private void callListenerOnRelease(final Key key, final int primaryCode,
            final boolean withSliding) {
        // See the comment at {@link #callListenerOnPressAndCheckKeyboardLayoutChange(Key}}.
        final boolean ignoreModifierKey = shouldIgnoreModifierKey(key);
        if (DEBUG_LISTENER) {
            logReleaseListener(primaryCode, withSliding, ignoreModifierKey);
        }
        if (ignoreModifierKey) {
            return;
        }
        sListener.onReleaseKey(primaryCode, withSliding);
    }

    private void logReleaseListener(final int primaryCode, final boolean withSliding,
            final boolean ignoreModifierKey) {
        Log.d(TAG, String.format("[%d] onRelease  : %s%s%s", mPointerId,
                Constants.printableCode(primaryCode),
                withSliding ? " sliding" : "", ignoreModifierKey ? " ignoreModifier" : ""));
    }

    private void callListenerOnFinishSlidingInput() {
        if (DEBUG_LISTENER) {
            Log.d(TAG, String.format("[%d] onFinishSlidingInput", mPointerId));
        }
        sListener.onFinishSlidingInput();
    }

    private void setKeyDetectorInner(final KeyDetector keyDetector) {
        final Keyboard keyboard = keyDetector.getKeyboard();
        if (keyboard == null) {
            return;
        }
        if (keyDetector == mKeyDetector && keyboard == mKeyboard) {
            return;
        }
        mKeyDetector = keyDetector;
        mKeyboard = keyboard;
        // Mark that keyboard layout has been changed.
        mKeyboardLayoutHasBeenChanged = true;
        final int keyPaddedWidth = mKeyboard.mMostCommonKeyWidth
                + Math.round(mKeyboard.mHorizontalGap);
        final int keyPaddedHeight = mKeyboard.mMostCommonKeyHeight
                + Math.round(mKeyboard.mVerticalGap);
        // Keep {@link #mCurrentKey} that comes from previous keyboard. The key preview of
        // {@link #mCurrentKey} will be dismissed by {@setReleasedKeyGraphics(Key)} via
        // {@link onMoveEventInternal(int,int,long)} or {@link #onUpEventInternal(int,int,long)}.
        mBogusMoveEventDetector.setKeyboardGeometry(keyPaddedWidth, keyPaddedHeight);
    }

    @Override
    public boolean isInDraggingFinger() {
        return mIsInDraggingFinger;
    }

    @Override
    public boolean isInCursorMove() {
        return mCursorMoved;
    }

    public Key getKey() {
        return mCurrentKey;
    }

    @Override
    public boolean isModifier() {
        return mCurrentKey != null && mCurrentKey.isModifier();
    }

    public Key getKeyOn(final int x, final int y) {
        return mKeyDetector.detectHitKey(x, y);
    }

    private void updateAssociatedKeysState(final Key key, final boolean pressed) {
        updateShiftKeysState(key, pressed);
        updateAltKeysState(key, pressed);
    }

    private static void updateKeyVisualState(final Key key, final boolean pressed) {
        if (pressed) {
            sDrawingProxy.onKeyPressed(key, false /* withPreview */);
        } else {
            sDrawingProxy.onKeyReleased(key, false /* withAnimation */);
        }
    }

    private void updateShiftKeysState(final Key key, final boolean pressed) {
        if (!key.isShift()) {
            return;
        }
        for (final Key shiftKey : mKeyboard.mShiftKeys) {
            if (shiftKey != key) {
                updateKeyVisualState(shiftKey, pressed);
            }
        }
    }

    private void updateAltKeysState(final Key key, final boolean pressed) {
        if (!shouldAlterAltCode(key, pressed)) {
            return;
        }
        final int altCode = key.getAltCode();
        updateSingleAltKey(altCode, pressed);
        updateAltCodeKeysWhileTyping(key, altCode, pressed);
    }

    private static boolean shouldAlterAltCode(final Key key, final boolean pressed) {
        return pressed
                ? (key.altCodeWhileTyping() && sTimerProxy.isTypingState())
                : key.altCodeWhileTyping();
    }

    private void updateSingleAltKey(final int altCode, final boolean pressed) {
        final Key altKey = mKeyboard.getKey(altCode);
        if (altKey != null) {
            updateKeyVisualState(altKey, pressed);
        }
    }

    private void updateAltCodeKeysWhileTyping(final Key key, final int altCode, final boolean pressed) {
        for (final Key k : mKeyboard.mAltCodeKeysWhileTyping) {
            if (k != key && k.getAltCode() == altCode) {
                updateKeyVisualState(k, pressed);
            }
        }
    }

    private void setReleasedKeyGraphics(final Key key, final boolean withAnimation) {
        if (key == null) {
            return;
        }

        sDrawingProxy.onKeyReleased(key, withAnimation);
        updateAssociatedKeysState(key, false);
    }

    private void setPressedKeyGraphics(final Key key) {
        if (key == null) {
            return;
        }

        sDrawingProxy.onKeyPressed(key, true);
        updateAssociatedKeysState(key, true);
    }

    public void getLastCoordinates(final int[] outCoords) {
        CoordinateUtils.set(outCoords, mLastX, mLastY);
    }

    private Key onDownKey(final int x, final int y) {
        CoordinateUtils.set(mDownCoordinates, x, y);
        mBogusMoveEventDetector.onDownKey();
        return onMoveToNewKey(onMoveKeyInternal(x, y), x, y);
    }

    private static int getDistance(final int x1, final int y1, final int x2, final int y2) {
        final int dx = x1 - x2;
        final int dy = y1 - y2;
        return (int) Math.sqrt(dx * dx + dy * dy);
    }

    private Key onMoveKeyInternal(final int x, final int y) {
        mBogusMoveEventDetector.onMoveKey(getDistance(x, y, mLastX, mLastY));
        mLastX = x;
        mLastY = y;
        return mKeyDetector.detectHitKey(x, y);
    }

    private Key onMoveKey(final int x, final int y) {
        return onMoveKeyInternal(x, y);
    }

    private Key onMoveToNewKey(final Key newKey, final int x, final int y) {
        mCurrentKey = newKey;
        mKeyX = x;
        mKeyY = y;
        return newKey;
    }

    /* package */ static int getActivePointerTrackerCount() {
        return sPointerTrackerQueue.size();
    }

    public void processMotionEvent(final MotionEvent me, final KeyDetector keyDetector) {
        final int action = me.getActionMasked();
        final long eventTime = me.getEventTime();
        if (action == MotionEvent.ACTION_MOVE) {
            processMoveMotionEvent(me, eventTime);
            return;
        }
        final int index = me.getActionIndex();
        dispatchNonMoveMotionEvent(action, (int) me.getX(index), (int) me.getY(index), eventTime,
                keyDetector);
    }

    private void processMoveMotionEvent(final MotionEvent me, final long eventTime) {
        // When this pointer is the only active pointer and is showing a more keys panel,
        // we should ignore other pointers' motion event.
        final boolean shouldIgnoreOtherPointers =
                isShowingMoreKeysPanel() && getActivePointerTrackerCount() == 1;
        final int pointerCount = me.getPointerCount();
        for (int index = 0; index < pointerCount; index++) {
            final int id = me.getPointerId(index);
            if (shouldIgnoreOtherPointers && id != mPointerId) {
                continue;
            }
            final int x = (int) me.getX(index);
            final int y = (int) me.getY(index);
            final PointerTracker tracker = getPointerTracker(id);
            tracker.onMoveEvent(x, y, eventTime);
        }
    }

    private void dispatchNonMoveMotionEvent(final int action, final int x, final int y,
            final long eventTime, final KeyDetector keyDetector) {
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            onDownEvent(x, y, eventTime, keyDetector);
            return;
        }
        dispatchUpOrCancelEvent(action, x, y, eventTime);
    }

    private void dispatchUpOrCancelEvent(final int action, final int x, final int y,
            final long eventTime) {
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            onUpEvent(x, y, eventTime);
        } else if (action == MotionEvent.ACTION_CANCEL) {
            onCancelEvent(x, y, eventTime);
        }
    }

    private void onDownEvent(final int x, final int y, final long eventTime,
            final KeyDetector keyDetector) {
        setKeyDetectorInner(keyDetector);
        if (DEBUG_EVENT) {
            printTouchEvent("onDownEvent:", x, y, eventTime);
        }
        if (isPotentialTouchNoise(x, y, eventTime)) {
            cancelTrackingForAction();
            return;
        }

        final Key key = getKeyOn(x, y);
        mBogusMoveEventDetector.onActualDownEvent(x, y);
        releasePointersIfModifier(key, eventTime);
        sPointerTrackerQueue.add(this);
        onDownEventInternal(x, y);
    }

    private boolean isPotentialTouchNoise(final int x, final int y, final long eventTime) {
        if (eventTime >= sParams.mTouchNoiseThresholdTime) {
            return false;
        }
        final int distance = getDistance(x, y, mLastX, mLastY);
        if (distance >= sParams.mTouchNoiseThresholdDistance) {
            return false;
        }
        if (DEBUG_MODE) {
            Log.w(TAG, String.format("[%d] onDownEvent:"
                    + " ignore potential noise: time=%d distance=%d",
                    mPointerId, eventTime, distance));
        }
        return true;
    }

    private void releasePointersIfModifier(final Key key, final long eventTime) {
        if (key != null && key.isModifier()) {
            // Before processing a down event of modifier key, all pointers already being
            // tracked should be released.
            sPointerTrackerQueue.releaseAllPointers(eventTime);
        }
    }

    /* package */ boolean isShowingMoreKeysPanel() {
        return (mMoreKeysPanel != null);
    }

    private void dismissMoreKeysPanel() {
        if (isShowingMoreKeysPanel()) {
            mMoreKeysPanel.dismissMoreKeysPanel();
            mMoreKeysPanel = null;
        }
    }

    private void onDownEventInternal(final int x, final int y) {
        if (sSettings == null) {
            sSettings = Settings.getInstance();
        }
        mGestureSettings = sSettings.getCurrent();
        final Key key = onDownKey(x, y);
        mIsAllowedDraggingFinger = isDraggingFingerAllowed(key);
        mKeyboardLayoutHasBeenChanged = false;
        mIsTrackingForActionDisabled = false;
        resetKeySelectionByDraggingFinger();
        if (key != null) {
            processValidDownKey(key, x, y);
        }
    }

    private boolean isDraggingFingerAllowed(final Key key) {
        return sParams.mKeySelectionByDraggingFinger
                || (key != null && key.isModifier())
                || mKeyDetector.alwaysAllowsKeySelectionByDraggingFinger();
    }

    private void processValidDownKey(final Key key, final int x, final int y) {
        Key currentKey = key;
        if (callListenerOnPressAndCheckKeyboardLayoutChange(currentKey, 0 /* repeatCount */)) {
            currentKey = onDownKey(x, y);
        }
        startRepeatKey(currentKey);
        startLongPressTimer(currentKey);
        setPressedKeyGraphics(currentKey);
        mStartX = x;
        mStartTime = System.currentTimeMillis();
    }

    private void startKeySelectionByDraggingFinger(final Key key) {
        if (!mIsInDraggingFinger) {
            mIsInSlidingKeyInput = key.isModifier();
        }
        mIsInDraggingFinger = true;
    }

    private void resetKeySelectionByDraggingFinger() {
        mIsInDraggingFinger = false;
        mIsInSlidingKeyInput = false;
    }

    private void onMoveEvent(final int x, final int y, final long eventTime) {
        if (DEBUG_MOVE_EVENT) {
            printTouchEvent("onMoveEvent:", x, y, eventTime);
        }
        if (mIsTrackingForActionDisabled) {
            return;
        }

        if (isShowingMoreKeysPanel()) {
            final int translatedX = mMoreKeysPanel.translateX(x);
            final int translatedY = mMoreKeysPanel.translateY(y);
            mMoreKeysPanel.onMoveEvent(translatedX, translatedY, mPointerId);
            onMoveKey(x, y);
            return;
        }
        onMoveEventInternal(x, y, eventTime);
    }

    private void processDraggingFingerInToNewKey(final Key newKey, final int x, final int y) {
        // This onPress call may have changed keyboard layout. Those cases are detected
        // at {@link #setKeyboard}. In those cases, we should update key according
        // to the new keyboard layout.
        Key key = newKey;
        if (callListenerOnPressAndCheckKeyboardLayoutChange(key, 0 /* repeatCount */)) {
            key = onMoveKey(x, y);
        }
        onMoveToNewKey(key, x, y);
        if (mIsTrackingForActionDisabled) {
            return;
        }
        startLongPressTimer(key);
        setPressedKeyGraphics(key);
    }

    private void processDraggingFingerOutFromOldKey(final Key oldKey) {
        setReleasedKeyGraphics(oldKey, true /* withAnimation */);
        callListenerOnRelease(oldKey, oldKey.getCode(), true /* withSliding */);
        startKeySelectionByDraggingFinger(oldKey);
        sTimerProxy.cancelKeyTimersOf(this);
    }

    private void dragFingerFromOldKeyToNewKey(final Key key, final int x, final int y,
            final long eventTime, final Key oldKey) {
        // The pointer has been slid in to the new key from the previous key, we must call
        // onRelease() first to notify that the previous key has been released, then call
        // onPress() to notify that the new key is being pressed.
        processDraggingFingerOutFromOldKey(oldKey);
        startRepeatKey(key);
        if (mIsAllowedDraggingFinger) {
            processDraggingFingerInToNewKey(key, x, y);
        }
        // HACK: If there are currently multiple touches, register the key even if the finger
        // slides off the key. This defends against noise from some touch panels when there are
        // close multiple touches.
        // Caveat: When in chording input mode with a modifier key, we don't use this hack.
        else if (getActivePointerTrackerCount() > 1
                && !sPointerTrackerQueue.hasModifierKeyOlderThan(this)) {
            if (DEBUG_MODE) {
                Log.w(TAG, String.format("[%d] onMoveEvent:"
                        + " detected sliding finger while multi touching", mPointerId));
            }
            onUpEvent(x, y, eventTime);
            cancelTrackingForAction();
            setReleasedKeyGraphics(oldKey, true /* withAnimation */);
        } else {
            cancelTrackingForAction();
            setReleasedKeyGraphics(oldKey, true /* withAnimation */);
        }
    }

    private void dragFingerOutFromOldKey(final Key oldKey, final int x, final int y) {
        // The pointer has been slid out from the previous key, we must call onRelease() to
        // notify that the previous key has been released.
        processDraggingFingerOutFromOldKey(oldKey);
        if (mIsAllowedDraggingFinger) {
            onMoveToNewKey(null, x, y);
        } else {
            cancelTrackingForAction();
        }
    }

    private void onMoveEventInternal(final int x, final int y, final long eventTime) {
        final Key oldKey = mCurrentKey;
        if (handleSpaceSwipe(x, oldKey) || handleDeleteSwipe(x, oldKey)) {
            return;
        }
        final Key newKey = onMoveKey(x, y);
        transitionToNewKey(newKey, oldKey, x, y, eventTime);
    }

    @FunctionalInterface
    private interface SwipeListener {
        void onSwipe(final int steps);
    }

    private static final SwipeListener sCursorSwipeListener = steps -> {
        if (sListener != null) {
            sListener.onMoveCursorPointer(steps);
        }
    };

    private final SwipeListener mDeleteSwipeListener = steps -> {
        sTimerProxy.cancelKeyTimersOf(this);
        if (sListener != null) {
            sListener.onMoveDeletePointer(steps);
        }
    };

    private SettingsValues getGestureSettings() {
        SettingsValues sv = mGestureSettings;
        if (sv == null) {
            if (sSettings == null) {
                sSettings = Settings.getInstance();
            }
            sv = sSettings.getCurrent();
            mGestureSettings = sv;
        }
        return sv;
    }

    private boolean handleSwipe(final int x, final Key oldKey, final int targetCode,
            final boolean enabled, final boolean checkTimeout, final SwipeListener listener) {
        if (!isSwipeEligible(oldKey, targetCode, enabled)) {
            return false;
        }
        final SettingsValues sv = getGestureSettings();
        if (sv == null) {
            return false;
        }
        final int effectiveStep = Math.max(1, (int)(sPointerStep * sv.mSwipeSensitivity));
        final int steps = (x - mStartX) / effectiveStep;
        if (steps != 0) {
            applySwipeSteps(steps, effectiveStep, checkTimeout, listener);
        }
        return true;
    }

    private static boolean isSwipeEligible(final Key oldKey, final int targetCode, final boolean enabled) {
        return enabled && oldKey != null && oldKey.getCode() == targetCode;
    }

    private void applySwipeSteps(final int steps, final int effectiveStep, final boolean checkTimeout, final SwipeListener listener) {
        if (checkTimeout && isSwipeTimeoutActive()) {
            return;
        }
        mCursorMoved = true;
        mStartX += steps * effectiveStep;
        listener.onSwipe(steps);
    }

    private boolean isSwipeTimeoutActive() {
        final SettingsValues sv = getGestureSettings();
        if (sv == null) {
            return false;
        }
        final int swipeIgnoreTime = sv.mKeyLongpressTimeout
                / MULTIPLIER_FOR_LONG_PRESS_TIMEOUT_IN_SLIDING_INPUT;
        return mStartTime + swipeIgnoreTime >= System.currentTimeMillis();
    }

    private boolean handleSpaceSwipe(final int x, final Key oldKey) {
        final SettingsValues sv = getGestureSettings();
        return handleSwipe(x, oldKey, Constants.CODE_SPACE,
                sv != null && sv.mSpaceSwipeEnabled,
                true /* checkTimeout */, sCursorSwipeListener);
    }

    private boolean handleDeleteSwipe(final int x, final Key oldKey) {
        final SettingsValues sv = getGestureSettings();
        return handleSwipe(x, oldKey, Constants.CODE_DELETE,
                sv != null && sv.mDeleteSwipeEnabled,
                false /* checkTimeout */, mDeleteSwipeListener);
    }

    private void transitionToNewKey(final Key newKey, final Key oldKey, final int x, final int y,
            final long eventTime) {
        if (newKey != null) {
            handleValidNewKeyTransition(newKey, oldKey, x, y, eventTime);
        } else if (oldKey != null && isMajorEnoughMoveToBeOnNewKey(x, y, null)) {
            dragFingerOutFromOldKey(oldKey, x, y);
        }
    }

    private void handleValidNewKeyTransition(final Key newKey, final Key oldKey, final int x,
            final int y, final long eventTime) {
        if (oldKey != null && isMajorEnoughMoveToBeOnNewKey(x, y, newKey)) {
            dragFingerFromOldKeyToNewKey(newKey, x, y, eventTime, oldKey);
        } else if (oldKey == null) {
            processDraggingFingerInToNewKey(newKey, x, y);
        }
    }

    private void onUpEvent(final int x, final int y, final long eventTime) {
        if (DEBUG_EVENT) {
            printTouchEvent("onUpEvent  :", x, y, eventTime);
        }

        if (mCurrentKey != null && mCurrentKey.isModifier()) {
            // Before processing an up event of modifier key, all pointers already being
            // tracked should be released.
            sPointerTrackerQueue.releaseAllPointersExcept(this, eventTime);
        } else {
            sPointerTrackerQueue.releaseAllPointersOlderThan(this, eventTime);
        }
        onUpEventInternal(x, y);
        sPointerTrackerQueue.remove(this);
    }

    // Let this pointer tracker know that one of newer-than-this pointer trackers got an up event.
    // This pointer tracker needs to keep the key top graphics "pressed", but needs to get a
    // "virtual" up event.
    @Override
    public void onPhantomUpEvent(final long eventTime) {
        if (DEBUG_EVENT) {
            printTouchEvent("onPhntEvent:", mLastX, mLastY, eventTime);
        }
        onUpEventInternal(mLastX, mLastY);
        cancelTrackingForAction();
    }

    private void onUpEventInternal(final int x, final int y) {
        sTimerProxy.cancelKeyTimersOf(this);
        final boolean isInDraggingFinger = mIsInDraggingFinger;
        final boolean isInSlidingKeyInput = mIsInSlidingKeyInput;
        resetKeySelectionByDraggingFinger();
        final Key currentKey = mCurrentKey;
        mCurrentKey = null;
        final int currentRepeatingKeyCode = mCurrentRepeatingKeyCode;
        mCurrentRepeatingKeyCode = Constants.NOT_A_CODE;
        // Release the last pressed key.
        setReleasedKeyGraphics(currentKey, true /* withAnimation */);

        notifyUpWithActivePointer(currentKey);

        if (isShowingMoreKeysPanel()) {
            handleMoreKeysPanelUp(x, y);
            return;
        }

        if (isKeyActionSuppressed(currentKey, currentRepeatingKeyCode, isInDraggingFinger)) {
            return;
        }
        detectAndSendKey(currentKey, mKeyX, mKeyY);
        if (isInSlidingKeyInput) {
            callListenerOnFinishSlidingInput();
        }
    }

    private void notifyUpWithActivePointer(final Key currentKey) {
        if (!mCursorMoved || currentKey == null) {
            return;
        }
        if (currentKey.getCode() == Constants.CODE_DELETE) {
            sListener.onUpWithDeletePointerActive();
        } else if (currentKey.getCode() == Constants.CODE_SPACE) {
            sListener.onUpWithSpacePointerActive();
        }
    }

    private void handleMoreKeysPanelUp(final int x, final int y) {
        if (!mIsTrackingForActionDisabled) {
            final int translatedX = mMoreKeysPanel.translateX(x);
            final int translatedY = mMoreKeysPanel.translateY(y);
            mMoreKeysPanel.onUpEvent(translatedX, translatedY, mPointerId);
        }
        dismissMoreKeysPanel();
    }

    private boolean isKeyActionSuppressed(final Key currentKey, final int repeatingCode,
            final boolean isDragging) {
        if (mCursorMoved) {
            mCursorMoved = false;
            return true;
        }
        if (mIsTrackingForActionDisabled) {
            return true;
        }
        return isRepeatKeySuppressed(currentKey, repeatingCode, isDragging);
    }

    private static boolean isRepeatKeySuppressed(final Key key, final int repeatingCode,
            final boolean isDragging) {
        if (isDragging || key == null || !key.isRepeatable()) {
            return false;
        }
        return key.getCode() == repeatingCode;
    }

    @Override
    public void cancelTrackingForAction() {
        if (isShowingMoreKeysPanel()) {
            return;
        }
        mIsTrackingForActionDisabled = true;
    }

    public void onLongPressed() {
        sTimerProxy.cancelLongPressTimersOf(this);
        if (isLongPressSuppressed()) {
            return;
        }
        final Key key = getKey();
        if (key == null) {
            return;
        }
        if (handleSpecialLongPressKey(key)) {
            return;
        }
        showMoreKeysPanelForLongPress(key);
    }

    private boolean isLongPressSuppressed() {
        return isShowingMoreKeysPanel() || mCursorMoved;
    }

    private boolean handleSpecialLongPressKey(final Key key) {
        return handleNoPanelAutoMoreKey(key) || handleSpaceOrLanguageSwitchLongPress(key);
    }

    private boolean handleNoPanelAutoMoreKey(final Key key) {
        if (!key.hasNoPanelAutoMoreKey()) {
            return false;
        }
        cancelKeyTracking();
        final int moreKeyCode = key.getMoreKeys()[0].mCode;
        sListener.onPressKey(moreKeyCode, 0 /* repeatCont */, true /* isSinglePointer */);
        sListener.onCodeInput(moreKeyCode, Constants.NOT_A_COORDINATE,
                Constants.NOT_A_COORDINATE, false /* isKeyRepeat */);
        sListener.onReleaseKey(moreKeyCode, false /* withSliding */);
        return true;
    }

    private boolean handleSpaceOrLanguageSwitchLongPress(final Key key) {
        final int code = key.getCode();
        if (code != Constants.CODE_SPACE && code != Constants.CODE_LANGUAGE_SWITCH) {
            return false;
        }
        // Long pressing the space key invokes IME switcher dialog.
        if (sListener.onCustomRequest(Constants.CUSTOM_CODE_SHOW_INPUT_METHOD_PICKER)) {
            cancelKeyTracking();
            sListener.onReleaseKey(code, false /* withSliding */);
            return true;
        }
        return false;
    }

    private void showMoreKeysPanelForLongPress(final Key key) {
        setReleasedKeyGraphics(key, false /* withAnimation */);
        final MoreKeysPanel moreKeysPanel = sDrawingProxy.showMoreKeysKeyboard(key, this);
        if (moreKeysPanel == null) {
            return;
        }
        final int translatedX = moreKeysPanel.translateX(mLastX);
        final int translatedY = moreKeysPanel.translateY(mLastY);
        moreKeysPanel.onDownEvent(translatedX, translatedY, mPointerId);
        mMoreKeysPanel = moreKeysPanel;
    }

    private void cancelKeyTracking() {
        resetKeySelectionByDraggingFinger();
        cancelTrackingForAction();
        setReleasedKeyGraphics(mCurrentKey, true /* withAnimation */);
        sPointerTrackerQueue.remove(this);
    }

    private void onCancelEvent(final int x, final int y, final long eventTime) {
        if (DEBUG_EVENT) {
            printTouchEvent("onCancelEvt:", x, y, eventTime);
        }

        cancelAllPointerTrackers();
        sPointerTrackerQueue.releaseAllPointers(eventTime);
        onCancelEventInternal();
    }

    private void onCancelEventInternal() {
        sTimerProxy.cancelKeyTimersOf(this);
        setReleasedKeyGraphics(mCurrentKey, true /* withAnimation */);
        resetKeySelectionByDraggingFinger();
        dismissMoreKeysPanel();
    }

    private boolean isMajorEnoughMoveToBeOnNewKey(final int x, final int y, final Key newKey) {
        final Key curKey = mCurrentKey;
        if (newKey == curKey) {
            return false;
        }
        if (curKey == null /* && newKey != null */) {
            return true;
        }
        return isMovedBeyondHysteresis(curKey, x, y) || isBogusLongDistanceMove(x, y);
    }

    private boolean isMovedBeyondHysteresis(final Key curKey, final int x, final int y) {
        final int keyHysteresisDistanceSquared = mKeyDetector.getKeyHysteresisDistanceSquared(
                mIsInSlidingKeyInput);
        final int distanceFromKeyEdgeSquared = curKey.squaredDistanceToHitboxEdge(x, y);
        if (distanceFromKeyEdgeSquared < keyHysteresisDistanceSquared) {
            return false;
        }
        if (DEBUG_MODE) {
            final float distanceToEdgeRatio = (float)Math.sqrt(distanceFromKeyEdgeSquared)
                    / (mKeyboard.mMostCommonKeyWidth + mKeyboard.mHorizontalGap);
            Log.d(TAG, String.format("[%d] isMajorEnoughMoveToBeOnNewKey:"
                    + " %.2f key width from key edge", mPointerId, distanceToEdgeRatio));
        }
        return true;
    }

    private boolean isBogusLongDistanceMove(final int x, final int y) {
        if (mIsAllowedDraggingFinger || !mBogusMoveEventDetector.hasTraveledLongDistance(x, y)) {
            return false;
        }
        if (DEBUG_MODE) {
            final float keyDiagonal = (float)Math.hypot(
                    mKeyboard.mMostCommonKeyWidth + mKeyboard.mHorizontalGap,
                    mKeyboard.mMostCommonKeyHeight + mKeyboard.mVerticalGap);
            final float lengthFromDownRatio =
                    mBogusMoveEventDetector.getAccumulatedDistanceFromDownKey() / keyDiagonal;
            Log.d(TAG, String.format("[%d] isMajorEnoughMoveToBeOnNewKey:"
                    + " %.2f key diagonal from virtual down point",
                    mPointerId, lengthFromDownRatio));
        }
        return true;
    }

    private void startLongPressTimer(final Key key) {
        // Note that we need to cancel all active long press shift key timers if any whenever we
        // start a new long press timer for both non-shift and shift keys.
        sTimerProxy.cancelLongPressShiftKeyTimer();
        if (!shouldStartLongPressTimer(key)) {
            return;
        }
        final int delay = getLongPressTimeout(key.getCode());
        if (delay > 0) {
            sTimerProxy.startLongPressTimerOf(this, delay);
        }
    }

    private boolean shouldStartLongPressTimer(final Key key) {
        if (key == null || !key.isLongPressEnabled()) {
            return false;
        }
        // Caveat: Please note that isLongPressEnabled() can be true even if the current key
        // doesn't have its more keys. (e.g. spacebar, globe key) If we are in the dragging finger
        // mode, we will disable long press timer of such key.
        // We always need to start the long press timer if the key has its more keys regardless of
        // whether or not we are in the dragging finger mode.
        return !mIsInDraggingFinger || key.getMoreKeys() != null;
    }

    private int getLongPressTimeout(final int code) {
        if (code == Constants.CODE_SHIFT) {
            return sParams.mLongPressShiftLockTimeout;
        }
        final SettingsValues sv = getGestureSettings();
        final int longpressTimeout = sv != null ? sv.mKeyLongpressTimeout : sParams.mLongPressShiftLockTimeout;
        if (mIsInSlidingKeyInput) {
            // We use longer timeout for sliding finger input started from the modifier key.
            return longpressTimeout * MULTIPLIER_FOR_LONG_PRESS_TIMEOUT_IN_SLIDING_INPUT;
        }
        if (code == Constants.CODE_SPACE) {
            // Cursor can be moved in space
            return longpressTimeout * MULTIPLIER_FOR_LONG_PRESS_TIMEOUT_IN_SLIDING_INPUT;
        }
        return longpressTimeout;
    }

    private void detectAndSendKey(final Key key, final int x, final int y) {
        if (key == null) {
            Log.w(TAG, "PointerTracker: key is null");
            return;
        }

        final int code = key.getCode();
        callListenerOnCodeInput(key, code, x, y, false /* isKeyRepeat */);
        callListenerOnRelease(key, code, false /* withSliding */);
    }

    private void startRepeatKey(final Key key) {
        if (key == null) {
            Log.w(TAG, "PointerTracker: key is null");
            return;
        }
        if (!key.isRepeatable()) return;
        // Don't start key repeat when we are in the dragging finger mode.
        if (mIsInDraggingFinger) return;
        final int startRepeatCount = 1;
        startKeyRepeatTimer(startRepeatCount);
    }

    public void onKeyRepeat(final int code, final int repeatCount) {
        final Key key = getKey();
        if (key == null || key.getCode() != code) {
            mCurrentRepeatingKeyCode = Constants.NOT_A_CODE;
            return;
        }
        mCurrentRepeatingKeyCode = code;
        final int nextRepeatCount = repeatCount + 1;
        startKeyRepeatTimer(nextRepeatCount);
        callListenerOnPressAndCheckKeyboardLayoutChange(key, repeatCount);
        callListenerOnCodeInput(key, code, mKeyX, mKeyY, true /* isKeyRepeat */);
    }

    private void startKeyRepeatTimer(final int repeatCount) {
        final SettingsValues settingsValues = getGestureSettings();
        final int startTimeout = (settingsValues != null && settingsValues.mKeyRepeatStartTimeout > 0)
                ? settingsValues.mKeyRepeatStartTimeout : sParams.mKeyRepeatStartTimeout;
        final int repeatInterval = (settingsValues != null && settingsValues.mKeyRepeatInterval > 0)
                ? settingsValues.mKeyRepeatInterval : sParams.mKeyRepeatInterval;
        final int delay = (repeatCount == 1) ? startTimeout : repeatInterval;
        sTimerProxy.startKeyRepeatTimerOf(this, repeatCount, delay);
    }

    private void printTouchEvent(final String title, final int x, final int y,
            final long eventTime) {
        final Key key = mKeyDetector.detectHitKey(x, y);
        final String code = (key == null ? "none" : Constants.printableCode(key.getCode()));
        Log.d(TAG, String.format("[%d]%s%s %4d %4d %5d %s", mPointerId,
                (mIsTrackingForActionDisabled ? "-" : " "), title, x, y, eventTime, code));
    }
}
