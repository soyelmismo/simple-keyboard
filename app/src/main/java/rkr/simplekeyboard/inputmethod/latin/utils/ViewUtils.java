/*
 * Copyright (C) 2026 Raimondas Rimkus
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

package rkr.simplekeyboard.inputmethod.latin.utils;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import rkr.simplekeyboard.inputmethod.R;

public final class ViewUtils {
    private ViewUtils() {
        // Utility class
    }

    public static void applyKeyboardBackground(final View view) {
        if (view == null) return;
        final Drawable bg = getThemeDrawable(view.getContext(), R.attr.keyboardViewStyle, R.style.KeyboardView, android.R.attr.background);
        if (bg != null) {
            view.setBackground(bg);
        }
    }

    public static int getKeyTextColor(final Context context) {
        final TypedValue typedValue = new TypedValue();
        if (context.getTheme().resolveAttribute(R.attr.keyTextColor, typedValue, true) && typedValue.data != 0) {
            return typedValue.data;
        }
        final TypedArray a = context.obtainStyledAttributes(null, new int[]{R.attr.keyTextColor}, R.attr.keyboardViewStyle, R.style.KeyboardView);
        final int color = a.getColor(0, 0xFF37474F);
        a.recycle();
        return color;
    }

    public static int getFunctionalTextColor(final Context context, final int fallbackColor) {
        return getThemeColor(context, R.attr.functionalTextColor, fallbackColor);
    }

    public static View createHorizontalDivider(final Context context, final int color, final float alpha) {
        final View divider = new View(context);
        final int dividerHeight = dpToPx(context, 1);
        divider.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dividerHeight));
        divider.setBackgroundColor(color);
        if (alpha < 1.0f) {
            divider.setAlpha(alpha);
        }
        return divider;
    }

    public static View createVerticalDivider(final Context context, final int heightDp, final int color, final float alpha) {
        final View divider = new View(context);
        final int dividerWidth = dpToPx(context, 1);
        final int dividerHeight = dpToPx(context, heightDp);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dividerWidth, dividerHeight);
        lp.gravity = Gravity.CENTER_VERTICAL;
        divider.setLayoutParams(lp);
        divider.setBackgroundColor(color);
        if (alpha < 1.0f) {
            divider.setAlpha(alpha);
        }
        return divider;
    }

    public static void setGradientCornerRadius(final View view, final float cornerRadius) {
        if (view == null) return;
        final Drawable bg = view.getBackground();
        if (bg instanceof GradientDrawable) {
            final GradientDrawable gd = (GradientDrawable) bg;
            // Cache last-applied radius to avoid mutate()+setCornerRadius() on every press,
            // which would invalidate the drawable and force a re-draw of the view tree.
            if (Float.compare(gd.getCornerRadius(), cornerRadius) != 0) {
                ((GradientDrawable) gd.mutate()).setCornerRadius(cornerRadius);
            }
        }
    }

    public static int dpToPx(final Context context, final float dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, context.getResources().getDisplayMetrics());
    }

    public static int getSelectableItemBackgroundResId(final Context context, final boolean borderless) {
        final TypedValue outValue = new TypedValue();
        if (borderless && context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)) {
            return outValue.resourceId;
        }
        if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)) {
            return outValue.resourceId;
        }
        return 0;
    }

    public static void applySelectableItemBackground(final View view, final boolean borderless) {
        final int resId = getSelectableItemBackgroundResId(view.getContext(), borderless);
        if (resId != 0) {
            view.setBackgroundResource(resId);
        }
    }

    public static ImageView createIconButton(final Context context, final int drawableResId,
            final int widthPx, final int heightPx, final int paddingPx, final boolean borderlessRipple) {
        final ImageView iv = new ImageView(context);
        iv.setImageResource(drawableResId);
        iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        if (paddingPx > 0) {
            iv.setPadding(paddingPx, paddingPx, paddingPx, paddingPx);
        }
        iv.setLayoutParams(new LinearLayout.LayoutParams(widthPx, heightPx));
        iv.setClickable(true);
        iv.setFocusable(false);
        applySelectableItemBackground(iv, borderlessRipple);
        return iv;
    }

    public static ImageView createSquareIconButton(final Context context, final int drawableResId,
            final int sizePx, final int paddingPx, final int colorFilter, final boolean borderlessRipple) {
        final ImageView iv = createIconButton(context, drawableResId, sizePx, sizePx, paddingPx, borderlessRipple);
        if (colorFilter != 0) {
            iv.setColorFilter(colorFilter);
        }
        return iv;
    }

    public static ImageView createBarIconButton(final Context context, final int drawableResId,
            final int widthPx) {
        final ImageView iv = createIconButton(context, drawableResId, widthPx, ViewGroup.LayoutParams.MATCH_PARENT, 0, false);
        iv.setColorFilter(getKeyTextColor(context));
        return iv;
    }

    private static final ThreadLocal<android.util.TypedValue> sTypedValue = new ThreadLocal<android.util.TypedValue>() {
        @Override
        protected android.util.TypedValue initialValue() {
            return new android.util.TypedValue();
        }
    };

    public static int getThemeColor(final Context context, final int attrResId, final int defaultColor) {
        final android.util.TypedValue typedValue = sTypedValue.get();
        if (context.getTheme().resolveAttribute(attrResId, typedValue, true)) {
            return typedValue.data;
        }
        return defaultColor;
    }

    public static Drawable getThemeDrawable(final Context context, final int styleAttr,
            final int defStyleRes, final int attr) {
        final TypedArray a = context.obtainStyledAttributes(null, new int[]{attr}, styleAttr, defStyleRes);
        final Drawable d = a.getDrawable(0);
        a.recycle();
        return d;
    }
}
