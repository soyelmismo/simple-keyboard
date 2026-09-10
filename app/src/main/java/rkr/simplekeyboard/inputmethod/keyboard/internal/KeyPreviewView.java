/*
 * Copyright (C) 2014 The Android Open Source Project
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

package rkr.simplekeyboard.inputmethod.keyboard.internal;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import androidx.appcompat.widget.AppCompatTextView;

import java.util.HashSet;

import rkr.simplekeyboard.inputmethod.keyboard.Key;
import rkr.simplekeyboard.inputmethod.latin.utils.TypefaceUtils;
import rkr.simplekeyboard.inputmethod.latin.utils.ViewUtils;

/**
 * The pop up key preview view.
 */
public class KeyPreviewView extends AppCompatTextView {
    private final Rect mBackgroundPadding = new Rect();
    private static final HashSet<String> sNoScaleXTextSet = new HashSet<>();
    // Cached last background color applied via setColor() so we skip the filter invalidation
    // when the same value (typically Color.TRANSPARENT) is applied on every press.
    private int mLastBackgroundColor = Color.TRANSPARENT;
    private boolean mHasAppliedColor = false;
    // Cached last corner radius applied to the GradientDrawable background.
    private float mLastCornerRadius = Float.NaN;

    public KeyPreviewView(final Context context, final AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyPreviewView(final Context context, final AttributeSet attrs, final int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setGravity(Gravity.CENTER);
    }

    public void setPreviewVisual(final Key key, final KeyboardIconsSet iconsSet,
            final KeyDrawParams drawParams, final int backgroundColor, final float cornerRadius) {
        // What we show as preview should match what we show on a key top in onDraw().
        final int iconId = key.getIconId();
        if (iconId != KeyboardIconsSet.ICON_UNDEFINED) {
            setCompoundDrawables(null, null, null, key.getPreviewIcon(iconsSet));
            setText(null);
            return;
        }

        setCompoundDrawables(null, null, null, null);
        setTextColor(drawParams.mPreviewTextColor);
        setTextSize(TypedValue.COMPLEX_UNIT_PX, key.selectPreviewTextSize(drawParams));
        setTypeface(key.selectPreviewTypeface(drawParams));
        // TODO Should take care of temporaryShiftLabel here.
        setTextAndScaleX(key.getPreviewLabel());
        setColor(backgroundColor);
        // Only apply the corner-radius change when it actually changed. The drawable's mutate()
        // path invalidates the drawable cache and would otherwise run on every press.
        if (Float.compare(mLastCornerRadius, cornerRadius) != 0) {
            ViewUtils.setGradientCornerRadius(this, cornerRadius);
            mLastCornerRadius = cornerRadius;
        }
    }

    private void setTextAndScaleX(final String text) {
        setTextScaleX(1.0f);
        setText(text);
        if (text == null || sNoScaleXTextSet.contains(text)) {
            return;
        }
        // TODO: Override {@link #setBackground(Drawable)} that is supported from API 16 and
        // calculate maximum text width.
        final Drawable background = getBackground();
        if (background == null) {
            return;
        }
        background.getPadding(mBackgroundPadding);
        final int maxWidth = background.getIntrinsicWidth() - mBackgroundPadding.left
                - mBackgroundPadding.right;
        final float scaleX = TypefaceUtils.computeScaleX(text, getPaint(), maxWidth, 0.0f);
        if (scaleX >= 1.0f) {
            sNoScaleXTextSet.add(text);
            return;
        }
        setTextScaleX(scaleX);
    }

    private void setColor(final int backgroundColor) {
        // All current callers pass Color.TRANSPARENT (see MainKeyboardView#showKeyPreview).
        // We still defend against future use by applying the filter only when the alpha is
        // meaningful AND the color has changed since the last call: avoids a drawable
        // invalidation on every press in the hot path.
        if (Color.alpha(backgroundColor) <= 0) {
            return;
        }
        if (mHasAppliedColor && mLastBackgroundColor == backgroundColor) {
            return;
        }
        final Drawable background = getBackground();
        if (background == null) {
            return;
        }
        background.setColorFilter(backgroundColor, PorterDuff.Mode.OVERLAY);
        mLastBackgroundColor = backgroundColor;
        mHasAppliedColor = true;
    }

    public static void clearTextCache() {
        sNoScaleXTextSet.clear();
    }
}
