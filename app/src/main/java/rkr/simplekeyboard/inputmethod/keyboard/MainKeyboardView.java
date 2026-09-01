/*
 * Copyright (C) 2011 The Android Open Source Project
 * Copyright (C) 2025 Raimondas Rimkus
 * Copyright (C) 2021 wittmane
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

import android.animation.AnimatorInflater;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import java.util.WeakHashMap;

import rkr.simplekeyboard.inputmethod.R;
import rkr.simplekeyboard.inputmethod.keyboard.internal.DrawingPreviewPlacerView;
import rkr.simplekeyboard.inputmethod.keyboard.internal.DrawingProxy;
import rkr.simplekeyboard.inputmethod.keyboard.internal.KeyDrawParams;
import rkr.simplekeyboard.inputmethod.keyboard.internal.KeyPreviewChoreographer;
import rkr.simplekeyboard.inputmethod.keyboard.internal.KeyPreviewDrawParams;
import rkr.simplekeyboard.inputmethod.keyboard.internal.KeyPreviewView;
import rkr.simplekeyboard.inputmethod.keyboard.internal.MoreKeySpec;
import rkr.simplekeyboard.inputmethod.keyboard.internal.NonDistinctMultitouchHelper;
import rkr.simplekeyboard.inputmethod.keyboard.internal.TimerHandler;
import rkr.simplekeyboard.inputmethod.latin.Subtype;
import rkr.simplekeyboard.inputmethod.latin.RichInputMethodManager;
import rkr.simplekeyboard.inputmethod.latin.common.Constants;
import rkr.simplekeyboard.inputmethod.latin.common.CoordinateUtils;
import rkr.simplekeyboard.inputmethod.latin.settings.Settings;
import rkr.simplekeyboard.inputmethod.latin.settings.SettingsValues;
import rkr.simplekeyboard.inputmethod.latin.utils.LanguageOnSpacebarUtils;
import rkr.simplekeyboard.inputmethod.latin.utils.LocaleResourceUtils;
import rkr.simplekeyboard.inputmethod.latin.utils.TypefaceUtils;

/**
 * A view that is responsible for detecting key presses and touch movements.
 */
public final class MainKeyboardView extends KeyboardView implements MoreKeysPanel.Controller, DrawingProxy {
    private static final String TAG = MainKeyboardView.class.getSimpleName();

    /** Listener for {@link KeyboardActionListener}. */
    private KeyboardActionListener mKeyboardActionListener;

    /* Space key and its icon and background. */
    private Key mSpaceKey;
    // Stuff to draw language name on spacebar.
    private final int mLanguageOnSpacebarFinalAlpha;
    private int mLanguageOnSpacebarFormatType;
    private final float mLanguageOnSpacebarTextRatio;
    private float mLanguageOnSpacebarTextSize;
    private final int mLanguageOnSpacebarTextColor;
    private boolean mShouldDrawLanguageOnSpacebar;
    // The minimum x-scale to fit the language name on spacebar.
    private static final float MINIMUM_XSCALE_OF_LANGUAGE_NAME = 0.8f;

    // Stuff to draw altCodeWhileTyping keys.
    private final ObjectAnimator mAltCodeKeyWhileTypingFadeoutAnimator;
    private final ObjectAnimator mAltCodeKeyWhileTypingFadeinAnimator;
    private int mAltCodeKeyWhileTypingAnimAlpha = Constants.Color.ALPHA_OPAQUE;

    // Drawing preview placer view
    private final DrawingPreviewPlacerView mDrawingPreviewPlacerView;
    private final int[] mOriginCoords = CoordinateUtils.newInstance();
    private final int[] mScratchCoordinates = CoordinateUtils.newInstance();

    // Key preview
    private final KeyPreviewDrawParams mKeyPreviewDrawParams;
    private final KeyPreviewChoreographer mKeyPreviewChoreographer;

    // More keys keyboard
    private final Paint mBackgroundDimAlphaPaint = new Paint();
    private final View mMoreKeysKeyboardContainer;
    private final WeakHashMap<Key, Keyboard> mMoreKeysKeyboardCache = new WeakHashMap<>();
    private final boolean mConfigShowMoreKeysKeyboardAtTouchedPoint;
    // More keys panel (used by both more keys keyboard and more suggestions view)
    // TODO: Consider extending to support multiple more keys panels
    private MoreKeysPanel mMoreKeysPanel;

    private final KeyDetector mKeyDetector;
    private final NonDistinctMultitouchHelper mNonDistinctMultitouchHelper;

    private final TimerHandler mTimerHandler;
    private final int mLanguageOnSpacebarHorizontalMargin;

    public MainKeyboardView(final Context context, final AttributeSet attrs) {
        this(context, attrs, R.attr.mainKeyboardViewStyle);
    }

    public MainKeyboardView(final Context context, final AttributeSet attrs, final int defStyle) {
        super(context, attrs, defStyle);

        final DrawingPreviewPlacerView drawingPreviewPlacerView =
                new DrawingPreviewPlacerView(context, attrs);

        final TypedArray mainKeyboardViewAttr = context.obtainStyledAttributes(
                attrs, R.styleable.MainKeyboardView, defStyle, R.style.MainKeyboardView);
        final int ignoreAltCodeKeyTimeout = mainKeyboardViewAttr.getInt(
                R.styleable.MainKeyboardView_ignoreAltCodeKeyTimeout, 0);
        mTimerHandler = new TimerHandler(this, ignoreAltCodeKeyTimeout);

        final float keyHysteresisDistance = mainKeyboardViewAttr.getDimension(
                R.styleable.MainKeyboardView_keyHysteresisDistance, 0.0f);
        final float keyHysteresisDistanceForSlidingModifier = mainKeyboardViewAttr.getDimension(
                R.styleable.MainKeyboardView_keyHysteresisDistanceForSlidingModifier, 0.0f);
        mKeyDetector = new KeyDetector(
                keyHysteresisDistance, keyHysteresisDistanceForSlidingModifier);

        PointerTracker.init(mainKeyboardViewAttr, mTimerHandler, this /* DrawingProxy */);

        final boolean hasDistinctMultitouch = context.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH_DISTINCT);
        mNonDistinctMultitouchHelper = hasDistinctMultitouch ? null
                : new NonDistinctMultitouchHelper();

        final int backgroundDimAlpha = mainKeyboardViewAttr.getInt(
                R.styleable.MainKeyboardView_backgroundDimAlpha, 0);
        mBackgroundDimAlphaPaint.setColor(Color.BLACK);
        mBackgroundDimAlphaPaint.setAlpha(backgroundDimAlpha);
        mLanguageOnSpacebarTextRatio = mainKeyboardViewAttr.getFraction(
                R.styleable.MainKeyboardView_languageOnSpacebarTextRatio, 1, 1, 1.0f);
        mLanguageOnSpacebarTextColor = mainKeyboardViewAttr.getColor(
                R.styleable.MainKeyboardView_languageOnSpacebarTextColor, 0);
        mLanguageOnSpacebarFinalAlpha = mainKeyboardViewAttr.getInt(
                R.styleable.MainKeyboardView_languageOnSpacebarFinalAlpha,
                Constants.Color.ALPHA_OPAQUE);
        final int altCodeKeyWhileTypingFadeoutAnimatorResId = mainKeyboardViewAttr.getResourceId(
                R.styleable.MainKeyboardView_altCodeKeyWhileTypingFadeoutAnimator, 0);
        final int altCodeKeyWhileTypingFadeinAnimatorResId = mainKeyboardViewAttr.getResourceId(
                R.styleable.MainKeyboardView_altCodeKeyWhileTypingFadeinAnimator, 0);

        mKeyPreviewDrawParams = new KeyPreviewDrawParams(mainKeyboardViewAttr);
        mKeyPreviewChoreographer = new KeyPreviewChoreographer(mKeyPreviewDrawParams);

        final int moreKeysKeyboardLayoutId = mainKeyboardViewAttr.getResourceId(
                R.styleable.MainKeyboardView_moreKeysKeyboardLayout, 0);
        mConfigShowMoreKeysKeyboardAtTouchedPoint = mainKeyboardViewAttr.getBoolean(
                R.styleable.MainKeyboardView_showMoreKeysKeyboardAtTouchedPoint, false);

        mainKeyboardViewAttr.recycle();

        mDrawingPreviewPlacerView = drawingPreviewPlacerView;

        final LayoutInflater inflater = LayoutInflater.from(getContext());
        mMoreKeysKeyboardContainer = inflater.inflate(moreKeysKeyboardLayoutId, null);
        mAltCodeKeyWhileTypingFadeoutAnimator = loadObjectAnimator(
                altCodeKeyWhileTypingFadeoutAnimatorResId, this);
        mAltCodeKeyWhileTypingFadeinAnimator = loadObjectAnimator(
                altCodeKeyWhileTypingFadeinAnimatorResId, this);

        mKeyboardActionListener = KeyboardActionListener.EMPTY_LISTENER;

        mLanguageOnSpacebarHorizontalMargin = (int)getResources().getDimension(
                R.dimen.config_language_on_spacebar_horizontal_margin);
    }

    private ObjectAnimator loadObjectAnimator(final int resId, final Object target) {
        if (resId == 0) {
            // TODO: Stop returning null.
            return null;
        }
        final ObjectAnimator animator = (ObjectAnimator)AnimatorInflater.loadAnimator(
                getContext(), resId);
        if (animator != null) {
            animator.setTarget(target);
        }
        return animator;
    }

    private static void cancelAndStartAnimators(final ObjectAnimator animatorToCancel,
            final ObjectAnimator animatorToStart) {
        if (animatorToCancel == null || animatorToStart == null) {
            // TODO: Stop using null as a no-operation animator.
            return;
        }
        float startFraction = 0.0f;
        if (animatorToCancel.isStarted()) {
            animatorToCancel.cancel();
            startFraction = 1.0f - animatorToCancel.getAnimatedFraction();
        }
        final long startTime = (long)(animatorToStart.getDuration() * startFraction);
        animatorToStart.start();
        animatorToStart.setCurrentPlayTime(startTime);
    }

    // Implements {@link DrawingProxy#startWhileTypingAnimation(int)}.
    /**
     * Called when a while-typing-animation should be started.
     * @param fadeInOrOut {@link DrawingProxy#FADE_IN} starts while-typing-fade-in animation.
     * {@link DrawingProxy#FADE_OUT} starts while-typing-fade-out animation.
     */
    @Override
    public void startWhileTypingAnimation(final int fadeInOrOut) {
        switch (fadeInOrOut) {
        case DrawingProxy.FADE_IN:
            cancelAndStartAnimators(
                    mAltCodeKeyWhileTypingFadeoutAnimator, mAltCodeKeyWhileTypingFadeinAnimator);
            break;
        case DrawingProxy.FADE_OUT:
            cancelAndStartAnimators(
                    mAltCodeKeyWhileTypingFadeinAnimator, mAltCodeKeyWhileTypingFadeoutAnimator);
            break;
        }
    }

    public void setKeyboardActionListener(final KeyboardActionListener listener) {
        mKeyboardActionListener = listener;
        PointerTracker.setKeyboardActionListener(listener);
    }

    /**
     * Attaches a keyboard to this view. The keyboard can be switched at any time and the
     * view will re-layout itself to accommodate the keyboard.
     * @see Keyboard
     * @see #getKeyboard()
     * @param keyboard the keyboard to display in this view
     */
    @Override
    public void setKeyboard(final Keyboard keyboard) {
        // Remove any pending messages, except dismissing preview and key repeat.
        mTimerHandler.cancelLongPressTimers();
        super.setKeyboard(keyboard);
        mKeyDetector.setKeyboard(
                keyboard, -getPaddingLeft(), -getPaddingTop() + getVerticalCorrection());
        PointerTracker.setKeyDetector(mKeyDetector);
        mMoreKeysKeyboardCache.clear();

        mSpaceKey = keyboard.getKey(Constants.CODE_SPACE);
        final int keyHeight = keyboard.mMostCommonKeyHeight;
        mLanguageOnSpacebarTextSize = keyHeight * mLanguageOnSpacebarTextRatio;
        final SettingsValues settingsValues = Settings.getInstance().getCurrent();
        final boolean showLanguageOnSpacebar = settingsValues == null || settingsValues.mShowLanguageOnSpacebar;
        mShouldDrawLanguageOnSpacebar = showLanguageOnSpacebar && RichInputMethodManager.getInstance().hasMultipleEnabledSubtypes();
    }

    /**
     * Enables or disables the key preview popup. This is a popup that shows a magnified
     * version of the depressed key. By default the preview is enabled.
     * @param previewEnabled whether or not to enable the key feedback preview
     * @param delay the delay after which the preview is dismissed
     */
    public void setKeyPreviewPopupEnabled(final boolean previewEnabled, final int delay) {
        mKeyPreviewDrawParams.setPopupEnabled(previewEnabled, delay);
    }

    private void locatePreviewPlacerView() {
        getLocationInWindow(mOriginCoords);
        mDrawingPreviewPlacerView.setKeyboardViewGeometry(mOriginCoords);
        final View parent = (View) mDrawingPreviewPlacerView.getParent();
        if (parent != null && (mDrawingPreviewPlacerView.getWidth() == 0 || mDrawingPreviewPlacerView.getHeight() == 0)) {
            final int w = parent.getWidth();
            final int h = parent.getHeight();
            if (w > 0 && h > 0) {
                mDrawingPreviewPlacerView.layout(0, 0, w, h);
            }
        }
        mDrawingPreviewPlacerView.bringToFront();
    }

    private void installPreviewPlacerView() {
        final View rootView = getRootView();
        if (rootView == null) {
            Log.w(TAG, "Cannot find root view");
            return;
        }
        final ViewGroup windowContentView = rootView.findViewById(android.R.id.content);
        // Note: It'd be very weird if we get null by android.R.id.content.
        if (windowContentView == null) {
            Log.w(TAG, "Cannot find android.R.id.content view to add DrawingPreviewPlacerView");
            return;
        }
        final ViewGroup currentParent = (ViewGroup) mDrawingPreviewPlacerView.getParent();
        if (currentParent != null) {
            if (currentParent == windowContentView) {
                return;
            }
            currentParent.removeView(mDrawingPreviewPlacerView);
        }
        windowContentView.addView(mDrawingPreviewPlacerView,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final int w = windowContentView.getWidth();
        final int h = windowContentView.getHeight();
        if (w > 0 && h > 0) {
            mDrawingPreviewPlacerView.layout(0, 0, w, h);
        }
    }

    // Implements {@link DrawingProxy#onKeyPressed(Key,boolean)}.
    @Override
    public void onKeyPressed(final Key key, final boolean withPreview) {
        key.onPressed();
        invalidateKey(key);
        if (withPreview && !key.noKeyPreview()) {
            showKeyPreview(key);
        }
    }

    private void showKeyPreview(final Key key) {
        final Keyboard keyboard = getKeyboard();
        if (keyboard == null) {
            return;
        }
        final KeyPreviewDrawParams previewParams = mKeyPreviewDrawParams;
        if (!previewParams.isPopupEnabled()) {
            previewParams.setVisibleOffset(-Math.round(keyboard.mVerticalGap));
            return;
        }

        if (mDrawingPreviewPlacerView.getParent() == null) {
            installPreviewPlacerView();
        }
        locatePreviewPlacerView();
        final int backgroundColor = Color.TRANSPARENT;
        final float cornerRadius = rkr.simplekeyboard.inputmethod.keyboard.internal.KeyShapeHelper.getCornerRadius(getContext(), mKeyShape);
        mKeyPreviewChoreographer.placeAndShowKeyPreview(key, keyboard.mIconsSet, getKeyDrawParams(),
                mOriginCoords, mDrawingPreviewPlacerView, isHardwareAccelerated(), backgroundColor, cornerRadius);
    }

    private void dismissKeyPreviewWithoutDelay(final Key key) {
        mKeyPreviewChoreographer.dismissKeyPreview(key, false /* withAnimation */);
        invalidateKey(key);
    }

    // Implements {@link DrawingProxy#onKeyReleased(Key,boolean)}.
    @Override
    public void onKeyReleased(final Key key, final boolean withAnimation) {
        key.onReleased();
        invalidateKey(key);
        if (!key.noKeyPreview()) {
            if (withAnimation) {
                dismissKeyPreview(key);
            } else {
                dismissKeyPreviewWithoutDelay(key);
            }
        }
    }

    private void dismissKeyPreview(final Key key) {
        if (isHardwareAccelerated()) {
            mKeyPreviewChoreographer.dismissKeyPreview(key, true /* withAnimation */);
            return;
        }
        // TODO: Implement preference option to control key preview method and duration.
        mTimerHandler.postDismissKeyPreview(key, mKeyPreviewDrawParams.getLingerTimeout());
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        installPreviewPlacerView();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mDrawingPreviewPlacerView.removeAllViews();
        if (mKeyPreviewChoreographer != null) {
            mKeyPreviewChoreographer.deallocate();
        }
    }

    // Implements {@link DrawingProxy@showMoreKeysKeyboard(Key,PointerTracker)}.
    public MoreKeysPanel showMoreKeysKeyboard(final Key key,
            final PointerTracker tracker) {
        final MoreKeySpec[] moreKeys = key.getMoreKeys();
        if (moreKeys == null) {
            return null;
        }
        final Keyboard moreKeysKeyboard = getOrCreateMoreKeysKeyboard(key, moreKeys);

        final MoreKeysKeyboardView moreKeysKeyboardView =
                mMoreKeysKeyboardContainer.findViewById(R.id.more_keys_keyboard_view);
        moreKeysKeyboardView.setKeyboard(moreKeysKeyboard);
        mMoreKeysKeyboardContainer.measure(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        final int pointX = calculateMoreKeysPointX(key, tracker);
        final int pointY = calculateMoreKeysPointY(key, moreKeysKeyboard);
        moreKeysKeyboardView.showMoreKeysPanel(this, this, pointX, pointY, mKeyboardActionListener);
        return moreKeysKeyboardView;
    }

    private Keyboard getOrCreateMoreKeysKeyboard(final Key key, final MoreKeySpec[] moreKeys) {
        Keyboard moreKeysKeyboard = mMoreKeysKeyboardCache.get(key);
        if (moreKeysKeyboard != null) {
            return moreKeysKeyboard;
        }
        final boolean isSingleWithPreview = isSingleMoreKeyWithPreview(key, moreKeys);
        final MoreKeysKeyboard.Builder builder = new MoreKeysKeyboard.Builder(
                getContext(), key, getKeyboard(), isSingleWithPreview,
                mKeyPreviewDrawParams.getVisibleWidth(),
                mKeyPreviewDrawParams.getVisibleHeight(), newLabelPaint(key));
        moreKeysKeyboard = builder.build();
        mMoreKeysKeyboardCache.put(key, moreKeysKeyboard);
        return moreKeysKeyboard;
    }

    private boolean isSingleMoreKeyWithPreview(final Key key, final MoreKeySpec[] moreKeys) {
        return mKeyPreviewDrawParams.isPopupEnabled()
                && !key.noKeyPreview()
                && moreKeys.length == 1
                && mKeyPreviewDrawParams.getVisibleWidth() > 0;
    }

    private int calculateMoreKeysPointX(final Key key, final PointerTracker tracker) {
        final boolean keyPreviewEnabled = mKeyPreviewDrawParams.isPopupEnabled()
                && !key.noKeyPreview();
        if (mConfigShowMoreKeysKeyboardAtTouchedPoint && !keyPreviewEnabled) {
            final int[] lastCoords = mScratchCoordinates;
            tracker.getLastCoordinates(lastCoords);
            return CoordinateUtils.x(lastCoords);
        }
        return key.getX() + key.getWidth() / 2;
    }

    private int calculateMoreKeysPointY(final Key key, final Keyboard moreKeysKeyboard) {
        return key.getY() + mKeyPreviewDrawParams.getVisibleOffset()
                + Math.round(moreKeysKeyboard.mBottomPadding);
    }

    public boolean isInDraggingFinger() {
        if (isShowingMoreKeysPanel()) {
            return true;
        }
        return PointerTracker.isAnyInDraggingFinger();
    }

    public boolean isInCursorMove() {
        return PointerTracker.isAnyInCursorMove() || mTimerHandler.isInKeyRepeat();
    }

    @Override
    public void onShowMoreKeysPanel(final MoreKeysPanel panel) {
        locatePreviewPlacerView();
        // Dismiss another {@link MoreKeysPanel} that may be being showed.
        onDismissMoreKeysPanel();
        // Dismiss all key previews that may be being showed.
        PointerTracker.setReleasedKeyGraphicsToAllKeys();
        // Dismiss sliding key input preview that may be being showed.
        panel.showInParent(mDrawingPreviewPlacerView);
        mMoreKeysPanel = panel;
    }

    public boolean isShowingMoreKeysPanel() {
        return mMoreKeysPanel != null && mMoreKeysPanel.isShowingInParent();
    }

    @Override
    public void onCancelMoreKeysPanel() {
        PointerTracker.dismissAllMoreKeysPanels();
    }

    @Override
    public void onDismissMoreKeysPanel() {
        if (isShowingMoreKeysPanel()) {
            mMoreKeysPanel.removeFromParent();
            mMoreKeysPanel = null;
        }
    }

    public void startDoubleTapShiftKeyTimer() {
        mTimerHandler.startDoubleTapShiftKeyTimer();
    }

    public void cancelDoubleTapShiftKeyTimer() {
        mTimerHandler.cancelDoubleTapShiftKeyTimer();
    }

    public boolean isInDoubleTapShiftKeyTimeout() {
        return mTimerHandler.isInDoubleTapShiftKeyTimeout();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(final MotionEvent event) {
        if (getKeyboard() == null) {
            return false;
        }
        if (mNonDistinctMultitouchHelper != null) {
            if (event.getPointerCount() > 1 && mTimerHandler.isInKeyRepeat()) {
                // Key repeating timer will be canceled if 2 or more keys are in action.
                mTimerHandler.cancelKeyRepeatTimers();
            }
            // Non distinct multitouch screen support
            mNonDistinctMultitouchHelper.processMotionEvent(event, mKeyDetector);
            return true;
        }
        return processMotionEvent(event);
    }

    public boolean processMotionEvent(final MotionEvent event) {
        final int index = event.getActionIndex();
        final int id = event.getPointerId(index);
        final PointerTracker tracker = PointerTracker.getPointerTracker(id);
        // When a more keys panel is showing, we should ignore other fingers' single touch events
        // other than the finger that is showing the more keys panel.
        if (isShowingMoreKeysPanel() && !tracker.isShowingMoreKeysPanel()
                && PointerTracker.getActivePointerTrackerCount() == 1) {
            return true;
        }
        tracker.processMotionEvent(event, mKeyDetector);
        return true;
    }

    public void cancelAllOngoingEvents() {
        mTimerHandler.cancelAllMessages();
        PointerTracker.setReleasedKeyGraphicsToAllKeys();
        PointerTracker.dismissAllMoreKeysPanels();
        PointerTracker.cancelAllPointerTrackers();
    }

    public void closing() {
        cancelAllOngoingEvents();
        mMoreKeysKeyboardCache.clear();
    }

    @Override
    public void deallocateMemory() {
        super.deallocateMemory();
        mMoreKeysKeyboardCache.clear();
        if (mDrawingPreviewPlacerView != null) {
            mDrawingPreviewPlacerView.removeAllViews();
        }
        if (mKeyPreviewChoreographer != null) {
            mKeyPreviewChoreographer.deallocate();
        }
    }

    public void onHideWindow() {
        onDismissMoreKeysPanel();
    }

    public void startDisplayLanguageOnSpacebar(final boolean subtypeChanged,
            final int languageOnSpacebarFormatType) {
        if (subtypeChanged) {
            KeyPreviewView.clearTextCache();
        }
        mLanguageOnSpacebarFormatType = languageOnSpacebarFormatType;
        invalidateKey(mSpaceKey);
    }

    @Override
    protected void onDrawKeyTopVisuals(final Key key, final Canvas canvas, final Paint paint,
            final KeyDrawParams params) {
        if (key.altCodeWhileTyping()) {
            params.mAnimAlpha = mAltCodeKeyWhileTypingAnimAlpha;
        }
        super.onDrawKeyTopVisuals(key, canvas, paint, params);
        if (shouldDrawLanguageOnSpacebar(key)) {
            drawLanguageOnSpacebar(key, canvas, paint);
        }
    }

    private boolean shouldDrawLanguageOnSpacebar(final Key key) {
        if (key.getCode() != Constants.CODE_SPACE) {
            return false;
        }
        return mShouldDrawLanguageOnSpacebar;
    }

    private boolean fitsTextIntoWidth(final int width, final String text, final Paint paint) {
        final int maxTextWidth = width - mLanguageOnSpacebarHorizontalMargin * 2;
        paint.setTextScaleX(1.0f);
        final float textWidth = TypefaceUtils.getStringWidth(text, paint);
        if (textWidth < width) {
            return true;
        }

        final float scaleX = TypefaceUtils.computeScaleX(text, paint, maxTextWidth, 0.0f);
        if (scaleX < MINIMUM_XSCALE_OF_LANGUAGE_NAME) {
            return false;
        }

        paint.setTextScaleX(scaleX);
        return TypefaceUtils.getStringWidth(text, paint) < maxTextWidth;
    }

    // Layout language name on spacebar.
    private String layoutLanguageOnSpacebar(final Paint paint,
                                            final Subtype subtype, final int width) {
        // Choose appropriate language name to fit into the width.
        if (mLanguageOnSpacebarFormatType == LanguageOnSpacebarUtils.FORMAT_TYPE_FULL_LOCALE) {
            final String fullText =
                    LocaleResourceUtils.getLocaleDisplayNameInLocale(subtype.getLocale());
            if (fitsTextIntoWidth(width, fullText, paint)) {
                return fullText;
            }
        }

        final String middleText =
                LocaleResourceUtils.getLanguageDisplayNameInLocale(subtype.getLocale());
        if (fitsTextIntoWidth(width, middleText, paint)) {
            return middleText;
        }

        return "";
    }

    private void drawLanguageOnSpacebar(final Key key, final Canvas canvas, final Paint paint) {
        final Keyboard keyboard = getKeyboard();
        if (keyboard == null) {
            return;
        }
        final int width = key.getWidth();
        final int height = key.getHeight();
        paint.setTextAlign(Align.CENTER);
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(mLanguageOnSpacebarTextSize);
        final String language = layoutLanguageOnSpacebar(paint, keyboard.mId.mSubtype, width);
        // Draw language text with shadow
        final float descent = paint.descent();
        final float textHeight = -paint.ascent() + descent;
        final float baseline = height / 2 + textHeight / 2;
        paint.setColor(mLanguageOnSpacebarTextColor);
        paint.setAlpha(mLanguageOnSpacebarFinalAlpha);
        canvas.drawText(language, width / 2, baseline - descent, paint);
        paint.clearShadowLayer();
        paint.setTextScaleX(1.0f);
    }
}
